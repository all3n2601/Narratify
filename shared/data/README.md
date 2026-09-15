# Narratify shared data schema

This directory contains the initial SQLDelight/SQLite persistence contract for
the shared Kotlin Multiplatform layer. The schema is intentionally independent
of a renderer, TTS engine, and platform file API.

## Layout

SQL sources live under:

`src/commonMain/sqldelight/com/narratify/data/db`

The logical groups are:

- `Publications.sq` and `Files.sq`: library identity and managed/linked files.
- `Locators.sq`: canonical positions plus the current visual/audio position.
- `Playback.sq`: the crash-safe playback resume snapshot.
- `Annotations.sq`: bookmarks and highlight ranges.
- `Settings.sq`: versioned JSON settings with global and per-book scopes.
- `ImportJobs.sq`: resumable import state.
- `DerivedArtifacts.sq`: disposable, versioned caches and conversions.
- `VoicePacks.sq`: installed/download-in-progress offline voice packs.
- `Search.sq`: portable metadata/publication text search cache.

IDs are opaque UUID strings. Timestamps are Unix epoch milliseconds. Media
times are microseconds, sample positions are integer frames, progressions are
inclusive values from 0 to 1, and normalized PDF rectangles use page-relative
coordinates.

JSON columns are used only for evolving collections or engine/platform payloads.
Their serializers must have independent versioning where needed. Queryable,
integrity-sensitive fields remain first-class columns.

## Invariants owned by repositories

SQLite foreign keys must be enabled on every connection. Repositories must:

1. Insert or update a locator and its `reading_position`/`playback_snapshot` in
   one transaction.
2. Verify that referenced locators belong to the same publication as the
   annotation or snapshot. SQLite cannot express that cross-table equality with
   the current single-column foreign keys.
3. Flush playback immediately on pause, seek, interruption, backgrounding, and
   termination; periodic writes should be throttled.
4. Treat derived artifacts and FTS rows as rebuildable. Never use either as the
   only source for user-authored data or current position.
5. Soft-delete user records (`publication`, `bookmark`, and `highlight`) so the
   model can support future sync. A later retention job may hard-delete local
   tombstones when sync is still disabled.
6. Never include filenames, book text, searches, highlights, or synthesized
   audio in logs, telemetry, crash metadata, or error details.

## Search portability

Android's framework SQLite does not consistently include FTS5, including on a
current API 36 emulator. The initial schema therefore uses a normal indexed,
denormalized search cache with a `LIKE` query. This is correct but will not scale
to large full-library indexes. Before full-text search ships, select and test a
bundled SQLite driver with FTS5 on the complete Android/iOS device matrix, then
migrate this disposable table to a virtual FTS5 table. Do not make application
startup depend on an optional system SQLite extension.

## Migrations

This is schema version 1, so no `.sqm` migration exists yet. The tracked
`databases/1.db` snapshot is the migration-verification baseline and the root
build verifies it against the current CREATE statements. Future changes follow
`MIGRATIONS.md`.
