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
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundFocusRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalRuntime
import java.util.Locale
import kotlin.math.*

/** Real aggregate stereo instruments. No L/R, M/S, FFT or physical position is inferred. */
internal class SurroundFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val rate: Int, val frames: Int,
        val split: Boolean, val basic: List<Int>, val tools: List<Int>?, val toolsFresh: Boolean)
    private data class Series(val name: String, val color: Int, val read: (Frame) -> Double?)
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104, 216, 236) else Color.rgb(16, 124, 146)
    private val amber = if (dark) Color.rgb(255, 198, 118) else Color.rgb(169, 102, 22)
    private val red = if (dark) Color.rgb(255, 142, 142) else Color.rgb(180, 42, 42)
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
    private var focus = SurroundChartFocus.BAND
    private var key = "legacy_low"
    private var settings = SurroundController.defaults.first()
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
    private var sourceSessionId: Int? = null
    private var waitingForWindow = false
    private var toolsEpoch: Int? = null
    private var toolsTime = 0L
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

    fun setConfiguration(settings: SurroundController.Settings, rate: Int) {
        val validRate = rate.takeIf { it in 8000..768000 } ?: 48000
        if (this.settings == settings && this.rate == validRate) return
        this.settings = settings; this.rate = validRate; finishInspection(); redraw()
    }
    fun setFocus(focus: SurroundChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        finishInspection(); this.focus = focus; this.key = key; historySeries = emptyList(); plot.setEmpty(); redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; if (!value) finishInspection(); redraw()
    }
    fun pause(reason: String) { finishInspection(); state = reason; frozen = true; waitingForWindow = false; breakNext = true; redraw() }

    fun showSample(sample: SurroundFocusRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading = sample.reading
        val basic = reading?.snapshot?.data
        if (!sample.output.connected || sample.output.globalIssue != null || sample.output.processing != OutputRuntimeStatus.Processing.ACTIVE ||
            sample.streamIdentity == null || reading == null || !reading.enabled || !reading.active || basic == null || basic[3] != 1) {
            val outputSession = sample.output.activeSessions.singleOrNull()
            val sameOutput = sourceSessionId != null && outputSession?.sessionId == sourceSessionId &&
                outputSession?.rate == previousRate
            val hardUnavailable = !sample.output.connected || sample.output.globalIssue != null ||
                sample.output.activeSessions.size > 1 || sample.unavailableReason?.startsWith("多路") == true ||
                reading?.enabled == false || basic?.get(3)?.let { it != 1 } == true
            waitingForWindow = !hardUnavailable && sameOutput &&
                sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE &&
                current?.let { anchor - it.time <= 1000L } == true
            if (!waitingForWindow) current = null
            frozen = false; breakNext = true
            state = if (waitingForWindow) "" else sample.unavailableReason ?: "等待环绕处理仪表"
            redraw(); return
        }
        val id = sample.streamIdentity
        if (source !== id || previousRate != basic[2]) {
            source = id; previousRate = basic[2]; previousFrames = null; previousEpoch = null; toolsEpoch = null
            toolsTime = 0L; breakNext = true
        }
        sourceSessionId = sample.output.activeSessions.singleOrNull()?.sessionId
        val frames = basic[6]; val epoch = basic[1]; val before = previousFrames
        val progressed = before == null || frames > before || before >= 0x70000000 && frames <= 0x0fffffff
        frozen = false
        if (progressed && previousEpoch != epoch) {
            var tools = reading.listening?.takeIf { it[2] == basic[2] && it[3] == basic[3] && it[4] == basic[24] }
            val toolsFresh = tools != null && tools[1] != toolsEpoch
            if (toolsFresh) { toolsEpoch = tools!![1]; toolsTime = anchor }
            if (anchor - toolsTime > 650L) tools = null
            val split = breakNext || history.lastOrNull()?.let { anchor - it.time > 650L || it.basic[30] != basic[30] || it.basic[24] != basic[24] } == true
            val frame = Frame(anchor, id, basic[2], frames, split, basic, tools, toolsFresh)
            history.addLast(frame); current = frame; previousFrames = frames; previousEpoch = epoch
            breakNext = false; waitingForWindow = false; state = ""; prune()
        } else if (before != null && frames < before) {
            previousFrames = null; previousEpoch = null; toolsEpoch = null; current = null; waitingForWindow = false; breakNext = true
            state = "处理帧正在重新同步"
        }
        if (history.lastOrNull()?.let { anchor - it.time > 1000L } == true) {
            current = null; waitingForWindow = false; breakNext = true; state = "等待新的真实仪表读数"
        }
        redraw()
    }

    private fun prune() {
        while (history.isNotEmpty() && (history.size > 200 || history.first().time < anchor - 20000L)) history.removeFirst()
    }
    private fun number(value: Int?, divisor: Double = 10.0) = value?.takeIf { it != Int.MIN_VALUE }?.div(divisor)?.takeIf { it.isFinite() }
    private fun basic(frame: Frame?, index: Int) = number(frame?.basic?.getOrNull(index))
    private fun meterReady(frame: Frame?) = P.on(settings, "meters") && frame?.basic?.get(5) == 1 && frame.basic[29] == 1
    private fun level(frame: Frame?, index: Int) = if (meterReady(frame)) basic(frame, index) else null
    private fun corr(frame: Frame?, index: Int) = if (meterReady(frame)) number(frame?.basic?.getOrNull(index), 10000.0)?.takeIf { it in -1.0..1.0 } else null
    private fun tools(frame: Frame?, index: Int, divisor: Double = 1000.0) = number(frame?.tools?.getOrNull(index), divisor)
    private fun toolHistory(frame: Frame, index: Int, divisor: Double = 1000.0) = if (frame.toolsFresh) tools(frame, index, divisor) else null
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
        historySeries = emptyList(); plot.setEmpty()
        if (height < 20 || width < 20) return
        when (focus) {
            SurroundChartFocus.BAND, SurroundChartFocus.CROSSFEED, SurroundChartFocus.DELAY,
            SurroundChartFocus.GEOMETRY, SurroundChartFocus.DIAGNOSTIC -> drawCompatibility(canvas)
            SurroundChartFocus.LEVEL, SurroundChartFocus.MONITOR -> drawLevels(canvas)
            SurroundChartFocus.RMS -> drawRms(canvas)
            SurroundChartFocus.PEAK -> drawPeak(canvas)
            SurroundChartFocus.MATCH -> drawMatch(canvas)
            SurroundChartFocus.CALIBRATION -> drawCalibration(canvas)
            SurroundChartFocus.AUDITION -> drawAudition(canvas)
        }
        if (frozen) {
            fill.color = surface; canvas.drawRect(0f, height - 15f * d, width.toFloat(), height.toFloat(), fill)
            label(canvas, "$state · 末次真实读数冻结", width * .5f, height - 3f * d, width - 5f * d, muted, Paint.Align.CENTER)
        }
        if (!frozen && current != null) {
            fill.color = surface; canvas.drawRect(0f, height - 15f * d, width.toFloat(), height.toFloat(), fill)
            label(canvas, "显示最近有效处理窗口", width * .5f, height - 3f * d, width - 5f * d, muted, Paint.Align.CENTER)
        }
        if (!frozen && current == null) label(canvas, state, width * .5f, height * .55f, width - 10f * d, muted, Paint.Align.CENTER)
        drawInspection(canvas)
    }

    private fun drawCompatibility(c: Canvas) {
        label(c, "青输入 / 紫正常处理输出 · 相关度 ρ", 2f * d, 10f * d, width - 4f * d)
        val panel = width * .30f
        drawMeterBars(c, RectF(24f * d, 27f * d, panel - 6f * d, height - 31f * d),
            listOf(Triple("输入", level(current, 7), cyan), Triple("输出", level(current, 8), purple)))
        drawHistory(c, RectF(panel + 25f * d, 27f * d, width - 5f * d, height - 31f * d), -1.0, 1.0, "ρ",
            listOf(Series("输入相关度", cyan) { corr(it, 11) }, Series("正常输出相关度", purple) { corr(it, 12) }))
        val b = current?.basic
        val caption = when {
            !P.on(settings, "meters") -> "请在原生参数区开启“启用本模块监测”"
            !meterReady(current) -> "等待本模块 RMS / 相关度统计窗口"
            focus == SurroundChartFocus.DELAY || focus == SurroundChartFocus.GEOMETRY ->
                "支路延迟目标 ${f(number(b?.get(18)))} / ${f(number(b?.get(19)))} μs · 非实测声程"
            focus == SurroundChartFocus.CROSSFEED -> "抵消支路目标 ${f(number(b?.get(20), 1000.0), 3)} / ${f(number(b?.get(21), 1000.0), 3)} dB"
            else -> "有效频段目标 ${b?.get(16) ?: "—"}–${b?.get(17) ?: "—"} Hz · 测点在试听匹配前"
        }
        label(c, caption, 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawMeterBars(c: Canvas, r: RectF, bars: List<Triple<String, Double?, Int>>) {
        if (r.height() <= 0 || r.width() <= 0) return
        fun y(value: Double) = r.bottom - ((value.coerceIn(-90.0, 6.0) + 90.0) / 96.0).toFloat() * r.height()
        for (db in listOf(-90.0, -45.0, 0.0)) {
            val yy = y(db); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(db, 0), r.left - 3f * d, yy + 3f * d, r.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        bars.forEachIndexed { index, bar ->
            val step = r.width() / bars.size; val cx = r.left + step * (index + .5f); val half = min(12f * d, step * .23f)
            bar.second?.let { value -> fill.color = ColorUtils.setAlphaComponent(if (value > 0) red else bar.third, if (frozen) 90 else 190)
                c.drawRect(cx - half, y(value), cx + half, r.bottom, fill) }
            label(c, f(bar.second), cx, r.top + 9f * d, step - 2f * d, bar.third, Paint.Align.CENTER)
            label(c, bar.first, cx, r.bottom + 12f * d, step - 2f * d, muted, Paint.Align.CENTER)
        }
    }

    private fun drawLevels(c: Canvas) {
        label(c, "本模块立体声合计 · RMS / 采样峰值 · dBFS", 2f * d, 10f * d, width - 4f * d)
        drawMeterBars(c, RectF(31f * d, 27f * d, width * .45f, height - 31f * d), listOf(
            Triple("入RMS", level(current, 7), cyan), Triple("出RMS", level(current, 8), purple),
            Triple("入峰", level(current, 9), cyan), Triple("出峰", level(current, 10), purple)))
        drawHistory(c, RectF(width * .45f + 28f * d, 27f * d, width - 5f * d, height - 31f * d), -90.0, 6.0, "dBFS",
            listOf(Series("输入 RMS", cyan) { level(it, 7) }, Series("正常输出 RMS", purple) { level(it, 8) }))
        label(c, if (P.on(settings, "meters")) "青输入 / 紫正常输出 · 20 秒历史" else "本模块监测已关闭；未采集的电平保留为空",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawRms(c: Canvas) {
        label(c, "实际 RMS 补偿 ${f(basic(current, 13))} dB · 青输入 / 紫输出", 2f * d, 10f * d, width - 4f * d)
        drawHistory(c, RectF(31f * d, 27f * d, width * .47f, height - 31f * d), -6.0, 6.0, "dB",
            listOf(Series("RMS 补偿", purple) { basic(it, 13) }))
        drawHistory(c, RectF(width * .47f + 31f * d, 27f * d, width - 5f * d, height - 31f * d), -90.0, 6.0, "dBFS",
            listOf(Series("输入 RMS", cyan) { level(it, 7) }, Series("正常输出 RMS", purple) { level(it, 8) }))
        label(c, "RMS匹配${if (current?.basic?.get(27) == 1) "已启用" else "未启用"} · 补偿与电平分别标尺",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawPeak(c: Canvas) {
        val gain = basic(current, 14)
        label(c, "采样峰值保护 ${f(gain)} dB · 青输入 / 紫输出峰值", 2f * d, 10f * d, width - 4f * d,
            if (gain != null && gain < -.05) red else ink)
        drawHistory(c, RectF(31f * d, 27f * d, width * .47f, height - 31f * d), -36.0, 0.0, "dB",
            listOf(Series("峰值保护增益", red) { basic(it, 14) }))
        drawHistory(c, RectF(width * .47f + 31f * d, 27f * d, width - 5f * d, height - 31f * d), -90.0, 6.0, "dBFS",
            listOf(Series("输入峰值", cyan) { level(it, 9) }, Series("正常输出峰值", purple) { level(it, 10) }))
        label(c, "保护${if (current?.basic?.get(28) == 1) "已启用" else "未启用"} · 上限目标 ${f(P.value(settings, "peak_ceiling") / 10.0)} dBFS · 本模块采样峰值",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawMatch(c: Canvas) {
        val t = current?.tools
        val measuring = t?.get(13)
        val progress = tools(current, 14, 10.0)
        label(c, "A/B 等响度测量 · 当前试听增益 ${f(tools(current, 12), 3)} dB", 2f * d, 10f * d, width - 4f * d)
        val left = width * .43f
        for (i in 0..1) {
            val top = (29 + i * 32) * d
            val done = t?.get(6 + i) == 1
            val selectedGroup = measuring == 3 + i
            val percent = if (done) 100.0 else if (selectedGroup) progress else null
            val r = RectF(5f * d, top, left - 7f * d, top + 10f * d)
            c.drawRect(r, grid)
            percent?.let { fill.color = ColorUtils.setAlphaComponent(if (i == 0) cyan else purple, if (frozen) 80 else 180)
                c.drawRect(r.left, r.top, r.left + r.width() * (it / 100).toFloat(), r.bottom, fill) }
            val caption = "${if (i == 0) "A" else "B"} · ${if (done) "固定 ${f(tools(current, 10 + i), 3)} dB" else if (selectedGroup) "测量 ${f(progress)}%" else "待测量"}"
            label(c, caption, r.left, r.bottom + 12f * d, r.width(), if (i == 0) cyan else purple)
        }
        drawHistory(c, RectF(left + 28f * d, 27f * d, width - 5f * d, height - 31f * d), -12.0, 0.0, "dB",
            listOf(Series("试听匹配增益", purple) { toolHistory(it, 12) }))
        val caption = when {
            t == null -> "等待独立试听监测数据"
            t[21] == 1 -> "输入信号过低；等待有效音乐重新测量"
            t[20] == 1 -> "组间差异超过最大衰减；尚未完全等响"
            t[5] == 0 || !P.on(settings, "match_on") -> "等响度试听已关闭 · 原生 A/B 操作保留"
            else -> "分别试听 A、B 测量；完成后增益固定"
        }
        label(c, caption, 2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawCalibration(c: Canvas) {
        val t = current?.tools
        val mode = t?.get(15)
        val remaining = t?.get(16)?.toDouble()
        val labels = listOf("未播放测试信号", "左声道正弦", "右声道正弦", "双声道正弦", "带通噪声")
        label(c, "校准试听 · ${mode?.let { labels.getOrElse(it) { "等待确认" } } ?: "等待独立校准状态"}", 2f * d, 10f * d, width - 4f * d)
        val left = width * .42f
        val r = RectF(6f * d, 33f * d, left - 7f * d, 48f * d)
        c.drawRect(r, grid)
        if (remaining != null && mode != 0) {
            val ratio = remaining / P.value(settings, "cal_duration").toDouble()
            fill.color = ColorUtils.setAlphaComponent(purple, if (frozen) 80 else 180)
            c.drawRect(r.left, r.top, r.left + r.width() * ratio.coerceIn(0.0, 1.0).toFloat(), r.bottom, fill)
        }
        label(c, "剩余 ${if (mode == 0) "0" else f(remaining, 0)} ms", r.left, r.bottom + 15f * d, r.width(), purple)
        if (height >= 112f * d) {
            label(c, "正弦 ${t?.get(22) ?: "—"} Hz", r.left, r.bottom + 29f * d, r.width(), muted)
            label(c, "噪声 ${t?.get(17) ?: "—"}–${t?.get(18) ?: "—"} Hz", r.left, r.bottom + 43f * d, r.width(), muted)
        }
        drawHistory(c, RectF(left + 29f * d, 27f * d, width - 5f * d, height - 31f * d), 0.0, 30000.0, "ms",
            listOf(Series("校准剩余", purple) { if (it.tools?.get(15) != 0) toolHistory(it, 16, 1.0) else null }))
        label(c, if (t == null) "等待独立校准状态" else if (mode == 0) "需在原生操作区手动开始校准试听" else "当前为原生校准状态；图纸不触发测试声音",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawAudition(c: Canvas) {
        val audition = current?.basic?.get(24)
        val name = audition?.let { SurroundProfessionalRuntime.auditionLabels.getOrElse(it) { "等待确认" } } ?: "等待确认"
        label(c, "试听：$name · 测点在试听选路与等响度增益前", 2f * d, 10f * d, width - 4f * d)
        drawHistory(c, RectF(32f * d, 27f * d, width - 5f * d, height - 31f * d), -90.0, 6.0, "dBFS",
            listOf(Series("本模块输入 RMS", cyan) { level(it, 7) }, Series("正常处理输出 RMS", purple) { level(it, 8) },
                Series("正常处理输出峰值", amber) { level(it, 10) }))
        label(c, "当前试听匹配增益 ${f(tools(current, 12), 3)} dB · 青输入 / 紫正常输出 / 橙输出峰值",
            2f * d, height - 5f * d, width - 4f * d, muted)
    }

    private fun drawHistory(c: Canvas, r: RectF, low: Double, high: Double, unit: String, series: List<Series>) {
        if (r.width() <= 0 || r.height() <= 0) return
        // The most recently drawn plot owns the long-press cursor; the other plot remains visible.
        plot.set(r); historySeries = series; historyUnit = unit
        val points = held ?: history.toList(); val end = if (held != null) heldEnd else anchor
        fun x(time: Long) = r.right - ((end - time) / 20000.0).toFloat() * r.width()
        fun y(v: Double) = r.bottom - ((v.coerceIn(low, high) - low) / (high - low)).toFloat() * r.height()
        for (i in 0..2) {
            val v = low + (high - low) * i / 2; val yy = y(v); c.drawLine(r.left, yy, r.right, yy, grid)
            label(c, f(v, if (unit == "ρ") 1 else 0), r.left - 3f * d, yy + 3f * d, r.left - 5f * d, muted, Paint.Align.RIGHT)
        }
        for (i in 0..4) {
            val xx = r.left + r.width() * i / 4; c.drawLine(xx, r.top, xx, r.bottom, grid)
            label(c, if (i == 4) "现在" else if (i == 0) "−20s" else "−${20 - i * 5}", xx, r.bottom + 12f * d, r.width() / 4, muted,
                if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        c.save(); c.clipRect(r)
        for (s in series) {
            path.rewind(); var started = false; var before: Frame? = null
            for (p in points) {
                val v = s.read(p)
                if (v == null || !v.isFinite()) { started = false; before = p; continue }
                val split = p.split || before?.let { it.source !== p.source || it.rate != p.rate || p.time - it.time > 650L } == true
                if (!started || split) path.moveTo(x(p.time), y(v)) else path.lineTo(x(p.time), y(v))
                started = true; before = p
            }
            line.color = ColorUtils.setAlphaComponent(s.color, if (frozen) 95 else 235); c.drawPath(path, line)
            selected?.let { p -> s.read(p)?.let { value -> fill.color = s.color; c.drawCircle(x(p.time), y(value), 2.5f * d, fill) } }
        }
        selected?.let { p -> c.drawLine(x(p.time), r.top, x(p.time), r.bottom, grid) }
        c.restore()
        if (!frozen && current != null && points.none { p -> series.any { it.read(p) != null } }) label(c,
            if ((unit == "dBFS" || unit == "ρ") && !P.on(settings, "meters")) "本模块监测已关闭" else "等待有效仪表读数",
            r.centerX(), r.centerY(), r.width() - 5f * d, muted, Paint.Align.CENTER)
    }

    private fun drawInspection(c: Canvas) {
        val p = selected ?: return
        val rows = listOf("−${f((heldEnd - p.time) / 1000.0, 2)} s · 原生仪表") + historySeries.map { "${it.name} ${f(it.read(p), 2)} $historyUnit" }
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
