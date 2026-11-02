package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.FieldAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.FieldController
import me.timschneeberger.rootlessjamesdsp.fusion.FieldPageRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.FieldParameters
import me.timschneeberger.rootlessjamesdsp.fusion.NativeVisualizationTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import java.util.Locale
import kotlin.math.*

/** Read-only normal-branch telemetry. The only delayed callback is ordinary long-press recognition. */
internal class FieldRealtimeChart(context: Context) : View(context) {
    private data class Meter(
        val time: Long, val source: Any, val rate: Int, val frames: Int, val split: Boolean,
        val correlation: Double?, val mid: Double?, val side: Double?, val peak: Double?,
        val inputCorrelation: Double?, val inputMid: Double?, val inputSide: Double?,
        val reduction: Double?, val withdrawal: Double?, val transient: Double?,
        val mono: Double?, val baseline: Double?
    )
    private data class CloudKey(val source: Any, val generation: Int, val sequence: Int, val rate: Int)
    private val density = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(113, 217, 232) else Color.rgb(18, 119, 142)
    private val red = if (dark) Color.rgb(255, 143, 143) else Color.rgb(179, 42, 42)
    private val muted = ColorUtils.setAlphaComponent(ink, 155)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 43); strokeWidth = density; style = Paint.Style.STROKE
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.6f * density; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private val historyPlot = RectF()
    private val dash = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    private var focus = FieldChartFocus.WIDTH
    private var focusKey = FieldParameters.MODE
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var active = false
    private val canDraw get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    private val compact get() = height < 115f * density
    private val history = ArrayDeque<Meter>()
    private var meter: Meter? = null
    private var anchor = 0L
    private var source: Any? = null
    private var previousFrames: Int? = null
    private var previousRate: Int? = null
    private var breakNext = true
    private var cloud: NativeVisualizationTelemetry.FieldSample? = null
    private var cloudKey: CloudKey? = null
    private var cloudAt = 0L
    private var cloudReason = "等待真实 L/R 窗口"
    private var status = "监视已关闭"
    private var frozen = true
    private var configurationTargets: List<Int> = emptyList()
    private var advancedTargets: FieldAdvancedParameters.Runtime? = null
    private var held: List<Meter>? = null
    private var heldAnchor = 0L
    private var selected: Meter? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var pending = false
    private var inspecting = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (pending && isShown && history.isNotEmpty() && historyPlot.contains(downX, downY)) {
            pending = false; inspecting = true; held = history.toList(); heldAnchor = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(lastX)
        }
    }

    fun setFocus(focus: FieldChartFocus, key: String) {
        if (this.focus == focus && focusKey == key) return
        finishInspection(); this.focus = focus; focusKey = key; historyPlot.setEmpty(); redraw()
    }

    fun setConfiguration(values: Map<String, *>, rate: Int) {
        this.values = values.toMap(); this.rate = rate.takeIf { it in 8000..768000 } ?: 48000; redraw()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) finishInspection()
        redraw()
    }

    fun pause(reason: String) {
        finishInspection(); status = reason; frozen = true; breakNext = true; redraw()
    }

    fun showSample(sample: FieldPageRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading = sample.reading
        if (!sample.output.connected || sample.output.globalIssue != null || reading == null ||
            !reading.enabled || reading.processing != OutputRuntimeStatus.Processing.ACTIVE || sample.streamIdentity == null) {
            meter = null; cloud = null; frozen = false; breakNext = true
            status = sample.unavailableReason ?: "等待单一活动音频的真实仪表"
            cloudReason = status; redraw(); return
        }
        val identity = sample.streamIdentity
        val switched = source != identity || previousRate != reading.rate
        if (switched) {
            source = identity; previousFrames = null; previousRate = reading.rate
            cloud = null; cloudKey = null; cloudAt = 0L; breakNext = true
        }
        configurationTargets = reading.targets
        advancedTargets = reading.advanced
        frozen = false; status = ""
        receiveCloud(sample, reading, identity)
        val frames = reading.frames?.takeIf { it >= 0 }
        val before = previousFrames
        val progresses = frames != null && (before == null || frames > before || (before >= 0x70000000 && frames <= 0x0fffffff))
        if (progresses) {
            val basicReady = reading.meterReady
            val advanced = reading.advanced?.takeIf { it.ready }
            val gap = breakNext || history.lastOrNull()?.let { sample.elapsedRealtimeMs - it.time > 500L } == true
            val next = Meter(sample.elapsedRealtimeMs, identity, reading.rate, frames!!, gap,
                if (basicReady) correlation(reading.correlation) else null,
                if (basicReady) level(reading.midRms) else null,
                if (basicReady) level(reading.sideRms) else null,
                if (basicReady) level(reading.peak) else null,
                advanced?.let { correlation(it.inputCorrelation) }, advanced?.let { level(it.inputMid) },
                advanced?.let { level(it.inputSide) }, advanced?.dynamicReduction?.div(100.0),
                advanced?.guardRecovery?.div(10.0), advanced?.transientProtection?.div(10.0),
                advanced?.let { level(it.monoFoldDifference) }, advanced?.let { correlation(it.guardBaseline) })
            meter = next; history.addLast(next); breakNext = false; previousFrames = frames; prune()
        } else if (frames != null && before != null && frames < before) {
            previousFrames = null; meter = null; breakNext = true; status = "处理帧正在重新同步"
        }
        if (history.lastOrNull()?.let { anchor - it.time > 500L } == true) {
            meter = null; breakNext = true; status = "等待新的成像仪表读数"
        }
        redraw()
    }

    private fun receiveCloud(sample: FieldPageRuntimeMonitor.Sample, reading: FieldController.Reading, identity: Any) {
        val next = reading.visualization
        val valid = next != null && !next.clamped && next.rate == reading.rate && next.sequence > 0 &&
            next.left.size == next.right.size && next.left.size in 2..128 &&
            next.left.all { it.isFinite() } && next.right.all { it.isFinite() }
        if (!valid) {
            cloud = null
            cloudReason = sample.visualizationReason ?: if (next?.clamped == true) "L/R 窗口超出可视采样范围" else "等待有效 L/R 采样窗口"
            return
        }
        val snapshot = requireNotNull(next)
        val key = CloudKey(identity, snapshot.generation, snapshot.sequence, snapshot.rate)
        if (key != cloudKey) {
            cloud = snapshot; cloudKey = key; cloudAt = sample.elapsedRealtimeMs; cloudReason = ""
        } else if (sample.elapsedRealtimeMs - cloudAt > 500L) {
            cloud = null; cloudReason = "等待新的 L/R 采样窗口"
        }
    }

    private fun prune() {
        while (history.isNotEmpty() && (history.size > 220 || history.first().time < anchor - 20000L)) history.removeFirst()
    }

    private fun level(raw: Int?): Double? = raw?.takeIf { it != Int.MIN_VALUE }?.div(100.0)
    private fun correlation(raw: Int?): Double? = raw?.takeIf { it != Int.MIN_VALUE && it in -10000..10000 }?.div(10000.0)
    private fun redraw() { if (canDraw) invalidate() }
    private fun f(value: Double?, digits: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float,
        color: Int = ink, align: Paint.Align = Paint.Align.LEFT) {
        if (space <= 0f) return
        val size = text.textSize; text.color = color; text.textAlign = align
        val measured = text.measureText(value)
        if (measured > space) text.textSize *= space / measured
        c.drawText(value, x, y, text); text.textSize = size
    }
    private fun dot(c: Canvas, x: Float, y: Float, color: Int, radius: Float = 2.7f) {
        fill.color = color; c.drawCircle(x, y, radius * density, fill)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(8f * resources.displayMetrics.scaledDensity, 9f * density)
            else min(9f * resources.displayMetrics.scaledDensity, 10.5f * density)
        historyPlot.setEmpty()
        if (width < 20 || height < 20) return
        when (focus) {
            FieldChartFocus.GAIN, FieldChartFocus.FOCUS -> drawLevels(canvas)
            FieldChartFocus.DYNAMIC -> drawMetricHistory(canvas, "Side 衰减", 0.0, 12.0, "dB", { it.reduction })
            FieldChartFocus.GUARD -> drawGuard(canvas)
            FieldChartFocus.TRANSIENT -> drawMetricHistory(canvas, "瞬态保护", 0.0, 100.0, "%", { it.transient })
            FieldChartFocus.AUDITION -> drawCompatibility(canvas)
            else -> drawRadar(canvas)
        }
        if (frozen) label(canvas, "$status · 末次轨迹冻结", width * .5f, height - 2f * density,
            width - 6f * density, muted, Paint.Align.CENTER)
        drawInspection(canvas)
    }

    private fun drawRadar(c: Canvas) {
        val m = meter
        val split = width * .7f
        plot.set(4f * density, 20f * density, split, height - 22f * density)
        val radius = min(plot.width() * .48f, plot.height()).coerceAtLeast(1f)
        val cx = plot.centerX(); val cy = plot.bottom
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        label(c, "开合＝Side/Mid · 亮度＝电平", 2f * density, 10f * density, width - 4f * density)
        for (r in listOf(.33f, .66f, 1f)) {
            val arc = RectF(cx - radius * r, cy - radius * r, cx + radius * r, cy + radius * r)
            c.drawArc(arc, 180f, 180f, false, grid)
        }
        for (degrees in listOf(0, 45, 90, 135, 180)) {
            val a = degrees * PI / 180.0
            c.drawLine(cx, cy, cx - radius * cos(a).toFloat(), cy - radius * sin(a).toFloat(), grid)
        }
        if (m?.mid != null && m.side != null) {
            val ratio = 10.0.pow(((m.side - m.mid) / 20.0).coerceIn(-12.0, 12.0))
            val half = atan(ratio)
            val powerDb = max(m.mid, m.side) + 10 * log10(10.0.pow((m.mid - max(m.mid, m.side)) / 10) + 10.0.pow((m.side - max(m.mid, m.side)) / 10))
            val alpha = (35 + (powerDb + 80).coerceIn(0.0, 80.0) * 1.4).toInt().coerceIn(35, 150)
            path.rewind(); path.moveTo(cx, cy)
            for (i in 0..48) {
                val a = PI / 2 - half + 2 * half * i / 48
                path.lineTo(cx - radius * .92f * cos(a).toFloat(), cy - radius * .92f * sin(a).toFloat())
            }
            path.close(); fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) alpha / 2 else alpha); c.drawPath(path, fill)
        }
        val data = cloud
        if (data != null) {
            c.save(); path.rewind(); path.moveTo(cx - radius, cy); path.arcTo(bounds, 180f, 180f); path.close(); c.clipPath(path)
            for (i in data.left.indices) {
                val side = (data.left[i] - data.right[i]) * .5
                val mid = (data.left[i] + data.right[i]) * .5
                dot(c, cx - side.toFloat() * radius, cy - abs(mid).toFloat() * radius,
                    ColorUtils.setAlphaComponent(purple, if (frozen) 90 else 195), 1.25f)
            }
            c.restore()
        } else label(c, cloudReason, cx, cy - radius * .4f, radius * 1.8f, muted, Paint.Align.CENTER)
        label(c, "L/R 正负折叠投影 · 非距离或方位角", cx, cy + 12f * density, radius * 2, muted, Paint.Align.CENTER)
        val right = RectF(split + 7f * density, 26f * density, width - 5f * density, 36f * density)
        label(c, "相关度 ${f(m?.correlation, 2)}", right.centerX(), 20f * density, right.width(), ink, Paint.Align.CENTER)
        correlationBar(c, right, listOf(m?.correlation to purple))
        label(c, "Side / Mid", right.centerX(), (if (compact) 51f else 61f) * density, right.width(), muted, Paint.Align.CENTER)
        label(c, "${f(if (m?.mid != null && m.side != null) m.side - m.mid else null)} dB", right.centerX(), (if (compact) 63f else 76f) * density, right.width(), purple, Paint.Align.CENTER)
        val note = when (focus) {
            FieldChartFocus.BAND -> {
                val lo = configurationTargets.getOrNull(0) ?: FieldParameters.value(values, FieldParameters.bandLow)
                val hi = configurationTargets.getOrNull(1) ?: FieldParameters.value(values, FieldParameters.bandHigh)
                "配置 $lo–$hi Hz；非分频实测"
            }
            FieldChartFocus.DELAY -> advancedTargets?.let { "配置 ${f(it.delayUs / 1000.0, 2)} ms / ${f(it.delaySamplesMilli / 1000.0, 2)} 样本" }
                ?: "延时为参数目标，不是声学测距"
            FieldChartFocus.DIFFUSION -> advancedTargets?.let { "配置扩散 ${it.diffusionLow}–${it.diffusionHigh} Hz；非频谱测量" }
                ?: "扩散开合反映总体电信号变化"
            FieldChartFocus.DEPTH -> "纵深为听感参数；不测实际米数"
            else -> "正常处理支路 · 临时试听前"
        }
        if (!compact) label(c, note, right.centerX(), 96f * density, right.width(), muted, Paint.Align.CENTER)
        if (!compact && m?.peak?.let { it > 0.0 } == true) label(c, "峰值 >0 dBFS", right.centerX(), 111f * density, right.width(), red, Paint.Align.CENTER)
        if (m == null && !frozen) label(c, status.ifEmpty { "等待有效成像仪表" }, cx, cy - radius * .68f, radius * 1.8f, muted, Paint.Align.CENTER)
    }

    private fun correlationBar(c: Canvas, r: RectF, points: List<Pair<Double?, Int>>) {
        fill.color = ColorUtils.setAlphaComponent(purple, 17); c.drawRect(r, fill)
        c.drawRect(r, grid); c.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, grid)
        points.forEachIndexed { i, (value, color) -> value?.let {
            val x = r.left + ((it.coerceIn(-1.0, 1.0) + 1) / 2).toFloat() * r.width()
            dot(c, x, r.top + r.height() * (i + .5f) / points.size.coerceAtLeast(1), color, 2.5f)
        } }
        label(c, "−1", r.left, r.bottom + 10f * density, r.width() / 3, muted)
        label(c, "+1", r.right, r.bottom + 10f * density, r.width() / 3, muted, Paint.Align.RIGHT)
    }

    private fun drawLevels(c: Canvas) {
        val m = meter
        label(c, if (focus == FieldChartFocus.FOCUS) "全频 M/S 电平 · 非低频独立实测" else "紫：输出 · 青：输入仪表 · dBFS",
            2f * density, 10f * density, width - 4f * density)
        plot.set(30f * density, 24f * density, width - 5f * density, height - 22f * density)
        val low = -90.0; val high = 6.0
        fun y(v: Double) = plot.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * plot.height()
        for (db in listOf(-90.0, -45.0, 0.0)) {
            val yy = y(db); c.drawLine(plot.left, yy, plot.right, yy, grid)
            label(c, f(db, 0), plot.left - 4f * density, yy + 3f * density, plot.left - 6f * density, muted, Paint.Align.RIGHT)
        }
        val groups = listOf(Triple("Mid", m?.inputMid, m?.mid), Triple("Side", m?.inputSide, m?.side), Triple("峰值", null, m?.peak))
        groups.forEachIndexed { index, group ->
            val step = plot.width() / 3; val center = plot.left + step * (index + .5f)
            val bar = min(18f * density, step * .2f)
            fun draw(value: Double?, left: Float, color: Int) { value?.let {
                fill.color = ColorUtils.setAlphaComponent(if (it > 0) red else color, if (frozen) 90 else 190)
                c.drawRect(left, y(it), left + bar, plot.bottom, fill)
            } }
            draw(group.second, center - bar - 2f * density, cyan)
            draw(group.third, center + 2f * density, purple)
            label(c, f(group.third), center, plot.top + 10f * density, step - 4f * density,
                if (group.third?.let { it > 0 } == true) red else purple, Paint.Align.CENTER)
            label(c, group.first, center, plot.bottom + 13f * density, step - 2f * density, ink, Paint.Align.CENTER)
        }
        if (m == null && !frozen) label(c, status.ifEmpty { "等待就绪的 M/S 仪表" }, plot.centerX(), plot.centerY(), plot.width() - 4f * density, muted, Paint.Align.CENTER)
    }

    private fun drawMetricHistory(c: Canvas, title: String, low: Double, high: Double, unit: String,
        metric: (Meter) -> Double?) {
        val current = metric(meter ?: emptyMeter())
        label(c, "$title ${f(current, if (unit == "%") 1 else 2)} $unit", 2f * density, 11f * density, width - 4f * density)
        val bar = RectF(9f * density, 28f * density, width * .17f, height - 22f * density)
        c.drawRect(bar, grid)
        current?.let {
            val top = bar.bottom - ((it.coerceIn(low, high) - low) / (high - low)).toFloat() * bar.height()
            fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) 90 else 185); c.drawRect(bar.left, top, bar.right, bar.bottom, fill)
        }
        val r = RectF(width * .28f, 25f * density, width - 5f * density, height - 20f * density)
        drawHistory(c, r, low, high, unit, metric)
        if (current == null && !frozen) label(c, status.ifEmpty { "等待就绪的高级仪表；不填假零" }, r.centerX(), r.centerY(), r.width() - 4f * density, muted, Paint.Align.CENTER)
    }

    private fun drawGuard(c: Canvas) {
        val m = meter
        label(c, "输入 ${f(m?.inputCorrelation, 2)} · 输出 ${f(m?.correlation, 2)} · 基线 ${f(m?.baseline, 2)}", 2f * density, 10f * density, width - 4f * density)
        val r = RectF(31f * density, 17f * density, width - 6f * density, 27f * density)
        correlationBar(c, r, listOf(m?.inputCorrelation to cyan, m?.correlation to purple, m?.baseline to muted))
        label(c, "真实空间回收 ${f(m?.withdrawal)}% · 非恢复时间", 2f * density, 48f * density, width - 4f * density)
        drawHistory(c, RectF(31f * density, 58f * density, width - 6f * density, height - 20f * density),
            0.0, 100.0, "%", { it.withdrawal })
        if (m?.withdrawal == null && !frozen) label(c, status.ifEmpty { "等待就绪的相关度保护仪表" }, width * .5f, height * .7f, width - 8f * density, muted, Paint.Align.CENTER)
    }

    private fun drawCompatibility(c: Canvas) {
        val m = meter
        label(c, "单声道合并差 ${f(m?.mono, 2)} dB · 相关度 ${f(m?.correlation, 2)}", 2f * density, 11f * density, width - 4f * density)
        label(c, "正常成像支路 · 临时试听前 · 无效值不当 0", 2f * density, 23f * density, width - 4f * density, muted)
        drawHistory(c, RectF(31f * density, 34f * density, width - 6f * density, height - 20f * density),
            -24.0, 0.0, "dB", { it.mono })
        if (m?.mono == null && !frozen) label(c, status.ifEmpty { "没有可计量的单声道合并能量" }, width * .5f, height * .6f, width - 8f * density, muted, Paint.Align.CENTER)
    }

    private fun drawHistory(c: Canvas, r: RectF, low: Double, high: Double, unit: String, metric: (Meter) -> Double?) {
        if (r.height() <= 2f * density || r.width() <= 2f * density) return
        historyPlot.set(r)
        val points = held ?: history.toList(); val end = if (held != null) heldAnchor else anchor
        fun x(t: Long) = r.right - ((end - t) / 20000.0).toFloat() * r.width()
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (i in 0..2) {
            val v = low + (high - low) * i / 2; val yy = y(v)
            c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(v, 0), r.left - 3f * density, yy + 3f * density, r.left - 5f * density, muted, Paint.Align.RIGHT)
        }
        for (i in 0..4) {
            val xx = r.left + r.width() * i / 4; c.drawLine(xx, r.top, xx, r.bottom, grid)
            label(c, if (i == 4) "现在" else if (i == 0) "−20s" else "−${20 - i * 5}", xx, r.bottom + 12f * density,
                r.width() / 4, muted, if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        path.rewind(); var started = false; var previous: Meter? = null
        points.forEach { p ->
            val value = metric(p)
            if (value == null) { started = false; previous = p; return@forEach }
            val split = p.split || previous?.let { it.source != p.source || it.rate != p.rate || p.time - it.time > 500L } == true
            if (!started || split) path.moveTo(x(p.time), y(value)) else path.lineTo(x(p.time), y(value))
            started = true; previous = p
        }
        c.save(); c.clipRect(r)
        stroke.color = ColorUtils.setAlphaComponent(purple, if (frozen) 100 else 235); stroke.pathEffect = null
        c.drawPath(path, stroke)
        selected?.let { p -> metric(p)?.let {
            c.drawLine(x(p.time), r.top, x(p.time), r.bottom, grid); dot(c, x(p.time), y(it), purple)
        } }
        c.restore()
        if (points.none { metric(it) != null } && !frozen) label(c, "等待真实仪表读数", r.centerX(), r.centerY(), r.width() - 4f * density, muted, Paint.Align.CENTER)
    }

    private fun emptyMeter() = Meter(0L, this, rate, 0, true, null, null, null, null, null, null, null, null, null, null, null, null)
    private fun selectedValue(p: Meter): Pair<Double?, String> = when (focus) {
        FieldChartFocus.DYNAMIC -> p.reduction to "dB Side 衰减"
        FieldChartFocus.GUARD -> p.withdrawal to "% 空间回收"
        FieldChartFocus.TRANSIENT -> p.transient to "% 瞬态保护"
        FieldChartFocus.AUDITION -> p.mono to "dB 单声道合并差"
        else -> null to ""
    }
    private fun drawInspection(c: Canvas) {
        val p = selected ?: return
        val metric = selectedValue(p); if (metric.first == null) return
        val rows = listOf("−${f((heldAnchor - p.time) / 1000.0, 2)} s · ${f(metric.first, 2)} ${metric.second}",
            "仪表读数历史；不宣称与 L/R 点云同窗")
        val padding = 4f * density; val gap = text.fontSpacing
        val w = min(width - 2f * density, (rows.maxOfOrNull { text.measureText(it) } ?: 0f) + padding * 2)
        val h = gap * rows.size + padding * 2
        val left = historyPlot.left.coerceIn(density, max(density, width - w - density))
        val top = historyPlot.top.coerceAtMost(max(density, height - h - density))
        fill.color = ColorUtils.setAlphaComponent(surface, 245); c.drawRoundRect(RectF(left, top, left + w, top + h), 3f * density, 3f * density, fill)
        rows.forEachIndexed { i, row -> label(c, row, left + padding, top + padding - text.ascent() + i * gap, w - padding * 2) }
    }
    private fun select(x: Float) {
        val points = held ?: return
        val target = heldAnchor - ((historyPlot.right - x.coerceIn(historyPlot.left, historyPlot.right)) / historyPlot.width() * 20000L).toLong()
        selected = points.filter { selectedValue(it).first != null }.minByOrNull { abs(it.time - target) }; redraw()
    }
    private fun finishInspection() {
        removeCallbacks(longPress); pending = false
        if (inspecting) parent?.requestDisallowInterceptTouchEvent(false)
        inspecting = false; held = null; selected = null
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishInspection()
                if (!historyPlot.contains(event.x, event.y) || history.isEmpty()) return false
                downX = event.x; downY = event.y; lastX = event.x; pending = true
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { finishInspection(); return false }
                lastX = event.x
                if (inspecting) { select(lastX); return true }
                if (pending && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    pending = false; removeCallbacks(longPress)
                }
                return pending
            }
            MotionEvent.ACTION_UP -> { val click = pending; finishInspection(); if (click) performClick(); redraw(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { finishInspection(); redraw(); return false }
        }
        return inspecting || pending
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); finishInspection(); redraw() }
    override fun onDetachedFromWindow() { finishInspection(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
