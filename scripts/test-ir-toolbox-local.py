"""Run the same IR edit core used by JNI on the local host, without an Android device."""
from pathlib import Path
import argparse
import subprocess
import tempfile
import shutil
import os
import hashlib

parser = argparse.ArgumentParser()
parser.add_argument('--zig', required=True, type=Path)
parser.add_argument('--jdk', type=Path, help='Also test actual JNI loader/minimum-phase entry points using a small host JNI table.')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='dsp-ir-test-', ignore_cleanup_errors=True) as directory:
    work = Path(directory)
    output = Path(directory) / 'ir-edit-test.exe'
    subprocess.run([str(args.zig), 'cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-I', str(root / 'app/src/main/cpp/libjdspimptoolbox/main'),
                    str(root / 'app/src/test/native/IrEditCoreTests.c'), '-o', str(output)], check=True)
    subprocess.run([str(output)], check=True)
    if args.jdk:
        # Unicode dependency paths are unsupported by some Zig/Ninja combinations.
        shutil.copytree(root / 'app/src/main/cpp', work / 'cpp')
        shutil.copy2(root / 'app/src/test/native/IrToolboxJniTests.c', work / 'IrToolboxJniTests.c')
        default = root / 'app/src/main/assets/Convolver/stereo_enhance.irs'
        default_hash = hashlib.sha256(default.read_bytes()).hexdigest()
        shutil.copy2(default, work / 'bundled-default.irs')
        toolbox = work / 'cpp/libjdspimptoolbox'
        jdsp = work / 'cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp'
        output = work / 'ir-jni-test.exe'
        support = work / 'IrToolboxDspSupport.c'
        support.write_text('#define channel_splitFloat jdspHostUnusedChannelSplit\n'
                           '#include "generalDSP/generalProg.c"\n#include "generalDSP/interpolation.c"\n', encoding='utf-8')
        sources = [work / 'IrToolboxJniTests.c', support]
        sources += list((toolbox / 'libsamplerate').glob('*.c'))
        env = dict(os.environ, ZIG_GLOBAL_CACHE_DIR=str(work / 'zig-cache'))
        subprocess.run([str(args.zig), 'cc', '-std=gnu11', '-O2', '-ffunction-sections', '-fdata-sections',
                        '-Wno-error=implicit-function-declaration', '-Wl,--gc-sections',
                        '-I', str(args.jdk / 'include'), '-I', str(args.jdk / 'include/win32'),
                        '-I', str(toolbox / 'main'), '-I', str(jdsp),
                        *map(str, sources), '-lwinpthread', '-o', str(output)], env=env, check=True)
        subprocess.run([str(output)], cwd=work, check=True)
        assert hashlib.sha256(default.read_bytes()).hexdigest() == default_hash
        assert hashlib.sha256((work / 'bundled-default.irs').read_bytes()).hexdigest() == default_hash
        print('Bundled default source unchanged:', default_hash, flush=True)
