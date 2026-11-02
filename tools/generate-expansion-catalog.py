from pathlib import Path
import json, math
root=Path(__file__).resolve().parents[1]
native=root.parent/'driver/native/expansion'
out=root/'app/src/main/java/me/timschneeberger/rootlessjamesdsp/expansion'
items=json.loads((native/'group_a_catalog.json').read_text(encoding='utf-8-sig'))+json.loads((native/'group_b_catalog.json').read_text(encoding='utf-8-sig'))
head=[('mix','效果混合',1,0,1,.01,'%'),('angle','虚拟音箱夹角',30,5,90,1,'°'),('room','空间反射量',.15,0,1,.01,'%'),('distance','虚拟音箱距离',1.5,.2,10,.1,'m'),('smoothing','姿态平滑时间',40,0,500,1,'ms'),('yaw','水平角',0,-180,180,1,'°'),('pitch','俯仰角',0,-90,90,1,'°'),('roll','侧倾角',0,-180,180,1,'°'),('connected','设备连接',0,0,1,1,''),('pose_epoch','姿态序号',0,0,1999999,1,'')]
vocal=[('mix','效果混合',1,0,1,.01,'%'),('vocal_gain','人声增益',0,-60,12,.1,'dB'),('accompaniment_gain','伴奏增益',0,-60,12,.1,'dB'),('output','输出增益',0,-12,12,.1,'dB')]
def makecontrols(rows):return [dict(zip(['key','label','default','min','max','step','unit'],r)) for r in rows]
items += json.loads((native/'group_c_catalog.json').read_text(encoding='utf-8-sig'))
enum=['MICROSHIFT','FORMANT','FREQUENCY_SHIFT','VOCODER','GATED_REVERB','PINGPONG','AUTO_WAH','AUTOPAN','LOW_PHASE','TAPE','MS_EQ','MATCH_EQ','TRANSIENT','RESONANCE','SUB_BASS','LOUDNESS','DEREVERB','DECLICK','DEHUM','HEADTRACK','VOCAL']
basic={0:['mix','detune','width'],1:['mix','shift','strength'],2:['mix','shift','stereo'],3:['mix','carrier','formant'],4:['mix','hold','close'],5:['mix','bpm','feedback'],6:['mix','sensitivity','q'],7:['depth','rate','mode'],8:['frequency','amount','mix'],9:['drive','mix','wow'],10:['mix','output'],11:['strength','max_gain'],12:['low_attack','mid_attack','high_attack'],13:['threshold','depth','mix'],14:['mix','level'],15:['bass_gain','treble_gain'],16:['strength','mix'],17:['threshold','crackle'],18:['base','depth'],19:['angle','room'],20:['vocal_gain','accompaniment_gain']}
def q(s):return json.dumps(s,ensure_ascii=False)
lines=['package me.timschneeberger.rootlessjamesdsp.expansion','', 'import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters','', '// Generated from the same ordered control catalog used by the native processors.', 'internal object ExpansionEffectsCatalog {','    val entries: Map<ExpansionEffectsKind, List<ExpansionControl>> = mapOf(']
for e in items:
    lines += [f'        ExpansionEffectsKind.{enum[e["id"]]} to listOf(']
    for i,c in enumerate(e['controls']):
        lo,hi,d=(round(float(c[x])*1000) for x in ['min','max','default'])
        hi=min(hi,1999999000)
        unit=c.get('unit','');ispercent=unit in ['%','ratio'] and float(c['max'])<=2 and not (e['id']==9 and c['key'] in ['wow','flutter'])
        divisor=10 if ispercent else 1000
        unit={'cent':'音分','st':'半音','ratio':'%','x':'倍'}.get(unit,unit)
        step=max(1,round(float(c.get('step',1))*1000))
        shownstep=step/divisor
        decimals=max(0,min(3,math.ceil(-math.log10(shownstep)-1e-8))) if shownstep<1 else 0
        opts=c.get('options',[])
        if c['key'].endswith('_enable') or c['key']=='connected':opts=['关闭','开启']
        optioncode='mapOf('+','.join(f'{j*1000} to {q(v)}' for j,v in enumerate(opts))+')' if opts else 'emptyMap()'
        if c['key'] in ['bands','stages','peaks','harmonics']:
            optioncode='mapOf('+','.join(f'{j*1000} to {q(str(j))}' for j in range(int(c['min']),int(c['max'])+1,max(1,int(c.get('step',1)))))+')'
        if e['id']==18 and c['key']=='base': optioncode='mapOf(50000 to "50 Hz",60000 to "60 Hz")'
        hidden=c['key'] in ['media_volume','reference_capture_epoch','target_capture_epoch','yaw','pitch','roll','connected','pose_epoch']
        section='频段与滤波' if any(x in c['key'] for x in ['frequency','low','high','q','tone','band','cut']) else '动态与时间' if any(x in c['key'] for x in ['attack','release','hold','close','decay','delay','tracking','adaptation']) else '音色与空间' if c['key'] in ['shift','width','detune','formant','carrier','wave','depth','stereo','rate','phase','bias','wow','flutter','angle','distance'] else '混合与输出'
        if e['id']==9:
            section='混合与输出' if c['key'] in ['mix','output'] else '机械抖晃与抖颤' if c['key'] in ['wow','flutter','wow_rate','flutter_rate'] else '磁带底噪' if c['key']=='noise' else '磁带音色'
        if e['id']==12:
            section='分频点' if c['key'] in ['low_split','high_split'] else '低频塑形' if c['key'].startswith('low_') else '中频塑形' if c['key'].startswith('mid_') else '高频塑形' if c['key'].startswith('high_') else '瞬态检测' if c['key'] in ['attack','release'] else '混合与输出'
        if e['id']==20: section='人声与伴奏' if c['key'] in ['vocal_gain','accompaniment_gain'] else '混合与输出'
        if e['id']==19: section='虚拟音箱' if c['key'] in ['angle','distance','room'] else '姿态响应' if c['key']=='smoothing' else '混合与输出'
        lines += [f'            ExpansionControl(BassParameters.Control("p{i}", {q(c["label"])}, {lo}, {hi}, {d}, {divisor}, {q(unit)}, {decimals}), {q(c["key"])}, {step}, {optioncode}, {q(section)}, {str(c["key"] in basic[e["id"]]).lower()}, {str(hidden).lower()}),']
    lines += ['        ),']
lines+=['    )','    val descriptions = mapOf(']+[f'        ExpansionEffectsKind.{enum[e["id"]]} to {q(e.get("description",e.get("summary","")))},' for e in items]+['    )','}']
(out/'ExpansionEffectsCatalog.kt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
