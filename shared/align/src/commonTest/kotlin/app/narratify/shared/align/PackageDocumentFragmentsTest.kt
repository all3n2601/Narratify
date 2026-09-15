package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageDocumentFragmentsTest {
    @Test
    fun `the manifest links a content document to its overlay`() {
        val manifest = PackageDocumentFragments.manifestItems(
            textId = "c1",
            textHref = "chapter-1.xhtml",
            smilId = "c1smil",
            smilHref = "chapter-1.smil",
            audioId = "aud",
            audioHref = "audio/book.m4a",
        )
        assertTrue(manifest!!.contains("""media-overlay="c1smil""""))
        assertTrue(manifest.contains("""media-type="application/smil+xml""""))
        assertTrue(manifest.contains("""media-type="audio/mp4""""))
    }

    @Test
    fun `a manifest cannot be written for audio the format does not accept`() {
        assertNull(
            PackageDocumentFragments.manifestItems(
                textId = "c1",
                textHref = "chapter-1.xhtml",
                smilId = "c1smil",
                smilHref = "chapter-1.smil",
                audioId = "aud",
                audioHref = "audio/book.m4b",
            ),
        )
    }

    @Test
    fun `a per-overlay duration refines the overlay it belongs to`() {
        assertEquals(
            """<meta property="media:duration" refines="#c1smil">0:32:18.000</meta>""",
            PackageDocumentFragments.overlayDuration("c1smil", 1_938_000L),
        )
    }

    @Test
    fun `the total duration refines nothing`() {
        assertEquals(
            """<meta property="media:duration">4:12:05.000</meta>""",
            PackageDocumentFragments.totalDuration(15_125_000L),
        )
    }

    @Test
    fun `the active class is declared so a reader can style the spoken element`() {
        assertEquals(
            """<meta property="media:active-class">-epub-media-overlay-active</meta>""",
            PackageDocumentFragments.activeClass(),
        )
    }

    @Test
    fun `identifiers are escaped`() {
        assertTrue(PackageDocumentFragments.overlayDuration("""a"b""", 0L).contains("a&quot;b"))
    }
}
