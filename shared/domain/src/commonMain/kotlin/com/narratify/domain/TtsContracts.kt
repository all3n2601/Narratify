package com.narratify.domain

import kotlinx.serialization.Serializable

const val CURRENT_TTS_CHUNK_SCHEMA_VERSION: Int = 1

@Serializable
data class TtsChunk(
    val id: ChunkId,
    val tokens: List<SpokenToken>,
    val sourceStart: PublicationLocator,
    val sourceEnd: PublicationLocator,
    val voice: VoiceVersion,
    val model: ModelVersion,
    val parameters: SynthesisParameters,
    val pcmFormat: PcmFormat,
    val sampleCount: Long,
    val wordTimings: List<WordSampleTiming>,
    val schemaVersion: Int = CURRENT_TTS_CHUNK_SCHEMA_VERSION,
) {
    init {
        require(tokens.isNotEmpty()) { "A TTS chunk must contain tokens" }
        require(tokens.map(SpokenToken::index) == tokens.indices.toList()) {
            "Token indexes must be contiguous and zero-based"
        }
        require(sourceStart.publicationId == sourceEnd.publicationId) {
            "Chunk source locators must belong to the same publication"
        }
        require(sampleCount >= 0) { "Sample count must be non-negative" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(wordTimings.zipWithNext().all { (a, b) -> a.endSample <= b.startSample }) {
            "Word timings must be ordered and non-overlapping"
        }
        require(wordTimings.all { it.tokenIndex in tokens.indices && it.endSample <= sampleCount }) {
            "Word timing must reference a token and fit within the chunk"
        }
    }

    val durationMicros: Long
        get() = if (sampleCount == 0L) 0L else sampleCount * 1_000_000L / pcmFormat.sampleRateHz
}

@Serializable
data class VoiceVersion(
    val id: VoiceId,
    val version: String,
) {
    init {
        require(version.isNotBlank()) { "Voice version must not be blank" }
    }
}

@Serializable
data class ModelVersion(
    val id: ModelId,
    val version: String,
) {
    init {
        require(version.isNotBlank()) { "Model version must not be blank" }
    }
}

@Serializable
data class SynthesisParameters(
    val rate: Double = 1.0,
    /** Pitch offset in semitones. */
    val pitchSemitones: Double = 0.0,
    val sentencePauseMillis: Int = 0,
) {
    init {
        require(rate.isFinite() && rate > 0.0) { "Synthesis rate must be finite and positive" }
        require(pitchSemitones.isFinite()) { "Pitch must be finite" }
        require(sentencePauseMillis >= 0) { "Sentence pause must be non-negative" }
    }
}

@Serializable
data class PcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: PcmEncoding,
) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount > 0) { "Channel count must be positive" }
    }
}

@Serializable
enum class PcmEncoding {
    SIGNED_INT_16_LE,
    FLOAT_32_LE,
}

@Serializable
data class WordSampleTiming(
    val tokenIndex: Int,
    val startSample: Long,
    val endSample: Long,
) {
    init {
        require(tokenIndex >= 0) { "Timing token index must be non-negative" }
        require(startSample >= 0) { "Timing start sample must be non-negative" }
        require(endSample >= startSample) { "Timing end sample must not precede its start" }
    }
}
