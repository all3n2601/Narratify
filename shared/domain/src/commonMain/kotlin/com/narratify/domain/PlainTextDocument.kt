package com.narratify.domain

data class TextAnchor(
    val characterOffset: Int,
    val exact: String,
    val prefix: String,
    val suffix: String,
)

data class PlainTextDocument(
    val title: String,
    val text: String,
    val outline: List<OutlineItem> = emptyList(),
) {
    fun anchorAt(offset: Int, contextSize: Int = 32): TextAnchor {
        val safe = offset.coerceIn(0, text.length)
        val exactEnd = (safe + contextSize).coerceAtMost(text.length)
        return TextAnchor(
            characterOffset = safe,
            exact = text.substring(safe, exactEnd),
            prefix = text.substring((safe - contextSize).coerceAtLeast(0), safe),
            suffix = text.substring(exactEnd, (exactEnd + contextSize).coerceAtMost(text.length)),
        )
    }

    fun resolve(anchor: TextAnchor): Int {
        if (anchor.characterOffset in 0..text.length &&
            text.startsWith(anchor.exact, anchor.characterOffset)
        ) return anchor.characterOffset

        if (anchor.exact.isEmpty()) return anchor.characterOffset.coerceIn(0, text.length)
        val candidates = mutableListOf<Int>()
        var from = 0
        while (from < text.length) {
            val match = text.indexOf(anchor.exact, from)
            if (match < 0) break
            candidates += match
            from = match + 1
        }
        return candidates.maxByOrNull { candidate ->
            var score = 0
            if (anchor.prefix.isNotEmpty() && text.substring(0, candidate).endsWith(anchor.prefix)) score += 2
            val after = candidate + anchor.exact.length
            if (anchor.suffix.isNotEmpty() && after <= text.length && text.substring(after).startsWith(anchor.suffix)) score += 2
            score * 1_000_000 - kotlin.math.abs(candidate - anchor.characterOffset)
        } ?: anchor.characterOffset.coerceIn(0, text.length)
    }
}

object PlainTextNormalizer {
    fun parse(displayName: String, source: String, markdown: Boolean): PlainTextDocument {
        val normalized = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()
        val readable = if (markdown) markdownToReadableText(normalized) else normalized
        val fallback = displayName.substringBeforeLast('.').ifBlank { "Untitled" }
        val heading = if (markdown) normalized.lineSequence()
            .firstOrNull { it.trimStart().startsWith("#") }
            ?.trimStart()?.trimStart('#')?.trim() else null
        // The outline has to be derived here, while both strings are in hand: Markdown headings are
        // only recognisable in the source, but their offsets have to index the readable text the
        // reader displays and stores positions against.
        val outline = if (markdown) {
            BookOutline.markdown(normalized, readable)
        } else {
            BookOutline.plainText(readable)
        }
        return PlainTextDocument(heading?.ifBlank { null } ?: fallback, readable, outline)
    }

    private fun markdownToReadableText(value: String): String = value
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}[-*+]\\s+"), "• ")
        .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        .replace(Regex("[`*_~]{1,3}"), "")
}
