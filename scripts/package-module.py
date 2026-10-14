from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import re

root = Path(__file__).resolve().parents[1]
properties = dict(line.split('=', 1) for line in
                  (root / 'module/module.prop').read_text(encoding='utf-8').splitlines()
                  if line and not line.startswith('#'))
version = properties['version']
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version):
    raise ValueError(f'Invalid module version: {version!r}')
files = {file.relative_to(root / 'module').as_posix(): file
         for file in (root / 'module').rglob('*') if file.is_file()}
for abi, libdir in [('arm64-v8a', 'lib64'), ('armeabi-v7a', 'lib')]:
    candidates = list((root / f'build/driver-{abi}').rglob('libdspfusion.so'))
    if len(candidates) != 1:
        raise RuntimeError(f'Expected one driver for {abi}: {candidates}')
    files[f'system/vendor/{libdir}/soundfx/libdspfusion.so'] = candidates[0]
files['THIRD-PARTY.md'] = root / 'docs/THIRD-PARTY.md'
(root / 'dist').mkdir(exist_ok=True)
output = root / f'dist/DSP-Audio-Driver-{version}.zip'
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for name, file in sorted(files.items()):
        archive.write(file, name)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
(root / 'dist/SHA256SUMS.txt').write_text(f'{digest}  {output.name}\n', encoding='utf-8')
print(f'{output.name}: {digest}')
