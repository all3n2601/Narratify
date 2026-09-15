package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlinx.serialization.json.Json

/**
 * Builds the persistable map from a finished alignment.
 *
 * The per-token timings are deliberately not persisted. They are large, they are mostly
 * interpolation, and a reader that needs them can recompute them from the spans it is showing.
 */
fun AlignmentResult.toAlignmentMap(
    publicationId: PublicationId,
    mediaItemId: MediaItemId,
    resourceId: ResourceId,
): AlignmentMap = AlignmentMap(
    publicationId = publicationId,
    mediaItemId = mediaItemId,
    resourceId = resourceId,
    granularity = granularity,
    spans = spans,
)

/**
 * The on-disk form of an alignment.
 *
 * An alignment is expensive enough to cache and cheap enough to recompute, which makes strictness
 * free: an unreadable cache entry costs one background job, while a misread one puts the
 * highlight in the wrong place and looks like a bug in the reader.
 */
object AlignmentCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(map: AlignmentMap): String = json.encodeToString(AlignmentMap.serializer(), map)

    fun decode(value: String): AlignmentMap {
        val map = json.decodeFromString(AlignmentMap.serializer(), value)
        require(map.schemaVersion == CURRENT_ALIGNMENT_SCHEMA_VERSION) {
            "Alignment schema ${map.schemaVersion} cannot be read by version $CURRENT_ALIGNMENT_SCHEMA_VERSION"
        }
        return map
    }
}
