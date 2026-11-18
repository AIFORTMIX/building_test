#!/usr/bin/env python3
"""Preserve Kleaf stamping while using the target's verified Git abbreviation length.

Only source-derived build metadata is generated. No ELF, symbol CRC or kernel
configuration is changed; dirty suffixes remain visible.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def update_status(status: str, head: str, expected_release: str) -> str:
    wanted = re.search(r"-g([0-9a-f]{12,40})(?=-|$)", expected_release)
    if not wanted:
        return status
    abbreviation = wanted[1]
    if not re.fullmatch(r"[0-9a-f]{40}", head) or not head.startswith(abbreviation):
        raise ValueError("Expected release Git fragment does not match actual common HEAD")
    output = []
    found = False
    for line in status.splitlines():
        if line.startswith("STABLE_SCMVERSIONS "):
            metadata = json.loads(line.split(" ", 1)[1])
            if "common" not in metadata:
                raise ValueError("Kleaf stamp has no common source metadata")
            original = metadata["common"]
            actual = re.search(r"-g([0-9a-f]{12,40})(?=-|$)", original)
            if not actual or not head.startswith(actual[1]):
                raise ValueError("Kleaf stamp Git fragment does not match actual common HEAD")
            metadata["common"] = original[:actual.start(1)] + abbreviation + original[actual.end(1):]
            line = "STABLE_SCMVERSIONS " + json.dumps(metadata, sort_keys=True)
            found = True
        output.append(line)
    if not found:
        raise ValueError("Missing Kleaf STABLE_SCMVERSIONS output")
    return "\n".join(output) + "\n"


def main() -> None:
    root = Path.cwd()
    result = subprocess.run(["bash", "build/kernel/kleaf/workspace_status.sh"],
                            check=True, capture_output=True, text=True)
    sys.stderr.write(result.stderr)
    head = subprocess.check_output(["git", "-C", str(root / "common"), "rev-parse", "HEAD"], text=True).strip()
    sys.stdout.write(update_status(result.stdout, head, os.environ.get("ABK_KO_EXPECTED_RELEASE", "")))


if __name__ == "__main__":
    main()
