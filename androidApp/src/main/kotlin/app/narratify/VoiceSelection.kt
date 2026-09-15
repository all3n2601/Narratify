package app.narratify

/**
 * What the reader picked in Voices. Stored as a short string so an unknown or removed voice can
 * always degrade to [Automatic] instead of leaving narration unavailable.
 */
sealed interface VoiceSelection {
    fun store(): String

    data object Automatic : VoiceSelection {
        override fun store(): String = "auto"
    }

    data class SystemVoice(val voiceName: String) : VoiceSelection {
        override fun store(): String = "$SYSTEM_PREFIX$voiceName"
    }

    data class NeuralVoice(val packId: String) : VoiceSelection {
        override fun store(): String = "$NEURAL_PREFIX$packId"
    }

    companion object {
        private const val SYSTEM_PREFIX = "system:"
        private const val NEURAL_PREFIX = "neural:"

        fun parse(raw: String?): VoiceSelection = when {
            raw.isNullOrBlank() -> Automatic
            raw.startsWith(SYSTEM_PREFIX) -> raw.removePrefix(SYSTEM_PREFIX).takeIf(String::isNotBlank)
                ?.let(::SystemVoice) ?: Automatic
            raw.startsWith(NEURAL_PREFIX) -> raw.removePrefix(NEURAL_PREFIX).takeIf(String::isNotBlank)
                ?.let(::NeuralVoice) ?: Automatic
            else -> Automatic
        }

        /** Settings written before Narratify had a neural voice stored a bare system voice name. */
        fun parseLegacyVoiceId(voiceId: String?): VoiceSelection =
            voiceId?.takeIf(String::isNotBlank)?.let(::SystemVoice) ?: Automatic
    }
}

/** The engine decision, kept as data so both readers and the debug page agree on the reason. */
data class ResolvedVoice(
    val neuralPack: NeuralVoicePack?,
    val systemVoiceName: String?,
    val reason: String,
)

/**
 * Voice routing lives here rather than inside a controller so the choice is testable and the
 * debug page can explain, in words, why a given engine is speaking.
 */
object VoiceRouter {
    fun resolve(
        selection: VoiceSelection,
        installedNeuralPacks: List<NeuralVoicePack>,
        rank: (String) -> Int = NarratifyNeuralVoiceCatalog::preferenceRank,
    ): ResolvedVoice = when (selection) {
        is VoiceSelection.NeuralVoice ->
            installedNeuralPacks.firstOrNull { it.packId == selection.packId }
                ?.let { ResolvedVoice(it, null, "You chose this downloaded neural voice in Voices") }
                ?: ResolvedVoice(null, null, "The chosen neural voice is not installed — using a system voice")

        is VoiceSelection.SystemVoice ->
            ResolvedVoice(null, selection.voiceName, "You chose this system voice in Voices")

        VoiceSelection.Automatic ->
            installedNeuralPacks.minByOrNull { rank(it.packId) }
                ?.let { ResolvedVoice(it, null, "Automatic: the downloaded neural voice ${it.voiceId} is installed") }
                ?: ResolvedVoice(null, null, "Automatic: no neural voice is installed, using system speech")
    }
}
