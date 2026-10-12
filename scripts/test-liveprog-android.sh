#!/usr/bin/env bash
set -euo pipefail

# Explicitly refuse real phones. This regression writes only to a disposable CI emulator.
test "$#" -eq 1 || { echo 'Usage: test-liveprog-android.sh RELEASE_APK' >&2; exit 2; }
apk=$(realpath "$1")
repo=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is required}}
work="$repo/build/liveprog-android"
mkdir -p "$work/classes" "$work/assets" "$work/reports"
serial=$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')
test -n "$serial" || { echo 'A running Android emulator is required' >&2; exit 2; }
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 35

# Only the small Java test runner is compiled here. All parser/Kotlin/library classes come
# unchanged from the signed release APK that users will install, including its assets.
javac --release 8 -encoding UTF-8 -d "$work/classes" "$repo/scripts/liveprog-android/LiveprogAndroidRegression.java"
jar cf "$work/harness.jar" -C "$work/classes" .
"$sdk/build-tools/35.0.0/d8" --min-api 26 --lib "$sdk/platforms/android-35/android.jar" --output "$work/harness-dex.jar" "$work/harness.jar"
python3 - "$apk" "$work/assets" <<'PY'
import pathlib, sys, zipfile
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as archive:
    members = [name for name in archive.namelist() if name.startswith('assets/Liveprog/') and name.endswith('.eel')]
    assert len(members) == 40, f'Expected 40 scripts, found {len(members)}'
    for name in members:
        assert name.count('/') == 2, f'Unexpected nested asset: {name}'
        (destination / pathlib.PurePosixPath(name).name).write_bytes(archive.read(name))
PY
remote="/data/local/tmp/fusion-liveprog-regression-$(date +%s)"
adb -s "$serial" shell mkdir -p "$remote"
adb -s "$serial" push "$apk" "$remote/release.apk" >/dev/null
adb -s "$serial" push "$work/harness-dex.jar" "$remote/harness-dex.jar" >/dev/null
adb -s "$serial" push "$work/assets" "$remote/assets" >/dev/null
# Android 14+ refuses writable dynamically loaded DEX files.
adb -s "$serial" shell chmod 444 "$remote/release.apk" "$remote/harness-dex.jar"
adb -s "$serial" shell "CLASSPATH=$remote/release.apk:$remote/harness-dex.jar app_process /system/bin LiveprogAndroidRegression $remote/assets" 2>&1 | tee "$work/reports/android-runtime.log"
grep -q '^LIVEPROG_ANDROID_PASS scripts=40 ' "$work/reports/android-runtime.log"
