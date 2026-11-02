package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.DiffAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DiffChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.DiffPageRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.DiffRuntimeReader
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import java.util.Locale
import kotlin.math.*

/** Native 100 ms instrument readings, never invented PCM, location or measured propagation delay. */
internal class DiffRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val frames: Int, val rate: Int,
        val split: Boolean, val snapshot: DiffRuntimeReader.Snapshot)
    private data class Series(val name: String, val color: Int, val read: (Frame) -> Double?)
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(109, 219, 235) else Color.rgb(15, 119, 143)
    private val red = if (dark) Color.rgb(255, 142, 142) else Color.rgb(180, 42, 42)
    private val muted = ColorUtils.setAlphaComponent(ink, 150)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 40); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private var focus = DiffChartFocus.DELAY
    private var key = "v_diff_delay"
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var active = false
    private val canDraw get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    private val compact get() = height < 115f * d
    private val history = ArrayDeque<Frame>()
    private var current: Frame? = null
    private var source: Any? = null
    private var previousFrames: Int? = null
    private var previousRate: Int? = null
    private var anchor = 0L
    private var breakNext = true
    private var frozen = true
    private var state = "监视已关闭"
    private var historySeries = emptyList<Series>()
    private var historyUnit = ""
    private var held: List<Frame>? = null
    private var heldEnd = 0L
    private var selected: Frame? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var pending = false
    private var inspecting = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable {
        if (pending && isShown && history.isNotEmpty() && plot.contains(downX, downY) && historySeries.isNotEmpty()) {
            pending = false; inspecting = true; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(lastX)
        }
    }

    fun setConfiguration(values: Map<String, *>, rate: Int) {
        this.values = values.toMap(); this.rate = rate.takeIf { it in 8000..768000 } ?: 48000; redraw()
    }
    fun setFocus(focus: DiffChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        finishInspection(); this.focus = focus; this.key = key; historySeries = emptyList(); plot.setEmpty(); redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; if (!value) finishInspection(); redraw()
    }
    fun pause(reason: String) { finishInspection(); state = reason; frozen = true; breakNext = true; redraw() }

    fun showSample(sample: DiffPageRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading = sample.reading
        val snapshot = reading?.snapshot
        if (!sample.output.connected || sample.output.globalIssue != null || reading == null || !reading.enabled ||
            reading.processing != OutputRuntimeStatus.Processing.ACTIVE || sample.streamIdentity == null || snapshot == null || !snapshot.ready) {
            current = null; frozen = false; breakNext = true
            state = sample.unavailableReason ?: "等待就绪的差分延时统计窗口"; redraw(); return
        }
        val id = sample.streamIdentity
        if (source != id || previousRate != reading.rate) {
            source = id; previousFrames = null; previousRate = reading.rate; breakNext = true
        }
        val frames = reading.frames?.takeIf { it >= 0 }
        val old = previousFrames
        val progressed = frames != null && (old == null || frames > old || (old >= 0x70000000 && frames <= 0x0fffffff))
        frozen = false
        if (progressed) {
            val split = breakNext || history.lastOrNull()?.let { sample.elapsedRealtimeMs - it.time > 500L } == true
            val frame = Frame(sample.elapsedRealtimeMs, id, frames!!, reading.rate, split, snapshot)
            current = frame; history.addLast(frame); previousFrames = frames; breakNext = false
            state = if (snapshot.switching) "正在平滑切换；仪表为真实处理中读数" else ""
            prune()
        } else if (frames != null && old != null && frames < old) {
            previousFrames = null; current = null; breakNext = true; state = "处理帧正在重新同步"
        }
        if (history.lastOrNull()?.let { anchor - it.time > 500L } == true) {
            current = null; breakNext = true; state = "等待新的真实仪表读数"
        }
        redraw()
    }

    private fun prune() {
        while (history.isNotEmpty() && (history.size > 200 || history.first().time < anchor - 20000L)) history.removeFirst()
    }
    private fun advanced(f: Frame?) = f?.snapshot?.advanced?.takeIf { it.ready }
    private fun level(raw: Int?): Double? = raw?.takeIf { it != Int.MIN_VALUE }?.div(100.0)
    private fun corr(raw: Int?): Double? = raw?.takeIf { it != Int.MIN_VALUE && it in -10000..10000 }?.div(10000.0)
    private fun f(value: Double?, places: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${places}f", it) } ?: "—"
    private fun redraw() { if (canDraw) invalidate() }
    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float,
        color: Int = ink, align: Paint.Align = Paint.Align.LEFT) {
        if (space <= 0f) return
        val size = text.textSize; text.color = color; text.textAlign = align
        val width = text.measureText(value); if (width > space) text.textSize *= space / width
        c.drawText(value, x, y, text); text.textSize = size
    }
    private fun dot(c: Canvas, x: Float, y: Float, color: Int, radius: Float = 2.6f) {
        fill.color = color; c.drawCircle(x, y, radius * d, fill)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(8f * resources.displayMetrics.scaledDensity, 9f * d)
            else min(9f * resources.displayMetrics.scaledDensity, 10.5f * d)
        historySeries = emptyList(); plot.setEmpty()
        if (height < 20 || width < 20) return
        when (focus) {
            DiffChartFocus.DELAY, DiffChartFocus.QUALITY -> drawEnergyField(canvas)
            DiffChartFocus.MIX -> drawMix(canvas)
            DiffChartFocus.LEVEL -> drawLevels(canvas)
            DiffChartFocus.BAND, DiffChartFocus.MONITOR -> drawBands(canvas)
            DiffChartFocus.GUARD -> drawGuard(canvas)
            DiffChartFocus.TRANSIENT -> drawTransient(canvas)
            DiffChartFocus.AUDITION -> drawAudition(canvas)
        }
        if (frozen) label(canvas, "$state · 末次真实读数冻结", width * .5f, height - 2f * d,
            width - 5f * d, muted, Paint.Align.CENTER)
        if (!frozen && current == null) label(canvas, state, width * .5f, height * .55f,
            width - 10f * d, muted, Paint.Align.CENTER)
        drawInspection(canvas)
    }

    private fun drawEnergyField(c: Canvas) {
        val s = current?.snapshot
        label(c, "相对声场开合 · 来自 Mid / Side 能量", 2f * d, 10f * d, width - 4f * d)
        val leftWidth = width * .43f
        val radius = min(leftWidth * .46f, height - 47f * d).coerceAtLeast(d)
        val cx = leftWidth * .5f; val cy = height - 25f * d
        for (part in listOf(.33f, .66f, 1f)) {
            c.drawArc(RectF(cx - radius * part, cy - radius * part, cx + radius * part, cy + radius * part), 180f, 180f, false, grid)
        }
        c.drawLine(cx, cy - radius, cx, cy, grid)
        val mid = level(s?.midRms); val side = level(s?.sideRms)
        // In a ready native RMS window, MIN is zero/below the meter's power floor, not 0 dBFS.
        val midOnly = mid != null && s?.sideRms == Int.MIN_VALUE
        val sideOnly = side != null && s?.midRms == Int.MIN_VALUE
        if (mid != null && side != null || midOnly || sideOnly) {
            val half = when {
                midOnly -> 0.0
                sideOnly -> PI / 2
                else -> atan(10.0.pow(((side!! - mid!!) / 20.0).coerceIn(-12.0, 12.0)))
            }
            val maximum = max(mid ?: Double.NEGATIVE_INFINITY, side ?: Double.NEGATIVE_INFINITY)
            val db = maximum + 10 * log10((mid?.let { 10.0.pow((it - maximum) / 10) } ?: 0.0) +
                (side?.let { 10.0.pow((it - maximum) / 10) } ?: 0.0))
            val alpha = (30 + (db + 80).coerceIn(0.0, 80.0) * 1.4).toInt().coerceIn(30, 150)
            path.rewind(); path.moveTo(cx, cy)
            for (i in 0..48) {
                val angle = PI / 2 - half + 2 * half * i / 48
                path.lineTo(cx - radius * cos(angle).toFloat(), cy - radius * sin(angle).toFloat())
            }
            path.close(); fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) alpha / 2 else alpha); c.drawPath(path, fill)
            if (midOnly) {
                line.color = ColorUtils.setAlphaComponent(purple, if (frozen) alpha else max(100, alpha))
                c.drawLine(cx, cy - radius, cx, cy, line)
            }
        }
        val ratioCaption = when {
            midOnly -> "Side 低于计量下限 · 中央集中"
            sideOnly -> "Mid 低于计量下限 · 侧向集中"
            mid == null && side == null && s != null -> "静音或 M/S 能量低于计量下限"
            else -> "Side/Mid ${f(if (mid != null && side != null) side - mid else null)} dB"
        }
        label(c, ratioCaption, cx, cy + 12f * d,
            leftWidth - 4f * d, purple, Paint.Align.CENTER)
        label(c, "青输入 / 紫输出 · 相关度 ρ", width * .48f, 24f * d, width * .5f, muted)
        drawHistory(c, RectF(width * .53f, 33f * d, width - 5f * d, height - 21f * d), -1.0, 1.0, "ρ",
            listOf(Series("输入", cyan) { corr(it.snapshot.inputCorrelation) }, Series("输出", purple) { corr(it.snapshot.outputCorrelation) }))
    }

    private fun drawLevels(c: Canvas) {
        val s = current?.snapshot
        val a = advanced(current)
        label(c, "青：输入 · 紫：正常输出 · dBFS", 2f * d, 10f * d, width - 4f * d)
        val r = RectF(30f * d, 25f * d, width - 5f * d, height - 34f * d)
        val low = -90.0; val high = 6.0
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (db in listOf(-90.0, -45.0, 0.0)) {
            val yy = y(db); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(db, 0), r.left - 4f * d, yy + 3f * d, r.left - 6f * d, muted, Paint.Align.RIGHT)
        }
        val groups = listOf(Triple("Mid", level(s?.inputMidRms), level(s?.midRms)),
            Triple("Side", level(s?.inputSideRms), level(s?.sideRms)), Triple("峰值", level(s?.inputPeak), level(s?.peak)))
        groups.forEachIndexed { index, item ->
            val step = r.width() / 3; val cx = r.left + step * (index + .5f); val bar = min(17f * d, step * .2f)
            fun draw(value: Double?, left: Float, color: Int) { value?.let {
                fill.color = ColorUtils.setAlphaComponent(if (it > 0) red else color, if (frozen) 90 else 190)
                c.drawRect(left, y(it), left + bar, r.bottom, fill)
            } }
            draw(item.second, cx - bar - 2f * d, cyan); draw(item.third, cx + 2f * d, purple)
            label(c, "${f(item.second)} / ${f(item.third)}", cx, r.top + 10f * d, step - 4f * d,
                if (item.third?.let { it > 0 } == true) red else purple, Paint.Align.CENTER)
            label(c, item.first, cx, r.bottom + 12f * d, step - 3f * d, ink, Paint.Align.CENTER)
        }
        label(c, "实际混合 ${f(a?.actualMix?.div(10.0))}% · 100 ms 原生统计", 2f * d, height - 6f * d, width - 4f * d, muted)
    }

    private fun drawMix(c: Canvas) {
        val s = current?.snapshot
        label(c, "实际混合 ${f(advanced(current)?.actualMix?.div(10.0))}% · 青输入 / 紫输出", 2f * d, 10f * d, width - 4f * d)
        label(c, "M/S 与峰值 · dBFS", 2f * d, 23f * d, width * .45f, muted)
        val r = RectF(25f * d, 33f * d, width * .45f, height - 22f * d)
        fun y(db: Double) = r.bottom - ((db.coerceIn(-90.0, 6.0) + 90.0) / 96.0).toFloat() * r.height()
        for (db in listOf(-90.0, -45.0, 0.0)) {
            val yy = y(db); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(db, 0), r.left - 3f * d, yy + 3f * d, r.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        val groups = listOf(Triple("Mid", level(s?.inputMidRms), level(s?.midRms)),
            Triple("Side", level(s?.inputSideRms), level(s?.sideRms)), Triple("峰值", level(s?.inputPeak), level(s?.peak)))
        groups.forEachIndexed { index, item ->
            val step = r.width() / 3; val center = r.left + step * (index + .5f); val bar = min(8f * d, step * .24f)
            fun draw(value: Double?, left: Float, color: Int) { value?.let {
                fill.color = ColorUtils.setAlphaComponent(if (it > 0) red else color, if (frozen) 90 else 190)
                c.drawRect(left, y(it), left + bar, r.bottom, fill)
            } }
            draw(item.second, center - bar - d, cyan); draw(item.third, center + d, purple)
            label(c, item.first, center, r.bottom + 12f * d, step - d, ink, Paint.Align.CENTER)
        }
        label(c, "实际混合量 · %", width * .55f, 23f * d, width * .43f, purple)
        drawHistory(c, RectF(width * .55f, 33f * d, width - 5f * d, height - 22f * d), 0.0, 100.0, "%",
            listOf(Series("实际混合", purple) { advanced(it)?.actualMix?.div(10.0) }))
    }

    private fun drawBands(c: Canvas) {
        val a = advanced(current)
        val energy = focus == DiffChartFocus.MONITOR
        val fold = key.contains("fold")
        val low = if (energy) -90.0 else if (fold) -24.0 else -1.0
        val high = if (energy || fold) 0.0 else 1.0
        val name = if (energy) "Side RMS · dBFS" else if (fold) "单声道合并差 · dB" else "相关度 ρ"
        label(c, "低 / 中 / 高频监测 · $name", 2f * d, 10f * d, width - 4f * d)
        label(c, "青：对齐原声 / 灰：保护前 / 紫：实际输出", 2f * d, 22f * d, width - 4f * d, muted)
        val r = RectF(30f * d, 34f * d, width - 5f * d, height - 31f * d)
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (i in 0..2) {
            val value = low + (high - low) * i / 2; val yy = y(value)
            c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(value, if (energy || fold) 0 else 1), r.left - 4f * d, yy + 3f * d, r.left - 6f * d, muted, Paint.Align.RIGHT)
        }
        val colors = intArrayOf(cyan, muted, purple)
        for (band in 0..2) {
            val step = r.width() / 3; val center = r.left + step * (band + .5f); val bar = min(12f * d, step * .17f)
            for (signal in 0..2) {
                val b = a?.band(band, signal)
                val value = if (energy) level(b?.side) else if (fold) level(b?.fold) else corr(b?.correlation)
                value?.let {
                    val left = center + (signal - 1) * (bar + 2f * d) - bar / 2
                    val base = if (energy || fold) r.bottom else y(0.0)
                    fill.color = ColorUtils.setAlphaComponent(colors[signal], if (frozen) 85 else 200)
                    c.drawRect(left, min(base, y(it)), left + bar, max(base, y(it)), fill)
                    if (signal == 2) label(c, f(it, if (energy || fold) 1 else 2), center, r.top + 10f * d,
                        step - 4f * d, purple, Paint.Align.CENTER)
                }
            }
            val title = when (band) {
                0 -> "低 <${a?.low ?: "—"}Hz"
                1 -> "中频"
                else -> "高 >${a?.high ?: "—"}Hz"
            }
            label(c, title, center, r.bottom + 13f * d, step - 3f * d, ink, Paint.Align.CENTER)
            val normal = a?.band(band, 2)
            val extra = if (energy) "M/S ${f(level(normal?.mid))}/${f(level(normal?.side))}"
                else "输出折叠 ${f(level(normal?.fold))}dB"
            label(c, extra, center, r.bottom + 25f * d, step - 3f * d, muted, Paint.Align.CENTER)
        }
        if (a == null) label(c, "等待三频段监测数据", r.centerX(), r.centerY(), r.width() - 5f * d, muted, Paint.Align.CENTER)
    }

    private fun drawGuard(c: Canvas) {
        val a = advanced(current)
        val s = current?.snapshot
        val useFold = key.contains("fold") || DiffAdvancedParameters.detector(values) == 1
        val base = if (useFold) level(a?.values?.getOrNull(13)) else corr(a?.values?.getOrNull(11))
        val candidate = if (useFold) level(a?.values?.getOrNull(14)) else corr(a?.values?.getOrNull(12))
        val output = if (useFold) level(s?.monoFold) else corr(s?.outputCorrelation)
        label(c, "${if (useFold) "折叠 dB" else "相关度 ρ"} · 基线 ${f(base, 2)} / 候选 ${f(candidate, 2)} / 输出 ${f(output, 2)}", 2f * d, 10f * d, width - 4f * d)
        val triggered = if (a == null) "—" else if (a.guardActive) "触发" else "未触发/恢复"
        label(c, "回收 ${f(a?.withdrawal?.div(10.0))}% · 实际混合 ${f(a?.actualMix?.div(10.0))}% · $triggered", 2f * d, 23f * d, width - 4f * d, purple)
        drawHistory(c, RectF(31f * d, 35f * d, width - 5f * d, height - 21f * d), 0.0, 100.0, "%",
            listOf(Series("回收", purple) { advanced(it)?.withdrawal?.div(10.0) }, Series("实际混合", cyan) { advanced(it)?.actualMix?.div(10.0) }))
        if (a == null && !frozen) label(c, "等待就绪的兼容保护仪表", width * .5f, height * .6f, width - 8f * d, muted, Paint.Align.CENTER)
    }

    private fun drawTransient(c: Canvas) {
        val a = advanced(current)
        val detected = if (a == null) "—" else if (a.transientDetected) "已检测" else "未触发"
        label(c, "真实瞬态保护 ${f(a?.protection?.div(10.0))}% · 起音 $detected", 2f * d, 11f * d, width - 4f * d)
        label(c, "紫：保护比例 / 青：实际混合量", 2f * d, 24f * d, width - 4f * d, muted)
        drawHistory(c, RectF(31f * d, 35f * d, width - 5f * d, height - 21f * d), 0.0, 100.0, "%",
            listOf(Series("保护", purple) { advanced(it)?.protection?.div(10.0) }, Series("实际混合", cyan) { advanced(it)?.actualMix?.div(10.0) }))
    }

    private fun drawAudition(c: Canvas) {
        val s = current?.snapshot
        label(c, "输入 ρ ${f(corr(s?.inputCorrelation), 2)} · 输出 ρ ${f(corr(s?.outputCorrelation), 2)}", 2f * d, 10f * d, width - 4f * d)
        label(c, "试听前正常输出 · 青输入折叠 / 紫输出折叠 · dB", 2f * d, 23f * d, width - 4f * d, muted)
        drawHistory(c, RectF(31f * d, 35f * d, width - 5f * d, height - 21f * d), -24.0, 0.0, "dB",
            listOf(Series("输入折叠", cyan) { level(it.snapshot.inputMonoFold) }, Series("输出折叠", purple) { level(it.snapshot.monoFold) }))
    }

    private fun drawHistory(c: Canvas, r: RectF, low: Double, high: Double, unit: String, series: List<Series>) {
        if (r.height() <= 2f * d || r.width() <= 2f * d) return
        plot.set(r); historySeries = series; historyUnit = unit
        val points = held ?: history.toList(); val end = if (held != null) heldEnd else anchor
        fun x(t: Long) = r.right - ((end - t) / 20000.0).toFloat() * r.width()
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (i in 0..2) {
            val v = low + (high - low) * i / 2; val yy = y(v); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(v, if (unit == "ρ") 1 else 0), r.left - 3f * d, yy + 3f * d, r.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        for (i in 0..4) {
            val xx = r.left + r.width() * i / 4; c.drawLine(xx, r.top, xx, r.bottom, grid)
            label(c, if (i == 4) "现在" else if (i == 0) "−20s" else "−${20 - i * 5}", xx, r.bottom + 12f * d,
                r.width() / 4, muted, if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        c.save(); c.clipRect(r)
        for (s in series) {
            path.rewind(); var started = false; var previous: Frame? = null
            for (p in points) {
                val v = s.read(p)
                if (v == null || !v.isFinite()) { started = false; previous = p; continue }
                val split = p.split || previous?.let { it.source != p.source || it.rate != p.rate || p.time - it.time > 500L } == true
                if (!started || split) path.moveTo(x(p.time), y(v)) else path.lineTo(x(p.time), y(v))
                started = true; previous = p
            }
            line.color = ColorUtils.setAlphaComponent(s.color, if (frozen) 95 else 235); c.drawPath(path, line)
            selected?.let { p -> s.read(p)?.let { v -> dot(c, x(p.time), y(v), s.color) } }
        }
        selected?.let { p -> c.drawLine(x(p.time), r.top, x(p.time), r.bottom, grid) }
        c.restore()
        if (!frozen && points.none { p -> series.any { it.read(p) != null } }) label(c, "等待真实仪表读数", r.centerX(), r.centerY(), r.width() - 5f * d, muted, Paint.Align.CENTER)
    }

    private fun drawInspection(c: Canvas) {
        val p = selected ?: return
        val rows = listOf("−${f((heldEnd - p.time) / 1000.0, 2)} s · 原生仪表读数") +
            historySeries.map { "${it.name} ${f(it.read(p), 2)} $historyUnit" }
        val pad = 4f * d; val gap = text.fontSpacing
        val w = min(width - 2f * d, (rows.maxOfOrNull { text.measureText(it) } ?: 0f) + pad * 2)
        val h = rows.size * gap + pad * 2
        val left = plot.left.coerceIn(d, max(d, width - w - d)); val top = plot.top.coerceAtMost(max(d, height - h - d))
        fill.color = ColorUtils.setAlphaComponent(surface, 245)
        c.drawRoundRect(RectF(left, top, left + w, top + h), 3f * d, 3f * d, fill)
        rows.forEachIndexed { i, row -> label(c, row, left + pad, top + pad - text.ascent() + i * gap, w - pad * 2) }
    }
    private fun select(x: Float) {
        val points = held ?: return
        val target = heldEnd - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000L).toLong()
        selected = points.filter { p -> historySeries.any { it.read(p) != null } }.minByOrNull { abs(it.time - target) }; redraw()
    }
    private fun finishInspection() {
        removeCallbacks(hold); pending = false
        if (inspecting) parent?.requestDisallowInterceptTouchEvent(false)
        inspecting = false; held = null; selected = null
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishInspection()
                if (historySeries.isEmpty() || !plot.contains(event.x, event.y) || history.isEmpty()) return false
                downX = event.x; downY = event.y; lastX = event.x; pending = true
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { finishInspection(); return false }
                lastX = event.x
                if (inspecting) { select(lastX); return true }
                if (pending && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) { pending = false; removeCallbacks(hold) }
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
