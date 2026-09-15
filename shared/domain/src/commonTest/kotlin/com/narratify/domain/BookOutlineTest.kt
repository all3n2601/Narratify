package com.narratify.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookOutlineTest {
    @Test
    fun `chapter lines become outline entries`() {
        val text = "Preface words.\n\nCHAPTER I\n\nFirst body.\n\nCHAPTER II\n\nSecond body.\n"

        val outline = BookOutline.plainText(text)

        assertEquals(listOf("CHAPTER I", "CHAPTER II"), outline.map { it.title })
        assertTrue(outline.all { it.depth == 0 })
        assertEquals(text.indexOf("CHAPTER II"), outline[1].characterOffset)
    }

    @Test
    fun `a single chapter line is not enough to report an outline`() {
        val text = "A Short Work\n\nCHAPTER I\n\nOnly one qualifying line appears here.\n"

        assertEquals(emptyList(), BookOutline.plainText(text))
    }

    @Test
    fun `prose that merely mentions a chapter has no outline`() {
        val text = "It mentions a chapter of history in passing.\n\nBut never as a heading.\n"

        assertEquals(emptyList(), BookOutline.plainText(text))
    }

    @Test
    fun `a chapter line needs blank lines around it`() {
        val text = "CHAPTER I\nruns straight into the body text.\nCHAPTER II\nalso does.\n"

        assertEquals(emptyList(), BookOutline.plainText(text))
    }

    @Test
    fun `bare numerals count as chapter lines`() {
        val text = "Opening.\n\n1\n\nFirst body.\n\n2\n\nSecond body.\n"

        assertEquals(listOf("1", "2"), BookOutline.plainText(text).map { it.title })
    }

    @Test
    fun `an over long line is not a chapter heading`() {
        val long = "CHAPTER " + "x".repeat(80)
        val text = "Opening.\n\n$long\n\nBody.\n\nCHAPTER II\n\nMore.\n"

        assertEquals(emptyList(), BookOutline.plainText(text))
    }

    @Test
    fun `markdown heading level becomes depth`() {
        val source = "# Top\n\nBody.\n\n### Deep\n\nBody.\n"

        val outline = BookOutline.markdown(source, source)

        assertEquals(listOf("Top" to 0, "Deep" to 2), outline.map { it.title to it.depth })
    }

    @Test
    fun `headings inside fenced code are ignored`() {
        val source = "# Real\n\n```\n# fake\n```\n\n## Also real\n"

        assertEquals(listOf("Real", "Also real"), BookOutline.markdown(source, source).map { it.title })
    }

    @Test
    fun `setext underlines are not headings`() {
        val source = "# Real\n\nSetext Style\n---\n\nBody.\n"

        assertEquals(listOf("Real"), BookOutline.markdown(source, source).map { it.title })
    }

    @Test
    fun `heading titles drop inline emphasis markers`() {
        val source = "## A **Bold** Chapter\n"

        assertEquals(listOf("A Bold Chapter"), BookOutline.markdown(source, source).map { it.title })
    }

    @Test
    fun `a parsed markdown document carries offsets into its readable text`() {
        val document = PlainTextNormalizer.parse(
            "notes.md",
            "# Top\n\nIntro.\n\n## A **Bold** Chapter\n\nBody.\n",
            markdown = true,
        )

        val chapter = document.outline.single { it.title == "A Bold Chapter" }
        assertTrue(
            document.text.startsWith("A Bold Chapter", chapter.characterOffset),
            "offset ${chapter.characterOffset} does not land on the heading in ${document.text}",
        )
    }

    @Test
    fun `a parsed plain text document carries its chapter outline`() {
        val document = PlainTextNormalizer.parse(
            "book.txt",
            "Opening.\n\nCHAPTER I\n\nFirst.\n\nCHAPTER II\n\nSecond.\n",
            markdown = false,
        )

        assertEquals(listOf("CHAPTER I", "CHAPTER II"), document.outline.map { it.title })
        assertTrue(document.text.startsWith("CHAPTER II", document.outline[1].characterOffset))
    }
}
