package app.narratify.playback

import com.narratify.domain.PlaybackState

object PlaybackReducer {
    fun reduce(session: PlaybackSession, event: PlaybackEvent): PlaybackTransition = when (event) {
        PlaybackEvent.PlayRequested -> when (session.state) {
            PlaybackState.PLAYING -> transition(session)
            PlaybackState.COMPLETED -> transition(
                session.copy(state = PlaybackState.BUFFERING, positionMicros = 0, failure = null),
                PlaybackEffect.SeekBackend(0),
                PlaybackEffect.StartBackend,
            )
            else -> transition(session.copy(state = PlaybackState.PLAYING, failure = null), PlaybackEffect.StartBackend)
        }

        PlaybackEvent.PauseRequested -> transition(
            session.copy(state = PlaybackState.PAUSED),
            PlaybackEffect.PauseBackend,
            PlaybackEffect.PersistImmediately,
        )

        PlaybackEvent.BufferingStarted -> transition(session.copy(state = PlaybackState.BUFFERING))
        PlaybackEvent.BufferingEnded -> transition(session.copy(state = PlaybackState.PLAYING))

        is PlaybackEvent.SeekRequested -> {
            val position = clampPosition(session, event.positionMicros)
            transition(
                session.copy(positionMicros = position, state = PlaybackState.BUFFERING),
                PlaybackEffect.SeekBackend(position),
                PlaybackEffect.PersistImmediately,
            )
        }

        is PlaybackEvent.Progressed -> transition(
            session.copy(
                positionMicros = clampPosition(session, event.positionMicros),
                bufferedMicros = event.bufferedMicros.coerceAtLeast(0),
                locator = event.locator,
            ),
            PlaybackEffect.PersistThrottled,
        )

        is PlaybackEvent.RateChanged -> transition(
            session.copy(rate = event.rate.coerceIn(PlaybackSession.MIN_RATE, PlaybackSession.MAX_RATE)),
            PlaybackEffect.SetBackendRate(event.rate.coerceIn(PlaybackSession.MIN_RATE, PlaybackSession.MAX_RATE)),
            PlaybackEffect.PersistImmediately,
        )

        PlaybackEvent.ItemCompleted -> advance(session)

        is PlaybackEvent.Failed -> transition(
            session.copy(state = PlaybackState.FAILED, failure = event.failure),
            PlaybackEffect.PauseBackend,
            PlaybackEffect.PersistImmediately,
        )
    }

    private fun advance(session: PlaybackSession): PlaybackTransition {
        val nextIndex = session.currentIndex + 1
        return if (nextIndex in session.queue.indices) {
            val next = session.queue[nextIndex]
            transition(
                session.copy(currentIndex = nextIndex, positionMicros = 0, bufferedMicros = 0, state = PlaybackState.BUFFERING),
                PlaybackEffect.LoadItem(next),
                PlaybackEffect.PersistImmediately,
            )
        } else {
            val end = session.currentItem.durationMicros ?: session.positionMicros
            transition(session.copy(positionMicros = end, state = PlaybackState.COMPLETED), PlaybackEffect.PersistImmediately)
        }
    }

    private fun clampPosition(session: PlaybackSession, requested: Long): Long {
        val nonNegative = requested.coerceAtLeast(0)
        return session.currentItem.durationMicros?.let(nonNegative::coerceAtMost) ?: nonNegative
    }

    private fun transition(session: PlaybackSession, vararg effects: PlaybackEffect) =
        PlaybackTransition(session, effects.toList())
}
