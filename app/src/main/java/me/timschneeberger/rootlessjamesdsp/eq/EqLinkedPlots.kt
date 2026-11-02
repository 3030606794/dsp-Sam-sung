package me.timschneeberger.rootlessjamesdsp.eq

import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogMark
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPoint
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogRegion
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.view.EqualizerSurface
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/** Read-only presentation of the existing fifteen-control multimodal equalizer, not a PEQ bank. */
internal object EqLinkedPlots {
    data class Request(
        val levels: DoubleArray, val filter: Int, val interpolation: Int, val selectedBand: Int,
        val rate: Double, val revision: Long, val pixelColumns: Int = 512, val enabled: Boolean = true
    )
    data class Result(val request: Request, val pair: LiveprogChartPair)
    private const val REFERENCE_RATE = 48000
    private const val MINIMUM = EqPreview.MINIMUM_FREQUENCY
    private const val MAXIMUM = EqPreview.MAXIMUM_FREQUENCY
    private val orders = intArrayOf(4, 6, 8, 10, 12)
    private fun number(value: Double): String = String.format(Locale.ROOT, "%.4g", value)
    private fun hz(value: Double): String = if (value >= 1000.0) "${number(value / 1000.0)}kHz" else "${number(value)}Hz"
    private fun filterLabel(filter: Int): String = if (filter == 0) "FIR目标" else "IIR${orders[filter - 1]}阶"
    private fun interpolationLabel(interpolation: Int): String = if (interpolation == 0) "PCHIP" else "MAKIMA"

    /** Never infer a separate section: changing one interpolation node also changes its slopes. */
    private fun compute(request: Request, cache: NativeCache, coordinates: Coordinates,
        cancelled: () -> Boolean): Result? {
        if (request.levels.size != 15 || request.levels.any { !it.isFinite() } ||
            request.filter !in 0..5 || request.interpolation !in 0..1) return unavailable(request, "当前设置无法形成有效预览")
        val axis = EqualizerSurface.SCALE.copyOf()
        val selected = request.selectedBand.takeIf { it in axis.indices } ?: 8
        val lower = if (selected == 0) MINIMUM else axis[selected - 1]
        val upper = if (selected == axis.lastIndex) MAXIMUM else axis[selected + 1]
        val center = axis[selected]
        val zeroed = request.levels.copyOf().also { it[selected] = 0.0 }
        // Add every control point and native IIR transition design frequency to the log grid.
        // No narrow-Q PEQ bell is substituted for the multimodal engine's actual preview API.
        val global = logarithmic(1025, MINIMUM, MAXIMUM)
        val local = logarithmic(193, lower, upper)
        val transitions = DoubleArray(axis.size - 1) { if (it == 0) axis[0] else (axis[it] + axis[it + 1]) / 2.0 }
        val frequencies = (global.asList() + local.asList() + axis.asList() + transitions.asList())
            .filter { it in MINIMUM..MAXIMUM }.distinct().sorted().toDoubleArray()
        if (cancelled()) return null
        val current = cache.response(request.levels, request.filter, request.interpolation, frequencies, cancelled) ?: return null
        val reference = if (zeroed.contentEquals(request.levels)) current else
            cache.response(zeroed, request.filter, request.interpolation, frequencies, cancelled) ?: return null
        if (cancelled()) return null
        if (current.none { it.isFinite() } || reference.none { it.isFinite() })
            return unavailable(request, "原生预览暂不可用，未绘制替代曲线")
        val effect = DoubleArray(frequencies.size) { index ->
            if (current[index].isFinite() && reference[index].isFinite()) current[index] - reference[index] else Double.NaN
        }
        val leftExtent = coordinates.extent(request, selected, current, effect, local = false)
        val rightExtent = coordinates.extent(request, selected,
            current.indices.filter { frequencies[it] in lower..upper }.map { current[it] }.toDoubleArray(),
            reference.indices.filter { frequencies[it] in lower..upper }.map { reference[it] }.toDoubleArray(), local = true)
        val centerIndex = frequencies.binarySearch(center)
        val modelAtCenter = current.getOrNull(centerIndex) ?: Double.NaN
        val mode = filterLabel(request.filter)
        val interpolation = interpolationLabel(request.interpolation)
        val domainLabel = if (request.filter == 0) "设定目标" else "48k参考"
        val common = listOf(
            "本页是原多模态15频段均衡器，与可变段数参数均衡器PEQ分属不同设置。控制点固定25、40、63、100、160、250、400、630、1000、1600、2500、4000、6300、10000、16000 Hz。",
            "两张静图均为保存或临时编辑数值的目标/预览模型，不是当前音乐频谱、真实前后测量、dBFS、声压或原处理链完整输出。",
            "当前第${selected + 1}项，${center} Hz，设定增益 ${request.levels[selected]} dB；当前滤波模式 $mode；保存的插值选择 $interpolation。",
            if (request.filter == 0)
                "FIR：复用原ComputeEqResponse，PCHIP或MAKIMA对十五个dB控制点插值，0Hz端延用第一项、24kHz端延用末项。这是送往最小相位FIR设计的目标曲线，并未计算或冒充实际采样率下完整、有限长、加窗卷积核的响应。"
            else "IIR：复用原ComputeIIREqualizerCplx及ComputeIIREqualizerResponse，${orders[request.filter - 1]}阶高阶架式段串联，以相邻控制点增益差设计；首转换使用25Hz，其余使用相邻频率算术中点，第一项增益构成公共起始增益。十五个滑杆不是十五个独立峰形biquad。",
            if (request.filter == 0) "插值方式 $interpolation 正参与当前目标曲线。" else
                "IIR模式原算法不使用插值选择，当前保存值 $interpolation 保留，不能伪造它在IIR音频中的效果。",
            "IIR原生预览JNI虽然声明接收srate，内部HSHOResponse实际固定48000Hz；本图据实标为48k参考，没有修改JNI或音频算法。界面提供的处理率上下文为 ${request.rate} Hz，不能把它当作本图已验证的实际IIR频响。",
            "当前项影响=当前全部设置的dB模型−仅把第${selected + 1}项归零后的同模式dB模型。其它十四项保持原值，重新走同一原生预览。这是一次明确的有限改动对照，不是独立滤波段、微分灵敏度或可与其它项简单相加的分解。",
            "邻域阴影仅标相邻控制点之间的显示范围，不是滤波截止、硬作用范围、Q、−3dB带宽或声音在此范围外完全不变的承诺。PCHIP/MAKIMA斜率与IIR相邻差都会造成耦合。",
            "静图轴固定覆盖20Hz…24kHz预览域；当前点和邻点精确加入查询网格。路径保留原生查询数值，未以手画钟形、直线伪装实际滤波器。",
            if (request.enabled) "曲线均衡器当前启用；此处仍只表示参数模型。" else
                "曲线均衡器当前关闭，灰虚线保留设定预览，不代表音频已经施加这些增益。"
        ) + axis.indices.map { index ->
            "第${index + 1}项 ${axis[index]} Hz：${request.levels[index]} dB。"
        }
        val region = LiveprogRegion(lower, upper, "控制点邻域", 1)
        val left = LiveprogStaticPlot(
            if (request.filter == 0) "全局设定目标" else "全局响应 · 48k参考",
            "${hz(center)} · ${number(request.levels[selected])}dB · $mode" + if (request.filter == 0) " · $interpolation" else "",
            xLabel = "Hz", yLabel = "dB", xMinimum = MINIMUM, xMaximum = MAXIMUM,
            yMinimum = -leftExtent, yMaximum = leftExtent, logarithmicX = true,
            curves = listOf(LiveprogCurve("全局模型", envelope(frequencies, current, MINIMUM, MAXIMUM, request.pixelColumns), 0),
                LiveprogCurve("此项影响", envelope(frequencies, effect, MINIMUM, MAXIMUM, request.pixelColumns), 1)),
            regions = listOf(region),
            marks = listOf(LiveprogMark(center, modelAtCenter, "当前频率的全局模型值", 0)),
            notes = common + "当前控制点处的全局模型为 ${modelAtCenter} dB。IIR架式串联不保证该值恰等于滑杆 ${request.levels[selected]} dB；标记放在实际模型曲线上。",
            inactive = !request.enabled)
        val neighbors = listOf(selected, selected - 1, selected + 1).filter { it in axis.indices }
        val right = LiveprogStaticPlot(
            if (request.filter == 0) "当前点邻域目标" else "邻域作用 · 48k参考",
            "${hz(center)} · ${number(request.levels[selected])}dB · $domainLabel",
            xLabel = "Hz", yLabel = "dB", xMinimum = lower, xMaximum = upper,
            yMinimum = -rightExtent, yMaximum = rightExtent, logarithmicX = true,
            curves = listOf(
                LiveprogCurve("当前设置", points(frequencies, current, lower, upper), 0),
                LiveprogCurve("此项归零", points(frequencies, reference, lower, upper), 1)),
            regions = listOf(region),
            marks = neighbors.map { index -> LiveprogMark(axis[index], current[frequencies.binarySearch(axis[index])],
                if (index == selected) "当前控制点频率处模型" else "第${index + 1}邻点频率处模型", if (index == selected) 0 else 1) },
            notes = common + listOf("当前局部窗口 ${lower}…${upper} Hz，完整模型保持原十五项，未删除窗口外的控制点。",
                "邻点标记的纵坐标也取全局模型实际值，不能把IIR滑杆数值直接贴到不经过它的模型曲线上。",
                "归零对照只用于图纸，未写回设置、原预设、驱动或试听模式。"), inactive = !request.enabled)
        return Result(request, LiveprogChartPair(left, right))
    }

    private fun logarithmic(count: Int, minimum: Double, maximum: Double): DoubleArray =
        DoubleArray(count) { if (it == count - 1) maximum else minimum * (maximum / minimum).pow(it.toDouble() / (count - 1)) }

    private fun points(frequencies: DoubleArray, values: DoubleArray, minimum: Double,
        maximum: Double): List<LiveprogPoint> = frequencies.indices.filter { frequencies[it] in minimum..maximum }
        .map { LiveprogPoint(frequencies[it], values[it]) }

    /** Pixel display reduction retains each column's extrema and frequency order. */
    private fun envelope(frequencies: DoubleArray, values: DoubleArray, minimum: Double,
        maximum: Double, pixelColumns: Int): List<LiveprogPoint> {
        val columns = pixelColumns.coerceIn(128, 1536)
        val span = ln(maximum / minimum)
        val result = ArrayList<LiveprogPoint>()
        var column = -1; var first = -1; var last = -1; var low = -1; var high = -1
        fun flush() {
            if (first < 0) return
            listOf(first, low, high, last).distinct().sorted().forEach {
                result.add(LiveprogPoint(frequencies[it], values[it]))
            }
        }
        frequencies.forEachIndexed { index, frequency ->
            if (!values[index].isFinite()) {
                flush(); first = -1; column = -1
                result.add(LiveprogPoint(frequency, Double.NaN))
                return@forEachIndexed
            }
            val next = floor(ln(frequency / minimum) / span * columns).toInt().coerceIn(0, columns - 1)
            if (next != column) { flush(); first = index; low = index; high = index; column = next }
            last = index
            if (values[index] < values[low]) low = index
            if (values[index] > values[high]) high = index
        }
        flush()
        return result
    }

    private fun unavailable(request: Request, reason: String): Result {
        val plot = LiveprogStaticPlot("多模态参数模型", reason, LiveprogPlotStyle.TEXT,
            notes = listOf(reason, "原生预览未返回有效值；没有以平直曲线或PEQ峰形替代，不修改音频设置。"), inactive = true)
        return Result(request, LiveprogChartPair(plot, plot.copy(title = "当前控制点邻域")))
    }

    /** JNI interpolation uses shared native workspaces: all existing callers share this monitor. */
    private class NativeCache {
        private data class Entry(val levels: DoubleArray, val filter: Int, val interpolation: Int,
            val frequencies: DoubleArray, val response: DoubleArray)
        private val entries = ArrayDeque<Entry>()
        fun response(levels: DoubleArray, filter: Int, interpolation: Int, frequencies: DoubleArray,
            cancelled: () -> Boolean): DoubleArray? {
            val participatingInterpolation = if (filter == 0) interpolation else -1
            entries.firstOrNull { it.filter == filter && it.interpolation == participatingInterpolation &&
                it.levels.contentEquals(levels) && it.frequencies.contentEquals(frequencies) }?.let {
                entries.remove(it); entries.addFirst(it)
                return it.response
            }
            if (cancelled()) return null
            val response = FloatArray(frequencies.size) { Float.NaN }
            synchronized(JdspImpResToolbox) {
                if (cancelled()) return null
                val axis = EqualizerSurface.SCALE.copyOf()
                if (filter == 0) {
                    JdspImpResToolbox.ComputeEqResponse(15, axis, levels.copyOf(), interpolation,
                        frequencies.size, frequencies, response)
                } else {
                    val real = DoubleArray(frequencies.size) { Double.NaN }
                    val imaginary = DoubleArray(frequencies.size) { Double.NaN }
                    JdspImpResToolbox.ComputeIIREqualizerCplx(REFERENCE_RATE, orders[filter - 1], axis,
                        levels.copyOf(), frequencies.size, frequencies, real, imaginary)
                    JdspImpResToolbox.ComputeIIREqualizerResponse(frequencies.size, real, imaginary, response)
                }
            }
            if (cancelled()) return null
            val values = DoubleArray(response.size) { response[it].toDouble() }
            entries.addFirst(Entry(levels.copyOf(), filter, participatingInterpolation, frequencies.copyOf(), values))
            while (entries.size > 4) entries.removeLast()
            return values
        }
    }

    private class Coordinates {
        private data class Scope(val filter: Int, val interpolation: Int, val selected: Int, val rate: Double)
        private var scope: Scope? = null
        private var wholeExtent = Double.NaN
        private var localExtent = Double.NaN
        fun extent(request: Request, selected: Int, first: DoubleArray, second: DoubleArray, local: Boolean): Double {
            val next = Scope(request.filter, if (request.filter == 0) request.interpolation else -1, selected, request.rate)
            if (scope != next) { scope = next; wholeExtent = Double.NaN; localExtent = Double.NaN }
            val value = (first.asSequence() + second.asSequence()).filter { it.isFinite() }.maxOfOrNull { abs(it) } ?: 0.0
            val tolerance = max(1e-7, value * 1e-7) // JNI exposes floats; a one-ULP grid-boundary tail is not another 6dB.
            val nearest = round(value / 6.0) * 6.0
            val snapped = if (abs(value - nearest) <= tolerance) nearest else ceil(value / 6.0) * 6.0
            val target = max(12.0, snapped)
            val previous = if (local) localExtent else wholeExtent
            val chosen = if (!previous.isFinite() || value > previous + tolerance ||
                target < previous && value < target - .5) target else previous
            if (local) localExtent = chosen else wholeExtent = chosen
            return chosen
        }
    }

    /** Complete one running immutable frame; replace only the queued edit, never starve drags. */
    class Worker(private val complete: (Result) -> Unit) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "eq-linked-model").apply { isDaemon = true } }
        private val pending = AtomicReference<Request?>()
        private val running = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val cache = NativeCache()
        private val coordinates = Coordinates()
        fun submit(request: Request) {
            if (closed.get()) return
            pending.set(request.copy(levels = request.levels.copyOf()))
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
                            val result = try { compute(request, cache, coordinates, cancelled) }
                            catch (_: LinkageError) { unavailable(request, "原生预览库暂不可用") }
                            catch (_: Exception) { unavailable(request, "当前参数预览暂不可用") }
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
