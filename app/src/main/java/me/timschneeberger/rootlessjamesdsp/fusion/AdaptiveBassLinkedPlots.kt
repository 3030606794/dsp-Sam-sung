package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogBar
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogMark
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPoint
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogRegion
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/** Immutable display values, read through the original packet's unit and range normalization. */
internal data class AdaptiveBassPlotConfiguration(
    val rate: Int, val actualRate: Boolean, val enabled: Boolean,
    val lowerHz: Double, val upperHz: Double, val maxGainDb: Double, val q: Double,
    val trackingMs: Double, val riseMs: Double, val fallMs: Double,
) {
    val analysisFactor: Int get() = floor(rate / 500.0 + .5).toInt().coerceAtLeast(1)
    val analysisRate: Double get() = rate.toDouble() / analysisFactor
    val analysisStepMs: Double get() = analysisFactor * 1000.0 / rate
}

/** Parameter-only views of JamesDSP DBB, unrelated to the separate ViPER bass module. */
internal object AdaptiveBassLinkedPlots {
    data class Focus(val key: String, val leftTitle: String, val rightTitle: String)
    private val p = BassParameters
    val relevantKeys: Set<String> = p.dynamic.map { it.key }.toSet()

    fun focus(key: String?): Focus {
        val normalized = when {
            key == p.LEGACY_MAX_GAIN || key == "bass_legacy_gain_ui" -> p.maxGain.key
            key != null && key in relevantKeys -> key
            else -> p.maxGain.key
        }
        return when (normalized) {
            p.tracking.key -> Focus(normalized, "频率跟随阶跃", "跟上目标的速度")
            p.rise.key, p.fall.key -> Focus(normalized, "增益上升与回落", "上升与回落时间")
            else -> Focus(normalized, "增强形状参数参考", "允许跟踪的频段")
        }
    }

    fun configuration(values: Map<String, *>, enabled: Boolean, rate: Int, actualRate: Boolean): AdaptiveBassPlotConfiguration {
        val packet = p.dynamicPacket(values)
        val validRate = rate in 8000..768000
        return AdaptiveBassPlotConfiguration(if (validRate) rate else 48000, actualRate && validRate, enabled,
            lowerHz = packet[1].toDouble(), upperHz = packet[2].toDouble(), maxGainDb = packet[0] / 10.0,
            q = packet[3] / 100.0, trackingMs = packet[4].toDouble(), riseMs = packet[5].toDouble(), fallMs = packet[6].toDouble())
    }

    fun pair(config: AdaptiveBassPlotConfiguration, key: String?): LiveprogChartPair {
        val selected = focus(key)
        val result = when (selected.key) {
            p.tracking.key -> tracking(config, selected)
            p.rise.key, p.fall.key -> gainTime(config, selected)
            else -> peak(config, selected)
        }
        val context = listOf("JamesDSP自适应低频增强；${if (config.actualRate) "实际" else "参考"}处理率${config.rate}Hz；" +
            if (config.enabled) "保存参数模型。" else "模块关闭时保留保存参数预览。",
            "这两张静图是明确假设条件下的参数参考，不是当前音乐、当前峰值、实时跟踪频率或实际增益。只有下方频率历史和增益读数来自只读原生状态。",
            "原生目标增益=min(最大增益,max(0,20log10((选中峰值＋eps)/(九个频格的最小值＋eps))))；最大增益是上限，不能当作当前一直施加的增益。",
            "分析先将左右平均降采样到${config.analysisRate}Hz，16点FHT形成九个候选频格；相邻间隔${config.analysisRate / 16}Hz。更新间隔${config.analysisStepMs}ms。",
            "默认0…250Hz沿用原九格选峰；自定义范围还会比较插值边界。频率和增益按分析更新时刻平滑，当前Q另以15ms时间常数靠近保存Q。图不向原算法写值。")
        fun present(plot: LiveprogStaticPlot) = plot.copy(inactive = !config.enabled,
            subtitle = if (config.enabled) plot.subtitle else "关闭预览 · ${plot.subtitle}", notes = context + plot.notes)
        return LiveprogChartPair(present(result.professional), present(result.illustration))
    }

    private fun peak(config: AdaptiveBassPlotConfiguration, focus: Focus): LiveprogChartPair {
        val curves = listOf(config.lowerHz to "下限中心假设", config.upperHz to "上限中心假设")
            .mapIndexed { index, (center, label) ->
                val frequencies = (List(241) { it * 300.0 / 240 } + center).distinct().sorted()
                LiveprogCurve(label, frequencies.map { frequency ->
                    LiveprogPoint(frequency, peakDb(frequency, center, config.q, config.maxGainDb, config.rate))
                }, index)
            }
        val subtitle = if (focus.key == p.maxGain.key) "最大${n(config.maxGainDb)}dB · Q${n(config.q)}" else
            "${n(config.lowerHz)}–${n(config.upperHz)}Hz · Q${n(config.q)}"
        val notes = listOf("两条曲线分别假设稳定跟踪频率恰好为下限${config.lowerHz}Hz或上限${config.upperHz}Hz，且目标增益已经达到保存上限${config.maxGainDb}dB。它们是两个备选场景，不是同时放置两个滤波器。",
            "未取范围中点冒充实际跟踪频率。当前真正跟踪哪个峰只看下方原生频率读数。图中的中心随保存边界变化，不是根据音乐推测。",
            "形状按原生状态变量Peak滤波器的稳定数学传递H=1+(G−1)×UBP构造，G=10^(最大dB/20)，g=tan(π×假设中心/Fs)、u=tan(π×横轴频率/Fs)。",
            "|H|²=((g²−u²)²+(G×g×u/Q)²)/((g²−u²)²+(g×u/Q)²)。它是原系数方程的稳态参考，不是旧归一化钟形公式或逐浮点位实测。",
            "Q=${config.q}控制峰形集中程度。不能仅用中心/Q或对称阴影声称精确−3dB带宽，更不能称为音乐实际峰宽。",
            "中心为0Hz时g=0，原Peak支路在此稳定假设下没有有限宽度增强峰，按0dB画出；不虚构一个以0Hz为中心的钟形。最大增益0时两曲线自然归零。",
            "色区只标跟踪候选允许范围，不表示增强响应在边界外一定为0。原生Peak响应仍有过渡和尾部。")
        val left = LiveprogStaticPlot(focus.leftTitle, subtitle,
            xLabel = "Hz", yLabel = "增益dB", xMinimum = 0.0, xMaximum = 300.0,
            yMinimum = 0.0, yMaximum = 15.0, curves = curves,
            regions = listOf(LiveprogRegion(config.lowerHz, config.upperHz, "允许跟踪范围", 0)),
            marks = listOf(LiveprogMark(config.lowerHz, if (config.lowerHz > 0) config.maxGainDb else 0.0, "下限假设", 0),
                LiveprogMark(config.upperHz, config.maxGainDb, "上限假设", 1)), notes = notes)
        val right = LiveprogStaticPlot(focus.rightTitle, "${n(config.lowerHz)}–${n(config.upperHz)}Hz · 上限${n(config.maxGainDb)}dB",
            LiveprogPlotStyle.BANDS, xLabel = "Hz", yLabel = "可选", xMinimum = 0.0, xMaximum = 250.0,
            yMinimum = 0.0, yMaximum = 1.0,
            curves = listOf(LiveprogCurve("可选候选范围", listOf(LiveprogPoint(0.0, 0.0),
                LiveprogPoint(config.lowerHz, 0.0), LiveprogPoint(config.lowerHz, 1.0),
                LiveprogPoint(config.upperHz, 1.0), LiveprogPoint(config.upperHz, 0.0), LiveprogPoint(250.0, 0.0)))),
            regions = listOf(LiveprogRegion(config.lowerHz, config.upperHz, "允许", 0)),
            notes = notes + listOf("直观图高度1仅表示此处允许候选频率，0表示范围外；不是音频增益、滤波通带或能量。边界${config.lowerHz}/${config.upperHz}Hz会约束平滑后的跟踪结果。",
                "增益上限${config.maxGainDb}dB、保存Q=${config.q}；具体峰形见左图的两个命名假设。"))
        return LiveprogChartPair(left, right)
    }

    private fun tracking(config: AdaptiveBassPlotConfiguration, focus: Focus): LiveprogChartPair {
        val times = times(5000.0)
        fun frequency(time: Double, tau: Double) = config.lowerHz +
            (config.upperHz - config.lowerHz) * progress(config, time, tau)
        val notes = listOf("假设t=0时检测目标突然从保存下限${config.lowerHz}Hz变到上限${config.upperHz}Hz，且此后目标保持不变；这不是实际音乐中的自动峰值轨迹。",
            "当前跟随时间常数${config.trackingMs}ms；灰虚线仅把时间改为默认48ms，其余边界条件相同，用来隔离快慢差异。",
            "原生每个分析时刻更新f←f+α(目标−f)，α=−expm1(−dt/τ)，dt=${config.analysisStepMs}ms。图按完成的离散更新次数计算，不把连续音频采样率误当分析更新率。",
            "一个时间常数约完成63.2%的目标差；离散采样时刻会有差别，不是到该时刻已经100%跟到。",
            "横轴固定0.1…5000ms对数刻度，纵轴固定0…250Hz。上下限是明确假设的起点/终点，没有使用中点伪装实时跟踪值。")
        val left = LiveprogStaticPlot(focus.leftTitle, "跟随${n(config.trackingMs)}ms · 假设阶跃",
            xLabel = "ms", yLabel = "Hz", xMinimum = .1, xMaximum = 5000.0,
            yMinimum = 0.0, yMaximum = 250.0, logarithmicX = true,
            curves = listOf(LiveprogCurve("当前跟随", times.map { LiveprogPoint(it, frequency(it, config.trackingMs)) }, 0),
                LiveprogCurve("默认48ms", times.map { LiveprogPoint(it, frequency(it, 48.0)) }, 1, true),
                LiveprogCurve("假设目标", listOf(LiveprogPoint(.1, config.upperHz), LiveprogPoint(5000.0, config.upperHz)), 2, true)), notes = notes)
        val checkpoints = listOf(20.0, 100.0, 500.0)
        val right = LiveprogStaticPlot(focus.rightTitle, "1=20 · 2=100 · 3=500ms", LiveprogPlotStyle.BARS,
            yLabel = "到达%", yMinimum = 0.0, yMaximum = 100.0,
            bars = checkpoints.mapIndexed { i, time -> LiveprogBar("经过${time}ms完成的目标差", progress(config, time, config.trackingMs) * 100, i) },
            notes = notes + checkpoints.mapIndexed { i, time ->
                "${i + 1}：${time}ms时完成${progress(config, time, config.trackingMs) * 100}%的频率差，假设跟随值${frequency(time, config.trackingMs)}Hz。"
            })
        return LiveprogChartPair(left, right)
    }

    private fun gainTime(config: AdaptiveBassPlotConfiguration, focus: Focus): LiveprogChartPair {
        val times = times(25000.0)
        fun rise(time: Double) = config.maxGainDb * progress(config, time, config.riseMs)
        fun fall(time: Double) = config.maxGainDb * (1 - progress(config, time, config.fallMs))
        val notes = listOf("上升曲线假设目标从0跃至保存最大${config.maxGainDb}dB；回落曲线是独立场景，假设目标从该最大值降回0。不是同一段实际音乐的两条增益历史。",
            "原生增益在dB域向谱对比度给出的目标跟随；上升用${config.riseMs}ms，下降用${config.fallMs}ms，均在降采样分析更新时刻应用。",
            "横轴固定0.1…25000ms对数刻度，纵轴固定0…15dB。最大增益0时两曲线均为0，时间常数仍保留在右侧。",
            "时间常数只控制接近目标的快慢，不表示固定音频延迟，也不改变原生固定对齐延迟。调时间不会重新清空音频或挪动声道。",
            "下方实时区仍只显示频率历史，实际当前增益保留为数值；这里是参数阶跃静图，不是重新添加dB实时历史图。")
        val left = LiveprogStaticPlot(focus.leftTitle,
            if (focus.key == p.rise.key) "上升${n(config.riseMs)}ms · 假设目标" else "回落${n(config.fallMs)}ms · 假设目标",
            xLabel = "ms", yLabel = "增益dB", xMinimum = .1, xMaximum = 25000.0,
            yMinimum = 0.0, yMaximum = 15.0, logarithmicX = true,
            curves = listOf(LiveprogCurve("0→最大上升", times.map { LiveprogPoint(it, rise(it)) }, 0),
                LiveprogCurve("最大→0回落", times.map { LiveprogPoint(it, fall(it)) }, 2)), notes = notes)
        val right = LiveprogStaticPlot(focus.rightTitle, "1上升${n(config.riseMs)} · 2回落${n(config.fallMs)}ms",
            LiveprogPlotStyle.BARS, yLabel = "ms", yMinimum = 0.0, yMaximum = 5000.0,
            bars = listOf(LiveprogBar("增益上升时间常数", config.riseMs, 0),
                LiveprogBar("增益回落时间常数", config.fallMs, 2)), notes = notes)
        return LiveprogChartPair(left, right)
    }

    /** Native SVF Peak's ideal steady coefficient equations, not an RBJ/normalized bell shortcut. */
    private fun peakDb(frequency: Double, center: Double, q: Double, gainDb: Double, rate: Int): Double {
        if (center <= 0 || frequency <= 0 || gainDb <= 0) return 0.0
        val g = tan(PI * center / rate)
        val u = tan(PI * frequency / rate)
        val real = g * g - u * u
        val imaginary = g * u / q
        val gain = 10.0.pow(gainDb / 20)
        val denominator = real * real + imaginary * imaginary
        if (denominator <= 0) return 0.0
        return (10 * log10((real * real + gain * gain * imaginary * imaginary) / denominator)).coerceIn(0.0, gainDb)
    }

    private fun progress(config: AdaptiveBassPlotConfiguration, timeMs: Double, tauMs: Double): Double {
        val updates = floor(timeMs / config.analysisStepMs).coerceAtLeast(0.0)
        return (1 - exp(-updates * config.analysisStepMs / tauMs)).coerceIn(0.0, 1.0)
    }
    private fun times(end: Double) = DoubleArray(193) { i -> .1 * (end / .1).pow(i / 192.0) }
    private fun n(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
