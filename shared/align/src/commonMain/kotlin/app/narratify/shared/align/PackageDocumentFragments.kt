package app.narratify.shared.align

/**
 * The package-document entries that turn a loose SMIL file into a working Media Overlay.
 *
 * These are generated rather than hand-written because the three links involved — the
 * `media-overlay` attribute pointing from the content document to the overlay, the overlay's own
 * duration, and the publication total — are each easy to write plausibly and wrongly, and a
 * reading system will simply refuse the book without saying which one is at fault.
 */
object PackageDocumentFragments {
    /** Returns null when the audio is not a type EPUB3 accepts. */
    fun manifestItems(
        textId: String,
        textHref: String,
        smilId: String,
        smilHref: String,
        audioId: String,
        audioHref: String,
    ): String? {
        val audioType = AudioMediaType.forHref(audioHref) ?: return null
        return buildString {
            append("<item id=\"${textId.escapeXml()}\" href=\"${textHref.escapeXml()}\" ")
            append("media-type=\"application/xhtml+xml\" media-overlay=\"${smilId.escapeXml()}\"/>\n")
            append("<item id=\"${smilId.escapeXml()}\" href=\"${smilHref.escapeXml()}\" ")
            append("media-type=\"application/smil+xml\"/>\n")
            append("<item id=\"${audioId.escapeXml()}\" href=\"${audioHref.escapeXml()}\" ")
            append("media-type=\"$audioType\"/>")
        }
    }

    fun overlayDuration(smilId: String, durationMs: Long): String =
        "<meta property=\"media:duration\" refines=\"#${smilId.escapeXml()}\">" +
            "${SmilClock.format(durationMs)}</meta>"

    fun totalDuration(durationMs: Long): String =
        "<meta property=\"media:duration\">${SmilClock.format(durationMs)}</meta>"

    fun activeClass(): String =
        "<meta property=\"media:active-class\">-epub-media-overlay-active</meta>"
}
