package com.narratify.domain

import kotlinx.serialization.Serializable

@Serializable
data class SourceRange(
    val resourceId: ResourceId,
    val range: TextRange,
)

@Serializable
data class SourceTextSpan(
    val displayText: String,
    val locator: PublicationLocator,
    val semanticRole: SemanticRole = SemanticRole.PARAGRAPH,
    /** A BCP-47 language tag, or null when the publication does not specify one. */
    val language: String? = null,
    val direction: TextDirection = TextDirection.AUTO,
    val visibility: TextVisibility = TextVisibility.VISIBLE,
    val sourceRanges: List<SourceRange>,
) {
    init {
        require(language == null || language.isNotBlank()) { "Language must be null or non-blank" }
        require(sourceRanges.isNotEmpty()) { "A source text span must contain a source range" }
        require(sourceRanges.zipWithNext().all { (a, b) ->
            a.resourceId != b.resourceId || a.range.endExclusive <= b.range.start
        }) { "Source ranges within a resource must be ordered and non-overlapping" }
    }
}

@Serializable
enum class SemanticRole {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    QUOTATION,
    CAPTION,
    FOOTNOTE,
    CODE,
    TABLE,
    PAGE_MARKER,
    IMAGE_ALTERNATIVE,
    OTHER,
}

@Serializable
enum class TextDirection {
    AUTO,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}

@Serializable
enum class TextVisibility {
    VISIBLE,
    HIDDEN,
    NON_LINEAR,
}

@Serializable
data class SpokenToken(
    /** Stable zero-based index within its TTS chunk. */
    val index: Int,
    val sourceRanges: List<SourceRange>,
    val displayText: String,
    val spokenText: String,
    val phonemes: List<String>,
    val language: String,
    val pronunciationSource: PronunciationSource,
    val flags: Set<SpokenTokenFlag> = emptySet(),
) {
    init {
        require(index >= 0) { "Token index must be non-negative" }
        require(sourceRanges.isNotEmpty()) { "A spoken token must retain at least one source range" }
        require(spokenText.isNotBlank()) { "Spoken text must not be blank" }
        require(language.isNotBlank()) { "Language must not be blank" }
        require(phonemes.none(String::isBlank)) { "Phonemes must not contain blank values" }
    }
}

@Serializable
enum class PronunciationSource {
    LEXICON,
    LETTER_TO_SOUND,
    USER_DICTIONARY,
    NORMALIZATION_RULE,
    MODEL_FRONTEND,
    UNKNOWN,
}

@Serializable
enum class SpokenTokenFlag {
    HEADING,
    FOOTNOTE,
    ALTERNATIVE_TEXT,
    SYNTHETIC_EXPANSION,
    PUNCTUATION,
}
