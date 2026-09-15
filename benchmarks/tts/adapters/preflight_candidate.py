#!/usr/bin/env python3
"""Offline preflight for a locally provisioned native TTS adapter."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from urllib.parse import urlparse


def resolve(base: Path, value: str) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else (base / path).resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-license-approved", action="store_true")
    parser.add_argument("--allow-placeholders", action="store_true", help="structural validation only")
    args = parser.parse_args()
    data = json.loads(args.manifest.read_text(encoding="utf-8"))
    errors: list[str] = []
    if data.get("schema_version") != 1 or not data.get("engine_id"):
        errors.append("manifest needs schema_version 1 and engine_id")
    if data.get("network_allowed") is not False:
        errors.append("network_allowed must be false")
    if not data.get("runtime_version") and not args.allow_placeholders:
        errors.append("runtime_version must be pinned")
    executable = data.get("adapter_executable")
    if not executable:
        if not args.allow_placeholders:
            errors.append("adapter_executable is not configured")
    else:
        executable_path = resolve(args.manifest.parent, executable)
        if not executable_path.is_file() or not executable_path.stat().st_mode & 0o111:
            errors.append(f"adapter executable missing or not executable: {executable_path}")
    for asset in data.get("assets", []):
        value, checksum = asset.get("path"), asset.get("sha256")
        if not value or not checksum:
            if not args.allow_placeholders:
                errors.append(f"{asset.get('role')} asset path/checksum is not configured")
            continue
        if urlparse(value).scheme in {"http", "https"}:
            errors.append(f"{asset.get('role')} asset must be local, not a URL")
            continue
        path = resolve(args.manifest.parent, value)
        if not path.is_file():
            errors.append(f"missing {asset.get('role')} asset: {path}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            errors.append(f"checksum mismatch for {asset.get('role')}: {path}")
    inventory_value = data.get("license_inventory")
    if not inventory_value:
        errors.append("license_inventory is required")
    else:
        inventory_path = resolve(args.manifest.parent, inventory_value)
        if not inventory_path.is_file():
            errors.append(f"license inventory not found: {inventory_path}")
        elif args.require_license_approved:
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
            if inventory.get("review_status") != "approved-commercial":
                errors.append(f"license inventory is {inventory.get('review_status')}, not approved-commercial")
    if errors:
        print("Candidate preflight failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    mode = "placeholder structure" if args.allow_placeholders else "local assets"
    print(f"Candidate preflight passed ({mode}): {data['engine_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
