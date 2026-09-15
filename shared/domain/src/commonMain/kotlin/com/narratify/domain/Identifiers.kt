package com.narratify.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class PublicationId(val value: String) {
    init {
        require(value.isNotBlank()) { "PublicationId must not be blank" }
    }
}

@Serializable
@JvmInline
value class ResourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "ResourceId must not be blank" }
    }
}

@Serializable
@JvmInline
value class ChunkId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChunkId must not be blank" }
    }
}

@Serializable
@JvmInline
value class MediaItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "MediaItemId must not be blank" }
    }
}

@Serializable
@JvmInline
value class VoiceId(val value: String) {
    init {
        require(value.isNotBlank()) { "VoiceId must not be blank" }
    }
}

@Serializable
@JvmInline
value class ModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "ModelId must not be blank" }
    }
}
