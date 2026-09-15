# Reader Outline Sidebar — Design

Date: 2026-09-10
Status: Approved, not yet implemented

## Goal

Give every Narratify reader a table of contents. In landscape it appears as a
persistent left column beside the page; in portrait the same button opens a
modal panel. Android and iOS both ship it.

Applies to all three reader formats: EPUB, Markdown, and plain TXT.

## Why this is implemented twice

`iosApp` does not link the Kotlin Multiplatform modules. `iosApp/project.yml`
declares only the Readium Swift package; there is no shared framework
dependency. Writing the extraction logic once would mean adding iOS targets to
the shared modules and wiring a framework into Xcode — a real architectural
change that should not ride in on a sidebar feature.

So the outline is implemented natively on each platform, and the heuristics
below are the contract that keeps the two in step. Shared fixtures under
`test-fixtures/outline/` enforce it.

## Model

```
OutlineItem {
    title: String
    depth: Int        // 0-based; nesting level
    target: Target    // per-format, see below
}
```

`Target` is a Readium `Link`/`Locator` for EPUB and a character offset for
TXT/Markdown.

## Extraction per format

### EPUB

Read the publication's table of contents and flatten depth-first, recording
depth from the nesting. When it is empty — common in public-domain EPUBs — fall
back to the reading order (spine), using each item's title. Untitled entries
are numbered `Section N`. Both platforms already hold the `Publication` at
reader construction.

Both platforms read the manifest value directly (`publication.tableOfContents`
on Android, `publication.manifest.tableOfContents` on iOS) rather than iOS's
async `tableOfContents()` service. That service only computes a table of
contents for formats Narratify does not open (PDF); for EPUB it returns the
same manifest value, and awaiting it would send a non-Sendable `Publication`
across isolation while the navigator still holds it.

### Markdown

ATX headings only (`#` through `######`), `depth = level - 1`.

Setext headings (`===` / `---` underlines) are excluded. They are rare in
practice and a `---` line is ambiguous against a horizontal rule; treating one
as a heading would silently promote rules into the outline.

Headings inside fenced code blocks (` ``` ` or `~~~`) are ignored — a `#`
comment in a shell snippet is not a chapter.

Titles have inline emphasis markers stripped. Android's Markdown rendering
already removes them before the outline is built, so iOS strips them too;
otherwise `## A **Bold** Chapter` would title differently on each platform and
the shared fixtures could not assert titles at all.

### Plain TXT

TXT has no structure, so the outline is a heuristic. A line qualifies when all
of these hold:

- it is 60 characters or fewer,
- it is preceded and followed by a blank line,
- and it matches either:
  - `CHAPTER | PART | BOOK | SECTION | PROLOGUE | EPILOGUE | ACT`,
    case-insensitive, optionally followed by a numeral, or
  - a bare roman or arabic numeral.

All TXT items are depth 0.

**If fewer than 2 lines qualify, the book has no outline.** The panel shows
"No sections in this book". Narratify does not synthesize percentage marks or
page divisions — they would look like chapters, and they are not.

## Offset spaces differ by platform

The two apps already store reading positions against different strings, and the
outline must match whichever one its platform uses.

- **Android**: `PlainTextNormalizer.parse` strips Markdown markers and
  normalizes whitespace (CRLF, trailing spaces, runs of blank lines). Saved
  offsets index the *rendered* text.
- **iOS**: `ReaderScreen` keeps the raw source and renders per block. Offsets
  index the *raw* string.

Consequences:

1. Android extraction runs **inside `PlainTextNormalizer.parse`**, while both
   the source and the readable text are in hand, and emits offsets into the
   readable text. The outline becomes a field on `PlainTextDocument`.
   Extracting after the fact would yield offsets that match nothing.
2. iOS extracts from the raw string it already holds.
3. **Shared fixtures assert titles, depths, and order only.** Offsets are
   asserted per platform, in that platform's own space. Sharing expected
   offsets would produce green tests and a sidebar that jumps to the wrong
   paragraph.

## UI

### Entry point

A Contents button in every reader's top bar, in both orientations, for all
three formats.

Android needs a new hand-drawn `CONTENTS` glyph in `NarratifyIcon` — the app
draws all of its icons and has no icon dependency. iOS uses `list.bullet`.

### Landscape

A left column at `min(320dp, 32% of width)` inside the reader's existing root.
The page content shrinks rather than being covered, so the outline can be
scanned while reading.

Open/closed state persists — `AppPreferences` on Android, `AppSettings` on iOS
— and applies to landscape only. Portrait never inherits the flag.

### Portrait

The same button opens a modal panel that closes on selection.

- iOS: `.sheet` with `.presentationDetents([.medium, .large])`.
- Android: a scrim plus a panel added to the reader's root `FrameLayout`. The
  project depends on `androidx.fragment` and hand-built views, with no Material
  Components dependency; adding one for a single sheet is not warranted.

### List behavior

- Nesting shown by indent per `depth`.
- The current section is highlighted, and the list scrolls it into view when
  opened. Opening a 60-chapter book at the top every time makes the panel
  useless.
  - TXT/Markdown: the last item whose offset is at or before the reader's
    current offset.
  - EPUB: the item whose `Link` href matches the navigator's current locator
    href. Hrefs are compared by resource name, because the navigator reports a
    fully resolved location while the table of contents lists whatever relative
    path the publication authored. An exact match (fragment included) wins;
    otherwise the last entry in the same resource does, since a locator deeper
    in a chapter than any listed anchor belongs to the last anchor above it.

    An earlier draft ordered same-resource entries by the locator's
    progression. A table of contents link carries a fragment, not a
    progression, so there is nothing to compare against; the last-anchor rule
    is what can actually be implemented.
- Tapping an item navigates. In portrait the panel dismisses; in landscape it
  stays open.
- Empty state when the outline has fewer than 2 items.

### Rotation

The two Android readers rotate by different mechanisms and the implementation
must treat them separately:

- `EpubReaderActivity` declares
  `configChanges="keyboardHidden|orientation|screenSize|smallestScreenSize"`,
  so it does not recreate on rotate and currently has no
  `onConfigurationChanged`. It needs one, to swap the sidebar between column
  and overlay.
- `MainActivity` (which hosts the text readers) declares no `configChanges`, so
  it recreates and re-reads orientation at construction.

## Components

| Where | What |
| --- | --- |
| `shared/domain/…/BookOutline.kt` *(new)* | `OutlineItem` and the TXT/Markdown extractor, kept out of `PlainTextDocument.kt` so that file stays focused. |
| `shared/domain/…/PlainTextDocument.kt` | New `outline` field; `PlainTextNormalizer.parse` populates it. |
| `androidApp/…/ReaderOutlinePanel.kt` *(new)* | One view, two modes (column / overlay), used by both Android readers. |
| `androidApp/…/EpubSupport.kt` | TOC flattening and spine fallback. |
| `androidApp/…/TextReaderScreen.kt` | Button and layout wiring. |
| `androidApp/…/EpubReaderActivity.kt` | Button, layout, `onConfigurationChanged`. |
| `iosApp/Sources/BookOutline.swift` *(new)* | Model, TXT/Markdown extraction, TOC flattening. |
| `iosApp/Sources/OutlineSidebar.swift` *(new)* | Column and sheet presentation. |
| `iosApp/Sources/ReaderScreen.swift` | Wiring. |
| `iosApp/Sources/EPUBReaderScreen.swift` | Wiring. |
| `test-fixtures/outline/` *(new)* | Sample TXT and Markdown files plus expected titles/depths as JSON. |

## Testing

Fixture parity lives in `shared/domain/src/jvmTest` (common code cannot read
files from disk) and `iosApp/Tests/OutlineFixtureParityTests.swift`. Both load
the same fixture files and assert identical titles, depths, and order, then
assert offsets in their own space and verify the offset lands on the heading.
`BookOutlineTest` and `BookOutlineTests.swift` cover the heuristics themselves
with inline strings.

Cases to pin:

- TXT with no qualifying lines → empty outline.
- TXT with exactly one qualifying line → empty outline (below the 2-item floor).
- Markdown nesting that skips a level (`#` then `###`).
- A `---` horizontal rule that must not register as a setext heading.

The iOS fixtures must be added as a resource on the `NarratifyTests` target in
`iosApp/project.yml`.

**EPUB coverage is limited.** The repository has no EPUB fixture, so TOC
flattening is tested against a minimal generated EPUB. That exercises depth
handling and the spine fallback; it does not cover real-world TOC weirdness.

## Failure handling

- A TOC that fails to read never blocks opening the book: it degrades to the
  spine, then to the empty state.
- Extraction is a single linear line scan with no whole-document regex, and
  runs where `parse` already runs (`LocalLibraryRepository`, off the main
  thread), so a 20 MB TXT does not stall the open.
- Offsets are coerced into range before any scroll.
- iOS offsets are UTF-16 (`NSString`) positions, matching the block offsets the
  reader already scrolls by.

## Out of scope

Bookmarks, highlights, and search results in the panel. The panel shows the
outline only.
