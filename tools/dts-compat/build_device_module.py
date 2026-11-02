"""Build a private, device-specific DTS add-on from an existing upstream checkout.

No upstream binaries are committed here. Input configuration must come from a
verified backup of the active Fusion module on the intended device.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import tempfile
import zipfile
from pathlib import Path

from patch_effects import patch_file

HERE = Path(__file__).resolve().parent
CONFIGS = (
    "system/vendor/etc/audio/sku_kalama/audio_effects.conf",
    "system/vendor/etc/audio/sku_kalama/audio_effects.xml",
    "system/vendor/etc/audio_effects.xml",
    "system/vendor/etc/audio_effects_sec.xml",
    "system/etc/audio_effects.xml",
    "system/etc/audio_effects_common.conf",
)

CUSTOMIZE = '''#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the running Android system only."
[ "$(getprop ro.product.device)" = dm3q ] || abort "This package is for the backed-up Samsung S23 Ultra only."
[ "$(getprop ro.build.version.sdk)" = 35 ] || abort "Android version differs from the verified baseline."
[ ! -d /data/adb/modules/dts_fusion_compat/system ] || abort "Already installed. Restore and remove the old add-on before reinstalling."
ui_print "- Keeping the existing Fusion driver and user presets"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor" 0 0 0755 0644 u:object_r:vendor_file:s0
set_perm_recursive "$MODPATH/system/vendor/etc" 0 0 0755 0644 u:object_r:vendor_configs_file:s0
chmod 0755 "$MODPATH"/*.sh
MODPATH="$MODPATH" /system/bin/sh "$MODPATH/install-configs.sh" || abort "Configuration merge failed; original configuration was preserved/restored."
ui_print "- DTS control panel and libraries staged; reboot required"
ui_print "- DTS uses the upstream ZTE model properties for its original license"
'''

POST_FS = '''#!/system/bin/sh
# Only the DTS data directory: no audio service restart or global policy changes.
mkdir -p /data/vendor/audio/dts || exit 1
chown 1013:1005 /data/vendor/audio/dts
chmod 0771 /data/vendor/audio/dts
restorecon /data/vendor/audio/dts
'''

SERVICE = '''#!/system/bin/sh
MODPATH=${0%/*}
# Same runtime identity properties as the upstream ZTE module. Do not persist
# these to build.prop: removing/disabling the module and rebooting restores them.
resetprop -n ro.build.product P855A01
resetprop -n ro.product.model "ZTE A2020G Pro"
printf '%s\n' 'DTS compatibility module started; listening tests pending.' > "$MODPATH/status.txt"
'''

ACTION = '''#!/system/bin/sh
MODPATH=${0%/*}
export MODPATH
echo "Restoring the backed-up Fusion configuration..."
/system/bin/sh "$MODPATH/restore-configs.sh" || exit 1
touch "$MODPATH/disable"
echo "DTS disabled. Reboot to restore the original Samsung model and Fusion-only audio."
'''

UNINSTALL = '''#!/system/bin/sh
MODPATH=${0%/*}
export MODPATH
/system/bin/sh "$MODPATH/restore-configs.sh"
# Keep user data; never remove or disable the Fusion module.
'''

UPDATE_BINARY = '''#!/sbin/sh
umask 022
OUTFD=$2
ZIPFILE=$3
[ -f /data/adb/magisk/util_functions.sh ] || exit 1
. /data/adb/magisk/util_functions.sh
[ "$MAGISK_VER_CODE" -ge 20400 ] || exit 1
install_module
'''


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(upstream: Path, baseline: Path, output: Path) -> None:
    if output.exists():
        raise ValueError("Choose a new output path; do not overwrite a delivered package")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dts-compat-") as temporary:
        root = Path(temporary)

        def copy(relative: str) -> None:
            source = upstream / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)

        for relative in (
            "system/priv-app/DtsUltra/DtsUltra.apk",
            "system/framework/dtsaudio.jar",
            "system/etc/permissions/dtsaudio.xml",
            "system/etc/sysconfig/config-com.dts.dtsxultra.xml",
            "system/vendor/etc/dts",
        ):
            copy(relative)
        # The phone's VNDK supplies common dependencies. Never install ZTE's
        # sqlite, crypto, C++ runtime, codec libraries or audio policy overlays.
        for libdir in ("lib", "lib64"):
            for name in ("soundfx/libdtsaudio.so", "libdts-eagle-shared.so", "libdtsdsec.so"):
                copy(f"system/vendor/{libdir}/{name}")

        manifest = []
        for relative in CONFIGS:
            original = root / "assets/original" / relative
            merged = root / "assets/merged" / relative
            original.parent.mkdir(parents=True, exist_ok=True)
            merged.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(baseline / relative, original)
            if "30306067-9400-4d53-9000-000000000001" not in original.read_text(encoding="utf-8"):
                raise ValueError(f"Fusion registration missing in {relative}")
            patch_file(original, merged)
            manifest.append(f"{sha(original)} {sha(merged)} {relative}\n")
        (root / "assets/configs.list").write_text("".join(manifest), encoding="ascii", newline="\n")
        for name in ("install-configs.sh", "restore-configs.sh"):
            shutil.copy2(HERE / name, root / name)

        files = {
            "module.prop": "id=dts_fusion_compat\nname=DTS:X Ultra - Samsung Fusion Compatibility\nversion=5.9-samsung-compat1\nversionCode=5901\nauthor=Xperi, reiryuki; local compatibility adaptation\ndescription=DTS control panel alongside the existing Fusion driver. Action restores Fusion-only configuration; reboot required. Runtime ZTE model properties are used by upstream DTS.\n",
            "customize.sh": CUSTOMIZE,
            "post-fs-data.sh": POST_FS,
            "service.sh": SERVICE,
            "action.sh": ACTION,
            "uninstall.sh": UNINSTALL,
            "system.prop": "ro.vendor.dts.licensepath=/vendor/etc/dts/\nro.vendor.dts.cfgpath=/vendor/etc/dts/\n",
            "META-INF/com/google/android/update-binary": UPDATE_BINARY,
            "META-INF/com/google/android/updater-script": "#MAGISK\n",
            "SOURCE.txt": "Upstream: https://github.com/reiryuki/DTSX-Ultra-ZTE-A2020G-Pro-Magisk-Module\nCommit: 8ccb74ad2a9af512faf007d04837d49b4fc3f335\nDTS application and binary libraries belong to their original owners.\nThis device-specific archive is for local testing; not a source-code release.\n",
        }
        for relative, content in files.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8", newline="\n")
        shutil.copy2(upstream / "LICENSE", root / "UPSTREAM-LICENSE")
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(root.rglob("*")):
                if file.is_file():
                    archive.write(file, file.relative_to(root).as_posix())
        with zipfile.ZipFile(output) as archive:
            if archive.testzip() is not None:
                raise RuntimeError("Archive verification failed")
    print(f"Created {output}\nSHA256 {sha(output)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.upstream, args.baseline, args.output)
