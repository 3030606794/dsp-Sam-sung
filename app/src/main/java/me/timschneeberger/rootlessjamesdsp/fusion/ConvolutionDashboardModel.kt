package me.timschneeberger.rootlessjamesdsp.fusion

import java.io.File
import java.util.Locale
import kotlin.math.*

/** Observes the immutable prepared-IR preview cache. It never decodes, edits, uploads or invents an IR. */
class ConvolutionDashboardModel private constructor(
    val values: Map<String, *>, val rate: Int, val actualRate: Boolean,
    val preparation: ConvolutionController.PreparedIr?, val prepared: ConvolutionController.PreparedIr?,
) {
    private val p = ConvolutionParameters
    private val a = ConvolutionAdvancedParameters
    val sampleRate get() = rate
    val enabled = values[p.ENABLED] as? Boolean ?: false
    val moduleEnabled get() = enabled
    val professional = true
    val hasIr = prepared?.hasWorkingKernel == true
    val pending = preparation?.status == ConvolutionController.Preparation.PROCESSING
    val confirmed = ConvolutionController.kernelConfirmed(prepared)
    val channels = prepared?.channels ?: 0
    val irRate = prepared?.processingSampleRate ?: rate
    val durationMs = prepared?.let { it.workingFrames * 1000.0 / it.processingSampleRate } ?: 0.0
    val mix = p.value(values, p.mix) / 1000.0
    val wetGainDb = p.value(values, p.wet) / 10.0
    val outputDb = p.value(values, p.output) / 10.0
    val leftTrimDb = p.value(values, p.left) / 10.0
    val rightTrimDb = p.value(values, p.right) / 10.0
    val invertLeft = p.enabled(values, p.INVERT_LEFT)
    val invertRight = p.enabled(values, p.INVERT_RIGHT)
    val swap = p.enabled(values, p.SWAP)
    val requestedPredelayMs = p.value(values, p.predelay) / 1000.0
    private val timeRate = if (actualRate) rate else irRate
    val predelayMs = if (actualRate || hasIr) (requestedPredelayMs * timeRate / 1000).roundToInt() * 1000.0 / timeRate else requestedPredelayMs
    val matrixOn = a.on(values, a.MATRIX)
    val fractionalOn = a.on(values, a.FRACTIONAL)
    val shapeOn = a.on(values, a.SHAPE)
    val route = a.choice(values, a.ROUTE)
    val fractionalMethod = a.choice(values, a.METHOD)
    val pathGainsDb = DoubleArray(4) { if (matrixOn) a.value(values, a.gain[it]) / 10.0 else 0.0 }
    val pathMuted = BooleanArray(4) { matrixOn && a.on(values, a.mute(it)) }
    val pathInverted = BooleanArray(4) { matrixOn && a.on(values, a.invert(it)) }
    val pathDelayMs = DoubleArray(4) { if (fractionalOn) a.value(values, a.delay[it]) / 1000.0 else 0.0 }
    val pathNames = a.paths
    val splitMs = a.value(values, a.split) / 1000.0
    val blendMs = a.value(values, a.blend) / 1000.0
    val earlyDb = a.value(values, a.early) / 10.0
    val lateDb = a.value(values, a.late) / 10.0
    val decayDbPerSecond = a.value(values, a.decay) / 10.0
    val windowOn = p.enabled(values, p.WINDOW_ENABLED)
    val windowStartMs = p.value(values, p.start) / 1000.0
    val windowLengthMs = p.value(values, p.length) / 1000.0
    val fadeInMs = p.value(values, p.fadeIn) / 1000.0
    val fadeOutMs = p.value(values, p.fadeOut) / 1000.0
    val waveform = prepared?.waveform ?: emptyList()
    val timeMs = waveform.firstOrNull()?.let { samples -> DoubleArray(samples.size) { (it + .5) / samples.size * durationMs } } ?: doubleArrayOf()
    val bucketWidthMs = waveform.firstOrNull()?.size?.takeIf { it > 0 }?.let { durationMs / it } ?: 0.0
    val peakFrames = prepared?.peakFrames ?: emptyList()
    val pathChannels = if (channels == 4) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 3)
    val pathPeak = DoubleArray(4) { path ->
        val c = when { channels == 1 && path in listOf(0, 3) -> 0; channels == 2 && path == 0 -> 0; channels == 2 && path == 3 -> 1; channels == 4 -> path; else -> -1 }
        if (c < 0) 0.0 else waveform.getOrNull(c)?.maxOfOrNull { abs(it).toDouble() } ?: 0.0
    }
    val frequencies = (prepared?.advancedAnalysis?.hz?.takeIf { it.size > 1 }
        ?: prepared?.frequencyHz?.takeIf { it.size > 1 })?.map { it.toDouble() }?.toDoubleArray()
        ?: DoubleArray(192) { 20.0 * (min(20000.0, rate * .49) / 20.0).pow(it / 191.0) }
    val irDb = (prepared?.advancedAnalysis?.workingDb ?: prepared?.magnitudesDb ?: emptyList()).map { row -> row.map { it.toDouble() }.toDoubleArray() }
    val phaseDegrees = prepared?.advancedAnalysis?.phaseDegrees ?: emptyList()
    val groupDelayMs = prepared?.advancedAnalysis?.groupDelayMs ?: emptyList()
    val filterEnabled = BooleanArray(4) { a.on(values, a.enabledFilter(it)) }
    val filterHz = doubleArrayOf(a.value(values, a.hp).toDouble(), a.value(values, a.lp).toDouble(),
        a.value(values, a.lowHz).toDouble(), a.value(values, a.highHz).toDouble()).map { min(it, rate * .45) }.toDoubleArray()
    val filterOrders = intArrayOf(2 shl a.choice(values, a.slope(0)), 2 shl a.choice(values, a.slope(1)))
    val shelfGainsDb = doubleArrayOf(a.value(values, a.lowGain) / 10.0, a.value(values, a.highGain) / 10.0)
    private val sections = List(4) { kind -> if (!filterEnabled[kind]) emptyList() else design(kind) }
    val eqBandsDb = sections.map { bank -> DoubleArray(frequencies.size) { i ->
        bank.sumOf { it.db(frequencies[i], rate) }
    } }
    val eqDb = DoubleArray(frequencies.size) { i -> eqBandsDb.sumOf { it[i] } }
    /** IR-magnitude plus the downstream wet filters and output-channel calibration; mix is separate. */
    val wetPathDb = irDb.mapIndexed { c, data -> DoubleArray(min(data.size, frequencies.size)) { i ->
        data[i] + eqDb[i] + 20 * log10(max(1e-15, abs(waveGain(c))))
    } }
    val staticStatusLines = listOf(
        "${rate} Hz${if (actualRate) " 实际采样率" else " 参考采样率"} · ${if (hasIr) "${channels}核 / ${format(durationMs)}ms" else "等待有效工作IR"} · ${if (enabled) "已启用" else "已关闭（预览保留）"}",
        "混合${format(mix * 100, 0)}% · 湿声${format(wetGainDb)}dB · 输出${format(outputDb)}dB · 预延时${format(predelayMs, 3)}ms",
        when { pending && hasIr -> "正在准备新核；图中保留同文件上一份完整工作核"; pending -> "工作IR正在准备；仅显示附加参数模型";
            hasIr -> "${File(prepared!!.sourcePath).name} · CRC${prepared.crc.toUInt().toString(16)} · ${if (confirmed) "已确认装载" else "生成核待确认装载"}"
            else -> "未用示例脉冲代替真实IR；滤波与路径图仅为配置模型" },
    )
    fun waveGain(channel: Int): Double {
        val outputChannel = if (channels == 4) if (channel == 0 || channel == 2) 0 else 1 else channel.coerceAtMost(1)
        val target = if (swap) 1 - outputChannel else outputChannel
        val trim = if (target == 0) leftTrimDb else rightTrimDb
        val sign = if (if (target == 0) invertLeft else invertRight) -1.0 else 1.0
        return sign * 10.0.pow((wetGainDb + trim) / 20)
    }
    fun shapingDb(time: Double): Double {
        if (!shapeOn) return 0.0
        val t = ((time - splitMs) / blendMs + .5).coerceIn(0.0, 1.0)
        val blend = .5 - .5 * cos(PI * t)
        return earlyDb * (1 - blend) + lateDb * blend - decayDbPerSecond * max(0.0, (time - splitMs) / 1000)
    }
    /** Parameter-only preview on the very same prepared cache; never applies offline IR edits again. */
    fun withDisplayValues(displayValues: Map<String, *>): ConvolutionDashboardModel =
        ConvolutionDashboardModel(LinkedHashMap(displayValues), rate, actualRate, preparation, prepared)
    private fun design(kind: Int): List<Section> {
        val w = 2 * PI * filterHz[kind] / rate; val co = cos(w); val si = sin(w)
        if (kind < 2) {
            val order = filterOrders[kind]
            return List(order / 2) { k ->
                val q = 1 / (2 * cos(PI * (2 * k + 1) / (2 * order)))
                val alpha = si / (2 * q); val sign = if (kind == 0) 1.0 else -1.0
                Section((1 + sign * co) / 2, -sign * (1 + sign * co), (1 + sign * co) / 2,
                    1 + alpha, -2 * co, 1 - alpha)
            }
        }
        val gain = shelfGainsDb[kind - 2]; if (gain == 0.0) return emptyList()
        val aa = 10.0.pow(gain / 40); val beta = 2 * sqrt(aa) * si / sqrt(2.0)
        return listOf(if (kind == 2) Section(aa * ((aa + 1) - (aa - 1) * co + beta), 2 * aa * ((aa - 1) - (aa + 1) * co),
            aa * ((aa + 1) - (aa - 1) * co - beta), (aa + 1) + (aa - 1) * co + beta,
            -2 * ((aa - 1) + (aa + 1) * co), (aa + 1) + (aa - 1) * co - beta)
        else Section(aa * ((aa + 1) + (aa - 1) * co + beta), -2 * aa * ((aa - 1) + (aa + 1) * co),
            aa * ((aa + 1) + (aa - 1) * co - beta), (aa + 1) - (aa - 1) * co + beta,
            2 * ((aa - 1) - (aa + 1) * co), (aa + 1) - (aa - 1) * co - beta))
    }
    private data class Section(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double) {
        fun db(hz: Double, fs: Int): Double {
            val w = -2 * PI * hz / fs
            val numerator = hypot(b0 + b1 * cos(w) + b2 * cos(2 * w), b1 * sin(w) + b2 * sin(2 * w))
            val denominator = hypot(a0 + a1 * cos(w) + a2 * cos(2 * w), a1 * sin(w) + a2 * sin(2 * w))
            return 20 * log10(max(1e-15, numerator / max(1e-15, denominator)))
        }
    }
    companion object {
        @Volatile private var lastComplete: ConvolutionController.PreparedIr? = null
        @Synchronized fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false): ConvolutionDashboardModel {
            val current = ConvolutionController.prepared
            val work = if (current?.hasWorkingKernel == true) current.also { lastComplete = it }
                else if (current?.status == ConvolutionController.Preparation.PROCESSING && current.sourcePath == lastComplete?.sourcePath) lastComplete else null
            if (current == null || current.status == ConvolutionController.Preparation.FAILED || current.status == ConvolutionController.Preparation.WAITING) lastComplete = null
            return ConvolutionDashboardModel(LinkedHashMap(values), rate.coerceIn(8000, 768000), actualRate, current, work)
        }
        private fun format(v: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", v)
    }
}
