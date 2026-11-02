#!/usr/bin/env python3
"""Fetch pinned Android 15 HAL sources and generate versioned NDK bindings.

Uses only Python's standard library and the Android SDK build-tools aidl binary.
Never reads from, connects to, or changes an Android device.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native/aidl/sdk/sources.json"


def get(url: str) -> bytes:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(attempt + 1)
    raise AssertionError("unreachable")


def url_for(repo: dict, path: str, fmt: str) -> str:
    return f"https://android.googlesource.com/platform/{repo['project']}/+/{repo['commit']}/{path}?format={fmt}"


def tree_files(repo: dict, path: str):
    listing = json.loads(get(url_for(repo, path, "JSON"))[5:])
    for entry in listing["entries"]:
        child = f"{path.rstrip('/')}/{entry['name']}"
        if entry["type"] == "tree":
            yield from tree_files(repo, child)
        elif entry["name"].endswith(".aidl") or entry["name"] == ".hash":
            yield child


def find_aidl(explicit: str | None) -> str:
    if explicit:
        return str(Path(explicit).resolve())
    if os.environ.get("AIDL"):
        return os.environ["AIDL"]
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(variable)
        if not sdk:
            continue
        executable = "aidl.exe" if os.name == "nt" else "aidl"
        # Build tools 35 match the Android 15 snapshot used below.
        candidate = Path(sdk) / "build-tools/35.0.0" / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("aidl")
    if found:
        return found
    raise RuntimeError("Set AIDL or pass --aidl to SDK build-tools 35.0.0/aidl")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aidl", help="Path to SDK build-tools 35.0.0/aidl")
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--offline", action="store_true", help="Use previously fetched source files")
    args = parser.parse_args()
    spec = json.loads(LOCK.read_text(encoding="utf-8"))
    sdk = LOCK.parent
    source_dir = sdk / "upstream"
    jobs: list[tuple[dict, str, Path]] = []
    for package in spec["interfaces"]:
        repo = spec["repositories"][package["repository"]]
        destination = source_dir / package["name"]
        if not args.offline:
            for remote in tree_files(repo, package["path"]):
                jobs.append((repo, remote, destination / remote[len(package["path"]) + 1:]))
    for item in spec["files"]:
        jobs.append((spec["repositories"][item["repository"]], item["path"], source_dir / item["destination"]))

    def fetch(item):
        repo, remote, destination = item
        if args.offline:
            if not destination.is_file():
                raise RuntimeError(f"Offline source missing: {destination}")
            content = destination.read_bytes()
        else:
            content = base64.b64decode(get(url_for(repo, remote, "TEXT")))
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        return {"path": destination.relative_to(sdk).as_posix(),
                "sha256": hashlib.sha256(content).hexdigest(),
                "source": url_for(repo, remote, "TEXT")}

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        fetched = list(pool.map(fetch, jobs))
    if not args.offline:
        (sdk / "download-manifest.json").write_text(
            json.dumps(sorted(fetched, key=lambda e: e["path"]), indent=2) + "\n", encoding="utf-8")
    else:
        for entry in json.loads((sdk / "download-manifest.json").read_text(encoding="utf-8")):
            if hashlib.sha256((sdk / entry["path"]).read_bytes()).hexdigest() != entry["sha256"]:
                raise RuntimeError(f"Cached source changed: {entry['path']}")
    source_count = len(json.loads((sdk / "download-manifest.json").read_text(encoding="utf-8")))
    print(f"Pinned Android 15 sources ready ({source_count} files)", flush=True)
    if args.fetch_only:
        return
    aidl = find_aidl(args.aidl)
    # A failed regeneration must not leave a previous success marker usable by
    # CMake with partially updated sources.
    (sdk / "generated-versions.json").unlink(missing_ok=True)
    generated = sdk / "generated"
    generated.mkdir(parents=True, exist_ok=True)
    includes = [f"-I{source_dir / package['name']}" for package in spec["interfaces"]]
    versions = {}
    for package in spec["interfaces"]:
        folder = source_dir / package["name"]
        api_hash = (folder / ".hash").read_text(encoding="utf-8").splitlines()[-1].strip()
        if len(api_hash) != 40 or any(c not in "0123456789abcdef" for c in api_hash):
            raise RuntimeError(f"Invalid frozen API hash: {package['name']}")
        inputs = sorted(str(p) for p in folder.rglob("*.aidl"))
        subprocess.run([aidl, "--lang=ndk", "--structured", "--stability=vintf",
                        "--min_sdk_version=31", "--omit_invocation",
                        f"--version={package['version']}", f"--hash={api_hash}",
                        "-o", str(generated), "-h", str(generated / "include"),
                        *includes, *inputs], check=True)
        versions[package["name"]] = {"version": package["version"], "hash": api_hash}
        print(f"Generated {package['name']} V{package['version']}: {len(inputs)} types", flush=True)
    (sdk / "generated-versions.json").write_text(json.dumps(versions, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
