package com.narratify.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackSnapshotTest {
    @Test
    fun snapshotAndLocatorMustIdentifySamePublication() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                publicationId = PublicationId("book-2"),
                locator = PublicationLocator(PublicationId("book-1")),
            )
        }
    }

    @Test
    fun onlyTtsSnapshotsMayCarryVoiceIdentity() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                modality = PlaybackModality.AUDIOBOOK,
                voice = VoiceVersion(VoiceId("voice-1"), "1.0"),
            )
        }
    }

    @Test
    fun dataClassEqualitySupportsAtomicPersistenceComparisons() {
        val first = snapshot()
        val second = first.copy()

        assertEquals(first, second)
    }

    private fun snapshot(
        publicationId: PublicationId = PublicationId("book-1"),
        locator: PublicationLocator = PublicationLocator(publicationId),
        modality: PlaybackModality = PlaybackModality.TTS,
        voice: VoiceVersion? = VoiceVersion(VoiceId("voice-1"), "1.0"),
    ) = PlaybackSnapshot(
        publicationId = publicationId,
        modality = modality,
        locator = locator,
        state = PlaybackState.PAUSED,
        mediaPositionMicros = 1_000_000,
        samplePosition = 24_000,
        bufferedDurationMicros = 10_000_000,
        voice = voice,
        updatedAtEpochMillis = 1_725_758_400_000,
    )
}
