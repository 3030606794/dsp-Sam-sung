from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=root/'app/src/main'
java=base/'java/me/timschneeberger/rootlessjamesdsp'
icons={
'microshift':('M3,8 C6,2 8,14 11,8 S16,2 21,8 M3,16 C6,10 8,22 11,16 S16,10 21,16','M3,6 L6,6 L6,10 L3,10 Z M8,4 L11,4 L11,12 L8,12 Z M13,6 L16,6 L16,10 L13,10 Z M18,4 L21,4 L21,12 L18,12 Z M3,15 L6,15 L6,19 L3,19 Z M8,13 L11,13 L11,21 L8,21 Z M13,15 L16,15 L16,19 L13,19 Z M18,13 L21,13 L21,21 L18,21 Z'),
'formant':('M3,19 C6,19 5,5 8,5 S9,19 12,19 C14,19 14,10 16,10 S18,19 21,19 M5,3 L12,3 M10,1 L12,3 L10,5','M3,20 L3,17 C6,17 5,4 8,4 S10,17 12,17 C14,17 14,9 16,9 S19,17 21,17 L21,20 Z M12,2 L18,2 L18,0 L23,4 L18,8 L18,6 L12,6 Z'),
'frequency_shift':('M3,18 L5,18 L5,9 L7,9 L7,18 M10,18 L12,18 L12,5 L14,5 L14,18 M17,18 L19,18 L19,8 L21,8 M3,3 L9,3 M7,1 L9,3 L7,5','M3,9 L7,9 L7,20 L3,20 Z M10,5 L14,5 L14,20 L10,20 Z M17,8 L21,8 L21,20 L17,20 Z M2,2 L8,2 L8,0 L12,4 L8,7 L8,5 L2,5 Z'),
'vocoder':('M5,5 L19,5 L19,21 L5,21 Z M9,10 L9,12 M15,10 L15,12 M9,17 L15,17 M12,5 L12,2 M3,10 L3,16 M21,10 L21,16','M4,5 L20,5 L20,22 L4,22 Z M7,9 L10,9 L10,13 L7,13 Z M14,9 L17,9 L17,13 L14,13 Z M8,17 L16,17 L16,19 L8,19 Z M11,0 L13,0 L13,5 L11,5 Z'),
'gated_reverb':('M3,7 L6,7 L8,16 L11,8 L13,16 L16,10 L18,16 L19,20 M19,3 L19,20 L22,20','M2,6 L6,6 L8,13 L10,8 L12,13 L14,9 L17,14 L18,14 L18,3 L21,3 L21,21 L18,21 L18,18 L16,18 L14,14 L12,18 L10,14 L8,18 L5,9 L2,9 Z'),
'pingpong':('M3,7 L20,7 M16,3 L20,7 L16,11 M21,17 L4,17 M8,13 L4,17 L8,21','M2,5 L16,5 L16,1 L23,7 L16,13 L16,9 L2,9 Z M22,15 L8,15 L8,11 L1,17 L8,23 L8,19 L22,19 Z'),
'auto_wah':('M3,19 L8,19 C10,19 10,3 12,3 S14,19 16,19 L21,19 M5,5 L8,5 M6,3 L8,5 L6,7 M19,5 L16,5 M18,3 L16,5 L18,7','M2,17 L7,17 C9,17 9,2 12,2 S15,17 17,17 L22,17 L22,21 L17,21 C12,21 14,7 12,7 S12,21 7,21 L2,21 Z'),
'autopan':('M3,12 C6,2 9,2 12,12 S18,22 21,12 M3,4 L3,20 M21,4 L21,20','M2,3 L5,3 L5,21 L2,21 Z M19,3 L22,3 L22,21 L19,21 Z M5,10 C7,2 10,2 13,11 S17,18 19,12 L19,17 C16,23 12,22 10,14 S7,6 5,15 Z'),
'low_phase':('M3,12 C6,2 8,2 11,12 S16,22 21,12 M3,16 C6,6 8,6 11,16 M16,3 L21,3 L21,8 M21,3 L15,9','M2,11 C5,0 9,0 13,11 S18,20 22,11 L22,16 C17,26 12,22 9,13 S5,5 2,16 Z M16,1 L23,1 L23,8 L20,8 L20,6 L16,10 L14,8 L19,4 L16,4 Z'),
'tape':('M3,5 L21,5 L21,20 L3,20 Z M7,8 A3,3 0,1 1,7,14 A3,3 0,1 1,7,8 M17,8 A3,3 0,1 1,17,14 A3,3 0,1 1,17,8 M7,14 L17,14 M8,20 L10,16 L14,16 L16,20','M2,4 L22,4 L22,21 L2,21 Z M7,7 A4,4 0,1 0,7,15 A4,4 0,1 0,7,7 M17,7 A4,4 0,1 0,17,15 A4,4 0,1 0,17,7 M9,17 L15,17 L17,21 L7,21 Z'),
'ms_eq':('M3,4 L3,20 L21,20 M5,12 C7,12 7,5 10,5 S12,12 14,12 M10,16 C13,16 13,9 16,9 S19,16 21,16 M12,3 L12,21','M2,3 L4,3 L4,20 L22,20 L22,22 L2,22 Z M5,15 C7,15 7,5 10,5 S13,15 15,15 L15,18 L5,18 Z M14,14 C16,14 16,8 19,8 S21,14 23,14 L23,18 L14,18 Z'),
'match_eq':('M3,6 L5,6 L7,3 L10,9 L13,6 L21,6 M3,18 L5,18 L7,15 L10,21 L13,18 L21,18 M16,10 L20,12 L16,14','M2,5 L5,5 L7,1 L10,8 L13,5 L22,5 L22,8 L14,8 L10,12 L7,6 L6,8 L2,8 Z M2,17 L5,17 L7,13 L10,20 L13,17 L22,17 L22,20 L14,20 L10,24 L7,18 L6,20 L2,20 Z'),
'transient':('M3,18 L6,18 L8,4 L10,15 L13,12 L16,17 L21,18 M3,21 L21,21','M2,17 L5,17 L8,1 L11,13 L13,10 L17,16 L22,17 L22,20 L16,19 L13,15 L10,19 L8,11 L7,20 L2,20 Z'),
'resonance':('M3,19 C6,19 5,4 8,4 S10,19 12,19 C14,19 14,7 16,7 S18,19 21,19 M6,10 L10,10 M14,12 L18,12 M8,1 L8,8 M16,2 L16,10','M2,18 L5,18 C6,18 5,10 8,10 S10,18 12,18 C14,18 13,12 16,12 S18,18 22,18 L22,21 L2,21 Z M6,1 L10,1 L10,6 L12,6 L8,10 L4,6 L6,6 Z M14,3 L18,3 L18,8 L20,8 L16,12 L12,8 L14,8 Z'),
'sub_bass':('M3,8 C6,2 8,14 11,8 S16,2 21,8 M3,18 C8,7 16,29 21,18 M12,11 L12,15 M10,13 L12,15 L14,13','M2,6 C5,0 9,0 12,6 S18,12 22,6 L22,11 C18,17 14,13 11,8 S5,4 2,11 Z M2,18 C8,6 16,28 22,16 L22,21 C16,30 8,10 2,23 Z'),
'loudness':('M3,9 L7,9 L12,4 L12,20 L7,15 L3,15 Z M16,9 C19,11 19,13 16,15 M18,5 C24,9 24,15 18,19','M2,8 L7,8 L13,3 L13,21 L7,16 L2,16 Z M16,7 C22,10 22,14 16,17 L16,14 C19,12 19,12 16,10 Z'),
'dereverb':('M3,5 L3,19 L7,19 M7,8 C9,5 10,20 12,17 M13,9 L15,15 M17,10 L19,14 M16,3 L22,9 M22,3 L16,9','M2,4 L5,4 L5,20 L2,20 Z M7,8 L10,8 L12,17 L9,17 Z M13,10 L16,10 L18,16 L15,16 Z M17,2 L23,8 L21,10 L15,4 Z M21,2 L23,4 L17,10 L15,8 Z'),
'declick':('M3,15 L7,15 L9,3 L11,21 L13,15 L21,15 M5,6 L19,6 M6,3 L18,19','M2,14 L6,14 L9,0 L12,20 L14,14 L22,14 L22,17 L15,17 L10,24 L8,12 L7,17 L2,17 Z M5,3 L20,18 L18,20 L3,5 Z'),
'dehum':('M3,12 C5,3 7,3 9,12 S13,21 15,12 S19,3 21,12 M4,4 L20,20','M2,11 C5,-1 8,3 10,11 S13,18 15,10 S20,0 22,10 L22,17 C19,5 18,9 17,14 S11,26 8,15 S4,7 2,18 Z M4,2 L22,20 L20,22 L2,4 Z'),
'headtrack':('M7,20 L7,14 C3,4 8,2 12,2 S21,4 17,14 L17,20 M9,20 L15,20 M2,9 L2,15 M22,9 L22,15 M8,9 L16,9 M12,6 L16,9 L12,12','M6,21 L6,15 C0,2 8,0 12,0 S24,2 18,15 L18,21 L15,21 L15,14 C20,5 16,3 12,3 S4,5 9,14 L9,21 Z M1,8 L4,8 L4,16 L1,16 Z M20,8 L23,8 L23,16 L20,16 Z'),
'vocal':('M9,3 L15,3 L15,12 A3,3 0,0 1,9,12 Z M6,10 L6,12 A6,6 0,0 0,18,12 L18,10 M12,18 L12,22 M8,22 L16,22','M9,1 L15,1 L15,12 A3,3 0,0 1,9,12 Z M5,10 L8,10 L8,12 A4,4 0,0 0,16,12 L16,10 L19,10 L19,12 A7,7 0,0 1,13,19 L13,21 L17,21 L17,24 L7,24 L7,21 L11,21 L11,19 A7,7 0,0 1,5,12 Z')}
draw=base/'res/drawable'
for name,(line,solid) in icons.items():
    for style in ['','instrument_','solid_']:
        body=(f'<path android:fillColor="@android:color/transparent" android:strokeColor="?attr/colorControlNormal" android:strokeWidth="1.5" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{line}" />' if style!='solid_' else f'<path android:fillColor="?attr/colorControlNormal" android:fillType="evenOdd" android:pathData="{solid}" />')
        if style=='instrument_': body='<path android:fillColor="@android:color/transparent" android:strokeColor="?attr/colorControlNormal" android:strokeWidth="0.65" android:pathData="M1,1 L1,23 L23,23 M1,5 L3,5 M1,11 L3,11 M1,17 L3,17 M7,21 L7,23 M13,21 L13,23 M19,21 L19,23" />'+body
        (draw/f'ic_expansion_{style}{name}.xml').write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">{body}</vector>\n',encoding='utf-8')
p=java/'ui/EffectIconStyle.kt';t=p.read_text(encoding='utf-8')
t=t.replace('        else -> null',''.join(f'        "expansion_{n}_enable" -> R.drawable.ic_expansion_{n}\n' for n in icons)+'        else -> null')
t=t.replace('    private val variants = mapOf(','    private val variants = mapOf(\n'+''.join(f'        R.drawable.ic_expansion_{n} to (R.drawable.ic_expansion_instrument_{n} to R.drawable.ic_expansion_solid_{n}),\n' for n in icons))
p.write_text(t,encoding='utf-8')
# Insert related effects into existing family cards. New families use the exact existing card style.
groups={'card_dynamic_peq':['ms_eq','match_eq'], 'card_bass':['sub_bass','low_phase'], 'card_surround':['microshift'], 'card_crossfeed':['headtrack'], 'card_shimmer':['gated_reverb','pingpong'], 'card_tube':['tape'], 'card_compressor':['transient','resonance'], 'card_fusion_tone':['loudness']}
p=base/'res/layout/fragment_dsp.xml';t=p.read_text(encoding='utf-8')
def container(n):return f'\n                <androidx.fragment.app.FragmentContainerView android:id="@+id/card_expansion_{n}" android:layout_width="match_parent" android:layout_height="wrap_content" />'
for anchor,names in groups.items():
    start=t.index(f'android:id="@+id/{anchor}"');end=t.index('/>',start)+2
    t=t[:end]+''.join(container(n) for n in names)+t[end:]
for family,names in [('voice',['formant','frequency_shift','vocoder','vocal']),('modulation',['auto_wah','autopan']),('repair',['dereverb','declick','dehum'])]:
    card=f'''\n        <com.google.android.material.card.MaterialCardView android:id="@+id/card_expansion_{family}" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginHorizontal="1dp" android:layout_marginTop="6dp">
            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                {''.join(container(n) for n in names)}
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
'''
    anchor=t.index('\n    </LinearLayout>\n    </androidx.core.widget.NestedScrollView>')
    t=t[:anchor]+card+t[anchor:]
p.write_text(t,encoding='utf-8')
p=java/'fragment/DspFragment.kt';t=p.read_text(encoding='utf-8')
t=t.replace('import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind','import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsHomeFragment')
anchor='            .addIfMissing(R.id.card_dynamic_peq, DynamicPeqHomeFragment())'
t=t.replace(anchor,anchor+'\n'+''.join(f'            .addIfMissing(R.id.card_expansion_{n}, ExpansionEffectsHomeFragment.newInstance(ExpansionEffectsKind.{n.upper()}))\n' for n in icons).rstrip())
p.write_text(t,encoding='utf-8')
