package app.narratify

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Streams a registered local neural runtime into AudioTrack and maps sample timing back to source text. */
class AndroidNeuralTtsController(
    context: Context,
    private val publicationId: String,
    private val text: String,
    private val pack: NeuralVoicePack,
    private val runtime: OnDeviceNeuralTtsRuntime,
    private val listener: ReaderTtsController.Listener,
) : ReaderTtsController {
    private data class SourceTiming(val startFrame: Long, val endFrame: Long, val sourceStart: Int, val sourceEnd: Int)

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "neural-tts") }
    private val playback: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "neural-tts-audio") }
    private val player = StreamingPcmPlayer(context.applicationContext) { main.post { pause() } }
    private val generation = AtomicLong()
    private val timingLock = Any()
    private val timings = mutableListOf<SourceTiming>()
    @Volatile private var session: NeuralTtsSession? = null
    @Volatile private var renderQueue: SpeechRenderQueue? = null
    @Volatile private var currentState = ReaderTtsController.State.INITIALIZING
    @Volatile private var currentOffset = 0
    @Volatile private var speed = 1f
    @Volatile private var released = false
    @Volatile private var playbackFinished = false
    private var timingIndex = 0
    private var lastReportedTimingIndex = -1

    init {
        transition(ReaderTtsController.State.INITIALIZING, "Loading ${pack.voiceId} on device…")
        worker.execute {
            val startedNanos = System.nanoTime()
            runCatching { runtime.open(pack) }
                .onSuccess { opened ->
                    if (released) opened.close()
                    else {
                        session = opened
                        val loadMillis = (System.nanoTime() - startedNanos) / 1_000_000
                        TtsDiagnostics.updateEngine { it.copy(modelLoadMillis = loadMillis) }
                        TtsDiagnostics.recordEvent("Model loaded in $loadMillis ms")
                        transition(ReaderTtsController.State.READY, "Neural voice · ${pack.voiceId} · on device")
                    }
                }
                .onFailure { fail("Could not load the installed neural voice", it) }
        }
    }

    override fun play(fromOffset: Int) {
        if (currentState !in setOf(ReaderTtsController.State.READY, ReaderTtsController.State.PAUSED, ReaderTtsController.State.STOPPED)) return
        currentOffset = fromOffset.coerceIn(0, text.length).let { if (it == text.length) 0 else it }
        val run = generation.incrementAndGet()
        transition(ReaderTtsController.State.PLAYING, "Neural voice · ${formatSpeed(speed)} · offline")
        worker.execute {
            cancelPlayback()
            val passages = SpeechPlanner.plan(text, currentOffset, publicationId)
            if (passages.isEmpty()) {
                transition(ReaderTtsController.State.STOPPED, "Nothing to read from this position.")
                return@execute
            }
            val activeSession = session ?: return@execute fail("The neural voice session was lost")
            timingIndex = 0
            lastReportedTimingIndex = -1
            playbackFinished = false
            synchronized(timingLock) { timings.clear() }
            val queue = SpeechRenderQueue(activeSession, LOOKAHEAD_PASSAGES) { passage ->
                TtsDiagnostics.recordUtterance(passage.text.length, passage.tokens.size)
            }
            renderQueue = queue
            queue.start(passages, speed)
            playback.execute { consume(run, queue) }
            main.post(timingPoll)
        }
    }

    /**
     * Plays whatever the render queue has ready. Writing PCM blocks while the track drains, so
     * this loop paces itself against playback while the queue keeps rendering ahead of it.
     */
    private fun consume(run: Long, queue: SpeechRenderQueue) {
        try {
            while (!released && run == generation.get()) {
                val rendered = queue.take() ?: break
                require(rendered.format == pack.pcmFormat) { "Runtime PCM does not match the verified voice manifest" }
                player.start(rendered.format)
                registerTimings(rendered, player.writtenFrames())
                if (rendered.timings.isEmpty()) {
                    dispatchRange(rendered.passage.sourceStart, rendered.passage.sourceEnd)
                }
                player.write(rendered.pcm)
                if (rendered.passage.pauseAfterMillis > 0) {
                    player.write(silencePcm(rendered.format, rendered.passage.pauseAfterMillis))
                }
            }
            if (run == generation.get()) playbackFinished = true
        } catch (error: Throwable) {
            if (run == generation.get() && !released) {
                TtsDiagnostics.recordEvent("Synthesis error: ${error.message}")
                fail(error.message ?: "Neural audio output failed", error)
            }
        }
    }

    /** Word timings arrive in passage-relative samples; playback needs whole-track frames. */
    private fun registerTimings(rendered: RenderedPassage, baseFrame: Long) {
        synchronized(timingLock) {
            rendered.timings.forEach { timing ->
                val token = rendered.passage.tokens.firstOrNull {
                    !it.isPunctuation && timing.spokenStart < it.spokenEnd && timing.spokenEndExclusive > it.spokenStart
                } ?: return@forEach
                timings += SourceTiming(
                    baseFrame + timing.startSample,
                    baseFrame + timing.endSample,
                    token.sourceStart,
                    token.sourceEnd,
                )
            }
        }
    }

    override fun resume() = play(currentOffset)

    override fun pause() {
        if (currentState != ReaderTtsController.State.PLAYING) return
        generation.incrementAndGet()
        player.abort()
        worker.execute { cancelPlayback() }
        transition(ReaderTtsController.State.PAUSED, "Paused")
    }

    override fun stop() {
        generation.incrementAndGet()
        player.abort()
        worker.execute {
            cancelPlayback()
            player.close()
        }
        currentOffset = 0
        transition(ReaderTtsController.State.STOPPED, "Stopped")
    }

    override fun setSpeed(value: Float) {
        speed = value.coerceIn(.5f, 2f)
        TtsDiagnostics.recordEvent("Speed set to ${formatSpeed(speed)}")
        if (currentState == ReaderTtsController.State.PLAYING) {
            val resumeAt = currentOffset
            pause()
            play(resumeAt)
        } else dispatchState("Neural voice at ${formatSpeed(speed)}")
    }

    override fun speed(): Float = speed
    override fun state(): ReaderTtsController.State = currentState

    override fun release() {
        released = true
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        player.abort()
        worker.execute {
            cancelPlayback()
            player.close()
            session?.close()
            session = null
        }
        worker.shutdown()
        playback.shutdownNow()
        currentState = ReaderTtsController.State.STOPPED
    }

    private val timingPoll = object : Runnable {
        override fun run() {
            if (released || currentState != ReaderTtsController.State.PLAYING) return
            val played = player.playedFrames()
            val next = synchronized(timingLock) {
                while (timingIndex < timings.size && timings[timingIndex].endFrame <= played) timingIndex++
                timings.getOrNull(timingIndex)?.takeIf { played >= it.startFrame }
            }
            next?.takeIf { timingIndex != lastReportedTimingIndex }?.let {
                lastReportedTimingIndex = timingIndex
                currentOffset = it.sourceStart
                dispatchRange(it.sourceStart, it.sourceEnd)
            }
            if (playbackFinished && played >= player.writtenFrames()) {
                currentOffset = text.length
                transition(ReaderTtsController.State.STOPPED, "Finished")
                player.close()
            } else main.postDelayed(this, TIMING_POLL_MILLIS)
        }
    }

    private fun cancelPlayback() {
        renderQueue?.close()
        renderQueue = null
        player.abort()
        player.stopTrack()
        main.removeCallbacks(timingPoll)
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (released) return
        generation.incrementAndGet()
        worker.execute { cancelPlayback() }
        transition(ReaderTtsController.State.ERROR, cause?.let { "$message: ${it.message}" } ?: message)
    }

    private fun dispatchRange(start: Int, end: Int) {
        if (!released) main.post {
            if (!released) listener.onSourceRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
        }
    }

    private fun transition(state: ReaderTtsController.State, message: String) {
        currentState = state
        dispatchState(message)
    }

    private fun dispatchState(message: String) {
        val reported = currentState
        if (!released) main.post { if (!released) listener.onState(reported, message) }
    }

    private fun formatSpeed(value: Float): String = "%.2f×".format(Locale.US, value)

    private companion object {
        const val TIMING_POLL_MILLIS = 24L

        /** Finished passages held ahead of playback. Two covers a slow render without unbounded audio. */
        const val LOOKAHEAD_PASSAGES = 2
    }
}
