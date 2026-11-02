"""Host-only transaction tests. Metadata commands are mocked, never a device."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
SHELL = shutil.which("sh")
if not SHELL and os.name == "nt":
    candidate = Path("C:/Program Files/Git/usr/bin/sh.exe")
    if candidate.is_file():
        SHELL = str(candidate)


def shell_path(path):
    value = Path(path).resolve().as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


def digest(value):
    return hashlib.sha256(value).hexdigest()


@unittest.skipUnless(SHELL, "A POSIX shell is required for the host simulation")
class ConfigTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dts-config-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.module = self.root / "modules" / "dts_test"
        self.fusion = self.root / "modules" / "dsp_fusion"
        self.stubs = self.root / "stubs"
        self.stubs.mkdir()
        self.paths = ["system/vendor/etc/audio_effects.xml",
                      "system/etc/audio_effects_common.conf",
                      "system/vendor/etc/audio_effects_extra.xml"]
        self.before = [b"original vendor config", b"original system config", b"original extra config"]
        self.after = [b"merged vendor config", b"merged system config", b"merged extra config"]
        self.rows = []
        for relative, before, after in zip(self.paths, self.before, self.after):
            for prefix, data in [(self.module / "assets/original", before),
                                 (self.module / "assets/merged", after),
                                 (self.fusion, before)]:
                file = prefix / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(data)
            self.rows.append(f"{digest(before)} {digest(after)} {relative}")
        self.manifest = self.module / "assets/configs.list"
        self.manifest.write_text("\n".join(self.rows) + "\n", encoding="ascii")
        self.stub("chmod", 'exit 0\n')
        self.stub("chown", 'exit 0\n')
        self.stub("chcon", '''
printf '%s\\n' "$*" >> "$METADATA_LOG"
case "$*" in *"${FAIL_LABEL_BASENAME:-no-matching-file}"*) exit 1 ;; esac
case "$1" in --reference=*) [ "${FAIL_REFERENCE:-0}" = 1 ] && exit 1 ;; esac
exit 0
''')
        self.stub("mv", '''
for destination do :; done
printf '%s\\n' "$destination" >> "$RENAME_LOG"
if [ "$destination" = "$FAIL_TARGET" ] && [ ! -e "$FAIL_MARKER" ]; then
  : > "$FAIL_MARKER"
  if [ -n "$MUTATE_PATH" ]; then printf '%s' 'future update' > "$MUTATE_PATH"; fi
  exit 1
fi
exec /usr/bin/mv "$@"
''')
        self.environment = os.environ.copy()
        self.environment.update({
            "PATH": shell_path(self.stubs) + ":/usr/bin:/bin",
            "MODPATH": shell_path(self.module), "FUSION_ROOT": shell_path(self.fusion),
            "METADATA_LOG": shell_path(self.root / "metadata.log"),
            "RENAME_LOG": shell_path(self.root / "rename.log"),
            "FAIL_MARKER": shell_path(self.root / "failed-once"),
            "FAIL_TARGET": "", "MUTATE_PATH": "", "FAIL_REFERENCE": "0",
            "FAIL_LABEL_BASENAME": "no-matching-file",
        })

    def stub(self, name, body):
        file = self.stubs / name
        file.write_text("#!/bin/sh\n" + body, encoding="ascii", newline="\n")
        file.chmod(0o755)

    def run_script(self, name="install-configs.sh", **changes):
        environment = self.environment.copy()
        environment.update(changes)
        return subprocess.run([SHELL, shell_path(SCRIPTS / name)], env=environment,
                              capture_output=True, text=True, timeout=30)

    def contents(self):
        return [(self.fusion / relative).read_bytes() for relative in self.paths]

    def assert_no_temporary_files(self):
        self.assertEqual([], list(self.module.glob(".config-*")))
        self.assertEqual([], list(self.fusion.rglob("*.dts-compat.*")))

    def test_syntax_install_idempotence_restore_and_restore_idempotence(self):
        for script in ["install-configs.sh", "restore-configs.sh"]:
            check = subprocess.run([SHELL, "-n", shell_path(SCRIPTS / script)],
                                   capture_output=True, text=True)
            self.assertEqual(0, check.returncode, check.stderr)
        for expected, script in [(self.after, "install-configs.sh"),
                                 (self.after, "install-configs.sh"),
                                 (self.before, "restore-configs.sh"),
                                 (self.before, "restore-configs.sh")]:
            result = self.run_script(script)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(expected, self.contents())
            self.assert_no_temporary_files()
        self.assertEqual(self.before, [(self.module / "assets/original" / p).read_bytes() for p in self.paths])

    def test_reference_label_fallback_uses_correct_partition_labels(self):
        result = self.run_script(FAIL_REFERENCE="1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        metadata = (self.root / "metadata.log").read_text()
        self.assertIn("u:object_r:vendor_configs_file:s0", metadata)
        self.assertIn("u:object_r:system_file:s0", metadata)
        self.assertEqual(self.after, self.contents())

    def test_third_rename_failure_rolls_back_first_two_in_reverse_order(self):
        result = self.run_script(FAIL_TARGET=shell_path(self.fusion / self.paths[2]))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.before, self.contents())
        renames = (self.root / "rename.log").read_text().splitlines()
        self.assertEqual([Path(p).name for p in [self.paths[0], self.paths[1], self.paths[2],
                                               self.paths[1], self.paths[0]]],
                         [Path(p).name for p in renames])
        self.assert_no_temporary_files()

    def test_rollback_preserves_a_file_changed_by_an_external_updater(self):
        result = self.run_script(FAIL_TARGET=shell_path(self.fusion / self.paths[1]),
                                 MUTATE_PATH=shell_path(self.fusion / self.paths[0]))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([b"future update", self.before[1], self.before[2]], self.contents())
        self.assertIn("preserved externally changed file", result.stdout)
        self.assert_no_temporary_files()

    def test_reapply_failure_does_not_rollback_already_merged_files(self):
        (self.fusion / self.paths[0]).write_bytes(self.after[0])
        result = self.run_script(FAIL_TARGET=shell_path(self.fusion / self.paths[2]))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([self.after[0], self.before[1], self.before[2]], self.contents())
        self.assert_no_temporary_files()

    def test_metadata_failure_does_not_commit_and_rolls_back_prior_files(self):
        result = self.run_script(FAIL_LABEL_BASENAME="audio_effects_common.conf")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.before, self.contents())
        self.assert_no_temporary_files()

    def test_bad_late_asset_hash_prevents_any_configuration_changes(self):
        for asset_kind in ["original", "merged"]:
            with self.subTest(asset_kind=asset_kind):
                asset = self.module / "assets" / asset_kind / self.paths[2]
                expected = asset.read_bytes()
                asset.write_bytes(b"corrupted")
                result = self.run_script()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(self.before, self.contents())
                self.assertFalse((self.root / "rename.log").exists())
                self.assert_no_temporary_files()
                asset.write_bytes(expected)

    def test_changed_fusion_configuration_prevents_the_entire_batch(self):
        (self.fusion / self.paths[2]).write_bytes(b"new driver version")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([self.before[0], self.before[1], b"new driver version"], self.contents())
        self.assertFalse((self.root / "rename.log").exists())

    def test_disabled_removed_or_pending_fusion_update_is_rejected(self):
        for marker in [self.fusion / "disable", self.fusion / "remove",
                       self.root / "modules_update/dsp_fusion"]:
            with self.subTest(marker=marker):
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.touch()
                result = self.run_script()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(self.before, self.contents())
                marker.unlink()

    def test_invalid_duplicate_and_traversal_manifest_rows_are_rejected_before_write(self):
        invalid_paths = ["system/vendor/etc/../audio_effects.xml",
                         "system/vendor/etc//audio_effects.xml",
                         "system/etc/./audio_effects.xml", "system/etc/audio_effects bad.xml",
                         "system/vendor/lib64/audio_effects.xml", "system/etc/not_effects.xml",
                         "/system/etc/audio_effects.xml", "system/etc/audio_effects;bad.xml"]
        bad_rows = [f"{digest(self.before[0])} {digest(self.after[0])} {p}" for p in invalid_paths]
        bad_rows.extend([self.rows[0], "x y system/etc/audio_effects.xml", ""])
        for row in bad_rows:
            with self.subTest(row=row):
                self.manifest.write_text(self.rows[0] + "\n" + row + "\n", encoding="ascii")
                result = self.run_script()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(self.before, self.contents())
                self.assert_no_temporary_files()

    def test_restore_only_after_hash_and_never_overwrites_new_driver_contents(self):
        (self.fusion / self.paths[0]).write_bytes(self.after[0])
        (self.fusion / self.paths[1]).write_bytes(b"new driver version")
        result = self.run_script("restore-configs.sh")
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertEqual([self.before[0], b"new driver version", self.before[2]], self.contents())
        self.assertIn("preserving future update", result.stdout)
        self.assertIn("Already original", result.stdout)
        self.assert_no_temporary_files()

    def test_restore_write_failure_returns_one_and_retains_failed_file(self):
        for path, data in zip(self.paths, self.after):
            (self.fusion / path).write_bytes(data)
        result = self.run_script("restore-configs.sh", FAIL_TARGET=shell_path(self.fusion / self.paths[1]))
        self.assertEqual(1, result.returncode)
        self.assertEqual([self.before[0], self.after[1], self.before[2]], self.contents())
        self.assert_no_temporary_files()

    def test_restore_corrupt_backup_rejects_before_restoring_any_files(self):
        for path, data in zip(self.paths, self.after):
            (self.fusion / path).write_bytes(data)
        (self.module / "assets/original" / self.paths[2]).write_bytes(b"bad original")
        result = self.run_script("restore-configs.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.after, self.contents())
        self.assert_no_temporary_files()

    def test_manifest_without_final_newline_installs_all_rows(self):
        self.manifest.write_text("\n".join(self.rows), encoding="ascii")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.after, self.contents())
        self.assert_no_temporary_files()


if __name__ == "__main__":
    unittest.main()
