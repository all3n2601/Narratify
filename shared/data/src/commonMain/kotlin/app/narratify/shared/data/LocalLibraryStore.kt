package app.narratify.shared.data

import app.narratify.shared.data.db.NarratifyDatabase
import com.narratify.domain.TextAnchor

data class StoredLibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val displayName: String,
    val format: String,
    val storageUri: String,
    val byteSize: Long,
    val durationUs: Long?,
    val importedAt: Long,
    val progress: Int,
    val hidden: Boolean,
    val coverUri: String? = null,
)

data class StoredTextPosition(
    val anchor: TextAnchor,
    val progression: Float,
)

/**
 * Database-backed local library state. File copying and text decoding stay in
 * platform adapters; library identity and resilient positions live here.
 */
class LocalLibraryStore(private val database: NarratifyDatabase) {
    fun books(): List<StoredLibraryBook> = database.publicationsQueries.selectLibrary()
        .executeAsList()
        .mapNotNull { publication -> storedBook(publication, hidden = false) }

    fun hiddenBooks(): List<StoredLibraryBook> = database.publicationsQueries.selectHiddenLibrary()
        .executeAsList()
        .mapNotNull { publication -> storedBook(publication, hidden = true) }

    fun book(id: String): StoredLibraryBook? {
        val publication = database.publicationsQueries.selectPublicationById(id).executeAsOneOrNull()
            ?: return null
        return storedBook(publication, hidden = publication.import_state == HIDDEN_STATE)
    }

    fun findByFingerprint(fingerprint: String): StoredLibraryBook? {
        val publication = database.publicationsQueries.selectPublicationByFingerprint(fingerprint)
            .executeAsOneOrNull() ?: return null
        return book(publication.id)
    }

    fun addBook(
        id: String,
        fingerprint: String,
        format: String,
        title: String,
        author: String? = null,
        displayName: String,
        storageUri: String,
        mediaType: String?,
        byteSize: Long,
        durationUs: Long? = null,
        now: Long,
    ): StoredLibraryBook {
        database.transaction {
            database.publicationsQueries.insertPublication(
                id = id,
                fingerprint = fingerprint,
                format = format,
                title = title,
                sort_title = title,
                subtitle = null,
                authors_json = author?.let(::encodeSingleJsonString) ?: "[]",
                identifiers_json = "{}",
                language_tags_json = "[]",
                description = null,
                resource_count = 1,
                duration_us = durationUs,
                import_state = "ready",
                created_at = now,
                updated_at = now,
                schema_version = 1,
            )
            database.filesQueries.insertLibraryFile(
                id = "file:$id",
                publication_id = id,
                role = ORIGINAL_FILE_ROLE,
                storage_uri = storageUri,
                display_name = displayName,
                media_type = mediaType,
                content_hash = fingerprint,
                byte_size = byteSize,
                source_modified_at = null,
                is_linked = 0,
                resource_index = 0,
                created_at = now,
                updated_at = now,
            )
        }
        return requireNotNull(book(id))
    }

    /** Hiding changes shelf visibility only; the managed file and every reading position remain. */
    fun setHidden(id: String, hidden: Boolean, now: Long): Boolean {
        if (database.publicationsQueries.selectPublicationById(id).executeAsOneOrNull() == null) return false
        database.publicationsQueries.updatePublicationImportState(
            import_state = if (hidden) HIDDEN_STATE else READY_STATE,
            updated_at = now,
            id = id,
        )
        return true
    }

    /** The caller owns deleting managed files; this removes the database graph atomically. */
    fun deleteBook(id: String): Boolean {
        if (database.publicationsQueries.selectPublicationById(id).executeAsOneOrNull() == null) return false
        database.transaction {
            database.publicationsQueries.deletePublication(id)
        }
        return true
    }

    fun saveCover(
        publicationId: String,
        storageUri: String,
        contentHash: String,
        byteSize: Long,
        now: Long,
    ): Boolean {
        val publication = database.publicationsQueries.selectPublicationById(publicationId)
            .executeAsOneOrNull() ?: return false
        val artifactId = "cover:$publicationId"
        database.transaction {
            database.derivedArtifactsQueries.upsertCoverArtifact(
                id = artifactId,
                publication_id = publicationId,
                storage_uri = storageUri,
                source_fingerprint = publication.fingerprint,
                content_hash = contentHash,
                byte_size = byteSize,
                last_accessed_at = now,
                created_at = now,
                updated_at = now,
            )
            database.publicationsQueries.updatePublicationCover(
                cover_artifact_id = artifactId,
                updated_at = now,
                id = publicationId,
            )
        }
        return true
    }

    fun position(publicationId: String): StoredTextPosition? =
        database.locatorsQueries.selectReadingPosition(publicationId)
            .executeAsOneOrNull()
            ?.let { row ->
                StoredTextPosition(
                    anchor = TextAnchor(
                        characterOffset = row.structural_anchor
                            ?.removePrefix(TEXT_OFFSET_PREFIX)
                            ?.toIntOrNull()
                            ?: 0,
                        exact = row.text_exact.orEmpty(),
                        prefix = row.text_prefix.orEmpty(),
                        suffix = row.text_suffix.orEmpty(),
                    ),
                    progression = (row.total_progression ?: row.resource_progression ?: 0.0)
                        .toFloat()
                        .coerceIn(0f, 1f),
                )
            }

    fun savePosition(publicationId: String, position: StoredTextPosition, now: Long) {
        val locatorId = "reading:$publicationId"
        val progression = position.progression.coerceIn(0f, 1f).toDouble()
        database.transaction {
            val existing = database.locatorsQueries.selectLocatorById(locatorId).executeAsOneOrNull()
            if (existing == null) {
                database.locatorsQueries.insertLocator(
                    id = locatorId,
                    publication_id = publicationId,
                    resource_id = TEXT_RESOURCE_ID,
                    resource_index = 0,
                    resource_progression = progression,
                    total_progression = progression,
                    structural_anchor = "$TEXT_OFFSET_PREFIX${position.anchor.characterOffset}",
                    text_exact = position.anchor.exact,
                    text_prefix = position.anchor.prefix,
                    text_suffix = position.anchor.suffix,
                    pdf_page_index = null,
                    pdf_character_start = null,
                    pdf_character_end = null,
                    pdf_rect_x = null,
                    pdf_rect_y = null,
                    pdf_rect_width = null,
                    pdf_rect_height = null,
                    audio_media_item_id = null,
                    audio_time_us = null,
                    tts_chunk_id = null,
                    tts_source_word_index = null,
                    tts_sample_offset = null,
                    schema_version = 1,
                    created_at = now,
                    updated_at = now,
                )
            } else {
                database.locatorsQueries.updateLocator(
                    resource_id = TEXT_RESOURCE_ID,
                    resource_index = 0,
                    resource_progression = progression,
                    total_progression = progression,
                    structural_anchor = "$TEXT_OFFSET_PREFIX${position.anchor.characterOffset}",
                    text_exact = position.anchor.exact,
                    text_prefix = position.anchor.prefix,
                    text_suffix = position.anchor.suffix,
                    pdf_page_index = null,
                    pdf_character_start = null,
                    pdf_character_end = null,
                    pdf_rect_x = null,
                    pdf_rect_y = null,
                    pdf_rect_width = null,
                    pdf_rect_height = null,
                    audio_media_item_id = null,
                    audio_time_us = null,
                    tts_chunk_id = null,
                    tts_source_word_index = null,
                    tts_sample_offset = null,
                    schema_version = 1,
                    updated_at = now,
                    id = locatorId,
                    publication_id = publicationId,
                )
            }
            database.locatorsQueries.upsertReadingPosition(
                publication_id = publicationId,
                locator_id = locatorId,
                modality = "visual",
                rendering_context_json = null,
                updated_at = now,
            )
            database.publicationsQueries.markPublicationOpened(now, now, publicationId)
        }
    }

    fun epubPosition(publicationId: String): String? =
        database.locatorsQueries.selectReadingPosition(publicationId)
            .executeAsOneOrNull()
            ?.structural_anchor
            ?.takeIf { it.startsWith(EPUB_LOCATOR_PREFIX) }
            ?.removePrefix(EPUB_LOCATOR_PREFIX)

    fun saveEpubPosition(
        publicationId: String,
        locatorJson: String,
        resourceId: String,
        resourceProgression: Double?,
        totalProgression: Double?,
        textExact: String?,
        textPrefix: String?,
        textSuffix: String?,
        now: Long,
    ) {
        val locatorId = "reading:$publicationId"
        val safeResource = resourceProgression?.coerceIn(0.0, 1.0)
        val safeTotal = totalProgression?.coerceIn(0.0, 1.0)
        database.transaction {
            val existing = database.locatorsQueries.selectLocatorById(locatorId).executeAsOneOrNull()
            if (existing == null) {
                database.locatorsQueries.insertLocator(
                    locatorId, publicationId, resourceId, null, safeResource, safeTotal,
                    "$EPUB_LOCATOR_PREFIX$locatorJson", textExact, textPrefix, textSuffix,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    1, now, now,
                )
            } else {
                database.locatorsQueries.updateLocator(
                    resourceId, null, safeResource, safeTotal,
                    "$EPUB_LOCATOR_PREFIX$locatorJson", textExact, textPrefix, textSuffix,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    1, now, locatorId, publicationId,
                )
            }
            database.locatorsQueries.upsertReadingPosition(
                publicationId, locatorId, "visual", null, now,
            )
            database.publicationsQueries.markPublicationOpened(now, now, publicationId)
        }
    }

    fun audioPositionMs(publicationId: String): Long =
        database.locatorsQueries.selectReadingPosition(publicationId)
            .executeAsOneOrNull()
            ?.audio_time_us
            ?.div(1_000L)
            ?.coerceAtLeast(0L)
            ?: 0L

    fun saveAudioPosition(
        publicationId: String,
        positionMs: Long,
        durationMs: Long,
        now: Long,
    ) {
        val locatorId = "reading:$publicationId"
        val safePosition = positionMs.coerceAtLeast(0L)
        val progression = if (durationMs > 0L) {
            safePosition.toDouble().div(durationMs.toDouble()).coerceIn(0.0, 1.0)
        } else null
        database.transaction {
            val existing = database.locatorsQueries.selectLocatorById(locatorId).executeAsOneOrNull()
            if (existing == null) {
                database.locatorsQueries.insertLocator(
                    locatorId, publicationId, "audio:$publicationId", 0, progression, progression,
                    null, null, null, null, null, null, null, null, null, null, null,
                    publicationId, safePosition * 1_000L, null, null, null, 1, now, now,
                )
            } else {
                database.locatorsQueries.updateLocator(
                    resource_id = "audio:$publicationId",
                    resource_index = 0,
                    resource_progression = progression,
                    total_progression = progression,
                    structural_anchor = null,
                    text_exact = null,
                    text_prefix = null,
                    text_suffix = null,
                    pdf_page_index = null,
                    pdf_character_start = null,
                    pdf_character_end = null,
                    pdf_rect_x = null,
                    pdf_rect_y = null,
                    pdf_rect_width = null,
                    pdf_rect_height = null,
                    audio_media_item_id = publicationId,
                    audio_time_us = safePosition * 1_000L,
                    tts_chunk_id = null,
                    tts_source_word_index = null,
                    tts_sample_offset = null,
                    schema_version = 1,
                    updated_at = now,
                    id = locatorId,
                    publication_id = publicationId,
                )
            }
            database.locatorsQueries.upsertReadingPosition(
                publicationId, locatorId, "audio", null, now,
            )
            database.publicationsQueries.markPublicationOpened(now, now, publicationId)
        }
    }

    companion object {
        private const val READY_STATE = "ready"
        private const val HIDDEN_STATE = "hidden"
        private const val ORIGINAL_FILE_ROLE = "original"
        private const val TEXT_RESOURCE_ID = "content"
        private const val TEXT_OFFSET_PREFIX = "text-offset:"
        private const val EPUB_LOCATOR_PREFIX = "readium-locator-json:"

        private fun encodeSingleJsonString(value: String): String =
            "[\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

        private fun decodeFirstJsonString(value: String): String? {
            if (!value.startsWith("[\"") || !value.endsWith("\"]")) return null
            return value.substring(2, value.length - 2)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }

    private fun storedBook(
        publication: com.narratify.data.db.Publication,
        hidden: Boolean,
    ): StoredLibraryBook? {
        val file = database.filesQueries.selectFilesForPublication(publication.id)
            .executeAsList()
            .firstOrNull { it.role == ORIGINAL_FILE_ROLE }
            ?: return null
        return StoredLibraryBook(
            id = publication.id,
            title = publication.title,
            author = decodeFirstJsonString(publication.authors_json),
            displayName = file.display_name ?: publication.title,
            format = publication.format,
            storageUri = file.storage_uri,
            byteSize = file.byte_size,
            durationUs = publication.duration_us,
            importedAt = publication.created_at,
            progress = ((position(publication.id)?.progression ?: 0f) * 100f)
                .toInt()
                .coerceIn(0, 100),
            hidden = hidden,
            coverUri = publication.cover_artifact_id
                ?.let(database.derivedArtifactsQueries::selectArtifactById)
                ?.executeAsOneOrNull()
                ?.storage_uri,
        )
    }
}
