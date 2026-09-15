# Android system-TTS baseline

The TXT/Markdown reader can read from the currently visible line using an
installed Android `TextToSpeech` voice. It filters out every voice whose Android
metadata declares `isNetworkConnectionRequired`, prepares short utterances with
`shared:text`, exposes read/pause/stop and 0.5–2.0× speed controls, and highlights
reported word ranges. If an engine omits range callbacks, the active utterance is
highlighted instead.

This is a foreground baseline, not the premium neural implementation. Android
system voice quality, boundary callback accuracy, and available languages vary
by device and installed engine. Android has no real system-TTS pause API, so
pause stops synthesis and resume restarts at the last reported source token.
There is no background media service, notification/lock-screen controls, audio
cache, neural model, phoneme timing, or EPUB support in this slice.
