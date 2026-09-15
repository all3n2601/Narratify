# Alignment hypothesis protocol

Any speech recognizer can feed the aligner by producing one JSON file: an array of word objects,
in the order they were spoken.

```json
[
  {"text": "the", "startMs": 0, "endMs": 320, "confidence": 0.97},
  {"text": "lantern", "startMs": 360, "endMs": 940, "confidence": 0.93}
]
```

| Field | Type | Rule |
|---|---|---|
| `text` | string | One word. Non-blank. Case, punctuation, and apostrophes are ignored by the aligner, so normalizing them is wasted work. |
| `startMs` | integer | Milliseconds from the start of the media item. Non-negative. Non-decreasing across the array. |
| `endMs` | integer | Not less than `startMs`. |
| `confidence` | number | 0.0 to 1.0. Currently recorded but not used to weight matching; a recognizer with no confidence should emit `1.0`. |

Rules the aligner depends on:

- **Words, not sub-word pieces.** A recognizer that emits byte-pair tokens must merge them, as
  `whisper_adapter.py` does. Un-merged pieces will not match book words and coverage collapses.
- **One media item per file.** Times are relative to that item. An M4B is one item per chapter
  mark; a folder of MP3s is one item per file.
- **Order is the contract.** The aligner never re-sorts. A recognizer that emits segments out of
  order must sort before writing.

The aligner does not need, and ignores: speaker labels, punctuation tokens, segment text,
language identification, and per-token log-probabilities beyond `confidence`.
