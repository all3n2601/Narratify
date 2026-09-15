package app.narratify

import app.narratify.shared.text.PreparedTtsChunk
import app.narratify.shared.text.TextPreparationOptions
import app.narratify.shared.text.TtsTextPreparer
import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SpokenTokenFlag
import com.narratify.domain.TextDirection
import com.narratify.domain.TextRange
import java.util.Locale

/** A spoken range and the source range it came from, both half-open. */
internal data class SpokenSourceRange(
    val spokenStart: Int,
    val spokenEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    /** Highlighting a lone comma or full stop looks like a glitch, so these are skipped. */
    val isPunctuation: Boolean = false,
)

/** One unit of speech: the text an engine speaks, and where every spoken word came from. */
internal data class PlannedPassage(
    val text: String,
    val tokens: List<SpokenSourceRange>,
    val sourceStart: Int,
    val sourceEnd: Int,
    /** Silence to play after this passage. A paragraph is a beat, not just another sentence. */
    val pauseAfterMillis: Int = 0,
)

/**
 * Turns document text into speakable passages with source mapping intact. Every narrated surface
 * plans here — the plain-text reader, the EPUB narrator, the system voice, and the neural voice —
 * so "Dr." and "$12.50" are spoken the same way regardless of which engine and format is reading,
 * and so no passage is longer than the neural model's context.
 */
internal object SpeechPlanner {
    const val MAXIMUM_PASSAGE_CHARACTERS = 240

    /**
     * Time to first audio is set by the first passage alone. The desktop streaming spike measured
     * 933 ms for a full-size opening chunk against a 900 ms gate, and 678 ms for a 56-character
     * one (`benchmarks/tts/results/b4-streaming-desktop-spike.json`), so the opening is shortened
     * and every later passage keeps its full size.
     */
    const val OPENING_PASSAGE_CHARACTERS = 56

    /** A reader pauses between paragraphs; sentence-final punctuation alone does not convey it. */
    const val PARAGRAPH_PAUSE_MILLIS = 400

    private val OPTIONS = TextPreparationOptions(
        targetChunkCharacters = 200,
        maximumChunkCharacters = MAXIMUM_PASSAGE_CHARACTERS,
    )

    fun plan(text: String, fromOffset: Int = 0, publicationId: String = "plain-text"): List<PlannedPassage> {
        val requested = fromOffset.coerceIn(0, text.length)
        if (requested == text.length) return emptyList()
        // Speech that starts halfway through a clause has no intonation to build on.
        val offset = TtsTextPreparer.sentenceStart(text, requested)
        val resource = ResourceId("plain-text")
        val span = SourceTextSpan(
            displayText = text.substring(offset),
            locator = PublicationLocator(PublicationId(publicationId), resourceId = resource, resourceIndex = 0),
            semanticRole = SemanticRole.PARAGRAPH,
            language = Locale.getDefault().toLanguageTag().ifBlank { "en-US" },
            direction = TextDirection.AUTO,
            sourceRanges = listOf(SourceRange(resource, TextRange(offset, text.length))),
        )
        val passages = TtsTextPreparer.prepare(listOf(span), OPTIONS).mapNotNull(::toPassage)
        return withParagraphPauses(withShortenedOpening(passages), text)
    }

    /** A blank line between two passages in the source is a paragraph break. */
    private fun withParagraphPauses(passages: List<PlannedPassage>, text: String): List<PlannedPassage> =
        passages.mapIndexed { index, passage ->
            val next = passages.getOrNull(index + 1) ?: return@mapIndexed passage
            val between = text.substring(passage.sourceEnd.coerceAtMost(text.length), next.sourceStart.coerceAtMost(text.length))
            if (between.count { it == '\n' } >= 2) passage.copy(pauseAfterMillis = PARAGRAPH_PAUSE_MILLIS) else passage
        }

    private fun withShortenedOpening(passages: List<PlannedPassage>): List<PlannedPassage> {
        val opening = passages.firstOrNull() ?: return passages
        if (opening.text.length <= OPENING_PASSAGE_CHARACTERS) return passages
        val split = splitAfterToken(opening, lastTokenWithinOpeningBudget(opening) ?: return passages)
        return listOf(split.first, split.second) + passages.drop(1)
    }

    /** The last whole token that fits the opening budget, with any punctuation that trails it. */
    private fun lastTokenWithinOpeningBudget(passage: PlannedPassage): Int? {
        var index = passage.tokens.indexOfLast { it.spokenEnd <= OPENING_PASSAGE_CHARACTERS }
        if (index < 0 || index == passage.tokens.lastIndex) return null
        while (index + 1 < passage.tokens.lastIndex && isPunctuation(passage, index + 1)) index++
        return index
    }

    private fun isPunctuation(passage: PlannedPassage, index: Int): Boolean =
        passage.text.substring(passage.tokens[index].spokenStart, passage.tokens[index].spokenEnd)
            .none(Char::isLetterOrDigit)

    private fun splitAfterToken(passage: PlannedPassage, index: Int): Pair<PlannedPassage, PlannedPassage> {
        val headTokens = passage.tokens.take(index + 1)
        val tailTokens = passage.tokens.drop(index + 1)
        val tailStart = tailTokens.first().spokenStart
        val head = PlannedPassage(
            text = passage.text.substring(0, headTokens.last().spokenEnd),
            tokens = headTokens,
            sourceStart = headTokens.first().sourceStart,
            sourceEnd = headTokens.last().sourceEnd,
        )
        val tail = PlannedPassage(
            text = passage.text.substring(tailStart),
            tokens = tailTokens.map { it.copy(spokenStart = it.spokenStart - tailStart, spokenEnd = it.spokenEnd - tailStart) },
            sourceStart = tailTokens.first().sourceStart,
            sourceEnd = tailTokens.last().sourceEnd,
        )
        return head to tail
    }

    private fun toPassage(chunk: PreparedTtsChunk): PlannedPassage? {
        var cursor = 0
        val tokens = chunk.tokens.mapNotNull { token ->
            val spokenStart = chunk.speechText.indexOf(token.spokenText, cursor)
            if (spokenStart < 0) return@mapNotNull null
            cursor = spokenStart + token.spokenText.length
            val ranges = token.sourceRanges
            val first = ranges.firstOrNull()?.range ?: return@mapNotNull null
            SpokenSourceRange(
                spokenStart = spokenStart,
                spokenEnd = cursor,
                sourceStart = first.start,
                sourceEnd = ranges.last().range.endExclusive,
                isPunctuation = SpokenTokenFlag.PUNCTUATION in token.flags,
            )
        }
        if (tokens.isEmpty()) return null
        return PlannedPassage(
            text = chunk.speechText,
            tokens = tokens,
            sourceStart = tokens.first().sourceStart,
            sourceEnd = tokens.last().sourceEnd,
        )
    }
}
