package app.narratify

import com.narratify.domain.PcmFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class SpeechRenderFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A frame-aligned block of silence, used to give a paragraph break its beat. */
internal fun silencePcm(format: PcmFormat, millis: Int): ByteArray {
    val frames = format.sampleRateHz.toLong() * millis / 1_000
    return ByteArray((frames * 2 * format.channelCount).toInt())
}

internal data class RenderedPassage(
    val passage: PlannedPassage,
    val format: PcmFormat,
    val pcm: ByteArray,
    val timings: List<NeuralWordTiming>,
)

/**
 * Renders passages ahead of playback so a reader never hears the synthesizer think between
 * sentences, while holding no more than [depth] finished passages plus the one being rendered.
 * The bound holds by construction — the producer blocks on a full queue — rather than being
 * inferred from memory use.
 */
internal class SpeechRenderQueue(
    private val session: NeuralTtsSession,
    private val depth: Int = 2,
    private val recorderFor: (PlannedPassage) -> TtsDiagnostics.UtteranceRecorder? = { null },
) : Closeable {
    private sealed interface Item {
        data class Ready(val rendered: RenderedPassage) : Item
        data class Failed(val message: String, val cause: Throwable?) : Item
        data object End : Item
    }

    private val queue = ArrayBlockingQueue<Item>(depth)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var producer: Thread? = null
    @Volatile private var job: NeuralSynthesisJob? = null

    init {
        require(depth >= 1) { "A render queue needs room for at least one passage" }
    }

    fun start(passages: List<PlannedPassage>, rate: Float) {
        check(producer == null) { "This render queue has already started" }
        val thread = Thread({ render(passages, rate) }, "speech-render")
        thread.isDaemon = true
        producer = thread
        thread.start()
    }

    /** Blocks until the next passage is rendered. Null once the passages run out or on cancel. */
    fun take(): RenderedPassage? {
        if (cancelled.get()) return null
        return when (val item = runCatching { queue.take() }.getOrNull()) {
            null, Item.End -> null
            is Item.Failed -> throw SpeechRenderFailure(item.message, item.cause)
            is Item.Ready -> item.rendered
        }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        job?.cancel()
        producer?.interrupt()
        queue.clear()
        queue.offer(Item.End)
    }

    override fun close() {
        cancel()
        producer = null
    }

    private fun render(passages: List<PlannedPassage>, rate: Float) {
        try {
            for (passage in passages) {
                if (cancelled.get()) return
                val item = renderOne(passage, rate)
                if (cancelled.get()) return
                queue.put(item)
                if (item is Item.Failed) return
            }
            queue.put(Item.End)
        } catch (_: InterruptedException) {
            // Cancellation has already published the terminal item.
        }
    }

    /** Waits for one whole passage, because the runtime reports its word timings up front. */
    private fun renderOne(passage: PlannedPassage, rate: Float): Item {
        val collector = Collector(recorderFor(passage))
        job = session.synthesize(NeuralSynthesisRequest(passage.text, rate), collector)
        collector.await()
        job = null

        collector.failure?.let { return Item.Failed(it.first, it.second) }
        val format = collector.format ?: return Item.Failed("The voice returned no audio format", null)
        val pcm = collector.audio()
        if (pcm.isEmpty()) return Item.Failed("The voice produced no audio for this passage", null)
        collector.recorder?.complete(format.sampleRateHz, format.channelCount)
        return Item.Ready(RenderedPassage(passage, format, pcm, collector.timings))
    }

    /** Accumulates one passage's audio; the runtime may call back from any thread. */
    private class Collector(val recorder: TtsDiagnostics.UtteranceRecorder?) : NeuralSynthesisCallback {
        private val pcm = ByteArrayOutputStream()
        private val finished = CountDownLatch(1)

        @Volatile var format: PcmFormat? = null
            private set

        @Volatile var timings: List<NeuralWordTiming> = emptyList()
            private set

        @Volatile var failure: Pair<String, Throwable?>? = null
            private set

        override fun onMetadata(format: PcmFormat, timings: List<NeuralWordTiming>) {
            recorder?.firstAudio()
            this.format = format
            this.timings = timings
        }

        override fun onPcm(bytes: ByteArray) {
            recorder?.pcm(bytes.size)
            synchronized(pcm) { pcm.write(bytes) }
        }

        override fun onComplete() = finished.countDown()

        override fun onError(message: String, cause: Throwable?) {
            failure = message to cause
            finished.countDown()
        }

        fun await() = finished.await()

        fun audio(): ByteArray = synchronized(pcm) { pcm.toByteArray() }
    }
}
