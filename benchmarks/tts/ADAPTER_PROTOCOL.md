# TTS benchmark adapter protocol v1

An adapter is a long-lived process that reads one JSON object per line from
standard input and writes exactly one JSON response per line to standard output.
Diagnostics go to standard error. Requests and responses share an `id`.

All times are monotonic durations. Audio is signed little-endian PCM WAV. Paths
must be absolute or relative to the `working_directory` supplied at initialize.

## `initialize`

Request:

```json
{"id":"1","method":"initialize","params":{"working_directory":"/tmp/run","sample_rate_hz":24000,"channels":1,"threads":4}}
```

Successful response:

```json
{"id":"1","ok":true,"result":{"protocol_version":1,"engine":{"id":"kokoro-82m-v1.0-fp32","name":"Kokoro","version":"1.0","runtime":"onnxruntime","runtime_version":"1.x","model_sha256":"...","voice_id":"af_heart","quantization":"fp32","phonemizer":"flite-derived","license_review_status":"pending"},"capabilities":{"streaming":true,"word_timings":true,"timing_source":"predicted-phoneme-durations"},"initialization_ms":812.4,"peak_rss_mb":611.2}}
```

Initialization must include model loading and the first creation of runtime
sessions. Do not hide one-time work before process launch.

## `synthesize`

Request:

```json
{"id":"2","method":"synthesize","params":{"passage_id":"dialogue-001","text":"...","language":"en-US","output_path":"0001.wav"}}
```

Successful response:

```json
{"id":"2","ok":true,"result":{"audio_path":"0001.wav","sample_rate_hz":24000,"channels":1,"sample_count":188400,"first_audio_ms":281.3,"adapter_synthesis_ms":982.1,"peak_rss_mb":650.0,"word_timings":[{"text":"Good","text_start":0,"text_end":4,"start_sample":0,"end_sample":7200}]}}
```

`first_audio_ms` is measured inside the adapter from request receipt until the
first playable PCM frame becomes available. It is required even for
full-utterance engines, where it may equal synthesis latency.

Text offsets use Unicode code-point indices into the exact request string, not
UTF-8 bytes or platform UTF-16 units. Sample intervals are half-open. Timings
must be monotonic, non-overlapping, and within `sample_count`. If timing is not
available, return an empty array and advertise `timing_source: unavailable`.

The runner independently measures request-to-response wall time and calculates:

`RTF = wall_synthesis_seconds / audio_duration_seconds`

## `shutdown`

Request: `{"id":"3","method":"shutdown","params":{}}`

Return an empty successful result, flush diagnostics, and exit with status zero.

## Errors

Return `{"id":"...","ok":false,"error":{"code":"stable_code","message":"human readable"}}`.
The process should remain usable after passage-level input errors. Crashes,
timeouts, unsolicited stdout, missing output audio, and malformed timings fail
the run.

## Native integration notes

The protocol host is intentionally thin. The Kokoro adapter, and any future
candidate adapter, should call the same production C ABI that mobile applications use. Keep
model tokenization, phonemization, duration-to-word mapping, PCM ownership, and
cancellation below that boundary. Only copy bounded PCM chunks across Swift/JNI.

For mobile harnesses that cannot host stdin/stdout, serialize the same request
and response objects from an XCTest or Android instrumentation test. The result
schema is the compatibility boundary, not the desktop process mechanism.
