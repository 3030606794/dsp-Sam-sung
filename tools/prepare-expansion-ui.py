from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=root/'app/src/main/java/me/timschneeberger/rootlessjamesdsp'
out=base/'expansion'
out.mkdir(exist_ok=True)
def source(name):
    return (base/'creative'/f'CreativeEffects{name}.kt').read_text(encoding='utf-8').replace('rootlessjamesdsp.creative','rootlessjamesdsp.expansion').replace('CreativeEffects','ExpansionEffects').replace('creative','expansion')
def write(name,text): (out/f'ExpansionEffects{name}.kt').write_text(text,encoding='utf-8')
write('Repository',source('Repository'))
dashboard=source('Dashboard')
a=dashboard.index(' ?: when (kind) {');b=dashboard.index('\n    private var renderedValues',a)
dashboard=dashboard[:a]+' ?: controls.first().key\n'+dashboard[b:]
write('Dashboard',dashboard)
header=source('Header')
header=header.replace('liveTitle.text=if(kind==ExpansionEffectsKind.FREEZE)"冻结频谱（合成前）· 16频带"else"原声与湿声峰值 · 最近20秒"','liveTitle.text=ExpansionEffectsTelemetry.title(kind)')
a=header.index('        val live=when(kind){');b=header.index('        readouts.setLines',a)
header=header[:a]+'        val live=sample?.let { ExpansionEffectsTelemetry.readout(it) } ?: "等待实际音频处理"\n'+header[b:]
header=header.replace('"原声 ${number(sample?.inputPeakDb)} dBFS · 湿声 ${number(sample?.wetPeakDb)} dBFS"','"输入 ${number(sample?.inputPeakDb)} dBFS · 输出 ${number(sample?.outputPeakDb)} dBFS"')
write('Header',header)
chart=source('RealtimeChart')
chart=chart.replace('private val kind: ExpansionEffectsKind','private val kind: ExpansionEffectsKind')
chart=chart.replace('kind == ExpansionEffectsKind.FREEZE','ExpansionEffectsTelemetry.isSpectrum(kind)')
chart=chart.replace('sample.activity > 0','sample.isProcessing').replace('sample.activity == 0','false')
chart=chart.replace('sample.spectrumDb','sample.spectrumDb')
chart=chart.replace('冻结频谱','实际频带读数').replace('尚未捕捉 · 点击捕捉当前声音','等待驱动实际读数')
chart=chart.replace('sample.wetPeakDb.coerceIn','sample.outputPeakDb.coerceIn')
chart=chart.replace('湿声 / 灰色输入','输出 / 灰色输入').replace('实际输入和湿声峰值','实际输入和输出峰值')
write('RealtimeChart',chart)
