package app.narratify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.File
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * Kokoro v1.0 mobile adapter. ONNX Runtime is bundled with the app; downloaded packs contain
 * the model, voice style, and CMU pronunciation data. Registration still requires an exact,
 * app-owned legal approval record, so merely installing files can never activate this backend.
 */
object KokoroOnnxRuntime : OnDeviceNeuralTtsRuntime {
    override val runtimeId: String = "narratify-kokoro-onnx"

    /** Registers every catalog pack the app is licensed to ship. Nothing else can be activated. */
    fun registerIfApproved(catalog: List<NeuralVoicePackCatalogEntry>) {
        val approvals = catalog
            .filter { it.runtimeBundled && it.canDownload }
            .mapNotNull { it.approval }
            .toSet()
        if (approvals.isNotEmpty()) NeuralTtsRuntimeRegistry.register(this, approvals)
    }

    override fun supports(pack: NeuralVoicePack): Boolean =
        pack.kind == NeuralPackKind.VOICE &&
            pack.runtimeId == runtimeId &&
            pack.modelId == "kokoro-82m-v1.0-fp32-duration" &&
            pack.pcmFormat.sampleRateHz == SAMPLE_RATE &&
            pack.pcmFormat.channelCount == 1 &&
            pack.assets.any { it.relativePath == voiceFileName(pack) } &&
            pack.dependency?.assets.orEmpty().let { model ->
                model.any { it.relativePath == MODEL_FILE } && model.any { it.relativePath == DICTIONARY_FILE }
            }

    /**
     * Every phase of the open is timed and reported to [TtsDiagnostics], because "read aloud"
     * latency is dominated by this call and the only way to know which phase to attack is to
     * measure them separately on a real device.
     */
    override fun open(pack: NeuralVoicePack): NeuralTtsSession {
        require(supports(pack)) { "Unsupported Kokoro voice pack" }
        val openStart = System.nanoTime()
        NeuralVoicePackVerifier.verify(pack)
        val verifyMillis = millisSince(openStart)
        val session = KokoroOnnxSession(
            model = pack.file(MODEL_FILE),
            voice = pack.file(voiceFileName(pack)),
            dictionary = pack.file(DICTIONARY_FILE),
        )
        TtsDiagnostics.recordSessionOpen(
            session.openPhases.copy(totalMillis = millisSince(openStart), verifyMillis = verifyMillis)
        )
        return session
    }

    private fun voiceFileName(pack: NeuralVoicePack) = "${pack.voiceId}.bin"

    private const val MODEL_FILE = "kokoro-v1.0.onnx"
    private const val DICTIONARY_FILE = "cmudict.dict"
    private const val SAMPLE_RATE = 24_000
}

private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

private inline fun <T> timed(record: (Long) -> Unit, block: () -> T): T {
    val start = System.nanoTime()
    return block().also { record(millisSince(start)) }
}

internal fun interface ArpabetEncoder {
    fun encode(word: String, context: WordContext): List<String>
}

/** Read-only CMUdict data. Pronunciation choices live in [EnglishArpabetEncoder]. */
internal class CmuDictionaryEncoder private constructor(
    private val entries: Map<String, List<List<String>>>,
) {
    /** Every pronunciation the dictionary lists for [word], in file order. */
    fun variants(word: String): List<List<String>> = entries[normalizeKey(word)].orEmpty()

    companion object {
        private fun normalizeKey(word: String): String = word.lowercase().replace('\u2019', '\'').trim('\'', '-', '_')

        /** Letter names, the deterministic last resort for anything nothing else can read. */
        fun spell(word: String): List<String> = word.flatMap { LETTER_NAMES[it].orEmpty() }

        fun load(reader: Reader): CmuDictionaryEncoder {
            val entries = HashMap<String, MutableList<List<String>>>(140_000)
            BufferedReader(reader).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith(";;;")) return@forEach
                    val separator = line.indexOfFirst(Char::isWhitespace)
                    if (separator <= 0) return@forEach
                    val key = line.substring(0, separator).substringBefore('(').lowercase()
                    entries.getOrPut(key) { mutableListOf() } += line.substring(separator + 1).trim().split(Regex("\\s+"))
                }
            }
            return CmuDictionaryEncoder(entries)
        }

        private val LETTER_NAMES = mapOf(
            'a' to listOf("EY1"), 'b' to listOf("B", "IY1"), 'c' to listOf("S", "IY1"),
            'd' to listOf("D", "IY1"), 'e' to listOf("IY1"), 'f' to listOf("EH1", "F"),
            'g' to listOf("JH", "IY1"), 'h' to listOf("EY1", "CH"), 'i' to listOf("AY1"),
            'j' to listOf("JH", "EY1"), 'k' to listOf("K", "EY1"), 'l' to listOf("EH1", "L"),
            'm' to listOf("EH1", "M"), 'n' to listOf("EH1", "N"), 'o' to listOf("OW1"),
            'p' to listOf("P", "IY1"), 'q' to listOf("K", "Y", "UW1"), 'r' to listOf("AA1", "R"),
            's' to listOf("EH1", "S"), 't' to listOf("T", "IY1"), 'u' to listOf("Y", "UW1"),
            'v' to listOf("V", "IY1"), 'w' to listOf("D", "AH1", "B", "AH0", "L", "Y", "UW0"),
            'x' to listOf("EH1", "K", "S"), 'y' to listOf("W", "AY1"), 'z' to listOf("Z", "IY1"),
            '0' to listOf("Z", "IH1", "R", "OW0"), '1' to listOf("W", "AH1", "N"),
            '2' to listOf("T", "UW1"), '3' to listOf("TH", "R", "IY1"),
            '4' to listOf("F", "AO1", "R"), '5' to listOf("F", "AY1", "V"),
            '6' to listOf("S", "IH1", "K", "S"), '7' to listOf("S", "EH1", "V", "AH0", "N"),
            '8' to listOf("EY1", "T"), '9' to listOf("N", "AY1", "N"),
        )
    }
}

/**
 * Raised instead of dropping the tail of a passage. The model context is fixed, so a caller that
 * hands over more text than fits has a chunking defect; silently speaking part of a sentence would
 * hide it from both the reader and the highlighter.
 */
internal class PassageTooLongForModel(val limit: Int) :
    IllegalArgumentException("This passage needs more than the $limit tokens the voice model can hold")

internal data class KokoroPhonemes(
    val tokenIds: LongArray,
    val tokenSourceRanges: List<IntRange?>,
)

/** Preserves the UTF-16 source range that generated every model token. */
internal class KokoroEnglishPhonemizer(
    private val encoder: ArpabetEncoder,
) {
    fun encode(text: String): KokoroPhonemes {
        val ids = ArrayList<Long>()
        val ranges = ArrayList<IntRange?>()
        var needSpace = false
        val matches = TOKEN.findAll(text).toList()
        val words = matches.map { match -> match.value.takeIf { isWord(it) } }

        matches.forEachIndexed { position, match ->
            val raw = match.value
            val source = match.range
            val punctuation = raw.singleOrNull()?.let(VOCAB::get)
            val context = WordContext(
                previous = words.take(position).lastOrNull { it != null },
                next = words.drop(position + 1).firstOrNull { it != null },
                followedByPunctuation = matches.getOrNull(position + 1)?.value?.let { !isWord(it) } ?: true,
            )
            val phonemes = if (punctuation != null) raw else arpabetToKokoro(encoder.encode(raw.lowercase(), context))
            if (phonemes.isBlank()) return@forEachIndexed

            if (needSpace && punctuation == null) {
                ids += VOCAB.getValue(' ').toLong()
                ranges += null
            }
            phonemes.forEach { symbol ->
                val token = VOCAB[symbol] ?: return@forEach
                ids += token.toLong()
                ranges += source
            }
            if (ids.size > MAX_TOKENS) throw PassageTooLongForModel(MAX_TOKENS)
            needSpace = punctuation == null
        }
        return KokoroPhonemes(ids.toLongArray(), ranges)
    }

    private fun isWord(raw: String): Boolean = raw.singleOrNull()?.let(VOCAB::get) == null

    private fun arpabetToKokoro(phones: List<String>): String = buildString {
        phones.forEach { raw ->
            val match = PHONE.matchEntire(raw.uppercase()) ?: return@forEach
            val phone = match.groupValues[1]
            val stress = match.groupValues[2]
            val ipa = ARPABET[phone] ?: return@forEach
            if (stress == "1") append('ˈ') else if (stress == "2") append('ˌ')
            append(REDUCED[phone.takeIf { stress == "0" }] ?: ipa)
        }
    }

    companion object {
        private const val MAX_TOKENS = 510
        private val TOKEN = Regex("[\\p{L}\\p{N}'’_-]+|[;:,.!?—…\\\"()“”]")
        private val PHONE = Regex("([A-Z]+)([012]?)")
        /** Unstressed syllables reduce; keeping the full vowel is what makes speech sound recited. */
        private val REDUCED = mapOf("AH" to "ə", "ER" to "ɚ")
        private val ARPABET = mapOf(
            "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "W",
            "AY" to "I", "B" to "b", "CH" to "ʧ", "D" to "d", "DH" to "ð",
            "EH" to "ɛ", "ER" to "ɜɹ", "EY" to "A", "F" to "f", "G" to "ɡ",
            "HH" to "h", "IH" to "ɪ", "IY" to "i", "JH" to "ʤ", "K" to "k",
            "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "OW" to "O",
            "OY" to "Y", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ",
            "T" to "t", "TH" to "θ", "UH" to "ʊ", "UW" to "u", "V" to "v",
            "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ",
        )
        private val VOCAB = mapOf(
            ';' to 1, ':' to 2, ',' to 3, '.' to 4, '!' to 5, '?' to 6, '—' to 9,
            '…' to 10, '"' to 11, '(' to 12, ')' to 13, '“' to 14, '”' to 15, ' ' to 16,
            'A' to 24, 'I' to 25, 'O' to 31, 'Q' to 33, 'S' to 35, 'T' to 36,
            'W' to 39, 'Y' to 41, 'ᵊ' to 42, 'a' to 43, 'b' to 44, 'c' to 45,
            'd' to 46, 'e' to 47, 'f' to 48, 'h' to 50, 'i' to 51, 'j' to 52,
            'k' to 53, 'l' to 54, 'm' to 55, 'n' to 56, 'o' to 57, 'p' to 58,
            'q' to 59, 'r' to 60, 's' to 61, 't' to 62, 'u' to 63, 'v' to 64,
            'w' to 65, 'x' to 66, 'y' to 67, 'z' to 68, 'ɑ' to 69, 'ɐ' to 70,
            'ɒ' to 71, 'æ' to 72, 'β' to 75, 'ɔ' to 76, 'ɕ' to 77, 'ç' to 78,
            'ɖ' to 80, 'ð' to 81, 'ʤ' to 82, 'ə' to 83, 'ɚ' to 85, 'ɛ' to 86,
            'ɜ' to 87, 'ɟ' to 90, 'ɡ' to 92, 'ɥ' to 99, 'ɨ' to 101, 'ɪ' to 102,
            'ʝ' to 103, 'ɯ' to 110, 'ɰ' to 111, 'ŋ' to 112, 'ɳ' to 113, 'ɲ' to 114,
            'ɴ' to 115, 'ø' to 116, 'ɸ' to 118, 'θ' to 119, 'œ' to 120, 'ɹ' to 123,
            'ɾ' to 125, 'ɻ' to 126, 'ʁ' to 128, 'ɽ' to 129, 'ʂ' to 130, 'ʃ' to 131,
            'ʈ' to 132, 'ʧ' to 133, 'ʊ' to 135, 'ʋ' to 136, 'ʌ' to 138, 'ɣ' to 139,
            'ɤ' to 140, 'χ' to 142, 'ʎ' to 143, 'ʒ' to 147, 'ʔ' to 148, 'ˈ' to 156,
            'ˌ' to 157, 'ː' to 158, 'ʰ' to 162, 'ʲ' to 164, '↓' to 169, '→' to 171,
            '↗' to 172, '↘' to 173, 'ᵻ' to 177,
        )
    }
}

private class KokoroOnnxSession(
    model: File,
    voice: File,
    dictionary: File,
) : NeuralTtsSession {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session: OrtSession
    private val voiceStyles: FloatArray
    private val phonemizer: KokoroEnglishPhonemizer
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kokoro-onnx") }
    private val closed = AtomicBoolean(false)

    /**
     * Per-phase cost of building this session. [TtsSessionOpenBreakdown.totalMillis] and
     * [TtsSessionOpenBreakdown.verifyMillis] are filled in by the runtime, which owns those steps.
     */
    val openPhases: TtsSessionOpenBreakdown

    init {
        var graphMillis = 0L
        var voiceStyleMillis = 0L
        var dictionaryMillis = 0L
        session = timed({ graphMillis = it }) { environment.createSession(model.absolutePath, sessionOptions) }
        voiceStyles = timed({ voiceStyleMillis = it }) { loadVoiceStyles(voice) }
        phonemizer = timed({ dictionaryMillis = it }) {
            KokoroEnglishPhonemizer(EnglishArpabetEncoder(CmuDictionaryEncoder.load(dictionary.reader())))
        }
        var warmupMillis = 0L
        timed({ warmupMillis = it }) { warmUp() }
        openPhases = TtsSessionOpenBreakdown(
            totalMillis = 0,
            verifyMillis = 0,
            graphMillis = graphMillis,
            voiceStyleMillis = voiceStyleMillis,
            dictionaryMillis = dictionaryMillis,
            warmupMillis = warmupMillis,
        )
    }

    /**
     * One throwaway inference so the first real passage does not pay the first-inference penalty:
     * ONNX graph initialisation, arena allocation, and cold caches. The PCM is discarded, no
     * callback is delivered, and nothing is recorded as an utterance, so this is invisible to
     * consumers and to diagnostics apart from its own timing. A failure here is not fatal — the
     * session is still usable, and the first real utterance would report the real error anyway.
     *
     * It runs on the session's own inference thread, not the opening thread, so the allocator and
     * caches that get warmed are the ones every later utterance will use. Waiting for it keeps
     * the cost inside [open], which every caller already performs off the main thread.
     */
    private fun warmUp() {
        runCatching {
            executor.submit(Callable { infer(NeuralSynthesisRequest(WARMUP_TEXT, rate = 1f), AtomicBoolean(false)) }).get()
        }
    }

    override fun synthesize(request: NeuralSynthesisRequest, callback: NeuralSynthesisCallback): NeuralSynthesisJob {
        val cancelled = AtomicBoolean(false)
        executor.execute {
            if (closed.get() || cancelled.get()) return@execute
            runCatching { infer(request, cancelled) }
                .onSuccess { result ->
                    if (cancelled.get() || closed.get()) return@onSuccess
                    callback.onMetadata(PCM_FORMAT, result.timings)
                    var offset = 0
                    while (offset < result.pcm.size && !cancelled.get() && !closed.get()) {
                        val end = (offset + PCM_CHUNK_BYTES).coerceAtMost(result.pcm.size)
                        callback.onPcm(result.pcm.copyOfRange(offset, end))
                        offset = end
                    }
                    if (!cancelled.get() && !closed.get()) callback.onComplete()
                }
                .onFailure { error ->
                    if (!cancelled.get() && !closed.get()) {
                        callback.onError(error.message ?: "Kokoro synthesis failed", error)
                    }
                }
        }
        return NeuralSynthesisJob { cancelled.set(true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        session.close()
        sessionOptions.close()
    }

    private fun infer(request: NeuralSynthesisRequest, cancelled: AtomicBoolean): Result {
        val encoded = phonemizer.encode(request.speechText)
        require(encoded.tokenIds.isNotEmpty()) { "No pronounceable English text" }
        if (cancelled.get()) throw InterruptedException("Kokoro synthesis cancelled")

        val padded = LongArray(encoded.tokenIds.size + 2)
        encoded.tokenIds.copyInto(padded, destinationOffset = 1)
        val style = voiceStyle(encoded.tokenIds.size)
        val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(padded), longArrayOf(1, padded.size.toLong()))
        val styleTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong()))
        val speedTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(request.rate)), longArrayOf(1))
        tokenTensor.use { tokens ->
            styleTensor.use { voice ->
                speedTensor.use { speed ->
                    session.run(mapOf("input_ids" to tokens, "style" to voice, "speed" to speed)).use { output ->
                        val audio = (output[0] as OnnxTensor).floatBuffer.copyToArray()
                        val durations = (output[1] as OnnxTensor).longBuffer.copyToArray()
                        return Result(pcm16(audio), wordTimings(encoded, durations, audio.size))
                    }
                }
            }
        }
    }

    private fun voiceStyle(tokenCount: Int): FloatArray {
        val row = tokenCount.coerceIn(1, VOICE_ROWS) - 1
        return voiceStyles.copyOfRange(row * STYLE_SIZE, (row + 1) * STYLE_SIZE)
    }

    private fun wordTimings(encoded: KokoroPhonemes, durations: LongArray, sampleCount: Int): List<NeuralWordTiming> {
        if (durations.size < encoded.tokenIds.size + 2) return emptyList()
        val total = durations.sum().coerceAtLeast(1)
        var elapsed = durations[0]
        val timings = ArrayList<NeuralWordTiming>()
        encoded.tokenSourceRanges.forEachIndexed { index, range ->
            val start = (elapsed.toDouble() * sampleCount / total).roundToLong()
            elapsed += durations[index + 1]
            val end = (elapsed.toDouble() * sampleCount / total).roundToLong()
            if (range == null) return@forEachIndexed
            val previous = timings.lastOrNull()
            if (previous != null && previous.spokenStart == range.first && previous.spokenEndExclusive == range.last + 1) {
                timings[timings.lastIndex] = previous.copy(endSample = end)
            } else {
                timings += NeuralWordTiming(range.first, range.last + 1, start, end)
            }
        }
        return timings
    }

    private data class Result(val pcm: ByteArray, val timings: List<NeuralWordTiming>)

    companion object {
        private const val VOICE_ROWS = 510
        private const val STYLE_SIZE = 256

        /** Two syllables: enough to run every graph node once, short enough to cost almost nothing. */
        private const val WARMUP_TEXT = "Hi"
        private const val PCM_CHUNK_BYTES = 256 * 1024
        private val PCM_FORMAT = com.narratify.domain.PcmFormat(
            sampleRateHz = 24_000,
            channelCount = 1,
            encoding = com.narratify.domain.PcmEncoding.SIGNED_INT_16_LE,
        )

        private fun loadVoiceStyles(file: File): FloatArray {
            require(file.length() == (VOICE_ROWS * STYLE_SIZE * Float.SIZE_BYTES).toLong()) {
                "Kokoro voice style has an unexpected size"
            }
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also(buffer::get)
        }

        private fun pcm16(samples: FloatArray): ByteArray {
            val output = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { sample ->
                output.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
            return output.array()
        }

        private fun FloatBuffer.copyToArray(): FloatArray =
            duplicate().let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }

        private fun LongBuffer.copyToArray(): LongArray =
            duplicate().let { buffer -> LongArray(buffer.remaining()).also(buffer::get) }
    }
}
