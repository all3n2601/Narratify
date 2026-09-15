package com.narratify.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlainTextDocumentTest {
    @Test fun markdownHeadingBecomesTitleAndMarkupIsRemoved() {
        val document = PlainTextNormalizer.parse("notes.md", "# My Book\r\n\r\nRead **this** [now](https://example.test).", true)
        assertEquals("My Book", document.title)
        assertTrue(document.text.contains("Read this now."))
    }

    @Test fun quoteAnchorSurvivesTextInsertedBeforeIt() {
        val original = PlainTextDocument("Book", "First paragraph.\n\nThe target sentence is here.\n\nLast paragraph.")
        val anchor = original.anchorAt(original.text.indexOf("target"))
        val edited = PlainTextDocument("Book", "A new preface.\n\n" + original.text)
        assertEquals(edited.text.indexOf("target"), edited.resolve(anchor))
    }
}
