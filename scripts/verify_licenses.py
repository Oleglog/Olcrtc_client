#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "licenses.json"
FORBIDDEN = {"GPL", "GPL-2.0", "GPL-3.0", "AGPL", "AGPL-3.0"}
FLOATING_REF = re.compile(r"^(?:latest|main|master|dev|develop|head)$", re.IGNORECASE)


def fail(message: str) -> None:
    print(f"license verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    allowed = set(data["allowed_licenses"])

    for component in data["components"]:
        license_id = component.get("license", "")
        if license_id in FORBIDDEN or license_id.startswith(("GPL-", "AGPL-")):
            fail(f"{component['name']} uses forbidden license {license_id}")
        if license_id not in allowed:
            fail(f"{component['name']} uses unverified license {license_id}")

    for source in data["native_sources"]:
        ref = source.get("ref", "").strip()
        if not ref or FLOATING_REF.fullmatch(ref):
            fail(f"{source['name']} does not use an immutable ref")
        if source.get("license") not in allowed:
            fail(f"{source['name']} uses unverified license {source.get('license')}")

    print("license verification passed")


if __name__ == "__main__":
    main()
