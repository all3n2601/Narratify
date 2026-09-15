package com.narratify.domain

import kotlinx.serialization.Serializable

const val CURRENT_PLAYBACK_SNAPSHOT_SCHEMA_VERSION: Int = 1

@Serializable
data class PlaybackSnapshot(
    val publicationId: PublicationId,
    val modality: PlaybackModality,
    val locator: PublicationLocator,
    val state: PlaybackState,
    val mediaPositionMicros: Long? = null,
    val samplePosition: Long? = null,
    val rate: Double = 1.0,
    val bufferedDurationMicros: Long = 0,
    val voice: VoiceVersion? = null,
    /** Unix epoch milliseconds. */
    val updatedAtEpochMillis: Long,
    val schemaVersion: Int = CURRENT_PLAYBACK_SNAPSHOT_SCHEMA_VERSION,
) {
    init {
        require(locator.publicationId == publicationId) {
            "Snapshot and locator must reference the same publication"
        }
        require(mediaPositionMicros == null || mediaPositionMicros >= 0) {
            "Media position must be non-negative"
        }
        require(samplePosition == null || samplePosition >= 0) {
            "Sample position must be non-negative"
        }
        require(rate.isFinite() && rate > 0.0) { "Playback rate must be finite and positive" }
        require(bufferedDurationMicros >= 0) { "Buffered duration must be non-negative" }
        require(updatedAtEpochMillis >= 0) { "Update timestamp must be non-negative" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(modality == PlaybackModality.TTS || voice == null) {
            "A voice may only be present for TTS playback"
        }
    }
}

@Serializable
enum class PlaybackModality {
    VISUAL,
    TTS,
    AUDIOBOOK,
}

@Serializable
enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
    FAILED,
}
