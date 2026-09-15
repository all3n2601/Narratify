package app.narratify.shared.align

/**
 * Supplies the id of the element in the content document that a span's text lives in.
 *
 * This is an interface rather than a calculation because nothing in this module can perform it.
 * A `SourceRange` is expressed in whatever coordinate space the caller used when it built its
 * `SourceTextSpan`s — character offsets into extracted plain text, positions in a Readium
 * locator, something else again — and `TtsTextPreparer` maps through that space without
 * interpreting it. Only the component that produced the spans knows how to reach markup from
 * them.
 *
 * Returning null means there is no element for this span, and the span is left out of the
 * overlay. That is legal: a reading system does not require every element to be narrated.
 */
fun interface TextAnchorResolver {
    fun anchor(span: AlignedSpan): String?
}

/** A finished Media Overlay, ready to be written into a publication. */
data class MediaOverlayDocument(
    val smil: String,
    val durationMs: Long,
    val parCount: Int,
) {
    init {
        require(smil.isNotBlank()) { "A media overlay must have content" }
        require(durationMs >= 0) { "Duration must be non-negative" }
        require(parCount > 0) { "A media overlay with no pars claims nothing and should not exist" }
    }
}
