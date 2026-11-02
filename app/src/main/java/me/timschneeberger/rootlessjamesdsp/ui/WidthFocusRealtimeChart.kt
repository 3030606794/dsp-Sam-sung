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
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedRuntime
import me.timschneeberger.rootlessjamesdsp.fusion.WidthChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.WidthFocusRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters
import java.util.Locale
import kotlin.math.*

/** Native width instruments and, when enabled, real normal-output L/R observations before audition. */
internal class WidthFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val rate: Int, val frames: Int,
        val epoch: Int, val split: Boolean, val basic: List<Int>, val extra: List<Int>?)
    private data class Series(val name: String, val color: Int, val read: (Frame) -> Double?)
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104, 216, 236) else Color.rgb(16, 124, 146)
    private val amber = if (dark) Color.rgb(255, 198, 118) else Color.rgb(169, 102, 22)
    private val red = if (dark) Color.rgb(255, 142, 142) else Color.rgb(180, 42, 42)
    private val colors = intArrayOf(cyan, purple, amber, purple)
    private val muted = ColorUtils.setAlphaComponent(ink, 150)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 38); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val path = Path()
    private val plot = RectF()
    private val historyPanel = RectF()
    private var focus = WidthChartFocus.WIDTH
    private var key = WidthParameters.MODE
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var active = false
    private val canDraw get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    private val compact get() = height < 115f * d
    private val history = ArrayDeque<Frame>()
    private var current: Frame? = null
    private var source: Any? = null
    private var previousRate: Int? = null
    private var previousFrames: Int? = null
    private var previousEpoch: Int? = null
    private var cloudFrames: Int? = null
    private var cloud: List<Int>? = null
    private var cloudTime = 0L
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
    private val durationMs get() = ((values["width2_history"] as? Number)?.toLong() ?: 10L).coerceIn(5L, 30L) * 1000L
    private val hold = Runnable {
        if (pending && isShown && history.isNotEmpty() && plot.contains(downX, downY) && historySeries.isNotEmpty()) {
            pending = false; inspecting = true; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(lastX)
        }
    }

    fun setConfiguration(values: Map<String, *>, rate: Int) {
        val nextRate = rate.takeIf { it in 8000..768000 } ?: 48000
        if (this.values == values && this.rate == nextRate) return
        this.values = values.toMap(); this.rate = nextRate
        if (values["width2_meter_on"] != true) cloud = null
        finishInspection(); prune(); redraw()
    }
    fun setFocus(focus: WidthChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        finishInspection(); this.focus = focus; this.key = key; historySeries = emptyList(); plot.setEmpty(); redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; if (!value) finishInspection(); redraw()
    }
    fun pause(reason: String) { finishInspection(); state = reason; frozen = true; breakNext = true; redraw() }

    fun showSample(sample: WidthFocusRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading = sample.reading
        val snapshot = reading?.snapshot
        if (!sample.output.connected || sample.output.globalIssue != null || sample.output.processing != OutputRuntimeStatus.Processing.ACTIVE ||
            sample.streamIdentity == null || reading == null || !reading.enabled || !reading.active || snapshot == null || !snapshot.enabled || !snapshot.ready) {
            current = null; cloud = null; frozen = false; breakNext = true
            state = sample.unavailableReason ?: "等待就绪的立体声宽度仪表"; redraw(); return
        }
        val basic = snapshot.data
        val id = sample.streamIdentity
        if (source !== id || previousRate != snapshot.rate) {
            source = id; previousRate = snapshot.rate; previousFrames = null; previousEpoch = null
            cloudFrames = null; cloud = null; breakNext = true
        }
        val frames = basic[26]
        val epoch = basic[1]
        val old = previousFrames
        val progressed = old == null || frames > old || old >= 0x70000000 && frames <= 0x0fffffff
        frozen = false
        if (progressed && previousEpoch != epoch) {
            val extra = reading.advanced?.data?.takeIf {
                it[5] == 1 && it[1] == epoch && it[2] == snapshot.rate && it[3] == basic[3] && it[4] == snapshot.mode && it[6] == frames
            }
            val split = breakNext || history.lastOrNull()?.let { anchor - it.time > 650L || it.basic[4] != basic[4] } == true
            val frame = Frame(anchor, id, snapshot.rate, frames, epoch, split, basic, extra)
            history.addLast(frame); current = frame; previousFrames = frames; previousEpoch = epoch; breakNext = false
            // The 64-point ring has no separate sequence. A matching new native count must cross
            // at least one native fs/8000 decimation interval before these observations can be new.
            val step = max(1, (snapshot.rate / 8000.0).roundToInt())
            val cloudOld = cloudFrames
            if (values["width2_meter_on"] == true && extra?.get(45) == 1 && (cloudOld == null || frames < cloudOld || frames.toLong() - cloudOld >= step)) {
                cloud = extra.subList(48, 176).toList(); cloudFrames = frames; cloudTime = anchor
            } else if (values["width2_meter_on"] != true || extra?.get(45) != 1 || anchor - cloudTime > 500L) cloud = null
            state = ""; prune()
        } else if (old != null && frames < old) {
            previousFrames = null; previousEpoch = null; cloudFrames = null; current = null; cloud = null
            breakNext = true; state = "处理帧正在重新同步"
        }
        if (history.lastOrNull()?.let { anchor - it.time > 650L } == true) {
            current = null; cloud = null; breakNext = true; state = "等待新的真实仪表读数"
        }
        redraw()
    }

    private fun prune() {
        while (history.isNotEmpty() && (history.size > 280 || history.first().time < anchor - durationMs)) history.removeFirst()
    }
    private fun raw(frame: Frame?, index: Int) = frame?.basic?.getOrNull(index)
    private fun number(value: Int?, divisor: Double = 10.0) = value?.takeIf { it != Int.MIN_VALUE }?.div(divisor)?.takeIf { it.isFinite() }
    private fun basic(frame: Frame?, index: Int) = number(raw(frame, index))
    private fun extra(frame: Frame?, index: Int, divisor: Double = 10.0) = number(frame?.extra?.getOrNull(index), divisor)
    private fun corr(frame: Frame?, index: Int) = number(raw(frame, index), 10000.0)?.takeIf { it in -1.0..1.0 }
    private fun bandCorr(frame: Frame?, index: Int) = extra(frame, index, 10000.0)?.takeIf { it in -1.0..1.0 }
    private fun metering(frame: Frame?) = values["width2_meter_on"] == true && frame?.extra?.getOrNull(45) == 1
    private fun f(value: Double?, digits: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
    private fun redraw() { if (canDraw) invalidate() }
    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float,
        color: Int = ink, align: Paint.Align = Paint.Align.LEFT) {
        if (space <= 0f) return
        text.color = color; text.textAlign = align
        val count = text.breakText(value, true, space, null)
        val shown = if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…"
        c.drawText(shown, x, y, text)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(8f * resources.displayMetrics.scaledDensity, 9f * d)
            else min(9f * resources.displayMetrics.scaledDensity, 10.5f * d)
        historySeries = emptyList(); plot.setEmpty(); historyPanel.setEmpty()
        if (height < 20 || width < 20) return
        when (focus) {
            WidthChartFocus.WIDTH, WidthChartFocus.MONO, WidthChartFocus.EQ -> drawField(canvas)
            WidthChartFocus.BANDS, WidthChartFocus.MONITOR -> drawBands(canvas)
            WidthChartFocus.LEVEL -> drawLevels(canvas)
            WidthChartFocus.AUTO -> drawAuto(canvas)
            WidthChartFocus.GUARD -> drawGuard(canvas)
            WidthChartFocus.DYNAMIC -> drawRecovery(canvas, 19, "动态宽度回收")
            WidthChartFocus.TRANSIENT -> drawRecovery(canvas, 23, "瞬态扩宽回收")
            WidthChartFocus.AUDITION -> drawAudition(canvas)
        }
        if (frozen) {
            fill.color = surface; canvas.drawRect(0f, height - 15f * d, width.toFloat(), height.toFloat(), fill)
            label(canvas, "$state · 末次真实读数冻结", width * .5f, height - 3f * d,
                width - 5f * d, muted, Paint.Align.CENTER)
        }
        if (!frozen && current == null) label(canvas, state, width * .5f, height * .55f,
            width - 10f * d, muted, Paint.Align.CENTER)
        drawInspection(canvas)
    }

    private fun drawField(c: Canvas) {
        label(c, "相对声场开合 · Mid / Side 能量", 2f * d, 10f * d, width - 4f * d)
        // Keep both instruments, including their captions and axis labels, in separate panels.
        // These bounds depend only on the View size, never on the native meter/sample state.
        val leftPanel = RectF(2f * d, 18f * d, width * .50f - 7f * d, height - 2f * d)
        val rightPanel = RectF(width * .50f + 5f * d, 18f * d, width - 2f * d, height - 16f * d)
        val leftSave = c.save()
        c.clipRect(leftPanel)
        val cx = leftPanel.centerX(); val cy = height - 33f * d
        val radius = min(leftPanel.width() * .46f, cy - leftPanel.top - 4f * d).coerceAtLeast(d)
        for (part in listOf(.33f, .66f, 1f)) c.drawArc(RectF(cx - radius * part, cy - radius * part,
            cx + radius * part, cy + radius * part), 180f, 180f, false, grid)
        c.drawLine(cx - radius, cy, cx + radius, cy, grid); c.drawLine(cx, cy, cx, cy - radius, grid)
        val mid = basic(current, 9); val side = basic(current, 10)
        val midOnly = mid != null && raw(current, 10) == Int.MIN_VALUE
        val sideOnly = side != null && raw(current, 9) == Int.MIN_VALUE
        if (mid != null && side != null || midOnly || sideOnly) {
            val half = when { midOnly -> 0.0; sideOnly -> PI / 2; else -> atan(10.0.pow(((side!! - mid!!) / 20).coerceIn(-12.0, 12.0))) }
            val db = basic(current, 14) ?: max(mid ?: -120.0, side ?: -120.0)
            val alpha = (30 + (db + 80).coerceIn(0.0, 80.0) * 1.3).toInt()
            path.rewind(); path.moveTo(cx, cy)
            for (i in 0..48) {
                val a = PI / 2 - half + 2 * half * i / 48
                path.lineTo(cx - radius * cos(a).toFloat(), cy - radius * sin(a).toFloat())
            }
            path.close(); fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) alpha / 2 else alpha); c.drawPath(path, fill)
            if (midOnly) { line.color = ColorUtils.setAlphaComponent(purple, if (frozen) 95 else 235); c.drawLine(cx, cy, cx, cy - radius, line) }
        }
        var cloudCaption = when {
            current?.extra == null -> "等待高级声场监测"
            !metering(current) -> "开启原生“分段监测与声场图”可看 L/R"
            else -> "等待新的 L/R 观察点"
        }
        cloud?.takeIf { frozen || anchor - cloudTime <= 500L }?.let { points ->
            val peak = points.maxOfOrNull { abs(it.toDouble()) }?.div(1000000.0) ?: 0.0
            if (peak > 0.0) {
                path.rewind()
                for (i in 0 until 64) {
                    val l = points[i * 2] / 1000000.0; val r = points[i * 2 + 1] / 1000000.0
                    val x = cx + radius * ((l - r) * .5 / peak).toFloat()
                    val y = cy - radius * (abs((l + r) * .5) / peak).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                line.color = ColorUtils.setAlphaComponent(cyan, if (frozen) 100 else 220); c.drawPath(path, line)
                cloudCaption = if (points.any { abs(it.toLong()) >= 1000000 }) "真 L/R · 观察点限幅 · 窗口归一化" else "真 L/R · M/S 投影 · 窗口归一化"
            } else cloudCaption = "L/R 静音或低于观察分辨率"
        }
        label(c, "S/M ${f(basic(current, 11))} dB", cx, cy + 12f * d, leftPanel.width() - 4f * d, purple, Paint.Align.CENTER)
        label(c, cloudCaption, leftPanel.left, height - 5f * d, leftPanel.width(), muted)
        c.restoreToCount(leftSave)
        val series = if (focus == WidthChartFocus.MONO && metering(current)) listOf(
            Series("低频输入", cyan) { if (metering(it)) bandCorr(it, 7) else null },
            Series("低频输出", purple) { if (metering(it)) bandCorr(it, 10) else null })
        else listOf(Series("输入", cyan) { corr(it, 5) }, Series("正常输出", purple) { corr(it, 6) })
        val rightSave = c.save()
        c.clipRect(rightPanel)
        label(c, if (focus == WidthChartFocus.MONO && metering(current)) "低频相关度 ρ" else "青输入 / 紫正常输出 · ρ",
            rightPanel.left + 2f * d, 24f * d, rightPanel.width() - 4f * d, muted)
        drawHistory(c, RectF(rightPanel.left + 24f * d, 34f * d,
            rightPanel.right - 3f * d, height - 40f * d), -1.0, 1.0, "ρ", series, rightPanel)
        c.restoreToCount(rightSave)
    }

    private fun drawLevels(c: Canvas) {
        label(c, "青：输入 · 紫：正常输出 · dBFS", 2f * d, 10f * d, width - 4f * d)
        val r = RectF(29f * d, 24f * d, width - 5f * d, height - 32f * d)
        fun y(v: Double) = r.bottom - ((v.coerceIn(-90.0, 6.0) + 90.0) / 96.0).toFloat() * r.height()
        for (db in listOf(-90.0, -45.0, 0.0)) {
            val yy = y(db); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(db, 0), r.left - 3f * d, yy + 3f * d, r.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        listOf(Triple("Mid", 7, 9), Triple("Side", 8, 10), Triple("RMS", 31, 14), Triple("峰值", 12, 13)).forEachIndexed { index, item ->
            val step = r.width() / 4; val cx = r.left + step * (index + .5f); val bar = min(14f * d, step * .19f)
            val input = basic(current, item.second); val output = basic(current, item.third)
            listOf(Triple(input, cx - bar - d, cyan), Triple(output, cx + d, purple)).forEach { (value, x, color) ->
                value?.let { fill.color = ColorUtils.setAlphaComponent(if (it > 0) red else color, if (frozen) 90 else 190)
                    c.drawRect(x, y(it), x + bar, r.bottom, fill) }
            }
            label(c, "${f(input)} / ${f(output)}", cx, r.top + 10f * d, step - 3f * d, purple, Paint.Align.CENTER)
            label(c, item.first, cx, r.bottom + 12f * d, step - 3f * d, ink, Paint.Align.CENTER)
        }
        label(c, "实际混合 ${f(basic(current, 28))}% · 增益 ${f(basic(current, 29))} dB", 2f * d, height - 6f * d, width - 4f * d, muted)
    }

    private fun drawAuto(c: Canvas) {
        label(c, "RMS 匹配补偿 ${f(basic(current, 20))} dB · 青输入 / 紫正常输出", 2f * d, 10f * d, width - 4f * d)
        val panel = width * .31f
        val db = basic(current, 20)
        val r = RectF(17f * d, 28f * d, panel - 10f * d, height - 30f * d)
        val mid = r.centerY(); c.drawLine(r.left, mid, r.right, mid, grid)
        for (i in 0..2) { val y = r.top + r.height() * i / 2; c.drawLine(r.left, y, r.right, y, grid) }
        db?.let { val y = mid - (it.coerceIn(-6.0, 6.0) / 12).toFloat() * r.height()
            fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) 90 else 190)
            c.drawRect(r.left + r.width() * .25f, min(mid, y), r.right - r.width() * .25f, max(mid, y), fill) }
        label(c, "+6 dB", r.centerX(), r.top - 4f * d, r.width(), muted, Paint.Align.CENTER)
        label(c, "−6 dB", r.centerX(), r.bottom + 12f * d, r.width(), muted, Paint.Align.CENTER)
        drawHistory(c, RectF(panel + 26f * d, 27f * d, width - 5f * d, height - 29f * d), -90.0, 6.0, "dBFS",
            listOf(Series("输入 RMS", cyan) { basic(it, 31) }, Series("正常输出 RMS", purple) { basic(it, 14) }))
        label(c, "补偿量与 RMS 使用独立刻度", 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawBands(c: Canvas) {
        // Basic meters remain useful while the user's separate native segmented meter is off.
        // A temporarily missing cross-epoch advanced reading must not change the layout.
        if (values["width2_meter_on"] != true) { drawField(c); return }
        val showRatio = focus == WidthChartFocus.BANDS || key == "width2_meter_on" || key == "width2_history"
        val isMeter = metering(current)
        label(c, if (showRatio) "分段 Side / Mid · 正常输出 dB" else "分段相关度 · 正常输出 ρ", 2f * d, 10f * d, width - 4f * d)
        label(c, if (isMeter) "青低频 / 紫中频 / 橙高频" else "等待新的分段监测数据", 2f * d, 23f * d, width - 4f * d, muted)
        val series = (0..2).map { band -> Series(listOf("低频", "中频", "高频")[band], colors[band]) { frame ->
            if (!metering(frame)) null else if (showRatio) extra(frame, 16 + band) else bandCorr(frame, 10 + band)
        } }
        drawHistory(c, RectF(34f * d, 31f * d, width - 5f * d, height - 29f * d),
            if (showRatio) -60.0 else -1.0, if (showRatio) 36.0 else 1.0, if (showRatio) "dB" else "ρ", series)
        val hzLow = raw(current, 21) ?: (values[WidthParameters.crossLow.key] as? Number)?.toInt()
        val hzHigh = raw(current, 22) ?: (values[WidthParameters.crossHigh.key] as? Number)?.toInt()
        label(c, "实际分界 ${hzLow ?: "—"} / ${hzHigh ?: "—"} Hz · 原生平滑 ${rawValue(WidthParameters.meterWindow.key, 300)} ms",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun activeBands(frame: Frame?) = if (raw(frame, 4) == 2) listOf(0, 1, 2) else listOf(3)
    private fun bandName(index: Int) = listOf("低频", "中频", "高频", "全频")[index]
    private fun rawValue(key: String, fallback: Int) = (values[key] as? Number)?.toInt() ?: fallback

    private fun drawRecovery(c: Canvas, start: Int, name: String) {
        val mode = raw(current, 4) ?: WidthParameters.choice(values, WidthParameters.MODE)
        val bands = if (mode == 2) listOf(0, 1, 2) else listOf(3)
        val requested = WidthChartFocus.dynamicBand(key)
        val maximum = bands.mapNotNull { extra(current, start + it) }.maxOrNull()
        label(c, "$name ${f(maximum)}% · 新增扩宽回收比例", 2f * d, 10f * d, width - 4f * d)
        val barRight = width * .32f
        val r = RectF(9f * d, 29f * d, barRight - 8f * d, height - 29f * d)
        bands.forEachIndexed { index, band ->
            val step = r.width() / bands.size; val cx = r.left + step * (index + .5f)
            val v = extra(current, start + band)
            val bar = min(20f * d, step * .44f)
            c.drawRect(cx - bar / 2, r.top, cx + bar / 2, r.bottom, grid)
            v?.let { fill.color = ColorUtils.setAlphaComponent(colors[band], if (frozen) 80 else 190)
                c.drawRect(cx - bar / 2, r.top, cx + bar / 2, r.top + r.height() * (it / 100).toFloat(), fill) }
            label(c, "${f(v)}%", cx, r.top - 4f * d, step - 2f * d, colors[band], Paint.Align.CENTER)
            label(c, bandName(band), cx, r.bottom + 12f * d, step - 2f * d,
                if (start == 19 && band == requested) purple else muted, Paint.Align.CENTER)
        }
        val series = bands.map { band -> Series(bandName(band), colors[band]) { frame ->
            if (band in activeBands(frame)) extra(frame, start + band) else null
        } }
        drawHistory(c, RectF(barRight + 25f * d, 29f * d, width - 5f * d, height - 29f * d), 0.0, 100.0, "%", series)
        val caption = when {
            mode == 0 -> "原版兼容模式 · 专业动态扩宽未激活"
            current != null && current?.extra == null -> "等待高级动态仪表"
            start == 23 -> "原生起音保护 · 回收仅作用于新增扩宽"
            else -> "原生动态包络 · 回收仅作用于新增扩宽"
        }
        label(c, caption, 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawGuard(c: Canvas) {
        val bands = activeBands(current)
        val guard = bands.mapNotNull { extra(current, 27 + it) }.maxOrNull()
        val recoveryName = if (guard != null) "保护回收" else "综合扩宽回收"
        label(c, "$recoveryName ${f(guard ?: basic(current, 19))}% · 青输入 / 紫输出相关度", 2f * d, 10f * d, width - 4f * d,
            if ((guard ?: basic(current, 19) ?: 0.0) > 0.0) red else ink)
        val series = listOf(Series("输入相关度", cyan) { corr(it, 5) }, Series("正常输出相关度", purple) { corr(it, 6) })
        val r = RectF(33f * d, 26f * d, width * .72f, height - 30f * d)
        drawHistory(c, r, -1.0, 1.0, "ρ", series)
        val threshold = rawValue(WidthParameters.threshold.key, 0) / 100.0
        val y = r.bottom - ((threshold + 1) / 2).toFloat() * r.height()
        line.color = ColorUtils.setAlphaComponent(red, 120); c.drawLine(r.left, y, r.right, y, line)
        val x = width * .85f; val top = 27f * d; val bottom = height - 30f * d
        c.drawRect(x - 9f * d, top, x + 9f * d, bottom, grid)
        (guard ?: basic(current, 19))?.let { v -> fill.color = ColorUtils.setAlphaComponent(red, if (frozen) 80 else 190)
            c.drawRect(x - 9f * d, bottom - (bottom - top) * (v / 100).toFloat(), x + 9f * d, bottom, fill) }
        label(c, "回收 %", x, bottom + 12f * d, width * .23f, red, Paint.Align.CENTER)
        val candidates = bands.mapNotNull { band -> bandCorr(current, 35 + band)?.let { "${bandName(band)} ${f(it, 2)}" } }
        label(c, if (candidates.isEmpty()) "参考阈值 ${f(threshold, 2)} · 实际回收 ${f(guard ?: basic(current, 19))}%"
            else "保护候选 ρ · ${candidates.joinToString(" / ")}", 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawAudition(c: Canvas) {
        val audition = raw(current, 24) ?: 0
        label(c, "试听：${WidthAdvancedRuntime.auditionLabel(audition)} · 以下测点在试听选路前", 2f * d, 10f * d, width - 4f * d)
        drawHistory(c, RectF(34f * d, 27f * d, width - 5f * d, height - 30f * d), -90.0, 6.0, "dBFS",
            listOf(Series("原声 RMS", cyan) { basic(it, 31) }, Series("正常输出 RMS", purple) { basic(it, 14) },
                Series("正常输出 Mid", amber) { basic(it, 9) }))
        val caption = if (audition >= 8) "A/B 当前参数下的正常处理读数；不代表两套配置同时测量" else "原声 / 正常输出 / Mid · 保留原生分量与 A/B 试听控制"
        label(c, caption, 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawHistory(c: Canvas, r: RectF, low: Double, high: Double, unit: String, series: List<Series>,
        panel: RectF? = null) {
        if (r.width() <= 0 || r.height() <= 0) return
        val panelBounds = panel ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        val panelSave = c.save()
        c.clipRect(panelBounds)
        historyPanel.set(panelBounds)
        plot.set(r); historySeries = series; historyUnit = unit
        val points = held ?: history.toList(); val end = if (held != null) heldEnd else anchor
        fun x(time: Long) = r.right - ((end - time).toDouble() / durationMs).toFloat() * r.width()
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (i in 0..2) {
            val v = low + (high - low) * i / 2; val yy = y(v); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(v, if (unit == "ρ") 1 else 0), r.left - 3f * d, yy + 3f * d,
                r.left - panelBounds.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        val seconds = durationMs / 1000.0
        for (i in 0..4) {
            val xx = r.left + r.width() * i / 4; c.drawLine(xx, r.top, xx, r.bottom, grid)
            val caption = if (i == 4) "现在" else "−${f(seconds * (4 - i) / 4, if (seconds.toInt() % 4 == 0 || i == 0) 0 else 1)}${if (i == 0) "s" else ""}"
            label(c, caption, xx, r.bottom + 12f * d, r.width() / 4, muted,
                if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        c.save(); c.clipRect(r)
        for (s in series) {
            path.rewind(); var started = false; var before: Frame? = null
            for (p in points) {
                val v = s.read(p)
                if (v == null || !v.isFinite()) { started = false; before = p; continue }
                val split = p.split || before?.let { it.source !== p.source || it.rate != p.rate || p.time - it.time > 650L || it.basic[4] != p.basic[4] } == true
                if (!started || split) path.moveTo(x(p.time), y(v)) else path.lineTo(x(p.time), y(v))
                started = true; before = p
            }
            line.color = ColorUtils.setAlphaComponent(s.color, if (frozen) 95 else 235); c.drawPath(path, line)
            selected?.let { p -> s.read(p)?.let { value -> fill.color = s.color; c.drawCircle(x(p.time), y(value), 2.5f * d, fill) } }
        }
        selected?.let { p -> c.drawLine(x(p.time), r.top, x(p.time), r.bottom, grid) }
        c.restore()
        if (!frozen && current != null && points.none { p -> series.any { it.read(p) != null } })
            label(c, if (focus == WidthChartFocus.BANDS || focus == WidthChartFocus.MONITOR) "等待分段监测数据" else "静音或等待有效仪表读数",
                r.centerX(), r.centerY(), r.width() - 5f * d, muted, Paint.Align.CENTER)
        c.restoreToCount(panelSave)
    }

    private fun drawInspection(c: Canvas) {
        val p = selected ?: return
        val rows = listOf("−${f((heldEnd - p.time) / 1000.0, 2)} s · 原生仪表") + historySeries.map { "${it.name} ${f(it.read(p), 2)} $historyUnit" }
        val pad = 4f * d; val gap = text.fontSpacing
        val bounds = historyPanel.takeUnless { it.isEmpty } ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        val w = min(bounds.width() - 2f * d, (rows.maxOfOrNull { text.measureText(it) } ?: 0f) + pad * 2)
        if (w <= 0f) return
        val h = rows.size * gap + pad * 2
        val left = plot.left.coerceIn(bounds.left + d, max(bounds.left + d, bounds.right - w - d))
        val top = plot.top.coerceIn(bounds.top + d, max(bounds.top + d, bounds.bottom - h - d))
        val panelSave = c.save()
        c.clipRect(bounds)
        fill.color = ColorUtils.setAlphaComponent(surface, 245)
        c.drawRoundRect(RectF(left, top, left + w, top + h), 3f * d, 3f * d, fill)
        rows.forEachIndexed { i, row -> label(c, row, left + pad, top + pad - text.ascent() + i * gap, w - pad * 2) }
        c.restoreToCount(panelSave)
    }
    private fun select(x: Float) {
        val points = held ?: return
        val target = heldEnd - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * durationMs).toLong()
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
