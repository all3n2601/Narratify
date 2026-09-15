package app.narratify.shared.text

import com.narratify.domain.PronunciationSource
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SpokenToken
import com.narratify.domain.SpokenTokenFlag
import com.narratify.domain.TextRange
import com.narratify.domain.TextVisibility

object TtsTextPreparer {
    /**
     * The start of the sentence containing [offset]. Speech that begins halfway through a clause
     * has no intonation to build on, so resuming, seeking, and reading from a tapped position all
     * snap back here first.
     */
    fun sentenceStart(text: String, offset: Int, language: String = "en-US"): Int {
        val cursor = offset.coerceIn(0, text.length)
        if (cursor == 0) return 0
        val windowStart = (cursor - SENTENCE_LOOKBACK_CHARACTERS).coerceAtLeast(0)
        val window = text.substring(windowStart, cursor)
        val segments = SentenceSegmenter.segment(window, language)
        val last = segments.lastOrNull() ?: return cursor
        // A window that ends on a finished sentence means the cursor already starts the next one.
        if (endsSentence(window.substring(last.start, last.endExclusive))) return cursor
        return windowStart + last.start
    }

    private fun endsSentence(segment: String): Boolean {
        val trimmed = segment.trimEnd(' ', '\t', '\n', '"', '\'', '\u201d', '\u2019', ')', ']', '}')
        return trimmed.lastOrNull() in setOf('.', '!', '?', '\u3002', '\uff01', '\uff1f', '\u2026')
    }

    fun prepare(
        spans: List<SourceTextSpan>,
        options: TextPreparationOptions = TextPreparationOptions(),
    ): List<PreparedTtsChunk> = buildList {
        spans.forEachIndexed { spanIndex, span ->
            if (!span.isSpeakable(options)) return@forEachIndexed
            val language = span.language ?: options.defaultLanguage
            val segments = SentenceSegmenter.segment(span.displayText, language)
                .flatMap { splitLong(it, options.maximumChunkCharacters) }
            val grouped = if (span.semanticRole == SemanticRole.HEADING) {
                segments.map(::listOf)
            } else {
                mergeShort(segments, options)
            }
            grouped.forEachIndexed { groupIndex, group ->
                val start = group.first().start
                val end = group.last().endExclusive
                val raw = span.displayText.substring(start, end)
                val tokens = tokenize(raw, start, span, language)
                if (tokens.isEmpty()) return@forEachIndexed
                val indexed = tokens.mapIndexed { index, token -> token.copy(index = index) }
                add(
                    PreparedTtsChunk(
                        id = stableChunkId(span, spanIndex, groupIndex, start, end),
                        tokens = indexed,
                        speechText = composeSpeech(indexed),
                        language = language,
                        semanticRole = span.semanticRole,
                        sourceStart = span.locator,
                        sourceEnd = span.locator,
                    ),
                )
            }
        }
    }

    private fun SourceTextSpan.isSpeakable(options: TextPreparationOptions): Boolean =
        visibility == TextVisibility.VISIBLE &&
            displayText.isNotBlank() &&
            semanticRole !in setOf(SemanticRole.PAGE_MARKER, SemanticRole.CODE) &&
            (semanticRole != SemanticRole.FOOTNOTE || options.footnotePolicy == FootnotePolicy.INCLUDE_INLINE)

    private fun stableChunkId(span: SourceTextSpan, spanIndex: Int, groupIndex: Int, start: Int, end: Int): String {
        val resource = span.sourceRanges.first().resourceId.value
        return "$resource:$spanIndex:$groupIndex:$start-$end"
    }

    private fun mergeShort(segments: List<Segment>, options: TextPreparationOptions): List<List<Segment>> {
        val result = mutableListOf<MutableList<Segment>>()
        for (segment in segments) {
            val current = result.lastOrNull()
            val combinedLength = if (current == null) Int.MAX_VALUE else segment.endExclusive - current.first().start
            if (current != null &&
                !paragraphBreakBefore(segment, current.last()) &&
                (current.sumOf { it.length } < options.minimumMergeCharacters || segment.length < options.minimumMergeCharacters) &&
                combinedLength <= options.targetChunkCharacters
            ) {
                current += segment
            } else {
                result += mutableListOf(segment)
            }
        }
        return result
    }

    /** A blank line is a paragraph boundary, and a paragraph never shares a chunk with the next. */
    private fun paragraphBreakBefore(segment: Segment, previous: Segment): Boolean =
        segment.source.substring(previous.endExclusive, segment.start).count { it == '\n' } >= 2

    private fun splitLong(segment: Segment, maximum: Int): List<Segment> {
        if (segment.length <= maximum) return listOf(segment)
        val result = mutableListOf<Segment>()
        var start = segment.start
        while (segment.endExclusive - start > maximum) {
            val desired = start + maximum
            var split = -1
            for (priority in listOf(";:—", ",", " \t\n")) {
                var cursor = desired
                val floor = start + maximum / 2
                while (cursor > floor) {
                    if (priority.contains(segment.source[cursor])) {
                        split = cursor + 1
                        break
                    }
                    cursor--
                }
                if (split > start) break
            }
            if (split <= start) split = desired
            result += Segment(segment.source, start, split.trimEnd(segment.source, start))
            start = split.trimStart(segment.source, segment.endExclusive)
        }
        if (start < segment.endExclusive) result += Segment(segment.source, start, segment.endExclusive)
        return result.filter { it.length > 0 }
    }

    private fun Int.trimEnd(text: String, floor: Int): Int {
        var value = this
        while (value > floor && text[value - 1].isWhitespace()) value--
        return value
    }

    private fun Int.trimStart(text: String, ceiling: Int): Int {
        var value = this
        while (value < ceiling && text[value].isWhitespace()) value++
        return value
    }

    private fun tokenize(text: String, sourceOffset: Int, span: SourceTextSpan, language: String): List<SpokenToken> {
        val lexemes = Lexer.lex(text)
        var previousWord: String? = null
        return lexemes.mapNotNull { lexeme ->
            val display = text.substring(lexeme.start, lexeme.endExclusive)
            val normalized = TextNormalizer.normalize(display, language, previousWord)
            if (!lexeme.punctuation) previousWord = display
            if (normalized.isBlank() || normalized.all { isIgnoredSymbol(it) }) return@mapNotNull null
            val flags = buildSet {
                if (span.semanticRole == SemanticRole.HEADING) add(SpokenTokenFlag.HEADING)
                if (span.semanticRole == SemanticRole.FOOTNOTE) add(SpokenTokenFlag.FOOTNOTE)
                if (span.semanticRole == SemanticRole.IMAGE_ALTERNATIVE) add(SpokenTokenFlag.ALTERNATIVE_TEXT)
                if (normalized != display) add(SpokenTokenFlag.SYNTHETIC_EXPANSION)
                if (lexeme.punctuation) add(SpokenTokenFlag.PUNCTUATION)
            }
            SpokenToken(
                index = 0,
                sourceRanges = sliceRanges(span, sourceOffset + lexeme.start, sourceOffset + lexeme.endExclusive),
                displayText = display,
                spokenText = normalized,
                phonemes = emptyList(),
                language = language,
                pronunciationSource = if (normalized != display) PronunciationSource.NORMALIZATION_RULE else PronunciationSource.MODEL_FRONTEND,
                flags = flags,
            )
        }
    }

    private fun isIgnoredSymbol(char: Char): Boolean = char in setOf('©', '®', '™', '✓', '✔', '❌', '✅', '☎') ||
        (char.code in 0xD800..0xDFFF)

    private fun sliceRanges(span: SourceTextSpan, start: Int, end: Int): List<SourceRange> {
        val mappedLength = span.sourceRanges.sumOf { it.range.length }
        if (mappedLength != span.displayText.length) return span.sourceRanges
        val result = mutableListOf<SourceRange>()
        var displayCursor = 0
        for (source in span.sourceRanges) {
            val localStart = maxOf(start, displayCursor)
            val localEnd = minOf(end, displayCursor + source.range.length)
            if (localStart < localEnd) {
                val offset = source.range.start - displayCursor
                result += SourceRange(source.resourceId, TextRange(localStart + offset, localEnd + offset))
            }
            displayCursor += source.range.length
        }
        return result.ifEmpty { span.sourceRanges }
    }

    private fun composeSpeech(tokens: List<SpokenToken>): String = buildString {
        tokens.forEach { token ->
            val punctuation = token.flags.contains(SpokenTokenFlag.PUNCTUATION)
            if (isNotEmpty() && !punctuation && last() !in "([{—") append(' ')
            append(token.spokenText)
        }
    }
}

/** Far enough back to contain any reasonable sentence, short enough to stay cheap. */
private const val SENTENCE_LOOKBACK_CHARACTERS = 2_000

internal data class Segment(val source: String, val start: Int, val endExclusive: Int) {
    val length: Int get() = endExclusive - start
}

internal object SentenceSegmenter {
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e",
        "a.m", "p.m", "no", "fig", "pp",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )
    private val terminalAbbreviations = setOf("etc", "a.m", "p.m")

    fun segment(text: String, language: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<Segment>()
        var start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (char in setOf('。', '！', '？') || (char in setOf('!', '?') && !isRepeatedPunctuation(text, index)) ||
                (char == '.' && isSentencePeriod(text, index, language))
            ) {
                var end = index + 1
                while (end < text.length && text[end] in "\"'”’)]}」』") end++
                result += Segment(text, start, end)
                start = end
                while (start < text.length && text[start].isWhitespace()) start++
                index = start
                continue
            }
            index++
        }
        if (start < text.length) {
            var end = text.length
            while (end > start && text[end - 1].isWhitespace()) end--
            result += Segment(text, start, end)
        }
        return result.filter { it.length > 0 }
    }

    private fun isRepeatedPunctuation(text: String, index: Int): Boolean =
        (index + 1 < text.length && text[index + 1] == text[index])

    private fun isSentencePeriod(text: String, index: Int, language: String): Boolean {
        if (index > 0 && index + 1 < text.length && text[index - 1].isDigit() && text[index + 1].isDigit()) return false
        if (index + 1 < text.length && text[index + 1] == '.') return false
        var wordStart = index - 1
        while (wordStart >= 0 && (text[wordStart].isLetter() || text[wordStart] == '.')) wordStart--
        val word = text.substring(wordStart + 1, index).lowercase()
        var next = index + 1
        // A line break ends a sentence just as a space does; only quotes and brackets are skipped.
        while (next < text.length && (text[next].isWhitespace() || text[next] in "\"'”’)]}")) next++
        if (language.startsWith("en") && word in abbreviations) {
            val canEndSentence = word in terminalAbbreviations &&
                (next >= text.length || text[next].isUpperCase() || text[next] in "“‘([{")
            if (!canEndSentence) return false
        }
        if (language.startsWith("en") && word.length == 1 && word.firstOrNull()?.isLetter() == true) return false
        return next >= text.length || text[next].isUpperCase() || text[next] in "“‘([{"
    }
}

internal data class Lexeme(val start: Int, val endExclusive: Int, val punctuation: Boolean)

internal object Lexer {
    private val currency = setOf('$', '£', '€')
    private val fraction = setOf('¼', '½', '¾', '⅓', '⅔', '⅛', '⅜', '⅝', '⅞')
    private val ORDINAL_SUFFIXES = setOf("st", "nd", "rd", "th")

    fun lex(text: String): List<Lexeme> = buildList {
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) { index++; continue }
            val start = index
            val numeric = text[index].isDigit() || text[index] in currency || text[index] == '−' || text[index] == '-'
            if (numeric && hasNearbyDigit(text, index)) {
                index++
                while (index < text.length && (text[index].isDigit() || text[index] in fraction || isInternalSeparator(text, index) || text[index] == '%')) index++
                index = consumeOrdinalSuffix(text, index)
                add(Lexeme(start, index, false))
            } else if (text[index].isLetterOrDigit() || text[index] in fraction) {
                index++
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "'’.-" || text[index] in fraction)) index++
                while (index > start + 1 && text[index - 1] == '-') index--
                if (index > start + 1 && text[index - 1] == '.' &&
                    !TextNormalizer.isKnownAbbreviation(text.substring(start, index))
                ) index--
                add(Lexeme(start, index, false))
            } else {
                index++
                if (text[start].code !in 0xD800..0xDFFF) add(Lexeme(start, index, true))
            }
        }
    }

    /**
     * A comma, point, or clock colon belongs to the number only when a digit follows it, never
     * sentence-finally.
     */
    private fun isInternalSeparator(text: String, index: Int): Boolean =
        text[index] in ",.:" && index + 1 < text.length && text[index + 1].isDigit()

    /** Keeps "22nd" in one lexeme so it can be spoken as an ordinal rather than "twenty two, N D". */
    private fun consumeOrdinalSuffix(text: String, index: Int): Int {
        if (index < 1 || !text[index - 1].isDigit() || index + 1 >= text.length) return index
        val suffix = text.substring(index, index + 2).lowercase()
        if (suffix !in ORDINAL_SUFFIXES) return index
        val after = index + 2
        if (after < text.length && (text[after].isLetterOrDigit() || text[after] in "'\u2019")) return index
        return after
    }

    private fun hasNearbyDigit(text: String, index: Int): Boolean = text[index].isDigit() ||
        (index + 1 < text.length && text[index + 1].isDigit())
}
