package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import java.util.Locale
import kotlin.math.*

/** Feature-specific diagrams for the second expansion group. Never manufactures live audio. */
internal object ExpansionVisualsB {
    private val p = ExpansionEffectsParameters
    private const val MODEL = "参数示意，不是当前音乐的测量；只用于解释控制关系。"
    private fun number(x: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", x)
    private fun curve(label: String, low: Double, high: Double, color: Int = 0, fn: (Double) -> Double) =
        LiveprogCurve(label, List(97) { val x = low + (high-low)*it/96; LiveprogPoint(x, fn(x)) }, color)
    private fun frequency(label: String, color: Int = 0, count: Int = 129, fn: (Double) -> Double) =
        LiveprogCurve(label, List(count) { val x = 20.0*1000.0.pow(it.toDouble()/(count-1)); LiveprogPoint(x, fn(x)) }, color)
    private fun plot(title: String, subtitle: String, x: String, y: String, xmin: Double, xmax: Double,
                     ymin: Double, ymax: Double, curves: List<LiveprogCurve>, log: Boolean = false,
                     notes: List<String> = listOf(MODEL), marks: List<LiveprogMark> = emptyList(),
                     regions: List<LiveprogRegion> = emptyList()) = EffectVisual(title, subtitle,
        plot = LiveprogStaticPlot(title, subtitle, xLabel=x, yLabel=y, xMinimum=xmin, xMaximum=xmax,
            yMinimum=ymin, yMaximum=ymax, logarithmicX=log, curves=curves, marks=marks, regions=regions, notes=notes), notes=notes)
    private fun scene(title: String, subtitle: String, elements: List<FigureElement>, notes: List<String> = listOf(MODEL)) =
        EffectVisual(title, subtitle, scene=EffectScene(elements), notes=notes)
    private fun path(color: Int = 0, fn: (Double) -> FigurePoint) = FigurePolyline(List(97) { fn(it/96.0) }, color)
    private fun gain(db: Double) = 10.0.pow(db/20)
    private fun selected(kind: ExpansionEffectsKind, focus: String?) = p.metadata(kind).firstOrNull { it.control.key==focus }?.logicalKey.orEmpty()

    fun static(kind: ExpansionEffectsKind, values: Map<String, *>, focusKey: String?): EffectVisualPair {
        fun v(key: String) = p.raw(kind, values, key)
        val focus=selected(kind,focusKey)
        val pair = when(kind) {
            ExpansionEffectsKind.MS_EQ -> {
                val selectedSide=if(focus.startsWith("side")) "side" else "mid"
                val band=focus.split('_').getOrNull(1)?.toIntOrNull()?.coerceIn(0,7) ?: 0
                val prefix="${selectedSide}_${band}_"
                val center=v(prefix+"frequency")
                val mid=msResponse(values,"mid",center); val side=msResponse(values,"side",center)
                val professional=if(focus=="mix") plot("M/S 与原声重组", "输出增益在处理支路内", "处理比例%","幅度权重",0.0,100.0,0.0,max(1.0,gain(v("output"))),
                    listOf(curve("原声",0.0,100.0){1-it/100},curve("M/S支路",0.0,100.0,2){gain(v("output"))*it/100}),
                    marks=listOf(LiveprogMark(v("mix")*100,gain(v("output"))*v("mix"),"当前")),
                    notes=listOf(MODEL,"曲线是原声与处理后的M/S支路重组权重，不把两路幅度权重当作能量比例；频率响应和相位由M/S频段决定。"))
                else plot("中／侧频响", "${if(selectedSide=="mid")"中"else"侧"}第${band+1}段 · ${number(center,0)} Hz", "Hz","dB",20.0,20000.0,-36.0,36.0,
                    listOf(frequency("中 M"){msResponse(values,"mid",it)},frequency("侧 S",2){msResponse(values,"side",it)}),true,
                    notes=listOf(MODEL,"48 kHz 参考，曲线为独立处理支路（含输出增益），不包含干湿混合的相位叠加。中声道为 (L+R)/2，侧声道为 (L−R)/2。"),
                    marks=listOf(LiveprogMark(center,if(selectedSide=="mid")mid else side,"当前段",if(selectedSide=="mid")0 else 2)))
                val width=(.18+.14*(side-mid)/18).coerceIn(.06,.40)
                val centerRadius=(.08+.05*mid/18).coerceIn(.035,.16)
                (if(focus=="mix")professional else fitDbAxis(professional)) to scene("居中与两侧", "${number(center,0)} Hz 的支路关系", listOf(
                    FigureArc(.5,.67,.40,.46,180.0,180.0,alpha=.4),
                    FigureCircle(.5,.42,centerRadius,label="M",colorIndex=0,filled=true,alpha=.25),
                    FigureCircle(.5-width,.42,.06,label="S",colorIndex=2,filled=true,alpha=.25),
                    FigureCircle(.5+width,.42,.06,label="S",colorIndex=2,filled=true,alpha=.25),
                    FigureLine(.5,.72,.5,.50,arrow=true), FigureLine(.5,.72,.5-width,.50,colorIndex=2,arrow=true),
                    FigureLine(.5,.72,.5+width,.50,colorIndex=2,arrow=true),
                    FigureLabel("中 ${number(mid)} dB",.5,.11),FigureLabel("侧 ${number(side)} dB",.5,.85,colorIndex=2)),
                    listOf(MODEL,"圆大小、左右间距只示意此频率的中侧相对增益；不是实测声源位置，单声道输入不会凭空产生侧声道。"))
            }
            ExpansionEffectsKind.MATCH_EQ -> {
                val profiles=storedProfiles(values); val correction=matchCorrection(values,profiles)
                val professional=if(focus=="capture_seconds" || focus.endsWith("capture_epoch"))
                    plot("有效声音采集", "静音暂停累计", "有效秒","完成%",0.0,30.0,0.0,100.0,
                        listOf(curve("学习进度",0.0,30.0){100*(it/v("capture_seconds").coerceAtLeast(1.0)).coerceIn(0.0,1.0)}))
                else plot("学习频谱与修正", "32 段 · 设置预览", "Hz","dB",20.0,20000.0,-12.0,12.0,
                    if(correction==null) emptyList() else listOf(indexedFrequency("预期修正",correction,0,midpoints=true)),true,
                    notes=listOf(MODEL,"由已保存的两份实际学习功率去整体电平差，按最大修正裁剪，再按平滑及匹配强度计算。作用范围以外归零。尚未学习时不制造频谱。"),
                    regions=listOf(LiveprogRegion(v("low"),v("high"),"作用范围")))
                professional to matchIllustration(profiles,correction,v("strength"),v("mix"))
            }
            ExpansionEffectsKind.TRANSIENT -> {
                val band=when {focus.startsWith("low_")->"low";focus.startsWith("high_")->"high";else->"mid"}
                val name=mapOf("low" to "低频","mid" to "中频","high" to "高频").getValue(band)
                val end=max(v("release")*3,v("attack")*6)
                val professional=if(focus.endsWith("split")) plot("互补分频响应", "48 kHz · 三带相加还原", "Hz","dB",20.0,20000.0,-48.0,3.0,
                    listOf(frequency("低"){splitResponse(it,v("low_split"),v("high_split"),0)},frequency("中",1){splitResponse(it,v("low_split"),v("high_split"),1)},frequency("高",2){splitResponse(it,v("low_split"),v("high_split"),2)}),true,
                    marks=listOf(LiveprogMark(v("low_split"),-3.0,"低中"),LiveprogMark(v("high_split"),-3.0,"中高",2)))
                else if(focus=="attack"||focus=="release") plot("包络跟随速度", "阶跃输入 · 时间常数", "ms","跟随%",0.0,end,0.0,100.0,
                    listOf(curve("快包络",0.0,end){100*(1-exp(-it/v("attack")))},curve("慢包络",0.0,end,2){100*(1-exp(-it/v("release")))}))
                else plot("$name 起音与延音", "检测权重与增益", "起音权重%","dB",0.0,100.0,-18.0,18.0,
                    listOf(curve("目标增益",0.0,100.0){effectiveGain(v("${band}_sustain")+(v("${band}_attack")-v("${band}_sustain"))*it/100,v("mix"))} ),
                    notes=listOf(MODEL,"横轴是快慢包络差形成的起音权重，而不是歌曲播放时间；静音门控与2 ms增益平滑另行作用。"))
                val attack=gain(effectiveGain(v("${band}_attack"),v("mix"))).coerceAtMost(4.0)
                val sustain=gain(effectiveGain(v("${band}_sustain"),v("mix"))).coerceAtMost(4.0)
                professional to scene("$name 鼓点轮廓", "起音 → 延音 · 示意",listOf(
                    FigureLine(.08,.79,.93,.79,arrow=true,alpha=.5),
                    path(2){t->FigurePoint(.08+.85*t,.79-.16*exp(-t*5)*sin(PI*t*12))},
                    path(0){t->val a=if(t<.14)attack else sustain;FigurePoint(.08+.85*t,(.79-.16*a*exp(-t*5)*sin(PI*t*12)).coerceIn(.16,.91))},
                    FigureLabel("起音 ${number(v("${band}_attack"))} dB",.36,.10,width=.66),
                    FigureLabel("延音 ${number(v("${band}_sustain"))} dB",.60,.94,width=.72,colorIndex=2)),
                    listOf(MODEL,"固定衰减鼓点用于解释起音和尾段的相对塑形，不是实际检测到的鼓点，也不宣称精确输出波形。"))
            }
            ExpansionEffectsKind.RESONANCE -> {
                val professional=when(focus) {
                    "attack","release" -> envelopePlot("抑制响应与恢复",v("attack"),v("release"),v("depth"))
                    "q","low","high" -> plot("单峰抑制形状", "1 kHz 固定参考峰", "Hz","dB",20.0,20000.0,-18.0,1.0,
                        listOf(frequency("最大压低"){f->if(f in v("low")..v("high")) -v("depth")/(1+((f-1000)/(500/v("q"))).pow(2)) else 0.0}),true,
                        regions=listOf(LiveprogRegion(v("low"),v("high"),"检测范围")))
                    else -> plot("突出量与抑制量", "相对邻频 · 上限约束", "突出dB","压低dB",0.0,36.0,0.0,18.0,
                        listOf(curve("混合后压低",0.0,36.0){-effectiveGain(-(it-v("threshold")).coerceIn(0.0,v("depth")),v("mix"))}))
                }
                val count=v("peaks").toInt().coerceIn(1,8);val reduction=v("depth")/18
                professional to scene("只压突出的峰", "最多 $count 峰 · 邻频保留",buildList {
                    add(FigureLine(.08,.78,.94,.78,alpha=.4))
                    val centers=listOf(.21,.43,.65,.83)
                    add(path(2){t->val peak=centers.sumOf { exp(-((t-it)/.025).pow(2)) };FigurePoint(.08+.85*t,.72-.44*peak)})
                    add(path(0){t->val peak=centers.mapIndexed{index,c->exp(-((t-c)/.025).pow(2))*(if(index<count)1-.72*reduction else 1.0)}.sum();FigurePoint(.08+.85*t,.72-.44*peak)})
                    centers.take(count).forEach { x->add(FigureLine(.08+.85*x,.22,.08+.85*x,.42,colorIndex=0,arrow=true)) }
                    add(FigureLabel("突出阈值 ${number(v("threshold"))} dB",.5,.07))
                    add(FigureLabel("低频 → 高频",.5,.92))
                },listOf(MODEL,"四个固定示意峰用于展示选择性压低；实际峰数、频率和压低量由下图驱动测量决定。"))
            }
            ExpansionEffectsKind.SUB_BASS -> {
                val professional=when(focus) {
                    "gate","level","mix" -> plot("起振与合成电平", "门限以上 12 dB 渐开", "检测dBFS","合成权重dB",-90.0,0.0,-60.0,12.0,
                        listOf(curve("合成权重",-90.0,0.0){(20*log10((((it-v("gate"))/12).coerceIn(0.0,1.0)*v("mix")).coerceAtLeast(1e-6))+v("level")).coerceAtLeast(-60.0)}),
                        notes=listOf(MODEL,"图示门控、合成电平与干湿比例共同形成的幅度权重，相对检测包络乘√2后的峰值；不包含输出低通响应及跟随平滑。"))
                    "tracking" -> envelopePlot("基音跟随速度",v("tracking"),v("tracking"),100.0,"跟随%")
                    else -> plot("低一个八度", "输入基频 ÷ 2", "输入Hz","合成Hz",0.0,400.0,0.0,200.0,
                        listOf(curve("次谐波",v("input_low"),v("input_high")){it/2}),regions=listOf(LiveprogRegion(v("input_low"),v("input_high"),"检测范围")))
                }
                val f=(v("input_low")+v("input_high"))/2
                professional to scene("周期加倍", "${number(f,0)} → ${number(f/2,0)} Hz 参考",listOf(
                    path(2){FigurePoint(.08+.84*it,.31-.11*sin(it*PI*8))},
                    path(0){FigurePoint(.08+.84*it,.67-.13*sin(it*PI*4))},
                    FigureLabel("原基音 · 短周期",.5,.08,colorIndex=2),
                    FigureLabel("次谐波 · 长周期",.5,.92),
                    FigureLine(.10,.48,.32,.48,colorIndex=2,arrow=true),FigureLine(.10,.83,.52,.83,arrow=true)),
                    listOf(MODEL,"仅显示选定检测范围中点的固定正弦参考；不是实际歌曲。复调素材不保证稳定基音。"))
            }
            ExpansionEffectsKind.LOUDNESS -> {
                val ref=v("reference_volume").coerceAtLeast(.1)
                fun amount(volume:Double)=(1-volume/ref).coerceIn(0.0,1.0).pow(v("curve"))
                val professional=if(focus.endsWith("frequency")||focus=="mix") plot("补偿架式频响", "静音量端最大补偿 · 48 kHz", "Hz","dB",20.0,20000.0,0.0,27.0,
                    listOf(frequency("混合后补偿"){
                        val low=biquadComplex(1,v("bass_frequency"),v("bass_gain"),.707,it)
                        val high=biquadComplex(2,v("treble_frequency"),v("treble_gain"),.707,it)
                        val re=1-v("mix")+v("mix")*(low.first*high.first-low.second*high.second)
                        val im=v("mix")*(low.first*high.second+low.second*high.first)
                        10*log10((re*re+im*im).coerceAtLeast(1e-20))
                    }),true)
                else plot("音量与补偿", "到归零音量恢复平直", "媒体音量%","dB",0.0,100.0,0.0,18.0,
                    listOf(curve("低频",0.0,100.0){v("bass_gain")*amount(it/100)},curve("高频",0.0,100.0,2){v("treble_gain")*amount(it/100)}),
                    marks=listOf(LiveprogMark(ref*100,0.0,"归零")))
                professional to scene("小音量补两端", "厚度与明亮 · 示意",buildList {
                    add(FigureCircle(.5,.46,.26,.27))
                    add(FigureArc(.5,.46,.32,.33,135.0,270*ref,2,width=3.0))
                    val angle=(135+270*ref)*PI/180
                    add(FigureLine(.5,.46,.5+.21*cos(angle),.46+.22*sin(angle),arrow=true))
                    add(FigureLabel("归零 ${number(ref*100,0)}%",.5,.65,width=.7))
                    add(FigureCircle(.14,.78,.035+v("bass_gain")/180,.07,label="低",filled=true,alpha=.23))
                    add(FigureCircle(.86,.78,.035+v("treble_gain")/90,.07,label="高",colorIndex=2,filled=true,alpha=.23))
                    add(FigureLabel("音量升高 → 补偿减小",.5,.08))
                    add(FigureLabel("媒体档位不是耳边声压",.5,.95))
                })
            }
            ExpansionEffectsKind.DEREVERB -> {
                val professional=if(focus=="decay"||focus=="delay") plot("晚期能量衰减模型", "RT60 ${number(v("decay"),0)} ms", "ms","相对dB",0.0,v("delay")+v("decay"),-60.0,0.0,
                    listOf(curve("尾音估计",0.0,v("delay")+v("decay")){if(it<v("delay"))0.0 else -60*(it-v("delay"))/v("decay")}),
                    regions=listOf(LiveprogRegion(0.0,v("delay"),"早期")),notes=listOf(MODEL,"声明的混响时长是功率衰减60 dB的估计，不是测得的房间混响。"))
                else plot("晚期占比与衰减", "起音保护降低抑制", "估计晚期/当前%","dB",0.0,200.0,-30.0,0.0,
                    listOf(curve("稳定尾音",0.0,200.0){effectiveGain(dereverbGain(it/100,v("strength"),v("floor"),0.0),v("mix"))},
                        curve("最大起音保护",0.0,200.0,2){effectiveGain(dereverbGain(it/100,v("strength"),v("floor"),v("protect")),v("mix"))}))
                professional to scene("保留直达 · 减少反射", "估计晚期尾音",listOf(
                    FigureBox(.09,.15,.91,.78,filled=false),FigureCircle(.23,.55,.05,label="声"),FigureCircle(.76,.55,.05,label="耳"),
                    FigureLine(.30,.55,.69,.55,arrow=true,width=2.4),
                    FigureLine(.24,.48,.46,.17,colorIndex=2,dashed=true,alpha=1-v("strength")*.75),
                    FigureLine(.46,.17,.73,.48,colorIndex=2,dashed=true,alpha=1-v("strength")*.75,arrow=true),
                    FigureLine(.24,.61,.48,.76,colorIndex=2,dashed=true,alpha=1-v("strength")*.75),
                    FigureLine(.48,.76,.73,.61,colorIndex=2,dashed=true,alpha=1-v("strength")*.75,arrow=true),
                    FigureLabel("晚期起点 ${number(v("delay"),0)} ms",.5,.92)),
                    listOf(MODEL,"房间反射只是晚期尾音的比喻；该算法从录音频谱估计尾音，不测量真实房间几何，也不是精确还原干声。"))
            }
            ExpansionEffectsKind.DECLICK -> {
                val threshold=v("threshold")*(1+.5*v("protect"))
                val professional=if(focus=="width") plot("异常宽度与修复窗口", "窗口 ${number(v("width"))} ms", "宽度ms","可处理候选",0.0,3.0,0.0,1.1,
                    listOf(curve("宽度约束",0.0,3.0){if(it<=v("width"))1.0 else 0.0}),notes=listOf(MODEL,"宽度只是修复条件之一；两侧可靠样本、边界和音乐瞬态保护也必须满足。"))
                else if(focus=="crackle"||focus=="mix") plot("细碎噼啪的插值权重", "仍需满足邻点与保护条件", "偏离/MAD","修正权重%",0.0,30.0,0.0,100.0,
                    listOf(curve("细碎修正",0.0,30.0){if(it>2.5+3*v("protect")&&it<=threshold)v("crackle")*v("mix")*100 else 0.0}),
                    notes=listOf(MODEL,"超过爆点门限时进入另一条检测分支，不沿用细碎噼啪修正。这里假定邻点条件通过，仅展示插值系数。"))
                else plot("稳健异常检测门限", "MAD × ${number(threshold)}", "偏离/MAD","异常候选",0.0,30.0,0.0,1.1,
                    listOf(curve("爆点门限",0.0,30.0){if(it>threshold)1.0 else 0.0}),
                    marks=listOf(LiveprogMark(2.5+3*v("protect"),.35,"细碎检测",2)))
                professional to scene("用邻点跨过爆点", "局部插值 · 固定样例",listOf(
                    FigurePolyline(listOf(FigurePoint(.08,.67),FigurePoint(.3,.58),FigurePoint(.46,.56),FigurePoint(.50,.17),FigurePoint(.54,.54),FigurePoint(.72,.51),FigurePoint(.93,.56)),2),
                    FigureLine(.36,.575,.65,.52,width=2.6),FigureCircle(.36,.575,.025,filled=true),FigureCircle(.65,.52,.025,filled=true),
                    FigureBox(.46,.12,.54,.72,filled=false,colorIndex=2),
                    FigureLine(.51,.24,.51,.47,arrow=true),FigureLabel("邻点",.25,.82,width=.35),FigureLabel("邻点",.76,.82,width=.35),
                    FigureLabel("最长 ${number(v("width"))} ms",.5,.96)),listOf(MODEL,"橙线为固定异常样例，紫线为邻点插值示意。原生遥测只报告修复数量和幅度，不能据此捏造实际爆点波形。"))
            }
            ExpansionEffectsKind.DEHUM -> {
                val professional=if(focus=="adaptation") envelopePlot("抵消估计收敛",v("adaptation"),v("adaptation"),100.0,"收敛%")
                else if(focus=="depth"||focus=="mix"||focus=="max_cancel") plot("抵消强度的剩余干扰", "已锁定同相正弦参考", "消除强度%","残留dB",0.0,100.0,-24.0,0.0,
                    listOf(curve("理想残留",0.0,100.0){20*log10((1-it/100*v("mix")*(1-gain(-v("max_cancel")))).coerceAtLeast(1e-6))}),
                    marks=listOf(LiveprogMark(v("depth")*100,20*log10((1-v("depth")*v("mix")*(1-gain(-v("max_cancel")))).coerceAtLeast(1e-6)),"当前")),
                    notes=listOf(MODEL,"这是已准确估计单个纯嗡声的理想上限；真实输出受估计和音乐能量约束，不把理想残留当实测结果。"))
                else plot("基频与谐波目标", "${number(v("base"),0)} Hz · ${v("harmonics").toInt()} 次", "Hz","候选",0.0,750.0,0.0,1.0,emptyList(),
                    marks=(1..v("harmonics").toInt().coerceIn(1,12)).map{LiveprogMark(v("base")*it,.7,if(it==1)"基频"else"",if(it==1)0 else 2)},
                    regions=(1..v("harmonics").toInt().coerceIn(1,12)).map{LiveprogRegion((v("base")-v("tracking"))*it,(v("base")+v("tracking"))*it)},
                    notes=listOf(MODEL,"色带为每次谐波随基频跟踪而移动的范围；实际估计使用21个基频候选，目标不是整个低频范围。"))
                val depth=v("depth")*v("mix")*(1-gain(-v("max_cancel")))
                professional to scene("估计反相抵消", "保留范围外的音乐",listOf(
                    path(2){FigurePoint(.08+.84*it,.28-.10*sin(6*PI*it))},
                    path(0){FigurePoint(.08+.84*it,.53+.10*depth*sin(6*PI*it))},
                    path(1){FigurePoint(.08+.84*it,.80-.10*(1-depth)*sin(6*PI*it))},
                    FigureLabel("持续嗡声",.5,.08,colorIndex=2),FigureLabel("反向估计",.5,.40),FigureLabel("相加后残留",.5,.95)),
                    listOf(MODEL,"固定正弦仅说明反相抵消；实际幅度受估计误差、自适应时间和最大消除限制，不保证完全归零。"))
            }
            ExpansionEffectsKind.HEADTRACK -> {
                val professional=when(focus) {
                    "smoothing" -> envelopePlot("姿态平滑响应",v("smoothing"),v("smoothing"),90.0,"°")
                    "mix" -> plot("姿态失效自动退回", "有效姿态最多保留 1 秒", "数据年龄ms","处理权重%",0.0,1100.0,0.0,100.0,
                        listOf(curve("有效处理目标",0.0,1100.0){if(it<1000)100*v("mix")else 0.0}),
                        notes=listOf(MODEL,"前提是效果启用且传感器报告连接。姿态超过1秒时处理目标归零，实际启停再经过20 ms淡化；没有设备时始终保持原声。"))
                    "room","distance" -> plot("反射时间与权重", "距离改变反射延迟", "ms","相对幅度",0.0,65.0,0.0,1.0,emptyList(),
                        marks=listOf(LiveprogMark(0.0,1.0,"直达"),LiveprogMark(18+8*v("distance"),v("room")*.35,"反射",2)),
                        notes=listOf(MODEL,"对应实现的简化交叉反射：18+8×距离毫秒，反射幅度0.35×房间参数；不是空气传播距离模型。"))
                    else -> plot("水平转头与耳间差", "水平姿态参考 · 远耳模型", "转头°","延迟ms",-180.0,180.0,.15,.80,
                        listOf(curve("左音箱→左耳",-180.0,180.0){.15+.65*max(0.0,sin((-v("angle")-it)*PI/180))},
                            curve("右音箱→右耳",-180.0,180.0,2){.15+.65*max(0.0,-sin((v("angle")-it)*PI/180))}),
                        notes=listOf(MODEL,"水平、零俯仰/侧倾条件的耳间时差模型；头影滤波与其他耳朵路径未在这两条曲线中绘出。"))
                }
                professional to headScene(v("angle"),v("distance"),0.0,v("room"),false)
            }
            ExpansionEffectsKind.VOCAL -> {
                val vocalGain=effectiveGain(v("vocal_gain")+v("output"),v("mix"))
                val backingGain=effectiveGain(v("accompaniment_gain")+v("output"),v("mix"))
                val minimum=floor(min(-84.0,-72+min(vocalGain,backingGain))/12)*12
                val maximum=ceil(max(24.0,max(vocalGain,backingGain))/12)*12
                val professional=plot("两声部增益传输", "混合与输出共同作用", "声部输入dBFS","输出dBFS",-72.0,0.0,minimum,maximum,
                    listOf(curve("人声",-72.0,0.0){it+vocalGain},curve("伴奏",-72.0,0.0,2){it+backingGain}),
                    notes=listOf(MODEL,"稳定处理时每个分离声部的有效系数为 (1−mix)+mix×声部增益×输出增益；输出增益只在处理支路内。假定声部与时间对齐原声完全相加，模型残留与切换淡化不在图内。"))
                professional to vocalScene(v("vocal_gain"),v("accompaniment_gain"),v("mix"),v("output"),null)
            }
            else -> error("Not a group B visual: $kind")
        }
        val inactive=values[kind.enableKey]!=true
        fun present(a:EffectVisual)=a.copy(inactive=inactive,plot=a.plot?.copy(inactive=inactive))
        return EffectVisualPair(present(pair.first),present(pair.second))
    }

    fun live(kind: ExpansionEffectsKind, values: Map<String,*>, focusKey: String?, sample: ExpansionEffectsRuntime,
             history: List<EffectVisualFrame>): EffectVisual {
        fun v(key:String)=p.raw(kind,values,key)
        fun m(index:Int)=sample.meter(index).toDouble()
        val focus=selected(kind,focusKey)
        val rate="${sample.sampleRate} Hz · 驱动实值"
        return when(kind) {
            ExpansionEffectsKind.MS_EQ -> plot("驱动中／侧目标频响",rate,"Hz","dB",20.0,min(20000.0,(sample.sampleRate*.49f).toDouble()),-36.0,36.0,
                listOf(nativeResponseCurve("中 M",sample.meters.drop(20).take(48),0,sample.sampleRate),nativeResponseCurve("侧 S",sample.meters.drop(68).take(48),2,sample.sampleRate)),true,
                notes=listOf("曲线来自原生驱动目标滤波器系数计算的48点频响。音频系数另经过10 ms平滑；这是含输出增益的湿声支路目标，未含干湿混合，不是逐采样实际响应、输入频谱或左右声源位置。"))
                .let{fitDbAxis(it)}
            ExpansionEffectsKind.MATCH_EQ -> matchLive(sample,values)
            ExpansionEffectsKind.TRANSIENT -> historyPlot("实际三频带塑形", "低 / 中 / 高 · 最近20秒",history,-18.0,18.0,"增益dB",listOf("低" to 4,"中" to 5,"高" to 6),
                listOf("实际快慢包络驱动的三频带增益，正值增强、负值减弱；不是原始输入输出电平。"))
            ExpansionEffectsKind.RESONANCE -> {
                if(focus=="attack"||focus=="release") historyPlot("实际压低的响应与恢复","峰值抑制 · 最近20秒",history,0.0,18.0,"压低dB",listOf("最大压低" to 5),listOf("实际48点以外的全FFT峰值压低读数。"))
                else fftGainPlot("实际共振峰与抑制", "${m(4).toInt()} 峰 · 最大 ${number(m(5))} dB", "频带衰减",sample.meters.drop(68).take(48),sample.sampleRate,-18.0,1.0,
                    notes=listOf("压低量来自原生1024点FFT抑制增益，重复落在同一FFT频点的读数合并到真实频率。48 kHz时最低发布频点为46.875 Hz，不当作20 Hz实测。标记是实际跟踪峰频率及选中压低目标，最多显示7个标记，总峰数可为8。"),
                    marks=(0..6).mapNotNull{val hz=m(6+2*it);if(hz>0)LiveprogMark(hz,-m(7+2*it),if(it==0)"峰"else"",2)else null})
            }
            ExpansionEffectsKind.SUB_BASS -> {
                if(focus=="gate"||focus=="level"||focus=="mix") historyPlot("实际起振与次低音", "合成包络 / 合成采样幅度",history,-100.0,12.0,"dBFS",listOf("合成包络" to 5,"合成采样" to 6),listOf("合成包络已包含起振门、√2峰值换算及输出包络平滑，尚未乘合成电平增益，不是原始检测包络。合成采样幅度来自当前最后一个次谐波输出样本的绝对值，不是该时间窗的峰值。"))
                else historyPlot("实际次谐波频率", "正在生成的低八度 · 最近20秒",history,0.0,240.0,"Hz",listOf("生成频率" to 4),listOf("原生遥测直接报告生成振荡器频率；不把它误标为检测基音，静音期间保留频率不等于仍有声音。"))
            }
            ExpansionEffectsKind.LOUDNESS -> plot("当前音量的补偿目标", "媒体 ${number(m(4)*100,0)}% · 低 ${number(m(5))} / 高 ${number(m(6))} dB", "Hz","dB",20.0,min(20000.0,(sample.sampleRate*.49f).toDouble()),0.0,27.0,
                listOf(nativeResponseCurve("补偿目标频响",sample.meters.drop(20).take(96),0,sample.sampleRate)),true,
                notes=listOf("来自驱动两个架式滤波器目标系数计算的96点响应。实际音频系数另经过10 ms平滑；此图是湿声支路目标，未含干湿混合。系统媒体音量比例不是经校准声压。"))
            ExpansionEffectsKind.DEREVERB -> fftGainPlot("实际尾音抑制分布", "能量加权平均 ${number(m(4))} dB", "每频带衰减",sample.meters.drop(68).take(48),sample.sampleRate,-30.0,0.0,
                notes=listOf("实际1024点WOLA频段增益，负值表示削减；重复FFT频点合并到真实频率，48 kHz时最低发布频点为46.875 Hz。平均值按当前频段能量加权，不等于测得的房间RT60。"))
            ExpansionEffectsKind.DECLICK -> {
                val now=history.lastOrNull()?.timeMs ?: 0L
                val eventPoints=history.zipWithNext().mapNotNull { (a,b)->
                    if(b.split || b.meter(4)<a.meter(4)) null else LiveprogPoint((b.timeMs-now)/1000.0,b.meter(4)-a.meter(4)) }
                val maxEvents=max(1.0,eventPoints.maxOfOrNull{it.y}?:1.0)
                plot("实际修复事件", "累计 ${number(m(4),0)} 点 · 前瞻 ${number(m(6),2)} ms", "秒","每次采样修复点",-20.0,0.0,0.0,maxEvents*1.1,
                    emptyList(),notes=listOf("每个点是相邻驱动采样间的累计修复数之差，双声道样本分别计数。时间间隔约200 ms；不是爆点的实际波形，也不是每秒修复率。"),
                    marks=eventPoints.filter{it.y>0}.map{LiveprogMark(it.x,it.y)})
            }
            ExpansionEffectsKind.DEHUM -> historyPlot("实际嗡声基频跟踪", "当前 ${number(m(4),2)} Hz · 抵消 ${number(m(5))} dBFS",history,47.0,63.0,"Hz",listOf("跟踪基频" to 4),
                listOf("原生21候选估计的实时基频，谐波跟随它的整数倍；频率稳定不代表此刻一定有可闻嗡声，须结合抵消电平。"))
            ExpansionEffectsKind.HEADTRACK -> if(v("mix")<=0.0) bypassScene("头部声场状态", "混合 0% · 当前原声", "原声旁路时驱动不刷新头部姿态，因此不使用之前保留的姿态绘制实时声场。")
            else if(m(8)<.5) scene("实际头部声场", "未连接有效头部跟踪设备",listOf(
                FigureCircle(.5,.45,.13,.16),FigureLine(.5,.28,.5,.18,arrow=true),
                FigureLabel("未连接",.5,.47,emphasis=true),FigureLabel("连接后显示真实转头位置",.5,.78),FigureLabel("当前保持原声",.5,.94)),
                listOf("需要系统开放 TYPE_HEAD_TRACKER 的头部设备；不会用手机姿态冒充头部运动。姿态超过1秒会失效并回到原声。"))
            else headScene(m(7),v("distance"),m(4),v("room"),true).copy(subtitle="水平 ${number(m(4))}° · 俯仰 ${number(m(5))}° · 侧倾 ${number(m(6))}°",
                notes=listOf("头部方向使用驱动已经平滑的真实水平姿态；俯仰和侧倾以数值报告，平面图不伪装三维完整定位。音箱位置固定，数据年龄 ${number(m(9),0)} ms。"))
            ExpansionEffectsKind.VOCAL -> {
                val unavailable=ExpansionEffectsTelemetry.unavailable(sample)
                if(v("mix")<=0.0) bypassScene("人声分离状态", "混合 0% · 当前原声", "混合为零时使用原声，不需要等待可用分离输出，也不会把之前的声部电平显示成正在应用的效果。")
                else if(unavailable!=null) vocalScene(v("vocal_gain"),v("accompaniment_gain"),v("mix"),v("output"),unavailable).copy(title="实际分离状态",subtitle="缓冲 ${number(m(5),0)} ms · 推理 ${number(m(6),0)} ms")
                else historyPlot("分离后的两声部", "人声 / 伴奏 · 缓冲 ${number(m(5),0)} ms",validVocalHistory(history),-120.0,12.0,"dBFS",listOf("人声" to 8,"伴奏" to 9),
                    listOf("两条轨迹是原生分离后声部的峰值电平，未把输入/输出电平冒充人声/伴奏；不是频谱或声波。实际推理 ${number(m(6),0)} ms，累计跟不上 ${number(m(7),0)} 次。"))
            }
            else -> error("Not a group B visual: $kind")
        }
    }

    private fun bypassScene(title:String,subtitle:String,note:String)=scene(title,subtitle,listOf(
        FigureBox(.08,.30,.36,.58,"原声",2),FigureLine(.39,.44,.63,.44,colorIndex=2,arrow=true,width=2.6),
        FigureBox(.66,.30,.94,.58,"输出",2),FigureLabel("当前保持原声",.5,.81)),listOf(note))

    private fun fitDbAxis(visual:EffectVisual):EffectVisual {
        val chart=visual.plot?:return visual
        val levels=(chart.curves.flatMap{it.points}.map{it.y}+chart.marks.map{it.y}).filter{it.isFinite()}
        if(levels.isEmpty())return visual
        val low=min(chart.yMinimum,floor(((levels.minOrNull()?:0.0)-3)/6)*6)
        val high=max(chart.yMaximum,ceil(((levels.maxOrNull()?:0.0)+3)/6)*6)
        return visual.copy(plot=chart.copy(yMinimum=low,yMaximum=high))
    }

    private fun storedProfiles(values:Map<String,*>) = ExpansionEffectsController.matchProfile(values[ExpansionEffectsController.MATCH_PROFILE] as? String)
        ?.map{Float.fromBits(it).toDouble()}
    /** Matches the native learning normalization, clipping, triangular smoothing and strength. */
    internal fun matchCorrection(values:Map<String,*>, powers:List<Double>?):List<Double>? {
        if(powers==null||powers.size!=64||powers.take(32).none{it>1e-12}||powers.drop(32).none{it>1e-12})return null
        fun v(k:String)=p.raw(ExpansionEffectsKind.MATCH_EQ,values,k)
        val usable=(0..31).filter{powers[it]>1e-12&&powers[it+32]>1e-12}
        val offset=if(usable.isEmpty())0.0 else usable.sumOf{10*log10(powers[it]/powers[it+32])}/usable.size
        val raw=List(32){if(it in usable)(10*log10(powers[it]/powers[it+32])-offset).coerceIn(-v("max_gain"),v("max_gain"))else 0.0}
        val radius=1+(v("smoothing")*5).toInt()
        return List(32){j->val hz=20.0*1000.0.pow((j+.5)/32);if(hz !in v("low")..v("high"))0.0 else {
            var sum=0.0;var weight=0.0
            for(d in -radius..radius){val b=j+d;if(b in 0..31){val w=radius+1-abs(d);sum+=raw[b]*w;weight+=w}}
            sum/weight*v("strength")
        }}
    }
    private fun matchIllustration(powers:List<Double>?,correction:List<Double>?,strength:Double,mix:Double):EffectVisual {
        val ready=powers!=null&&correction!=null
        if(!ready)return scene("向参考音色靠拢", "先记录两份频谱", listOf(
            FigureBox(.08,.13,.43,.37,"参考",0),FigureBox(.57,.13,.92,.37,"当前",2),
            FigureLine(.27,.40,.46,.65,arrow=true),FigureLine(.74,.40,.54,.65,colorIndex=2,arrow=true),
            FigureBox(.22,.66,.78,.82,"频谱差 → 修正"),FigureLabel("等待两份学习结果",.5,.94)),
            listOf("参考/当前学习结果完整后，这里以真实保存频谱演示音色轮廓向参考靠拢；不使用装饰性假频谱。"))
        val ref=powers!!.take(32).map{10*log10(it.coerceAtLeast(1e-14))};val target=powers.drop(32).map{10*log10(it.coerceAtLeast(1e-14))}
        val offset=ref.average()-target.average();val aligned=target.map{it+offset}
        val minDb=min(ref.minOrNull()?:-80.0,aligned.minOrNull()?:-80.0);val maxDb=max(ref.maxOrNull()?:0.0,aligned.maxOrNull()?:0.0);val span=max(12.0,maxDb-minDb)
        fun line(a:List<Double>,color:Int,dashed:Boolean=false)=FigurePolyline(a.mapIndexed{i,x->FigurePoint(.08+.84*i/31,.78-.55*(x-minDb)/span)},color,dashed=dashed)
        return scene("向参考轮廓靠拢", "强度 ${number(strength*100,0)}% · 混合 ${number(mix*100,0)}%",listOf(
            line(ref,2,true),line(aligned,1,true),line(aligned.mapIndexed{i,x->x+20*log10(1-mix+mix*gain(correction!![i]))},0),
            FigureLabel("参考 · 当前 · 修正后",.5,.08),FigureLabel("低频 → 高频",.5,.94)),
            listOf("使用已保存真实频谱。为便于比较形状，右图对齐两条曲线的平均dB；紫线把预期修正及混合应用到当前轮廓。并非新的实时录音，也不预测主观响度。"))
    }
    private fun matchLive(s:ExpansionEffectsRuntime,values:Map<String,*>):EffectVisual {
        val mode=s.meter(6).toInt();val ref=s.meter(4)>.5f;val target=s.meter(5)>.5f
        if(mode!=0 || !ref || !target) {
            val curves=buildList {
                if(ref)add(indexedFrequency("已学参考",s.meters.drop(20).take(32).map{it.toDouble()},0,true))
                if(target)add(indexedFrequency("已学当前",s.meters.drop(52).take(32).map{it.toDouble()},2,true))
            }
            val status=if(mode!=0)"${if(mode==1)"参考"else"当前"}学习 ${number(s.meter(7)*100.0,0)}%" else if(!ref)"等待学习参考音乐"else"参考完成 · 待学习当前"
            if(curves.isNotEmpty()) return plot("真实学习频谱",status,"Hz","功率dB",20.0,20000.0,-140.0,12.0,curves,true,
                notes=listOf("只绘制驱动确认已经完成的频谱；采集尚未完成时没有逐频带累计数据，故只报告真实进度，不编造正在生长的频谱。静音暂停累计。"))
            val progress=if(mode!=0)s.meter(7).toDouble().coerceIn(0.0,1.0)else 0.0
            return scene("真实学习进度",status,listOf(FigureCircle(.5,.43,.23,.27,alpha=.2),
                FigureArc(.5,.43,.23,.27,-90.0,progress*360,width=4.0),FigureLabel("${number(progress*100,0)}%",.5,.44,emphasis=true),
                FigureLabel(if(mode==0)"点击下方学习按钮"else"有效声音累计 · 静音暂停",.5,.86)),
                listOf("百分比直接来自原生有效声音累计计数；完成前不伪造参考频谱。"))
        }
        val low=p.raw(ExpansionEffectsKind.MATCH_EQ,values,"low");val high=p.raw(ExpansionEffectsKind.MATCH_EQ,values,"high")
        // Native telemetry reports the learned control curve BEFORE passband gating and 40 ms FFT smoothing.
        val applied=s.meters.drop(84).take(32).mapIndexed{i,x->if(20*1000.0.pow((i+.5)/32) in low..high)x.toDouble()else 0.0}
        return plot("驱动学习修正曲线","32 段 · 作用范围已标出","Hz","目标dB",20.0,20000.0,-12.0,12.0,
            listOf(indexedFrequency("修正目标",applied,0,true)),true,regions=listOf(LiveprogRegion(low,high,"匹配范围")),
            notes=listOf("由原生匹配器发布的32段修正目标，显示端把作用范围以外归零。实际FFT增益还经过40 ms平滑及频点插值，不把目标冒充逐帧实际增益。"))
    }
    private fun indexedFrequency(label:String,values:List<Double>,color:Int,midpoints:Boolean=false)=LiveprogCurve(label,
        values.mapIndexed{i,x->LiveprogPoint(20*1000.0.pow(if(midpoints)(i+.5)/values.size else i.toDouble()/(values.size-1).coerceAtLeast(1)),x)},color)
    private fun nativeResponseCurve(label:String,values:List<Float>,color:Int,rate:Int)=LiveprogCurve(label,
        values.mapIndexed{i,value->val hz=20f*1000f.pow(i.toFloat()/(values.size-1).coerceAtLeast(1));
            LiveprogPoint(min(hz,rate*.49f).toDouble(),value.toDouble())}.distinctBy{it.x},color)
    private fun fftGainPlot(title:String,subtitle:String,label:String,values:List<Float>,rate:Int,minimum:Double,maximum:Double,
                            notes:List<String>,marks:List<LiveprogMark> = emptyList()):EffectVisual {
        val points=values.mapIndexed{i,value->
            val nominal=20f*1000f.pow(i.toFloat()/(values.size-1).coerceAtLeast(1))
            val bin=(nominal*1024/rate).toInt().coerceIn(1,512)
            LiveprogPoint(bin*rate/1024.0,value.toDouble())
        }.distinctBy{it.x}
        val low=points.firstOrNull()?.x?:rate/1024.0
        val high=(points.lastOrNull()?.x?:rate*.499).coerceAtLeast(low+1)
        return plot(title,subtitle,"Hz","增益dB",low,high,minimum,maximum,listOf(LiveprogCurve(label,points)),true,notes,
            marks.filter{it.x in low..high})
    }
    private fun validVocalHistory(history:List<EffectVisualFrame>):List<EffectVisualFrame> {
        var gap=false
        return history.mapNotNull{frame->
            if(frame.meter(4)!=3.0||frame.meter(10)<.5){gap=true;null}
            else frame.copy(split=frame.split||gap).also{gap=false}
        }
    }
    private fun historyPlot(title:String,subtitle:String,history:List<EffectVisualFrame>,min:Double,max:Double,unit:String,
                            series:List<Pair<String,Int>>,notes:List<String>):EffectVisual {
        val end=history.lastOrNull()?.timeMs?:0
        val curves=series.flatMapIndexed{color,(label,index)->
            val segments=mutableListOf<MutableList<LiveprogPoint>>();var points=mutableListOf<LiveprogPoint>()
            history.forEach{frame->if(frame.split&&points.isNotEmpty()){segments.add(points);points=mutableListOf()};points.add(LiveprogPoint((frame.timeMs-end)/1000.0,frame.meter(index)))}
            if(points.isNotEmpty())segments.add(points)
            segments.mapIndexed{i,segment->LiveprogCurve(if(i==0)label else "",segment,color)}
        }
        return plot(title,subtitle,"秒",unit,-20.0,0.0,min,max,curves,notes=notes)
    }
    private fun envelopePlot(title:String,attack:Double,release:Double,level:Double,unit:String="压低dB"):EffectVisual {
        val end=3*max(attack,release)
        return plot(title,"阶跃参考 · 时间常数","ms",unit,0.0,end,0.0,level,
            listOf(curve("进入",0.0,end){level*(1-exp(-it/attack))},curve("释放",0.0,end,2){level*exp(-it/release)}))
    }
    private fun headScene(angle:Double,distance:Double,yaw:Double,room:Double,live:Boolean):EffectVisual {
        val radius=.18+.18*(distance/5).coerceIn(0.0,1.0);val a=angle*PI/180
        val lx=.5-radius*sin(a);val rx=.5+radius*sin(a);val sy=.64-radius*cos(a)
        val heading=yaw*PI/180
        return scene(if(live)"真实头姿与固定音箱"else"虚拟音箱俯视图",if(live)"随实际头部方向更新"else"夹角 ±${number(angle,0)}° · ${number(distance)} m",listOf(
            FigureBox(.07,.12,.93,.89,filled=false,alpha=.4),FigureCircle(lx,sy,.045,label="L",colorIndex=0,filled=true,alpha=.3),FigureCircle(rx,sy,.045,label="R",colorIndex=2,filled=true,alpha=.3),
            FigureCircle(.5,.68,.08,.09),FigureLine(.5,.68,.5+.14*sin(heading),.68-.15*cos(heading),arrow=true,width=2.8),
            FigureLine(lx,sy,.45,.65,alpha=.5),FigureLine(rx,sy,.55,.65,colorIndex=2,alpha=.5),
            FigureLine(lx,sy,.09,.45,dashed=true,alpha=room),FigureLine(.09,.45,.43,.67,dashed=true,alpha=room),
            FigureLine(rx,sy,.91,.45,colorIndex=2,dashed=true,alpha=room),FigureLine(.91,.45,.57,.67,colorIndex=2,dashed=true,alpha=room),
            FigureLabel("前方",.5,.06),FigureLabel(if(live)"头部 ${number(yaw,0)}°"else"以头部为中心",.5,.94)),
            listOf(MODEL,"按音箱角度和距离绘制平面空间示意；房间边界没有真实物理尺寸，不表示个性化HRTF。"))
    }
    private fun vocalScene(vocal:Double,backing:Double,mix:Double,output:Double,status:String?):EffectVisual = scene("人声与伴奏分路",status?:"模型估计 → 独立增益 → 重组",listOf(
        FigureBox(.04,.37,.28,.57,"音乐"),FigureLine(.28,.47,.43,.23,arrow=true),FigureLine(.28,.47,.43,.72,colorIndex=2,arrow=true),
        FigureBox(.43,.11,.75,.32,"人声",0),FigureBox(.43,.62,.75,.83,"伴奏",2),
        FigureLabel("${number(vocal)} dB",.59,.40,width=.48),FigureLabel("${number(backing)} dB",.59,.91,width=.48,colorIndex=2),
        FigureLine(.75,.23,.91,.47,arrow=true,width=(1+mix*2)),FigureLine(.75,.72,.91,.47,colorIndex=2,arrow=true,width=(1+mix*2)),
        FigureCircle(.91,.47,.045,label="+"),FigureLabel("输出 ${number(output)} dB",.50,.055,width=.85)),
        listOf(MODEL,"网络估计人声，伴奏为时间对齐原声减去人声。箭头仅表示信号流；不是不存在的声部频谱。模型有约2.79秒缓冲。"))
    private fun effectiveGain(db:Double,mix:Double)=20*log10((1-mix+mix*gain(db)).coerceAtLeast(1e-9))
    private fun dereverbGain(ratio:Double,strength:Double,floor:Double,protect:Double)=
        10*log10(max(gain(floor).pow(2),1-strength*(1-protect)*ratio).coerceAtLeast(1e-12))
    private fun splitResponse(f:Double,low:Double,high:Double,band:Int):Double {
        fun lp(fc:Double):Pair<Double,Double>{val a=1-exp(-2*PI*fc/48000);val w=2*PI*f/48000;val re=1-(1-a)*cos(w);val im=(1-a)*sin(w);val den=re*re+im*im;return a*re/den to -a*im/den}
        val l=lp(low);val h=lp(high)
        val z=when(band){0->l;1->h.first-l.first to h.second-l.second;else->1-h.first to -h.second}
        return (10*log10((z.first*z.first+z.second*z.second).coerceAtLeast(1e-12))).coerceAtLeast(-48.0)
    }
    private fun msResponse(values:Map<String,*>,side:String,f:Double):Double {
        fun v(k:String)=p.raw(ExpansionEffectsKind.MS_EQ,values,k)
        return (0..7).sumOf{band->val pre="${side}_${band}_";if(v(pre+"enable")<.5)0.0 else biquad(v(pre+"type").toInt(),v(pre+"frequency"),v(pre+"gain"),v(pre+"q"),f)}+v("output")
    }
    private fun biquad(type:Int,frequency:Double,gain:Double,q:Double,f:Double):Double {
        val response=biquadComplex(type,frequency,gain,q,f)
        return 10*log10((response.first*response.first+response.second*response.second).coerceAtLeast(1e-20))
    }
    private fun biquadComplex(type:Int,frequency:Double,gain:Double,q:Double,f:Double):Pair<Double,Double> {
        val a=10.0.pow(gain/40);val w=2*PI*frequency.coerceIn(20.0,20000.0)/48000;val c=cos(w);val alpha=sin(w)/(2*q.coerceAtLeast(.2));val z=2*sqrt(a)*alpha
        val b=when(type){
            1->doubleArrayOf(a*((a+1)-(a-1)*c+z),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-z),(a+1)+(a-1)*c+z,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-z)
            2->doubleArrayOf(a*((a+1)+(a-1)*c+z),-2*a*((a-1)+(a+1)*c),a*((a+1)+(a-1)*c-z),(a+1)-(a-1)*c+z,2*((a-1)-(a+1)*c),(a+1)-(a-1)*c-z)
            else->doubleArrayOf(1+alpha*a,-2*c,1-alpha*a,1+alpha/a,-2*c,1-alpha/a)}
        val wf=2*PI*f/48000
        fun part(offset:Int)=b[offset]+b[offset+1]*cos(wf)+b[offset+2]*cos(2*wf) to -b[offset+1]*sin(wf)-b[offset+2]*sin(2*wf)
        val n=part(0);val d=part(3);val den=(d.first*d.first+d.second*d.second).coerceAtLeast(1e-20)
        return (n.first*d.first+n.second*d.second)/den to (n.second*d.first-n.first*d.second)/den
    }
}
