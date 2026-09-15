#!/usr/bin/env python3
"""Deterministic fake adapter for exercising the benchmark pipeline."""

from __future__ import annotations

import json
import re
import struct
import sys
import time
import wave
from pathlib import Path

WORD = re.compile(r"[^\W_]+(?:['’][^\W_]+)?", re.UNICODE)
working_directory = Path.cwd()
sample_rate = 24_000


def respond(request_id: str, result: dict | None = None, error: Exception | None = None) -> None:
    if error:
        payload = {"id": request_id, "ok": False, "error": {"code": "mock_error", "message": str(error)}}
    else:
        payload = {"id": request_id, "ok": True, "result": result or {}}
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def initialize(params: dict) -> dict:
    global working_directory, sample_rate
    started = time.perf_counter()
    working_directory = Path(params["working_directory"])
    working_directory.mkdir(parents=True, exist_ok=True)
    sample_rate = int(params.get("sample_rate_hz", 24_000))
    time.sleep(0.005)
    return {
        "protocol_version": 1,
        "engine": {
            "id": "mock", "name": "Deterministic mock", "version": "1",
            "runtime": "python-stdlib", "runtime_version": sys.version.split()[0],
            "model_sha256": "not-applicable", "voice_id": "silence",
            "quantization": "not-applicable", "phonemizer": "regex",
            "license_review_status": "not-applicable"
        },
        "capabilities": {
            "streaming": False, "word_timings": True,
            "timing_source": "synthetic-mock", "offline_verified": False
        },
        "initialization_ms": (time.perf_counter() - started) * 1000,
        "peak_rss_mb": 24.0,
        "is_mock": True
    }


def synthesize(params: dict) -> dict:
    started = time.perf_counter()
    text = params["text"]
    words = list(WORD.finditer(text))
    duration_s = max(0.4, len(words) / 2.65 + text.count(".") * 0.12 + text.count("\n") * 0.1)
    sample_count = int(duration_s * sample_rate)
    output = working_directory / params["output_path"]
    output.parent.mkdir(parents=True, exist_ok=True)
    silence = struct.pack("<h", 0)
    with wave.open(str(output), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        block = silence * min(sample_count, 8192)
        remaining = sample_count
        while remaining:
            count = min(remaining, 8192)
            wav.writeframesraw(block[: count * 2])
            remaining -= count
    timings = []
    for index, match in enumerate(words):
        start = round(index * sample_count / max(1, len(words)))
        end = round((index + 1) * sample_count / max(1, len(words)))
        timings.append({
            "text": match.group(0), "text_start": match.start(), "text_end": match.end(),
            "start_sample": start, "end_sample": end
        })
    elapsed = (time.perf_counter() - started) * 1000
    return {
        "audio_path": str(output), "sample_rate_hz": sample_rate, "channels": 1,
        "sample_count": sample_count, "first_audio_ms": elapsed,
        "adapter_synthesis_ms": elapsed, "peak_rss_mb": 25.0,
        "word_timings": timings
    }


def main() -> int:
    for raw in sys.stdin:
        request: dict = {}
        try:
            request = json.loads(raw)
            method = request["method"]
            if method == "initialize":
                respond(request["id"], initialize(request["params"]))
            elif method == "synthesize":
                respond(request["id"], synthesize(request["params"]))
            elif method == "shutdown":
                respond(request["id"], {})
                return 0
            else:
                raise ValueError(f"unknown method: {method}")
        except Exception as exc:
            respond(str(request.get("id", "unknown")), error=exc)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
