from pathlib import Path
from zipfile import ZipFile
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
for file in root.joinpath('module').glob('*.sh'):
    subprocess.run(['sh', '-n', str(file)], check=True)
with ZipFile(root / 'dist/DSP-Audio-Driver-0.1.0-alpha10.zip') as z:
    assert z.testzip() is None
    for suffix, elf_class, machine in [('lib64', 2, 183), ('lib', 1, 40)]:
        blob = z.read(f'system/vendor/{suffix}/soundfx/libdspfusion.so')
        assert blob[:4] == b'\x7fELF' and blob[4] == elf_class
        assert int.from_bytes(blob[18:20], 'little') == machine
    assert 'id=dsp_fusion' in z.read('module.prop').decode()
    assert '\r' not in z.read('customize.sh').decode()
print('Validated driver ZIP, ELF architectures and module shell syntax.')
