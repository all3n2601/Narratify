package com.narratify.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicationLocatorTest {
    @Test
    fun restorationAnchorsFollowCanonicalFallbackOrder() {
        val locator = locator(
            resourceProgression = 0.25,
            totalProgression = 0.5,
            structuralAnchor = StructuralAnchor("epubcfi(/6/4)", StructuralAnchorKind.EPUB_CFI),
            textQuote = TextQuoteAnchor("Exact text", prefix = "Before ", suffix = " after"),
        )

        assertEquals(
            listOf(
                RestorationAnchorKind.STRUCTURAL,
                RestorationAnchorKind.TEXT_QUOTE,
                RestorationAnchorKind.RESOURCE_PROGRESSION,
                RestorationAnchorKind.TOTAL_PROGRESSION,
            ),
            locator.availableRestorationAnchors(),
        )
    }

    @Test
    fun progressionMustBeFiniteAndNormalized() {
        assertFailsWith<IllegalArgumentException> { locator(resourceProgression = -0.01) }
        assertFailsWith<IllegalArgumentException> { locator(resourceProgression = 1.01) }
        assertFailsWith<IllegalArgumentException> { locator(resourceProgression = Double.NaN) }
    }

    @Test
    fun serializedLocatorRoundTripsWithoutLosingTypedAnchors() {
        val expected = locator(
            resourceProgression = 0.25,
            pdfAnchor = PdfAnchor(
                pageIndex = 3,
                characterRange = TextRange(10, 15),
                rect = NormalizedRect(0.1, 0.2, 0.3, 0.4),
            ),
            audioAnchor = AudioAnchor(MediaItemId("track-1"), 3_500_000),
            ttsAnchor = TtsAnchor(ChunkId("chunk-1"), sourceTokenIndex = 2, sampleOffset = 1_024),
        )

        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<PublicationLocator>(json.encodeToString(expected))

        assertEquals(expected, restored)
        assertEquals(CURRENT_LOCATOR_SCHEMA_VERSION, restored.schemaVersion)
    }

    private fun locator(
        resourceProgression: Double? = null,
        totalProgression: Double? = null,
        structuralAnchor: StructuralAnchor? = null,
        textQuote: TextQuoteAnchor? = null,
        pdfAnchor: PdfAnchor? = null,
        audioAnchor: AudioAnchor? = null,
        ttsAnchor: TtsAnchor? = null,
    ) = PublicationLocator(
        publicationId = PublicationId("book-1"),
        resourceId = ResourceId("chapter-1"),
        resourceIndex = 0,
        resourceProgression = resourceProgression,
        totalProgression = totalProgression,
        structuralAnchor = structuralAnchor,
        textQuote = textQuote,
        pdfAnchor = pdfAnchor,
        audioAnchor = audioAnchor,
        ttsAnchor = ttsAnchor,
    )
}
