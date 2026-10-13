"""Extract Neutron 2.29.0 bundled Default EQ presets from the supplied APK.
No application code, audio assets, license checks, or user data are copied.
See docs/NEUTRON-SYSTEM-PRESETS.zh-CN.txt for evidence and limitations.
"""
from pathlib import Path
import json, struct, math, hashlib, zipfile, collections, argparse
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True, help='Locally supplied Neutron 2.29.0 APK')
parser.add_argument('--out-dir', type=Path, required=True, help='Directory for extracted numerical data')
args = parser.parse_args()
BASE = args.out_dir.resolve()
APK = args.apk.resolve()
if hashlib.sha256(APK.read_bytes()).hexdigest() != 'be8013cd74962e9cb93e338a02191aff6a0eaf3fb9694b0b6cc4f0e2a40a8343':
    raise ValueError('APK differs from the verified Neutron 2.29.0 input; offsets must be reanalysed')
BASE.mkdir(parents=True, exist_ok=True)
ENTRY = 'lib/arm64-v8a/libneutronmp.so'
with zipfile.ZipFile(APK) as z:
    blob = z.read(ENTRY)

def u32(addr): return struct.unpack_from('<I', blob, addr)[0]
def f32read(addr): return struct.unpack_from('<f', blob, addr)[0]
def f32(value): return struct.unpack('<f', struct.pack('<f', value))[0]
def text(addr): return blob[addr:blob.index(b'\x00',addr)].decode('ascii')
def quality_q(bw, frequency, sample_rate=48000):
    omega=2*math.pi*frequency/sample_rate
    return 1/(2*math.sinh(math.log(2)/2*bw*omega/math.sin(omega)))

def band_record(kind, frequency, quality_mode, quality, gain):
    assert kind in (11,12,13)
    assert quality_mode in (0,1)
    band={'type':{11:'PEAK',12:'LOW_SHELF',13:'HIGH_SHELF'}[kind],
          'enabled':True,'frequency':frequency,'gain':gain,
          'q':quality if quality_mode==0 else quality_q(quality,frequency),
          'qualityDisplay':'Q' if quality_mode==0 else 'BW',
          'sourceTypeId':kind,'sourceQualityMode':quality_mode,'sourceQualityValue':quality}
    if quality_mode==1: band['sourceBandwidthOctaves']=quality
    return band

def decode_template(addr, count):
    assert u32(addr)==count and u32(addr+4)==0
    return [band_record(*struct.unpack_from('<IfIff',blob,addr+8+i*20)) for i in range(count)]

def parametric_correction(count):
    jump_table=0x14772ac
    branch=jump_table+struct.unpack_from('<i',blob,jump_table+4*(count-11))[0]
    if branch==0x28b560:
        constant=0x1477258
        return f32read(constant), {'jumpTarget':hex(branch),'constant':hex(constant)}
    if branch==0x28b77c:
        assert u32(branch)==0x1e281000 # fmov s0,#0.125
        return 0.125, {'jumpTarget':hex(branch),'instruction':'fmov s0,#0.125'}
    # adrp x8,0x1477000; ldr s0,[x8,#constantOffset]; b 0x28b560
    assert u32(branch+4)&0xffc003ff==0xbd400100
    constant=0x1477000+((u32(branch+4)>>10)&0xfff)*4
    return f32read(constant), {'jumpTarget':hex(branch),'constant':hex(constant)}

def generate(count, upper, graphic):
    correction, evidence = (0, {}) if graphic else parametric_correction(count)
    bandwidth=f32(f32(10/count)-correction)
    # Native exp2(double(float BW)), then round to float; reciprocal and each
    # repeated multiplication are float32 too. Preserve this exact ordering.
    reciprocal=f32(1/f32(2**bandwidth))
    current=f32(upper)
    descending=[]
    for _ in range(count):
        descending.append(current)
        current=f32(current*reciprocal)
    bands=[]
    for i,frequency in enumerate(reversed(descending)):
        if not graphic and i in (0,count-1):
            bands.append(band_record(12 if i==0 else 13,frequency,0,f32read(0x1471a8c),0.0))
        else:
            bands.append(band_record(11,frequency,1,bandwidth,0.0))
    return bands, {'generator':'0x28b528','upperFrequency':upper,'spacingOctaves':bandwidth,
                   'float32Reciprocal':reciprocal,'parametricCorrection':correction,**evidence}

presets=[]
# The UI has parametric defaults before graphic defaults; retain original row order.
for category,base,count in [('parametric',0x184f3c0,29),('graphic',0x184ef88,45)]:
    for index in range(count):
        row=base+24*index
        name_addr,band_count,template=struct.unpack_from('<QQQ',blob,row)
        name=text(name_addr)
        evidence={'tableRow':hex(row),'sourceIndex':index}
        if template:
            bands=decode_template(template,band_count)
            evidence['templateOffset']=hex(template)
        else:
            upper=(16000 if name=='Flat 16k' else 20000) if category=='graphic' else (20000 if index>=19 else 18000)
            bands,generated=generate(band_count,upper,category=='graphic')
            evidence.update(generated)
        if category=='graphic':name+=f' ({band_count}-Band Graphic)'
        presets.append({'id':f'neutron-default-{category}-{index:02d}','name':name,'category':category,
                        'sourceBandCount':band_count,'sourcePreampDb':-max(0.0,*[b['gain'] for b in bands]),
                        'evidence':evidence,'bands':bands})
assert len(presets)==74 and len(set(p['name'] for p in presets))==74
assert all(len(p['bands'])==p['sourceBandCount'] for p in presets)
raw={'format':'neutron-default-presets-extraction','version':1,
     'source':{'apk':'Neutron Player_2.29.0.apk','apkSha256':hashlib.sha256(APK.read_bytes()).hexdigest(),
               'library':ENTRY,'librarySha256':hashlib.sha256(blob).hexdigest(),
               'parametricCount':29,'graphicCount':45,'total':74,
               'qReferenceSampleRate':48000,
               'qualityConversion':'Q=1/(2*sinh(ln(2)/2*BW*omega/sin(omega))), omega=2*pi*f/Fs',
               'qualityConversionCode':'0x35b0b0..0x35b290',
               'countLoopCode':['0x28c614: cmp x22,#0x1d','0x28c1b4: cmp x22,#0x2d'],
               'preampCode':'0x28bfc4..0x28bfcc and 0x28c4dc..0x28c4e4 call 0x28adf8 (-max nonnegative gain)'
             },'presets':presets}
(BASE/'neutron-default-presets.json').write_text(json.dumps(raw,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows=['order\tcategory\tname\tcount\tminFreq\tmaxFreq\tminQ48k\tmaxQ48k\tpreampDb\ttableRow']
for i,p in enumerate(presets,1):
    bs=p['bands'];rows.append('\t'.join(map(str,[i,p['category'],p['name'],len(bs),min(b['frequency'] for b in bs),max(b['frequency'] for b in bs),min(b['q'] for b in bs),max(b['q'] for b in bs),p['sourcePreampDb'],p['evidence']['tableRow']])))
(BASE/'catalog.tsv').write_text('\n'.join(rows)+'\n',encoding='utf-8')
low=[{'name':p['name'],'bands':[{'index':i+1,'frequency':b['frequency']} for i,b in enumerate(p['bands']) if b['frequency']<20]} for p in presets if any(b['frequency']<20 for b in p['bands'])]
summary={'presets':len(presets),'bands':sum(len(p['bands']) for p in presets),'frequencyMin':min(b['frequency'] for p in presets for b in p['bands']),'frequencyMax':max(b['frequency'] for p in presets for b in p['bands']),'qMin48k':min(b['q'] for p in presets for b in p['bands']),'qMax48k':max(b['q'] for p in presets for b in p['bands']),'sub20Presets':low}
(BASE/'extraction-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k!='sub20Presets'},ensure_ascii=False))
print('presets with sub-20Hz bands:',len(low),'total bands:',sum(len(p['bands']) for p in low))
