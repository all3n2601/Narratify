package com.narratify.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_LOCATOR_SCHEMA_VERSION: Int = 1

@Serializable
data class PublicationLocator(
    val publicationId: PublicationId,
    val resourceId: ResourceId? = null,
    val resourceIndex: Int? = null,
    val resourceProgression: Double? = null,
    val totalProgression: Double? = null,
    val structuralAnchor: StructuralAnchor? = null,
    val textQuote: TextQuoteAnchor? = null,
    val pdfAnchor: PdfAnchor? = null,
    val audioAnchor: AudioAnchor? = null,
    val ttsAnchor: TtsAnchor? = null,
    val schemaVersion: Int = CURRENT_LOCATOR_SCHEMA_VERSION,
) {
    init {
        require(resourceIndex == null || resourceIndex >= 0) { "resourceIndex must be non-negative" }
        requireValidProgression(resourceProgression, "resourceProgression")
        requireValidProgression(totalProgression, "totalProgression")
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    /** Ordered fallbacks for restoring a position after layout or publication changes. */
    fun availableRestorationAnchors(): List<RestorationAnchorKind> = buildList {
        if (structuralAnchor != null) add(RestorationAnchorKind.STRUCTURAL)
        if (textQuote != null) add(RestorationAnchorKind.TEXT_QUOTE)
        if (resourceProgression != null) add(RestorationAnchorKind.RESOURCE_PROGRESSION)
        if (totalProgression != null) add(RestorationAnchorKind.TOTAL_PROGRESSION)
    }
}

private fun requireValidProgression(value: Double?, name: String) {
    require(value == null || (value.isFinite() && value in 0.0..1.0)) {
        "$name must be finite and between 0 and 1"
    }
}

@Serializable
enum class RestorationAnchorKind {
    STRUCTURAL,
    TEXT_QUOTE,
    RESOURCE_PROGRESSION,
    TOTAL_PROGRESSION,
}

@Serializable
data class StructuralAnchor(
    val value: String,
    val kind: StructuralAnchorKind,
) {
    init {
        require(value.isNotBlank()) { "Structural anchor must not be blank" }
    }
}

@Serializable
enum class StructuralAnchorKind {
    EPUB_CFI,
    DOM_PATH,
    INTERNAL,
}

@Serializable
data class TextQuoteAnchor(
    val exact: String,
    val prefix: String? = null,
    val suffix: String? = null,
) {
    init {
        require(exact.isNotEmpty()) { "Exact text quote must not be empty" }
    }
}

@Serializable
data class TextRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "Text range start must be non-negative" }
        require(endExclusive >= start) { "Text range end must not precede start" }
    }

    val length: Int get() = endExclusive - start
}

@Serializable
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    init {
        require(listOf(x, y, width, height).all(Double::isFinite)) { "Rectangle values must be finite" }
        require(x >= 0.0 && y >= 0.0 && width >= 0.0 && height >= 0.0) {
            "Rectangle values must be non-negative"
        }
        require(x + width <= 1.0 && y + height <= 1.0) {
            "Normalized rectangle must fit within the unit page"
        }
    }
}

@Serializable
data class PdfAnchor(
    val pageIndex: Int,
    val characterRange: TextRange? = null,
    val rect: NormalizedRect? = null,
) {
    init {
        require(pageIndex >= 0) { "PDF page index must be non-negative" }
    }
}

@Serializable
data class AudioAnchor(
    val mediaItemId: MediaItemId,
    val positionMicros: Long,
) {
    init {
        require(positionMicros >= 0) { "Audio position must be non-negative" }
    }
}

@Serializable
data class TtsAnchor(
    val chunkId: ChunkId,
    val sourceTokenIndex: Int,
    val sampleOffset: Long,
) {
    init {
        require(sourceTokenIndex >= 0) { "Source token index must be non-negative" }
        require(sampleOffset >= 0) { "Sample offset must be non-negative" }
    }
}
