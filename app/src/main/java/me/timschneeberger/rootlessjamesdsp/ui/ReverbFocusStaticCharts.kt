package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Saved-target observers. Neither chart owns audio processing, parameter writes or listening operations. */
internal class ReverbParameterChart(context: Context) : ReverbFocusStaticBase(context, false)
internal class ReverbIllustrationChart(context: Context) : ReverbFocusStaticBase(context, true)

private data class ReverbFocusSnapshot(
    val settings: P.Settings, val rate: Int, val moduleOn: Boolean, val supported: Boolean, val advancedSupported: Boolean,
    val audition: Int, val matching: Boolean, val matchingReady: Boolean, val savedA: Boolean, val savedB: Boolean
) {
    val effectiveProfessional: Boolean get() = settings.professional && supported
    val effectiveAdvanced: A.Settings get() = if (advancedSupported) settings.advanced else A.Settings()
    fun legacyKey(key: String): Boolean = key in P.legacyKeys
    fun active(focus: ReverbChartFocus, key: String): Boolean {
        if (!moduleOn) return false
        if (legacyKey(key)) return !effectiveProfessional
        if (!supported) return false
        if (focus == ReverbChartFocus.AUDITION && key in setOf("v_reverb_pro_save_a", "v_reverb_pro_save_b")) return true
        if (key == P.PROFESSIONAL || key == P.ENABLED) return true
        if (!settings.professional) return false
        return when (focus) {
            ReverbChartFocus.ROOM -> !effectiveAdvanced.rtOn
            ReverbChartFocus.DECAY -> advancedSupported && when (key) {
                A.RT_ON, A.rt60.key -> settings.advanced.rtOn
                A.BANDS_ON, in A.bandNumbers.map { it.key } -> settings.advanced.bandsOn
                else -> true
            }
            ReverbChartFocus.TONE -> when (key) {
                P.damp.key -> !effectiveAdvanced.bandsOn
                P.LOW_CUT_ON, P.lowCut.key -> settings.lowCutOn
                P.HIGH_CUT_ON, P.highCut.key -> settings.highCutOn
                else -> true
            }
            ReverbChartFocus.PREDELAY -> settings.predelayOn
            ReverbChartFocus.MIX -> when (key) {
                P.wet.key, P.dry.key -> !settings.linked
                P.mix.key -> settings.linked
                else -> true
            }
            ReverbChartFocus.DUCK -> advancedSupported && settings.advanced.duckOn
            ReverbChartFocus.LEVEL -> settings.outputOn
            ReverbChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: ReverbChartFocus, key: String): String = when {
        !moduleOn -> "混响关闭 · 保存目标预览"
        legacyKey(key) && effectiveProfessional -> "专业参数接管 · 原五项预览"
        !supported -> "专业驱动未支持 · 参数预览"
        !settings.professional -> "原五项处理中 · 专业保存预览"
        focus in setOf(ReverbChartFocus.DECAY, ReverbChartFocus.DUCK) && !advancedSupported -> "增强驱动未支持 · 保存预览"
        focus == ReverbChartFocus.ROOM && effectiveAdvanced.rtOn -> "RT60 接管 · 房间大小保存预览"
        focus == ReverbChartFocus.TONE && key == P.damp.key && effectiveAdvanced.bandsOn -> "分频衰减接管 · 阻尼保存预览"
        key in setOf(A.RT_ON, A.rt60.key) && !settings.advanced.rtOn -> "秒制 RT60 关闭 · 目标预览"
        (key == A.BANDS_ON || key in A.bandNumbers.map { it.key }) && !settings.advanced.bandsOn -> "分频衰减关闭 · 目标预览"
        focus == ReverbChartFocus.PREDELAY -> "预延迟关闭 · 保存目标预览"
        key in setOf(P.LOW_CUT_ON, P.lowCut.key) -> "混响低切关闭 · 保存目标预览"
        key in setOf(P.HIGH_CUT_ON, P.highCut.key) -> "混响高切关闭 · 保存目标预览"
        focus == ReverbChartFocus.MIX && key in setOf(P.wet.key, P.dry.key) -> "干湿联动接管 · 独立值预览"
        focus == ReverbChartFocus.MIX && key == P.mix.key -> "干湿联动关闭 · 混合目标预览"
        focus == ReverbChartFocus.DUCK -> "湿声避让关闭 · 保存目标预览"
        focus == ReverbChartFocus.LEVEL -> "输出增益关闭 · 保存目标预览"
        focus == ReverbChartFocus.MONITOR -> "实时电平关闭 · 测点示意"
        else -> "保存目标预览"
    }
    /** These copies exist only in the view. They never reach a controller or preferences. */
    fun target(focus: ReverbChartFocus, key: String): P.Settings {
        if (legacyKey(key)) return settings.copy(professional = false)
        if (key == P.PROFESSIONAL || key == P.ENABLED) return settings
        var target: P.Settings = settings.copy(professional = true,
            advanced = if (advancedSupported) settings.advanced else A.Settings())
        if (focus == ReverbChartFocus.ROOM) target = target.copy(advanced = target.advanced.copy(rtOn = false))
        if (key == P.damp.key) target = target.copy(advanced = target.advanced.copy(bandsOn = false))
        if (key == A.RT_ON || key == A.rt60.key) target = target.copy(advanced = target.advanced.copy(rtOn = true, rt60 = settings.advanced.rt60))
        if (key == A.BANDS_ON || key in A.bandNumbers.map { it.key }) target = target.copy(advanced = target.advanced.copy(
            bandsOn = true, bassRatio = settings.advanced.bassRatio, trebleRatio = settings.advanced.trebleRatio,
            lowCrossover = settings.advanced.lowCrossover, highCrossover = settings.advanced.highCrossover))
        if (focus == ReverbChartFocus.PREDELAY) target = target.copy(predelayOn = true)
        if (key == P.LOW_CUT_ON || key == P.lowCut.key) target = target.copy(lowCutOn = true)
        if (key == P.HIGH_CUT_ON || key == P.highCut.key) target = target.copy(highCutOn = true)
        if (key == P.wet.key || key == P.dry.key) target = target.copy(linked = false)
        if (key == P.mix.key) target = target.copy(linked = true)
        if (focus == ReverbChartFocus.LEVEL) target = target.copy(outputOn = true)
        if (focus == ReverbChartFocus.DUCK) target = target.copy(advanced = target.advanced.copy(
            duckOn = true, threshold = settings.advanced.threshold, depth = settings.advanced.depth,
            attack = settings.advanced.attack, release = settings.advanced.release))
        return target
    }
}

private data class ReverbStaticRequest(val settings: P.Settings, val rate: Int)
/** Bounded shared model cache, with one running task and a latest waiting target for each visible owner. */
private object ReverbStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "viper-reverb-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: ReverbStaticRequest, val callback: (ReverbDashboardModel) -> Unit)
    private class Owner {
        var running: ReverbStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<ReverbStaticRequest, ReverbDashboardModel> =
        object : LinkedHashMap<ReverbStaticRequest, ReverbDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReverbStaticRequest, ReverbDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: ReverbStaticRequest, callback: (ReverbDashboardModel) -> Unit): Unit {
        val state: Owner = owners.getOrPut(owner) { Owner() }
        if (state.running == request) { state.waiting = null; return }
        state.waiting = Task(request, callback)
        if (state.future == null) start(owner, state)
    }
    @Synchronized private fun start(owner: Any, state: Owner): Unit {
        val task: Task = state.waiting ?: return
        state.waiting = null; state.running = task.request
        state.future = executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            val result: ReverbDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                ReverbDashboardModel.from(values, task.request.rate, true)
            }
            if (Thread.currentThread().isInterrupted) return@submit
            val deliver: Boolean = synchronized(this) {
                if (owners[owner] !== state) false else {
                    cache[task.request] = result; state.running = null; state.future = null
                    if (state.waiting != null) start(owner, state)
                    true
                }
            }
            if (deliver) main.post { task.callback(result) }
        }
    }
}

internal open class ReverbFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(113, 216, 208) else Color.rgb(17, 125, 118)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 185, 109) else Color.rgb(162, 90, 23)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x2a) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var configuration: ReverbDashboardModel = ReverbDashboardModel.from(emptyMap<String, Any>())
    private var shown: ReverbDashboardModel = configuration
    private var snapshot: ReverbFocusSnapshot = snapshot(configuration)
    private var focus: ReverbChartFocus = ReverbChartFocus.ROOM
    private var key: String = "v_reverb_size"
    private var active: Boolean = true
    private var request: ReverbStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x79)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x61)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4d)
    private fun snapshot(model: ReverbDashboardModel): ReverbFocusSnapshot = ReverbFocusSnapshot(model.settings,
        model.rate, model.enabled, ReverbController.supported(context), ReverbController.advancedSupported(context),
        ReverbController.audition, ReverbController.match, ReverbController.matchingReady(),
        ReverbController.hasComparison(0), ReverbController.hasComparison(1))
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
        updateDescription()
    }
    fun setConfiguration(model: ReverbDashboardModel): Unit {
        configuration = model
        val next: ReverbFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: ReverbChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; ReverbStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; ReverbStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key).let { value ->
            value.copy(meters = false, advanced = value.advanced.copy(graphOn = false))
        }
        val next: ReverbStaticRequest = ReverbStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        ReverbStaticWork.request(this, next) { result ->
            if (active && token == generation) { shown = result; invalidate() }
        }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(frequency: Double): Float = px(ln(frequency.coerceIn(20.0, 20000.0) / 20) / ln(1000.0))
    private fun timeAt(position: Double): Double = 100 * (exp(position * ln(601.0)) - 1)
    private fun timeX(milliseconds: Double): Float = px(ln(1 + milliseconds.coerceAtLeast(0.0) / 100) / ln(601.0))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT): Unit {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count: Int = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String): Unit = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String): Unit = text(canvas,
        if (enabled) value else snapshot.reason(focus, key), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun grid(canvas: Canvas, top: String, bottom: String, left: String, right: String): Unit {
        canvas.drawRect(plot, gridPaint)
        for (index in 1..3) {
            canvas.drawLine(px(index / 4.0), plot.top, px(index / 4.0), plot.bottom, gridPaint)
            canvas.drawLine(plot.left, py(index / 4.0), plot.right, py(index / 4.0), gridPaint)
        }
        text(canvas, top, plot.left - d(3f), plot.top + d(3f), d(25f), align = Paint.Align.RIGHT)
        text(canvas, bottom, plot.left - d(3f), plot.bottom, d(25f), align = Paint.Align.RIGHT)
        text(canvas, left, plot.left, plot.bottom + d(11f), plot.width() / 2)
        text(canvas, right, plot.right, plot.bottom + d(11f), plot.width() / 2, align = Paint.Align.RIGHT)
    }
    private fun stroke(canvas: Canvas, tint: Int, dashed: Boolean = false): Unit {
        linePaint.color = tint; linePaint.pathEffect = if (dashed || !enabled) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore(); linePaint.pathEffect = null
    }
    private fun trace(canvas: Canvas, tint: Int = color, dashed: Boolean = false, value: (Double) -> Double): Unit {
        path.rewind(); var started: Boolean = false
        for (index in 0..160) {
            val x: Double = index / 160.0; val y: Double = value(x)
            if (!y.isFinite()) { started = false; continue }
            if (!started) { path.moveTo(px(x), py(y)); started = true } else path.lineTo(px(x), py(y))
        }
        stroke(canvas, tint, dashed)
    }
    private fun marker(canvas: Canvas, x: Float, tint: Int = color): Unit {
        linePaint.color = tint; linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, tint: Int = color): Unit {
        fillPaint.color = tint; canvas.drawCircle(x, y, d(2.3f), fillPaint)
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        ReverbChartFocus.ROOM, ReverbChartFocus.DECAY -> drawDecay(canvas)
        ReverbChartFocus.TONE -> drawTone(canvas)
        ReverbChartFocus.WIDTH -> drawWidth(canvas)
        ReverbChartFocus.PREDELAY -> drawPredelay(canvas)
        ReverbChartFocus.MIX -> drawMixCoefficients(canvas)
        ReverbChartFocus.DUCK -> drawDuck(canvas)
        ReverbChartFocus.LEVEL -> drawLevel(canvas)
        ReverbChartFocus.MONITOR -> drawMonitor(canvas)
        ReverbChartFocus.MATCH -> drawMatch(canvas)
        ReverbChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        ReverbChartFocus.ROOM, ReverbChartFocus.WIDTH -> drawRoom(canvas)
        ReverbChartFocus.DECAY -> drawTailRibbons(canvas)
        ReverbChartFocus.TONE -> drawToneCloud(canvas)
        ReverbChartFocus.PREDELAY -> drawArrival(canvas)
        ReverbChartFocus.MIX -> drawMixIllustration(canvas)
        ReverbChartFocus.DUCK -> drawDuckEnvelope(canvas)
        ReverbChartFocus.LEVEL -> drawOutputIllustration(canvas)
        ReverbChartFocus.MONITOR -> drawMonitorChannels(canvas)
        ReverbChartFocus.MATCH -> drawMeasurement(canvas)
        ReverbChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawDecay(canvas: Canvas): Unit {
        caption(canvas, if (focus == ReverbChartFocus.ROOM)
            "房间 " + fmt(shown.roomPercent, 1) + "% · 反馈 " + fmt(shown.feedback, 3)
            else "参考 RT " + fmt(shown.referenceMs / 1000, 2) + " s · 反馈预测")
        grid(canvas, "0 dB", "−90", "0", "60 s")
        val colors: List<Int> = listOf(color, secondary, third)
        repeat(3) { band: Int ->
            trace(canvas, colors[band], band == 1) { position: Double ->
                val time: Double = timeAt(position)
                if (time < shown.predelayMs) Double.NaN else (90 - 60 * (time - shown.predelayMs) / shown.decayMs[band].coerceAtLeast(1.0)) / 90
            }
            point(canvas, timeX(shown.predelayMs + shown.decayMs[band]), py(1.0 / 3), colors[band])
        }
        marker(canvas, timeX(shown.predelayMs), secondary)
        footer(canvas, "低／中／高 · −60 dB 时间 · 预测非实测")
    }
    private fun drawTone(canvas: Canvas): Unit {
        caption(canvas, "单回路损耗与湿声滤波 · dB")
        grid(canvas, "0 dB", "−60", "20 Hz", "20k Hz")
        val rows: List<DoubleArray> = listOf(shown.filterDb, shown.loopLossDb)
        rows.forEachIndexed { row: Int, values: DoubleArray ->
            path.rewind()
            values.forEachIndexed { index: Int, value: Double ->
                val x: Float = fx(shown.spectrumHz[index]); val y: Float = py((value + 60) / 60)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke(canvas, if (row == 0) color else secondary, row == 1)
        }
        if (shown.lowCutOn) marker(canvas, fx(shown.lowCutHz), third)
        if (shown.highCutOn) marker(canvas, fx(shown.highCutHz), third)
        if (shown.advanced.bandsOn) { marker(canvas, fx(shown.lowBandHz), secondary); marker(canvas, fx(shown.highBandHz), secondary) }
        footer(canvas, "紫 湿声输出滤波 · 青 单回路损耗")
    }
    private fun drawWidth(canvas: Canvas): Unit {
        caption(canvas, "宽度 " + fmt(shown.widthPercent, 1) + "% · M 保留")
        grid(canvas, "+1", "−1", "Side 输入 −1", "+1")
        val width: Double = shown.widthPercent / 100
        trace(canvas, secondary, true) { it }
        trace(canvas) { .5 + (it - .5) * width }
        footer(canvas, "同侧 " + fmt((1 + width) / 2, 2) + " · 交侧 " + fmt((1 - width) / 2, 2))
    }
    private fun drawPredelay(canvas: Canvas): Unit {
        caption(canvas, "只延后混响激励 · 附加时序")
        grid(canvas, "干声", "湿声", "0 ms", "200 ms")
        val delay: Double = shown.predelayMs
        linePaint.color = secondary; canvas.drawLine(plot.left, py(.8), plot.left, py(.55), linePaint)
        linePaint.color = color; canvas.drawLine(px(delay / 200), py(.45), px(delay / 200), py(.08), linePaint)
        point(canvas, px(delay / 200), py(.45))
        footer(canvas, fmt(delay, 1) + " ms · " + fmt(delay * shown.rate / 1000, 2) + " 样本")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / count
        val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawMixCoefficients(canvas: Canvas): Unit {
        caption(canvas, if (shown.settings.linked && shown.professional) "干湿联动 · 线性增益系数" else "独立干湿 · 原生增益系数")
        grid(canvas, "3×", "0", "", "")
        bar(canvas, 0, 2, shown.dryGain / 3, "干声", fmt(shown.dryGain, 2) + "×", secondary)
        bar(canvas, 1, 2, shown.wetGain / 3, "湿声", fmt(shown.wetGain, 2) + "×", color)
        footer(canvas, if (shown.settings.linked && shown.professional) "干 + 湿 = 1 · 非等功率淡化" else "干声 50% = 1× · 湿声 100% = 3×")
    }
    private fun drawDuck(canvas: Canvas): Unit {
        val settings: A.Settings = shown.settings.advanced
        caption(canvas, "整体输入电平触发 · 只衰减湿声")
        grid(canvas, "0 dB", "−24", "输入 −60", "0 dBFS")
        trace(canvas) { position: Double ->
            val level: Double = -60 + 60 * position
            val reduction: Double = settings.depth / 10.0 * ((level - settings.threshold / 10.0) / 12).coerceIn(0.0, 1.0)
            1 - reduction / 24
        }
        marker(canvas, px((settings.threshold / 10.0 + 60) / 60), secondary)
        footer(canvas, "阈值上方 12 dB 达到最大避让")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        caption(canvas, "混合后整体增益 · 干湿同时变化")
        grid(canvas, "+1", "−1", "输入 −1", "+1")
        trace(canvas, secondary, true) { it }
        trace(canvas) { .5 + (it - .5) * shown.outputGain }
        footer(canvas, fmt(20 * log10(shown.outputGain), 1) + " dB · 不含后续效果")
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color): Unit {
        val bounds: RectF = RectF(px(left), py(top), px(right), py(bottom))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24); canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, "输入 / 湿声 / 输出 · 采样峰值")
        box(canvas, "输入", .0, .95, .4, .66, secondary); box(canvas, "混响支路", .6, .95, 1.0, .66)
        box(canvas, "混合与输出", .0, .52, 1.0, .23, third)
        linePaint.color = secondary
        canvas.drawLine(px(.2), py(.66), px(.4), py(.52), linePaint); canvas.drawLine(px(.8), py(.66), px(.6), py(.52), linePaint)
        footer(canvas, "本模块测点 · 不是耳内声压或真峰值")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, "A 比 B 较响 · 固定衰减参考")
        grid(canvas, "0 dB", "−18", "相对差 0", "+18 dB")
        trace(canvas) { 1 - it }; trace(canvas, secondary, true) { 1.0 }
        footer(canvas, if (snapshot.matching) "固定匹配开启 · 只衰减较响组" else "匹配关闭 · 测完再开启")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前路径 · " + auditionName(snapshot.audition))
        val labels: List<String> = listOf("正常", "原声", "差分", "湿声", "快照 A", "快照 B", "测量")
        labels.forEachIndexed { index: Int, label: String ->
            val row: Int = index / 4; val column: Int = index % 4
            box(canvas, label, column / 4.0 + .015, .95 - row * .53, (column + 1) / 4.0 - .015, .62 - row * .53,
                if (index == snapshot.audition) color else secondary)
        }
        footer(canvas, "原生按钮管理试听 · 图纸只读")
    }
    /** Decorative room scale follows feedback strength; there is deliberately no physical distance axis. */
    private fun drawRoom(canvas: Canvas): Unit {
        caption(canvas, "反馈空间与湿声扩散 · 相对示意")
        val size: Double = shown.roomPercent / 100
        val walls: RectF = RectF(px(.14 - .10 * size), py(.94), px(.86 + .10 * size), py(.08))
        fillPaint.shader = LinearGradient(walls.left, walls.top, walls.right, walls.bottom,
            ColorUtils.setAlphaComponent(secondary, 0x1c), ColorUtils.setAlphaComponent(color, 0x06), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(walls, d(9f), d(9f), fillPaint); fillPaint.shader = null
        linePaint.color = ColorUtils.setAlphaComponent(secondary, 0x85)
        canvas.drawRoundRect(walls, d(9f), d(9f), linePaint)
        val inner: RectF = RectF(walls.left + d(4f), walls.top + d(4f), walls.right - d(4f), walls.bottom - d(4f))
        canvas.drawRoundRect(inner, d(7f), d(7f), gridPaint)
        val cx: Float = walls.centerX(); val base: Float = walls.bottom - d(6f)
        val radius: Float = min(walls.width() * .45f, walls.height() * .76f)
        val spread: Double = 4 + 78 * shown.widthPercent / 100
        for (ring in 3 downTo 1) {
            val r: Float = radius * ring / 3
            val oval: RectF = RectF(cx - r, base - r, cx + r, base + r)
            fillPaint.color = ColorUtils.setAlphaComponent(color, (14 + ring * 7 + shown.wetGain * 8).roundToInt().coerceIn(10, 90))
            canvas.drawArc(oval, (270 - spread).toFloat(), (2 * spread).toFloat(), true, fillPaint)
            linePaint.color = ColorUtils.setAlphaComponent(color, 65 + ring * 35)
            canvas.drawArc(oval, (270 - spread).toFloat(), (2 * spread).toFloat(), false, linePaint)
        }
        path.rewind(); path.moveTo(cx, base - radius * .48f); path.lineTo(inner.left, inner.top + inner.height() * .28f)
        path.lineTo(inner.right, inner.top + inner.height() * .28f); path.lineTo(cx, base)
        linePaint.color = ColorUtils.setAlphaComponent(third, 0x70); linePaint.pathEffect = DashPathEffect(floatArrayOf(d(2f), d(3f)), 0f)
        canvas.drawPath(path, linePaint); linePaint.pathEffect = null
        fillPaint.color = secondary; canvas.drawCircle(cx, base, d(3f), fillPaint)
        footer(canvas, "扩散宽度 " + fmt(shown.widthPercent, 1) + "% · 相对示意")
    }
    private fun drawTailRibbons(canvas: Canvas): Unit {
        caption(canvas, "低 / 中 / 高频 · 尾音淡出示意")
        val colors: List<Int> = listOf(color, secondary, third)
        val labels: List<String> = listOf("低", "中", "高")
        repeat(3) { band: Int ->
            val y: Float = plot.top + plot.height() * (band + .5f) / 3
            val start: Float = timeX(shown.predelayMs); val end: Float = timeX(shown.predelayMs + shown.decayMs[band])
            fillPaint.shader = LinearGradient(start, y, max(start + d(1f), end), y,
                ColorUtils.setAlphaComponent(colors[band], 0xb0), ColorUtils.setAlphaComponent(colors[band], 0x08), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(start, y - d(7f), max(start + d(1f), end), y + d(7f)), d(7f), d(7f), fillPaint)
            fillPaint.shader = null
            point(canvas, start, y, colors[band])
            text(canvas, labels[band] + " " + fmt(shown.decayMs[band] / 1000, 2) + " s", plot.left, y - d(10f), plot.width(), colors[band])
        }
        footer(canvas, "反馈 −60 dB 预测 · 不含避让及房间测量")
    }
    private fun drawToneCloud(canvas: Canvas): Unit {
        caption(canvas, "频段保留 · 尾音厚度与明暗示意")
        val centers: List<Double> = listOf(80.0, 1000.0, 8000.0)
        val colors: List<Int> = listOf(color, secondary, third)
        repeat(3) { band: Int ->
            val index: Int = shown.spectrumHz.indices.minByOrNull { abs(ln(shown.spectrumHz[it] / centers[band])) } ?: 0
            val gain: Double = 10.0.pow((shown.loopLossDb[index] + shown.filterDb[index]) / 20)
            val x: Float = px((band + .5) / 3)
            val radius: Float = (plot.width() / 7) * (.35 + .65 * gain).toFloat()
            fillPaint.color = ColorUtils.setAlphaComponent(colors[band], (30 + 110 * gain).roundToInt().coerceIn(20, 150))
            canvas.drawCircle(x, plot.centerY(), radius + d(3f), fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(colors[band], (60 + 150 * gain).roundToInt().coerceIn(30, 220))
            canvas.drawCircle(x, plot.centerY(), radius, fillPaint)
            text(canvas, listOf("低频厚度", "中频主体", "高频明亮")[band], x, py(.12), plot.width() / 3, colors[band], Paint.Align.CENTER)
        }
        footer(canvas, "同一激励参考 · 回路与滤波保留示意")
    }
    private fun drawArrival(canvas: Canvas): Unit {
        caption(canvas, "干声先到 · 预延迟后驱动尾音")
        grid(canvas, "干声", "湿声", "0 ms", "200 ms")
        point(canvas, plot.left, py(.82), secondary)
        val start: Double = shown.predelayMs / 200
        trace(canvas) { position: Double -> if (position < start) Double.NaN else .18 + .12 * exp(-(position - start) * 5) * sin((position - start) * 45) }
        marker(canvas, px(start)); point(canvas, px(start), py(.30))
        footer(canvas, "仅示意附加间隔 · 不含混响内部传播")
    }
    private fun drawMixIllustration(canvas: Canvas): Unit {
        caption(canvas, "干声轮廓与湿声尾音 · 相对比例")
        val dry: Double = shown.dryGain / 3; val wet: Double = shown.wetGain / 3
        trace(canvas, secondary) { position: Double -> .70 + dry * .18 * sin(position * 8 * PI) * exp(-position * 10) }
        trace(canvas) { position: Double -> .25 + wet * .24 * sin(position * 17 * PI) * exp(-position * 2) }
        text(canvas, "原声 " + fmt(shown.dryGain, 2) + "×", plot.left, py(.90), plot.width(), secondary)
        text(canvas, "混响 " + fmt(shown.wetGain, 2) + "×", plot.left, py(.43), plot.width(), color)
        footer(canvas, "示意轮廓 · 不表示实际播放波形")
    }
    private fun drawDuckEnvelope(canvas: Canvas): Unit {
        val settings: A.Settings = shown.settings.advanced
        caption(canvas, "输入停顿后，湿声逐渐恢复")
        grid(canvas, "−24 dB", "0", "0", "15 s")
        val onMs: Double = 800.0
        trace(canvas) { position: Double ->
            val time: Double = 20 * (exp(position * ln(751.0)) - 1)
            val power: Double = if (time < onMs) 1 - exp(-time / settings.attack)
                else (1 - exp(-onMs / settings.attack)) * exp(-(time - onMs) / settings.release)
            val level: Double = 10 * log10(power.coerceAtLeast(1e-30))
            val reduction: Double = settings.depth / 10.0 * ((level - settings.threshold / 10.0) / 12).coerceIn(0.0, 1.0)
            reduction / 24
        }
        marker(canvas, px(ln(1 + onMs / 20) / ln(751.0)), secondary)
        footer(canvas, "0 dBFS 阶跃参考 · 攻击／释放平滑输入功率")
    }
    private fun drawOutputIllustration(canvas: Canvas): Unit {
        caption(canvas, "同一输出增益同时缩放干湿")
        bar(canvas, 0, 3, shown.dryGain * shown.outputGain / 3, "干声输出", fmt(shown.dryGain * shown.outputGain, 2) + "×", secondary)
        bar(canvas, 1, 3, shown.wetGain * shown.outputGain / 3, "湿声输出", fmt(shown.wetGain * shown.outputGain, 2) + "×", color)
        bar(canvas, 2, 3, shown.outputGain, "整体", fmt(20 * log10(shown.outputGain), 1) + "dB", third)
        footer(canvas, "参考系数 · 实际峰值见动态图")
    }
    private fun drawMonitorChannels(canvas: Canvas): Unit {
        caption(canvas, "真实峰值通道与尾音预测分开")
        box(canvas, "输入 L / R", .0, .95, 1.0, .70, secondary)
        box(canvas, "湿声 L / R", .0, .58, 1.0, .33)
        box(canvas, "输出 L / R + 超限帧", .0, .21, 1.0, -.04, third)
        footer(canvas, "预测图不测量真实音乐的 RT60")
    }
    private fun drawMeasurement(canvas: Canvas): Unit {
        caption(canvas, "各自充分预热 → 同一输入测量")
        box(canvas, "同一输入", .18, .95, .82, .69, secondary)
        box(canvas, "混响 A", .0, .52, .43, .23); box(canvas, "混响 B", .57, .52, 1.0, .23, third)
        linePaint.color = secondary
        canvas.drawLine(px(.5), py(.69), px(.22), py(.52), linePaint); canvas.drawLine(px(.5), py(.69), px(.78), py(.52), linePaint)
        text(canvas, if (snapshot.matchingReady) "测量已就绪" else "预热随拖尾与采样率变化", plot.centerX(), py(.02), plot.width(), color, Paint.Align.CENTER)
        footer(canvas, "预热后测量 2 秒 · 固定衰减较响组")
    }
    private fun drawComparison(canvas: Canvas): Unit {
        caption(canvas, "快照保存当前原五项或专业声音")
        box(canvas, "A " + (if (snapshot.savedA) "已保存" else "未保存"), .0, .95, .45, .57, secondary)
        box(canvas, "B " + (if (snapshot.savedB) "已保存" else "未保存"), .55, .95, 1.0, .57, third)
        box(canvas, auditionName(snapshot.audition), .0, .34, 1.0, .02)
        footer(canvas, "静图为保存参数 · 实际试听读数在下方")
    }
    private fun auditionName(value: Int): String = listOf("正常处理", "本模块原声", "效果差分（−6 dB）", "仅混响声", "快照 A", "快照 B", "测量 A/B").getOrElse(value) { "正常处理" }
    private fun details(): String {
        val settings: P.Settings = snapshot.settings
        val values: Map<String, Any> = settings.values()
        val controls: List<BassParameters.Control> = when (focus) {
            ReverbChartFocus.ROOM -> listOf(P.size)
            ReverbChartFocus.DECAY -> listOf(A.rt60) + A.bandNumbers
            ReverbChartFocus.TONE -> listOf(P.damp, P.lowCut, P.highCut)
            ReverbChartFocus.WIDTH -> listOf(P.width)
            ReverbChartFocus.PREDELAY -> listOf(P.predelay)
            ReverbChartFocus.MIX -> listOf(P.wet, P.dry, P.mix)
            ReverbChartFocus.DUCK -> A.duckNumbers
            ReverbChartFocus.LEVEL -> listOf(P.output)
            else -> emptyList()
        }
        val lines: MutableList<String> = mutableListOf("经典混响 · " + configuration.rate + " Hz · " +
            (if (settings.professional) "专业参数" else "原五项参数"), if (enabled) "参数模型与相对示意；反馈衰减预测不是房间实测。" else snapshot.reason(focus, key))
        P.legacyControls.zip(settings.legacy).filter { (control, _) -> ReverbChartFocus.fromKey(control.key) == focus }
            .forEach { (control, value) -> lines += "原版 " + control.title + " " + value + "%" }
        controls.forEach { control: BassParameters.Control -> lines += control.title + " " + control.label(P.value(values, control)) }
        lines += when (focus) {
            ReverbChartFocus.ROOM -> "房间大小是反馈百分比，不是平方米或物理距离。反馈系数约 0.70–0.98。秒制 RT60 开启后接管专业房间大小；灰色图显示关闭接管后的保存大小预览。原五项与专业两套数值独立保留，首次开启的复制只由原编辑器完成。房间墙体和反射轨迹只是反馈强弱的直观示意。"
            ReverbChartFocus.DECAY -> "RT60 是反馈回路衰减 60 dB 的参考时间，20–1000 厘秒对应 0.20–10.00 秒。低频倍率 0.25–4 倍、高频 0.25–2 倍，通过两个分界平滑改变回路反馈；接管阻尼而非湿声输出均衡。模型按左右八个梳状回路与实际内部采样率预测代表低中高频的最长衰减，最大显示 60 秒，不是完整实测脉冲或真实房间 RT60。预测开关只控制原显示，不改变音频。"
            ReverbChartFocus.TONE -> "传统阻尼改变反馈回路中的高频衰减，分频衰减开启后接管阻尼。低切与高切是湿声输出端的二阶滤波，不改变干声；它们控制混响频谱，和改变尾音消失速度的分频反馈不同。紫色为湿声输出滤波，青色为一个代表回路的一次循环损耗，不能相加冒充完整混响幅频实测。关闭子开关时以灰色显示该滑杆目标。"
            ReverbChartFocus.WIDTH -> "湿声立体声矩阵：同侧权重 (1+宽度)/2，另一侧 (1−宽度)/2。共同 Mid 不因宽度改变，Side 乘以宽度百分比；0% 收为中央，100% 保留两路湿声差异，不是额外的 200% 扩宽。矩阵图归一化显示系数，扇区开合不是实际声源方向或距离。"
            ReverbChartFocus.PREDELAY -> "预延迟只延后驱动混响的输入，干声保持原时序。原始整数除以 10 为毫秒，0–200 ms；按内部采样率换算样本并进行线性分数延迟。这里表示额外间隔，不包括混响梳状回路与全通扩散的内部传播时间。开关关闭时只显示灰色保存目标，不会开启预延迟。"
            ReverbChartFocus.MIX -> "独立模式下干声 50% 对应 1×原声、100% 对应2×；湿声100%对应3×的内部湿声系数。联动模式以0–100%混合比例接管两项：干声=1−混合，湿声=混合，属于线性混合。被接管的独立数值保留并显示灰色预览，关闭联动后恢复。图中系数与示意波形不是实际音乐能量。"
            ReverbChartFocus.DUCK -> "避让按整体输入 max(L²,R²) 的功率包络工作，不识别人声，也会响应鼓声。阈值上方0–12dB线性增加到最大避让量，只压湿声，干声不受影响。攻击与释放平滑检测功率，单位ms；右图使用0dBFS输入阶跃800ms后停止的参考情景，通过同一阈值与深度公式绘制，不冒充实时读数。"
            ReverbChartFocus.LEVEL -> "输出增益在干湿混合后执行，同时缩放正常输出与实际湿声试听支路。开关关闭时不改变声音，滑杆保存值以灰色目标预览。图中为参考增益，不是设备最终电平；后续效果仍可能增加峰值。"
            ReverbChartFocus.MONITOR -> "实时电平开关读取本模块输入、湿声与输出两声道采样峰值及超限帧数；关闭后再开启可清零。尾音预测开关是显示选项，和真实峰值、避让包络不同。峰值不是耳内声压或真峰值，预测不是音乐或房间测量。采样开关只控制上方监听，不代替原生音频处理开关。"
            ReverbChartFocus.MATCH -> "保存A/B后，两套模型各自充分预热，再用同一输入同时测量2秒；预热随最长拖尾和采样率变化，不能固定为100ms。匹配只固定衰减较响组，不持续追踪，不提升电平。左图0–18dB仅解释相对差值，不是实际读数或算法限制。当前匹配" + (if (snapshot.matching) "开启" else "关闭") + "，测量" + (if (snapshot.matchingReady) "就绪" else "未就绪") + "。"
            ReverbChartFocus.AUDITION -> "当前：" + auditionName(snapshot.audition) + "。A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "。原生保存按钮可保存当前原五项或专业声音；图纸不读取私有快照参数。静图表示当前保存配置，实际A/B和仅湿声状态见实时图；保存、测量、匹配与试听不写入预设，退出时仍由原控制器管理。"
        }
        lines += "静图仅观察保存值。未启用或被接管的控制使用灰色目标模型，预览不会改变下方开关、参数、快照或音频处理。"
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onAttachedToWindow(): Unit { super.onAttachedToWindow(); if (active) updateTarget() }
    override fun onDetachedFromWindow(): Unit {
        generation++; ReverbStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
