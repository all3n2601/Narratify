#!/usr/bin/env python3
"""Run the Narratify TTS JSON-lines adapter benchmark."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import platform
import random
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_CORPUS = ROOT / "test-fixtures" / "tts" / "corpus.jsonl"
DEFAULT_CONFIG = HERE / "benchmark-config.json"


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


class Adapter:
    def __init__(self, command: str):
        self.process = subprocess.Popen(
            shlex.split(command), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=None, text=True, encoding="utf-8", bufsize=1
        )
        self.sequence = 0

    def call(self, method: str, params: dict) -> dict:
        if not self.process.stdin or not self.process.stdout:
            raise RuntimeError("adapter pipes are unavailable")
        self.sequence += 1
        request_id = str(self.sequence)
        self.process.stdin.write(json.dumps({"id": request_id, "method": method, "params": params}, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError(f"adapter exited before replying to {method} (exit={self.process.poll()})")
        response = json.loads(line)
        if response.get("id") != request_id:
            raise RuntimeError(f"adapter response id mismatch: expected {request_id}, got {response.get('id')}")
        if not response.get("ok"):
            error = response.get("error", {})
            raise RuntimeError(f"adapter {method} failed: {error.get('code')}: {error.get('message')}")
        return response.get("result", {})

    def close(self) -> None:
        try:
            if self.process.poll() is None:
                self.call("shutdown", {})
        finally:
            if self.process.stdin:
                self.process.stdin.close()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()


def check_timings(timings: list[dict], text: str, sample_count: int) -> None:
    last_end = 0
    for timing in timings:
        start, end = int(timing["start_sample"]), int(timing["end_sample"])
        text_start, text_end = int(timing["text_start"]), int(timing["text_end"])
        if not (0 <= start <= end <= sample_count and start >= last_end):
            raise ValueError("word sample timings must be monotonic and inside the audio")
        if not (0 <= text_start < text_end <= len(text)):
            raise ValueError("word text offsets must be inside the request text")
        if text[text_start:text_end] != timing["text"]:
            raise ValueError("word timing text does not match its Unicode code-point range")
        last_end = end


def evaluate(summary: dict, config: dict, passage_count: int, category_count: int, capabilities: dict, engine: dict, is_mock: bool) -> dict:
    if is_mock:
        return {"status": "not-applicable", "checks": [{"name": "mock-run", "status": "not-applicable"}]}
    gates = config["hard_gates"]
    checks: list[dict] = []

    def maximum(name: str, value, limit) -> None:
        status = "incomplete" if value is None else ("pass" if value <= limit else "fail")
        checks.append({"name": name, "status": status, "value": value, "maximum": limit})

    maximum("cold_initialization_ms", summary.get("cold_initialization_ms"), gates["cold_initialization_ms_max"])
    maximum("sustained_p95_rtf", summary.get("sustained_rtf_p95"), gates["sustained_p95_rtf_max"])
    maximum("warm_first_audio_p95_ms", summary.get("warm_first_audio_p95_ms"), gates["warm_first_audio_p95_ms_max"])
    maximum("peak_rss_mb", summary.get("peak_rss_mb"), gates["peak_rss_mb_max"])
    maximum("thermal_underruns", summary.get("underruns"), gates["thermal_underruns_max"])
    maximum("word_start_error_median_ms", summary.get("word_start_error_median_ms"), gates["word_start_error_median_ms_max"])
    maximum("word_start_error_p95_ms", summary.get("word_start_error_p95_ms"), gates["word_start_error_p95_ms_max"])
    checks.append({"name": "minimum_passages", "status": "pass" if passage_count >= config["passage_policy"]["minimum_passages_for_decision"] else "incomplete", "value": passage_count})
    checks.append({"name": "minimum_categories", "status": "pass" if category_count >= config["passage_policy"]["minimum_categories_for_decision"] else "incomplete", "value": category_count})
    checks.append({"name": "offline_verified", "status": "pass" if capabilities.get("offline_verified") else "incomplete"})
    checks.append({"name": "commercial_license_review", "status": "pass" if engine.get("license_review_status") == "approved-commercial" else "incomplete", "value": engine.get("license_review_status")})
    statuses = {item["status"] for item in checks}
    status = "fail" if "fail" in statuses else ("incomplete" if "incomplete" in statuses else "pass")
    return {"status": status, "checks": checks}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adapter", required=True, help="quoted adapter command")
    parser.add_argument("--engine-id", required=True, help="expected engine variant id")
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--limit", type=int, default=6, help="passages for smoke run; 0 means all")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--category", action="append", help="category filter; repeatable")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--sample-rate", type=int, default=24000)
    parser.add_argument("--device-model", default=platform.machine())
    parser.add_argument("--device-platform", default=sys.platform)
    parser.add_argument("--os-version", default=platform.platform())
    parser.add_argument("--soc", default="unknown")
    parser.add_argument("--ram-mb", type=float)
    parser.add_argument("--power-state", default="unknown")
    parser.add_argument("--screen-state", default="on")
    parser.add_argument("--ambient-temperature-c", type=float)
    parser.add_argument("--notes", default="")
    return parser.parse_args()


def main() -> int:
    args = arguments()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    passages = load_jsonl(args.corpus)
    if args.category:
        passages = [passage for passage in passages if passage["category"] in set(args.category)]
    random.Random(config["passage_policy"]["deterministic_shuffle_seed"]).shuffle(passages)
    if args.limit:
        passages = passages[: args.limit]
    if not passages:
        raise SystemExit("no passages selected")

    measurements: list[dict] = []
    started_at = dt.datetime.now(dt.timezone.utc).isoformat()
    run_id = str(uuid.uuid4())
    with tempfile.TemporaryDirectory(prefix="narratify-tts-") as temporary:
        adapter = Adapter(args.adapter)
        try:
            initialized = adapter.call("initialize", {
                "working_directory": temporary, "sample_rate_hz": args.sample_rate,
                "channels": 1, "threads": args.threads
            })
            engine = initialized["engine"]
            if engine.get("id") != args.engine_id:
                raise RuntimeError(f"expected engine id {args.engine_id}, adapter reported {engine.get('id')}")
            capabilities = initialized["capabilities"]
            for repetition in range(1, args.repetitions + 1):
                for index, passage in enumerate(passages):
                    output_name = f"r{repetition:02d}-{index:04d}-{passage['id']}.wav"
                    wall_start = time.perf_counter()
                    synthesis = adapter.call("synthesize", {
                        "passage_id": passage["id"], "text": passage["text"],
                        "language": passage["language"], "output_path": output_name
                    })
                    wall_ms = (time.perf_counter() - wall_start) * 1000
                    audio_path = Path(synthesis["audio_path"])
                    if not audio_path.is_absolute():
                        audio_path = Path(temporary) / audio_path
                    with wave.open(str(audio_path), "rb") as wav:
                        frames, rate, channels = wav.getnframes(), wav.getframerate(), wav.getnchannels()
                    if frames != int(synthesis["sample_count"]) or rate != int(synthesis["sample_rate_hz"]) or channels != int(synthesis["channels"]):
                        raise ValueError(f"adapter metadata disagrees with WAV for {passage['id']}")
                    check_timings(synthesis.get("word_timings", []), passage["text"], frames)
                    audio_ms = frames / rate * 1000
                    measurements.append({
                        "passage_id": passage["id"], "category": passage["category"],
                        "repetition": repetition, "characters": len(passage["text"]),
                        "words": len(passage["text"].split()), "audio_duration_ms": round(audio_ms, 3),
                        "wall_synthesis_ms": round(wall_ms, 3), "adapter_synthesis_ms": synthesis.get("adapter_synthesis_ms"),
                        "rtf": round(wall_ms / audio_ms, 6), "first_audio_ms": synthesis["first_audio_ms"],
                        "peak_rss_mb": synthesis.get("peak_rss_mb"), "word_timing_count": len(synthesis.get("word_timings", []))
                    })
        finally:
            adapter.close()

    rtfs = [item["rtf"] for item in measurements]
    first_audio = [item["first_audio_ms"] for item in measurements[1:]] or [measurements[0]["first_audio_ms"]]
    memory = [float(item["peak_rss_mb"]) for item in measurements if item.get("peak_rss_mb") is not None]
    summary = {
        "cold_initialization_ms": initialized.get("initialization_ms"),
        "rtf_p50": percentile(rtfs, 0.50), "rtf_p95": percentile(rtfs, 0.95),
        "sustained_rtf_p95": None, "warm_first_audio_p95_ms": percentile(first_audio, 0.95),
        "peak_rss_mb": max(memory + [float(initialized.get("peak_rss_mb", 0))]),
        "underruns": None, "word_start_error_median_ms": None,
        "word_start_error_p95_ms": None
    }
    is_mock = bool(initialized.get("is_mock"))
    result = {
        "schema_version": 1,
        "run": {"id": run_id, "started_at_utc": started_at, "corpus_revision": config["corpus_revision"],
                "passage_count": len(passages), "repetitions": args.repetitions,
                "command": " ".join(shlex.quote(value) for value in sys.argv), "notes": args.notes, "is_mock": is_mock},
        "device": {"id": args.device_id, "platform": args.device_platform, "model": args.device_model,
                   "os_version": args.os_version, "soc": args.soc, "ram_mb": args.ram_mb,
                   "power_state": args.power_state, "screen_state": args.screen_state,
                   "ambient_temperature_c": args.ambient_temperature_c},
        "engine": engine, "capabilities": capabilities, "measurements": measurements,
        "summary": summary, "thermal_samples": [], "license_inventory": [],
        "gate_evaluation": evaluate(summary, config, len(passages), len({p["category"] for p in passages}), capabilities, engine, is_mock)
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(measurements)} measurements to {args.output}")
    print(f"Gate status: {result['gate_evaluation']['status']} (mock={is_mock})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
