package app.narratify.shared.align

/**
 * Writes an [AlignmentMap] as an EPUB3 Media Overlay.
 *
 * A Media Overlay is a published claim of exact synchronisation: once written into a book it
 * travels, and any reading system that opens it will act on it. So the grading the aligner
 * already did is carried into the format rather than discarded. An alignment that reached only
 * chapter granularity, or none, produces nothing. An individual span below sentence granularity
 * is left out rather than given a guessed clip range — SMIL does not require every element to be
 * narrated, which makes silence the honest option.
 *
 * Returns null whenever there is nothing it can honestly emit.
 */
object MediaOverlayWriter {
    fun write(
        map: AlignmentMap,
        audioHref: String,
        resolver: TextAnchorResolver,
    ): MediaOverlayDocument? {
        if (map.granularity != AlignmentGranularity.WORD && map.granularity != AlignmentGranularity.SENTENCE) {
            return null
        }
        if (AudioMediaType.forHref(audioHref) == null) return null

        val anchored = map.spans
            .filter { it.granularity == AlignmentGranularity.WORD || it.granularity == AlignmentGranularity.SENTENCE }
            .mapNotNull { span -> resolver.anchor(span)?.let { anchor -> anchor to span } }
        if (anchored.isEmpty()) return null

        // One element must not receive several competing clip ranges, so consecutive spans that
        // resolve to the same id are played as one.
        val merged = mutableListOf<Triple<String, Long, Long>>()
        for ((anchor, span) in anchored) {
            val last = merged.lastOrNull()
            if (last != null && last.first == anchor) {
                merged[merged.lastIndex] = Triple(anchor, last.second, maxOf(last.third, span.endMs))
            } else {
                merged.add(Triple(anchor, span.startMs, span.endMs))
            }
        }

        val textHref = map.resourceId.value
        val smil = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<smil xmlns=\"http://www.w3.org/ns/SMIL\" ")
            append("xmlns:epub=\"http://www.idpf.org/2007/ops\" version=\"3.0\">\n")
            append("  <body>\n")
            append("    <seq id=\"seq1\" epub:textref=\"${textHref.escapeXml()}\" epub:type=\"chapter\">\n")
            merged.forEachIndexed { index, (anchor, startMs, endMs) ->
                append("      <par id=\"par${index + 1}\">\n")
                append("        <text src=\"${textHref.escapeXml()}#${anchor.escapeXml()}\"/>\n")
                append("        <audio src=\"${audioHref.escapeXml()}\" ")
                append("clipBegin=\"${SmilClock.format(startMs)}\" ")
                append("clipEnd=\"${SmilClock.format(endMs)}\"/>\n")
                append("      </par>\n")
            }
            append("    </seq>\n")
            append("  </body>\n")
            append("</smil>\n")
        }

        return MediaOverlayDocument(
            smil = smil,
            durationMs = merged.maxOf { it.third },
            parCount = merged.size,
        )
    }
}

internal fun String.escapeXml(): String = buildString(length) {
    for (character in this@escapeXml) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
