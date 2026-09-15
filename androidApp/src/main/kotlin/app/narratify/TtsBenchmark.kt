package app.narratify

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.narratify.domain.PcmFormat
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A fixed passage so numbers from different runs, devices, and engines are comparable. Three
 * sentences of increasing length exercise short-utterance latency and sustained throughput.
 */
object TtsBenchmarkText {
    val sentences = listOf(
        "Narratify reads privately, right on your device.",
        "The quick brown fox jumps over the lazy dog while the narrator keeps a steady pace.",
        "Neural narration streams audio in blocks, so the first words should begin long before the " +
            "whole passage has been generated, even on a mid-range phone.",
    )

    val characters: Int get() = sentences.sumOf { it.length }
}

/** Parses only what the debug page needs: how long a synthesized WAV actually plays. */
internal object WavInspector {
    data class Info(val sampleRateHz: Int, val channelCount: Int, val bitsPerSample: Int, val dataBytes: Long) {
        val durationMillis: Long
            get() {
                val frameBytes = channelCount * (bitsPerSample / 8)
                if (frameBytes <= 0 || sampleRateHz <= 0) return 0
                return dataBytes / frameBytes * 1_000 / sampleRateHz
            }
    }

    fun inspect(bytes: ByteArray): Info? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readInt(bytes, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    channels = readShort(bytes, body + 2)
                    sampleRate = readInt(bytes, body + 4)
                    bits = readShort(bytes, body + 14)
                }
                "data" -> {
                    val available = (bytes.size - body).toLong()
                    val dataBytes = if (size in 1..available) size.toLong() else available
                    return if (sampleRate > 0 && channels > 0 && bits > 0) {
                        Info(sampleRate, channels, bits, dataBytes)
                    } else null
                }
            }
            if (size <= 0) return null
            offset = body + size + (size % 2)
        }
        return null
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0
        else (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or ((bytes[at + 3].toInt() and 0xff) shl 24)

    private fun readShort(bytes: ByteArray, at: Int): Int =
        if (at + 2 > bytes.size) 0 else (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
}

/**
 * Runs the benchmark passage through whichever engine is installed and feeds the results into
 * [TtsDiagnostics]. Nothing is played: the neural path counts PCM without touching AudioTrack and
 * the system path synthesizes to a cache file, so the page can be measured in a quiet room.
 */
object TtsBenchmark {
    class BenchmarkException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Blocking. Callers run this off the main thread. */
    fun run(context: Context, selection: VoiceSelection): String {
        val applicationContext = context.applicationContext
        val installed = runCatching {
            NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
        }.getOrElse { emptyList() }
        val resolved = VoiceRouter.resolve(selection, installed.map { it.first })
        val runtime = resolved.neuralPack?.let { pack ->
            installed.firstOrNull { it.first.packId == pack.packId }?.second
        }
        return if (resolved.neuralPack != null && runtime != null) {
            runNeural(resolved.neuralPack, runtime, resolved.reason)
        } else {
            runSystem(applicationContext, resolved.systemVoiceName, resolved.reason)
        }
    }

    private fun runNeural(pack: NeuralVoicePack, runtime: OnDeviceNeuralTtsRuntime, reason: String): String {
        TtsDiagnostics.beginSession(
            TtsEngineSnapshot(
                kind = TtsEngineKind.NEURAL,
                engineLabel = "Kokoro · ONNX Runtime",
                voiceLabel = pack.voiceId,
                selectionReason = "Benchmark · $reason",
                packId = pack.packId,
                packVersion = pack.packVersion,
                runtimeId = pack.runtimeId,
                modelId = pack.modelId,
                modelVersion = pack.modelVersion,
                voiceId = pack.voiceId,
                voiceVersion = pack.voiceVersion,
                licenseSpdxId = pack.licenseSpdxId,
                attribution = pack.attribution,
                languageTags = pack.languageTags,
                sampleRateHz = pack.pcmFormat.sampleRateHz,
                channelCount = pack.pcmFormat.channelCount,
                encoding = pack.pcmFormat.encoding.name,
                packDirectory = pack.directory.absolutePath,
                assets = pack.assets,
            )
        )
        val loadStart = System.nanoTime()
        val session = runCatching { runtime.open(pack) }
            .getOrElse { throw BenchmarkException("The neural runtime could not open this pack", it) }
        val loadMillis = (System.nanoTime() - loadStart) / 1_000_000
        TtsDiagnostics.updateEngine { it.copy(modelLoadMillis = loadMillis) }
        TtsDiagnostics.recordEvent("Benchmark: model loaded in $loadMillis ms")

        session.use {
            TtsBenchmarkText.sentences.forEach { sentence ->
                val recorder = TtsDiagnostics.recordUtterance(sentence.length, sentence.split(' ').size)
                val latch = CountDownLatch(1)
                var failure: String? = null
                session.synthesize(NeuralSynthesisRequest(sentence, rate = 1f), object : NeuralSynthesisCallback {
                    override fun onMetadata(format: PcmFormat, timings: List<NeuralWordTiming>) = recorder.firstAudio()
                    override fun onPcm(bytes: ByteArray) = recorder.pcm(bytes.size)
                    override fun onComplete() {
                        recorder.complete(pack.pcmFormat.sampleRateHz, pack.pcmFormat.channelCount)
                        latch.countDown()
                    }

                    override fun onError(message: String, cause: Throwable?) {
                        failure = message
                        latch.countDown()
                    }
                })
                if (!latch.await(BENCHMARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw BenchmarkException("The neural runtime did not finish within $BENCHMARK_TIMEOUT_SECONDS s")
                }
                failure?.let { throw BenchmarkException(it) }
            }
        }
        TtsDiagnostics.recordEvent("Benchmark finished · ${TtsBenchmarkText.sentences.size} utterances")
        return "Kokoro benchmark finished"
    }

    private fun runSystem(context: Context, systemVoiceName: String?, reason: String): String {
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            initStatus = status
            ready.countDown()
        }
        if (!ready.await(BENCHMARK_TIMEOUT_SECONDS, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            engine.shutdown()
            throw BenchmarkException("The system speech engine did not start")
        }
        try {
            val voice = engine.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.let { voices ->
                    voices.firstOrNull { it.name == systemVoiceName }
                        ?: voices.firstOrNull { it.locale.language == Locale.getDefault().language }
                        ?: voices.firstOrNull()
                }
                ?: throw BenchmarkException("This device has no offline system voice installed")
            engine.setVoice(voice)
            TtsDiagnostics.beginSession(
                TtsEngineSnapshot(
                    kind = TtsEngineKind.SYSTEM,
                    engineLabel = "Android system TTS · ${engine.defaultEngine ?: "default engine"}",
                    voiceLabel = voice.name,
                    selectionReason = "Benchmark · $reason",
                    languageTags = setOf(voice.locale.toLanguageTag()),
                )
            )
            val directory = File(context.cacheDir, "tts-benchmark").apply { mkdirs() }
            TtsBenchmarkText.sentences.forEachIndexed { index, sentence ->
                val output = File(directory, "benchmark-$index.wav")
                val done = CountDownLatch(1)
                var failed = false
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit
                    override fun onDone(utteranceId: String) = done.countDown()

                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String) {
                        failed = true
                        done.countDown()
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        failed = true
                        done.countDown()
                    }
                })
                val recorder = TtsDiagnostics.recordUtterance(sentence.length, sentence.split(' ').size)
                val queued = engine.synthesizeToFile(sentence, Bundle(), output, "benchmark-$index")
                if (queued == TextToSpeech.ERROR) throw BenchmarkException("The system engine rejected the passage")
                if (!done.await(BENCHMARK_TIMEOUT_SECONDS, TimeUnit.SECONDS) || failed) {
                    throw BenchmarkException("System synthesis failed for sentence ${index + 1}")
                }
                recorder.firstAudio()
                val info = runCatching { WavInspector.inspect(output.readBytes()) }.getOrNull()
                recorder.pcm(info?.dataBytes?.toInt() ?: 0)
                recorder.completeWithAudioMillis(info?.durationMillis ?: 0)
                output.delete()
            }
            TtsDiagnostics.recordEvent("Benchmark finished · ${TtsBenchmarkText.sentences.size} utterances")
            return "System voice benchmark finished"
        } finally {
            engine.shutdown()
        }
    }

    private const val BENCHMARK_TIMEOUT_SECONDS = 30L
}
