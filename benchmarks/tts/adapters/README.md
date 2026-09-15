# Offline candidate adapter scaffolds

Each manifest pins the inputs a native adapter must receive. The repository does
not download them. Copy each manifest to a local untracked file, fill exact
paths/checksums, and run `preflight_candidate.py` before a benchmark.

The adapter executable must implement `../ADAPTER_PROTOCOL.md` and use the same
production native library intended for Swift/JNI. The C ABI in
`include/narratify_tts_benchmark.h` fixes the minimum lifecycle, streaming, and
timing surface without selecting an inference library.

`preflight_candidate.py` rejects URLs as asset paths, missing files, checksum
mismatches, unpinned versions, and unapproved license inventories. It performs
no network access.

Example after locally building and provisioning an adapter:

```sh
python3 benchmarks/tts/adapters/preflight_candidate.py local/kokoro-fp32.json \
  --require-license-approved
python3 benchmarks/tts/run_benchmark.py \
  --adapter /absolute/path/to/kokoro_adapter \
  --engine-id kokoro-82m-v1.0-fp32 \
  --device-id iphone-12 \
  --limit 0 --repetitions 3 \
  --output benchmarks/tts/results/kokoro-iphone12.json
```

Do not benchmark a network-backed phonemizer, remotely mounted model, or Python
reference pipeline as evidence for the shipping mobile implementation.
