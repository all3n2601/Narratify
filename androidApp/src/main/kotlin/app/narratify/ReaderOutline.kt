package app.narratify

import com.narratify.domain.OutlineItem

/** Readium's link tree reduced to what an outline needs, so the logic stays unit testable. */
data class OutlineLink(
    val title: String?,
    val href: String,
    val children: List<OutlineLink> = emptyList(),
)

/** What an outline row points at. Text documents address offsets, EPUBs address resources. */
sealed interface OutlineTarget {
    data class Offset(val characterOffset: Int) : OutlineTarget

    data class Resource(val href: String) : OutlineTarget
}

/** One row in the reader outline panel. */
data class OutlineEntry(
    val title: String,
    val depth: Int,
    val target: OutlineTarget,
)

/** Builds and resolves reader outlines. Free of Android and Readium types on purpose. */
object ReaderOutline {
    /** A single row tells the reader nothing it does not already know from the title bar. */
    private const val MINIMUM_ENTRIES = 2

    fun isWorthShowing(entries: List<OutlineEntry>): Boolean = entries.size >= MINIMUM_ENTRIES

    /**
     * Flattens an EPUB table of contents depth first. Publications that ship no table of contents
     * — common in scanned public-domain titles — fall back to their reading order.
     */
    fun fromTableOfContents(
        tableOfContents: List<OutlineLink>,
        readingOrder: List<OutlineLink>,
    ): List<OutlineEntry> {
        if (tableOfContents.isNotEmpty()) {
            val entries = mutableListOf<OutlineEntry>()
            fun walk(links: List<OutlineLink>, depth: Int) {
                for (link in links) {
                    entries += OutlineEntry(
                        title = link.title?.trim()?.ifBlank { null } ?: "Section ${entries.size + 1}",
                        depth = depth,
                        target = OutlineTarget.Resource(link.href),
                    )
                    walk(link.children, depth + 1)
                }
            }
            walk(tableOfContents, depth = 0)
            return entries
        }
        return readingOrder.mapIndexed { index, link ->
            OutlineEntry(
                title = link.title?.trim()?.ifBlank { null } ?: "Section ${index + 1}",
                depth = 0,
                target = OutlineTarget.Resource(link.href),
            )
        }
    }

    fun fromText(items: List<OutlineItem>): List<OutlineEntry> = items.map {
        OutlineEntry(title = it.title, depth = it.depth, target = OutlineTarget.Offset(it.characterOffset))
    }

    /** The last entry at or before [offset], or -1 while the reader is still ahead of the first. */
    fun currentIndex(entries: List<OutlineEntry>, offset: Int): Int =
        entries.indexOfLast { (it.target as? OutlineTarget.Offset)?.let { target -> target.characterOffset <= offset } == true }

    /**
     * The entry for [href]. An exact match wins; otherwise the last entry inside the same resource
     * does, since a locator deeper in a chapter than any of its listed anchors still belongs to the
     * last anchor above it.
     *
     * Comparison is by resource name, because a navigator reports a fully resolved location while
     * a table of contents lists whatever relative path the publication authored.
     */
    fun currentIndex(entries: List<OutlineEntry>, href: String): Int {
        val wanted = resourceName(href)
        val exact = entries.indexOfFirst { resourceName(it) == wanted }
        if (exact >= 0) return exact
        val resource = wanted.substringBefore('#')
        return entries.indexOfLast { resourceName(it)?.substringBefore('#') == resource }
    }

    private fun resourceName(href: String): String = href.substringAfterLast('/')

    private fun resourceName(entry: OutlineEntry): String? =
        (entry.target as? OutlineTarget.Resource)?.href?.let(::resourceName)
}
