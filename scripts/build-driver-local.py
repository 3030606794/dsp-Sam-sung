"""Build the Android module and run native tests locally on Windows.

Uses an owned ASCII snapshot, never an existing build cache or a mapped drive.
No Gradle, network, signing material, phone access, or cloud service is involved.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def junction(path, target):
    # A unique, owned run directory prevents collisions with APK builds. Retain
    # junctions with that directory for inspection; never recursively delete them.
    if path.exists():
        raise RuntimeError(f'Build junction already exists: {path}')
    env = dict(os.environ, DSP_BUILD_LINK=str(path), DSP_BUILD_TARGET=str(target))
    subprocess.run(['powershell.exe', '-NoProfile', '-Command',
                    'New-Item -ItemType Junction -Path $env:DSP_BUILD_LINK '
                    '-Target $env:DSP_BUILD_TARGET | Out-Null'], env=env, check=True)
    if path.resolve() != target.resolve():
        raise RuntimeError(f'Unexpected build junction target: {path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--android-sdk', type=Path, required=True)
    parser.add_argument('--cmake', type=Path, required=True)
    parser.add_argument('--zig', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, default=Path(tempfile.gettempdir()) / 'dsp-native')
    parser.add_argument('--jobs', type=int, default=2, choices=range(1, 9))
    parser.add_argument('--allow-dirty', action='store_true', help='Development only; recorded in manifest.')
    parser.add_argument('--tests-only', action='store_true', help='Run host/installer tests without building or packaging Android drivers.')
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('This helper is for Windows; Linux can use the existing CMake commands.')
    source = args.source_dir.resolve()
    sdk = args.android_sdk.resolve()
    cmake_original = args.cmake.resolve()
    zig_original = args.zig.resolve()
    work_root = args.work_root.resolve()
    if not str(work_root).isascii():
        parser.error('--work-root must be an ASCII path for Ninja/NDK dependency files.')
    required = [cmake_original, zig_original, sdk / 'cmake/3.22.1/bin/ninja.exe',
                sdk / 'ndk/26.3.11579264/build/cmake/android.toolchain.cmake']
    for file in required:
        if not file.is_file():
            raise FileNotFoundError(file)
    status = subprocess.check_output(['git', 'status', '--porcelain'], cwd=source).decode('utf-8')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source).decode().strip()
    if status and not args.allow_dirty:
        raise RuntimeError('Commit the reviewed source before a release build, or use --allow-dirty for development.')
    work_root.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='driver-', dir=work_root))
    owner = {'source': str(source), 'commit': commit, 'createdUtc': datetime.datetime.now(datetime.UTC).isoformat(),
             'junctionTargets': {'cmake': str(cmake_original.parent.parent), 'sdk': str(sdk)},
             'copiedTools': {'zig': str(zig_original.parent)}}
    (run / '.dsp-driver-owner.json').write_text(json.dumps(owner, indent=2), encoding='utf-8')
    snapshot = run / 'source'
    snapshot.mkdir()
    # Only driver inputs are copied; in particular private signing files and APK
    # outputs are never included. Reject links that could escape this source tree.
    inputs = ['native', 'app/src/main/cpp/libjamesdsp',
              'app/src/main/assets/Liveprog', 'app/src/main/assets/LiveprogRuntime',
              'module', 'scripts', 'docs/THIRD-PARTY.md']
    hashes = {}
    for relative in inputs:
        item = source / relative
        files = sorted(item.rglob('*')) if item.is_dir() else [item]
        for file in files:
            if file.is_symlink() or not file.resolve().is_relative_to(source):
                raise RuntimeError(f'Linked source input is not supported: {file}')
            if file.is_file() and '__pycache__' not in file.parts:
                name = file.relative_to(source).as_posix()
                target = snapshot / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(file, target)
                hashes[name] = digest(target)
    if any(digest(source / name) != value for name, value in hashes.items()):
        raise RuntimeError('Source changed while the snapshot was copied; retry after edits finish.')
    if (subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source).decode().strip() != commit
            or subprocess.check_output(['git', 'status', '--porcelain'], cwd=source).decode('utf-8') != status):
        raise RuntimeError('Git state changed while the snapshot was copied; retry after edits finish.')
    for name, target in [('cmake', cmake_original.parent.parent), ('sdk', sdk)]:
        junction(run / name, target)
    # Zig canonicalizes its own library location even through a junction. Copy
    # the existing portable tool (no download/install) so depfiles are ASCII too.
    shutil.copytree(zig_original.parent, run / 'zig')
    cmake = run / 'cmake/bin/cmake.exe'
    ctest = run / 'cmake/bin/ctest.exe'
    zig = run / 'zig/zig.exe'
    # Cached Zig dependency lists can retain a former Unicode tool path. A fresh
    # ASCII cache keeps Ninja rebuilds independent of earlier compiler probes.
    os.environ['ZIG_GLOBAL_CACHE_DIR'] = str(run / 'zig-cache')
    ninja = run / 'sdk/cmake/3.22.1/bin/ninja.exe'
    ndk = run / 'sdk/ndk/26.3.11579264'
    output = args.output_dir.resolve()
    reports = args.report_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    reports.mkdir(parents=True, exist_ok=True)
    manifest = dict(owner, dirty=bool(status), sourceHash=hashlib.sha256(
        json.dumps(hashes, sort_keys=True).encode()).hexdigest(), sourceFileCount=len(hashes),
        workDirectory=str(run), status='building', sanitizer='none',
        zigCache=str(run / 'zig-cache'),
        sanitizerNote='This Windows run does not enable a memory sanitizer; these are ordinary native tests.',
        androidApi=26, abis=['arm64-v8a', 'armeabi-v7a'], hostTarget='x86_64-windows-gnu')
    (reports / 'driver-source-files.json').write_text(json.dumps(hashes, indent=2), encoding='utf-8')
    manifest_path = reports / 'driver-manifest.json'
    log_path = reports / 'local-driver-build.log'
    build = run / 'build'

    def execute(command, label, separate_log=None):
        print(label, flush=True)
        log.write('\n' + label + '\n' + subprocess.list2cmdline([str(x) for x in command]) + '\n')
        log.flush()
        if separate_log:
            with separate_log.open('w', encoding='utf-8', newline='\n') as part:
                result = subprocess.run([str(x) for x in command], cwd=snapshot, stdout=part, stderr=subprocess.STDOUT)
            log.write(separate_log.read_text(encoding='utf-8', errors='replace'))
        else:
            result = subprocess.run([str(x) for x in command], cwd=snapshot, stdout=log, stderr=subprocess.STDOUT)
        log.flush()
        if result.returncode:
            raise RuntimeError(f'{label} failed ({result.returncode}); see {log_path}')

    try:
        with log_path.open('w', encoding='utf-8', newline='\n') as log:
            manifest['toolchain'] = {
                'cmake': subprocess.check_output([cmake, '--version']).decode().splitlines()[0],
                'ninja': subprocess.check_output([ninja, '--version']).decode().strip(),
                'zig': subprocess.check_output([zig, 'version']).decode().strip(),
                'hostCompiler': subprocess.check_output([zig, 'cc', '--version']).decode().splitlines()[0],
                'androidCompiler': subprocess.check_output([ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe', '--version']).decode().splitlines()[0],
                'ndk': (ndk / 'source.properties').read_text(encoding='utf-8').strip(),
                'python': sys.version.split()[0],
            }
            common = ['-G', 'Ninja', f'-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}']
            host = [f'-DCMAKE_C_COMPILER={zig.as_posix()}', '-DCMAKE_C_COMPILER_ARG1=cc',
                    f'-DCMAKE_CXX_COMPILER={zig.as_posix()}', '-DCMAKE_CXX_COMPILER_ARG1=c++',
                    '-DCMAKE_BUILD_TYPE=Debug']
            execute([sys.executable, snapshot / 'scripts/test-module-installer.py', '-v'],
                    'Test module installer', reports / 'installer-tests.log')
            execute([cmake, '-S', snapshot / 'native', '-B', build / 'host', *common, *host,
                     '-DFUSION_BUILD_TESTS=ON', '-DFUSION_SANITIZE=OFF'], 'Configure native tests')
            execute([cmake, '--build', build / 'host', '--parallel', args.jobs], 'Build native tests')
            execute([cmake, '--build', build / 'host', '--parallel', args.jobs], 'Verify native build dependency paths')
            execute([ctest, '--test-dir', build / 'host', '--output-on-failure', '--verbose',
                     '--output-junit', run / 'native-tests.xml'], 'Run native tests', reports / 'native-tests.log')
            shutil.copy2(run / 'native-tests.xml', reports / 'native-tests.xml')
            execute([cmake, '-S', snapshot / 'native/tone', '-B', build / 'tone', *common, *host], 'Configure tone tests')
            execute([cmake, '--build', build / 'tone', '--parallel', args.jobs], 'Build tone tests')
            execute([ctest, '--test-dir', build / 'tone', '--output-on-failure', '--verbose',
                     '--output-junit', run / 'tone-tests.xml'], 'Run tone tests', reports / 'tone-tests.log')
            shutil.copy2(run / 'tone-tests.xml', reports / 'tone-tests.xml')
            if args.tests_only:
                manifest.update(status='tests-passed', testsOnly=True)
                print(f'Local tests passed. Reports: {reports}', flush=True)
                return
            manifest['libraries'] = {}
            for abi in manifest['abis']:
                abi_build = build / f'driver-{abi}'
                execute([cmake, '-S', snapshot / 'native', '-B', abi_build, *common,
                         f'-DCMAKE_TOOLCHAIN_FILE={(ndk / "build/cmake/android.toolchain.cmake").as_posix()}',
                         f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-26',
                         '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release'], f'Configure {abi}')
                execute([cmake, '--build', abi_build, '--parallel', args.jobs], f'Build {abi}')
                library = abi_build / 'libdspfusion.so'
                manifest['libraries'][abi] = {'sha256': digest(library), 'bytes': library.stat().st_size}
            package_args = ['--source-dir', snapshot, '--build-dir', build, '--output-dir', output]
            execute([sys.executable, snapshot / 'scripts/package-module.py', *package_args], 'Package driver')
            execute([sys.executable, snapshot / 'scripts/validate-release.py', '--driver-only', *package_args],
                    'Validate package', reports / 'package-validation.log')
            properties = dict(line.split('=', 1) for line in (snapshot / 'module/module.prop').read_text(
                encoding='utf-8').splitlines() if line and not line.startswith('#'))
            artifact = output / f'DSP-Audio-Driver-{properties["version"]}.zip'
            manifest.update(status='passed', version=properties['version'], versionCode=properties['versionCode'],
                            artifact=str(artifact), sha256=digest(artifact))
    except BaseException as error:
        manifest.update(status='failed', error=str(error))
        raise
    finally:
        for name in ('native-tests.xml', 'tone-tests.xml'):
            if (run / name).is_file():
                shutil.copy2(run / name, reports / name)
        manifest['finishedUtc'] = datetime.datetime.now(datetime.UTC).isoformat()
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8', newline='\n')
    print(f'Local build passed. Reports: {reports}', flush=True)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    main()
