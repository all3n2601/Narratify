# TTS fixture corpus

`corpus.jsonl` is a UTF-8, newline-delimited corpus for speech performance,
pronunciation, normalization, and timing tests. Every passage was generated for
Narratify and is dedicated to the public domain under CC0-1.0; it contains no
book excerpts or imitations of living authors.

Each row contains a stable `id`, primary `category`, BCP-47 `language`, `text`,
and feature `tags`. IDs and text must not be edited after results have been
recorded. Add a new corpus revision instead.

The corpus exceeds the 100-passage size gate and emphasizes linguistic coverage.
Its passages still require listening review; passage count alone is not an engine decision.
Timing-reference audio and manually annotated onset files are intentionally not
present yet; record them with a consistent narrator and store acquisition and
annotation metadata beside a future `timing-references.jsonl` file.
