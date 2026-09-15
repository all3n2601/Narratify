#!/usr/bin/env python3
"""Desktop evidence adapter for locally provisioned KittenTTS Mini 0.8.

This deliberately bypasses the SDK's network downloader and is not a shipping
mobile runtime. It exercises the pinned ONNX model through the common Gate 0
JSON-lines protocol so its measurements are directly comparable with Kokoro.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
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
    divisor = 1024 * 1024 if sys.platform == "darwin" else 1024
    return round(value / divisor, 3)


def write_pcm16(path: Path, audio, sample_rate: int) -> int:
    import numpy as np

    flattened = np.asarray(audio).reshape(-1)
    pcm = (np.clip(flattened, -1.0, 1.0) * 32767).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())
    return len(flattened)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--voices", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--voice", default="Bella")
    parser.add_argument("--engine-id", default="kittentts-mini-0.8")
    parser.add_argument("--sdk-revision", required=True)
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
    config_checksum = sha256(args.config)
    engine = None
    working_directory = None

    for line in sys.stdin:
        request = json.loads(line)
        request_id = request.get("id")
        method = request.get("method")
        params = request.get("params", {})
        try:
            if method == "initialize":
                from kittentts.onnx_model import KittenTTS_1_Onnx

                config = json.loads(args.config.read_text(encoding="utf-8"))
                started = time.perf_counter()
                engine = KittenTTS_1_Onnx(
                    str(args.model),
                    str(args.voices),
                    config.get("speed_priors", {}),
                    config.get("voice_aliases", {}),
                )
                initialization_ms = (time.perf_counter() - started) * 1000
                working_directory = Path(params["working_directory"])
                respond(request_id, {
                    "protocol_version": 1,
                    "engine": {
                        "id": args.engine_id,
                        "name": "KittenTTS Mini",
                        "version": "0.8",
                        "runtime": "onnxruntime",
                        "runtime_version": importlib.metadata.version("onnxruntime"),
                        "wrapper": "KittenML/KittenTTS",
                        "wrapper_revision": args.sdk_revision,
                        "model_sha256": model_checksum,
                        "voices_sha256": voices_checksum,
                        "config_sha256": config_checksum,
                        "voice_id": args.voice,
                        "quantization": "fp32",
                        "phonemizer": "phonemizer-fork + espeak-ng-loader",
                        "license_review_status": "pending",
                    },
                    "capabilities": {
                        "streaming": False,
                        "word_timings": False,
                        "timing_source": "unavailable",
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
                audio = engine.generate(
                    params["text"], voice=args.voice, speed=1.0, clean_text=False
                )
                synthesis_ms = (time.perf_counter() - started) * 1000
                output = working_directory / params["output_path"]
                sample_count = write_pcm16(output, audio, 24000)
                respond(request_id, {
                    "audio_path": str(output),
                    "sample_rate_hz": 24000,
                    "channels": 1,
                    "sample_count": sample_count,
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
        except Exception as error:
            respond(request_id, error={"code": type(error).__name__, "message": str(error)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
