# Shared playback

This module owns deterministic playback state, queue transitions, and effects.
It deliberately does not own an audio engine or platform media session.

Android and iOS integrations implement `PlaybackBackend`, translate native
callbacks into `PlaybackEvent` values, execute returned `PlaybackEffect`s, and
persist sessions through `PlaybackSnapshotStore`. This keeps Android audio
focus, iOS audio sessions, lock-screen controls, and replaceable TTS runtimes
outside the durable reader-position model.

Immediate persistence is requested for pause, seek, rate changes, failures,
item changes, and completion. Progress callbacks request throttled persistence.
The platform coordinator must additionally flush on interruption,
backgrounding, route changes, and termination.

The reducer has no clocks, threads, filesystem access, or media APIs, making
the same transitions testable on JVM, Android, and iOS Simulator.
