package me.timschneeberger.rootlessjamesdsp.creative

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import java.util.Locale
import kotlin.math.*

/** Effect-specific parameter diagrams and read-only native telemetry. No synthetic live audio. */
internal object CreativeEffectsVisuals {
    private val p = CreativeEffectsParameters
    private class Config(val kind: CreativeEffectsKind, val values: Map<String, *>, focusKey: String?) {
        val focus = p.controls(kind).firstOrNull { it.key == focusKey } ?: p.controls(kind).first()
        fun raw(c: BassParameters.Control) = p.value(values, c).toDouble()
        fun unit(c: BassParameters.Control) = raw(c) / c.divisor
        fun percent(c: BassParameters.Control) = raw(c) / 1000.0
        val enabled get() = values[kind.enableKey] == true
        val caption get() = "${focus.title} ${focus.label(p.value(values, focus))}"
    }
    data class Sample(val timeMs: Long, val runtime: CreativeEffectsRuntime, val split: Boolean = false)

    fun pair(kind: CreativeEffectsKind, values: Map<String, *>, focusKey: String?): EffectVisualPair {
        val c = Config(kind, values, focusKey)
        val professional = professional(c)
        val illustration = when {
            c.focus.key.endsWith("_mix") -> mixIllustration(c)
            c.focus.key in setOf(p.granularLowpass.key, p.freezeLowpass.key, p.shimmerDamping.key, p.shimmerHighpass.key) -> filterIllustration(c)
            c.focus.key == p.granularFeedback.key -> feedbackIllustration(c)
            kind == CreativeEffectsKind.GRANULAR -> granularIllustration(c)
            kind == CreativeEffectsKind.FREEZE -> freezeIllustration(c)
            else -> shimmerIllustration(c)
        }
        fun present(v: EffectVisual) = v.copy(inactive = !c.enabled,
            notes = v.notes + "左图为参数关系；右图为作用示意。示意中的声音与声源是固定例子，不是当前音乐的测量结果。长按可查看图的说明。")
        return EffectVisualPair(present(professional), present(illustration))
    }

    private fun professional(c: Config): EffectVisual {
        val key = c.focus.key
        val plot = when {
            key.endsWith("_mix") -> LiveprogStaticPlot("原声／效果混合规律", c.caption,
                xLabel = "效果混合 %", yLabel = "幅度权重 %", xMinimum = 0.0, xMaximum = 100.0,
                yMinimum = 0.0, yMaximum = 100.0,
                curves = listOf(curve("原声", 0.0, 100.0, 2) { 100 - it }, curve("效果", 0.0, 100.0) { it }),
                marks = listOf(LiveprogMark(c.unit(c.focus), c.unit(c.focus))),
                notes = listOf("线性干湿混合：原声×(1−混合)+效果×混合。权重不是响度或音乐能量百分比；冻结未捕捉时保持原声。"))
            key.endsWith("_pitch") -> {
                val ratio = 2.0.pow(c.raw(c.focus) / 1200.0)
                LiveprogStaticPlot("移调频率映射", "${c.caption} · ${n(ratio, 2)}×",
                    xLabel = "原始 Hz", yLabel = "移调 Hz", xMaximum = 1000.0, yMaximum = max(1000.0, 1000 * ratio),
                    curves = listOf(curve("当前移调", 0.0, 1000.0) { it * ratio }, curve("原音参考", 0.0, 1000.0, 2) { it }),
                    marks = listOf(LiveprogMark(440.0, 440 * ratio)),
                    notes = listOf("比率=2^(音分/1200)。440 Hz 仅为固定例子，不是检测到的基频；高于实际奈奎斯特频率的内容不会完整保留。"))
            }
            key in setOf(p.granularLowpass.key, p.freezeLowpass.key, p.shimmerDamping.key, p.shimmerHighpass.key) -> filter(c)
            key.endsWith("_width") -> stereo(c)
            c.kind == CreativeEffectsKind.GRANULAR -> granularProfessional(c)
            c.kind == CreativeEffectsKind.FREEZE -> freezeProfessional(c)
            else -> shimmerProfessional(c)
        }
        return EffectVisual(plot.title, plot.subtitle, plot = plot, notes = plot.notes)
    }

    private fun granularProfessional(c: Config): LiveprogStaticPlot = when (c.focus.key) {
        p.granularGrain.key, p.granularDensity.key -> {
            val length = c.raw(p.granularGrain)
            val interval = 1000.0 / c.raw(p.granularDensity)
            LiveprogStaticPlot("颗粒窗与重叠", "${length.toInt()} ms／粒 · 间隔 ${n(interval)} ms",
                xLabel = "时间 ms", yLabel = "Hann 窗 %", xMaximum = length + interval * 2,
                yMaximum = 100.0, curves = (0..2).map { grain ->
                    LiveprogCurve(if (grain == 0) "连续三个颗粒窗" else "", List(65) { i ->
                        LiveprogPoint(grain * interval + length * i / 64, 50 - 50 * cos(2 * PI * i / 64))
                    }, grain)
                }, notes = listOf("驱动使用 Hann 窗；密度决定生成间隔，长度决定每颗粒的持续时间。图中三个窗口只是包络参考，实际活跃颗粒数看下方。"))
        }
        p.granularDelay.key, p.granularSpread.key -> {
            val delay = c.raw(p.granularDelay)
            val spread = c.raw(p.granularSpread)
            LiveprogStaticPlot("片段回看范围", "${delay.toInt()}–${(delay + spread).toInt()} ms",
                xLabel = "回看时间 ms", yLabel = "候选区间", xMaximum = 3000.0,
                curves = listOf(LiveprogCurve("取样范围", listOf(LiveprogPoint(delay, .5), LiveprogPoint(delay + spread, .5)))),
                regions = listOf(LiveprogRegion(delay, delay + spread)), marks = listOf(LiveprogMark(delay, .5)),
                notes = listOf("显示名义延迟加 0 至散布的均匀随机范围；实际还会为升调读取留出额外历史，并受缓冲区有效范围约束。"))
        }
        p.granularReverse.key -> LiveprogStaticPlot("每颗粒的方向概率", c.caption, LiveprogPlotStyle.BARS,
            yLabel = "概率 %", yMaximum = 100.0,
            bars = listOf(LiveprogBar("正放", 100 - c.unit(p.granularReverse)), LiveprogBar("倒放", c.unit(p.granularReverse), 2)),
            notes = listOf("每个新颗粒独立随机决定方向；短时间内实际倒放比例可能偏离该概率。"))
        else -> {
            val feedback = c.percent(p.granularFeedback)
            LiveprogStaticPlot("再次反馈的幅度", c.caption, xLabel = "反馈次数", yLabel = "相对 dB",
                xMaximum = 8.0, yMinimum = -72.0, yMaximum = 0.0,
                curves = listOf(curve("仅反馈系数参考", 0.0, 8.0) { db(feedback.pow(it)).coerceAtLeast(-72.0) }),
                notes = listOf("假定每次经过反馈只乘一次设定系数；实际颗粒随机重排、窗归一化及滤波会进一步改变尾音，图中不是实测衰减。"))
        }
    }

    private fun freezeProfessional(c: Config): LiveprogStaticPlot = when (c.focus.key) {
        p.freezeAttack.key, p.freezeRelease.key -> {
            val attack = c.raw(p.freezeAttack)
            val release = c.raw(p.freezeRelease)
            val end = max(attack, release) * 1.1
            LiveprogStaticPlot("冻结淡入／释放包络", c.caption, xLabel = "时间 ms", yLabel = "包络 %",
                xMaximum = end, yMaximum = 100.0,
                curves = listOf(curve("淡入", 0.0, end) { 100 * (1 - exp(-ln(1000.0) * it / attack)) },
                    curve("释放", 0.0, end, 2) { 100 * exp(-ln(1000.0) * it / release) }),
                notes = listOf("两条曲线是分别触发淡入、释放的参考；设定时间到达 99.9% 或衰减至 0.1%。捕捉所需分析帧等待另计。"))
        }
        p.freezeBlur.key -> {
            val b = c.raw(p.freezeBlur) * .00025
            LiveprogStaticPlot("频谱相邻频点柔化", c.caption, LiveprogPlotStyle.BARS,
                yLabel = "幅度权重 %", yMaximum = 100.0,
                bars = listOf(LiveprogBar("前一格", 100 * b, 2), LiveprogBar("当前格", 100 * (1 - 2 * b)), LiveprogBar("后一格", 100 * b, 2)),
                notes = listOf("移调插值前，对每个 FFT 幅度频点混入两侧相邻频点。最大柔化时三点权重为 25%、50%、25%；不等于全频谱高斯模糊。"))
        }
        else -> decay(c.raw(p.freezeDecay), 0.0, false)
    }

    private fun shimmerProfessional(c: Config): LiveprogStaticPlot = when (c.focus.key) {
        p.shimmerSize.key -> {
            val scale = .55 + 1.95 * c.percent(p.shimmerSize)
            LiveprogStaticPlot("八条混响线的长度", c.caption, LiveprogPlotStyle.BARS,
                yLabel = "延迟 ms", yMaximum = 200.0,
                bars = reverbTimes.mapIndexed { i, value -> LiveprogBar("${i + 1}", value * 1000 * scale, i % 3) },
                notes = listOf("空间大小按 0.55+1.95×大小 同比例伸缩八条延迟线；这是网络时间结构，不是房间米数或测得的早期反射。"))
        }
        p.shimmerAmount.key -> {
            val amount = .65 * c.percent(p.shimmerAmount)
            LiveprogStaticPlot("混响环路中的移调配比", c.caption, LiveprogPlotStyle.BARS,
                yLabel = "环路权重 %", yMaximum = 100.0,
                bars = listOf(LiveprogBar("常规反馈", 100 * (1 - amount), 2), LiveprogBar("移调反馈", 100 * amount)),
                notes = listOf("闪烁强度在驱动中映射为最多 65% 的移调反馈权重；它不是最终输出的湿声比例，也不是每次反馈必然升高一个音程。"))
        }
        p.shimmerDiffusion.key -> {
            val g = .1 + .65 * c.percent(p.shimmerDiffusion)
            val points = buildList {
                repeat(9) { i ->
                    val value = if (i == 0) -g else (1 - g * g) * g.pow(i - 1)
                    add(LiveprogPoint(i * 3.1, 0.0)); add(LiveprogPoint(i * 3.1, value)); add(LiveprogPoint(i * 3.1, 0.0))
                }
            }
            LiveprogStaticPlot("扩散级脉冲分散", "首级全通参考 · 系数 ${n(g, 2)}", xLabel = "时间 ms", yLabel = "相对幅度",
                xMaximum = 26.0, yMinimum = -1.0, yMaximum = 1.0,
                curves = listOf(LiveprogCurve("首级 3.1 ms 全通", points)),
                notes = listOf("每声道有两个全通扩散级。此图单独展示第一条 3.1 ms 全通的冲激参考，系数=0.1+0.65×扩散；没有冒充完整混响冲激响应。"))
        }
        else -> decay(c.raw(p.shimmerDecay), c.raw(p.shimmerPredelay), true)
    }

    private fun filter(c: Config): LiveprogStaticPlot {
        val sr = 48000.0
        val requested = c.raw(c.focus)
        val pitchRatio = if (c.kind == CreativeEffectsKind.GRANULAR) 2.0.pow(c.raw(p.granularPitch) / 1200.0) else 1.0
        val cutoff = min(requested, sr * .45 / max(1.0, pitchRatio))
        val a = exp(-2 * PI * cutoff / sr)
        val highpass = c.focus.key == p.shimmerHighpass.key
        val points = List(97) { i ->
            val hz = 20.0 * 1000.0.pow(i / 96.0)
            val omega = 2 * PI * hz / sr
            val denominator = sqrt(1 + a * a - 2 * a * cos(omega))
            val magnitude = if (highpass) a * sqrt(2 - 2 * cos(omega)) / denominator else (1 - a) / denominator
            LiveprogPoint(hz, db(magnitude).coerceAtLeast(-60.0))
        }
        return LiveprogStaticPlot(if (highpass) "混响输入低切响应" else if (c.kind == CreativeEffectsKind.SHIMMER) "混响环路高频阻尼" else "效果高频截止响应",
            "48 kHz 参数参考 · ${cutoff.toInt()} Hz", xLabel = "频率 Hz", yLabel = "幅度 dB",
            xMinimum = 20.0, xMaximum = 20000.0, logarithmicX = true, yMinimum = -60.0, yMaximum = 0.0,
            curves = listOf(LiveprogCurve(if (highpass) "单次低切" else "单次滤波", points)),
            notes = listOf("按驱动的一阶离散滤波公式，以 48 kHz 计算。实际采样率会改变曲线；混响阻尼位于反馈环路，最终尾音会反复经过它。颗粒升调时截止还受防混叠上限约束。"))
    }

    private fun stereo(c: Config): LiveprogStaticPlot = if (c.kind == CreativeEffectsKind.GRANULAR) {
        val width = c.percent(p.granularWidth)
        LiveprogStaticPlot("颗粒随机声像权重", c.caption, xLabel = "声像 左 → 右", yLabel = "通道权重",
            xMinimum = -1.0, xMaximum = 1.0, yMinimum = 0.0, yMaximum = 1.5,
            regions = listOf(LiveprogRegion(-width, width)),
            curves = listOf(curve("左通道", -1.0, 1.0) { 1 - .5 * it }, curve("右通道", -1.0, 1.0, 2) { 1 + .5 * it }),
            notes = listOf("每颗粒的随机 pan 位于 ±散布范围，L/R 权重为 1∓0.5×pan；图中阴影显示候选范围，不是实时声像。"))
    } else {
        val width = c.percent(c.focus)
        LiveprogStaticPlot("中侧宽度变换", "固定纯左参考 · 宽度 ${n(width * 100, 0)}%", xLabel = "宽度 %", yLabel = "通道幅度",
            xMaximum = 100.0, yMaximum = 1.0,
            curves = listOf(curve("左输出", 0.0, 100.0) { .5 + it / 200 }, curve("右输出", 0.0, 100.0, 2) { .5 - it / 200 }),
            marks = listOf(LiveprogMark(width * 100, .5 + .5 * width)),
            notes = listOf("保持 Mid，Side 乘宽度。以纯左声源展示左右输出：0% 合为中心，100% 保持效果本身的原宽度；不是当前歌曲声源定位。"))
    }

    private fun decay(time: Double, predelay: Double, shimmer: Boolean): LiveprogStaticPlot {
        val points = buildList {
            if (predelay > 0) { add(LiveprogPoint(0.0, -60.0)); add(LiveprogPoint(predelay, -60.0)) }
            add(LiveprogPoint(predelay, 0.0))
            for (i in 1..96) add(LiveprogPoint(predelay + time * i / 96, -60.0 * i / 96))
        }
        return LiveprogStaticPlot(if (shimmer) "预延迟与尾音衰减" else "冻结频谱衰减", "${n(time / 1000)} s 衰减 60 dB",
            xLabel = "时间 ms", yLabel = "相对 dB", xMaximum = time + predelay, yMinimum = -60.0, yMaximum = 0.0,
            curves = listOf(LiveprogCurve(if (shimmer) "名义 RT60" else "频谱幅度衰减", points)),
            notes = listOf(if (shimmer) "名义 RT60 的理想衰减参考；移调反馈、阻尼、扩散和实际输入会改变实测尾声。预延迟是网络输入前的等待，网络自身传播时间另计。"
                else "冻结频谱幅度按此时间衰减 60 dB。淡入、释放、低通与干湿混合另外影响最终输出。"))
    }

    private fun mixIllustration(c: Config): EffectVisual {
        val mix = c.percent(c.focus)
        return EffectVisual("原音与效果汇合", c.caption, scene = EffectScene(listOf(
            FigureBox(.08, .07, .42, .25, "原音", colorIndex = 2),
            FigureBox(.58, .07, .92, .25, "效果"),
            FigureLine(.25, .27, .42, .6, colorIndex = 2, arrow = true, width = 1 + 4 * (1 - mix), alpha = .3 + .7 * (1 - mix)),
            FigureLine(.75, .27, .58, .6, arrow = true, width = 1 + 4 * mix, alpha = .3 + .7 * mix),
            FigureLabel("${n((1 - mix) * 100, 0)}%", .2, .43, .3, colorIndex = 2),
            FigureLabel("${n(mix * 100, 0)}%", .8, .43, .3, colorIndex = 0),
            FigureBox(.3, .63, .7, .85, "混合输出"), FigureLabel("线条宽度表示配比", .5, .94)
        )), notes = listOf("原音、效果分别沿两条路径进入同一输出，箭头粗细由干湿权重决定；没有用假想电平冒充当前音乐。"))
    }

    private fun filterIllustration(c: Config): EffectVisual {
        val cutoff = c.raw(c.focus)
        val highpass = c.focus.key == p.shimmerHighpass.key
        val retained = if (highpass) (1 - cutoff / 1100).coerceIn(0.0, 1.0) else (cutoff / 20000).coerceIn(0.0, 1.0)
        fun waveform(top: Double, after: Boolean) = List(65) { i ->
            val x = i / 64.0
            val low = sin(4 * PI * x) * if (after && highpass) retained else 1.0
            val high = sin(24 * PI * x) * .24 * if (after && !highpass) retained else .95
            FigurePoint(.08 + .84 * x, top + .06 * (low + high))
        }
        return EffectVisual(if (highpass) "滤去低频轮廓" else "柔化高频纹理", "固定复合波形示意", scene = EffectScene(listOf(
            FigureLabel("处理前", .5, .05), FigurePolyline(waveform(.22, false), colorIndex = 2),
            FigureLine(.5, .32, .5, .42, arrow = true),
            FigureBox(.16, .44, .84, .6, "${if (highpass) "低切" else "高频截止"} ${cutoff.toInt()} Hz"),
            FigureLine(.5, .62, .5, .72, arrow = true), FigurePolyline(waveform(.83, true)),
            FigureLabel("处理后示意", .5, .94)
        )), notes = listOf("上、下是固定复合波形的作用比喻，展示低频轮廓或高频细纹被减弱。严格的滤波幅频公式在左图；此图不是实测波形或时间域仿真。"))
    }

    private fun feedbackIllustration(c: Config): EffectVisual {
        val feedback = c.percent(p.granularFeedback)
        return EffectVisual("片段再次进入缓冲", c.caption, scene = EffectScene(listOf(
            FigureBox(.08, .08, .4, .3, "原音"), FigureBox(.57, .08, .92, .3, "延迟缓冲"),
            FigureLine(.42, .19, .55, .19, arrow = true),
            FigureLine(.75, .32, .75, .52, arrow = true), FigureBox(.57, .55, .92, .78, "颗粒输出"),
            FigurePolyline(listOf(FigurePoint(.55, .66), FigurePoint(.25, .66), FigurePoint(.25, .4), FigurePoint(.73, .4)),
                colorIndex = 2, width = 1 + feedback * 4, alpha = .25 + feedback * .75),
            FigureLine(.73, .4, .73, .32, colorIndex = 2, arrow = true),
            FigureLabel("${n(feedback * 100, 0)}% 回送", .34, .9, .62, colorIndex = 2)
        )), notes = listOf("反馈让已经处理的颗粒继续参与后续片段生成，形成重叠、重排的尾声；不是简单复制同一个回声。"))
    }

    private fun granularIllustration(c: Config): EffectVisual {
        val length = c.raw(p.granularGrain) / 250
        val scatter = c.raw(p.granularSpread) / 1000
        val width = c.percent(p.granularWidth)
        val reverse = c.percent(p.granularReverse)
        val pitch = c.raw(p.granularPitch) / 100
        val elements = buildList<FigureElement> {
            add(FigureLabel("原音切片", .5, .05, emphasis = true))
            add(FigurePolyline(List(65) { i -> val x = i / 64.0; FigurePoint(.08 + .84 * x, .22 + .065 * sin(x * 8 * PI)) }, colorIndex = 2))
            repeat(3) { i ->
                val x = .18 + i * .29
                add(FigureBox(x - .045, .13, x + .045, .31, alpha = .12))
                add(FigureLine(x, .32, x + (i - 1) * scatter * .07, .43, arrow = true))
                val middle = x + (i - 1) * scatter * .07
                add(FigureBox(middle - .035 - length * .04, .45, middle + .035 + length * .04, .58, colorIndex = i))
                add(FigureLine(middle, .495, middle + .035 * (1 - reverse), .495, colorIndex = i, arrow = true))
                add(FigureLine(middle, .54, middle - .035 * reverse, .54, colorIndex = 2, arrow = true))
            }
            add(FigureLabel("${n(pitch, 1)} 半音 · 倒放 ${n(reverse * 100, 0)}%", .5, .69))
            add(FigureLine(.5, .74, .5 - .34 * width, .88, arrow = true))
            add(FigureLine(.5, .74, .5 + .34 * width, .88, colorIndex = 2, arrow = true))
            add(FigureCircle(.5 - .34 * width, .89, .045, .045, colorIndex = 0, filled = true, alpha = .5))
            add(FigureCircle(.5 + .34 * width, .89, .045, .045, colorIndex = 2, filled = true, alpha = .5))
            add(FigureLabel("左", .08, .9, .12)); add(FigureLabel("右", .92, .9, .12))
        }
        return EffectVisual("切片、重排与散布", c.caption, scene = EffectScene(elements),
            notes = listOf("框宽随颗粒长度、落点随时间散布、左右范围随立体声散布变化。示意固定画三个片段；倒放只显示概率，不把图中的三个片段冒充实际随机颗粒。"))
    }

    private fun freezeIllustration(c: Config): EffectVisual {
        val blur = c.percent(p.freezeBlur)
        val pitchRatio = 2.0.pow(c.raw(p.freezePitch) / 1200)
        val envelope = c.raw(p.freezeDecay) / 60000
        val width = c.percent(p.freezeWidth)
        val attack = c.raw(p.freezeAttack) / 2000
        val release = c.raw(p.freezeRelease) / 5000
        val elements = buildList<FigureElement> {
            add(FigureLabel("捕捉一瞬，保留音色", .5, .05, emphasis = true))
            add(FigureBox(.07, .19, .3, .5, colorIndex = 2))
            repeat(5) { i ->
                val x = .1 + i * .04; val h = listOf(.04, .12, .2, .08, .035)[i]
                add(FigureLine(x, .47, x, .47 - h, colorIndex = 2, width = 2.4))
            }
            add(FigureLine(.32, .35, .45, .35, arrow = true))
            add(FigureBox(.47, .18, .91, .51, "保持频谱"))
            add(FigureLine(.54, .52, .54, .64, arrow = true))
            add(FigureLine(.84, .52, .84, .64, colorIndex = 2, arrow = true))
            repeat(3) { i ->
                val base = .72 + (i - 1) * .055 * width
                add(FigurePolyline(List(49) { j ->
                    val x = j / 48.0
                    val rise = (x / (.04 + attack * .22)).coerceAtMost(1.0)
                    val fade = ((1 - x) / (.04 + release * .22)).coerceAtMost(1.0)
                    FigurePoint(.1 + .8 * x, base + .045 * rise * fade * (1 - x * (1 - envelope) * .85) * sin(x * 6 * PI * pitchRatio))
                }, colorIndex = if (i == 1) 0 else 2, alpha = if (i == 1) 1.0 else .25 + .5 * blur))
            }
            add(FigureLabel("持续合成 · ${n(c.raw(p.freezeDecay) / 1000)} s 衰减", .5, .93))
        }
        return EffectVisual("声音快照与延展", c.caption, scene = EffectScene(elements),
            notes = listOf("不是把整段声音循环播放：捕捉幅度和相位推进信息后持续合成。示意的密度随移调、左右线条间距随宽度、尾部随衰减变化。实时冻结频谱在下方显示。"))
    }

    private fun shimmerIllustration(c: Config): EffectVisual {
        val size = .55 + .35 * c.percent(p.shimmerSize)
        val width = c.percent(p.shimmerWidth)
        val amount = .65 * c.percent(p.shimmerAmount)
        val pitch = c.raw(p.shimmerPitch) / 100
        val diffusion = c.percent(p.shimmerDiffusion)
        val damp = c.raw(p.shimmerDamping) / 20000
        val decay = c.raw(p.shimmerDecay) / 20000
        val predelay = c.raw(p.shimmerPredelay) / 250
        val elements = buildList<FigureElement> {
            add(FigureBox(.5 - size * .48, .12, .5 + size * .48, .87, filled = false))
            repeat(4) { i ->
                val radius = .12 + i * .075 * size
                add(FigureArc(.5, .77, radius * (.55 + width * .45), radius * 1.4, 190.0, 160.0,
                    colorIndex = if (i % 2 == 0) 0 else 2, alpha = (1 - i * (.23 - decay * .18)) * (.5 + damp * .5)))
            }
            if (diffusion > .05) repeat(5) { i ->
                val x = .2 + i * .15
                add(FigureLine(.5, .77, x, .4 + .045 * (i % 2), colorIndex = 2, dashed = true, alpha = diffusion * .55))
            }
            add(FigureCircle(.5, .79, .055, .055, filled = true, alpha = .7))
            if (amount > 0.0) {
                val nearSource = .7 - predelay * .15
                add(FigureLine(.5, if (pitch < 0) .27 else nearSource, .5, if (pitch < 0) nearSource else .27,
                    colorIndex = 0, arrow = pitch != 0.0, width = 1.3 + amount * 3))
            }
            add(FigureLabel(if (amount == 0.0) "常规混响尾音" else "${if (pitch >= 0) "+" else ""}${n(pitch, 1)} 半音尾音",
                .5, .05, emphasis = true))
            add(FigureLabel("原音", .5, .94))
        }
        return EffectVisual(if (amount == 0.0) "空间反射（无闪烁）" else "空间反射与移调尾音", c.caption, scene = EffectScene(elements),
            notes = listOf("房间外框随空间大小变化，反射展开随宽度变化，虚线表现扩散，箭头表现移调反馈比例。闪烁强度为0时不产生移调反馈，仅保留常规混响；左图仍可预览保存的移调设置。图形不表示真实房间尺寸、声源位置或实测反射。"))
    }

    fun liveTitle(kind: CreativeEffectsKind) = when (kind) {
        CreativeEffectsKind.GRANULAR -> "颗粒活动与生成周期"
        CreativeEffectsKind.FREEZE -> "实际冻结频谱 · 16 频带"
        CreativeEffectsKind.SHIMMER -> "移调读头与实际尾声"
    }

    fun live(kind: CreativeEffectsKind, samples: List<Sample>): EffectVisual {
        val latest = samples.lastOrNull() ?: return EffectVisual(liveTitle(kind), notes = listOf("只展示驱动确认的活动音频读数。"))
        val s = latest.runtime
        return when (kind) {
            CreativeEffectsKind.GRANULAR -> granularLive(samples, latest)
            CreativeEffectsKind.FREEZE -> freezeLive(s)
            CreativeEffectsKind.SHIMMER -> shimmerLive(samples, latest)
        }
    }

    private fun freezeLive(s: CreativeEffectsRuntime): EffectVisual {
        val maximum = max(12.0, ceil((s.spectrumDb.filter { it.isFinite() }.maxOrNull() ?: -120f) / 12.0) * 12.0)
        fun y(db: Double) = .82 - ((db.coerceIn(-120.0, maximum) + 120) / (maximum + 120)) * .65
        val elements = buildList<FigureElement> {
            add(FigureLabel("频谱幅度 · dBFS 参考", .08, .045, .88, FigureAlign.LEFT))
            listOf(-120.0, -60.0, maximum).forEach { tick ->
                add(FigureLine(.12, y(tick), .97, y(tick), colorIndex = -1, alpha = .18, width = .7))
                add(FigureLabel(n(tick, 0), .105, y(tick), .2, FigureAlign.RIGHT))
            }
            if (s.activity > 0) repeat(16) { i ->
                val value = s.spectrumDb.getOrElse(i) { -120f }.takeIf { it.isFinite() }?.toDouble() ?: -120.0
                val left = .125 + i * .0525
                add(FigureBox(left, y(value), left + .041, .82, colorIndex = if (i < 8) 0 else 2,
                    alpha = .55, rounded = false))
            }
            add(FigureLabel("低频", .13, .94, .24, FigureAlign.LEFT))
            add(FigureLabel("16 个对数频带", .54, .94, .5))
            add(FigureLabel("高频", .97, .94, .24, FigureAlign.RIGHT))
        }
        return EffectVisual(liveTitle(CreativeEffectsKind.FREEZE), "合成前频谱 · 包络 ${n(s.phase * 100.0, 0)}%", scene = EffectScene(elements),
            notes = listOf("来自驱动：16 个对数分组取双声道频点峰值。位置在移调、柔化及冻结衰减之后，逆变换、淡入释放包络、低通及干湿混合之前。不是最终输出频谱或声压级；实际采样率 ${s.sampleRate} Hz。"))
    }

    private fun granularLive(samples: List<Sample>, latest: Sample): EffectVisual {
        val s = latest.runtime
        val elements = buildList<FigureElement> {
            add(FigureLabel("实际活跃颗粒", .32, .04, .6, emphasis = true))
            listOf(0, 16, 32).forEach { count ->
                val y = .79 - count / 32.0 * .58
                add(FigureLine(.09, y, .63, y, colorIndex = -1, alpha = .18, width = .7))
                add(FigureLabel("$count", .065, y, .12, FigureAlign.RIGHT))
            }
            val visible = samples.filter { it.timeMs >= latest.timeMs - 20000 }
            val points = buildList {
                visible.forEachIndexed { i, point ->
                    val x = .63 - (latest.timeMs - point.timeMs) / 20000.0 * .54
                    val y = .79 - point.runtime.activity.coerceIn(0, 32) / 32.0 * .58
                    if (point.split) add(FigurePoint(Double.NaN, Double.NaN))
                    if (i > 0 && !point.split) add(FigurePoint(x, .79 - visible[i - 1].runtime.activity.coerceIn(0, 32) / 32.0 * .58))
                    add(FigurePoint(x, y))
                }
            }
            add(FigurePolyline(points, width = 2.0))
            add(FigureLabel("−20 s", .09, .91, .25, FigureAlign.LEFT))
            add(FigureLabel("现在", .63, .91, .2, FigureAlign.RIGHT))
            add(FigureLabel("生成周期", .82, .04, .34, emphasis = true))
            add(FigureCircle(.82, .46, .12, .24, colorIndex = -1, alpha = .2))
            add(FigureArc(.82, .46, .12, .24, -90.0, s.phase.coerceIn(0f, 1f) * 360.0, width = 3.5))
            add(FigureLabel("${s.activity} 粒", .82, .46, .32, emphasis = true))
            add(FigureLabel("${n(s.aux / 1000.0)} ms", .82, .8, .34))
            add(FigureLabel("每次生成间隔", .82, .94, .35))
        }
        return EffectVisual(liveTitle(CreativeEffectsKind.GRANULAR), "颗粒数量与相位来自驱动 · 最近 20 秒", scene = EffectScene(elements),
            notes = listOf("左边记录每次读取的真实活跃颗粒数，右边圆环为驱动生成周期相位。轮询约 5 Hz，可能跨过多个生成事件；没有推算每颗粒的音量、位置或倒放方向。"))
    }

    private fun shimmerLive(samples: List<Sample>, latest: Sample): EffectVisual {
        val s = latest.runtime
        val phase = s.phase.coerceIn(0f, 1f).toDouble()
        val second = (phase + .5) % 1.0
        val weight = .5 - .5 * cos(phase * 2 * PI)
        val level = ((s.wetPeakDb + 90) / 102).coerceIn(0f, 1f).toDouble()
        val elements = buildList<FigureElement> {
            add(FigureLabel("移调双读头交接", .33, .045, .62, emphasis = true))
            add(FigureCircle(.32, .4, .19, .22, colorIndex = -1, alpha = .25))
            listOf(phase to weight, second to (1 - weight)).forEachIndexed { i, (ph, gain) ->
                val angle = ph * 2 * PI - PI / 2
                val x = .32 + .19 * cos(angle); val y = .4 + .22 * sin(angle)
                add(FigureLine(.32, .4, x, y, colorIndex = i * 2, width = 1.1 + gain * 2))
                add(FigureCircle(x, y, .025 + gain * .018, .035 + gain * .025, colorIndex = i * 2, filled = true, alpha = .35 + .65 * gain))
            }
            add(FigureLabel("${n(s.aux / 100.0, 1)} 半音", .32, .4, .36))
            add(FigureLabel("A ${n(weight * 100, 0)}% · B ${n((1 - weight) * 100, 0)}%", .32, .7, .64))
            add(FigureLabel("尾声峰值", .83, .045, .3, emphasis = true))
            add(FigureBox(.78, .17, .88, .64, colorIndex = -1, filled = false))
            add(FigureBox(.78, .64 - .47 * level, .88, .64, colorIndex = 2, alpha = .65, rounded = false))
            add(FigureLabel("${n(s.wetPeakDb.toDouble())} dBFS", .83, .73, .31))
            val visible = samples.filter { it.timeMs >= latest.timeMs - 20000 }
            visible.forEach { point ->
                val x = .08 + (1 - (latest.timeMs - point.timeMs) / 20000.0) * .84
                val power = ((point.runtime.wetPeakDb + 90) / 102).coerceIn(0f, 1f).toDouble()
                add(FigureLine(x, .88, x, .88 - .09 * power, colorIndex = 2, width = 2.2, alpha = .18 + .82 * power))
            }
            add(FigureLabel("−20 s", .07, .94, .2, FigureAlign.LEFT))
            add(FigureLabel("尾声峰值历史", .5, .94, .48))
            add(FigureLabel("现在", .94, .94, .16, FigureAlign.RIGHT))
        }
        return EffectVisual(liveTitle(CreativeEffectsKind.SHIMMER), "真实读头相位 · 圆点大小对应当前窗权重", scene = EffectScene(elements),
            notes = listOf("两个读头相差半周期，按 Hann 窗交接，位置由驱动当前相位决定。右侧与底部色带只使用实际湿声峰值（混合前），色带范围 −90 至 +12 dBFS。不是频谱、完整波形或八条混响线各自的电平。"))
    }

    private val reverbTimes = listOf(.0297, .0371, .0411, .0437, .0531, .0617, .0719, .0793)
    private fun curve(label: String, from: Double, to: Double, color: Int = 0, f: (Double) -> Double) =
        LiveprogCurve(label, List(97) { i -> val x = from + (to - from) * i / 96; LiveprogPoint(x, f(x)) }, color)
    private fun db(value: Double) = 20 * log10(max(value, .000001))
    private fun n(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", value)
}
