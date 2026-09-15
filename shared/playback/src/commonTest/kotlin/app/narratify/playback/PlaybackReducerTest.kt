package app.narratify.playback

import com.narratify.domain.MediaItemId
import com.narratify.domain.PlaybackModality
import com.narratify.domain.PlaybackState
import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackReducerTest {
    @Test
    fun seekClampsToCurrentItemAndPersistsImmediately() {
        val result = PlaybackReducer.reduce(session(), PlaybackEvent.SeekRequested(12_000_000))

        assertEquals(10_000_000, result.session.positionMicros)
        assertEquals(PlaybackState.BUFFERING, result.session.state)
        assertIs<PlaybackEffect.SeekBackend>(result.effects[0])
        assertEquals(PlaybackEffect.PersistImmediately, result.effects[1])
    }

    @Test
    fun completionLoadsNextQueueItem() {
        val result = PlaybackReducer.reduce(session(), PlaybackEvent.ItemCompleted)

        assertEquals(1, result.session.currentIndex)
        assertEquals(0, result.session.positionMicros)
        assertEquals(PlaybackState.BUFFERING, result.session.state)
        assertEquals(PlaybackEffect.LoadItem(result.session.currentItem), result.effects.first())
    }

    @Test
    fun finalCompletionStopsAtKnownDuration() {
        val initial = session().copy(currentIndex = 1)
        val result = PlaybackReducer.reduce(initial, PlaybackEvent.ItemCompleted)

        assertEquals(PlaybackState.COMPLETED, result.session.state)
        assertEquals(8_000_000, result.session.positionMicros)
        assertEquals(listOf(PlaybackEffect.PersistImmediately), result.effects)
    }

    @Test
    fun rateIsClampedAtBoundary() {
        val result = PlaybackReducer.reduce(session(), PlaybackEvent.RateChanged(5.0))

        assertEquals(PlaybackSession.MAX_RATE, result.session.rate)
        assertEquals(PlaybackEffect.SetBackendRate(PlaybackSession.MAX_RATE), result.effects.first())
    }

    private fun session(): PlaybackSession {
        val publicationId = PublicationId("book-1")
        return PlaybackSession(
            publicationId = publicationId,
            modality = PlaybackModality.AUDIOBOOK,
            queue = listOf(
                PlaybackItem(MediaItemId("one"), publicationId, PlaybackItemKind.AUDIOBOOK_FILE, "One", 10_000_000),
                PlaybackItem(MediaItemId("two"), publicationId, PlaybackItemKind.AUDIOBOOK_FILE, "Two", 8_000_000),
            ),
            currentIndex = 0,
            state = PlaybackState.PAUSED,
            positionMicros = 4_000_000,
            bufferedMicros = 2_000_000,
            rate = 1.0,
            locator = PublicationLocator(publicationId = publicationId),
        )
    }
}
