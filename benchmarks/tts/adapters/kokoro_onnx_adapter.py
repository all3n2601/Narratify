#!/usr/bin/env python3
"""Desktop evidence adapter for a locally provisioned Kokoro v1.0 ONNX export.

This adapter is intentionally not the shipping mobile implementation. It lets the
Gate 0 runner exercise real model inference while the equivalent native C/C++
adapter is built for iOS and Android.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import resource
import sys
import time
import wave
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def peak_rss_mb() -> float:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS reports bytes; Linux reports KiB.
    divisor = 1024 * 1024 if sys.platform == "darwin" else 1024
    return round(value / divisor, 3)


def write_pcm16(path: Path, audio, sample_rate: int) -> None:
    import numpy as np

    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--voices", type=Path, required=True)
    parser.add_argument("--voice", default="af_heart")
    parser.add_argument("--engine-id", default="kokoro-82m-v1.0-fp32")
    parser.add_argument("--quantization", default="fp32")
    parser.add_argument("--wrapper-revision", required=True)
    parser.add_argument("--offline-verified", action="store_true")
    return parser.parse_args()


def respond(request_id, result=None, error=None) -> None:
    response = {"id": request_id, "ok": error is None}
    response["result" if error is None else "error"] = result if error is None else error
    print(json.dumps(response, ensure_ascii=False), flush=True)


def main() -> int:
    args = arguments()
    model_checksum = sha256(args.model)
    voices_checksum = sha256(args.voices)
    engine = None
    working_directory = None

    for line in sys.stdin:
        request = json.loads(line)
        request_id = request.get("id")
        method = request.get("method")
        params = request.get("params", {})
        try:
            if method == "initialize":
                from kokoro_onnx import Kokoro
                import onnxruntime as ort

                started = time.perf_counter()
                options = ort.SessionOptions()
                options.intra_op_num_threads = int(params["threads"])
                options.inter_op_num_threads = 1
                session = ort.InferenceSession(
                    str(args.model), sess_options=options,
                    providers=["CPUExecutionProvider"]
                )
                engine = Kokoro.from_session(session, str(args.voices))
                initialization_ms = (time.perf_counter() - started) * 1000
                working_directory = Path(params["working_directory"])
                has_timings = bool(engine.has_timings)
                respond(request_id, {
                    "protocol_version": 1,
                    "engine": {
                        "id": args.engine_id,
                        "name": "Kokoro",
                        "version": "1.0",
                        "runtime": "onnxruntime",
                        "runtime_version": importlib.metadata.version("onnxruntime"),
                        "wrapper": "thewh1teagle/kokoro-onnx",
                        "wrapper_revision": args.wrapper_revision,
                        "model_sha256": model_checksum,
                        "voices_sha256": voices_checksum,
                        "voice_id": args.voice,
                        "quantization": args.quantization,
                        "phonemizer": "phonemizer + espeak-ng-loader",
                        "license_review_status": "pending",
                    },
                    "capabilities": {
                        # This protocol adapter waits for the whole waveform. The
                        # wrapper also offers batch streaming, but that is not
                        # evidence that this adapter delivers bounded PCM early.
                        "streaming": False,
                        "word_timings": False,
                        "timing_source": (
                            "phoneme-durations-without-source-word-map"
                            if has_timings else "unavailable"
                        ),
                        "offline_verified": args.offline_verified,
                    },
                    "initialization_ms": round(initialization_ms, 3),
                    "peak_rss_mb": peak_rss_mb(),
                })
            elif method == "synthesize":
                if engine is None or working_directory is None:
                    raise RuntimeError("adapter is not initialized")
                if not str(params.get("language", "")).lower().startswith("en"):
                    raise ValueError("this candidate run is English-only")
                started = time.perf_counter()
                # Full-utterance latency is reported as first-audio latency. The
                # production adapter must instead emit bounded PCM chunks.
                audio, sample_rate = engine.create(
                    params["text"], voice=args.voice, lang="en-us"
                )
                synthesis_ms = (time.perf_counter() - started) * 1000
                output = working_directory / params["output_path"]
                write_pcm16(output, audio, sample_rate)
                respond(request_id, {
                    "audio_path": str(output),
                    "sample_rate_hz": sample_rate,
                    "channels": 1,
                    "sample_count": len(audio),
                    "first_audio_ms": round(synthesis_ms, 3),
                    "adapter_synthesis_ms": round(synthesis_ms, 3),
                    "peak_rss_mb": peak_rss_mb(),
                    "word_timings": [],
                })
            elif method == "shutdown":
                respond(request_id, {})
                return 0
            else:
                respond(request_id, error={"code": "unknown_method", "message": str(method)})
        except Exception as error:  # keep passage failures protocol-safe
            respond(request_id, error={"code": type(error).__name__, "message": str(error)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
