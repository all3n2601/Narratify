package app.narratify.playback

import com.narratify.domain.ChunkId
import com.narratify.domain.MediaItemId
import com.narratify.domain.PlaybackModality
import com.narratify.domain.PlaybackState
import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator

data class PlaybackItem(
    val id: MediaItemId,
    val publicationId: PublicationId,
    val kind: PlaybackItemKind,
    val title: String,
    val durationMicros: Long? = null,
    val ttsChunkId: ChunkId? = null,
) {
    init {
        require(title.isNotBlank()) { "Playback item title must not be blank" }
        require(durationMicros == null || durationMicros >= 0) { "Duration must be non-negative" }
        require(kind == PlaybackItemKind.SYNTHESIZED_SPEECH || ttsChunkId == null) {
            "Only synthesized speech items may reference a TTS chunk"
        }
    }
}

enum class PlaybackItemKind {
    AUDIOBOOK_FILE,
    SYNTHESIZED_SPEECH,
}

data class PlaybackSession(
    val publicationId: PublicationId,
    val modality: PlaybackModality,
    val queue: List<PlaybackItem>,
    val currentIndex: Int,
    val state: PlaybackState,
    val positionMicros: Long,
    val bufferedMicros: Long,
    val rate: Double,
    val locator: PublicationLocator,
    val failure: PlaybackFailure? = null,
) {
    init {
        require(queue.isNotEmpty()) { "Playback queue must not be empty" }
        require(currentIndex in queue.indices) { "Current index must reference the queue" }
        require(queue.all { it.publicationId == publicationId }) { "Queue items must belong to the session publication" }
        require(locator.publicationId == publicationId) { "Locator must belong to the session publication" }
        require(positionMicros >= 0) { "Position must be non-negative" }
        require(bufferedMicros >= 0) { "Buffered duration must be non-negative" }
        require(rate.isFinite() && rate in MIN_RATE..MAX_RATE) { "Playback rate must be between $MIN_RATE and $MAX_RATE" }
        require(state == PlaybackState.FAILED || failure == null) { "Failure details require FAILED state" }
    }

    val currentItem: PlaybackItem get() = queue[currentIndex]

    companion object {
        const val MIN_RATE = 0.5
        const val MAX_RATE = 3.0
    }
}

data class PlaybackFailure(
    val code: PlaybackFailureCode,
    val recoverable: Boolean,
)

enum class PlaybackFailureCode {
    SOURCE_UNAVAILABLE,
    DECODER_ERROR,
    AUDIO_SESSION_DENIED,
    TTS_SYNTHESIS_FAILED,
    UNKNOWN,
}

sealed interface PlaybackEvent {
    data object PlayRequested : PlaybackEvent
    data object PauseRequested : PlaybackEvent
    data object BufferingStarted : PlaybackEvent
    data object BufferingEnded : PlaybackEvent
    data class SeekRequested(val positionMicros: Long) : PlaybackEvent
    data class Progressed(
        val positionMicros: Long,
        val bufferedMicros: Long,
        val locator: PublicationLocator,
    ) : PlaybackEvent
    data class RateChanged(val rate: Double) : PlaybackEvent
    data object ItemCompleted : PlaybackEvent
    data class Failed(val failure: PlaybackFailure) : PlaybackEvent
}

sealed interface PlaybackEffect {
    data object StartBackend : PlaybackEffect
    data object PauseBackend : PlaybackEffect
    data class SeekBackend(val positionMicros: Long) : PlaybackEffect
    data class SetBackendRate(val rate: Double) : PlaybackEffect
    data class LoadItem(val item: PlaybackItem) : PlaybackEffect
    data object PersistImmediately : PlaybackEffect
    data object PersistThrottled : PlaybackEffect
}

data class PlaybackTransition(
    val session: PlaybackSession,
    val effects: List<PlaybackEffect>,
)

interface PlaybackBackend {
    fun load(item: PlaybackItem, startPositionMicros: Long)
    fun play()
    fun pause()
    fun seekTo(positionMicros: Long)
    fun setRate(rate: Double)
}

interface PlaybackSnapshotStore {
    fun save(session: PlaybackSession)
}
