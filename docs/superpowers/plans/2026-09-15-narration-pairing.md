# Narration Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let someone attach an MP3, M4A, or M4B narration to a book already in their library, and move between the two by chapter.

**Architecture:** A narration is a second `library_file` row with `role = "narration"` on the book's existing publication, not a second publication. Chapter marks are read from the file's MP4 atoms by a pure-Kotlin reader in `:shared:playback`, then stored with their chapter-to-spine mapping in a new `narration_chapter` table that the user can correct. Tapping a chapter seeks the audio and moves the reader; opening a mapped spine item offers its chapter.

**Tech Stack:** Kotlin Multiplatform (`:shared:data`, `:shared:playback`), SQLDelight 2.3.2 with migration verification, Android views (`:androidApp`), `kotlin.test` and JUnit 4.

**Spec:** [docs/superpowers/specs/2026-09-15-narration-pairing-design.md](../specs/2026-09-15-narration-pairing-design.md)

---

## Two things that will bite you

**1. The database verifies its own migrations.** `shared/data/build.gradle.kts` sets `verifyMigrations.set(true)` and a snapshot of schema version 1 is committed at `shared/data/src/commonMain/sqldelight/databases/1.db`. Adding a table by editing a `.sq` file alone will fail the build. Task 1 adds a real migration and regenerates the snapshot. The task names are long and easy to guess wrong:

```bash
./gradlew :shared:data:generateCommonMainNarratifyDatabaseSchema
./gradlew :shared:data:verifyCommonMainNarratifyDatabaseMigration
```

**2. Chapter coverage is partial by design, and that is fine.** Task 2 reads the Nero-style `moov/udta/chpl` atom, which is simple and well documented. It does **not** read QuickTime chapter *tracks* (a `tref/chap` reference to a text track), which is a much larger job involving sample tables. A file whose chapters cannot be read returns an empty list and is treated exactly like a file that has none — which the design already handles by skipping the review screen. Do not fake chapters for such a file. Reading QuickTime chapter tracks is listed as follow-on work.

## Prerequisites

Branch from the current work. The repository is a git repository with committed history on `feature/audiobook-forced-alignment`; check with `git status` before starting and confirm the tree is clean.

**Test commands used throughout:**

```bash
./gradlew :shared:playback:jvmTest
```

```bash
./gradlew :shared:data:jvmTest
```

```bash
./gradlew :androidApp:testDebugUnitTest
```

**Full suite:**

```bash
./gradlew check
```

**A naming rule.** Kotlin/Native rejects a comma inside a backticked declaration name (`Name contains illegal characters: ","`) while the JVM accepts it, so such a test passes `jvmTest` and then breaks an iOS compile. `:shared:playback` and `:shared:data` both build for iOS. No test name in this plan contains a comma; keep it that way.

## File structure

| File | Responsibility |
|---|---|
| `shared/data/src/commonMain/sqldelight/com/narratify/data/db/NarrationChapters.sq` | The `narration_chapter` table and its queries. |
| `shared/data/src/commonMain/sqldelight/com/narratify/data/db/1.sqm` | Migration from schema 1 to 2. |
| `shared/data/src/commonMain/sqldelight/databases/2.db` | Regenerated snapshot. |
| `shared/playback/src/commonMain/kotlin/app/narratify/playback/Mp4ChapterReader.kt` | Reads chapter marks from MP4 atom bytes. No I/O. |
| `shared/playback/src/commonMain/kotlin/app/narratify/playback/ChapterMapping.kt` | Pure operations on a mapping: build positional, offset, remap, confirm. |
| `shared/data/src/commonMain/kotlin/app/narratify/shared/data/LocalLibraryStore.kt` | Gains narration attach/read/replace and chapter persistence. |
| `androidApp/src/main/kotlin/app/narratify/LocalLibraryRepository.kt` | Gains `attachNarration`, copying into managed storage. |
| `androidApp/src/main/kotlin/app/narratify/NarrationMappingScreen.kt` | The review screen. |
| `androidApp/src/main/kotlin/app/narratify/LibraryScreen.kt` | The `Add narration` menu action. |
| `androidApp/src/main/kotlin/app/narratify/MainActivity.kt` | Picker wiring and screen navigation. |
| `androidApp/src/main/kotlin/app/narratify/EpubReaderActivity.kt` | Offers a chapter's audio when its spine item opens. |

---

## Task 1: The table and its migration

**Files:**
- Create: `shared/data/src/commonMain/sqldelight/com/narratify/data/db/NarrationChapters.sq`
- Create: `shared/data/src/commonMain/sqldelight/com/narratify/data/db/1.sqm`
- Create: `shared/data/src/commonMain/sqldelight/databases/2.db` (generated, then committed)

- [ ] **Step 1: Write the table and queries**

Create `NarrationChapters.sq`:

```sql
CREATE TABLE narration_chapter (
  publication_id TEXT NOT NULL,
  chapter_index INTEGER NOT NULL,
  title TEXT,
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  spine_index INTEGER,
  confirmed INTEGER NOT NULL DEFAULT 0 CHECK (confirmed IN (0, 1)),
  PRIMARY KEY (publication_id, chapter_index),
  FOREIGN KEY (publication_id) REFERENCES publication(id) ON DELETE CASCADE
);

CREATE INDEX narration_chapter_spine_idx
ON narration_chapter(publication_id, spine_index);

selectChapters:
SELECT * FROM narration_chapter
WHERE publication_id = ?
ORDER BY chapter_index;

selectChapterForSpine:
SELECT * FROM narration_chapter
WHERE publication_id = ? AND spine_index = ?
ORDER BY chapter_index
LIMIT 1;

insertChapter:
INSERT OR REPLACE INTO narration_chapter(
  publication_id, chapter_index, title, start_ms, end_ms, spine_index, confirmed
) VALUES (?, ?, ?, ?, ?, ?, ?);

deleteChapters:
DELETE FROM narration_chapter WHERE publication_id = ?;
```

- [ ] **Step 2: Write the migration**

Create `1.sqm`. SQLDelight names a migration for the version it migrates *from*, so `1.sqm` takes schema 1 to schema 2. It must contain the same statements as the table definition:

```sql
CREATE TABLE narration_chapter (
  publication_id TEXT NOT NULL,
  chapter_index INTEGER NOT NULL,
  title TEXT,
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  spine_index INTEGER,
  confirmed INTEGER NOT NULL DEFAULT 0 CHECK (confirmed IN (0, 1)),
  PRIMARY KEY (publication_id, chapter_index),
  FOREIGN KEY (publication_id) REFERENCES publication(id) ON DELETE CASCADE
);

CREATE INDEX narration_chapter_spine_idx
ON narration_chapter(publication_id, spine_index);
```

- [ ] **Step 3: Regenerate the schema snapshot**

Run: `./gradlew :shared:data:generateCommonMainNarratifyDatabaseSchema`
Expected: `BUILD SUCCESSFUL`, and a new `shared/data/src/commonMain/sqldelight/databases/2.db` appears.

- [ ] **Step 4: Verify the migration**

Run: `./gradlew :shared:data:verifyCommonMainNarratifyDatabaseMigration`
Expected: `BUILD SUCCESSFUL`.

If this fails with a mismatch between the migration and the `CREATE TABLE`, the two SQL blocks have diverged — they must be character-identical apart from surrounding whitespace. Do not resolve it by deleting `1.db`; that file is the record of what is on existing devices.

- [ ] **Step 5: Confirm the module still builds and tests**

Run: `./gradlew :shared:data:jvmTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add shared/data
git commit -m "feat(data): add the narration chapter table and its migration"
```

---

## Task 2: Reading chapter marks

An M4B stores chapters in atoms that neither `MediaMetadataRetriever` nor Media3 surfaces. This reads the Nero `chpl` atom directly from bytes, so it is pure logic that runs under test on the JVM and will serve iOS unchanged.

**Files:**
- Create: `shared/playback/src/commonMain/kotlin/app/narratify/playback/Mp4ChapterReader.kt`
- Test: `shared/playback/src/commonTest/kotlin/app/narratify/playback/Mp4ChapterReaderTest.kt`

- [ ] **Step 1: Write the failing test**

The test builds atom bytes by hand rather than checking in an M4B. Synthetic bytes are deterministic, diffable, and raise no licensing question about a sample recording.

```kotlin
package app.narratify.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp4ChapterReaderTest {
    /** A box is a big-endian length, a four-character name, then its payload. */
    private fun box(name: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ) + name.encodeToByteArray() + payload
    }

    /** Nero stores each chapter as a 100-nanosecond timestamp, a title length, then the title. */
    private fun chpl(chapters: List<Pair<Long, String>>): ByteArray {
        var payload = byteArrayOf(1, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) + chapters.size.toByte()
        for ((startMs, title) in chapters) {
            val ticks = startMs * 10_000L
            val stamp = ByteArray(8) { index -> (ticks ushr (56 - index * 8)).toByte() }
            val bytes = title.encodeToByteArray()
            payload = payload + stamp + bytes.size.toByte() + bytes
        }
        return box("chpl", payload)
    }

    private fun file(chapters: List<Pair<Long, String>>, durationMs: Long = 20_000L): ByteArray =
        box("ftyp", ByteArray(8)) + box("moov", box("udta", chpl(chapters)))

    @Test
    fun `chapters are read in order with their titles`() {
        val chapters = Mp4ChapterReader.read(
            file(listOf(0L to "Opening", 5_000L to "The harbour", 12_500L to "The breakwater")),
            durationMs = 20_000L,
        )
        assertEquals(listOf("Opening", "The harbour", "The breakwater"), chapters.map { it.title })
        assertEquals(listOf(0L, 5_000L, 12_500L), chapters.map { it.startMs })
    }

    @Test
    fun `each chapter ends where the next begins`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(5_000L, chapters[0].endMs)
    }

    @Test
    fun `the last chapter ends at the end of the file`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(20_000L, chapters.last().endMs)
    }

    @Test
    fun `indices are contiguous from zero`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(listOf(0, 1), chapters.map { it.index })
    }

    @Test
    fun `a file with no chapter atom has no chapters`() {
        assertEquals(emptyList(), Mp4ChapterReader.read(box("ftyp", ByteArray(8)), durationMs = 20_000L))
    }

    @Test
    fun `an empty input has no chapters`() {
        assertEquals(emptyList(), Mp4ChapterReader.read(ByteArray(0), durationMs = 20_000L))
    }

    @Test
    fun `a truncated atom is treated as having no chapters rather than throwing`() {
        val whole = file(listOf(0L to "One", 5_000L to "Two"))
        for (cut in listOf(whole.size / 2, whole.size - 3, 9)) {
            assertEquals(
                emptyList(),
                Mp4ChapterReader.read(whole.copyOf(cut), durationMs = 20_000L),
                "a file cut to $cut bytes should yield no chapters",
            )
        }
    }

    @Test
    fun `a chapter starting after the file ends is dropped`() {
        val chapters = Mp4ChapterReader.read(
            file(listOf(0L to "One", 99_000L to "Impossible")),
            durationMs = 20_000L,
        )
        assertEquals(listOf("One"), chapters.map { it.title })
    }

    @Test
    fun `an untitled chapter keeps its place`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].title.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:playback:jvmTest`
Expected: compilation failure, `Unresolved reference: Mp4ChapterReader`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.playback

/** One chapter mark, with the audio it covers. */
data class AudioChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(index >= 0) { "Chapter index must be non-negative" }
        require(startMs >= 0) { "Chapter start must be non-negative" }
        require(endMs >= startMs) { "A chapter must not end before it starts" }
    }
}

/**
 * Reads chapter marks out of an MP4 container.
 *
 * Neither `MediaMetadataRetriever` nor Media3 exposes them, so the bytes are walked directly. Only
 * the Nero-style `moov/udta/chpl` atom is understood; a QuickTime chapter *track*, which is a
 * `tref/chap` reference into a text track's sample tables, is a much larger job and is not read
 * here.
 *
 * Every failure is the same failure: no chapters. A truncated file, an atom this reader does not
 * understand, and a file that genuinely has none all return an empty list, because the product
 * treats a narration without chapters as ordinary rather than broken. Throwing would turn a
 * playable file into an import error for no gain.
 */
object Mp4ChapterReader {
    private const val HEADER_BYTES = 8
    private const val NERO_TICKS_PER_MILLISECOND = 10_000L

    fun read(bytes: ByteArray, durationMs: Long): List<AudioChapter> {
        val payload = findBox(bytes, 0, bytes.size, listOf("moov", "udta", "chpl")) ?: return emptyList()
        return runCatching { parseNero(bytes, payload.first, payload.second, durationMs) }
            .getOrElse { emptyList() }
    }

    /** Returns the payload range of the last name in [path], or null if the path is not present. */
    private fun findBox(bytes: ByteArray, from: Int, to: Int, path: List<String>): Pair<Int, Int>? {
        if (path.isEmpty()) return from to to
        var cursor = from
        while (cursor + HEADER_BYTES <= to) {
            val size = readInt(bytes, cursor)
            if (size < HEADER_BYTES || cursor + size > to) return null
            val name = bytes.decodeToString(cursor + 4, cursor + HEADER_BYTES)
            if (name == path.first()) {
                return findBox(bytes, cursor + HEADER_BYTES, cursor + size, path.drop(1))
            }
            cursor += size
        }
        return null
    }

    private fun parseNero(bytes: ByteArray, from: Int, to: Int, durationMs: Long): List<AudioChapter> {
        // One byte of version, three of flags, four reserved, then the chapter count.
        var cursor = from + 8
        if (cursor >= to) return emptyList()
        val count = bytes[cursor].toInt() and 0xFF
        cursor += 1

        val starts = ArrayList<Pair<Long, String>>(count)
        repeat(count) {
            if (cursor + 9 > to) return emptyList()
            val ticks = readLong(bytes, cursor)
            cursor += 8
            val titleLength = bytes[cursor].toInt() and 0xFF
            cursor += 1
            if (cursor + titleLength > to) return emptyList()
            starts.add((ticks / NERO_TICKS_PER_MILLISECOND) to bytes.decodeToString(cursor, cursor + titleLength))
            cursor += titleLength
        }

        val withinFile = starts.filter { it.first <= durationMs }.sortedBy { it.first }
        return withinFile.mapIndexed { index, (startMs, title) ->
            AudioChapter(
                index = index,
                title = title,
                startMs = startMs,
                endMs = withinFile.getOrNull(index + 1)?.first ?: durationMs,
            )
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (offset in 0 until 8) value = (value shl 8) or (bytes[at + offset].toLong() and 0xFF)
        return value
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:playback:jvmTest`
Expected: `BUILD SUCCESSFUL`, 9 new tests passing alongside the module's existing ones.

- [ ] **Step 5: Commit**

```bash
git add shared/playback
git commit -m "feat(playback): read Nero chapter marks from an MP4 container"
```

---

## Task 3: The mapping, as pure functions

The chapter-to-spine mapping is guessed, then corrected by hand. Keeping the operations pure and in `:shared:playback` means the correction logic is tested without an emulator, and the screen that drives it holds no rules of its own.

**Files:**
- Create: `shared/playback/src/commonMain/kotlin/app/narratify/playback/ChapterMapping.kt`
- Test: `shared/playback/src/commonTest/kotlin/app/narratify/playback/ChapterMappingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterMappingTest {
    private fun chapters(count: Int) = List(count) { index ->
        AudioChapter(index = index, title = "Chapter ${index + 1}", startMs = index * 1_000L, endMs = (index + 1) * 1_000L)
    }

    @Test
    fun `a fresh mapping pairs chapters with spine items in order`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
        assertEquals(listOf(0, 1, 2), mapping.rows.map { it.spineIndex })
        assertTrue(mapping.rows.none { it.confirmed })
    }

    @Test
    fun `a chapter past the end of the book maps to nothing`() {
        val mapping = ChapterMapping.positional(chapters(4), spineCount = 2)
        assertEquals(listOf(0, 1, null, null), mapping.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset shifts every unconfirmed row`() {
        val shifted = ChapterMapping.positional(chapters(3), spineCount = 5).offsetBy(1)
        assertEquals(listOf(1, 2, 3), shifted.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset leaves a row the reader already fixed alone`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
            .remap(chapterIndex = 1, spineIndex = 4)
            .offsetBy(1)
        assertEquals(listOf(1, 4, 3), mapping.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset that would run off the front clears those rows rather than clamping`() {
        // Clamping would silently pile several chapters onto spine item zero and look deliberate.
        val shifted = ChapterMapping.positional(chapters(3), spineCount = 5).offsetBy(-2)
        assertEquals(listOf(null, null, 0), shifted.rows.map { it.spineIndex })
    }

    @Test
    fun `remapping marks only that row as confirmed`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5).remap(chapterIndex = 2, spineIndex = 0)
        assertEquals(listOf(false, false, true), mapping.rows.map { it.confirmed })
        assertEquals(0, mapping.rows[2].spineIndex)
    }

    @Test
    fun `a row can be cleared so a chapter moves no reader position`() {
        val mapping = ChapterMapping.positional(chapters(2), spineCount = 5).remap(chapterIndex = 0, spineIndex = null)
        assertNull(mapping.rows[0].spineIndex)
        assertTrue(mapping.rows[0].confirmed)
    }

    @Test
    fun `an empty chapter list maps to nothing without failing`() {
        val mapping = ChapterMapping.positional(emptyList(), spineCount = 5)
        assertTrue(mapping.rows.isEmpty())
        assertFalse(mapping.hasAnySpineLink)
    }

    @Test
    fun `a mapping reports whether it links anything at all`() {
        assertTrue(ChapterMapping.positional(chapters(2), spineCount = 5).hasAnySpineLink)
        assertFalse(ChapterMapping.positional(chapters(2), spineCount = 0).hasAnySpineLink)
    }

    @Test
    fun `the chapter covering a spine item is the first one mapped to it`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
        assertEquals(1, mapping.chapterForSpine(1)?.chapter?.index)
        assertNull(mapping.chapterForSpine(4))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:playback:jvmTest`
Expected: compilation failure, `Unresolved reference: ChapterMapping`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.playback

/** One chapter and the spine item it is believed to narrate. */
data class ChapterMappingRow(
    val chapter: AudioChapter,
    val spineIndex: Int?,
    val confirmed: Boolean,
)

/**
 * Which chapter narrates which part of the book.
 *
 * The first guess is positional, which is right for most audiobooks and wrong for any with front
 * matter, a narrator's introduction, or two printed chapters read as one. Since no rule fixes
 * every case, the mapping is something a reader can correct rather than something the app insists
 * on: [offsetBy] handles the common whole-book shift in one gesture, [remap] handles the rest, and
 * a corrected row is marked so a later shift does not undo the correction.
 */
data class ChapterMapping(val rows: List<ChapterMappingRow>) {
    val hasAnySpineLink: Boolean get() = rows.any { it.spineIndex != null }

    fun offsetBy(delta: Int): ChapterMapping = ChapterMapping(
        rows.map { row ->
            if (row.confirmed) {
                row
            } else {
                // A shifted row that falls outside the book is cleared rather than clamped:
                // clamping would stack several chapters on one spine item and look intentional.
                row.copy(spineIndex = row.spineIndex?.plus(delta)?.takeIf { it >= 0 })
            }
        },
    )

    fun remap(chapterIndex: Int, spineIndex: Int?): ChapterMapping = ChapterMapping(
        rows.map { row ->
            if (row.chapter.index == chapterIndex) row.copy(spineIndex = spineIndex, confirmed = true) else row
        },
    )

    fun chapterForSpine(spineIndex: Int): ChapterMappingRow? = rows.firstOrNull { it.spineIndex == spineIndex }

    companion object {
        fun positional(chapters: List<AudioChapter>, spineCount: Int): ChapterMapping = ChapterMapping(
            chapters.map { chapter ->
                ChapterMappingRow(
                    chapter = chapter,
                    spineIndex = chapter.index.takeIf { it < spineCount },
                    confirmed = false,
                )
            },
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:playback:jvmTest`
Expected: `BUILD SUCCESSFUL`, 10 new tests passing.

Note `an offset that would run off the front clears those rows rather than clamping` expects `[null, null, 0]` for a shift of -2 over three chapters. If it fails, do not change the expectation: clearing is the specified behaviour and clamping is the bug it guards against.

- [ ] **Step 5: Commit**

```bash
git add shared/playback
git commit -m "feat(playback): map chapters to spine items with correctable rows"
```

---

## Task 4: Persisting a narration and its chapters

**Files:**
- Modify: `shared/data/src/commonMain/kotlin/app/narratify/shared/data/LocalLibraryStore.kt`
- Test: `shared/data/src/jvmTest/kotlin/app/narratify/shared/data/NarrationStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Model the driver setup on the existing `LocalLibraryStoreTest`.

```kotlin
package app.narratify.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.narratify.shared.data.db.NarratifyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarrationStoreTest {
    private fun store(): LocalLibraryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return LocalLibraryStore(NarratifyDatabase(driver))
    }

    private fun LocalLibraryStore.seedBook(id: String = "book-1"): String {
        addBook(
            id = id,
            fingerprint = "hash-$id",
            format = "EPUB",
            title = "The Harbour",
            displayName = "harbour.epub",
            storageUri = "/books/$id.epub",
            mediaType = "application/epub+zip",
            byteSize = 1_000L,
            now = 1L,
        )
        return id
    }

    @Test
    fun `a book has no narration until one is attached`() {
        val store = store()
        val id = store.seedBook()
        assertNull(store.narration(id))
    }

    @Test
    fun `an attached narration survives a new store instance reading the same database`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(
            publicationId = id,
            storageUri = "/audio/$id.m4b",
            displayName = "harbour.m4b",
            mediaType = "audio/mp4",
            contentHash = "audio-hash",
            byteSize = 9_000L,
            now = 2L,
        )
        val narration = store.narration(id)
        assertEquals("/audio/$id.m4b", narration?.storageUri)
        assertEquals("harbour.m4b", narration?.displayName)
        assertEquals(9_000L, narration?.byteSize)
    }

    @Test
    fun `attaching a second narration replaces the first`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        store.attachNarration(id, "/audio/two.m4b", "two.m4b", "audio/mp4", "hash-two", 1L, 3L)
        assertEquals("/audio/two.m4b", store.narration(id)?.storageUri)
    }

    @Test
    fun `the original file is untouched by attaching a narration`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        assertEquals("/books/$id.epub", store.book(id)?.storageUri)
        assertEquals("EPUB", store.book(id)?.format)
    }

    @Test
    fun `chapters round trip in order`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(
            id,
            listOf(
                StoredChapter(0, "One", 0L, 5_000L, 0, confirmed = false),
                StoredChapter(1, "Two", 5_000L, 9_000L, 3, confirmed = true),
            ),
        )
        val chapters = store.chapters(id)
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(listOf(0, 3), chapters.map { it.spineIndex })
        assertEquals(listOf(false, true), chapters.map { it.confirmed })
    }

    @Test
    fun `saving chapters replaces whatever was there before`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(id, listOf(StoredChapter(0, "Old", 0L, 1L, 0, confirmed = false)))
        store.saveChapters(id, listOf(StoredChapter(0, "New", 0L, 1L, 1, confirmed = true)))
        assertEquals(listOf("New"), store.chapters(id).map { it.title })
    }

    @Test
    fun `the chapter for a spine item is found`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(
            id,
            listOf(
                StoredChapter(0, "One", 0L, 5_000L, 0, confirmed = false),
                StoredChapter(1, "Two", 5_000L, 9_000L, 3, confirmed = false),
            ),
        )
        assertEquals("Two", store.chapterForSpine(id, 3)?.title)
        assertNull(store.chapterForSpine(id, 9))
    }

    @Test
    fun `removing the book removes its chapters`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(id, listOf(StoredChapter(0, "One", 0L, 1L, 0, confirmed = false)))
        assertTrue(store.deleteBook(id))
        assertTrue(store.chapters(id).isEmpty())
    }

    @Test
    fun `detaching removes the narration and its chapters but keeps the book`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        store.saveChapters(id, listOf(StoredChapter(0, "One", 0L, 1L, 0, confirmed = false)))
        store.detachNarration(id)
        assertNull(store.narration(id))
        assertTrue(store.chapters(id).isEmpty())
        assertEquals("The Harbour", store.book(id)?.title)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:data:jvmTest`
Expected: compilation failure, `Unresolved reference: StoredChapter`.

- [ ] **Step 3: Add the types and the store functions**

In `LocalLibraryStore.kt`, add these data classes beside `StoredLibraryBook`:

```kotlin
data class StoredNarration(
    val storageUri: String,
    val displayName: String?,
    val mediaType: String?,
    val byteSize: Long,
)

data class StoredChapter(
    val index: Int,
    val title: String?,
    val startMs: Long,
    val endMs: Long,
    val spineIndex: Int?,
    val confirmed: Boolean,
)
```

Add `private const val NARRATION_FILE_ROLE = "narration"` beside the existing `ORIGINAL_FILE_ROLE`, then add these functions to the class:

```kotlin
    /**
     * Attaches a narration to a book that already exists.
     *
     * The narration is a second file on the same publication rather than a publication of its own,
     * so the book keeps one identity, one reading position, and one row in the library. Attaching
     * a second narration replaces the first: a book has one recorded reading here, and keeping
     * both would leave the chapter rows ambiguous about which file they index.
     */
    fun attachNarration(
        publicationId: String,
        storageUri: String,
        displayName: String?,
        mediaType: String?,
        contentHash: String,
        byteSize: Long,
        now: Long,
    ) {
        database.transaction {
            database.filesQueries.deleteFilesForRole(publicationId, NARRATION_FILE_ROLE)
            database.filesQueries.insertLibraryFile(
                id = "narration:$publicationId",
                publication_id = publicationId,
                role = NARRATION_FILE_ROLE,
                storage_uri = storageUri,
                display_name = displayName,
                media_type = mediaType,
                content_hash = contentHash,
                byte_size = byteSize,
                source_modified_at = null,
                is_linked = 0,
                resource_index = 0,
                created_at = now,
                updated_at = now,
            )
        }
    }

    fun narration(publicationId: String): StoredNarration? =
        database.filesQueries.selectFilesForPublication(publicationId)
            .executeAsList()
            .firstOrNull { it.role == NARRATION_FILE_ROLE }
            ?.let {
                StoredNarration(
                    storageUri = it.storage_uri,
                    displayName = it.display_name,
                    mediaType = it.media_type,
                    byteSize = it.byte_size,
                )
            }

    fun detachNarration(publicationId: String) {
        database.transaction {
            database.narrationChaptersQueries.deleteChapters(publicationId)
            database.filesQueries.deleteFilesForRole(publicationId, NARRATION_FILE_ROLE)
        }
    }

    fun saveChapters(publicationId: String, chapters: List<StoredChapter>) {
        database.transaction {
            database.narrationChaptersQueries.deleteChapters(publicationId)
            for (chapter in chapters) {
                database.narrationChaptersQueries.insertChapter(
                    publication_id = publicationId,
                    chapter_index = chapter.index.toLong(),
                    title = chapter.title,
                    start_ms = chapter.startMs,
                    end_ms = chapter.endMs,
                    spine_index = chapter.spineIndex?.toLong(),
                    confirmed = if (chapter.confirmed) 1L else 0L,
                )
            }
        }
    }

    fun chapters(publicationId: String): List<StoredChapter> =
        database.narrationChaptersQueries.selectChapters(publicationId).executeAsList().map(::storedChapter)

    fun chapterForSpine(publicationId: String, spineIndex: Int): StoredChapter? =
        database.narrationChaptersQueries
            .selectChapterForSpine(publicationId, spineIndex.toLong())
            .executeAsOneOrNull()
            ?.let(::storedChapter)

    private fun storedChapter(row: com.narratify.data.db.Narration_chapter): StoredChapter = StoredChapter(
        index = row.chapter_index.toInt(),
        title = row.title,
        startMs = row.start_ms,
        endMs = row.end_ms,
        spineIndex = row.spine_index?.toInt(),
        confirmed = row.confirmed == 1L,
    )
```

`StoredNarration` deliberately carries no duration. Nothing needs it persisted: chapter end times
are computed when the file is parsed and stored in `narration_chapter`, and the player reads length
from the file itself. The publication's own `duration_us` is the *book's* duration, set at import,
and writing an audio length into it for an EPUB would conflate two different things. A duration
column on `library_file` would mean another migration to store a number that is already available
wherever it is used.

- [ ] **Step 4: Add the missing query**

`Files.sq` has no per-role delete. Add one at the end:

```sql
deleteFilesForRole:
DELETE FROM library_file WHERE publication_id = ? AND role = ?;
```

Adding a query does not change the schema, so no migration is needed. Confirm that by running `./gradlew :shared:data:verifyCommonMainNarratifyDatabaseMigration` after this step; it should still pass.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :shared:data:jvmTest`
Expected: `BUILD SUCCESSFUL`, 9 new tests passing.

- [ ] **Step 6: Commit**

```bash
git add shared/data
git commit -m "feat(data): attach a narration and its chapters to an existing book"
```

---

## Task 5: Attaching a narration on Android

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/LocalLibraryRepository.kt`
- Create: `androidApp/src/main/kotlin/app/narratify/Mp4MoovReader.kt`
- Test: `androidApp/src/test/kotlin/app/narratify/Mp4MoovReaderTest.kt`

- [ ] **Step 1: Write the failing test**

An audiobook can be four gigabytes, so the chapter reader must not be handed the whole file. This locates the `moov` box and reads only that.

Create `androidApp/src/test/kotlin/app/narratify/Mp4MoovReaderTest.kt`:

```kotlin
package app.narratify

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4MoovReaderTest {
    @get:Rule val folder = TemporaryFolder()

    private fun box(name: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ) + name.toByteArray() + payload
    }

    private fun write(bytes: ByteArray): File =
        folder.newFile().apply { writeBytes(bytes) }

    @Test
    fun `finds the moov box when it follows the media data`() {
        val moov = box("moov", byteArrayOf(1, 2, 3, 4))
        val file = write(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(64)) + moov)
        assertArrayEquals(moov, Mp4MoovReader.read(file))
    }

    @Test
    fun `finds the moov box when it precedes the media data`() {
        val moov = box("moov", byteArrayOf(9, 9))
        val file = write(box("ftyp", ByteArray(8)) + moov + box("mdat", ByteArray(64)))
        assertArrayEquals(moov, Mp4MoovReader.read(file))
    }

    @Test
    fun `a file with no moov box reads as nothing`() {
        assertNull(Mp4MoovReader.read(write(box("ftyp", ByteArray(8)))))
    }

    @Test
    fun `a file that is not an mp4 at all reads as nothing`() {
        assertNull(Mp4MoovReader.read(write("ID3 this is an mp3".toByteArray())))
    }

    @Test
    fun `a truncated box header reads as nothing rather than throwing`() {
        assertNull(Mp4MoovReader.read(write(byteArrayOf(0, 0, 1))))
    }

    @Test
    fun `a box claiming to be larger than the file reads as nothing`() {
        val lying = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + "moov".toByteArray()
        assertNull(Mp4MoovReader.read(write(lying)))
    }

    @Test
    fun `an empty file reads as nothing`() {
        assertNull(Mp4MoovReader.read(write(ByteArray(0))))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.Mp4MoovReaderTest"`
Expected: compilation failure, `Unresolved reference: Mp4MoovReader`.

- [ ] **Step 3: Write the implementation**

Create `androidApp/src/main/kotlin/app/narratify/Mp4MoovReader.kt`:

```kotlin
package app.narratify

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads just the `moov` box out of an MP4 file.
 *
 * An audiobook is routinely a few gigabytes and the chapter marks live in one box that is usually
 * a few kilobytes, so the file is walked box by box and only that one is read into memory. Where
 * the box sits varies: a file written for streaming puts it before the media data and a file
 * written in one pass puts it after, so both orders are handled.
 *
 * Anything unexpected reads as null, which the caller treats the same as a file with no chapters.
 */
object Mp4MoovReader {
    private const val HEADER_BYTES = 8L
    /** Well beyond any real chapter table, and small enough that a corrupt length cannot exhaust memory. */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    fun read(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            var cursor = 0L
            while (cursor + HEADER_BYTES <= length) {
                handle.seek(cursor)
                val size = handle.readInt().toLong() and 0xFFFFFFFFL
                val name = ByteArray(4).also(handle::readFully).decodeToString()
                if (size < HEADER_BYTES || cursor + size > length) return null
                if (name == "moov") {
                    if (size > MAX_MOOV_BYTES) return null
                    handle.seek(cursor)
                    return ByteArray(size.toInt()).also(handle::readFully)
                }
                cursor += size
            }
            null
        }
    }.getOrNull()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.Mp4MoovReaderTest"`
Expected: `BUILD SUCCESSFUL`, 7 tests passing.

- [ ] **Step 5: Add `attachNarration` to the repository**

In `LocalLibraryRepository.kt`, add this method. It reuses the same extension and size guards the existing `import` applies, so a narration cannot sneak past limits that a standalone import would enforce.

```kotlin
    /**
     * Copies an audio file into managed storage and records it as this book's narration.
     *
     * Chapters are read here rather than on demand because the file is already open and a reader
     * who has just chosen it is the only person who will be shown the mapping. A file whose
     * chapters cannot be read is still attached — it simply has none, which the design treats as
     * ordinary rather than as a failed import.
     */
    fun attachNarration(bookId: String, uri: Uri): Result<NarrationAttachment> = runCatching {
        val book = requireNotNull(store.book(bookId)) { "This book is no longer in the library." }
        val resolver = context.contentResolver
        val metadata = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                        name to size
                    }
                }
        }.getOrNull()
        val displayName = metadata?.first?.takeIf(String::isNotBlank) ?: "Narration"
        val mediaType = runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.trim()?.lowercase()
        val extension = supportedExtension(displayName, mediaType)
        require(extension in AUDIO_EXTENSIONS) {
            "A narration must be an MP3, M4A, or M4B file."
        }
        require((metadata?.second ?: 0L) <= MAX_AUDIO_BYTES) {
            "Audio files larger than 4 GB are not supported."
        }

        val destination = File(filesDirectory, "${book.id}.narration.$extension")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "That file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }

        // Needed only to close the last chapter, which ends where the audio does. Not persisted:
        // see the note on StoredNarration in Task 4.
        val durationMs = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(destination.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull() ?: 0L

        val chapters = Mp4MoovReader.read(destination)
            ?.let { Mp4ChapterReader.read(it, durationMs) }
            .orEmpty()

        store.attachNarration(
            publicationId = book.id,
            storageUri = destination.absolutePath,
            displayName = displayName,
            mediaType = mediaType,
            contentHash = fingerprintOf(destination),
            byteSize = destination.length(),
            now = System.currentTimeMillis(),
        )
        NarrationAttachment(bookId = book.id, displayName = displayName, chapters = chapters)
    }

    fun detachNarration(bookId: String): Result<Unit> = runCatching {
        store.narration(bookId)?.storageUri?.let { path ->
            val managed = File(path).canonicalFile
            require(managed.parentFile == filesDirectory.canonicalFile) {
                "Narratify can only remove its own managed files."
            }
            managed.delete()
        }
        store.detachNarration(bookId)
    }
```

Add this type at the bottom of the file, outside the class:

```kotlin
/** What a freshly attached narration knows about itself before its mapping is reviewed. */
data class NarrationAttachment(
    val bookId: String,
    val displayName: String,
    val chapters: List<AudioChapter>,
)
```

Add the imports the new code needs: `app.narratify.playback.AudioChapter`, `app.narratify.playback.Mp4ChapterReader`.

If `fingerprintOf` does not already exist as a private helper on the class, reuse whatever the existing `import` path calls to produce a content hash; do not invent a second hashing scheme. Report which one you used.

- [ ] **Step 6: Wire `:shared:playback` into the Android app**

`androidApp/build.gradle.kts` does not depend on `:shared:playback` yet. Add it beside the other shared module dependencies:

```kotlin
    implementation(project(":shared:playback"))
```

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the module's tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

The design asked for a repository-level pairing test against a temporary directory. That is not
included, and the reason is worth recording rather than quietly dropping: `attachNarration` needs a
`Context` and a `ContentResolver`, this module has no Robolectric dependency, and adding one to test
a file copy is a poor trade. The parts with real logic — locating the `moov` box, parsing chapter
marks, and every mapping operation — are unit-tested without Android. The copy itself is covered by
the manual pass in this plan's Verification section. If pairing grows more logic than a copy and a
row insert, that judgement should be revisited.

- [ ] **Step 8: Commit**

```bash
git add androidApp
git commit -m "feat(android): copy a narration into managed storage and read its chapters"
```

---

## Task 6: Surfacing the narration and offering the action

**Files:**
- Modify: `shared/data/src/commonMain/kotlin/app/narratify/shared/data/LocalLibraryStore.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/LibraryScreen.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/MainActivity.kt`
- Test: `shared/data/src/jvmTest/kotlin/app/narratify/shared/data/NarrationStoreTest.kt` (add to the existing class)

- [ ] **Step 1: Write the failing test**

Add to `NarrationStoreTest`:

```kotlin
    @Test
    fun `a book reports whether it has a narration`() {
        val store = store()
        val id = store.seedBook()
        assertFalse(store.book(id)!!.hasNarration)
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        assertTrue(store.book(id)!!.hasNarration)
    }

    @Test
    fun `a book in the library list reports its narration too`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        assertTrue(store.books().single { it.id == id }.hasNarration)
    }
```

Add `import kotlin.test.assertFalse` if it is not already imported.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:data:jvmTest`
Expected: compilation failure, `Unresolved reference: hasNarration`.

- [ ] **Step 3: Add the field**

In `LocalLibraryStore.kt`, add a field to `StoredLibraryBook` with a default so no existing construction site breaks:

```kotlin
    val hasNarration: Boolean = false,
```

Then in `storedBook`, set it from the same file list already being read. The list is fetched once and filtered twice rather than queried twice:

```kotlin
    private fun storedBook(
        publication: com.narratify.data.db.Publication,
        hidden: Boolean,
    ): StoredLibraryBook? {
        val files = database.filesQueries.selectFilesForPublication(publication.id).executeAsList()
        val file = files.firstOrNull { it.role == ORIGINAL_FILE_ROLE } ?: return null
```

...and pass `hasNarration = files.any { it.role == NARRATION_FILE_ROLE }` in the returned `StoredLibraryBook`. Leave every other field exactly as it is, and keep the `?: return null` on the original file: a publication without its original file has no identity to show, which is why that guard exists.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:data:jvmTest`
Expected: `BUILD SUCCESSFUL`, 11 tests in `NarrationStoreTest`.

- [ ] **Step 5: Add the menu action**

In `LibraryScreen.kt`, add a constructor parameter beside the existing callbacks:

```kotlin
    private val onAddNarration: (LocalBook) -> Unit = {},
    private val onRemoveNarration: (LocalBook) -> Unit = {},
```

In `showBookMenu`, insert these rows between the hide row and the rule that precedes `Remove from library`:

```kotlin
            addView(context.ruleView(theme.rule))
            addView(bookActionRow(
                title = if (book.hasNarration) "Replace narration" else "Add narration",
                detail = if (book.hasNarration) {
                    "Swap the audiobook paired with this book"
                } else {
                    "Pair an MP3, M4A, or M4B so you can move between reading and listening"
                },
                color = ink,
            ) {
                dialog.dismiss()
                onAddNarration(book)
            })
            if (book.hasNarration) {
                addView(context.ruleView(theme.rule))
                addView(bookActionRow(
                    title = "Remove narration",
                    detail = "Delete the paired audio and its chapter list",
                    color = theme.danger,
                ) {
                    dialog.dismiss()
                    onRemoveNarration(book)
                })
            }
```

- [ ] **Step 6: Wire the picker**

In `MainActivity.kt`, `openPicker` currently starts `ACTION_OPEN_DOCUMENT` for a library import. Pairing needs a second destination for the result, so record which book is being paired before launching.

Add a field beside the activity's other state:

```kotlin
    /** Set while the document picker is open for a pairing rather than a library import. */
    private var pairingBookId: String? = null
```

Add the launcher:

```kotlin
    private fun openNarrationPicker(book: LocalBook) {
        pairingBookId = book.id
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4b"))
        }, REQUEST_NARRATION)
    }
```

Add `private const val REQUEST_NARRATION = 4210` to the activity's companion object, or a value not already used by the existing request codes — check them before choosing.

In `onActivityResult`, handle the new code. Passing `pairingBookId` through a field rather than the intent is deliberate: the picker returns only a document uri, and the book being paired is this activity's state, not the document's.

```kotlin
        if (requestCode == REQUEST_NARRATION) {
            val bookId = pairingBookId
            pairingBookId = null
            val uri = data?.data
            if (resultCode != RESULT_OK || bookId == null || uri == null) return
            repository.attachNarration(bookId, uri)
                .onSuccess(::showNarrationMapping)
                .onFailure { showSection(AppSection.LIBRARY, it.message) }
            return
        }
```

Pass the two new callbacks into both `LibraryScreen` construction sites:

```kotlin
                        onAddNarration = ::openNarrationPicker,
                        onRemoveNarration = ::removeNarration,
```

And add:

```kotlin
    private fun removeNarration(book: LocalBook) {
        repository.detachNarration(book.id)
            .onSuccess { showSection(AppSection.LIBRARY) }
            .onFailure { showSection(AppSection.LIBRARY, it.message) }
    }
```

`showNarrationMapping` is written in Task 7. Until then, stub it as a function that calls `showSection(AppSection.LIBRARY)` so this task compiles and can be committed on its own, and say in your report that you did so.

Check the real signature of `showSection` before using it — the error-message parameter above is assumed and may not exist. If it does not, report what the signature actually is rather than inventing an overload.

- [ ] **Step 7: Verify**

Run: `./gradlew :shared:data:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add shared/data androidApp
git commit -m "feat(android): offer add and remove narration on a book"
```

---

## Task 7: The mapping review screen

The guess is positional and will sometimes be wrong. This is where a reader fixes it in a few seconds instead of living with it.

**Files:**
- Create: `androidApp/src/main/kotlin/app/narratify/NarrationMappingScreen.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/MainActivity.kt`

- [ ] **Step 1: Read the surrounding idiom first**

This codebase builds its screens from Android views in Kotlin, not Compose, with helpers like `styledText`, `eyebrow`, `primaryButton`, `ruleView`, `glassShape`, `stack(Gap.X)`, and a palette from `enchantedLibraryPalette()`. Read `LibraryScreen.kt` and at least one other screen before writing anything, and match what is there. Do not introduce Compose, a new theming approach, or a different spacing scheme.

- [ ] **Step 2: Write the screen**

Create `androidApp/src/main/kotlin/app/narratify/NarrationMappingScreen.kt`:

```kotlin
package app.narratify

import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.narratify.playback.ChapterMapping

/**
 * Shows which chapter is believed to narrate which part of the book, and lets the reader fix it.
 *
 * The mapping starts as a guess in reading order, which is right for most audiobooks and wrong for
 * any with front matter or a narrator's introduction. Rather than hiding that, the guess is shown
 * before it is used: one control shifts the whole book, which fixes the common case in a tap, and
 * a row can be pointed somewhere else or cleared entirely. Nothing is stored until Save.
 */
class NarrationMappingScreen(
    context: Context,
    private val bookTitle: String,
    private val narrationName: String,
    private val spineTitles: List<String>,
    initialMapping: ChapterMapping,
    private val onSave: (ChapterMapping) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val theme = enchantedLibraryPalette()
    private var mapping = initialMapping
    private val rowHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(Gap.LG), dp(Gap.LG), dp(Gap.LG), dp(Gap.XL))
                addView(context.eyebrow("Check the chapters", theme.accent))
                addView(
                    context.styledText(bookTitle, Type.TITLE, theme.ink, Typeface.BOLD, serif = true),
                    stack(Gap.XS),
                )
                addView(
                    context.styledText(
                        "$narrationName was paired with this book. Narratify matched its chapters in " +
                            "order. Check them and fix anything that looks wrong.",
                        Type.BODY,
                        theme.inkMuted,
                    ),
                    stack(Gap.SM),
                )
                addView(offsetControls(), stack(Gap.LG))
                addView(rowHost, stack(Gap.LG))
                addView(primaryButton("Save") { onSave(mapping) }, stack(Gap.LG))
                addView(primaryButton("Cancel") { onCancel() }, stack(Gap.SM))
            })
        })
        renderRows()
    }

    private fun offsetControls(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(primaryButton("Audio starts earlier") { shift(-1) })
        addView(primaryButton("Audio starts later") { shift(1) }, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(Gap.SM) })
    }

    private fun shift(delta: Int) {
        mapping = mapping.offsetBy(delta)
        renderRows()
    }

    private fun renderRows() {
        rowHost.removeAllViews()
        if (mapping.rows.isEmpty()) {
            rowHost.addView(
                context.styledText(
                    "This file has no chapter marks, so there is nothing to match. You can still " +
                        "listen to it alongside the book.",
                    Type.BODY,
                    theme.inkMuted,
                ),
            )
            return
        }
        for (row in mapping.rows) {
            val chapterName = row.chapter.title.ifBlank { "Chapter ${row.chapter.index + 1}" }
            val target = row.spineIndex?.let { spineTitles.getOrNull(it) ?: "Section ${it + 1}" }
                ?: "Not matched"
            rowHost.addView(
                bookActionRow(
                    title = "$chapterName → $target",
                    detail = if (row.confirmed) "You set this" else "Matched in order",
                    color = if (row.spineIndex == null) theme.inkMuted else theme.ink,
                ) { showRowPicker(row.chapter.index) },
                stack(Gap.XS),
            )
        }
    }

    private fun showRowPicker(chapterIndex: Int) {
        val labels = listOf("Not matched") + spineTitles.mapIndexed { index, title ->
            title.ifBlank { "Section ${index + 1}" }
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Which part of the book?")
            .setItems(labels.toTypedArray()) { _, which ->
                mapping = mapping.remap(chapterIndex, spineIndex = (which - 1).takeIf { it >= 0 })
                renderRows()
            }
            .show()
    }
}
```

`bookActionRow`, `primaryButton`, `eyebrow`, `styledText`, `ruleView`, `dp`, `stack`, `Gap`, `Type`, and the palette's field names are all existing helpers in this module. Their exact signatures and whether they are members or extensions will differ from the calls above — **adapt the calls to the real helpers, not the helpers to these calls.** If a helper is private to `LibraryScreen`, extract it to a shared file rather than duplicating it, and say so in your report.

- [ ] **Step 3: Show it from the activity**

Replace the Task 6 stub with a real implementation. The spine titles come from the book's own table of contents; the app already builds an outline for the reader, so use that rather than adding a second parser — read `ReaderOutline.kt` and `EpubSupport.kt` to find what is available, and report which you used.

```kotlin
    private fun showNarrationMapping(attachment: NarrationAttachment) {
        val book = repository.books().firstOrNull { it.id == attachment.bookId } ?: return
        val spineTitles = narrationSpineTitles(book)
        if (attachment.chapters.isEmpty()) {
            // Nothing to match, so do not show a review screen with one meaningless row.
            repository.saveChapters(book.id, ChapterMapping(emptyList()))
            showSection(AppSection.LIBRARY)
            return
        }
        setContentView(NarrationMappingScreen(
            context = this,
            bookTitle = book.title,
            narrationName = attachment.displayName,
            spineTitles = spineTitles,
            initialMapping = ChapterMapping.positional(attachment.chapters, spineTitles.size),
            onSave = { mapping ->
                repository.saveChapters(book.id, mapping)
                showSection(AppSection.LIBRARY)
            },
            onCancel = { showSection(AppSection.LIBRARY) },
        ))
    }
```

- [ ] **Step 4: Define the spine titles helper**

Both this task and Task 8 need the book's sections as a list whose indices are spine indices.
`ReaderOutline.fromTableOfContents(tableOfContents, readingOrder)` already produces exactly that
ordering — its final `readingOrder.mapIndexed { index, link -> ... }` means entry *i* is reading
order item *i* — so nothing new needs parsing.

Add to `MainActivity.kt`:

```kotlin
    /**
     * The book's sections, indexed the way a spine index is.
     *
     * Reuses the outline the reader already builds rather than parsing the package document
     * again: two parsers would eventually disagree about what section three is, and the mapping a
     * reader corrected would start pointing somewhere else.
     */
    private fun narrationSpineTitles(book: LocalBook): List<String> =
        runCatching { repository.outlineEntries(book) }
            .getOrDefault(emptyList())
            .map(OutlineEntry::title)
```

And in `LocalLibraryRepository.kt`, expose the outline for a book. Read `EpubSupport.kt` first:
`EpubService.inspect` already opens the publication and reads `publication.readingOrder`, so follow
whatever that does to reach `tableOfContents` and `readingOrder`, and convert each to `OutlineLink`.

```kotlin
    /** The reader outline for a book, empty when the format has no sections to speak of. */
    fun outlineEntries(book: LocalBook): List<OutlineEntry>
```

Implement it for `format == "EPUB"` using the existing `epubService`. For a text or Markdown book
return an empty list — a plain text file has no spine to map chapters onto, and an empty list makes
every chapter audio-only, which is the honest result. Report the exact Readium accessors you used.

- [ ] **Step 5: Add the repository save**

In `LocalLibraryRepository.kt`:

```kotlin
    fun saveChapters(bookId: String, mapping: ChapterMapping) {
        store.saveChapters(
            bookId,
            mapping.rows.map { row ->
                StoredChapter(
                    index = row.chapter.index,
                    title = row.chapter.title.ifBlank { null },
                    startMs = row.chapter.startMs,
                    endMs = row.chapter.endMs,
                    spineIndex = row.spineIndex,
                    confirmed = row.confirmed,
                )
            },
        )
    }
```

Add the imports for `ChapterMapping` and `StoredChapter`.

- [ ] **Step 6: Verify it builds**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add androidApp
git commit -m "feat(android): review and correct the chapter mapping before it is used"
```

---

## Task 8: Moving between the two

The pairing is only worth anything if it moves you. This is the task with the most existing code to fit into, so it begins by reading rather than writing.

**Files:**
- Create: `androidApp/src/main/kotlin/app/narratify/NarrationChaptersScreen.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/LibraryScreen.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/MainActivity.kt`
- Modify: `androidApp/src/main/kotlin/app/narratify/EpubReaderActivity.kt`

- [ ] **Step 1: Read the two integration points and report what you find**

Before writing anything, read and summarise in your report:

1. `AudiobookPlaybackService.kt` — how playback is started for a file and whether it accepts a start position. If it does not, find how `MainActivity.openBook(book, autoPlay = true)` reaches it, and what the smallest honest way to pass a millisecond offset is.
2. `EpubReaderActivity.kt` — how a `Locator` is built and applied (`initialLocator`, `navigator?.currentLocator`), and how to derive the current spine index from a locator. Readium exposes reading order by index; find the accessor this codebase already uses rather than adding a new dependency.

If either turns out to need more than a small change, **stop and report** before implementing. A large refactor of playback belongs in its own task, not hidden inside this one.

- [ ] **Step 2: Write the chapter list**

Create `androidApp/src/main/kotlin/app/narratify/NarrationChaptersScreen.kt`, following the same view idiom as `NarrationMappingScreen`:

```kotlin
package app.narratify

import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.narratify.shared.data.StoredChapter

/**
 * The chapters of a paired narration.
 *
 * A row says where it came from. A chapter Narratify matched in reading order is a guess, and
 * saying so is what keeps a wrong jump legible as a mapping to fix rather than as a bug in the
 * reader — the same reason the aligner lowers its granularity instead of overstating a match.
 */
class NarrationChaptersScreen(
    context: Context,
    private val bookTitle: String,
    private val chapters: List<StoredChapter>,
    private val spineTitles: List<String>,
    private val onPlay: (StoredChapter) -> Unit,
    private val onEditMapping: () -> Unit,
) : FrameLayout(context) {
    private val theme = enchantedLibraryPalette()

    init {
        addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(Gap.LG), dp(Gap.LG), dp(Gap.LG), dp(Gap.XL))
                addView(context.eyebrow("Chapters", theme.accent))
                addView(
                    context.styledText(bookTitle, Type.TITLE, theme.ink, Typeface.BOLD, serif = true),
                    stack(Gap.XS),
                )
                if (chapters.isEmpty()) {
                    addView(
                        context.styledText(
                            "This narration has no chapter marks, so it plays as one piece.",
                            Type.BODY,
                            theme.inkMuted,
                        ),
                        stack(Gap.SM),
                    )
                } else {
                    for (chapter in chapters) {
                        val name = chapter.title?.takeIf(String::isNotBlank)
                            ?: "Chapter ${chapter.index + 1}"
                        val target = chapter.spineIndex?.let {
                            spineTitles.getOrNull(it)?.takeIf(String::isNotBlank) ?: "Section ${it + 1}"
                        }
                        addView(
                            bookActionRow(
                                title = name,
                                detail = when {
                                    target == null -> "Audio only — not matched to the book"
                                    chapter.confirmed -> "Opens $target"
                                    else -> "Opens $target — matched in order"
                                },
                                color = theme.ink,
                            ) { onPlay(chapter) },
                            stack(Gap.XS),
                        )
                    }
                    addView(primaryButton("Fix the matching") { onEditMapping() }, stack(Gap.LG))
                }
            })
        })
    }
}
```

Adapt every helper call to the real signatures in this module, as in Task 7.

- [ ] **Step 3: Add a Chapters action to the book menu**

In `LibraryScreen.kt`, add a constructor parameter:

```kotlin
    private val onOpenChapters: (LocalBook) -> Unit = {},
```

And in `showBookMenu`, immediately before the `Add narration` row, add:

```kotlin
            if (book.hasNarration) {
                addView(context.ruleView(theme.rule))
                addView(bookActionRow(
                    title = "Chapters",
                    detail = "Jump between the audio and the text",
                    color = ink,
                ) {
                    dialog.dismiss()
                    onOpenChapters(book)
                })
            }
```

- [ ] **Step 4: Wire it, and make a chapter tap do both things**

In `MainActivity.kt`:

```kotlin
    private fun openChapters(book: LocalBook) {
        val chapters = repository.chapters(book.id)
        val spineTitles = narrationSpineTitles(book)
        setContentView(NarrationChaptersScreen(
            context = this,
            bookTitle = book.title,
            chapters = chapters,
            spineTitles = spineTitles,
            onPlay = { chapter -> playChapter(book, chapter) },
            onEditMapping = { editChapterMapping(book) },
        ))
    }
```

`playChapter` is where Step 1's reading pays off: it seeks the narration to `chapter.startMs` and, when the chapter has a `spineIndex`, opens the reader there. Implement it with the smallest change the existing playback and reader entry points allow, and describe in your report exactly what you did and what you had to add.

`editChapterMapping(book)` rebuilds a `ChapterMapping` from the stored rows and reuses `NarrationMappingScreen`, so there is one screen for correcting a mapping rather than two. Rebuild it with:

```kotlin
    private fun editChapterMapping(book: LocalBook) {
        val stored = repository.chapters(book.id)
        val spineTitles = narrationSpineTitles(book)
        val mapping = ChapterMapping(
            stored.map { chapter ->
                ChapterMappingRow(
                    chapter = AudioChapter(
                        index = chapter.index,
                        title = chapter.title.orEmpty(),
                        startMs = chapter.startMs,
                        endMs = chapter.endMs,
                    ),
                    spineIndex = chapter.spineIndex,
                    confirmed = chapter.confirmed,
                )
            },
        )
        setContentView(NarrationMappingScreen(
            context = this,
            bookTitle = book.title,
            narrationName = repository.narrationName(book.id) ?: "This narration",
            spineTitles = spineTitles,
            initialMapping = mapping,
            onSave = { corrected ->
                repository.saveChapters(book.id, corrected)
                openChapters(book)
            },
            onCancel = { openChapters(book) },
        ))
    }
```

Add to `LocalLibraryRepository`:

```kotlin
    fun chapters(bookId: String): List<StoredChapter> = store.chapters(bookId)

    fun narrationName(bookId: String): String? = store.narration(bookId)?.displayName

    fun chapterForSpine(bookId: String, spineIndex: Int): StoredChapter? =
        store.chapterForSpine(bookId, spineIndex)
```

Add `onOpenChapters = ::openChapters` to both `LibraryScreen` construction sites.

- [ ] **Step 5: Offer the chapter from the reader**

In `EpubReaderActivity.kt`, when the current locator's spine index maps to a chapter, offer it. Add a control to the existing control bar built by `makeControlBar()` rather than inventing a new surface, and show it only when a chapter exists for the current position:

```kotlin
    /**
     * Offers the narration for the section being read.
     *
     * Only shown when a chapter actually maps to this spine item. A book with a narration whose
     * chapters were never matched will not offer anything here, which is correct: there is nothing
     * to seek to, and a button that guessed would be worse than no button.
     */
    private fun narrationChapterForCurrentPosition(): StoredChapter? {
        val spineIndex = currentSpineIndex() ?: return null
        return repository.chapterForSpine(bookId, spineIndex)
    }
```

`currentSpineIndex()` and `bookId` must come from what this activity already holds — Step 1 establishes what those are. If the activity has no repository reference, pass what it needs through its existing intent extras rather than constructing a repository inside the reader.

- [ ] **Step 6: Verify**

```bash
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

Then run the app and check the whole path by hand, because none of this is covered by a unit test: pair a narration with a book, correct one row, save, open Chapters, tap a chapter, confirm both the audio position and the reader position move. Report what you observed, including anything that behaved oddly.

- [ ] **Step 7: Commit**

```bash
git add androidApp
git commit -m "feat(android): jump between a narration and the book by chapter"
```

---

## Verification

```bash
./gradlew check && ./gradlew :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

Then, by hand on a device or emulator, with a real M4B that has chapter marks:

1. Import an EPUB. Open its menu — it offers **Add narration** and no chapter actions.
2. Add an M4B. The review screen appears with rows matched in order.
3. Shift the whole mapping one step, then correct a single row. Save.
4. Reopen the menu — it now offers **Chapters**, **Replace narration**, and **Remove narration**.
5. Open Chapters. The row you corrected says "You set this"; the others say "matched in order".
6. Tap a chapter. Audio starts at that chapter and the reader opens the matching section.
7. Remove the narration. The chapter actions disappear and the managed audio file is gone from `filesDir/publications`.

Also pair a plain MP3 with no chapter marks and confirm the review screen is skipped rather than showing a single meaningless row.

## Follow-on work

1. **QuickTime chapter tracks.** `Mp4ChapterReader` reads the Nero `chpl` atom only. Files whose chapters live in a `tref/chap`-referenced text track currently read as chapterless. Adding that means walking `stts`, `stsc`, `stco`, and `stsz` sample tables, which is a task of its own.
2. **A folder of per-chapter MP3s.** Many audiobooks ship as one file per chapter, where the files *are* the chapters and no atom parsing is needed. It needs multi-file import, ordering rules, and partial-failure handling.
3. **iOS.** The same feature against SwiftUI. `Mp4ChapterReader` and `ChapterMapping` are already in `:shared:playback` and need no changes.
4. **Word-level sync.** The aligner and its gate exist; what is missing is a recognizer on device, which carries its own licensing, model-distribution, thermal, and battery gates. When that lands, `narration_chapter` gives the alignment job its per-chapter audio ranges to work in.
5. **Matching chapter titles to the table of contents.** Positional matching plus human correction was chosen deliberately over title matching, which is silent when wrong and useless when titles are `Track 07`. Worth revisiting only with evidence that readers are correcting the same mappings repeatedly.
