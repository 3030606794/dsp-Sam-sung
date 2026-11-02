"""Incrementally compile and package Samsung's two legacy drivers, without tests."""

from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys


ABI_LIBDIRS = (("arm64-v8a", "lib64"), ("armeabi-v7a", "lib"))
IGNORED_PARTS = {".git", ".cxx", "build", "__pycache__"}
TEXT_SUFFIXES = {".sh", ".prop", ".md", ".txt", ".conf", ".xml"}


def checked_file(path: Path, parent: Path) -> Path:
    if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
        raise RuntimeError(f"Linked source or work path is not supported: {path}")
    if not path.resolve().is_relative_to(parent):
        raise RuntimeError(f"Path escapes its owned directory: {path}")
    return path


def sync_tree(source: Path, snapshot: Path, relative: str) -> None:
    """Keep unchanged timestamps so Ninja can reuse this owned build tree."""
    origin = source / relative
    destination = snapshot / relative
    if not origin.is_dir():
        raise RuntimeError(f"Missing driver input directory: {origin}")
    checked_file(origin, source)
    destination.mkdir(parents=True, exist_ok=True)
    checked_file(destination, snapshot)
    expected = set()
    for current, directories, filenames in os.walk(origin, followlinks=False):
        current = checked_file(Path(current), source)
        directories[:] = [name for name in directories if name not in IGNORED_PARTS
                          and not (relative == "native" and current == origin
                                   and name in {"tests", "aidl"})]
        for name in directories:
            checked_file(current / name, source)
        for name in filenames:
            file = checked_file(current / name, source)
            rel = file.relative_to(origin)
            expected.add(rel)
            staged = destination / rel
            staged.parent.mkdir(parents=True, exist_ok=True)
            checked_file(staged.parent, snapshot)
            checked_file(staged, snapshot)
            if staged.is_file() and staged.read_bytes() == file.read_bytes():
                continue
            shutil.copy2(file, staged)
            # An older source revision must also invalidate a newer cached object.
            os.utime(staged, None)
    for staged in destination.rglob("*"):
        checked_file(staged, snapshot)
        if staged.is_file() and staged.relative_to(destination) not in expected:
            staged.unlink()


def package_legacy(snapshot: Path, build: Path, output_dir: Path) -> Path:
    module = snapshot / "module"
    properties = dict(line.split("=", 1) for line in
                      (module / "module.prop").read_text(encoding="utf-8").splitlines()
                      if line and not line.startswith("#") and "=" in line)
    version = properties["version"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", version):
        raise ValueError(f"Invalid module version: {version!r}")
    files = {file.relative_to(module).as_posix(): file
             for file in module.rglob("*") if file.is_file()}
    if any(name.endswith(".so") for name in files):
        raise RuntimeError("The module template must not contain prebuilt libraries.")
    for abi, libdir in ABI_LIBDIRS:
        library = build / f"driver-{abi}" / "libdspfusion.so"
        if not library.is_file():
            raise RuntimeError(f"Missing compiled legacy library: {library}")
        files[f"system/vendor/{libdir}/soundfx/libdspfusion.so"] = library
    files["THIRD-PARTY.md"] = snapshot / "docs/THIRD-PARTY.md"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"DSP-Audio-Driver-{version}.zip"
    pending = output.with_suffix(".zip.partial")
    with ZipFile(pending, "w", ZIP_DEFLATED) as archive:
        for name, file in sorted(files.items()):
            content = file.read_bytes()
            if file.suffix in TEXT_SUFFIXES:
                content = content.replace(b"\r\n", b"\n")
            info = ZipInfo.from_file(file, name)
            info.create_system = 3
            executable = file.suffix == ".sh" or name == "META-INF/com/google/android/update-binary"
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, content)
    pending.replace(output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (output_dir / "SHA256SUMS.txt").write_text(
        f"{digest}  {output.name}\n", encoding="utf-8", newline="\n")
    (build.parent / "samsung-driver-artifact.json").write_text(
        json.dumps({"version": version, "versionCode": properties.get("versionCode"),
                    "route": "legacy/HIDL", "abis": [abi for abi, _ in ABI_LIBDIRS],
                    "archive": str(output), "sha256": digest}, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    print(f"{output}: {digest}", flush=True)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--android-sdk", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--work-dir", "--work-root", dest="work_dir", type=Path,
                        required=True, help="Owned ASCII directory for incremental driver builds.")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2, choices=range(1, 9))
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This local build helper expects Windows SDK/NDK tools.")
    source = args.source_dir.resolve()
    work = args.work_dir.resolve()
    if (not str(work).isascii() or work == source or work.is_relative_to(source)
            or source.is_relative_to(work)):
        parser.error("Use an independent ASCII work directory outside the source tree.")
    sdk = args.android_sdk.resolve()
    cmake = args.cmake.resolve()
    ninja = sdk / "cmake/3.22.1/bin/ninja.exe"
    toolchain = sdk / "ndk/26.3.11579264/build/cmake/android.toolchain.cmake"
    for tool in (cmake, ninja, toolchain):
        if not tool.is_file():
            parser.error(f"Required local tool was not found: {tool}")
    for relative in ("native/CMakeLists.txt", "module/module.prop", "docs/THIRD-PARTY.md"):
        if not (source / relative).is_file():
            parser.error(f"Required driver input was not found: {source / relative}")
    owner = work / ".samsung-source-owner.txt"
    if work.exists():
        if owner.is_file():
            if owner.read_text(encoding="utf-8") != str(source):
                parser.error("This work directory belongs to a different source tree.")
        elif any(work.iterdir()):
            parser.error("Refusing to modify a nonempty work directory without an ownership marker.")
    work.mkdir(parents=True, exist_ok=True)
    owner.write_text(str(source), encoding="utf-8")
    snapshot = work / "snapshot"
    snapshot.mkdir(parents=True, exist_ok=True)
    checked_file(snapshot, work)
    for relative in ("native", "app/src/main/cpp/libjamesdsp", "module"):
        sync_tree(source, snapshot, relative)
    third_party = snapshot / "docs/THIRD-PARTY.md"
    third_party.parent.mkdir(parents=True, exist_ok=True)
    checked_file(third_party.parent, snapshot)
    checked_file(third_party, snapshot)
    shutil.copy2(checked_file(source / "docs/THIRD-PARTY.md", source), third_party)
    build = work / "build"
    build.mkdir(parents=True, exist_ok=True)
    checked_file(build, work)
    for abi, _ in ABI_LIBDIRS:
        target = build / f"driver-{abi}"
        checked_file(target, work)
        print(f"Compiling Samsung legacy driver: {abi}", flush=True)
        subprocess.run([
            str(cmake), "-G", "Ninja", "-S", str(snapshot / "native"), "-B", str(target),
            f"-DCMAKE_MAKE_PROGRAM={ninja}", f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26",
            "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
            "-DFUSION_BUILD_TESTS=OFF", "-DFUSION_BUILD_DIAGNOSTIC_RUNNER=OFF",
            "-DFUSION_SANITIZE=OFF", "-DBUILD_TESTING=OFF",
            "-DFUSION_BUILD_AIDL=OFF", "-DFUSION_BUILD_AIDL_TESTS=OFF",
        ], check=True)
        subprocess.run([str(cmake), "--build", str(target), "--parallel", str(args.jobs),
                        "--target", "dspfusion"], check=True)
    package_legacy(snapshot, build, args.output_dir.resolve())


if __name__ == "__main__":
    main()
