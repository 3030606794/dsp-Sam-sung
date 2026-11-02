"""Package the reviewed source with explicitly selected legacy and AIDL builds."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import argparse
import hashlib
import re


def build_package(root, require_aidl=False, output=None, aidl_build_dir=None,
                  build_dir=None, output_dir=None):
    root = Path(root).resolve()
    build_dir = Path(build_dir or root / 'build').resolve()
    output_dir = Path(output_dir or root / 'dist').resolve()
    properties = dict(line.split('=', 1) for line in
                      (root / 'module/module.prop').read_text(encoding='utf-8-sig').splitlines()
                      if line and not line.startswith('#'))
    version = properties['version'].strip()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version):
        raise ValueError(f'Invalid module version: {version!r}')
    files = {file.relative_to(root / 'module').as_posix(): file
             for file in (root / 'module').rglob('*') if file.is_file()}
    for abi, libdir in [('arm64-v8a', 'lib64'), ('armeabi-v7a', 'lib')]:
        candidates = list((build_dir / f'driver-{abi}').rglob('libdspfusion.so'))
        if len(candidates) != 1:
            raise RuntimeError(f'Expected one driver for {abi}: {candidates}')
        files[f'system/vendor/{libdir}/soundfx/libdspfusion.so'] = candidates[0]
    aidl_directory = Path(aidl_build_dir or build_dir / 'driver-aidl-arm64-v8a').resolve()
    aidl = list(aidl_directory.rglob('libdspfusion_aidl.so'))
    if len(aidl) > 1 or (require_aidl and not aidl):
        raise RuntimeError(f'Expected one ARM64 AIDL driver: {aidl}')
    if aidl:
        files['system/vendor/lib64/soundfx/libdspfusion_aidl.so'] = aidl[0]
        license_file = root / 'native/aidl/sdk/upstream/LICENSE-Apache-2.0.txt'
        if not license_file.is_file() or not license_file.read_bytes().strip():
            raise RuntimeError('AIDL SDK license is missing; no module was packaged.')
        files['LICENSE-Apache-2.0.txt'] = license_file
    files['THIRD-PARTY.md'] = root / 'docs/THIRD-PARTY.md'

    # Direct selected inputs prevent stale staging libraries entering a package.
    output = Path(output).resolve() if output else output_dir / f'DSP-Audio-Driver-{version}.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
        for name, file in sorted(files.items()):
            content = file.read_bytes()
            if file.suffix in {'.sh', '.prop', '.md', '.txt', '.conf', '.xml', '.awk'}:
                content = content.replace(b'\r\n', b'\n')
            info = ZipInfo.from_file(file, name)
            info.create_system = 3
            info.external_attr = (0o100755 if file.suffix == '.sh' else 0o100644) << 16
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, content)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (output.parent / 'SHA256SUMS.txt').write_text(
        f'{digest}  {output.name}\n', encoding='utf-8', newline='\n')
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--build-dir', type=Path,
                        help='Parent of driver-arm64-v8a, driver-armeabi-v7a and driver-aidl-arm64-v8a.')
    parser.add_argument('--aidl-build-dir', type=Path, help='Override the selected ARM64 AIDL build directory.')
    parser.add_argument('--require-aidl', action='store_true',
                        help='Required for vivo V2454A Android 15: refuse a ZIP without its native ARM64 AIDL transport.')
    parser.add_argument('--output-dir', type=Path, help='Destination for the module and SHA256SUMS.txt.')
    parser.add_argument('--output', type=Path, help='Optional explicit destination ZIP path.')
    args = parser.parse_args()
    output = build_package(args.source_dir, args.require_aidl, args.output,
                           args.aidl_build_dir, args.build_dir, args.output_dir)
    print(f'{output.name}: {hashlib.sha256(output.read_bytes()).hexdigest()}')
