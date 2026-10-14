from pathlib import Path
from zipfile import ZipFile
import argparse
import hashlib
import shutil
import subprocess

parser = argparse.ArgumentParser(description='Validate the packaged driver without requiring an APK.')
parser.add_argument('--driver-only', action='store_true',
                    help='Explicit driver-only mode (also the default for compatibility).')
parser.parse_args()
root = Path(__file__).resolve().parents[1]
properties = dict(line.split('=', 1) for line in
                  (root / 'module/module.prop').read_text(encoding='utf-8').splitlines()
                  if line and not line.startswith('#'))
output = root / f"dist/DSP-Audio-Driver-{properties['version']}.zip"
shell = shutil.which('sh') or 'C:/Program Files/Git/bin/sh.exe'
for file in root.joinpath('module').glob('*.sh'):
    subprocess.run([shell, '-n', file.as_posix()], check=True)
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
    if properties.get('id') != 'dsp_fusion' or not properties['versionCode'].isdigit():
        raise ValueError('Invalid module identity or version code.')
    if z.read('module.prop') != (root / 'module/module.prop').read_bytes():
        raise ValueError('Packaged module metadata does not match this checkout.')
    for file in root.joinpath('module').glob('*.sh'):
        if z.read(file.name) != file.read_bytes() or b'\r' in z.read(file.name):
            raise ValueError(f'Stale script or non-Unix line endings in {file.name}.')
    if z.read('THIRD-PARTY.md') != (root / 'docs/THIRD-PARTY.md').read_bytes():
        raise ValueError('Missing or stale third-party notices.')
digest = hashlib.sha256(output.read_bytes()).hexdigest()
expected_checksum = f'{digest}  {output.name}\n'
if (root / 'dist/SHA256SUMS.txt').read_text(encoding='utf-8') != expected_checksum:
    raise ValueError('Driver SHA256SUMS.txt does not match the ZIP.')
print(f'Validated {output.name}: ELF architectures, module metadata, shell syntax and SHA-256. No APK required.')
