package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the emitted overlay is XML a parser accepts, and pins its exact text.
 *
 * The unit tests check that substrings appear, which a serializer can satisfy while producing
 * something unparseable. This parses the real output, and diffs it against a checked-in file so
 * that any change to the format is a visible change to a reviewable artifact.
 */
class MediaOverlayGoldenTest {
    private val golden = File("../../test-fixtures/alignment/media-overlay/chapter-1.smil")

    private fun document(): MediaOverlayDocument {
        val spans = listOf(
            AlignedSpan(0, 6, 0L, 3_120L, 1.0, AlignmentGranularity.WORD),
            AlignedSpan(6, 13, 3_120L, 6_480L, 1.0, AlignmentGranularity.WORD),
            AlignedSpan(13, 20, 6_480L, 11_907L, 0.6, AlignmentGranularity.SENTENCE),
        )
        val map = AlignmentMap(
            publicationId = PublicationId("p"),
            mediaItemId = MediaItemId("book.m4a"),
            resourceId = ResourceId("chapter-1.xhtml"),
            granularity = AlignmentGranularity.WORD,
            spans = spans,
        )
        return MediaOverlayWriter.write(
            map,
            audioHref = "audio/book.m4a",
            resolver = TextAnchorResolver { "s${it.bookTokenStart}" },
        )!!
    }

    @Test
    fun `the emitted overlay is well-formed xml`() {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val parsed = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(document().smil.toByteArray(Charsets.UTF_8)))
        assertEquals("smil", parsed.documentElement.localName)
        assertEquals(3, parsed.getElementsByTagNameNS("http://www.w3.org/ns/SMIL", "par").length)
    }

    @Test
    fun `the emitted overlay matches the checked-in golden file`() {
        val emitted = document().smil
        assertTrue(golden.isFile, "missing golden file at ${golden.absolutePath}")
        assertEquals(golden.readText(), emitted)
    }
}
