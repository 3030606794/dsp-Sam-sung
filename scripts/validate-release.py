from pathlib import Path
from zipfile import ZipFile
import argparse
import hashlib
import shutil
import subprocess

parser = argparse.ArgumentParser(description='Validate the packaged driver without requiring an APK.')
parser.add_argument('--driver-only', action='store_true',
                    help='Explicit driver-only mode (also the default for compatibility).')
parser.add_argument('--source-dir', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--build-dir', type=Path, help='Also verify binary identity against this build tree.')
parser.add_argument('--output-dir', type=Path, help='Directory containing the module and SHA256SUMS.txt.')
args = parser.parse_args()
root = args.source_dir.resolve()
output_dir = (args.output_dir or root / 'dist').resolve()
properties = dict(line.split('=', 1) for line in
                  (root / 'module/module.prop').read_text(encoding='utf-8').splitlines()
                  if line and not line.startswith('#'))
output = output_dir / f"DSP-Audio-Driver-{properties['version']}.zip"
shell = shutil.which('sh') or 'C:/Program Files/Git/bin/sh.exe'


def unix_text(file):
    return file.read_bytes().replace(b'\r\n', b'\n')


with ZipFile(output) as z:
    if z.testzip() is not None:
        raise ValueError('Driver ZIP has a corrupt member.')
    if len(z.namelist()) != len(set(z.namelist())):
        raise ValueError('Driver ZIP has duplicate entries.')
    for suffix, elf_class, machine in [('lib64', 2, 183), ('lib', 1, 40)]:
        blob = z.read(f'system/vendor/{suffix}/soundfx/libdspfusion.so')
        if (len(blob) < 20 or blob[:4] != b'\x7fELF' or blob[4] != elf_class
                or blob[5] != 1 or int.from_bytes(blob[18:20], 'little') != machine):
            raise ValueError(f'Unexpected ELF architecture in {suffix} driver.')
        if args.build_dir is not None:
            abi = 'arm64-v8a' if suffix == 'lib64' else 'armeabi-v7a'
            candidates = list((args.build_dir / f'driver-{abi}').rglob('libdspfusion.so'))
            if len(candidates) != 1 or blob != candidates[0].read_bytes():
                raise ValueError(f'Packaged driver does not match the selected {abi} build.')
    if properties.get('id') != 'dsp_fusion' or not properties['versionCode'].isdigit():
        raise ValueError('Invalid module identity or version code.')
    if z.read('module.prop') != unix_text(root / 'module/module.prop'):
        raise ValueError('Packaged module metadata does not match this checkout.')
    for file in root.joinpath('module').glob('*.sh'):
        content = z.read(file.name)
        if content != unix_text(file) or b'\r' in content:
            raise ValueError(f'Stale script or non-Unix line endings in {file.name}.')
        subprocess.run([shell, '-n'], input=content, check=True)
    if z.read('THIRD-PARTY.md') != unix_text(root / 'docs/THIRD-PARTY.md'):
        raise ValueError('Missing or stale third-party notices.')
digest = hashlib.sha256(output.read_bytes()).hexdigest()
expected_checksum = f'{digest}  {output.name}\n'
driver_checksums = [line for line in (output_dir / 'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines()
                    if line.endswith(f'  {output.name}')]
if driver_checksums != [expected_checksum.rstrip('\n')]:
    raise ValueError('Driver SHA256SUMS.txt does not match the ZIP.')
print(f'Validated {output.name}: ELF architectures, module metadata, shell syntax and SHA-256. No APK required.')
