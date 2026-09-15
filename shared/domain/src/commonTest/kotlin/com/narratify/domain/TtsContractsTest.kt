package com.narratify.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TtsContractsTest {
    @Test
    fun chunkRequiresContiguousTokenIndexes() {
        assertFailsWith<IllegalArgumentException> {
            chunk(tokens = listOf(token(index = 1)), timings = emptyList())
        }
    }

    @Test
    fun chunkRejectsOverlappingOrOutOfBoundsTimings() {
        val tokens = listOf(token(0), token(1))

        assertFailsWith<IllegalArgumentException> {
            chunk(
                tokens = tokens,
                timings = listOf(
                    WordSampleTiming(0, 0, 600),
                    WordSampleTiming(1, 500, 900),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            chunk(tokens = tokens, timings = listOf(WordSampleTiming(0, 0, 1_001)))
        }
    }

    @Test
    fun durationUsesPcmSampleClock() {
        val chunk = chunk(
            sampleCount = 48_000,
            format = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        )

        assertEquals(2_000_000, chunk.durationMicros)
    }

    @Test
    fun serializedChunkRoundTripsWithDefaults() {
        val expected = chunk()
        val json = Json { encodeDefaults = true }

        assertEquals(
            expected,
            json.decodeFromString<TtsChunk>(json.encodeToString(expected)),
        )
    }

    private fun chunk(
        tokens: List<SpokenToken> = listOf(token(0)),
        timings: List<WordSampleTiming> = listOf(WordSampleTiming(0, 0, 1_000)),
        sampleCount: Long = 1_000,
        format: PcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
    ): TtsChunk = TtsChunk(
        id = ChunkId("chunk-1"),
        tokens = tokens,
        sourceStart = locator(),
        sourceEnd = locator(),
        voice = VoiceVersion(VoiceId("voice-1"), "1.0"),
        model = ModelVersion(ModelId("kokoro-82m"), "1.0"),
        parameters = SynthesisParameters(),
        pcmFormat = format,
        sampleCount = sampleCount,
        wordTimings = timings,
    )

    private fun token(index: Int): SpokenToken = SpokenToken(
        index = index,
        sourceRanges = listOf(SourceRange(ResourceId("chapter-1"), TextRange(index, index + 1))),
        displayText = "A",
        spokenText = "a",
        phonemes = listOf("ə"),
        language = "en-US",
        pronunciationSource = PronunciationSource.LEXICON,
    )

    private fun locator() = PublicationLocator(
        publicationId = PublicationId("book-1"),
        resourceId = ResourceId("chapter-1"),
        resourceIndex = 0,
    )
}
