"""Generate the shared, ordered width2 parameter contract (no build dependencies)."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
ENTRIES = []
def number(key, title, low, high, default, scale=1, unit='', decimals=0, group=0, band=-1):
    ENTRIES.append(dict(key='width2_'+key, title=title, low=low, high=high, default=default,
                        scale=scale, unit=unit, decimals=decimals, group=group, band=band, kind='number'))
def switch(key, title, group, band=-1):
    number(key,title,0,1,0,group=group,band=band); ENTRIES[-1]['kind']='switch'
def choice(key,title,items,default,group):
    number(key,title,0,len(items)-1,default,group=group); ENTRIES[-1].update(kind='choice',items=items)

for band, name in enumerate(['低频','中频','高频','全频']):
    prefix=f'dyn{band}_'
    switch(prefix+'on',name+'动态宽度',1,band)
    number(prefix+'threshold',name+' Side/Mid 触发阈值',-300,60,-60,10,'dB',1,1,band)
    number(prefix+'ratio',name+'动态压缩比',10,100,20,10,':1',1,1,band)
    number(prefix+'knee',name+'软拐点宽度',0,120,30,10,'dB',1,1,band)
    number(prefix+'attack',name+'动态启动',2,200,20,unit='ms',group=1,band=band)
    number(prefix+'release',name+'动态恢复',50,2000,300,unit='ms',group=1,band=band)
    number(prefix+'max',name+'最大动态回收',0,1000,500,10,'%',1,1,band)
switch('transient_on','启用瞬态扩宽抑制',2)
choice('transient_bands','瞬态作用频段',['不选择频段','低频','中频','低＋中频','高频','低＋高频','中＋高频','低＋中＋高频'],7,2)
number('transient_threshold','瞬态包络差阈值',0,180,60,10,'dB',1,2)
number('transient_max','最大瞬态回收',0,1000,600,10,'%',1,2)
number('transient_attack','瞬态启动',1,20,2,unit='ms',group=2)
number('transient_hold','瞬态保持',0,100,20,unit='ms',group=2)
number('transient_release','瞬态恢复',20,1000,100,unit='ms',group=2)
switch('guard_on','启用高级相关度保护',3)
choice('guard_mode','高级保护联动方式',['全频联动','分段独立'],0,3)
number('guard_window','保护检测窗口',10,500,100,unit='ms',group=3)
choice('guard_point','保护检测位置',['湿声候选','干湿混合候选'],0,3)
number('guard_floor','保护有效信号门限',-900,-300,-600,10,'dBFS',1,3)
for name, label in [('low','低频'),('mid','中频'),('high','高频')]:
    number('guard_'+name,label+'相关度阈值',-20,50,0,100,'',2,3)
for band, freq in enumerate([400,6000]):
    switch(f'eq{band}_on',f'启用扩宽增量均衡 {band+1}',4)
    number(f'eq{band}_hz',f'增量均衡 {band+1} 频率',80,16000,freq,unit='Hz',group=4)
    number(f'eq{band}_gain',f'增量均衡 {band+1} 增益',-60,60,0,10,'dB',1,4)
    number(f'eq{band}_q',f'增量均衡 {band+1} Q 值',30,400,100,100,'',2,4)
switch('auto_on','启用电平匹配高级响应',5)
number('auto_attack','匹配衰减响应',10,500,80,unit='ms',group=5)
number('auto_release','匹配增益恢复',100,3000,800,unit='ms',group=5)
number('auto_deadband','匹配变化死区',0,20,3,10,'dB',1,5)
number('auto_floor','匹配静音冻结门限',-900,-300,-600,10,'dBFS',1,5)
choice('auto_direction','匹配补偿方式',['允许双向补偿','仅衰减'],1,5)
number('history','相关度历史长度',5,30,10,unit='秒',group=6)
switch('meter_on','启用分段监测与声场图',6)
assert len(ENTRIES)==59

def generate():
    target=ROOT/'app/src/main/java/me/timschneeberger/rootlessjamesdsp/fusion/WidthAdvancedParameters.kt'
    rows=[]
    for e in ENTRIES:
        c='BassParameters.Control('+','.join([json.dumps(e['key']),json.dumps(e['title'],ensure_ascii=False),str(e['low']),str(e['high']),str(e['default']),str(e['scale']),json.dumps(e['unit']),str(e['decimals'])])+')'
        items='listOf('+','.join(json.dumps(s,ensure_ascii=False) for s in e.get('items',[]))+')'
        rows.append(f'        Entry({c}, "{e["kind"]}", {e["group"]}, {e["band"]}, {items})')
    target.write_text('''package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Generated ordered contract; ui23 keys and Float legacy width remain untouched. */
object WidthAdvancedParameters {
    const val CAPABILITY=32768
    const val PACKET=1412
    const val AUDITION=1413
    const val RUNTIME=131216
    const val RUNTIME_WORDS=176
    data class Entry(val control: BassParameters.Control,val kind: String,val group: Int,val band: Int,val items: List<String>)
    val entries=listOf(
'''+',\n'.join(rows)+'''
    )
    val all=entries.filter {it.kind=="number"}.map {it.control}
    val keys=entries.map {it.control.key}.toSet()
    fun value(v: Map<String,*>,e: Entry): Int {
        if(e.kind=="switch")return if(v[e.control.key] as? Boolean==true)1 else 0
        val x=(v[e.control.key] as? Number)?.toDouble()
        return x?.takeIf {it.isFinite() && it%1.0==0.0 && it>=e.control.min && it<=e.control.max}?.toInt() ?: e.control.default
    }
    fun packet(v: Map<String,*>)=intArrayOf(1)+entries.map {value(v,it)}.toIntArray()
    fun normalize(prefs: SharedPreferences) {
        val v=prefs.all;val edit=prefs.edit();var dirty=false
        entries.filter {v.containsKey(it.control.key)}.forEach {e->
            val x=value(v,e);val key=e.control.key
            if(e.kind=="switch"){if(v[key] !is Boolean){edit.putBoolean(key,x==1);dirty=true}}
            else if(v[key]!=x){edit.putInt(key,x);dirty=true}
        }
        if(dirty)edit.apply()
    }
}
''',encoding='utf-8')
    dest=ROOT/'app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/widthAdvancedContract.h'
    values=lambda k: ','.join(str(e[k]) for e in ENTRIES)
    dest.write_text('''/* Generated by scripts/width2-schema.py. */
#ifndef WIDTH_ADVANCED_CONTRACT_H
#define WIDTH_ADVANCED_CONTRACT_H
#define WIDTH2_WORDS 60
#define WIDTH2_RUNTIME_WORDS 176
static const int32_t width2Defaults[WIDTH2_WORDS]={1,'''+values('default')+'''};
static const int32_t width2Low[WIDTH2_WORDS]={1,'''+values('low')+'''};
static const int32_t width2High[WIDTH2_WORDS]={1,'''+values('high')+'''};
#endif
''',encoding='utf-8')
    (ROOT/'docs/WIDTH2-SCHEMA.json').write_text(json.dumps(ENTRIES,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':generate()
