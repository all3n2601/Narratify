package app.narratify

import android.content.Context

enum class AppSection { LIBRARY, DISCOVER, VOICES, SETTINGS }

enum class ReaderTheme(val label: String) {
    LIGHT("Light"), SEPIA("Sepia"), DARK("Dark"), BLACK("True black");
}

/** Small app-owned preference store. All values are usable offline and applied on the next open reader. */
class AppPreferences(context: Context) {
    private val values = context.applicationContext.getSharedPreferences("narratify-settings-v1", Context.MODE_PRIVATE)

    var readerTheme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(values.getString(KEY_THEME, null) ?: ReaderTheme.SEPIA.name) }
            .getOrDefault(ReaderTheme.SEPIA)
        set(value) = values.edit().putString(KEY_THEME, value.name).apply()

    var fontSize: Int
        get() = values.getInt(KEY_FONT_SIZE, 20).coerceIn(15, 32)
        set(value) = values.edit().putInt(KEY_FONT_SIZE, value.coerceIn(15, 32)).apply()

    var lineSpacing: Int
        get() = values.getInt(KEY_LINE_SPACING, 148).coerceIn(110, 200)
        set(value) = values.edit().putInt(KEY_LINE_SPACING, value.coerceIn(110, 200)).apply()

    var speechRate: Float
        get() = values.getFloat(KEY_SPEECH_RATE, 1f).coerceIn(.5f, 2f)
        set(value) = values.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(.5f, 2f)).apply()

    var highlightWords: Boolean
        get() = values.getBoolean(KEY_HIGHLIGHT, true)
        set(value) = values.edit().putBoolean(KEY_HIGHLIGHT, value).apply()

    var keepScreenAwake: Boolean
        get() = values.getBoolean(KEY_KEEP_AWAKE, false)
        set(value) = values.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /**
     * Whether the landscape reader keeps its outline column open. Portrait always opens the
     * outline as a panel on demand, so it deliberately does not read this.
     */
    var readerOutlineOpen: Boolean
        get() = values.getBoolean(KEY_READER_OUTLINE_OPEN, false)
        set(value) = values.edit().putBoolean(KEY_READER_OUTLINE_OPEN, value).apply()

    var compactLibrary: Boolean
        get() = values.getBoolean(KEY_COMPACT_LIBRARY, false)
        set(value) = values.edit().putBoolean(KEY_COMPACT_LIBRARY, value).apply()

    var sortNewestFirst: Boolean
        get() = values.getBoolean(KEY_SORT_NEWEST, true)
        set(value) = values.edit().putBoolean(KEY_SORT_NEWEST, value).apply()

    /**
     * The reader's explicit engine choice. Settings written before Narratify had a neural voice
     * only stored a system voice name, so those migrate to an equivalent system selection.
     */
    var voiceSelection: VoiceSelection
        get() = values.getString(KEY_VOICE_SELECTION, null)
            ?.let(VoiceSelection::parse)
            ?: VoiceSelection.parseLegacyVoiceId(values.getString(KEY_VOICE, null))
        set(value) = values.edit().putString(KEY_VOICE_SELECTION, value.store()).apply()

    /** System voice to request, or null when any offline platform voice is acceptable. */
    val systemVoiceName: String?
        get() = (voiceSelection as? VoiceSelection.SystemVoice)?.voiceName

    fun reset() {
        values.edit().clear().apply()
    }

    companion object {
        private const val KEY_THEME = "reader-theme"
        private const val KEY_FONT_SIZE = "font-size"
        private const val KEY_LINE_SPACING = "line-spacing"
        private const val KEY_SPEECH_RATE = "speech-rate"
        private const val KEY_HIGHLIGHT = "highlight-words"
        private const val KEY_KEEP_AWAKE = "keep-screen-awake"
        private const val KEY_COMPACT_LIBRARY = "compact-library"
        private const val KEY_READER_OUTLINE_OPEN = "reader-outline-open"
        private const val KEY_SORT_NEWEST = "sort-newest"
        private const val KEY_VOICE = "preferred-voice-id"
        private const val KEY_VOICE_SELECTION = "voice-selection"
    }
}
