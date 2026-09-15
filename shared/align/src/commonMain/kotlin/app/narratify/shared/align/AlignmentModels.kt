package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlinx.serialization.Serializable

const val CURRENT_ALIGNMENT_SCHEMA_VERSION: Int = 1

/** One word as a speech recognizer heard it, with the audio it occupied. */
@Serializable
data class AsrToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double = 1.0,
) {
    init {
        require(text.isNotBlank()) { "ASR token text must not be blank" }
        require(startMs >= 0) { "ASR token start must be non-negative" }
        require(endMs >= startMs) { "ASR token must not end before it starts" }
        require(confidence in 0.0..1.0) { "ASR token confidence must be a probability" }
    }
}

/**
 * How much of the text a granularity is honest about.
 *
 * WORD means the share of matched tokens cleared [AlignmentOptions.wordThreshold], so following
 * the highlight word by word is reasonable. It is a claim about the span, not about every token
 * in it: at the default threshold up to about one word in seven may still be interpolated, and
 * [TokenTiming.matched] is the only thing that says which. That flag is not persisted, so a
 * caller reading a stored map can see how much of a span was measured but not precisely where.
 * SENTENCE means only the sentence is trustworthy. CHAPTER means nothing finer than the chapter
 * was established.
 * NONE means the narration and the text did not agree enough to claim anything, which is the
 * correct answer for an abridgement, a different translation, or the wrong book.
 */
@Serializable
enum class AlignmentGranularity { WORD, SENTENCE, CHAPTER, NONE }

/** The predicted audio position of one book token, and whether it was matched or interpolated. */
@Serializable
data class TokenTiming(
    val bookTokenIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val matched: Boolean,
) {
    init {
        require(bookTokenIndex >= 0) { "Book token index must be non-negative" }
        require(startMs >= 0) { "Token start must be non-negative" }
        require(endMs >= startMs) { "Token must not end before it starts" }
    }
}

/** A run of book tokens the reader can be moved to as a unit. */
@Serializable
data class AlignedSpan(
    val bookTokenStart: Int,
    val bookTokenEndExclusive: Int,
    val startMs: Long,
    val endMs: Long,
    val matchedRatio: Double,
    val granularity: AlignmentGranularity,
) {
    init {
        require(bookTokenStart >= 0) { "Span start must be non-negative" }
        require(bookTokenEndExclusive > bookTokenStart) { "A span must cover at least one token" }
        require(startMs >= 0) { "Span start must be non-negative" }
        require(endMs >= startMs) { "Span must not end before it starts" }
        require(matchedRatio in 0.0..1.0) { "Matched ratio must be a proportion" }
    }
}

/** The persisted result for one text resource against one media item. */
@Serializable
data class AlignmentMap(
    val publicationId: PublicationId,
    val mediaItemId: MediaItemId,
    val resourceId: ResourceId,
    val granularity: AlignmentGranularity,
    val spans: List<AlignedSpan>,
    val schemaVersion: Int = CURRENT_ALIGNMENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(
            spans.zipWithNext().all { (earlier, later) ->
                earlier.bookTokenEndExclusive <= later.bookTokenStart &&
                    earlier.startMs <= later.startMs &&
                    earlier.endMs <= later.endMs
            },
        ) { "Spans must move forward in both the book and the audio" }
        require(granularity != AlignmentGranularity.NONE || spans.isEmpty()) {
            "A refused alignment must not carry spans"
        }
        // The map's granularity comes from the whole chapter's matched share, which is the
        // token-weighted mean of its spans'. Because granularity falls monotonically with that
        // share, the summary can sit anywhere between the best and worst span but never outside
        // them. A chapter that is word-accurate overall may still contain a sentence nobody
        // matched, so requiring agreement instead would be wrong.
        require(
            spans.isEmpty() ||
                granularity in spans.minOf { it.granularity }..spans.maxOf { it.granularity },
        ) { "Map granularity must lie between its best and worst span" }
    }
}

/**
 * Where the line sits between a claim and a guess.
 *
 * These are not tuning knobs for making a book look aligned. Lowering them moves text out of
 * "interpolated" and into "exact" without any new evidence, which is the one failure the
 * product cannot recover from: a reader who catches the highlight lying stops trusting it.
 */
@Serializable
data class AlignmentOptions(
    val wordThreshold: Double = 0.85,
    val sentenceThreshold: Double = 0.50,
    val chapterThreshold: Double = 0.20,
) {
    init {
        require(wordThreshold in 0.0..1.0) { "wordThreshold must be a proportion" }
        require(sentenceThreshold in 0.0..wordThreshold) { "sentenceThreshold must not exceed wordThreshold" }
        require(chapterThreshold in 0.0..sentenceThreshold) { "chapterThreshold must not exceed sentenceThreshold" }
    }

    fun granularityFor(matchedRatio: Double): AlignmentGranularity = when {
        matchedRatio >= wordThreshold -> AlignmentGranularity.WORD
        matchedRatio >= sentenceThreshold -> AlignmentGranularity.SENTENCE
        matchedRatio >= chapterThreshold -> AlignmentGranularity.CHAPTER
        else -> AlignmentGranularity.NONE
    }
}

/** Everything one alignment run produced, before anything is persisted. */
data class AlignmentResult(
    val timings: List<TokenTiming>,
    val spans: List<AlignedSpan>,
    val matchedRatio: Double,
    val granularity: AlignmentGranularity,
) {
    init {
        require(matchedRatio in 0.0..1.0) { "Matched ratio must be a proportion" }
        require(timings.withIndex().all { (position, timing) -> timing.bookTokenIndex == position }) {
            "Timings must cover book token indices 0 until n in order"
        }
        require(spans.all { it.bookTokenEndExclusive <= timings.size }) {
            "Spans must not reference token indices beyond the timings list"
        }
    }

    companion object {
        fun refused(bookTokenCount: Int): AlignmentResult = AlignmentResult(
            timings = List(bookTokenCount) { TokenTiming(it, 0L, 0L, matched = false) },
            spans = emptyList(),
            matchedRatio = 0.0,
            granularity = AlignmentGranularity.NONE,
        )
    }
}
