package app.narratify

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Applies the reader's voice choice from Voices. A downloaded neural pack is used only when it
 * is verified, has a registered runtime, and the selection allows it; otherwise system speech
 * runs. Every decision is published to [TtsDiagnostics] so the debug page can explain it.
 */
class AndroidReaderTtsController(
    context: Context,
    private val publicationId: String,
    private val text: String,
    private val listener: ReaderTtsController.Listener,
    private val selection: VoiceSelection = VoiceSelection.Automatic,
) : ReaderTtsController {
    private val applicationContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tts-pack-loader") }
    @Volatile private var delegate: ReaderTtsController? = null
    @Volatile private var released = false
    private var requestedSpeed = 1f
    /** A Read tap that arrives while the engine is still loading is honoured once it is ready. */
    private var pendingPlayOffset: Int? = null

    init {
        listener.onState(ReaderTtsController.State.INITIALIZING, "Checking on-device voices…")
        loader.execute {
            val installed = runCatching {
                NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
            }.getOrElse { emptyList() }
            val resolved = VoiceRouter.resolve(selection, installed.map { it.first })
            main.post {
                if (released) return@post
                val neural = resolved.neuralPack?.let { pack ->
                    installed.firstOrNull { it.first.packId == pack.packId }?.second?.let { pack to it }
                }
                delegate = if (neural != null) {
                    TtsDiagnostics.beginSession(neuralEngineSnapshot(neural.first, resolved.reason))
                    AndroidNeuralTtsController(applicationContext, publicationId, text, neural.first, neural.second, listener)
                } else {
                    TtsDiagnostics.beginSession(
                        TtsEngineSnapshot(
                            kind = TtsEngineKind.SYSTEM,
                            engineLabel = "Android system TTS",
                            voiceLabel = resolved.systemVoiceName ?: "Platform default offline voice",
                            selectionReason = resolved.reason,
                            languageTags = setOf(Locale.getDefault().toLanguageTag()),
                        )
                    )
                    AndroidSystemTtsController(applicationContext, publicationId, text, listener, resolved.systemVoiceName)
                }.also {
                    it.setSpeed(requestedSpeed)
                    pendingPlayOffset?.let { offset ->
                        pendingPlayOffset = null
                        it.play(offset)
                    }
                }
            }
        }
    }

    private fun neuralEngineSnapshot(pack: NeuralVoicePack, reason: String) = TtsEngineSnapshot(
        kind = TtsEngineKind.NEURAL,
        engineLabel = "Kokoro · ONNX Runtime",
        voiceLabel = pack.voiceId,
        selectionReason = reason,
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

    override fun play(fromOffset: Int) {
        val active = delegate
        if (active != null) {
            active.play(fromOffset)
        } else {
            pendingPlayOffset = fromOffset
            listener.onState(ReaderTtsController.State.INITIALIZING, "Preparing the voice — narration starts in a moment…")
        }
    }

    override fun resume() {
        val active = delegate
        if (active != null) active.resume() else play(pendingPlayOffset ?: 0)
    }

    override fun pause() {
        pendingPlayOffset = null
        delegate?.pause()
    }

    override fun stop() {
        pendingPlayOffset = null
        delegate?.stop()
    }
    override fun setSpeed(value: Float) {
        requestedSpeed = value.coerceIn(.5f, 2f)
        delegate?.setSpeed(requestedSpeed)
    }
    override fun speed(): Float = delegate?.speed() ?: requestedSpeed
    override fun state(): ReaderTtsController.State = delegate?.state() ?: ReaderTtsController.State.INITIALIZING

    override fun release() {
        released = true
        pendingPlayOffset = null
        main.removeCallbacksAndMessages(null)
        delegate?.release()
        delegate = null
        loader.shutdownNow()
    }
}
