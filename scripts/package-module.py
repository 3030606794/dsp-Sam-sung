from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import shutil

root = Path(__file__).resolve().parents[1]
stage = root / 'build/module-package'
stage.mkdir(parents=True, exist_ok=True)
shutil.copytree(root / 'module', stage, dirs_exist_ok=True)
for abi, libdir in [('arm64-v8a', 'lib64'), ('armeabi-v7a', 'lib')]:
    candidates = list((root / f'build/driver-{abi}').rglob('libdspfusion.so'))
    if len(candidates) != 1:
        raise RuntimeError(f'Expected one driver for {abi}: {candidates}')
    destination = stage / f'system/vendor/{libdir}/soundfx/libdspfusion.so'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(candidates[0], destination)
shutil.copy2(root / 'docs/THIRD-PARTY.md', stage / 'THIRD-PARTY.md')
(root / 'dist').mkdir(exist_ok=True)
output = root / 'dist/DSP-Audio-Driver-0.1.0-alpha10.zip'
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for file in sorted(stage.rglob('*')):
        if file.is_file():
            archive.write(file, file.relative_to(stage))
print(f'{output.name}: {hashlib.sha256(output.read_bytes()).hexdigest()}')
