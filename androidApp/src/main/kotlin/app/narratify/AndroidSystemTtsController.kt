package app.narratify

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.narratify.ReaderTtsController.State
import java.util.Locale
import java.util.UUID

/**
 * Offline Android system-TTS baseline for plain-text publications.
 *
 * Android has no native pause operation, so pause stops the engine and resume
 * prepares again from the last reported source word. No audio leaves the device.
 */
class AndroidSystemTtsController(
    context: Context,
    private val publicationId: String,
    private val text: String,
    private val listener: ReaderTtsController.Listener,
    private val preferredVoiceId: String? = null,
) : ReaderTtsController {

    private var engine: TextToSpeech? = null
    private var state = State.INITIALIZING
    private var currentOffset = 0
    private var speed = 1f
    private var generation = ""
    private val utterances = mutableMapOf<String, PlannedPassage>()
    private var finalUtteranceId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorders = java.util.concurrent.ConcurrentHashMap<String, TtsDiagnostics.UtteranceRecorder>()
    private val queuedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val spokenStartNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var released = false

    init {
        listener.onState(State.INITIALIZING, "Preparing offline system voice…")
        engine = TextToSpeech(context.applicationContext) { status ->
            if (released) return@TextToSpeech
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                transition(State.UNAVAILABLE, "Offline system speech is unavailable on this device.")
                return@TextToSpeech
            }
            val preferredLanguage = Locale.getDefault().language
            val availableVoices = tts.voices
                ?.filter { !it.isNetworkConnectionRequired && it.locale.language == preferredLanguage }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.language == Locale.getDefault().language }
                    .thenByDescending { it.quality })
            val offlineVoice = availableVoices?.firstOrNull { it.name == preferredVoiceId }
                ?: availableVoices?.firstOrNull()
            if (offlineVoice == null || tts.setVoice(offlineVoice) == TextToSpeech.ERROR) {
                tts.shutdown()
                engine = null
                transition(State.UNAVAILABLE, "Install an offline Android speech voice to use read aloud.")
                return@TextToSpeech
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            tts.setSpeechRate(speed)
            tts.setOnUtteranceProgressListener(progressListener)
            TtsDiagnostics.updateEngine {
                it.copy(
                    voiceLabel = offlineVoice.name,
                    engineLabel = "Android system TTS · ${tts.defaultEngine ?: "default engine"}",
                    languageTags = setOf(offlineVoice.locale.toLanguageTag()),
                )
            }
            TtsDiagnostics.recordEvent("System voice ready · ${offlineVoice.name} · quality ${offlineVoice.quality}")
            transition(State.READY, "Offline voice · ${offlineVoice.name}")
        }
    }

    override fun play(fromOffset: Int) {
        val tts = engine ?: return
        if (state !in setOf(State.READY, State.PAUSED, State.STOPPED)) return
        currentOffset = fromOffset.coerceIn(0, text.length)
        if (currentOffset == text.length) currentOffset = 0
        val planned = SpeechPlanner.plan(text, currentOffset, publicationId)
        if (planned.isEmpty()) {
            transition(State.STOPPED, "Nothing to read from this position.")
            return
        }
        tts.stop()
        utterances.clear()
        recorders.clear()
        spokenStartNanos.clear()
        queuedIds.clear()
        generation = UUID.randomUUID().toString()
        planned.forEachIndexed { index, item ->
            val id = "$generation:$index"
            utterances[id] = item
            if (index == planned.lastIndex) finalUtteranceId = id
            queuedIds += id
            val result = tts.speak(item.text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (result == TextToSpeech.ERROR) {
                utterances.clear()
                recorders.clear()
                spokenStartNanos.clear()
                transition(State.ERROR, "The offline speech engine rejected this passage.")
                return
            }
        }
        // The whole passage is queued at once, so timing an utterance from here would count the
        // time spent speaking everything before it. Each unit is timed from when it becomes next.
        queuedIds.firstOrNull()?.let { recorders[it] = TtsDiagnostics.recordUtterance(planned.first().text.length, planned.first().tokens.size) }
        transition(State.PLAYING, "Reading aloud at ${formatSpeed(speed)}")
    }

    override fun resume() = play(currentOffset)

    override fun pause() {
        if (state != State.PLAYING) return
        engine?.stop()
        generation = ""
        utterances.clear()
        recorders.clear()
        spokenStartNanos.clear()
        queuedIds.clear()
        transition(State.PAUSED, "Paused")
    }

    override fun stop() {
        engine?.stop()
        generation = ""
        utterances.clear()
        recorders.clear()
        spokenStartNanos.clear()
        queuedIds.clear()
        currentOffset = 0
        transition(State.STOPPED, "Stopped")
    }

    override fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2f)
        engine?.setSpeechRate(speed)
        dispatchState("${if (state == State.PLAYING) "Reading aloud" else "Offline voice"} at ${formatSpeed(speed)}")
        if (state == State.PLAYING) {
            engine?.stop()
            state = State.PAUSED
            play(currentOffset)
        }
    }

    override fun speed(): Float = speed
    override fun state(): ReaderTtsController.State = state

    override fun release() {
        released = true
        generation = ""
        utterances.clear()
        recorders.clear()
        spokenStartNanos.clear()
        queuedIds.clear()
        mainHandler.removeCallbacksAndMessages(null)
        engine?.stop()
        engine?.shutdown()
        engine = null
        state = State.STOPPED
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            recorders[utteranceId]?.firstAudio()
            spokenStartNanos[utteranceId] = System.nanoTime()
            val item = current(utteranceId) ?: return
            currentOffset = item.sourceStart
            dispatchRange(item.sourceStart, item.sourceEnd)
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val item = current(utteranceId) ?: return
            val token = item.tokens.firstOrNull {
                !it.isPunctuation && start < it.spokenEnd && end > it.spokenStart
            } ?: return
            currentOffset = token.sourceStart
            dispatchRange(token.sourceStart, token.sourceEnd)
        }

        override fun onDone(utteranceId: String) {
            finishRecording(utteranceId)
            val item = current(utteranceId) ?: return
            currentOffset = item.sourceEnd
            if (utteranceId == finalUtteranceId) {
                currentOffset = text.length
                transition(State.STOPPED, "Finished")
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            recorders.remove(utteranceId)
            spokenStartNanos.remove(utteranceId)
            queuedIds.remove(utteranceId)
            TtsDiagnostics.recordEvent("System speech error $errorCode")
            if (current(utteranceId) != null) transition(State.ERROR, "Offline speech stopped with error $errorCode.")
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
    }

    /**
     * The platform engine reports no PCM, so audio length is measured as the time it actually
     * spent speaking. Latency stays the gap between queueing and the first spoken word.
     */
    private fun finishRecording(utteranceId: String) {
        val recorder = recorders.remove(utteranceId)
        val started = spokenStartNanos.remove(utteranceId)
        val spokenMillis = started?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0L
        recorder?.completeWithAudioMillis(spokenMillis)
        startRecordingNext(utteranceId)
    }

    /** Begins timing the utterance that the engine is about to speak next. */
    private fun startRecordingNext(finishedId: String) {
        val index = queuedIds.indexOf(finishedId)
        if (index < 0) return
        queuedIds.getOrNull(index + 1)?.let { nextId ->
            val item = utterances[nextId] ?: return
            recorders[nextId] = TtsDiagnostics.recordUtterance(item.text.length, item.tokens.size)
        }
    }

    private fun current(id: String): PlannedPassage? =
        if (generation.isNotEmpty() && id.startsWith(generation)) utterances[id] else null

    private fun dispatchRange(start: Int, end: Int) {
        if (!released) mainHandler.post {
            if (!released) listener.onSourceRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
        }
    }

    private fun transition(newState: State, message: String) {
        state = newState
        dispatchState(message)
    }

    private fun dispatchState(message: String) {
        val reportedState = state
        if (!released) mainHandler.post { if (!released) listener.onState(reportedState, message) }
    }

    private fun formatSpeed(value: Float): String = "%.2f×".format(Locale.US, value)
}
