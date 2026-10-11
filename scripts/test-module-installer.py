"""Run the actual Magisk installer against isolated filesystem fixtures, never a phone.

CI: python3 scripts/test-module-installer.py
Only CONFIG_BASE in a temporary script is redirected; patching functions and installer
control flow are unchanged. Magisk logging/permission helpers are stubbed on the host.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INSTALLER = (ROOT / "module/customize.sh").read_text(encoding="utf-8")
UUID = "30306067-9400-4d53-9000-000000000001"
SH = shutil.which("sh") or str(Path("C:/Program Files/Git/bin/sh.exe"))
XML = """<?xml version="1.0"?>
<audio_effects_conf version="2.0"><libraries><library name="vendor" path="libvendor.so"/></libraries>
<effects><effect name="vendor_fx" library="vendor" uuid="vendor-original"/></effects>
<postprocess><stream type="music"><apply effect="vendor_fx"/></stream></postprocess></audio_effects_conf>
"""
CONF = """# Vendor file: comments containing { do not affect nesting
 libraries { vendor { path /vendor/lib/soundfx/libvendor.so } }
 effects { vendor_fx { library vendor uuid vendor-original } }
 post_processing { music { vendor_fx {} } }
"""


class InstallerFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fusion-installer-")
        self.base = Path(self.temp.name) / "filesystem"
        self.module = Path(self.temp.name) / "module"
        self.base.mkdir()
        self.module.mkdir()
        for abi in ("lib", "lib64"):
            file = self.module / f"system/vendor/{abi}/soundfx/libdspfusion.so"
            file.parent.mkdir(parents=True)
            file.write_bytes(b"fixture driver file")
        self.sources = {}
        self.permissions = Path(self.temp.name) / "permissions.tsv"
        self.script = Path(self.temp.name) / "customize.sh"
        redirected = INSTALLER.replace('CONFIG_BASE=""', f'CONFIG_BASE="{self.base.as_posix()}"', 1)
        self.script.write_text(redirected, encoding="utf-8", newline="\n")

    def tearDown(self):
        self.temp.cleanup()

    def config(self, path, contents=XML):
        file = self.base / path.lstrip("/")
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents, encoding="utf-8", newline="\n")
        self.sources[file] = file.read_bytes()
        return file

    def install(self, arch="arm64", api="35", success=True):
        self.permissions.write_text("", encoding="utf-8")
        env = dict(os.environ, MODPATH=self.module.as_posix(), ARCH=arch, API=api,
                   PERMISSION_LOG=self.permissions.as_posix())
        if os.name == "nt":
            env["PATH"] = "C:/Program Files/Git/usr/bin;" + env.get("PATH", "")
        wrapper = '''
ui_print() { printf "%s\\n" "$*"; }
abort() { printf "ABORT: %s\\n" "$*"; exit 1; }
set_perm_recursive() {
  printf "recursive\\t%s\\t%s\\n" "$1" "${6:-u:object_r:system_file:s0}" >> "$PERMISSION_LOG"
}
set_perm() {
  printf "single\\t%s\\t%s\\n" "$1" "${5:-u:object_r:system_file:s0}" >> "$PERMISSION_LOG"
}
. "$1"
'''
        result = subprocess.run([SH, "-c", wrapper, "fixture", self.script.as_posix()],
                                env=env, text=True, capture_output=True, timeout=15)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        for file, original in self.sources.items():
            self.assertEqual(file.read_bytes(), original, f"Installer edited live fixture {file}")
        return result

    def context(self, file):
        """Replay permission helpers in order, including later recursive overrides."""
        context = None
        for line in self.permissions.read_text(encoding="utf-8").splitlines():
            kind, target, label = line.split("\t")
            target = Path(target)
            if file == target or (kind == "recursive" and target in file.parents):
                context = label
        return context

    def output(self, logical):
        mapped = logical if logical.startswith("/system/") else "/system" + logical
        return self.module / mapped.lstrip("/")

    def check_xml(self, path):
        tree = ET.parse(path)
        libraries = tree.findall("./libraries/library[@name='dsp_fusion']")
        effects = tree.findall("./effects/effect[@name='dsp_fusion']")
        self.assertEqual(len(libraries), 1)
        self.assertEqual(len(effects), 1)
        self.assertEqual(libraries[0].get("path"), "libdspfusion.so")
        self.assertEqual(effects[0].get("uuid"), UUID)
        return tree

    def test_vendor_xml_preserves_existing_effects_and_streams(self):
        self.config("/vendor/etc/audio_effects.xml")
        self.install()
        tree = self.check_xml(self.output("/vendor/etc/audio_effects.xml"))
        self.assertEqual(tree.find("./libraries/library[@name='vendor']").get("path"), "libvendor.so")
        self.assertEqual(tree.find("./postprocess/stream/apply").get("effect"), "vendor_fx")

    def test_minified_single_quote_multiline_existing_registration_is_unique(self):
        contents = """<audio_effects_conf><libraries><library name='vendor' path='vendor.so'/><library
name = 'dsp_fusion' path='old.so'></library></libraries><effects><effect name='vendor_fx' library='vendor'/><effect
name='dsp_fusion' library='old' uuid='old'/></effects></audio_effects_conf>"""
        self.config("/vendor/etc/audio_effects.xml", contents)
        self.install()
        tree = self.check_xml(self.output("/vendor/etc/audio_effects.xml"))
        self.assertEqual(len(tree.findall("./libraries/library")), 2)
        self.assertEqual(len(tree.findall("./effects/effect")), 2)

    def test_empty_sections_and_comment_tags(self):
        self.config("/vendor/etc/audio_effects.xml", """<audio_effects_conf>
<!-- <libraries><library name="dsp_fusion"/></libraries> -->
<libraries /><effects /></audio_effects_conf>""")
        self.install()
        self.check_xml(self.output("/vendor/etc/audio_effects.xml"))

    def test_partition_mapping_multiple_files_space_paths_haptic_ignored(self):
        paths = [f"/{part}/etc/audio_effects.xml" for part in ("vendor", "system", "product", "system_ext")]
        paths += ["/vendor/etc/sub dir/audio_effects_extra.xml"]
        for path in paths:
            self.config(path)
        self.config("/vendor/etc/audio_effects_haptic.xml")
        result = self.install()
        self.assertIn("Patched 5", result.stdout)
        for path in paths:
            self.check_xml(self.output(path))
        self.assertFalse(self.output("/vendor/etc/audio_effects_haptic.xml").exists())

    def test_vendor_driver_and_configs_receive_hal_accessible_labels(self):
        for path in ("/vendor/etc/audio_effects.xml", "/vendor/etc/sub dir/audio_effects_extra.xml",
                     "/system/etc/audio_effects.xml", "/product/etc/audio_effects.xml"):
            self.config(path)
        self.install()
        for abi in ("lib", "lib64"):
            directory = self.module / f"system/vendor/{abi}/soundfx"
            self.assertEqual(self.context(directory), "u:object_r:vendor_file:s0")
            self.assertEqual(self.context(directory / "libdspfusion.so"), "u:object_r:vendor_file:s0")
        for path in ("/vendor/etc/audio_effects.xml", "/vendor/etc/sub dir/audio_effects_extra.xml"):
            self.assertEqual(self.context(self.output(path)), "u:object_r:vendor_configs_file:s0")
        for path in ("/system/etc/audio_effects.xml", "/product/etc/audio_effects.xml"):
            self.assertEqual(self.context(self.output(path)), "u:object_r:system_file:s0")

    def test_odm_only_configuration_is_not_claimed_supported(self):
        self.config("/odm/etc/audio_effects.xml")
        result = self.install(success=False)
        self.assertIn("No supported", result.stdout)
        self.assertFalse(self.output("/odm/etc/audio_effects.xml").exists())

    def test_xml_upgrade_is_semantically_idempotent(self):
        path = "/vendor/etc/audio_effects.xml"
        self.config(path)
        self.install()
        first = ET.tostring(self.check_xml(self.output(path)).getroot())
        self.config(path, self.output(path).read_text(encoding="utf-8"))
        self.install()
        second = ET.tostring(self.check_xml(self.output(path)).getroot())
        self.assertEqual(re.sub(rb"\s+", b"", first), re.sub(rb"\s+", b"", second))

    def test_vendor_directory_symlink_is_followed(self):
        self.config("/system/vendor/etc/audio_effects.xml")
        try:
            (self.base / "vendor").symlink_to(self.base / "system/vendor", target_is_directory=True)
        except OSError:
            self.skipTest("Host cannot create directory symlinks")
        self.install()
        self.check_xml(self.output("/vendor/etc/audio_effects.xml"))

    def test_legacy_inline_conf_arm64_path_and_idempotence(self):
        path = "/system/etc/audio_effects.conf"
        self.config(path, CONF)
        self.install()
        first = self.output(path).read_text()
        self.assertIn("path /vendor/lib64/soundfx/libdspfusion.so", first)
        self.assertEqual(first.count("dsp_fusion {"), 2)
        self.assertIn("vendor_fx { library vendor uuid vendor-original }", first)
        self.assertIn("post_processing { music { vendor_fx {} } }", first)
        self.config(path, first)
        self.install()
        second = self.output(path).read_text()
        self.assertEqual(second.count("dsp_fusion {"), 2)
        self.assertEqual(re.sub(r"\s+", "", first), re.sub(r"\s+", "", second))

    def test_arm32_conf_path_and_module_layout(self):
        path = "/vendor/etc/audio_effects.conf"
        self.config(path, CONF)
        self.install(arch="arm")
        self.assertIn("path /vendor/lib/soundfx/libdspfusion.so", self.output(path).read_text())
        self.assertFalse((self.module / "system/vendor/lib64").exists())
        self.assertTrue((self.module / "system/vendor/lib/soundfx/libdspfusion.so").exists())
        self.assertEqual(self.context(self.module / "system/vendor/lib/soundfx/libdspfusion.so"),
                         "u:object_r:vendor_file:s0")
        self.assertEqual(self.context(self.output(path)), "u:object_r:vendor_configs_file:s0")

    def test_invalid_config_does_not_become_systemless_override(self):
        self.config("/vendor/etc/audio_effects.xml", "<audio_effects_conf><libraries></effects></audio_effects_conf>")
        self.config("/system/etc/audio_effects.conf", "libraries { vendor { path x } effects {}")
        result = self.install(success=False)
        self.assertIn("No supported", result.stdout)
        self.assertFalse(self.output("/vendor/etc/audio_effects.xml").exists())
        self.assertFalse(self.output("/system/etc/audio_effects.conf").exists())

    def test_old_modules_block_until_disabled_or_removed(self):
        self.config("/vendor/etc/audio_effects.xml")
        for name in ("ainur_jamesdsp", "ViPER4Android-RE-Fork", "ViPER4Android-RE-AIDL", "aml"):
            module = self.base / "data/adb/modules" / name
            module.mkdir(parents=True)
            result = self.install(success=False)
            self.assertIn(f"Disable {name}", result.stdout)
            (module / "disable").touch()
        self.install()

    def test_disabled_but_still_mounted_old_registration_requires_reboot(self):
        for uuid in ("f27317f4-c984-4de6-9a90-545759495bf2", "90380da3-8536-4744-a6a3-5731970e640f"):
            self.config("/vendor/etc/audio_effects.xml", XML.replace("vendor-original", uuid))
            result = self.install(success=False)
            self.assertIn("reboot", result.stdout)
            self.assertFalse(self.output("/vendor/etc/audio_effects.xml").exists())

    def test_unsupported_arch_api_and_missing_payload_rejected(self):
        self.config("/vendor/etc/audio_effects.xml")
        self.install(arch="x64", success=False)
        self.install(api="25", success=False)
        (self.module / "system/vendor/lib64/soundfx/libdspfusion.so").unlink()
        result = self.install(success=False)
        self.assertIn("64-bit driver is missing", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
