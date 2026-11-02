#!/usr/bin/env python3
"""Fetch only the eight pinned runtime files, never a whole research repository."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, help="Optional existing repository root for offline preparation")
    parser.add_argument("--check", action="store_true", help="Verify existing files without downloading")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "runtime.lock.json").read_text())
    destination = ROOT / "vendor/qbar"
    base = f"https://raw.githubusercontent.com/{manifest['repository']}/{manifest['commit']}/{manifest['source_prefix']}"
    for item in manifest["files"]:
        target = destination / item["target"]
        expected = item["sha256"]
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
            print("OK", item["target"])
            continue
        if args.check:
            raise SystemExit("Missing or invalid runtime file: " + item["target"])
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as temporary:
            staging = Path(temporary.name)
        try:
            if args.source_root:
                source = args.source_root / manifest["source_prefix"] / item["source"]
                shutil.copyfile(source, staging)
            else:
                _download(base + item["source"], staging)
            if hashlib.sha256(staging.read_bytes()).hexdigest() != expected:
                raise SystemExit("Runtime checksum mismatch: " + item["target"])
            staging.replace(target)
            print("PREPARED", item["target"])
        finally:
            staging.unlink(missing_ok=True)


def _download(url: str, staging: Path) -> None:
    """Retry transient TLS/network failures; hashing still validates the result."""
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(url, timeout=90) as response:
                with staging.open("wb") as output:
                    shutil.copyfileobj(response, output)
            return
        except OSError as error:
            staging.unlink(missing_ok=True)
            if attempt == 3:
                raise SystemExit(f"Download failed after 3 attempts: {error}") from error
            time.sleep(2 * attempt)


if __name__ == "__main__":
    main()
