package app.narratify

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

/**
 * Owns EPUB narration for as long as it is speaking, rather than for as long as the reader screen
 * is on top. The reader attaches to whatever is already running, the mini player and the library
 * player column can see it, and [NarrationPlaybackService] keeps it alive in the background with
 * a media notification.
 *
 * The holder opens its own copy of the publication so narration never depends on the lifetime of
 * the rendering navigator.
 */
@OptIn(ExperimentalReadiumApi::class)
object EpubNarration {
    data class Session(
        val bookId: String,
        val title: String,
        val playing: Boolean,
        val message: String,
    )

    private val lock = Any()
    private val observers = CopyOnWriteArrayList<(Session?) -> Unit>()
    private var scope: CoroutineScope? = null
    private var playbackJob: Job? = null
    private var publication: Publication? = null
    private var navigator: TtsNavigator<*, *, *, *>? = null
    private var submitSpeed: ((Double) -> Unit)? = null
    private var bookId: String? = null
    private var title: String = ""
    private var engineLabel: String = ""
    private var session: Session? = null

    fun session(): Session? = synchronized(lock) { session }

    fun currentBook(): Pair<String, String>? = synchronized(lock) {
        val id = bookId ?: return@synchronized null
        id to title
    }

    fun isActive(bookId: String): Boolean = synchronized(lock) { this.bookId == bookId && navigator != null }

    fun navigator(): TtsNavigator<*, *, *, *>? = synchronized(lock) { navigator }

    /** The media3 player behind the navigator, handed to the playback service's session. */
    fun mediaPlayer(): Player? = synchronized(lock) { navigator }?.asMedia3Player()

    fun observe(observer: (Session?) -> Unit) {
        observers += observer
        observer(session())
    }

    fun stopObserving(observer: (Session?) -> Unit) {
        observers -= observer
    }

    /**
     * Starts narrating [bookId], or does nothing when that book is already being narrated. Runs on
     * the caller's coroutine; publication opening happens on IO.
     */
    suspend fun start(
        context: Context,
        bookId: String,
        title: String,
        initialLocator: Locator?,
        preferences: AppPreferences,
    ): Result<Unit> {
        if (isActive(bookId)) {
            play()
            return Result.success(Unit)
        }
        release()
        val applicationContext = context.applicationContext
        rememberContext(applicationContext)
        val opened = withContext(Dispatchers.IO) {
            runCatching { LocalLibraryRepository(applicationContext).openEpub(bookId) }
        }.getOrElse { return Result.failure(it) }

        val installed = withContext(Dispatchers.IO) {
            runCatching {
                NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
            }.getOrElse { emptyList() }
        }
        val resolved = VoiceRouter.resolve(preferences.voiceSelection, installed.map { it.first })
        val neuralRuntime = resolved.neuralPack?.let { pack ->
            installed.firstOrNull { it.first.packId == pack.packId }?.second
        }
        val created = if (resolved.neuralPack != null && neuralRuntime != null) {
            startNeural(applicationContext, opened.publication, resolved, resolved.neuralPack, neuralRuntime, initialLocator, preferences)
        } else {
            startSystem(applicationContext, opened.publication, resolved, initialLocator, preferences)
        }
        return created.fold(
            onSuccess = {
                synchronized(lock) {
                    publication = opened.publication
                    this.bookId = bookId
                    this.title = title
                }
                follow()
                play()
                applicationContext.startService(Intent(applicationContext, NarrationPlaybackService::class.java))
                Result.success(Unit)
            },
            onFailure = { failure ->
                withContext(Dispatchers.IO) { runCatching { opened.publication.close() } }
                Result.failure(failure)
            },
        )
    }

    private suspend fun startNeural(
        context: Context,
        publication: Publication,
        resolved: ResolvedVoice,
        pack: NeuralVoicePack,
        runtime: OnDeviceNeuralTtsRuntime,
        initialLocator: Locator?,
        preferences: AppPreferences,
    ): Result<Unit> {
        val factory = TtsNavigatorFactory(
            context.applicationContext as android.app.Application,
            publication,
            KokoroTtsEngineProvider(context, pack, runtime),
        ) ?: return Result.failure(IllegalStateException("This EPUB does not expose readable text for narration."))
        TtsDiagnostics.beginSession(neuralSnapshot(pack, resolved))
        val created = factory.createNavigator(
            listener = stopListener,
            initialLocator = initialLocator,
            initialPreferences = KokoroTtsPreferences(speed = preferences.speechRate.toDouble()),
        ).getOrElse { return Result.failure(IllegalStateException(it.message)) }
        synchronized(lock) {
            navigator = created
            submitSpeed = { speed -> created.submitPreferences(KokoroTtsPreferences(speed = speed)) }
            engineLabel = "Neural voice"
        }
        return Result.success(Unit)
    }

    private suspend fun startSystem(
        context: Context,
        publication: Publication,
        resolved: ResolvedVoice,
        initialLocator: Locator?,
        preferences: AppPreferences,
    ): Result<Unit> {
        val preferredVoiceId = resolved.systemVoiceName
        val factory = TtsNavigatorFactory(
            context.applicationContext as android.app.Application,
            publication,
            voiceSelector = { language, voices ->
                voices.firstOrNull { it.id.value == preferredVoiceId && !it.requiresNetwork }
                    ?: voices.firstOrNull { it.language == language && !it.requiresNetwork }
                    ?: voices.firstOrNull { !it.requiresNetwork }
            },
        ) ?: return Result.failure(IllegalStateException("This EPUB does not expose readable text for narration."))
        TtsDiagnostics.beginSession(
            TtsEngineSnapshot(
                kind = TtsEngineKind.SYSTEM,
                engineLabel = "Android system TTS · EPUB",
                voiceLabel = preferredVoiceId ?: "Platform default offline voice",
                selectionReason = resolved.reason,
                languageTags = setOf(Locale.getDefault().toLanguageTag()),
            )
        )
        val created = factory.createNavigator(
            listener = stopListener,
            initialLocator = initialLocator,
            initialPreferences = AndroidTtsPreferences(speed = preferences.speechRate.toDouble()),
        ).getOrElse { return Result.failure(IllegalStateException(it.message)) }
        synchronized(lock) {
            navigator = created
            submitSpeed = { speed -> created.submitPreferences(AndroidTtsPreferences(speed = speed)) }
            engineLabel = "System voice"
        }
        return Result.success(Unit)
    }

    private fun neuralSnapshot(pack: NeuralVoicePack, resolved: ResolvedVoice) = TtsEngineSnapshot(
        kind = TtsEngineKind.NEURAL,
        engineLabel = "Kokoro · ONNX Runtime · EPUB",
        voiceLabel = pack.voiceId,
        selectionReason = resolved.reason,
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

    private val stopListener = object : TtsNavigator.Listener {
        override fun onStopRequested() = stop()
    }

    /** Mirrors the navigator's playback into a snapshot the UI can read without Readium types. */
    private fun follow() {
        val active = navigator() ?: return
        val narrationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        synchronized(lock) { scope = narrationScope }
        playbackJob = narrationScope.launch {
            active.playback.collect { playback ->
                val snapshot = Session(
                    bookId = bookId.orEmpty(),
                    title = title,
                    playing = playback.playWhenReady,
                    message = when (val state = playback.state) {
                        is TtsNavigator.State.Failure -> state.error.message
                        TtsNavigator.State.Ended -> "Finished"
                        else -> if (playback.playWhenReady) engineLabel else "Paused"
                    },
                )
                synchronized(lock) { session = snapshot }
                observers.forEach { it(snapshot) }
                if (playback.state is TtsNavigator.State.Ended) stop()
            }
        }
    }

    fun play() {
        navigator()?.play()
    }

    fun pause() {
        navigator()?.pause()
    }

    fun skipToNext() {
        navigator()?.skipToNextUtterance()
    }

    fun skipToPrevious() {
        navigator()?.skipToPreviousUtterance()
    }

    fun setSpeed(speed: Double) {
        synchronized(lock) { submitSpeed }?.invoke(speed)
    }

    /** Ends narration and lets the service stop; the reader screen may stay open. */
    fun stop() {
        val context = serviceContext
        release()
        context?.stopService(Intent(context, NarrationPlaybackService::class.java))
    }

    private var serviceContext: Context? = null

    fun rememberContext(context: Context) {
        serviceContext = context.applicationContext
    }

    fun release() {
        val (closing, closingPublication, closingScope) = synchronized(lock) {
            val values = Triple(navigator, publication, scope)
            navigator = null
            publication = null
            scope = null
            submitSpeed = null
            bookId = null
            title = ""
            session = null
            values
        }
        playbackJob?.cancel()
        playbackJob = null
        closingScope?.cancel()
        closing?.close()
        runCatching { closingPublication?.close() }
        observers.forEach { it(null) }
    }
}
