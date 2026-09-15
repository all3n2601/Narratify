package com.narratify.domain

/**
 * One entry in a book's table of contents. [characterOffset] indexes the same string the reader
 * stores positions against, which differs per platform for Markdown: Android renders Markdown down
 * to readable text before displaying it, iOS displays the source.
 */
data class OutlineItem(
    val title: String,
    val depth: Int,
    val characterOffset: Int,
)

/**
 * Derives an outline for documents that have no authored table of contents. EPUB outlines come
 * from the publication itself and do not pass through here.
 */
object BookOutline {
    /** Longer isolated lines read as prose, not as chapter headings. */
    private const val MAX_HEADING_LENGTH = 60

    /**
     * Plain text has no markup, so a lone match is more likely a coincidence than a structure.
     * Two corroborating lines are the smallest outline worth showing.
     */
    private const val MINIMUM_PLAIN_TEXT_ENTRIES = 2

    private val chapterKeyword = Regex(
        "^(chapter|part|book|section|prologue|epilogue|act)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    /** Roman numerals stay case-sensitive: lowercase words like "civil" are all roman letters. */
    private val bareNumeral = Regex("^([IVXLCDM]+|\\d{1,4})\\.?$")
    private val atxHeading = Regex("^(#{1,6})\\s+\\S.*$")
    private val atxPrefix = Regex("^#{1,6}\\s+")
    private val atxSuffix = Regex("\\s+#+$")
    private val inlineMarkers = Regex("[`*_~]{1,3}")

    /** Chapter-like lines in a plain text document, with offsets into [text]. */
    fun plainText(text: String): List<OutlineItem> {
        val lines = lineSpans(text)
        val items = lines.mapIndexedNotNull { index, span ->
            val trimmed = span.content.trim()
            when {
                trimmed.isEmpty() || trimmed.length > MAX_HEADING_LENGTH -> null
                !isIsolated(lines, index) -> null
                !chapterKeyword.matches(trimmed) && !bareNumeral.matches(trimmed) -> null
                else -> OutlineItem(trimmed, depth = 0, characterOffset = span.textStart())
            }
        }
        return if (items.size < MINIMUM_PLAIN_TEXT_ENTRIES) emptyList() else items
    }

    /**
     * ATX headings in a Markdown document. Headings are recognised in [source] but reported at
     * their position in [readableText], which is what the reader actually displays. The two
     * strings must have the same lines: Markdown rendering rewrites lines without adding or
     * removing any. Callers that display the source unchanged pass it as both arguments.
     *
     * Setext underlines are deliberately not recognised. A `---` line is equally a horizontal
     * rule, and promoting rules into the outline is worse than missing a rare heading style.
     */
    fun markdown(source: String, readableText: String): List<OutlineItem> {
        val sourceLines = lineSpans(source)
        val readableLines = lineSpans(readableText)
        if (sourceLines.size != readableLines.size) return emptyList()

        val items = mutableListOf<OutlineItem>()
        var fenced = false
        sourceLines.forEachIndexed { index, span ->
            val trimmed = span.content.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
                return@forEachIndexed
            }
            if (fenced || !atxHeading.matches(trimmed)) return@forEachIndexed

            val readable = readableLines[index]
            val title = titleOf(readable.content)
            if (title.isEmpty()) return@forEachIndexed
            items += OutlineItem(
                title = title,
                depth = trimmed.takeWhile { it == '#' }.length - 1,
                characterOffset = readable.textStart(),
            )
        }
        return items
    }

    /**
     * Markdown rendering already strips these on Android; iOS reports headings straight from the
     * source. Stripping again here keeps both platforms reporting the same titles.
     */
    private fun titleOf(line: String): String {
        val withoutMarkers = atxSuffix.replace(atxPrefix.replace(line.trim(), ""), "")
        return inlineMarkers.replace(withoutMarkers, "").trim()
    }

    /** A heading stands alone: blank space above and below, or the edge of the document. */
    private fun isIsolated(lines: List<LineSpan>, index: Int): Boolean {
        val before = index == 0 || lines[index - 1].content.isBlank()
        val after = index == lines.lastIndex || lines[index + 1].content.isBlank()
        return before && after
    }

    private data class LineSpan(val start: Int, val content: String) {
        /** Offset of the first non-blank character, so an indented heading still points at its text. */
        fun textStart(): Int = start + content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    }

    private fun lineSpans(text: String): List<LineSpan> {
        val spans = mutableListOf<LineSpan>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                spans += LineSpan(start, text.substring(start))
                return spans
            }
            spans += LineSpan(start, text.substring(start, newline))
            start = newline + 1
        }
    }
}
