package me.timschneeberger.rootlessjamesdsp.peq

import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogMark
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPoint
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogRegion
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tan

/** Presentation-only PEQ bank and single-section models. No editor or audio state is written. */
internal object PeqLinkedPlots {
    data class Request(
        val state: PeqState, val band: PeqBand?, val rate: Double, val enabled: Boolean,
        val revision: Long, val pixelColumns: Int = 512
    )
    data class Result(val request: Request, val pair: LiveprogChartPair)

    fun typeName(type: PeqFilterType): String = when (type) {
        PeqFilterType.PEAK -> "峰形"
        PeqFilterType.LOW_SHELF -> "低架"
        PeqFilterType.HIGH_SHELF -> "高架"
        PeqFilterType.LOW_PASS -> "低通"
        PeqFilterType.HIGH_PASS -> "高通"
        PeqFilterType.NOTCH -> "陷波"
        PeqFilterType.BAND_PASS -> "带通"
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.4g", value)
    private fun hz(value: Double): String = if (value >= 1000) "${number(value / 1000)}kHz" else "${number(value)}Hz"

    /** Worker-local display coordinates, never a filter coefficient or smoothed model value. */
    private class Coordinates {
        private data class BankScope(val rate: Double, val minimum: Double, val maximum: Double,
            val ids: List<String>, val selectedId: String?)
        private data class LocalScope(val id: String, val type: PeqFilterType, val rate: Double,
            val center: Double, val graphMinimum: Double, val graphMaximum: Double)
        data class Local(val minimum: Double, val maximum: Double, val offset: Boolean, val scale: Double)
        private class Extent {
            private var previous = Double.NaN
            fun update(value: Double, minimum: Double): Double {
                val magnitude = value.coerceAtLeast(0.0)
                val nearest = round(magnitude / 6.0) * 6.0
                // Exact 18dB may evaluate a few ULP above 18; that is not a 24dB scale change.
                val snapped = if (abs(magnitude - nearest) <= tolerance(magnitude)) nearest else ceil(magnitude / 6.0) * 6.0
                val candidate = max(minimum, snapped)
                val shrinkMargin = max(.1, min(1.0, candidate * .025))
                if (!previous.isFinite() || minimum > previous || magnitude > previous + tolerance(magnitude) ||
                    candidate < previous && magnitude <= candidate - shrinkMargin) previous = candidate
                return previous
            }
        }
        private var bankScope: BankScope? = null
        private var bankExtent = Extent()
        private var localScope: LocalScope? = null
        private var localLow = Extent()
        private var localHigh = Extent()
        private var halfSpan = Double.NaN
        private var relativeAxis: Boolean? = null
        private var relativeScale = Double.NaN

        fun bank(request: Request, magnitude: Double): Double {
            val next = BankScope(request.rate, request.state.graphMinFrequency, request.state.graphMaxFrequency,
                request.state.bands.map { it.id }, request.band?.id)
            if (bankScope != next) { bankScope = next; bankExtent = Extent() }
            return bankExtent.update(magnitude, max(6.0, request.state.gainRange))
        }

        fun local(request: Request, band: PeqBand, center: Double, desired: Double,
            required: Double, narrow: Boolean): Local {
            val next = LocalScope(band.id, band.type, request.rate, center,
                request.state.graphMinFrequency, request.state.graphMaxFrequency)
            if (localScope != next) {
                localScope = next; localLow = Extent(); localHigh = Extent()
                halfSpan = Double.NaN; relativeAxis = null; relativeScale = Double.NaN
            }
            // Quantised viewport zoom with a wide hysteresis gap: Q changes the real curve,
            // rather than continuously stretching both the curve and its coordinate window.
            val target = desired.coerceIn(1e-9, 24.0)
            if (!halfSpan.isFinite() || target > halfSpan * 1.5 || target < halfSpan * .375 ||
                required > halfSpan * (1.0 - 1e-9)) {
                halfSpan = if (narrow) 2.0.pow(ceil(ln(max(target, required * 1.25)) / ln(2.0) - 1e-10))
                    .coerceIn(1e-9, 24.0) else 3.0
            }
            val warped = tan(PI * center / request.rate)
            val lower = max(1.0, request.rate / PI * atan(warped * 2.0.pow(-halfSpan)))
            val upper = min(request.rate / 2.0, request.rate / PI * atan(warped * 2.0.pow(halfSpan)))
            val ratio = upper / lower
            relativeAxis = when {
                !narrow -> false
                relativeAxis == null -> ratio < 1.1
                relativeAxis == true -> ratio < 1.16
                else -> ratio < 1.06
            }
            val span = upper - lower
            if (relativeAxis == true) {
                if (!relativeScale.isFinite()) relativeScale = initialScale(span)
                // Engineering-unit changes also use separate enter/leave thresholds.
                repeat(3) {
                    relativeScale = when (relativeScale) {
                        1e6 -> if (span > .02) 1e3 else 1e6
                        1e3 -> if (span < .005) 1e6 else if (span > 20.0) 1.0 else 1e3
                        .001 -> if (span < 5000.0) 1.0 else .001
                        else -> if (span < 5.0) 1e3 else if (span > 20000.0) .001 else 1.0
                    }
                }
            }
            return Local(lower, upper, relativeAxis == true, if (relativeAxis == true) relativeScale else 1.0)
        }

        fun localBounds(minimum: Double, maximum: Double): Pair<Double, Double> =
            -localLow.update(-minimum, 6.0) to localHigh.update(maximum, 6.0)

        companion object {
            private fun tolerance(value: Double) = max(1e-9, abs(value) * 1e-10)
            private fun initialScale(span: Double): Double = when {
                span < .01 -> 1e6
                span < 10.0 -> 1e3
                span > 10000.0 -> .001
                else -> 1.0
            }
        }
    }

    private fun compute(request: Request, cache: PeqResponse.PreviewCache,
        coordinates: Coordinates, cancelled: () -> Boolean): Result? {
        val state = request.state
        val range = PeqFrequencyLimits.graphRange(state, request.rate)
        val minimum = range.start
        val maximum = range.endInclusive
        val whole = cache.curve(state, points = 1536, sampleRate = request.rate,
            frequencyMin = minimum, frequencyMax = maximum, cancelled = cancelled) ?: return null
        if (cancelled()) return null
        val band = request.band?.let(PeqProtocol::quantized)
        val model = band?.let { PeqParameterResponse.prepare(it, request.rate) }
        val contribution = if (model != null && band != null) PeqResponse.adaptiveCurve(
            PeqState(listOf(band.copy(enabled = true))), points = 512, sampleRate = request.rate,
            frequencyMin = minimum, frequencyMax = maximum) else emptyList()
        val extrema = (whole.asSequence() + contribution.asSequence()).map { abs(it.db) }
            .filter { it.isFinite() }.maxOrNull() ?: 0.0
        val extent = coordinates.bank(request, extrema)
        val ordinal = state.bands.indexOfFirst { it.id == band?.id }.let { if (it < 0) "" else "第${it + 1}段 · " }
        val current = if (band == null) "未选择频段" else "$ordinal${typeName(band.type)} · ${hz(band.frequency)}"
        val context = listOf(
            "静图是保存参数或当前编辑预览的稳态滤波器模型，不是音乐频谱、测量结果或完整音频链输出。真实音乐只看下方独立只读采样。",
            "处理率 ${number(request.rate)} Hz；全银行共 ${state.bands.size} 段，其中 ${state.bands.count { it.enabled && it.frequency > 0 && it.frequency <= request.rate / 2 }} 段已启用且频率有效。",
            "总响应仅串联银行内启用且频率有效的段；当前段曲线可以单独预览关闭的段，虚线不代表已把它加入总响应或启用了试听。",
            "频率和增益按驱动实际的毫赫兹、毫分贝量化，Q 保留原双精度；高 Q 峰形增益补偿、Nyquist 左极限与原 PeqResponse 保持一致。",
            "频率轴沿用保存的图形范围并限制至当前处理率 Nyquist。高于 Nyquist 或 0 Hz 未设置的段保留原参数，但不伪造有效响应。",
            "全段自适应网格包括每段中心、局部宽度及架式极零点。显示按每像素依频率顺序保留首点、最小值、最大值和末点，不以平均值抹平窄峰。有限显示网格不等于连续域极值证明。",
            if (request.enabled) "参数均衡器当前启用。" else "参数均衡器当前关闭；灰虚线仍是参数预览，实际处理状态以只读采样为准。"
        )
        val bandNotes = band?.let {
            listOf("${ordinal}${typeName(it.type)}；${if (it.enabled) "启用" else "关闭预览"}；频率 ${it.frequency} Hz，增益 ${it.gain} dB，Q ${it.q}；界面质量单位 ${it.qualityDisplay}。",
                "Q/BW 是同一个原生质量参数的两种显示，不新增斜率、频带边界或其它音频控制。原有频段卡、精调、锁定和试听归原编辑器管理。")
        } ?: emptyList()
        val left = LiveprogStaticPlot("总响应与当前段", current,
            xLabel = "Hz", yLabel = "dB", xMinimum = minimum, xMaximum = maximum,
            yMinimum = -extent, yMaximum = extent, logarithmicX = true,
            curves = buildList {
                add(LiveprogCurve("总响应", envelope(whole, minimum, maximum, request.pixelColumns), 0))
                if (contribution.isNotEmpty()) add(LiveprogCurve("当前段", envelope(contribution, minimum, maximum,
                    request.pixelColumns), 1, band?.enabled == false))
            }, notes = context + bandNotes, inactive = !request.enabled)
        val right = if (model == null || band == null) {
            val reason = when {
                band == null -> "尚未选择频段"
                band.frequency == 0.0 -> "0 Hz：尚未设置"
                band.frequency > request.rate / 2.0 -> "超出当前 Nyquist"
                else -> "参数模型不可用"
            }
            LiveprogStaticPlot("当前段作用", reason, LiveprogPlotStyle.TEXT,
                notes = context + bandNotes + reason, inactive = true)
        } else local(request, band, model, coordinates, context + bandNotes)
        if (cancelled()) return null
        return Result(request, LiveprogChartPair(left, right))
    }

    private fun local(request: Request, band: PeqBand, model: PeqParameterResponse.Model,
        coordinates: Coordinates, notes: List<String>): LiveprogStaticPlot {
        val center = PeqResponse.coefficientFrequency(band.frequency, request.rate)
        val narrow = band.type == PeqFilterType.PEAK || band.type == PeqFilterType.NOTCH || band.type == PeqFilterType.BAND_PASS
        val bandwidth = model.bandwidth()
        val desiredHalfSpan = if (narrow) {
            val effectiveQ = PeqResponse.coefficientQ(band.type, band.q, band.gain)
            (PeqResponse.bandwidthOctaves(effectiveQ, center, request.rate) * 4.0).coerceIn(1e-9, 24.0)
        } else 3.0
        val warped = tan(PI * center / request.rate)
        val requiredHalfSpan = if (narrow && bandwidth.complete) listOfNotNull(bandwidth.lowerHz, bandwidth.upperHz)
            .filter { it > 0.0 && it < request.rate / 2.0 }
            .map { abs(ln(tan(PI * it / request.rate) / warped) / ln(2.0)) }
            .filter { it.isFinite() }.maxOrNull() ?: 0.0 else 0.0
        val viewport = coordinates.local(request, band, center,
            max(desiredHalfSpan, requiredHalfSpan * 1.25), requiredHalfSpan, narrow)
        val lower = viewport.minimum
        val upper = viewport.maximum
        if (!lower.isFinite() || !upper.isFinite() || lower >= upper) return LiveprogStaticPlot(
            "当前段作用", "当前窗口小于数值分辨率", LiveprogPlotStyle.TEXT, notes = notes, inactive = !request.enabled)
        val points = PeqResponse.adaptiveCurve(PeqState(listOf(band.copy(enabled = true))), points = 384,
            sampleRate = request.rate, frequencyMin = lower, frequencyMax = upper)
        // A relative axis keeps two distinct high-Q endpoints readable without rounding both to 24k.
        val offsetAxis = viewport.offset
        val scale = viewport.scale
        val unit = when (scale) { 1e6 -> "ΔµHz"; 1e3 -> "ΔmHz"; .001 -> "ΔkHz"; else -> if (offsetAxis) "ΔHz" else "Hz" }
        fun axis(frequency: Double) = if (offsetAxis) (frequency - center) * scale else frequency
        val rawMinimum = min(0.0, points.minOfOrNull { it.db } ?: 0.0)
        val rawMaximum = max(0.0, points.maxOfOrNull { it.db } ?: 0.0)
        val (low, high) = coordinates.localBounds(rawMinimum, rawMaximum)
        val title = when (band.type) {
            PeqFilterType.PEAK -> "峰形增强与削减"
            PeqFilterType.LOW_SHELF -> "低频平台与过渡"
            PeqFilterType.HIGH_SHELF -> "高频平台与过渡"
            PeqFilterType.LOW_PASS -> "低频保留与高频削减"
            PeqFilterType.HIGH_PASS -> "高频保留与低频削减"
            PeqFilterType.NOTCH -> "凹口与两侧保留"
            PeqFilterType.BAND_PASS -> "保留带与两侧削减"
        }
        val regions = if (bandwidth.complete) {
            val start = max(lower, bandwidth.lowerHz ?: lower)
            val end = min(upper, bandwidth.upperHz ?: upper)
            if (end > start) listOf(LiveprogRegion(axis(start), axis(end), bandwidth.label, 1)) else emptyList()
        } else emptyList()
        val subtitle = "${hz(band.frequency)} · ${number(band.gain)}dB · ${if (band.qualityDisplay == PeqQualityDisplay.BW)
            "BW${number(PeqResponse.bandwidthOctaves(band.q, band.frequency, request.rate))}" else "Q${number(band.q)}"}"
        val explanation = when (band.type) {
            PeqFilterType.PEAK -> "曲线正值是增强，负值是削减；低于 3 dB 的峰/凹不一定存在双边 −3 dB 作用区。"
            PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF -> "架式曲线表示一侧增益平台与过渡，不能把中心频率冒充硬切频率，也不假设存在对称双边带宽。"
            PeqFilterType.LOW_PASS, PeqFilterType.HIGH_PASS -> "保留一侧频率、衰减另一侧；本类型的 gain 是额外输出增益，Q 还会改变拐点处的共振，不统一标成 −3 dB 截止。"
            PeqFilterType.NOTCH -> "陷波中心零点深度按数值地板显示，相位在该零点未定义。gain 仍是该滤波段的额外输出增益，不是凹口深度控制。"
            PeqFilterType.BAND_PASS -> "中央通带保留、两侧削减；gain 是额外输出增益。阴影仅表示存在的通带 −3 dB 边界，不代表边界外立即静音。"
        }
        val centerDb = model.db(center)
        val bandwidthDetail = if (bandwidth.complete) "${bandwidth.label}；下界 ${bandwidth.lowerHz ?: "延续至DC"}，" +
            "上界 ${bandwidth.upperHz ?: "延续至Nyquist"}，阈值 ${bandwidth.thresholdDb} dB。" else
            "${bandwidth.label}；当前没有可标注的对应 −3 dB 区间。"
        return LiveprogStaticPlot(title, subtitle,
            xLabel = unit, yLabel = "dB", xMinimum = axis(lower), xMaximum = axis(upper),
            yMinimum = low, yMaximum = high, logarithmicX = !offsetAxis,
            curves = listOf(LiveprogCurve(typeName(band.type), points.map { LiveprogPoint(axis(it.frequency), it.db) },
                if (band.gain < 0) 2 else 1, !band.enabled)), regions = regions,
            marks = listOf(LiveprogMark(axis(center.coerceIn(lower, upper)), centerDb, "", 0)),
            notes = notes + listOf(explanation, "局部频率窗口 ${lower}…${upper} Hz；当前中心 ${band.frequency} Hz。" +
                if (offsetAxis) "横轴为相对当前系数中心 ${center} Hz 的偏移，单位 $unit；0 对应中心。" else "横轴为对数频率。",
                bandwidthDetail,
                "坐标轴按同一频段、类型、处理率、中心及图域独立保存，仅对坐标做带容差的6dB整格取整、缩放档位和滞回；曲线、中心极值、−3dB边界、原始参数及精确读数均未平滑或改写。",
                "图中标记系数中心 ${center} Hz 的模型增益 ${centerDb} dB；保存频率 ${band.frequency} Hz 的模型增益 ${model.db(band.frequency)} dB。恰好 Nyquist 时按原模型保留系数左极限；未移动用户参数。",
                "模型最小数值地板 ${PeqResponse.FLOOR_DB} dB。坐标值是原模型输出，不是音乐能量。"),
            inactive = !request.enabled || !band.enabled)
    }

    /** Preserve extrema and frequency order inside every display column, without averaging. */
    private fun envelope(points: List<PeqResponse.Point>, minimum: Double, maximum: Double,
        columns: Int): List<LiveprogPoint> {
        val count = columns.coerceIn(128, 1536)
        val span = ln(maximum / minimum)
        val output = ArrayList<LiveprogPoint>(min(points.size, count * 4))
        var first = -1
        var last = -1
        var low = -1
        var high = -1
        var column = -1
        fun flush() {
            if (first < 0) return
            listOf(first, low, high, last).distinct().sorted().forEach { index ->
                output.add(LiveprogPoint(points[index].frequency, points[index].db))
            }
        }
        points.forEachIndexed { index, point ->
            val next = floor(ln(point.frequency / minimum) / span * count).toInt().coerceIn(0, count - 1)
            if (next != column) {
                flush(); first = index; low = index; high = index; column = next
            }
            last = index
            if (point.db < points[low].db) low = index
            if (point.db > points[high].db) high = index
        }
        flush()
        return output
    }

    /** One complete running snapshot and the newest pending edit; no audio algorithm execution. */
    class Worker(private val complete: (Result) -> Unit) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "peq-linked-model").apply { isDaemon = true } }
        private val cache = PeqResponse.PreviewCache()
        private val coordinates = Coordinates()
        private val pending = AtomicReference<Request?>()
        private val running = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        fun submit(request: Request) {
            if (closed.get()) return
            pending.set(request)
            schedule()
        }
        private fun schedule() {
            if (closed.get() || !running.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try {
                        while (!closed.get() && !Thread.currentThread().isInterrupted) {
                            val request = pending.getAndSet(null) ?: break
                            val cancelled = { closed.get() || Thread.currentThread().isInterrupted }
                            val result = runCatching { compute(request, cache, coordinates, cancelled) }.getOrNull()
                            if (result != null && !cancelled()) complete(result)
                        }
                    } finally {
                        running.set(false)
                        if (pending.get() != null && !closed.get()) schedule()
                    }
                }
            } catch (_: RejectedExecutionException) { running.set(false) }
        }
        override fun close() { closed.set(true); pending.set(null); executor.shutdownNow() }
    }
}
