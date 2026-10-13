DTS compatibility tools — Samsung SM-S9180 / Android 15

This directory contains original integration tools only. DTS APKs, shared
libraries, license/configuration files and phone backups are not included in
the repository. Do not describe the proprietary DTS binaries as open source.

Upstream module:
https://github.com/reiryuki/DTSX-Ultra-ZTE-A2020G-Pro-Magisk-Module
Inspected revision: 8ccb74ad2a9af512faf007d04837d49b4fc3f335

Design
- Preserve the existing dsp_fusion module, driver libraries and application data.
- Merge DTS registration and music apply into the six existing full audio
  configurations owned by Fusion. Preserve Samsung/OEM registrations and applies.
- Install DTS-specific libraries, its original APK, Java wrapper and data in a
  separate Magisk module (dts_fusion_compat). That module does not mount competing
  audio_effects files and does not install old common libraries or codec patches.
- Do not enable AML, restart audio services, change SELinux to permissive or
  inherit the original module's broad SELinux rules.
- The upstream model properties are still required by the original DTS license.
  service.sh sets ro.product.model and ro.build.product at runtime. Disabling or
  removing the module and rebooting restores the normal boot properties. This
  may affect Samsung/model-sensitive applications while enabled.

Build
python build_device_module.py UPSTREAM_DIRECTORY VERIFIED_FUSION_BACKUP OUTPUT.zip

The backup argument must contain the original Fusion module's system/ tree.
The output is a private, device-specific package, not a general installer.
The installer checks all original hashes and rejects disabled/removed Fusion or
pending Fusion module updates. Configurations use same-directory atomic rename.
Reinstallation must not reinterpret an already merged configuration as original.

Recovery
Use the Magisk module's Action button, then reboot. The action restores only
files matching the recorded merged hash, and then marks DTS disabled. A changed
configuration is reported rather than overwritten. Directly disabling the
module does NOT run this restoration step. Uninstall also attempts guarded
restoration, retaining user data. Fusion updates require rechecking/rebuilding
the compatibility merge; never overwrite a new configuration with an old backup.

Checks
python -m unittest discover -s tools/dts-compat/tests -v

29 tests cover XML/legacy configuration preservation and conflicts, transaction
failure rollback, state changes, hash guards and recovery. Script metadata
commands are mocked during host tests; the real device installer sets labels.
The native probe uses existing AudioEffect ABI declarations from this project.
Compile with NDK clang targeting aarch64-linux-android28 and link -ldl.
Default usage: native-probe /absolute/path/libdtsaudio.so --maps
Optional --create invokes vendor initialization; it is not a listening test.

Installation record, 2026-10-13
- Installed 5.9-samsung-compat1 (5901) on the connected SM-S9180.
- Existing Fusion driver remains 0.1.0-alpha12-surround1 (1013).
- Existing application remains 0.1.0-alpha12-surround1-ui1 (1014).
- Verified 115 hashes covering original driver/user data, merged configurations
  and staged DTS files. Audio service PIDs did not change during installation.
- Native dlopen(RTLD_NOW) and descriptor lookup succeeded using this phone's
  vendor linker configuration via /data/local/tests/vendor. Common dependencies
  resolved to Samsung system/VNDK v33 libraries, not bundled replacements.
- 6 merged configurations and packaged scripts passed final archive checks.
- Reboot is pending. Actual HAL loading, original DTS license initialization,
  routing and sound must still be verified after reboot. The user requested
  to perform listening tests independently. No reboot was triggered.

Private local evidence is outside the repository under
04 验证记录/DTS兼容安装; the archive is under 02 Magisk驱动/DTS兼容版.
