# Database migration policy

The initial schema is **version 1**. Its serialized locator and playback rows
also carry `schema_version`; that version describes the record contract and is
separate from SQLite's database version.

For every database change after v1:

1. Add the next monotonically numbered SQLDelight `.sqm` file. Never edit a
   migration already included in a released build.
2. Prefer additive nullable columns, new tables, and backfills. Table rebuilds
   must explicitly preserve UUIDs, tombstones, timestamps, locators, bookmarks,
   highlights, and settings.
3. Do not migrate derived artifacts or FTS content at high cost. Mark artifacts
   stale and rebuild them; recreate/reindex FTS rows when its shape changes.
4. Make migration work restart-safe when it extends beyond SQLDelight's schema
   transaction. Record resumable work as an import/maintenance job.
5. Test migration from every previously released schema using fixtures that
   include Unicode quotation anchors, PDF/audio/TTS locators, tombstones,
   interrupted imports, partially installed voice packs, and stale artifacts.
6. Run `PRAGMA foreign_key_check` and application-level locator ownership checks
   after fixture migrations.

Database downgrades are unsupported. Before opening a database with a newer
version than the app understands, stop and show a recoverable upgrade-required
error rather than attempting destructive repair.

