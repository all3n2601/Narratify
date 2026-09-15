package app.narratify

import com.narratify.domain.OutlineItem
import kotlin.test.assertEquals
import org.junit.Test

class ReaderOutlineTest {
    private fun link(title: String?, href: String, children: List<OutlineLink> = emptyList()) =
        OutlineLink(title = title, href = href, children = children)

    @Test
    fun `table of contents nesting becomes depth`() {
        val toc = listOf(
            link("Part One", "p1.html", listOf(link("Chapter 1", "c1.html", listOf(link("Scene", "c1.html#s"))))),
            link("Part Two", "p2.html"),
        )

        val entries = ReaderOutline.fromTableOfContents(toc, readingOrder = emptyList())

        assertEquals(
            listOf("Part One" to 0, "Chapter 1" to 1, "Scene" to 2, "Part Two" to 0),
            entries.map { it.title to it.depth },
        )
        assertEquals(OutlineTarget.Resource("c1.html#s"), entries[2].target)
    }

    @Test
    fun `an empty table of contents falls back to the reading order`() {
        val spine = listOf(link("Cover", "cover.html"), link("Chapter 1", "c1.html"))

        val entries = ReaderOutline.fromTableOfContents(emptyList(), readingOrder = spine)

        assertEquals(listOf("Cover", "Chapter 1"), entries.map { it.title })
        assertEquals(listOf(0, 0), entries.map { it.depth })
    }

    @Test
    fun `untitled reading order resources are numbered`() {
        val spine = listOf(link(null, "a.html"), link("  ", "b.html"))

        val entries = ReaderOutline.fromTableOfContents(emptyList(), readingOrder = spine)

        assertEquals(listOf("Section 1", "Section 2"), entries.map { it.title })
    }

    @Test
    fun `text outline items become offset targets`() {
        val items = listOf(OutlineItem("CHAPTER I", 0, 12), OutlineItem("CHAPTER II", 0, 480))

        val entries = ReaderOutline.fromText(items)

        assertEquals(listOf("CHAPTER I", "CHAPTER II"), entries.map { it.title })
        assertEquals(listOf(OutlineTarget.Offset(12), OutlineTarget.Offset(480)), entries.map { it.target })
    }

    @Test
    fun `the current entry is the last one at or before the reading offset`() {
        val entries = ReaderOutline.fromText(
            listOf(OutlineItem("One", 0, 10), OutlineItem("Two", 0, 100), OutlineItem("Three", 0, 200)),
        )

        assertEquals(1, ReaderOutline.currentIndex(entries, offset = 150))
        assertEquals(2, ReaderOutline.currentIndex(entries, offset = 200))
    }

    @Test
    fun `nothing is current before the first entry`() {
        val entries = ReaderOutline.fromText(listOf(OutlineItem("One", 0, 10), OutlineItem("Two", 0, 100)))

        assertEquals(-1, ReaderOutline.currentIndex(entries, offset = 4))
    }

    @Test
    fun `the current entry matches the locator href including its fragment`() {
        val entries = ReaderOutline.fromTableOfContents(
            listOf(link("Start", "c1.html"), link("Scene", "c1.html#s"), link("Next", "c2.html")),
            readingOrder = emptyList(),
        )

        assertEquals(1, ReaderOutline.currentIndex(entries, href = "c1.html#s"))
        assertEquals(2, ReaderOutline.currentIndex(entries, href = "c2.html"))
    }

    @Test
    fun `a locator without a fragment matches the last entry in that resource`() {
        val entries = ReaderOutline.fromTableOfContents(
            listOf(link("Start", "c1.html"), link("Scene", "c1.html#s"), link("Next", "c2.html")),
            readingOrder = emptyList(),
        )

        assertEquals(1, ReaderOutline.currentIndex(entries, href = "c1.html#unknown"))
    }

    @Test
    fun `hrefs are compared by resource name so a longer locator path still matches`() {
        val entries = ReaderOutline.fromTableOfContents(
            listOf(link("Start", "OEBPS/c1.xhtml"), link("Next", "OEBPS/c2.xhtml")),
            readingOrder = emptyList(),
        )

        assertEquals(1, ReaderOutline.currentIndex(entries, href = "/book/OEBPS/c2.xhtml"))
    }

    @Test
    fun `an unknown href marks nothing current`() {
        val entries = ReaderOutline.fromTableOfContents(listOf(link("Start", "c1.html")), readingOrder = emptyList())

        assertEquals(-1, ReaderOutline.currentIndex(entries, href = "gone.html"))
    }

    @Test
    fun `a one entry outline is not worth showing`() {
        assertEquals(false, ReaderOutline.isWorthShowing(ReaderOutline.fromText(listOf(OutlineItem("One", 0, 0)))))
        assertEquals(
            true,
            ReaderOutline.isWorthShowing(ReaderOutline.fromText(listOf(OutlineItem("One", 0, 0), OutlineItem("Two", 0, 9)))),
        )
    }
}
