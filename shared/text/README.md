# Shared text preparation

Pure Kotlin Multiplatform preparation for offline TTS. The module accepts
semantic `SourceTextSpan` values and emits deterministic, source-preserving
`PreparedTtsChunk` values containing domain `SpokenToken`s. It performs no
phonemization, inference, audio generation, or platform I/O.

Source ranges use Kotlin UTF-16 offsets, matching the existing domain contract.
Normalization may expand a source token into several spoken words, but the token
continues to point to the exact original source range. Punctuation is retained
as a token so the model front end can preserve prosody.

The rule-based English normalizer is deliberately conservative. Unrecognised
languages retain lexical content unchanged. A future ICU-backed platform layer
can replace sentence rules without changing the output contract.
