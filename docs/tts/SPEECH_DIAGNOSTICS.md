# Speech engine diagnostics

Settings → Diagnostics → **Speech engine details** opens a read-only page describing the voice
that is actually speaking. Everything on it is recorded in-process by `TtsDiagnostics`; nothing
is uploaded, stored, or written to disk.

## What the numbers mean

| Metric | Definition | Reading it |
|---|---|---|
| Real-time factor (RTF) | synthesis wall time ÷ audio produced | `0.25` means one second of speech took 250 ms to generate. Below `1.00` the engine outruns playback; above `1.00` narration will stutter. |
| Median / worst RTF | across the retained utterances | A good median with a bad worst case usually means one long paragraph stalled. |
| First-audio latency | queueing an utterance → first PCM block | What the reader feels after pressing Read. The chart marks 500 ms. |
| Audio produced | PCM frames ÷ sample rate | For the system engine there is no PCM, so this is the time it spent speaking. |
| Throughput | characters ÷ synthesis seconds | Comparable across devices; independent of sentence length. |
| Speed vs real time | 1 ÷ RTF, shown on the gauge | "Seconds of speech bought per second of compute." The arc saturates at 8×. |

The neural and system engines measure different things by necessity: Kokoro streams PCM, so its
synthesis time excludes playback, while Android's engine only reports start/finish, so its RTF
sits near `1.0` by construction. Compare neural runs to neural runs.

## Session boundaries

A session begins when a reader picks an engine, or when **Run benchmark** starts. Beginning a
session clears the per-utterance history; the last 60 utterances and 40 events are retained.

## Benchmark

**Run benchmark** speaks nothing aloud. It synthesizes three fixed sentences
(`TtsBenchmarkText`) through the currently selected engine: the neural path counts PCM without
opening AudioTrack, and the system path renders to a cache file, measures the WAV, and deletes
it. Use it to compare devices or to check a build without opening a book.

**Copy report** puts the whole snapshot — engine identity, digests, aggregates, per-utterance
rows, event log — on the clipboard as plain text.

## Choosing the voice

Voices lists the downloadable Kokoro pack and every offline system voice. The choice is explicit
and stored as `VoiceSelection`:

- `neural:<packId>` — Kokoro is primary. Set by the **Use as primary voice** switch on the Kokoro
  card, which appears once the pack is installed. A fresh download turns this on automatically
  unless a system voice was already pinned.
- `system:<voice name>` — the chosen platform voice, never overridden by an installed pack.
- `auto` — neural when a verified pack is installed, otherwise system speech.

`VoiceRouter` resolves the selection at reader start and records the reason on the diagnostics
page, so "why is this voice speaking" always has an answer. Removing the pack resets a
`neural:` selection to `auto` rather than leaving narration pointed at missing files.

## Narration outlives the screen

The speech session is owned by `ReaderNarration`, not by the reader view. Leaving the reader —
for the library, Settings, or the diagnostics page — only detaches the view, so playback
continues and reopening the book replays the current state and highlight into the returning
screen. The session ends on Stop, on opening a different book, or when the host activity is
destroyed.

A Read tap that lands while the engine is still loading is queued rather than dropped, and a
failure that used to be truncated in the one-line status label is now shown in full, with a
shortcut to the system speech settings when no offline voice is installed.

## Playback surfaces

| Surface | Where | What it controls |
|---|---|---|
| Text reader bar | Plain-text reader | Read/Pause, Stop, speed, status |
| EPUB reader bar | EPUB reader | Read/Pause, Stop, previous/next utterance, speed, live status from Readium |
| Media notification | System shade and lock screen | Whatever the media session exposes for the running EPUB narration or audiobook |
| Mini player | Library, Discover, Voices, Settings (phone and portrait tablet) | Title, engine status, play/pause, stop, tap to reopen |
| Player column | Library on a landscape tablet (≥840dp wide) | Cover, title, status, progress, stop / play-pause / open, speed |

The mini player is suppressed on the landscape library because the player column already covers
it. Pressing play in the column opens the book and starts narration in one step.

### Who owns a running narration

| Kind | Holder | Survives leaving the screen | Survives leaving the app |
|---|---|---|---|
| Plain text | `ReaderNarration` | yes | no — ends when the activity finishes |
| EPUB | `EpubNarration` + `NarrationPlaybackService` | yes | yes, with a media notification |
| Audiobook | `AudiobookPlaybackService` | yes | yes, with a media notification |

`EpubNarration` opens its own copy of the publication, so narration no longer depends on the
reader's rendering navigator. The reader attaches to whatever is already running: reopening a book
that is being narrated re-binds the controls instead of starting a second engine.

`NarrationPlaybackService` is a `MediaSessionService` hosting Readium's own media3 player
(`TtsNavigator.asMedia3Player()`). It calls `addSession` in `onCreate` — without that the service
never posts a notification and never enters the foreground, because nothing in the app connects a
`MediaController` to it. Swiping the app away stops narration through `onTaskRemoved`.

Background playback needs `POST_NOTIFICATIONS`; the EPUB reader asks for it once. If the reader
declines, narration still plays while the app is open but the service cannot stay in the
foreground, so the system will stop it shortly after the app is backgrounded.

## Where each setting applies

| Setting | Plain-text reader | EPUB reader | Applied live |
|---|---|---|---|
| Reading theme | whole app | whole app (Readium theme; True black maps to dark plus explicit colours) | on resume |
| Text size | yes | yes (`fontSize` factor relative to 20 pt, publisher styles disabled) | on resume |
| Line spacing | yes | yes (`lineHeight`) | on resume |
| Keep screen awake | yes | yes | on resume |
| Word highlighting | yes | yes, from the engine's word ranges | on resume (text reader) |
| Default speed | yes | yes | next play for the text reader; next open for EPUB |
| Voice selection | yes | yes — EPUBs run through `KokoroTtsEngine` when a neural voice is chosen | next open |

The reading theme is an app-wide palette (`AppPalette`): library, Discover, Voices, Settings,
now playing, the diagnostics page, and both readers resolve their colours from it, along with the
status and navigation bars. A unit test holds every theme to a 4.5:1 contrast floor for body text.

Both readers re-read preferences in `onResume`, so a change made in Settings takes effect when
the reader comes back to the foreground instead of only at the next open. Voice changes are
deliberately deferred to the next open: swapping engines mid-passage would drop the position.
