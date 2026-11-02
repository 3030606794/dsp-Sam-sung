package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import java.util.Locale
import kotlin.math.*

/** Effect-specific views of parameters and published DSP state. No wall-clock animation or invented PCM. */
internal object ExpansionVisualsA {
    private const val MODEL = "参数模型；参考信号只解释算法，不是正在播放音乐的测量。"
    private const val FS = 48000.0
    private class State(val kind: ExpansionEffectsKind, val values: Map<String, *>, focusKey: String?) {
        val meta = ExpansionEffectsParameters.metadata(kind).firstOrNull { it.control.key == focusKey }
        val focus = meta?.logicalKey ?: "mix"
        fun v(key: String) = ExpansionEffectsParameters.raw(kind, values, key)
    }

    fun static(kind: ExpansionEffectsKind, values: Map<String, *>, focusKey: String?): EffectVisualPair {
        val s = State(kind, values, focusKey)
        val professional = when (kind) {
            ExpansionEffectsKind.MICROSHIFT -> microPlot(s)
            ExpansionEffectsKind.FORMANT -> formantPlot(s)
            ExpansionEffectsKind.FREQUENCY_SHIFT -> shiftPlot(s)
            ExpansionEffectsKind.VOCODER -> vocoderPlot(s)
            ExpansionEffectsKind.GATED_REVERB -> gatePlot(s)
            ExpansionEffectsKind.PINGPONG -> pingPlot(s)
            ExpansionEffectsKind.AUTO_WAH -> wahPlot(s)
            ExpansionEffectsKind.AUTOPAN -> panPlot(s)
            ExpansionEffectsKind.LOW_PHASE -> phasePlot(s)
            ExpansionEffectsKind.TAPE -> tapePlot(s)
            else -> error("ExpansionVisualsA does not own $kind")
        }
        val illustration = when (kind) {
            ExpansionEffectsKind.MICROSHIFT -> microScene(s)
            ExpansionEffectsKind.FORMANT -> formantScene(s)
            ExpansionEffectsKind.FREQUENCY_SHIFT -> shiftScene(s)
            ExpansionEffectsKind.VOCODER -> vocoderScene(s)
            ExpansionEffectsKind.GATED_REVERB -> gateScene(s)
            ExpansionEffectsKind.PINGPONG -> pingScene(s)
            ExpansionEffectsKind.AUTO_WAH -> wahScene(s)
            ExpansionEffectsKind.AUTOPAN -> panScene(s)
            ExpansionEffectsKind.LOW_PHASE -> phaseScene(s)
            ExpansionEffectsKind.TAPE -> tapeScene(s)
            else -> error("ExpansionVisualsA does not own $kind")
        }
        val focusNote = s.meta?.let { "联动：${it.control.title}。${ExpansionEffectsParameters.help(kind, it.control.key)}" }
        return EffectVisualPair(professional.copy(notes = listOfNotNull(focusNote) + professional.notes), illustration)
    }

    fun live(kind: ExpansionEffectsKind, values: Map<String, *>, focusKey: String?,
             sample: ExpansionEffectsRuntime, history: List<EffectVisualFrame>): EffectVisual {
        val s = State(kind, values, focusKey)
        fun m(i: Int) = sample.meter(i).toDouble()
        return when (kind) {
            ExpansionEffectsKind.MICROSHIFT -> microLive(s, sample, history)
            ExpansionEffectsKind.FORMANT -> spectrumLive(s, sample, false)
            ExpansionEffectsKind.FREQUENCY_SHIFT -> shiftLive(s, sample)
            ExpansionEffectsKind.VOCODER -> spectrumLive(s, sample, true)
            ExpansionEffectsKind.GATED_REVERB -> gateLive(s, sample, history)
            ExpansionEffectsKind.PINGPONG -> pingLive(s, sample)
            ExpansionEffectsKind.AUTO_WAH -> {
                val sr = sample.sampleRate.toDouble()
                val center = m(4).coerceIn(10.0, sr * .42)
                if (s.focus in setOf("attack", "release", "sensitivity", "direction")) {
                    historyPlot("实际扫频轨迹", "有效中心频率 · ${n(center)} Hz", history, "Hz",
                        0.0, max(s.v("high").coerceAtMost(sr * .42), center) * 1.05,
                        listOf("滤波中心" to { f: EffectVisualFrame -> f.meter(4).coerceIn(10.0, f.sampleRate * .42) }),
                        "中心由驱动目标读数计算；目标超出采样率时，按 TPT 滤波器内部的采样率 × 0.42 上限夹限。")
                } else {
                    val q = m(6).coerceAtLeast(.3); val wet = m(3).coerceIn(0.0, 1.0)
                    plot(freqPlot("实际扫频位置", "实际中心 / Q / 干湿共同决定响应", -42.0, 18.0,
                        listOf(frequency("混合后滤波", sr) { f -> wahMixed(f, center, q, wet, sr) }), sr).copy(
                        marks = listOf(LiveprogMark(center, wahMixed(center, center, q, wet, sr), "${n(center)} Hz")),
                        regions = listOf(LiveprogRegion(s.v("low").coerceAtMost(sr * .42), s.v("high").coerceAtMost(sr * .42), "扫频范围", 2))),
                        "中心频率、Q 和干湿来自驱动读数；曲线是相应滤波器的复数干湿混合模型，不是实测输出频谱。中心按驱动 TPT 的采样率 × 0.42 上限夹限。")
                }
            }
            ExpansionEffectsKind.AUTOPAN -> panLive(s, sample, history)
            ExpansionEffectsKind.LOW_PHASE -> phaseLive(s, sample)
            ExpansionEffectsKind.TAPE -> tapeLive(s, sample, history)
            else -> error("ExpansionVisualsA does not own $kind")
        }
    }

    private fun microPlot(s: State): EffectVisual = when (s.focus) {
        "delay", "depth", "rate" -> {
            val period = 1000 / s.v("rate").coerceAtLeast(.02)
            plot(xy("短延迟摆动", "双轨相差四分之一周期", "ms", "偏移 ms", 0.0, period,
                s.v("delay") - s.v("depth") - 1, s.v("delay") + s.v("depth") + 1,
                listOf(curve("左轨", 0.0, period) { s.v("delay") + s.v("depth") * sin(2 * PI * it / period) },
                    curve("右轨", 0.0, period, 2) { s.v("delay") + s.v("depth") * cos(2 * PI * it / period) })),
                "显示基础延迟与 LFO 摆动；移调的双读头另占 0–28 ms，不把此图当作总时延。")
        }
        "tone" -> cutoffPlot("双轨高频衰减", s.v("tone"))
        "width" -> plot(xy("中侧宽度矩阵", "同相声保留 · 差分声按宽度缩放", "原始侧声道", "输出侧声道", -1.0, 1.0,
            -2.0, 2.0, listOf(curve("侧声道", -1.0, 1.0) { it * s.v("width") }, curve("中声道", -1.0, 1.0, 2) { it })))
        "mix" -> plot(xy("双轨与原声配比", "两路相反微移调 · 示意振荡", "ms", "幅度", 0.0, 20.0, -1.0, 1.0,
            listOf(curve("原声 200 Hz", 0.0, 20.0, 2) { sin(2 * PI * .2 * it) * (1 - s.v("mix")) },
                curve("左湿轨", 0.0, 20.0) { sin(2 * PI * .2 * it * 2.0.pow(s.v("detune") / 1200)) * s.v("mix") })),
            "幅度权重示意；曲线未模拟有初始位置和交叉淡化的双延迟读头。")
        else -> plot(xy("双轨音分与频率", "左 +${n(s.v("detune"))} / 右 −${n(s.v("detune"))} 音分", "原音 Hz", "偏移 Hz", 80.0, 2000.0,
            -65.0, 65.0, listOf(curve("左轨", 80.0, 2000.0) { it * (2.0.pow(s.v("detune") / 1200) - 1) },
                curve("右轨", 80.0, 2000.0, 2) { it * (2.0.pow(-s.v("detune") / 1200) - 1) })))
    }

    private fun formantPlot(s: State): EffectVisual = when (s.focus) {
        "smoothing" -> responsePlot("包络平滑响应", s.v("smoothing"), s.v("smoothing"), "平滑频谱包络的跟随速度")
        "low", "high" -> plot(freqPlot("共振峰变换作用窗", "频带边缘逐渐淡入与淡出", 0.0, 100.0,
            listOf(frequency("处理权重") { formantWindow(s, it) * 100 })).copy(yLabel = "%"))
        else -> {
            val ratio = 2.0.pow(s.v("shift") / 12)
            fun envelope(f: Double) = -24.0 + listOf(500.0, 1500.0, 2800.0).sumOf { 15 * exp(-.5 * (ln(f / it) / .16).pow(2)) }
            plot(freqPlot("共振包络重塑", "固定三峰参考 · 谐波音高保留", -48.0, 36.0,
                listOf(frequency("原包络", color = 2) { envelope(it) }, frequency("处理包络") { f ->
                    val delta = ((envelope(f / ratio) - envelope(f)) * s.v("strength") * formantWindow(s, f)).coerceIn(-24.0, 24.0)
                    envelope(f) + 20 * log10((1 - s.v("mix")) + s.v("mix") * 10.0.pow((delta + s.v("output")) / 20))
                })), "三个参考峰是讲解用输入；实时图才显示音乐的 16 带实际处理频谱。")
        }
    }

    private fun shiftPlot(s: State): EffectVisual = when (s.focus) {
        "lowcut", "highcut" -> bandLimitPlot("频移输出通带", s.v("lowcut"), s.v("highcut"))
        "feedback" -> plot(xy("反馈代次与频移累积", "每圈再偏移一次 · 理想单频示例", "反馈次数", "相对幅度 %", 0.0, 8.0, 0.0, 100.0,
            emptyList()).copy(marks = (0..8).map { LiveprogMark(it.toDouble(), 100 * s.v("feedback").pow(it), "", 0) }),
            "显示反馈衰减。固定参考输入 440 Hz，第 n 圈为 440+n×偏移；真实反馈还经过滤波和限幅。")
        "mix" -> {
            val f = 440.0; val d = s.v("shift") + s.v("stereo") / 2
            plot(xy("原频率与新频率叠加", "440 Hz 参考 · 跨零频率镜像", "Hz", "幅度 %", 0.0, max(1000.0, abs(f + d) + 50), 0.0, 100.0,
                emptyList()).copy(marks = listOf(LiveprogMark(f, 100 * (1 - s.v("mix")), "原频率", 2),
                    LiveprogMark(abs(f + d), 100 * s.v("mix"), "偏移后", 0))))
        }
        else -> plot(xy("固定 Hz 频率搬移", "跨零后镜像 · 非按比例变调", "输入 Hz", "带符号输出 Hz", 0.0, 4000.0, -2500.0, 6500.0,
            listOf(curve("左", 0.0, 4000.0) { it + s.v("shift") + s.v("stereo") / 2 },
                curve("右", 0.0, 4000.0, 2) { it + s.v("shift") - s.v("stereo") / 2 })))
    }

    private fun vocoderPlot(s: State): EffectVisual = when (s.focus) {
        "attack", "release" -> responsePlot("分析包络跟随", s.v("attack"), s.v("release"), "能量包络 · 上升与回落")
        "carrier", "wave" -> {
            val count = min(24, floor(12000 / s.v("carrier")).toInt()).coerceAtLeast(1)
            if (s.v("wave").toInt() == 2) plot(freqPlot("噪声载波覆盖", "期望平坦谱 · 非随机样本", -36.0, 6.0,
                listOf(frequency("白噪声期望") { 0.0 }))) else plot(xy("载波谐波结构", "${n(s.v("carrier"))} Hz · 前 $count 次谐波", "Hz", "相对幅度 dB", 0.0, s.v("carrier") * (count + .5), -36.0, 3.0,
                emptyList()).copy(marks = (1..count).filter { s.v("wave").toInt() != 1 || it % 2 == 1 }
                    .map { LiveprogMark(it * s.v("carrier"), -20 * log10(it.toDouble()), "", 0) }),
                "理想锯齿包含各次谐波，方波只含奇次谐波；实际载波还有抗混叠修正。")
        }
        "unvoiced" -> plot(freqPlot("清辅音保留支路", "5 kHz 一阶高通 · 处理前旁路", -60.0, 12.0,
            listOf(frequency("保留量") { hp(it, 5000.0) + db(s.v("unvoiced")) + s.v("output") })))
        "mix", "output" -> plot(freqPlot("载波合成频带权重", "滤波组示意 · 实际由包络驱动", -48.0, 18.0,
            listOf(frequency("合成覆盖") { f ->
                val count = s.v("bands").toInt(); val ratio = 2.0.pow(s.v("formant") / 12)
                val energy = (0 until count).sumOf { i ->
                    val center = 90 * (12000.0 / 90).pow(i.toDouble() / (count - 1)) * ratio
                    10.0.pow(biquadBand(f, center, max(.7, count / 8.0)) / 10)
                }
                10 * log10(energy.coerceAtLeast(1e-8)) + s.v("output") + db(s.v("mix"))
            })), "假定所有分析包络等强；不是实际声码器输出频响。")
        else -> {
            val count = s.v("bands").toInt(); val ratio = 2.0.pow(s.v("formant") / 12)
            plot(xy("分析与合成频带", "$count 段 · 合成包络 ×${n(ratio, 2)}", "分析 Hz", "合成 Hz", 90.0, 12000.0, 0.0, 24000.0,
                listOf(curve("位置对应", 90.0, 12000.0) { (it * ratio).coerceAtMost(FS * .43) })).copy(
                marks = (0 until count).map { i -> val f = 90 * (12000.0 / 90).pow(i.toDouble() / (count - 1));
                    LiveprogMark(f, (f * ratio).coerceAtMost(FS * .43), "", 2) }, logarithmicX = true))
        }
    }

    private fun gatePlot(s: State): EffectVisual = when (s.focus) {
        "threshold" -> plot(xy("混响触发门限", "输入包络达到阈值后刷新保持", "输入 dBFS", "触发", -84.0, 0.0, 0.0, 1.1,
            listOf(curve("门触发", -84.0, 0.0) { if (it >= s.v("threshold")) 1.0 else 0.0 })).copy(
            marks = listOf(LiveprogMark(s.v("threshold"), 1.0, "${n(s.v("threshold"))} dBFS"))))
        "damping" -> cutoffPlot("混响反馈高频阻尼", s.v("damping"))
        "size" -> {
            val ms = listOf(29.7, 37.1, 41.1, 43.7, 53.1, 61.7, 71.3, 79.7)
            plot(xy("八条扩散路径", "尺寸缩放实际延迟长度", "ms", "反馈比例 %", 0.0, 125.0, 0.0, 100.0,
                emptyList()).copy(marks = ms.mapIndexed { i, t -> val d = t * s.v("size");
                    LiveprogMark(d, 100 * .001.pow(d / (1000 * s.v("decay"))), "${i + 1}", i % 3) }))
        }
        "width" -> widthPlot("混响中侧分布", s.v("width"))
        else -> {
            val hold = s.v("hold"); val close = s.v("close"); val pre = s.v("predelay")
            val end = max(10 + hold + 5 * close, pre + s.v("decay") * 1000).coerceAtMost(9000.0)
            plot(xy("尾音与混响门", "10 ms 参考触发 · 预延迟 ${n(pre)} ms", "ms", "幅度 %", 0.0, end, 0.0, 105.0,
                listOf(curve("混响门", 0.0, end, 2) { t -> gateEnvelope(t, hold, close) * 100 },
                    curve("门控尾音", 0.0, end) { t -> if (t < pre) 0.0 else 100 * s.v("mix") *
                        .001.pow((t - pre) / (s.v("decay") * 1000)) * gateEnvelope(t, hold, close) })),
                "尾音为 RT60 指数包络示意，不是八路 FDN 的逐采样脉冲响应；预延迟作用于声音，门由输入包络控制。")
        }
    }

    private fun pingPlot(s: State): EffectVisual {
        if (s.focus in setOf("tone", "lowcut")) return bandLimitPlot("反馈回声通带", s.v("lowcut"), s.v("tone"))
        if (s.focus == "width") return widthPlot("回声中侧分布", s.v("width"))
        val delay = 60000 / s.v("bpm") * s.v("division")
        val left = delay * (1 + s.v("swing")); val right = delay * (1 - s.v("swing"))
        val marks = mutableListOf<LiveprogMark>()
        // Positive-amplitude single left impulse routing, including partial cross feedback.
        data class Echo(val time: Double, val channel: Int, val amplitude: Double, val generation: Int)
        val queue = ArrayDeque<Echo>(); queue.add(Echo(left, 0, 1.0, 0))
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst(); if (e.amplitude < .005 || e.generation > 4) continue
            marks += LiveprogMark(e.time, e.amplitude * 100 * s.v("mix"), if (e.generation == 0) "左" else "", if (e.channel == 0) 0 else 2)
            queue.add(Echo(e.time + if (e.channel == 0) left else right, e.channel,
                e.amplitude * s.v("feedback") * (1 - s.v("cross")), e.generation + 1))
            queue.add(Echo(e.time + if (e.channel == 0) right else left, 1 - e.channel,
                e.amplitude * s.v("feedback") * s.v("cross"), e.generation + 1))
        }
        listOf("tap_quarter", "tap_half", "tap_three_quarters").forEachIndexed { i, key ->
            if (s.v(key) > 0) marks += LiveprogMark(left * (i + 1) / 4, 100 * s.v(key) * s.v("mix"), listOf("¼", "½", "¾")[i], 1)
        }
        return plot(xy("左右回声时序", "左 ${n(left)} / 右 ${n(right)} ms", "ms", "相对幅度 %", 0.0, 5.2 * max(left, right), 0.0, 100.0,
            emptyList()).copy(marks = marks), "左延迟线内一次单位输入的路由示意；交叉反馈决定重复路径，未模拟滤波、宽度与输入本身的左右内容。")
    }

    private fun wahPlot(s: State): EffectVisual = when (s.focus) {
        "attack", "release" -> responsePlot("包络响应速度", s.v("attack"), s.v("release"), "上升与回落时间常数")
        "sensitivity", "direction" -> plot(xy("输入电平驱动扫频", "包络开平方后指数映射", "输入 dBFS", "中心 Hz", -72.0, 0.0, 0.0, s.v("high") * 1.05,
            listOf(curve("中心频率", -72.0, 0.0) { input ->
                var t = sqrt(10.0.pow((input + s.v("sensitivity")) / 20)).coerceIn(0.0, 1.0)
                if (s.v("direction") > .5) t = 1 - t
                s.v("low") * (s.v("high") / s.v("low")).pow(t)
            })))
        else -> plot(freqPlot("共振滤波扫描范围", "当前 Q ${n(s.v("q"))} · 48 kHz 参数参考", -42.0, 18.0,
            listOf(frequency("下限") { f -> wahMixed(f, s.v("low"), s.v("q"), s.v("mix")) },
                frequency("上限", color = 2) { f -> wahMixed(f, s.v("high"), s.v("q"), s.v("mix")) })),
            "显示上下限中心位置的复数干湿混合响应；音乐包络决定两者之间的实时中心位置。")
    }

    private fun panPlot(s: State): EffectVisual {
        val end = 1000 / s.v("rate").coerceAtLeast(.02)
        return plot(xy(if (s.v("mode") < .5) "声像周期增益" else "左右颤音包络", "完整周期 ${n(end)} ms", "ms", "增益 %", 0.0, end, 0.0, 150.0,
            listOf(curve("左", 0.0, end) { (1 - s.v("mix") + s.v("mix") * panGain(s, it / end, false)) * 100 },
                curve("右", 0.0, end, 2) { (1 - s.v("mix") + s.v("mix") * panGain(s, it / end, true)) * 100 })),
            if (s.v("mode") < .5) "等功率声像；深度决定摆幅，偏置决定中心。颤音声道相位参数在此模式不参与处理。"
            else "颤音由两路包络调制音量；声道相位差改变左右起伏关系。声像偏置在此模式不参与处理。")
    }

    private fun phasePlot(s: State): EffectVisual {
        val fc = s.v("frequency"); val stages = s.v("stages"); val amount = s.v("amount") * s.v("mix")
        if (s.focus in setOf("amount", "mix", "channel")) {
            return plot(freqPlot("混合后的幅频抵消", "纯全通幅频为平直 · 混合可抵消", -60.0, 6.0,
                listOf(frequency("左") { phaseMixed(s, it, false, amount) }, frequency("右", color = 2) { phaseMixed(s, it, true, amount) })),
                "使用全通支路复数响应与原声相加；右反相模式也计入。深凹陷表示相消，不等于新增滤波增益。")
        }
        return plot(freqPlot("全通支路展开相位", "${stages.toInt()} 级 · 转折 ${n(fc)} Hz", -180 * stages, 0.0,
            listOf(frequency("全通相位") { phase(it, fc, stages, FS) * 180 / PI })).copy(yLabel = "°"),
            "全通支路的展开相位；混合后相位不能简单乘以处理比例。")
    }

    private fun tapePlot(s: State): EffectVisual = when (s.focus) {
        "wow", "flutter", "wow_rate", "flutter_rate" -> {
            val fast = s.focus.startsWith("flutter")
            val end = 2 / s.v(if (fast) "flutter_rate" else "wow_rate")
            val curves = if (fast) listOf(curve("抖颤", 0.0, end, 2) {
                val p = s.v("flutter_rate") * it; val phase = p - floor(p)
                s.v("flutter") * (.8 * cos(2 * PI * phase) + .342 * cos(2 * PI * phase * 1.71)) })
                else listOf(curve("抖晃", 0.0, end) { s.v("wow") * cos(2 * PI * s.v("wow_rate") * it) })
            plot(xy(if (fast) "快速抖颤速度误差" else "慢速抖晃速度误差", "所选调制的两个周期", "s", "误差 %", 0.0, end, -1.4, 1.4, curves),
                "参考初相位；深度为速度偏差百分比。驱动积分速度误差形成延迟摆动，实时图读取实际相位。")
        }
        "tone", "speed" -> cutoffPlot("磁带速度与带宽", min(s.v("tone"), 10000 * sqrt(s.v("speed") / 7.5)))
        "hf_compression" -> plot(xy("高频随电平压缩", "2.5 kHz 分频以上的幅度因子", "包络 dBFS", "高频衰减 dB", -72.0, 0.0, -40.0, 1.0,
            listOf(curve("高频压缩", -72.0, 0.0) { -20 * log10(1 + s.v("hf_compression") * 10.0.pow(s.v("drive") / 20) * 10.0.pow(it / 20) * 3) })))
        "noise" -> plot(freqPlot("底噪幅度基准", if (s.v("noise") <= -95.9) "底噪已关闭" else "白噪声幅度设置 · 非实测谱", -108.0, -24.0,
            if (s.v("noise") <= -95.9) emptyList() else listOf(frequency("设置幅度") { s.v("noise") + s.v("output") })),
            "底噪参数是随机信号峰幅度系数，不是每个 FFT 频点的噪声电平；曲线仅作设置基准。")
        else -> {
            val gain = 10.0.pow(s.v("drive") / 20); val bias = s.v("bias"); val out = 10.0.pow(s.v("output") / 20)
            plot(xy("磁化饱和传输", "驱动与偏磁改变非线性形状", "输入幅度", "输出幅度", -1.0, 1.0, -max(1.2, out), max(1.2, out),
                listOf(curve("磁带混合", -1.0, 1.0) { x -> (1 - s.v("mix")) * x + s.v("mix") * out * (tanh(x * gain + bias) - tanh(bias)) / sqrt(gain) },
                    curve("线性参考", -1.0, 1.0, 2) { it })), "静态非线性参考；不包含前后的带宽、动态高频压缩和抗混叠积分。")
        }
    }

    private fun microScene(s: State): EffectVisual {
        val spread = .12 + .12 * s.v("width"); val y = .32 + .003 * s.v("delay")
        return scene("双轨声源示意", "原声居中 · 两轨略微错开", listOf(
            FigureCircle(.5, .2, .065, label = "原声", colorIndex = 1),
            FigureLine(.5, .27, .5 - spread, y - .07, 0, true), FigureLine(.5, .27, .5 + spread, y - .07, 2, true),
            FigureCircle(.5 - spread, y, .065, label = "左", colorIndex = 0), FigureCircle(.5 + spread, y, .065, label = "右", colorIndex = 2),
            FigureArc(.5, .85, spread + .1, .45, 205.0, 130.0, 0, alpha = .4),
            FigureLabel("+${n(s.v("detune"))} / −${n(s.v("detune"))} 音分", .5, .65),
            FigureCircle(.5, .84, .055, label = "听", colorIndex = 1),
            FigureLabel("湿轨 ${n(s.v("mix") * 100, 0)}% · 宽度 ${n(s.v("width"), 2)}", .5, .98)
        ), "声源几何是双轨厚化的比喻，不代表真实声场定位或头相关传递函数。")
    }

    private fun formantScene(s: State): EffectVisual {
        val ratio = 2.0.pow(s.v("shift") / 12); val es = mutableListOf<FigureElement>()
        es += FigureLabel("共振腔峰位移动", .5, .08)
        listOf(.22, .45, .69).forEachIndexed { i, x ->
            val to = (.12 + (x - .12) * ratio).coerceIn(.1, .92)
            es += FigureArc(x, .4, .08, .18, 180.0, 180.0, 2)
            es += FigureLine(x, .45, to, .68, 0, true)
            es += FigureArc(to, .9, .08, .18 * (.35 + .65 * s.v("strength")), 180.0, 180.0, 0)
            if (i == 1) es += FigureLabel("×${n(ratio, 2)}", .5, .57, .45)
        }
        es += FigureLabel("谐波音高保留", .5, .98)
        return scene("音色共振腔比喻", "峰位改变 · 音符不整体升降", es,
            "拱形代表平滑频谱包络的峰，不是人体解剖或真实音乐的三个已检测共振峰。")
    }

    private fun shiftScene(s: State): EffectVisual {
        val es = mutableListOf<FigureElement>(); val offset = s.v("shift") / 2000 * .22
        es += FigureLabel("等间隔音列整体搬移", .5, .08)
        for (i in 0..3) {
            val x = .23 + i * .17
            es += FigureLine(x, .38, x, .2, 2, width = 3.0)
            es += FigureLine(x, .44, (x + offset).coerceIn(.05, .95), .63, 0, true)
            es += FigureLine((x + offset).coerceIn(.05, .95), .86, (x + offset).coerceIn(.05, .95), .68, 0, width = 3.0)
        }
        es += FigureLabel("${signed(s.v("shift"))} Hz", .5, .55)
        es += FigureLabel("间距保留 · 整数倍关系改变", .5, .98)
        return scene("谐波搬移示意", "金属音色来自谐波关系变化", es)
    }

    private fun vocoderScene(s: State): EffectVisual {
        val es = mutableListOf<FigureElement>(FigureBox(.06, .08, .42, .25, "输入包络", 0),
            FigureBox(.58, .08, .94, .25, "内部载波", 2), FigureLine(.24, .25, .36, .48, 0, true), FigureLine(.76, .25, .64, .48, 2, true))
        val bands = s.v("bands").toInt()
        for (i in 0 until bands) { val x = .11 + .78 * i / bands
            es += FigureBox(x, .48, x + .6 / bands, .68, colorIndex = if (i % 2 == 0) 0 else 2, alpha = .2) }
        es += FigureLabel("$bands 段包络 × 合成滤波", .5, .39)
        es += FigureLine(.5, .71, .5, .82, 0, true)
        es += FigureBox(.22, .84, .78, .99, "合成发声", 0)
        return scene("声音借用载波发声", "${n(s.v("carrier"))} Hz · ${listOf("锯齿", "方波", "噪声")[s.v("wave").toInt().coerceIn(0, 2)]}", es,
            "语音/音乐提供随频带变化的包络，内部载波提供音高与谐波；图中条块仅代表滤波通道。")
    }

    private fun gateScene(s: State): EffectVisual {
        val width = .18 + .08 * s.v("size"); val es = mutableListOf<FigureElement>()
        es += FigureBox(.5 - width, .14, .5 + width, .66, filled = false)
        es += FigureCircle(.5, .32, .035, colorIndex = 0, filled = true)
        es += FigurePolyline(listOf(FigurePoint(.5, .32), FigurePoint(.5 + width, .42), FigurePoint(.5 - width, .55), FigurePoint(.5, .64)), 2, alpha = .7)
        es += FigureLabel("房间反射", .5, .07)
        val gateX = .23 + .48 * s.v("hold") / 1000
        es += FigurePolyline((0..32).map { i -> val x = .08 + .84 * i / 32; FigurePoint(x, .91 - .15 * exp(-i / 13.0)) }, 2)
        es += FigureLine(gateX, .71, gateX, .97, 0, width = 3.0)
        es += FigureLabel("门截尾", gateX, .66, .4)
        return scene("房间尾音被门截断", "保持 ${n(s.v("hold"))} ms 后关闭", es,
            "房间是混响空间的示意，不是真实测量尺寸；竖门移动表示保持时间。")
    }

    private fun pingScene(s: State): EffectVisual {
        val delay = 60000 / s.v("bpm") * s.v("division")
        return scene("左右回声路线", "交叉反馈 ${n(s.v("cross") * 100, 0)}%", listOf(
            FigureBox(.03, .12, .36, .32, "左延迟", 0), FigureBox(.64, .12, .97, .32, "右延迟", 2),
            FigureLine(.36, .23, .64, .66, 0, true, width = 1 + 3 * s.v("cross")),
            FigureLine(.64, .23, .36, .66, 2, true, width = 1 + 3 * s.v("cross")),
            FigureBox(.03, .65, .36, .85, "左声道", 0), FigureBox(.64, .65, .97, .85, "右声道", 2),
            FigureLine(.13, .65, .13, .33, 0, true, dashed = true, alpha = 1 - .7 * s.v("cross")),
            FigureLine(.87, .65, .87, .33, 2, true, dashed = true, alpha = 1 - .7 * s.v("cross")),
            FigureLabel("${n(delay)} ms / 拍段", .5, .48), FigureLabel("反馈 ${n(s.v("feedback") * 100, 0)}%", .5, .97)
        ), "交叉线表示跨声道反馈；同侧虚线表示保留在原声道的反馈。")
    }

    private fun wahScene(s: State): EffectVisual {
        val reverse = s.v("direction") > .5
        val es = mutableListOf<FigureElement>(FigureBox(.08, .17, .92, .72, filled = false),
            FigureLabel("低频", .2, .83, .3), FigureLabel("高频", .8, .83, .3))
        es += FigurePolyline((0..48).map { i -> val x = .08 + .84 * i / 48
            FigurePoint(x, .7 - .43 * exp(-((x - .5) / (.2 / sqrt(s.v("q")))).pow(2))) }, 0)
        es += FigureLine(if (reverse) .78 else .22, .34, if (reverse) .22 else .78, .34, 2, true, width = 2.4)
        es += FigureLabel(if (reverse) "声音越强 → 越低" else "声音越强 → 越高", .5, .06)
        es += FigureLabel("${n(s.v("low"))}–${n(s.v("high"))} Hz", .5, .99)
        return scene("滤波窗口随声音扫动", "峰宽由 Q 决定", es)
    }

    private fun panScene(s: State): EffectVisual {
        if (s.v("mode") > .5) return scene("左右音量起伏", "相位决定交替 · 干湿决定起伏", listOf(
            FigureCircle(.27, .42, .14, label = "左", colorIndex = 0), FigureCircle(.73, .42, .14, label = "右", colorIndex = 2),
            FigureArc(.27, .42, .22, .22, 0.0, 360.0, 0, alpha = s.v("depth") * s.v("mix")),
            FigureArc(.73, .42, .22, .22, s.v("phase"), 270.0, 2, alpha = s.v("depth") * s.v("mix")),
            FigureLabel("相差 ${n(s.v("phase"), 0)}°", .5, .82), FigureLabel("音量变化，不表示音源位移", .5, .99)))
        fun mixedPosition(p: Double): Double {
            val pan = p.coerceIn(-1.0, 1.0); val mix = s.v("mix")
            val l = 1 - mix + mix * sqrt(1 - pan); val r = 1 - mix + mix * sqrt(1 + pan)
            return (r * r - l * l) / (r * r + l * l).coerceAtLeast(1e-12)
        }
        val left = .5 + .4 * mixedPosition(s.v("bias") - s.v("depth"))
        val right = .5 + .4 * mixedPosition(s.v("bias") + s.v("depth"))
        return scene("舞台声像摆动范围", "干湿混合后 · 深度与偏置联动", listOf(
            FigureBox(.04, .18, .96, .61, filled = false), FigureLine(left, .4, right, .4, 0, true, width = 2.8),
            FigureCircle(left, .4, .045, colorIndex = 0, filled = true), FigureCircle(right, .4, .045, colorIndex = 2, filled = true),
            FigureLabel("左", .1, .71, .2), FigureLabel("右", .9, .71, .2), FigureCircle(.5, .9, .055, label = "听", colorIndex = 1)))
    }

    private fun phaseScene(s: State): EffectVisual = phasorScene("低频叠加比喻", "固定 100 Hz 参考", 100.0, s.v("frequency"),
        s.v("stages"), s.v("amount") * s.v("mix"), s.v("channel").toInt(), FS, null)

    private fun tapeScene(s: State): EffectVisual {
        val es = mutableListOf<FigureElement>(); val radius = .15
        for (x in listOf(.25, .75)) {
            es += FigureCircle(x, .38, radius, label = if (x < .5) "供带" else "收带", colorIndex = if (x < .5) 0 else 2)
            es += FigureCircle(x, .38, .045, colorIndex = 1)
        }
        es += FigurePolyline(listOf(FigurePoint(.1, .38), FigurePoint(.16, .75), FigurePoint(.84, .75), FigurePoint(.9, .38)), 0, width = 2.6)
        es += FigureBox(.4, .67, .6, .85, "磁头", 2)
        es += FigureLine(.4, .57, .61, .57, 1, true)
        es += FigureLabel("${n(s.v("speed"))} ips", .5, .09)
        es += FigureLabel("驱动 ${n(s.v("drive"))} dB · 偏磁 ${signed(s.v("bias"), 2)}", .5, .99)
        return scene("磁带运行路径", "磁头染色 · 速度变化形成抖晃", es,
            "双盘与磁头是算法的直观比喻，不表示实体机器或磁滞物理仿真。")
    }

    private fun microLive(s: State, sample: ExpansionEffectsRuntime, history: List<EffectVisualFrame>): EffectVisual {
        if (s.focus in setOf("rate", "depth", "delay")) {
            val depth = s.v("depth")
            return historyPlot("实际延迟调制轨迹", "实际相位 + 已确认配置推导", history, "ms", -10.0, 75.0,
                listOf("左轨" to { f: EffectVisualFrame -> f.meter(6) + depth * sin(2 * PI * f.meter(4)) },
                    "右轨" to { f: EffectVisualFrame -> f.meter(6) + depth * cos(2 * PI * f.meter(4)) }),
                "从真实调制相位和已生效摆幅计算；不包含移调双读头的额外延迟。曲线连接界面采样时的离散快照，较快摆动不能凭此图估计频率。")
        }
        val phase = sample.meter(4).toDouble(); val base = sample.meter(6).toDouble(); val width = sample.meter(7).toDouble()
        val left = base + s.v("depth") * sin(2 * PI * phase); val right = base + s.v("depth") * cos(2 * PI * phase)
        return scene("实际双轨调制位置", "驱动相位 ${n(phase * 360, 0)}° · 声源为示意", listOf(
            FigureLine(.1, .3, .9, .3, 0), FigureLine(.1, .68, .9, .68, 2),
            FigureCircle((.1 + .8 * (left + 10) / 80).coerceIn(.1, .9), .3, .035 + .015 * width, label = "左", colorIndex = 0, filled = true, alpha = .4),
            FigureCircle((.1 + .8 * (right + 10) / 80).coerceIn(.1, .9), .68, .035 + .015 * width, label = "右", colorIndex = 2, filled = true, alpha = .4),
            FigureLabel("基础偏移 ${n(left)} / ${n(right)} ms", .5, .93),
            FigureLabel("±${n(sample.meter(5).toDouble())} 音分 · 湿声 ${n(sample.wetPeakDb.toDouble())} dBFS", .5, .06)
        ), "位置来自真实 LFO 相位；并非实测声像、音频波形或完整移调读头位置。")
    }

    private fun spectrumLive(s: State, sample: ExpansionEffectsRuntime, vocoder: Boolean): EffectVisual {
        val es = mutableListOf<FigureElement>(); val levels = (0..15).map { sample.meter(4 + it).toDouble().coerceIn(-120.0, 12.0) }
        for (i in 0..15) {
            val x = .09 + .86 * i / 16; val h = ((levels[i] + 90) / 102).coerceIn(0.0, 1.0) * .64
            es += FigureBox(x, .78 - h, x + .038, .78, colorIndex = if (vocoder) i % 2 * 2 else 0, alpha = .6, rounded = false)
        }
        es += FigureLine(.07, .78, .96, .78, 1)
        es += FigureLabel("−90", .0, .77, .14, align = FigureAlign.LEFT)
        es += FigureLabel("0", .0, .21, .1, align = FigureAlign.LEFT)
        val low = if (vocoder) 90.0 else 40.0
        val high = if (vocoder) min(12000.0, sample.sampleRate * .42) else sample.sampleRate / 2.0
        es += FigureLabel("${n(low, 0)} Hz", .15, .9, .28)
        es += FigureLabel("${n(high / 1000, 1)} kHz", .83, .9, .3)
        val caption = when (s.focus) {
            "attack", "release", "smoothing" -> "实际频带随输入跟随 · 时间响应看上图"
            "carrier", "wave", "formant" -> "这里测量输入包络；不是载波输出谱"
            else -> if (vocoder) "16 个驱动包络读数 · dBFS" else "16 带处理后频谱峰值 · dBFS"
        }
        es += FigureLabel(caption, .5, .04)
        return scene(if (vocoder) "实际分析包络驱动" else "实际变换后的频谱", "最高频带 ${n(levels.maxOrNull() ?: -120.0)} dBFS", es,
            if (vocoder) "16 个柱来自驱动实际 RMS 分析包络。8 段配置会重复采样部分频带；24 段配置取其中代表带。横向是分析频带顺序。"
            else "16 个对数频带来自变换后 FFT 幅度，不是实时估计的三个共振峰，也不表示原始输入与输出的差值。")
    }

    private fun shiftLive(s: State, sample: ExpansionEffectsRuntime): EffectVisual {
        val phase = sample.meter(4) * 2.0 * PI; val shift = sample.meter(5).toDouble(); val stereo = sample.meter(6).toDouble()
        val es = mutableListOf<FigureElement>(FigureCircle(.28, .46, .19, colorIndex = 1),
            FigureLine(.28, .46, .28 + .19 * cos(phase), .46 - .19 * sin(phase), 0, true, width = 2.8),
            FigureLabel("左振荡相位", .28, .78, .48), FigureLabel("${n(sample.meter(4) * 360.0, 0)}°", .28, .08, .45),
            FigureBox(.57, .22, .96, .4, "左 ${signed(shift + stereo / 2)} Hz", 0),
            FigureBox(.57, .51, .96, .69, "右 ${signed(shift - stereo / 2)} Hz", 2),
            FigureLabel("FIR 延迟 ${n(sample.meter(7).toDouble(), 2)} ms", .5, .97))
        if (s.focus == "feedback") es += FigureArc(.76, .46, .22, .31, 60.0, 250.0, 1, width = 1 + 3 * s.v("feedback"))
        return scene("实际频移振荡器", "相位快照 · 不外推连续旋转", es,
            "只发布了左振荡器相位，右侧显示实际偏移量。界面采样率远低于振荡频率，不能用旋转快慢判断 Hz；数值才是偏移速度。")
    }

    private fun gateLive(s: State, sample: ExpansionEffectsRuntime, history: List<EffectVisualFrame>): EffectVisual {
        if (s.focus in setOf("hold", "close", "decay", "predelay")) return historyPlot("实际混响门开合", "剩余保持 ${n(sample.meter(5).toDouble())} ms", history,
            "门增益 %", 0.0, 100.0, listOf("混响门" to { f: EffectVisualFrame -> f.meter(4) * 100 }),
            "历史为真实门增益，不生成假定鼓点；低于阈值后，门按剩余保持与关闭时间变化。")
        val gate = sample.meter(4).toDouble().coerceIn(0.0, 1.0); val level = sample.meter(6).toDouble()
        val thresholdY = .85 - .65 * ((s.v("threshold") + 84) / 84)
        return scene("实际门状态与触发电平", "${if (sample.meter(5) > 0) "保持中" else if (gate > .01) "关闭中" else "已关闭"} · ${n(gate * 100, 0)}%", listOf(
            FigureBox(.08, .85 - .65 * ((level + 84) / 84).coerceIn(0.0, 1.0), .25, .85, colorIndex = 2, alpha = .6),
            FigureLine(.04, thresholdY, .31, thresholdY, 1, dashed = true),
            FigureLabel("输入包络", .17, .96, .34), FigureLabel("${n(level)} dBFS", .17, .08, .35),
            FigureBox(.46, .18, .94, .85, filled = false),
            FigureBox(.46, .18, .7 - .24 * gate, .85, colorIndex = 0), FigureBox(.7 + .24 * gate, .18, .94, .85, colorIndex = 0),
            FigureLine(.34, .52, .95, .52, 2, true, width = 1 + 3 * gate, alpha = .15 + .85 * gate),
            FigureLabel("混响门", .7, .96, .45)), "门扇开度由真实 gate 读数控制；虚线是当前触发阈值。")
    }

    private fun pingLive(s: State, sample: ExpansionEffectsRuntime): EffectVisual {
        val delay = sample.meter(4).toDouble(); val swing = sample.meter(7).toDouble(); val feedback = sample.meter(6).toDouble()
        val alpha = ((sample.wetPeakDb + 84) / 84.0).coerceIn(.08, 1.0)
        val cross = s.v("cross").coerceIn(0.0, 1.0)
        val es = mutableListOf<FigureElement>(FigureBox(.04, .16, .4, .4, "左 ${n(delay * (1 + swing))} ms", 0),
            FigureBox(.6, .16, .96, .4, "右 ${n(delay * (1 - swing))} ms", 2),
            FigureLabel("实际反馈 ${n(feedback * 100, 0)}%", .5, .06), FigureLabel("湿声 ${n(sample.wetPeakDb.toDouble())} dBFS", .5, .99))
        if (cross > 0 && feedback > 0) {
            es += FigureLine(.4, .28, .6, .65, 0, true, width = 1 + 4 * feedback * cross, alpha = alpha * cross)
            es += FigureLine(.6, .28, .4, .65, 2, true, width = 1 + 4 * feedback * cross, alpha = alpha * cross)
        }
        if (cross < 1 && feedback > 0) {
            es += FigureLine(.12, .65, .12, .4, 0, true, width = 1 + 4 * feedback * (1 - cross), alpha = alpha * (1 - cross))
            es += FigureLine(.88, .65, .88, .4, 2, true, width = 1 + 4 * feedback * (1 - cross), alpha = alpha * (1 - cross))
        }
        for (i in 0..2) {
            val level = sample.meter(8 + i).toDouble().coerceIn(0.0, 1.0); val x = .18 + i * .32
            es += FigureCircle(x, .76, .045 + .04 * level, label = listOf("¼", "½", "¾")[i], colorIndex = i, filled = level > 0, alpha = .15 + .7 * level)
        }
        return scene("生效中的回声回路", "真实延迟 / 反馈 / 抽头 · 路径亮度随湿声", es,
            "抽头圆大小来自驱动实际电平系数，路径亮度来自湿声峰值；当前驱动不发布逐回声位置，因此不绘制虚构的弹跳音符。")
    }

    private fun panLive(s: State, sample: ExpansionEffectsRuntime, history: List<EffectVisualFrame>): EffectVisual {
        if (s.focus in setOf("rate", "wave", "phase")) return historyPlot("实际左右调制包络", "实际干湿混合后 · 相位 ${n(sample.meter(4) * 360.0, 0)}°", history,
            "增益 %", 0.0, 150.0, listOf("左" to { f: EffectVisualFrame -> (1 - f.meter(3) + f.meter(3) * f.meter(5)) * 100 },
                "右" to { f: EffectVisualFrame -> (1 - f.meter(3) + f.meter(3) * f.meter(6)) * 100 }))
        val wet = sample.meter(3).toDouble(); val l = 1 - wet + wet * sample.meter(5); val r = 1 - wet + wet * sample.meter(6); val mode = sample.meter(7).toInt()
        val pan = ((r * r - l * l) / (r * r + l * l).coerceAtLeast(1e-8)).coerceIn(-1.0, 1.0)
        val es = mutableListOf<FigureElement>(FigureLabel("左 ${n(l * 100, 0)}%", .2, .08, .4), FigureLabel("右 ${n(r * 100, 0)}%", .8, .08, .4))
        if (mode == 0) {
            es += FigureBox(.05, .2, .95, .65, filled = false); es += FigureLine(.1, .44, .9, .44, 1, dashed = true)
            es += FigureCircle(.5 + .38 * pan, .44, .065, colorIndex = 0, filled = true, alpha = .8)
            es += FigureCircle(.5, .88, .06, label = "听", colorIndex = 1)
        } else {
            es += FigureCircle(.26, .48, .055 + .15 * l.coerceIn(0.0, 1.0), label = "左", colorIndex = 0, filled = true, alpha = .4)
            es += FigureCircle(.74, .48, .055 + .15 * r.coerceIn(0.0, 1.0), label = "右", colorIndex = 2, filled = true, alpha = .4)
            es += FigureLabel("两路音量脉冲", .5, .9)
        }
        return scene(if (mode == 0) "实际声像控制位置" else "实际左右颤音强弱", "实际干湿混合后的左右增益", es,
            "这表示处理器施加的增益控制，不是对原始立体声音乐的声源定位测量。")
    }

    private fun phaseLive(s: State, sample: ExpansionEffectsRuntime): EffectVisual {
        return phasorScene("实际配置相位参考", "100 Hz 配置参考 · 非音频示波", 100.0,
            sample.meter(4).toDouble().coerceAtLeast(20.0), sample.meter(5).toDouble().coerceAtLeast(1.0),
            sample.meter(6).toDouble() * sample.meter(3), sample.meter(7).toInt(), sample.sampleRate.toDouble(), sample.wetPeakDb.toDouble())
            .copy(notes = listOf("驱动发布的频率、级数、处理比例和声道模式用于计算 100 Hz 参考矢量，圆盘亮度跟随真实湿声；它不是 PCM 波形或音乐实测相位。"))
    }

    private fun tapeLive(s: State, sample: ExpansionEffectsRuntime, history: List<EffectVisualFrame>): EffectVisual {
        if (s.focus in setOf("wow", "flutter", "wow_rate", "flutter_rate")) return historyPlot("实际机械摆动轨迹", "实际相位 + 已确认配置推导", history,
            "偏移 ms", -18.0, 18.0, listOf("抖晃" to { f: EffectVisualFrame ->
                s.v("wow") * 10 / (2 * PI * s.v("wow_rate")) * sin(2 * PI * f.meter(4)) },
                "抖颤" to { f: EffectVisualFrame -> s.v("flutter") * 10 / (2 * PI * s.v("flutter_rate")) *
                    (.8 * sin(2 * PI * f.meter(5)) + .2 * sin(2 * PI * f.meter(5) * 1.71)) }),
            "从实际 wow/flutter 相位计算对应延迟偏移；快速抖颤超过界面刷新率，离散样点不能用于判断其振荡速度。")
        val drive = sample.meter(7).toDouble(); val level = sample.meter(6).toDouble()
        val compression = -20 * log10(1 + s.v("hf_compression") * 10.0.pow(drive / 20) * 10.0.pow(level / 20) * 3)
        val es = mutableListOf<FigureElement>()
        for ((i, x) in listOf(.24, .76).withIndex()) {
            val ph = sample.meter(4 + i).toDouble() * 2 * PI
            es += FigureCircle(x, .39, .18, colorIndex = if (i == 0) 0 else 2)
            es += FigureLine(x, .39, x + .16 * cos(ph), .39 + .16 * sin(ph), if (i == 0) 0 else 2, width = 2.4)
        }
        es += FigureLabel("抖晃相位", .24, .67, .42); es += FigureLabel("抖颤相位", .76, .67, .42)
        es += FigureLabel("包络 ${n(level)} dBFS · 驱动 ${n(drive)} dB", .5, .07)
        es += FigureBox(.12, .79, .88, .96, "高频压缩 ${n(compression)} dB", 1)
        return scene("实际磁带调制状态", "真实相位 / 包络 · 动态高频因子", es,
            "圆盘指针表示实际调制相位，不是机械轴的测量。高频因子由实际包络及当前参数计算，不等于全频段输出衰减。")
    }

    private fun phasorScene(title: String, subtitle: String, reference: Double, center: Double, stages: Double,
                            amount: Double, mode: Int, sr: Double, wetDb: Double?): EffectVisual {
        val ph = phase(reference, center, stages, sr); val alpha = wetDb?.let { ((it + 84) / 84).coerceIn(.12, 1.0) } ?: 1.0
        val es = mutableListOf<FigureElement>()
        for ((c, x) in listOf(.25, .75).withIndex()) {
            val processed = !(mode == 0 && c == 1 || mode == 2 && c == 0)
            val theta = if (processed) ph + if (mode == 3 && c == 1) PI else 0.0 else 0.0
            val a = if (processed) amount.coerceIn(0.0, 1.0) else 0.0
            val re = 1 - a + a * cos(theta); val im = a * sin(theta)
            es += FigureCircle(x, .45, .19, colorIndex = 1, alpha = .5)
            es += FigureLine(x, .45, x + .19, .45, 1, true, dashed = true)
            es += FigureLine(x, .45, x + .19 * cos(theta), .45 - .19 * sin(theta), 2, true, alpha = .65)
            es += FigureLine(x, .45, x + .19 * re, .45 - .19 * im, 0, true, width = 2.8, alpha = alpha)
            es += FigureLabel(if (c == 0) "左" else "右", x, .77, .3)
            es += FigureLabel("${n(db(hypot(re, im)))} dB", x, .93, .42)
        }
        es += FigureLabel("虚线原声 · 橙全通 · 紫混合", .5, .06)
        return scene(title, subtitle, es, "固定 100 Hz 正弦的复数矢量示意；长度是该参考频率的幅度，方向是相位。")
    }

    private fun historyPlot(title: String, subtitle: String, frames: List<EffectVisualFrame>, unit: String,
                            low: Double, high: Double, series: List<Pair<String, (EffectVisualFrame) -> Double>>, vararg notes: String): EffectVisual {
        val now = frames.lastOrNull()?.timeMs ?: 0L
        val curves = mutableListOf<LiveprogCurve>()
        series.forEachIndexed { channel, (label, read) ->
            var points = mutableListOf<LiveprogPoint>(); var first = true
            fun flush() { if (points.isNotEmpty()) { curves += LiveprogCurve(if (first) label else "", points.toList(), if (channel == 0) 0 else 2); first = false; points = mutableListOf() } }
            frames.forEach { f ->
                if (f.split) flush()
                val y = read(f); if (y.isFinite()) points += LiveprogPoint((f.timeMs - now) / 1000.0, y) else flush()
            }; flush()
        }
        return plot(xy(title, subtitle, "最近 s", unit, -20.0, 0.0, low, high, curves), *notes).copy(notes = notes.toList())
    }

    private fun responsePlot(title: String, attack: Double, release: Double, subtitle: String): EffectVisual {
        val end = max(attack, release) * 5
        return plot(xy(title, subtitle, "ms", "响应 %", 0.0, end, 0.0, 100.0,
            listOf(curve("上升", 0.0, end) { 100 * (1 - exp(-it / attack.coerceAtLeast(.1))) },
                curve("回落", 0.0, end, 2) { 100 * exp(-it / release.coerceAtLeast(.1)) })), "单位阶跃/回落参考，所填时间为时间常数，并非完成全部响应的时长。")
    }
    private fun cutoffPlot(title: String, cutoff: Double) = plot(freqPlot(title, "截止 ${n(cutoff)} Hz · 48 kHz", -42.0, 3.0,
        listOf(frequency("低通幅频") { lp(it, cutoff) })))
    private fun bandLimitPlot(title: String, low: Double, high: Double) = plot(freqPlot(title, "一阶高通串联一阶低通", -48.0, 3.0,
        listOf(frequency("通带") { hp(it, low) + lp(it, high) })))
    private fun widthPlot(title: String, width: Double) = plot(xy(title, "差分声缩放 · 中央同相声保留", "原侧声道", "输出侧声道", -1.0, 1.0, -2.0, 2.0,
        listOf(curve("侧声道", -1.0, 1.0) { it * width }, curve("中声道", -1.0, 1.0, 2) { it })))
    private fun formantWindow(s: State, f: Double) = ((f - s.v("low")) / max(50.0, s.v("low"))).coerceIn(0.0, 1.0) *
        ((s.v("high") - f) / max(200.0, s.v("high") * .1)).coerceIn(0.0, 1.0)
    private fun gateEnvelope(t: Double, hold: Double, close: Double) = when {
        t < 10 -> 1 - exp(-t / 2)
        t < 10 + hold -> 1 - exp(-5.0)
        else -> (1 - exp(-5.0)) * exp(-(t - 10 - hold) / close.coerceAtLeast(1.0))
    }
    private fun panGain(s: State, phase: Double, right: Boolean): Double {
        fun wave(p: Double): Double { val x = p - floor(p); return when (s.v("wave").toInt()) { 1 -> 1 - 4 * abs(x - .5); 2 -> tanh(sin(2 * PI * x) * 8); else -> sin(2 * PI * x) } }
        return if (s.v("mode") < .5) { val pan = (wave(phase) * s.v("depth") + s.v("bias")).coerceIn(-1.0, 1.0); sqrt(if (right) 1 + pan else 1 - pan) }
        else 1 - s.v("depth") * .5 * (1 - wave(phase + if (right) s.v("phase") / 360 else 0.0))
    }
    private fun phase(f: Double, center: Double, stages: Double, sr: Double) = -2 * atan(tan(PI * min(f, sr * .49) / sr) / tan(PI * center / sr)) * stages
    private fun phaseMixed(s: State, f: Double, right: Boolean, amount: Double): Double {
        val mode = s.v("channel").toInt(); if (mode == 0 && right || mode == 2 && !right) return 0.0
        val ph = phase(f, s.v("frequency"), s.v("stages"), FS) + if (mode == 3 && right) PI else 0.0
        return db(hypot(1 - amount + amount * cos(ph), amount * sin(ph)))
    }
    private fun lp(f: Double, cutoff: Double, sr: Double = FS): Double {
        val a = exp(-2 * PI * cutoff.coerceIn(1.0, sr * .45) / sr)
        return db((1 - a) / sqrt(1 + a * a - 2 * a * cos(2 * PI * f / sr)))
    }
    private fun hp(f: Double, cutoff: Double, sr: Double = FS): Double {
        val a = exp(-2 * PI * cutoff.coerceIn(1.0, sr * .45) / sr); val w = 2 * PI * f / sr
        return db(a * sqrt(2 - 2 * cos(w)) / sqrt(1 + a * a - 2 * a * cos(w)))
    }
    private fun bandpass(f: Double, center: Double, q: Double, sr: Double = FS): Double {
        val x = tan(PI * f.coerceAtMost(sr * .49) / sr) / tan(PI * center.coerceIn(10.0, sr * .42) / sr)
        return db(x / sqrt((1 - x * x).pow(2) + (x / q).pow(2)) / sqrt(max(1.0, q)))
    }
    private fun wahMixed(f: Double, center: Double, q: Double, mix: Double, sr: Double = FS): Double {
        val x = tan(PI * f.coerceAtMost(sr * .49) / sr) / tan(PI * center.coerceIn(10.0, sr * .42) / sr)
        val den = (1 - x * x).pow(2) + (x / q).pow(2)
        val re = x * x / q / den / sqrt(max(1.0, q)); val im = x * (1 - x * x) / den / sqrt(max(1.0, q))
        return db(hypot(1 - mix + mix * re, mix * im))
    }
    private fun biquadBand(f: Double, center: Double, q: Double): Double {
        val w = 2 * PI * center.coerceIn(10.0, FS * .43) / FS; val a = sin(w) / (2 * q)
        val z = 2 * PI * f / FS; val b0 = a / (1 + a); val a1 = -2 * cos(w) / (1 + a); val a2 = (1 - a) / (1 + a)
        val num = hypot(b0 * (1 - cos(2 * z)), b0 * sin(2 * z))
        val den = hypot(1 + a1 * cos(z) + a2 * cos(2 * z), a1 * sin(z) + a2 * sin(2 * z))
        return db(num / den.coerceAtLeast(1e-12))
    }
    private fun xy(title: String, subtitle: String, x: String, y: String, x0: Double, x1: Double, y0: Double, y1: Double,
                   curves: List<LiveprogCurve>) = LiveprogStaticPlot(title, subtitle, xLabel = x, yLabel = y,
        xMinimum = x0, xMaximum = x1, yMinimum = y0, yMaximum = y1, curves = curves)
    private fun freqPlot(title: String, subtitle: String, low: Double, high: Double, curves: List<LiveprogCurve>, sr: Double = FS) =
        xy(title, subtitle, "Hz", "dB", 20.0, min(20000.0, sr * .48), low, high, curves).copy(logarithmicX = true)
    private fun curve(label: String, low: Double, high: Double, color: Int = 0, fn: (Double) -> Double) = LiveprogCurve(label,
        (0..96).map { val x = low + (high - low) * it / 96; LiveprogPoint(x, fn(x)) }, color)
    private fun frequency(label: String, sr: Double = FS, color: Int = 0, fn: (Double) -> Double) = LiveprogCurve(label,
        (0..128).map { val f = 20 * (min(20000.0, sr * .48) / 20).pow(it / 128.0); LiveprogPoint(f, fn(f)) }, color)
    private fun plot(p: LiveprogStaticPlot, vararg notes: String) = EffectVisual(p.title, p.subtitle, plot = p,
        notes = listOf(MODEL) + notes)
    private fun scene(title: String, subtitle: String, elements: List<FigureElement>, vararg notes: String) =
        EffectVisual(title, subtitle, scene = EffectScene(elements), notes = notes.toList())
    private fun db(gain: Double) = (20 * log10(gain.coerceAtLeast(1e-5))).coerceIn(-100.0, 100.0)
    private fun n(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.$digits" + "f", if (value.isFinite()) value else 0.0)
    private fun signed(value: Double, digits: Int = 1) = (if (value >= 0) "+" else "") + n(value, digits)
}
