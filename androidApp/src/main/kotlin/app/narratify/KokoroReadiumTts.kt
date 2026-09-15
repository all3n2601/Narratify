package app.narratify

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.narratify.domain.PcmFormat
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/**
 * Speed is the only Kokoro knob Narratify exposes today; language comes from the installed pack,
 * which is English-only.
 */
@OptIn(ExperimentalReadiumApi::class)
data class KokoroTtsPreferences(
    override val language: Language? = null,
    val speed: Double? = null,
) : TtsEngine.Preferences<KokoroTtsPreferences> {
    init {
        require(speed == null || speed > 0)
    }

    override fun plus(other: KokoroTtsPreferences): KokoroTtsPreferences =
        KokoroTtsPreferences(language = other.language ?: language, speed = other.speed ?: speed)
}

@OptIn(ExperimentalReadiumApi::class)
data class KokoroTtsSettings(
    override val language: Language?,
    override val overrideContentLanguage: Boolean,
    val speed: Double,
) : TtsEngine.Settings

@OptIn(ExperimentalReadiumApi::class)
class KokoroTtsPreferencesEditor(
    initialPreferences: KokoroTtsPreferences,
) : PreferencesEditor<KokoroTtsPreferences> {
    override var preferences: KokoroTtsPreferences = initialPreferences
        private set

    override fun clear() {
        preferences = KokoroTtsPreferences()
    }
}

/**
 * Bridges the verified on-device neural pack into Readium's TTS navigator, so an EPUB is narrated
 * by exactly the same runtime, pack, and word timings as the plain-text reader. Readium drives one
 * utterance at a time, so a single synthesis job and PCM stream are enough.
 */
@OptIn(ExperimentalReadiumApi::class)
class KokoroTtsEngine(
    context: Context,
    private val pack: NeuralVoicePack,
    private val runtime: OnDeviceNeuralTtsRuntime,
    initialPreferences: KokoroTtsPreferences,
) : TtsEngine<KokoroTtsSettings, KokoroTtsPreferences, KokoroTtsEngine.Error, KokoroTtsEngine.Voice> {

    sealed class Error(override val message: String) : TtsEngine.Error {
        override val cause: org.readium.r2.shared.util.Error? = null

        data object EngineUnavailable : Error("The neural voice could not be loaded")
        class Synthesis(message: String) : Error(message)
    }

    data class Voice(override val language: Language, val id: String) : TtsEngine.Voice

    private data class Timing(val startFrame: Long, val endFrame: Long, val range: IntRange)

    private val applicationContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kokoro-epub-tts") }
    private val playback = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kokoro-epub-audio") }
    private val player = StreamingPcmPlayer(applicationContext, manageAudioFocus = false) { stop() }
    private val timings = mutableListOf<Timing>()
    private val timingLock = Any()

    private val settingsState = MutableStateFlow(resolve(initialPreferences))
    private var session: NeuralTtsSession? = null
    private var listener: TtsEngine.Listener<Error>? = null
    @Volatile private var renderQueue: SpeechRenderQueue? = null
    @Volatile private var activeRequest: TtsEngine.RequestId? = null
    @Volatile private var playbackFinished = false
    @Volatile private var closed = false
    private var timingIndex = 0

    override val settings: StateFlow<KokoroTtsSettings> = settingsState.asStateFlow()

    override val voices: Set<Voice> = pack.languageTags
        .map { Voice(Language(it), pack.voiceId) }
        .toSet()

    override fun submitPreferences(preferences: KokoroTtsPreferences) {
        settingsState.value = resolve(preferences)
    }

    override fun setListener(listener: TtsEngine.Listener<Error>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        if (closed) return
        activeRequest = requestId
        playbackFinished = false
        timingIndex = 0
        synchronized(timingLock) { timings.clear() }
        worker.execute {
            val active = openSession() ?: return@execute reportError(requestId, Error.EngineUnavailable)
            // The same preparation the plain-text reader uses, so an EPUB says "doctor" and
            // "twelve dollars and fifty cents" too, and no passage overruns the model context.
            val passages = SpeechPlanner.plan(text)
            if (passages.isEmpty()) {
                main.post { if (activeRequest == requestId) listener?.onDone(requestId) }
                return@execute
            }
            val queue = SpeechRenderQueue(active, LOOKAHEAD_PASSAGES) { passage ->
                TtsDiagnostics.recordUtterance(passage.text.length, passage.tokens.size)
            }
            renderQueue = queue
            queue.start(passages, settingsState.value.speed.toFloat())
            playback.execute { consume(requestId, queue) }
        }
    }

    /** Renders ahead of playback so a chapter does not pause between its sentences. */
    private fun consume(requestId: TtsEngine.RequestId, queue: SpeechRenderQueue) {
        var announced = false
        try {
            while (!closed && activeRequest == requestId) {
                val rendered = queue.take() ?: break
                require(rendered.format == pack.pcmFormat) { "Runtime PCM does not match the verified pack" }
                player.start(rendered.format)
                registerTimings(rendered, player.writtenFrames())
                if (!announced) {
                    announced = true
                    main.post {
                        if (activeRequest == requestId) {
                            listener?.onStart(requestId)
                            main.post(rangePoll)
                        }
                    }
                }
                player.write(rendered.pcm)
                if (rendered.passage.pauseAfterMillis > 0) {
                    player.write(silencePcm(rendered.format, rendered.passage.pauseAfterMillis))
                }
            }
            if (activeRequest == requestId) playbackFinished = true
        } catch (error: Throwable) {
            TtsDiagnostics.recordEvent("EPUB synthesis error: ${error.message}")
            reportError(requestId, Error.Synthesis(error.message ?: "Audio output failed"))
        }
    }

    private fun registerTimings(rendered: RenderedPassage, baseFrame: Long) {
        synchronized(timingLock) {
            rendered.timings.forEach { timing ->
                val token = rendered.passage.tokens.firstOrNull {
                    !it.isPunctuation && timing.spokenStart < it.spokenEnd && timing.spokenEndExclusive > it.spokenStart
                } ?: return@forEach
                timings += Timing(
                    baseFrame + timing.startSample,
                    baseFrame + timing.endSample,
                    token.sourceStart until token.sourceEnd,
                )
            }
        }
    }

    override fun stop() {
        val interrupted = activeRequest
        activeRequest = null
        renderQueue?.close()
        renderQueue = null
        main.removeCallbacks(rangePoll)
        // Readium stops between utterances, so focus is kept until the engine itself closes.
        player.abort()
        player.stopTrack()
        if (interrupted != null && !closed) main.post { listener?.onInterrupted(interrupted) }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        player.close()
        worker.execute {
            session?.close()
            session = null
        }
        worker.shutdown()
        playback.shutdownNow()
    }

    /** Word highlighting follows the audio actually played, not the audio already generated. */
    private val rangePoll = object : Runnable {
        override fun run() {
            val requestId = activeRequest ?: return
            val played = player.playedFrames()
            val next = synchronized(timingLock) {
                while (timingIndex < timings.size && timings[timingIndex].endFrame <= played) timingIndex++
                timings.getOrNull(timingIndex)?.takeIf { played >= it.startFrame }
            }
            next?.let { listener?.onRange(requestId, it.range) }
            if (playbackFinished && played >= player.writtenFrames()) {
                activeRequest = null
                player.stopTrack()
                listener?.onDone(requestId)
            } else {
                main.postDelayed(this, RANGE_POLL_MILLIS)
            }
        }
    }

    private fun openSession(): NeuralTtsSession? = session ?: runCatching { runtime.open(pack) }
        .onSuccess { opened ->
            session = opened
            TtsDiagnostics.recordEvent("EPUB narration opened ${pack.voiceId}")
        }
        .getOrElse {
            TtsDiagnostics.recordEvent("EPUB narration could not open the pack: ${it.message}")
            null
        }

    private fun reportError(requestId: TtsEngine.RequestId, error: Error) {
        if (closed) return
        main.post {
            if (activeRequest == requestId) activeRequest = null
            listener?.onError(requestId, error)
        }
    }

    private fun resolve(preferences: KokoroTtsPreferences) = KokoroTtsSettings(
        language = preferences.language ?: pack.languageTags.firstOrNull()?.let(::Language),
        overrideContentLanguage = false,
        speed = (preferences.speed ?: 1.0).coerceIn(0.5, 2.0),
    )

    private companion object {
        const val RANGE_POLL_MILLIS = 24L

        /** Rendered passages held ahead of playback within one utterance. */
        const val LOOKAHEAD_PASSAGES = 2
    }
}

@OptIn(ExperimentalReadiumApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KokoroTtsEngineProvider(
    private val context: Context,
    private val pack: NeuralVoicePack,
    private val runtime: OnDeviceNeuralTtsRuntime,
) : TtsEngineProvider<
    KokoroTtsSettings,
    KokoroTtsPreferences,
    KokoroTtsPreferencesEditor,
    KokoroTtsEngine.Error,
    KokoroTtsEngine.Voice,
    > {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: KokoroTtsPreferences,
    ): Try<TtsEngine<KokoroTtsSettings, KokoroTtsPreferences, KokoroTtsEngine.Error, KokoroTtsEngine.Voice>, org.readium.r2.shared.util.Error> =
        Try.success(KokoroTtsEngine(context, pack, runtime, initialPreferences))

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: KokoroTtsPreferences,
    ): KokoroTtsPreferencesEditor = KokoroTtsPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences(): KokoroTtsPreferences = KokoroTtsPreferences()

    override fun getPlaybackParameters(settings: KokoroTtsSettings): PlaybackParameters =
        PlaybackParameters(settings.speed.toFloat())

    override fun updatePlaybackParameters(
        previousPreferences: KokoroTtsPreferences,
        playbackParameters: PlaybackParameters,
    ): KokoroTtsPreferences = previousPreferences.copy(speed = playbackParameters.speed.toDouble())

    override fun mapEngineError(error: KokoroTtsEngine.Error): PlaybackException =
        PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
}
