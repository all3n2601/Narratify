#!/usr/bin/env python3
"""Validate completeness without treating declared metadata as legal approval."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REQUIRED = {"engine_variant", "component", "kind", "version_or_commit", "sha256", "source_url", "license_expression", "license_evidence_url", "redistributed", "commercial_conclusion", "notes"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("--require-approved", action="store_true")
    args = parser.parse_args()
    data = json.loads(args.inventory.read_text(encoding="utf-8"))
    errors: list[str] = []
    if data.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if data.get("review_status") not in {"pending", "approved-commercial", "rejected"}:
        errors.append("invalid review_status")
    policy = data.get("release_policy", {})
    prohibited = {name.lower() for name in policy.get("prohibited_components", [])}
    if not policy.get("downloaded_assets_are_data_only") or policy.get("downloaded_executable_code_allowed") is not False:
        errors.append("release_policy must require data-only downloads and prohibit downloaded executable code")
    components = data.get("components", [])
    if not components:
        errors.append("components must not be empty")
    for index, component in enumerate(components):
        missing = REQUIRED - component.keys()
        if missing:
            errors.append(f"component {index} missing {sorted(missing)}")
        if component.get("commercial_conclusion") not in {"pending", "approved", "rejected"}:
            errors.append(f"component {index} has invalid commercial_conclusion")
        normalized_name = str(component.get("component", "")).lower()
        if component.get("redistributed") and any(name in normalized_name for name in prohibited):
            errors.append(f"component {index} redistributes prohibited component: {component.get('component')}")
    if data.get("review_status") == "approved-commercial":
        if not data.get("reviewed_by") or not data.get("reviewed_at_utc"):
            errors.append("approved inventory requires reviewer and review timestamp")
        pending = [item.get("component") for item in components if item.get("redistributed") and item.get("commercial_conclusion") != "approved"]
        if pending:
            errors.append(f"approved inventory contains non-approved components: {pending}")
        incomplete = [item.get("component") for item in components if item.get("redistributed") and (not item.get("version_or_commit") or not item.get("sha256") or not item.get("license_evidence_url"))]
        if incomplete:
            errors.append(f"approved inventory has unpinned evidence: {incomplete}")
    if args.require_approved and data.get("review_status") != "approved-commercial":
        errors.append(f"commercial approval required; current status is {data.get('review_status')}")
    if errors:
        print("License inventory validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"License inventory structurally valid: {len(components)} components; status={data['review_status']}")
    if data["review_status"] == "pending":
        print("Commercial licensing gate remains incomplete; this file is not legal approval.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
