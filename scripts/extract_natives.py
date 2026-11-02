#!/usr/bin/env python3
"""Pre-extract JNI libraries bundled in the bridge JAR.

unidbg's loader extracts these at runtime, which fails on read-only images.
This writes them once at build time instead.
"""
import sys
import zipfile
from pathlib import Path

PLATFORM = {"x86_64": "linux_64", "aarch64": "linux_arm64"}[__import__("platform").machine()]


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: extract_natives.py <bridge.jar> <output-dir>", file=sys.stderr)
        return 2
    jar, output = Path(sys.argv[1]), Path(sys.argv[2])
    with zipfile.ZipFile(jar) as archive:
        members = [name for name in archive.namelist()
                   if name.startswith(f"natives/{PLATFORM}/") and name.endswith(".so")]
        if not members:
            raise SystemExit(f"No {PLATFORM} JNI libraries found in {jar}")
        output.mkdir(parents=True, exist_ok=True)
        for name in members:
            (output / Path(name).name).write_bytes(archive.read(name))
            print("EXTRACTED", Path(name).name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
