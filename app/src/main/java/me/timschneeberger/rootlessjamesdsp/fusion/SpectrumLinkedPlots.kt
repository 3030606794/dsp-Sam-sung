package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogBar
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogEdge
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogMark
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogNode
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPoint
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Read-only views of the existing exciter snapshot. No parameter ownership or audio execution. */
internal object SpectrumLinkedPlots {
    enum class Group { BAND, MIX, COLOR, OVERSAMPLING }
    data class Focus(val key: String, val group: Group, val leftTitle: String,
        val rightTitle: String, val liveTitle: String = "实时激励能量 · 最近20秒")

    private val base = HighFrequencyParameters
    private val advanced = HighFrequencyAdvancedParameters
    val relevantKeys: Set<String> = base.spectrum.map { it.key }.toSet() +
        base.LP_AUTO + advanced.nonlinear.map { it.key } + advanced.OVERSAMPLING

    fun focus(key: String?): Focus {
        val normalized = when {
            key == "v_spectrum_amount" -> base.spectrumAmount.key
            key != null && key in relevantKeys -> key
            else -> base.spectrumFrequency.key
        }
        return when (normalized) {
            base.spectrumAmount.key, base.spectrumOutput.key ->
                Focus(normalized, Group.MIX, "支路与输出倍率", "原声与激励混合")
            advanced.drive.key, advanced.evenMix.key, advanced.highOrder.key ->
                Focus(normalized, Group.COLOR, "单音谐波模型", "瞬时染色传递")
            advanced.OVERSAMPLING ->
                Focus(normalized, Group.OVERSAMPLING, "采样与滤波参考", "原声与支路对齐")
            else -> Focus(normalized, Group.BAND, "选频与工作包络", "原声保留与选频")
        }
    }

    fun pair(model: SpectrumDashboardModel, key: String?): LiveprogChartPair {
        val selected = focus(key)
        val pair = when (selected.group) {
            Group.BAND -> band(model, selected)
            Group.MIX -> mix(model, selected)
            Group.COLOR -> color(model, selected)
            Group.OVERSAMPLING -> oversampling(model, selected)
        }
        return LiveprogChartPair(pair.professional.copy(inactive = !model.enabled),
            pair.illustration.copy(inactive = !model.enabled))
    }

    private fun band(model: SpectrumDashboardModel, focus: Focus): LiveprogChartPair {
        val subtitle = when (focus.key) {
            base.highpassQ.key -> "输入高通 Q ${number(model.hpQ)}"
            base.lowpassQ.key -> "输出低通 Q ${number(model.lpQ)}"
            base.lowpassFrequency.key, base.LP_AUTO ->
                "低通 ${hz(model.lpHz)}${if (model.automaticLowpass) " · 自动" else " · 手动"}"
            else -> "高通 ${hz(model.hpHz)} · Q ${number(model.hpQ)}"
        }
        val low = -90.0
        val high = max(12.0, listOf(model.highpassDb, model.lowpassDb, model.workEnvelopeDb)
            .flatMap { it.asIterable() }.filter { it.isFinite() }.maxOrNull()?.let { min(60.0, it + 3) } ?: 12.0)
        val left = LiveprogStaticPlot(focus.leftTitle, subtitle,
            xLabel = "Hz", yLabel = "dB", xMinimum = model.frequencies.first(), xMaximum = model.frequencies.last(),
            yMinimum = low, yMaximum = high, logarithmicX = true,
            curves = listOf(
                sampledCurve("输入高通", model.frequencies, model.highpassDb, 1, low),
                sampledCurve("输出低通", model.frequencies,
                    DoubleArray(model.lowpassDb.size) { model.lowpassDb[it] - 20 * log10(model.outputGain) }, 2, low),
                sampledCurve("支路包络", model.frequencies, model.workEnvelopeDb, 0, low)),
            marks = listOf(LiveprogMark(model.hpHz, 20 * log10(model.hpQ), "高通", 1),
                LiveprogMark(model.lpHz, 20 * log10(model.lpQ), "低通", 2)),
            notes = common(model) + listOf(
                "高通曲线来自现有 highpassDb；纯低通从 lowpassDb 去除公共输出衰减。Q 决定转折处形状，不把所有 Q 的截止点都当作 −3 dB；两个二阶滤波器在各自配置频率的增益均为20log10(Q)，HP/LP点按此标出。",
                "第三条曲线为现有 HP×LP×激励支路倍率×输出倍率的线性工作包络。非线性位于高通和低通之间，因此这不是模块总频响，也不是音乐实测。图底为−90dB；强度为零时模型为−∞，按图底显示。",
                "HP ${model.hpHz} Hz / Q ${model.hpQ}；LP ${model.lpHz} Hz / Q ${model.lpQ}。${if (model.automaticLowpass) "自动低通为 Fs/2−2000 Hz。" else "手动低通受现有模型的采样率安全边界限制。"}",
                "若高通频率高于低通，仍保留两个原配置及真实滤波器参考，不排序回写，也不画出不存在的理想矩形通带。"))
        val rightSubtitle = when (focus.key) {
            base.highpassQ.key -> "输入选频 · Q ${number(model.hpQ)}"
            base.lowpassQ.key -> "支路限带 · Q ${number(model.lpQ)}"
            base.LP_AUTO -> if (model.automaticLowpass) "自动截止 · Fs/2−2kHz" else "手动截止 · ${hz(model.lpHz)}"
            else -> "原声直达 · 高频支路并行"
        }
        val right = LiveprogStaticPlot(focus.rightTitle, rightSubtitle, LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .12, .16, 1),
                LiveprogNode("out", "混合输出", .88, .16, 0),
                LiveprogNode("hp", "高通 ${hz(model.hpHz)}", .12, .78, 1),
                LiveprogNode("shape", "生成谐波", .5, .78, 0),
                LiveprogNode("lp", "低通 ${hz(model.lpHz)}", .88, .78, 2)),
            edges = listOf(LiveprogEdge("in", "out", "保留原声；目标对齐 ${model.latencySamples} 主采样", 1),
                LiveprogEdge("in", "hp", "输入高通 Q=${model.hpQ}", 1),
                LiveprogEdge("hp", "shape", "高通后进入整形；${model.effectiveFactor}×处理", 0),
                LiveprogEdge("shape", "lp", "整形结果×${model.branchGain}；低通 Q=${model.lpQ}", 2),
                LiveprogEdge("lp", "out", "支路与原声相加后统一×${model.outputGain}", 0)),
            notes = common(model) + listOf(
                "箭头对应正常处理的原生结构：原声保留，另一路高通→非线性整形→支路强度→低通，随后相加并施加公共输出衰减。",
                "输入高通只筛选用于生成激励的材料，不删除原声的低频；输出低通只限激励支路，不能理解成把整个输出高频切掉。",
                "非线性可以产生新频率，所以不能把 HP 与 LP 两个边界之间涂成全部生成声的硬边界。当前边界和 Q 的详细值见左图长按说明。"))
        return LiveprogChartPair(left, right)
    }

    private fun mix(model: SpectrumDashboardModel, focus: Focus): LiveprogChartPair {
        val contribution = model.branchGain * model.outputGain
        val left = LiveprogStaticPlot(focus.leftTitle,
            "1原声 · 2激励 · 3最终",
            LiveprogPlotStyle.BARS, yLabel = "倍率×", yMinimum = 0.0, yMaximum = 5.6,
            bars = listOf(LiveprogBar("原声最终乘数", model.outputGain, 1),
                LiveprogBar("激励支路强度", model.branchGain, 2),
                LiveprogBar("激励最终乘数", contribution, 0)),
            notes = common(model) + listOf(
                "1：原声在混合后乘公共输出系数 ${model.outputGain}；2：激励支路先乘强度系数 ${model.branchGain}；3：支路最终系数为两者乘积 ${contribution}。",
                "三个数都是乘法系数，不能相加解释为总声能或实际响度。激励整形本身、滤波器和当前音乐均会影响真实电平。",
                "正常输出=(对齐原声＋低通(整形结果×支路强度))×公共输出系数。支路强度归零时原声仍按公共输出系数播放。",
                "输出衰减属于相加后的公共末级，同时影响原声与激励。纯湿声试听会移除原声；这两张静图展示保存参数下的正常处理结构，不冒充当前试听状态。"))
        val right = LiveprogStaticPlot(focus.rightTitle,
            "原声＋激励 → 公共输出衰减", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("dry", "原声输入", .12, .18, 1),
                LiveprogNode("mix", "相加", .88, .18, 0),
                LiveprogNode("wet", "整形支路", .12, .78, 2),
                LiveprogNode("amount", "激励×${number(model.branchGain)}", .5, .78, 0),
                LiveprogNode("out", "输出 ${number(model.outputDb)}dB", .88, .78, 0)),
            edges = listOf(LiveprogEdge("dry", "mix", "原声正常保留；经${model.latencySamples}主采样的对齐补偿", 1),
                LiveprogEdge("dry", "wet", "原始输入的独立高通／整形支路", 2),
                LiveprogEdge("wet", "amount", "支路目标系数 ${model.branchGain}", 0),
                LiveprogEdge("amount", "mix", "支路经输出低通后参与相加", 2),
                LiveprogEdge("mix", "out", "公共系数 ${model.outputGain}，同时缩放两路", 0)),
            notes = left.notes + listOf("支路方框为简写：高通与整形先处理原始输入，支路强度后接低通；对齐原声只为回混匹配 FIR 延迟。"))
        return LiveprogChartPair(left, right)
    }

    private fun color(model: SpectrumDashboardModel, focus: Focus): LiveprogChartPair {
        val floor = -120.0
        val top = 12.0
        val high = model.frequencies.last()
        val harmonicCurves = listOf(0, 1).map { parity ->
            val points = ArrayList<LiveprogPoint>()
            model.harmonicFrequencies.forEachIndexed { index, frequency ->
                val level = model.harmonicPeakDb[index]
                if (index % 2 == parity && frequency <= high && level.isFinite()) {
                    points += LiveprogPoint(frequency, floor)
                    points += LiveprogPoint(frequency, level.coerceIn(floor, top))
                    points += LiveprogPoint(Double.NaN, Double.NaN)
                }
            }
            LiveprogCurve(if (parity == 0) "奇次模型" else "偶次模型", points, if (parity == 0) 0 else 2)
        }
        val parameter = when (focus.key) {
            advanced.drive.key -> "输入驱动 ${number(model.driveDb)} dB"
            advanced.evenMix.key -> "偶次系数 ${number(model.evenMix * 100)}%"
            else -> "高阶系数 ${number(model.highOrder * 100)}%"
        }
        val left = LiveprogStaticPlot(focus.leftTitle, "1FS／1kHz 整形输入探针",
            xLabel = "Hz", yLabel = "dBFS", xMinimum = model.frequencies.first(), xMaximum = high,
            yMinimum = floor, yMaximum = top, logarithmicX = true, curves = harmonicCurves,
            notes = common(model) + listOf(
                "专业参考来自现有 harmonicPeakDb：1 kHz、1 FS 余弦在整形级输入端注入的离线数学模型，随后包含当前驱动、Chebyshev 整形、DC 阻断、过采样滤波、输出低通、支路强度及公共输出系数。",
                "探针位于输入高通之后，不是从模块输入送进的 1 kHz，也不是当前音乐、当前 FFT 峰或实际 THD 测量。",
                "H1 为基波；H2…H10 为各阶模型分量。绘图区止于主采样率 Nyquist 前100Hz，超出绘图区的阶次不显示；−∞代表该模型分量为零，图的可见底为 −120 dBFS。",
                "${parameter}；当前各阶模型：" + model.harmonicPeakDb.mapIndexed { i, value ->
                    "H${i + 1}=${if (value == Double.NEGATIVE_INFINITY) "−∞" else value.toString()} dBFS"
                }.joinToString("；")))
        val polynomial = polynomial(model.weights)
        val transfer = List(201) { i ->
            val input = -1.0 + i / 100.0
            var shapedInput = input
            if (!model.legacyPath) shapedInput =
                (input * model.drive / (1 + max(0.0, model.drive - 1) * abs(input))).coerceIn(-1.0, 1.0)
            var output = polynomial.last().toDouble()
            for (j in polynomial.lastIndex - 1 downTo 0) output = polynomial[j] + shapedInput * output
            LiveprogPoint(input, output)
        }
        val right = LiveprogStaticPlot(focus.rightTitle, parameter,
            xLabel = "输入FS", yLabel = "输出FS", xMinimum = -1.0, xMaximum = 1.0,
            yMinimum = -.25, yMaximum = .25, curves = listOf(LiveprogCurve("瞬时整形", transfer)),
            notes = common(model) + listOf(
                "曲线只展示整形级的瞬时映射 y=P(g(x))。x 是该级当前输入幅度，不是经过整个模块后的时间波形；未把高通、低通、DC 阻断或 FIR 的记忆性藏进单条无记忆曲线。",
                "${if (model.legacyPath) "原始路径不应用新增输入驱动压缩。" else "驱动函数 g(x)=clip(x·drive/(1+max(0,drive−1)·|x|),−1,1)。"}drive=${model.drive}。",
                "多项式系数使用现有模型相同的 Float Chebyshev 变换顺序。H1 权重为0.02，H2为0.02×偶次比例；H3…H10乘高阶比例，其中偶数阶再乘偶次比例。",
                "权重及比例是整形控制系数，不是当前音乐的谐波能量占比；输入驱动与后续滤波也会改变最终单音谱。",
                "当前 T1…T10 权重：${model.weights.joinToString()}。纵轴固定 ±0.25 FS，不随旋钮缩放曲线以掩盖幅度变化。"))
        return LiveprogChartPair(left, right)
    }

    private fun oversampling(model: SpectrumDashboardModel, focus: Focus): LiveprogChartPair {
        val nyquist = model.rate * .5
        val upper = model.rate * 2.0
        val frequencies = DoubleArray(192) { i -> 20.0 * (upper / 20).pow(i / 191.0) }
        val curves = listOf(1, 2, 4).mapIndexed { index, factor ->
            val taps = taps(factor)
            val points = frequencies.map { frequency ->
                if (frequency > nyquist * factor) LiveprogPoint(Double.NaN, Double.NaN)
                else {
                    val magnitude = if (factor == 1) 1.0 else firMagnitude(taps, frequency, model.rate * factor.toDouble())
                    LiveprogPoint(frequency, if (magnitude > 0) max(-120.0, 20 * log10(magnitude)) else -120.0)
                }
            }
            LiveprogCurve("${factor}×${if (factor == model.effectiveFactor) " 当前" else " 参考"}", points, index,
                dashed = factor != model.effectiveFactor)
        }
        val left = LiveprogStaticPlot(focus.leftTitle, "当前 ${model.effectiveFactor}× · Fs ${hz(model.rate.toDouble())}",
            xLabel = "Hz", yLabel = "FIR dB", xMinimum = 20.0, xMaximum = upper,
            yMinimum = -120.0, yMaximum = 3.0, logarithmicX = true, curves = curves,
            marks = listOf(1, 2, 4).mapIndexed { i, factor ->
                LiveprogMark(nyquist * factor, 0.0, "${factor}×上限", i)
            }, notes = common(model) + listOf(
                "三种处理率的内部 Nyquist 上限依次为 ${nyquist}、${nyquist * 2}、${nyquist * 4} Hz；最终输出的主采样率上限始终为 ${nyquist} Hz。",
                "1×不使用过采样 FIR；2×和4×的线性参考直接按现有模型相同的 Blackman 窗 sinc taps 构造，长度32×factor+1、归一化截止0.45/factor。",
                "曲线为单个过采样 FIR 的幅度响应参考，不是非线性全链路频响，不把两侧 FIR 简单相乘冒充非线性系统。过采样降低混叠风险，不能承诺任意输入绝对无混叠。",
                "原路径／新版1×的区别由驱动、奇偶和高阶设置决定；本组只对照倍率、FIR和目标延迟，不更改原路径选择。"))
        val right = LiveprogStaticPlot(focus.rightTitle, "1原声 · 2激励 · 同延时",
            LiveprogPlotStyle.BARS, yLabel = "主采样", yMinimum = 0.0, yMaximum = 40.0,
            bars = listOf(LiveprogBar("原声补偿延迟", model.latencySamples.toDouble(), 1),
                LiveprogBar("激励过采样 FIR 群延迟", model.latencySamples.toDouble(), 0)),
            notes = common(model) + listOf(
                "1 对应原声补偿，2 对应激励支路过采样 FIR 延迟。1×两者均为0；2×与4×均为32个主采样，不是32个内部高采样率采样。",
                "当前目标 ${model.latencySamples} 主采样＝${model.latencySamples * 1000.0 / model.rate} ms。两路在正常回混前对齐，而不是随便把一条波形向后挪来装作实时效果。",
                "这是配置目标；倍率切换期间原驱动有预热及路径交叉淡化，实际活动路径以原生运行状态为准。原滤波与声音算法保持原样。"))
        return LiveprogChartPair(left, right)
    }

    private fun common(model: SpectrumDashboardModel): List<String> = listOf(
        "${if (model.actualRate) "实际" else "参考"}处理率 ${model.rate} Hz；${if (model.enabled) "模块已启用" else "关闭时保留保存参数的静态预览"}。静图只读参数，不向驱动写入或执行试听。")

    private fun sampledCurve(label: String, frequencies: DoubleArray, values: DoubleArray, color: Int, floor: Double) =
        LiveprogCurve(label, frequencies.indices.map { i ->
            LiveprogPoint(frequencies[i], values[i].let { if (it == Double.NEGATIVE_INFINITY) floor else it.coerceAtLeast(floor) })
        }, color)

    /** Identical coefficient construction to SpectrumDashboardModel; presentation calculations only. */
    private fun polynomial(weights: FloatArray): FloatArray {
        val input = FloatArray(11); val previous = FloatArray(11); val result = FloatArray(11)
        var sum = 0f; weights.forEach { sum += abs(it) }; val scale = if (sum > 1f) 1f / sum else 1f
        weights.forEachIndexed { i, value -> input[i + 1] = value * scale }; result[10] = input[10]
        for (i in 2..10) {
            for (j in 0 until i) {
                val saved = previous[i - j]; previous[i - j] = result[i - j]
                result[i - j] = result[i - j - 1] * 2f - saved
            }
            val saved = input[11 - i] - previous[0]; previous[0] = result[0]; result[0] = saved
        }
        for (i in 1..10) result[11 - i] = result[10 - i] - previous[11 - i]
        result[0] = input[0] / 2f - previous[0]
        return result
    }

    private fun taps(factor: Int): DoubleArray {
        if (factor == 1) return doubleArrayOf(1.0)
        val n = 32 * factor + 1; val center = (n - 1) * .5; val cutoff = .45 / factor
        val coefficients = DoubleArray(n) { i ->
            val t = i - center
            val sinc = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
            sinc * (.42 - .5 * cos(2 * PI * i / (n - 1)) + .08 * cos(4 * PI * i / (n - 1)))
        }
        val sum = coefficients.sum()
        return coefficients.map { it / sum }.toDoubleArray()
    }

    private fun firMagnitude(taps: DoubleArray, frequency: Double, rate: Double): Double {
        var real = 0.0; var imaginary = 0.0
        taps.forEachIndexed { index, value ->
            val angle = -2 * PI * frequency * index / rate
            real += value * cos(angle); imaginary += value * sin(angle)
        }
        return hypot(real, imaginary)
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun hz(value: Double): String = if (value >= 1000) "${number(value / 1000)}k" else "${number(value)}Hz"
}
