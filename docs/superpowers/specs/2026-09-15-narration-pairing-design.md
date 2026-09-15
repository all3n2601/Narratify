# Pairing a narration with a book — design

**Status:** approved 2026-09-15. Android first; iOS is a separate spec.

## The problem

`LocalLibraryRepository.import()` creates one `LocalBook` per file. Importing an EPUB gives you a book; importing its M4B gives you a second, unrelated audiobook sitting beside it. Nothing in the app says the two belong together, so none of the alignment work built in `docs/superpowers/plans/2026-09-14-audiobook-forced-alignment.md` has an entry point a user can reach.

This spec covers pairing and chapter-level navigation. It does not cover word-level sync, which needs a speech recognizer on device and its own licensing, distribution, thermal, and battery gates.

## Decisions

| Question | Decision |
|---|---|
| What does a chapter tap do? | Seeks the audio *and* moves the reader. Opening a chapter in the reader also offers its audio. |
| How is a wrong chapter↔spine match handled? | A review screen after pairing: a global offset nudge plus per-row remap. Nothing is used until saved. |
| Which audio files are accepted? | Any of MP3, M4A, M4B. Chapters are shown when the file has them and skipped entirely when it does not. |

## Architecture

### Narration is a second file on the same publication

`library_file` is already keyed `(publication_id, role, storage_uri)` and `LocalLibraryStore.addBook` already writes `role = "original"`. Pairing inserts `role = "narration"` against the same `publication_id`. No schema change, and `derived_artifact` already keys future alignment results on `publication_id`.

Two consequences:

- `LocalLibraryStore.storedBook()` filters to `role == "original"` and returns null otherwise. It must keep doing that to establish identity, and load the narration separately.
- `StoredLibraryBook` gains a nullable `narration` rather than changing `storageUri`, so every existing caller keeps working.

### Chapters and their mapping live in their own table

A new SQLDelight table:

```sql
CREATE TABLE narration_chapter (
  publication_id TEXT NOT NULL,
  chapter_index INTEGER NOT NULL,
  title TEXT,
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  spine_index INTEGER,
  confirmed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (publication_id, chapter_index),
  FOREIGN KEY (publication_id) REFERENCES publication(id) ON DELETE CASCADE
);
```

Deliberately **not** `derived_artifact`. That table exists for data that can be thrown away and regenerated — it carries `producer_version` and a staleness sweep precisely so stale rows can be discarded. A mapping the user corrected by hand is not derived and must not be discarded by a version bump.

`confirmed` records that a human touched the row, so a later re-parse can leave it alone.

### Chapter extraction is byte-level and platform-independent

Neither `MediaMetadataRetriever` nor Media3 exposes MP4 chapter tracks, so chapters are read directly: `moov/udta/chpl` (Nero style) first, falling back to a `tref/chap`-referenced text track.

This goes in `:shared:playback`, whose stated job is audiobook playback state and platform-backend contracts. It is pure Kotlin with no Android dependency, runs on the JVM under test, and iOS will need the same reader.

A file with no chapters returns an empty list. That is an ordinary result, not a failure.

## Flow

1. A book's existing menu (`LibraryScreen.showBookMenu`) gains **Add narration**.
2. The picker accepts MP3, M4A, and M4B, reusing `AUDIO_EXTENSIONS` and the existing size guard.
3. The file is copied into managed storage the way `import()` already does, then recorded as `role = "narration"`.
4. Chapters are parsed.
   - **Chapters found:** the review screen opens. Rows read `Chapter 1 → Chapter One`. A global offset control shifts every unconfirmed row at once, which fixes the common front-matter case in one tap; individual rows can be remapped. Saving writes `narration_chapter`, marking touched rows `confirmed`.
   - **No chapters:** the review screen is skipped and one whole-file row is stored with `spine_index = null`. A one-row list pretending to be a chapter list is worse than saying plainly that the file has no chapters.
5. The book shows that it has a narration, with a way to remove it.

## Two-way linking

- **Chapter → book.** Tapping a chapter seeks the audio to `start_ms` and moves the reader to `spine_index`. A row with a null `spine_index` seeks audio only.
- **Book → chapter.** When the reader opens a spine item that a chapter maps to, the mini player offers that chapter.

A row with `confirmed = 0` still drives navigation, but the UI shows the mapping was guessed. A wrong jump then reads as a mapping that needs fixing rather than as a bug — which is the same stance the alignment work takes: degrade the claim, never make a confident wrong one.

## Error handling

| Case | Behaviour |
|---|---|
| Unsupported extension | Refused with the existing message listing accepted types. |
| File over the size guard | Refused with the existing audio size message. |
| Malformed or truncated MP4 atoms | Treated as "no chapters". Never throws into the UI. |
| Narration already paired | The action offers to replace it, removing the old managed file and its chapter rows. |
| Book removed | `ON DELETE CASCADE` clears chapter rows; the managed narration file is removed by the same staged-rename path `delete()` already uses. |

## Testing

- `Mp4ChapterReader` against synthetically constructed atom bytes, including truncated and malformed input. No checked-in M4B binary: synthetic bytes are deterministic, reviewable in a diff, and raise no licensing question.
- Mapping operations — offset, remap, confirm — as pure functions in `:shared:playback`, tested with no Android dependency.
- `narration_chapter` round-trips and cascade deletion in `:shared:data`'s existing JVM test setup.
- Repository-level pairing and replacement against a temporary directory.

## Out of scope

- Word-level sync and running `ForcedAligner`. Gated on an on-device recognizer.
- A folder of per-chapter MP3s. One narration file per book for now.
- Matching chapter titles against the EPUB table of contents. Positional mapping plus human correction is the agreed approach; title matching is silent when wrong and M4B titles are often just `Track 07`.
- iOS.
