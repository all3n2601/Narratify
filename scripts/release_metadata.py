#!/usr/bin/env python3
"""Validate release versions and write safe GitHub Actions outputs."""
import argparse
from pathlib import Path
import re

SEMVER = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)


def metadata(version: str, run_number: int, *, publish: bool) -> dict[str, str]:
    match = SEMVER.fullmatch(version)
    if not match:
        raise ValueError("Use a version such as 1.2.3 or 1.2.3-rc.1 (without a v prefix).")
    prerelease = match.group(4)
    if prerelease and any(part.isdigit() and len(part) > 1 and part[0] == "0" for part in prerelease.split(".")):
        raise ValueError("Numeric prerelease identifiers cannot have leading zeroes.")
    if not 1 <= run_number <= 2_100_000_000:
        raise ValueError("Build number must be between 1 and 2100000000.")
    return {
        "version": version,
        "version_code": str(run_number),
        "tag": f"v{version}",
        "prerelease": str(prerelease is not None).lower(),
        "publish": str(publish).lower(),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--run-number", type=int, required=True)
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    try:
        values = metadata(args.version, args.run_number, publish=args.publish)
    except ValueError as error:
        parser.error(str(error))
    with args.github_output.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
