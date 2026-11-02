package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.DdcChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.DdcController
import me.timschneeberger.rootlessjamesdsp.fusion.DdcDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.DdcParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

internal class DdcParameterChart(context: Context) : DdcFocusStaticBase(context, false)
internal class DdcIllustrationChart(context: Context) : DdcFocusStaticBase(context, true)

private data class DdcStaticSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean, val filePath: String,
    val supported: Boolean, val acceptedSha: String?, val bindings: List<DdcController.FileBinding>,
    val audition: Int, val matching: Boolean, val ready: Boolean, val savedA: Boolean, val savedB: Boolean
) {
    private fun rightSaved(key: String): Boolean = settings.linked && key in setOf(P.rightRatio.key, P.rightLevel.key)
    fun active(focus: DdcChartFocus, key: String): Boolean {
        if (focus == DdcChartFocus.FILE) return filePath.isNotBlank() && acceptedSha != null && moduleOn
        if (!moduleOn || !supported || !settings.professional || acceptedSha == null || rightSaved(key)) return false
        return when (focus) {
            DdcChartFocus.RATIO -> settings.ratioOn
            DdcChartFocus.COMPENSATION -> settings.compensationOn
            DdcChartFocus.LOW -> settings.compensationOn && settings.lowOn
            DdcChartFocus.HIGH -> settings.compensationOn && settings.highOn
            DdcChartFocus.PEAK1 -> settings.compensationOn && settings.peak1On
            DdcChartFocus.PEAK2 -> settings.compensationOn && settings.peak2On
            DdcChartFocus.EAR_RATIO, DdcChartFocus.EAR_LEVEL -> settings.earOn
            DdcChartFocus.PREAMP -> settings.preampOn
            DdcChartFocus.HEADROOM -> settings.autoHeadroomOn
            DdcChartFocus.OUTPUT -> settings.outputOn
            DdcChartFocus.RESPONSE -> settings.response
            DdcChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: DdcChartFocus, key: String): String = when {
        filePath.isBlank() -> "未选择VDC · 个人保存目标预览"
        !moduleOn -> "DDC关闭 · 保存目标预览"
        focus == DdcChartFocus.FILE -> if (acceptedSha == null) "文件等待确认 · 路线目标示意" else "文件路线示意 · 不读取或改写系数"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "原文件处理 · 专业保存目标预览"
        rightSaved(key) -> "左右联动 · 右耳保存值灰色预览"
        acceptedSha == null -> "文件尚未确认接受 · 保存目标预览"
        else -> when (focus) {
            DdcChartFocus.RATIO -> "文件混合关闭 · 保存比例预览"
            DdcChartFocus.COMPENSATION -> "个人补偿关闭 · 保存PEQ预览"
            DdcChartFocus.LOW, DdcChartFocus.HIGH, DdcChartFocus.PEAK1, DdcChartFocus.PEAK2 -> "对应补偿关闭 · 保存单段预览"
            DdcChartFocus.EAR_RATIO, DdcChartFocus.EAR_LEVEL -> "双耳微调关闭 · 保存目标预览"
            DdcChartFocus.PREAMP -> "前级衰减关闭 · 保存目标预览"
            DdcChartFocus.HEADROOM -> "预测余量关闭 · 安全边际预览"
            DdcChartFocus.OUTPUT -> "输出增益关闭 · 保存目标预览"
            DdcChartFocus.RESPONSE -> "响应监测关闭 · 个人模型仍保留"
            DdcChartFocus.MONITOR -> "实时电平关闭 · 监测位置示意"
            else -> "保存参数参考"
        }
    }
    fun target(focus: DdcChartFocus, key: String): P.Settings {
        val base: P.Settings = settings.copy(meters = false, response = false)
        return when (focus) {
            DdcChartFocus.RATIO -> base.copy(professional = true, ratioOn = true)
            DdcChartFocus.COMPENSATION -> base.copy(professional = true, compensationOn = true)
            DdcChartFocus.LOW -> base.copy(professional = true, compensationOn = true, lowOn = true)
            DdcChartFocus.HIGH -> base.copy(professional = true, compensationOn = true, highOn = true)
            DdcChartFocus.PEAK1 -> base.copy(professional = true, compensationOn = true, peak1On = true)
            DdcChartFocus.PEAK2 -> base.copy(professional = true, compensationOn = true, peak2On = true)
            DdcChartFocus.EAR_RATIO, DdcChartFocus.EAR_LEVEL -> base.copy(professional = true, earOn = true,
                linked = if (rightSaved(key)) false else base.linked)
            DdcChartFocus.PREAMP -> base.copy(professional = true, preampOn = true)
            DdcChartFocus.HEADROOM -> base.copy(professional = true, autoHeadroomOn = true)
            DdcChartFocus.OUTPUT -> base.copy(professional = true, outputOn = true)
            else -> base
        }
    }
}

private data class DdcStaticRequest(val settings: P.Settings, val rate: Int, val actualRate: Boolean, val filePath: String)
private object DdcStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ddc-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: DdcStaticRequest, val callback: (DdcDashboardModel) -> Unit)
    private class Owner { var running: DdcStaticRequest? = null; var waiting: Task? = null; var future: Future<*>? = null }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<DdcStaticRequest, DdcDashboardModel> = object : LinkedHashMap<DdcStaticRequest, DdcDashboardModel>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DdcStaticRequest, DdcDashboardModel>?): Boolean = size > 16
    }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { it.waiting = null; it.future?.cancel(true); it.future = null }
    }
    @Synchronized fun request(owner: Any, request: DdcStaticRequest, callback: (DdcDashboardModel) -> Unit): Unit {
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
            val result: DdcDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() +
                    mapOf(P.ENABLED to true, P.FILE to task.request.filePath)
                DdcDashboardModel.from(values, task.request.rate, task.request.actualRate)
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

internal open class DdcFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(110, 216, 208) else Color.rgb(17, 124, 117)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 184, 109) else Color.rgb(163, 90, 23)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x28) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var configuration: DdcDashboardModel = DdcDashboardModel.from(emptyMap<String, Any>())
    private var shown: DdcDashboardModel = configuration
    private var snapshot: DdcStaticSnapshot = snapshot(configuration)
    private var focus: DdcChartFocus = DdcChartFocus.FILE
    private var key: String = P.FILE
    private var active: Boolean = true
    private var request: DdcStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x50)
    private val routeLabels: List<String> = listOf("正常处理", "本模块输入原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B 电平")
    private fun snapshot(model: DdcDashboardModel): DdcStaticSnapshot = DdcStaticSnapshot(model.settings, model.rate,
        model.actualRate, model.enabled, model.filePath, DdcController.supported(context), DdcController.selectedSha256(context),
        DdcController.fileBindings(), DdcController.audition, DdcController.match, DdcController.matchingReady(),
        DdcController.hasComparison(0), DdcController.hasComparison(1))
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
    }
    fun setConfiguration(model: DdcDashboardModel): Unit {
        configuration = model
        val next: DdcStaticSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateTarget(); updateDescription()
        if (active) invalidate()
    }
    fun setFocus(focus: DdcChartFocus, key: String = ""): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; DdcStaticWork.release(this); request = null }
        this.focus = focus; this.key = key; updateTarget(); updateDescription()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) { generation++; DdcStaticWork.release(this); request = null; detailDialog?.dismiss(); detailDialog = null }
        else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val next: DdcStaticRequest = DdcStaticRequest(snapshot.target(focus, key), configuration.rate, configuration.actualRate, configuration.filePath)
        if (request == next) return
        request = next
        val token: Long = generation
        DdcStaticWork.request(this, next) { value -> if (active && token == generation) { shown = value; updateDescription(); invalidate() } }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%+." + decimals + "f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.toFloat()
    private fun fx(frequency: Double): Float = px(ln(frequency.coerceIn(20.0, 20000.0) / 20) / ln(1000.0))
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
    private fun frequencyTrace(canvas: Canvas, values: DoubleArray, tint: Int, dashed: Boolean = false,
        floor: Double = -24.0, ceiling: Double = 24.0): Unit {
        path.rewind(); var started: Boolean = false
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            val value: Double = values[index]
            if (!value.isFinite() || frequency >= shown.rate / 2.0) { started = false; return@forEachIndexed }
            if (!started) { path.moveTo(fx(frequency), py((value - floor) / (ceiling - floor))); started = true }
            else path.lineTo(fx(frequency), py((value - floor) / (ceiling - floor)))
        }
        stroke(canvas, tint, dashed)
    }
    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, dashed: Boolean = false): Unit {
        linePaint.color = tint; linePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, linePaint); linePaint.pathEffect = null
    }
    private fun dot(canvas: Canvas, x: Float, y: Float, tint: Int = color, radius: Float = d(2.2f)): Unit {
        fillPaint.color = tint; canvas.drawCircle(x, y, radius, fillPaint)
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, ratio: Double, label: String, value: String, tint: Int): Unit {
        if (count <= 0 || index !in 0 until count) return
        val column: Float = plot.width() / count
        val center: Float = plot.left + column * (index + .5f)
        val fraction: Double = if (ratio.isFinite()) ratio.coerceIn(0.0, 1.0) else 0.0
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (enabled) 0x85 else 0x45)
        canvas.drawRoundRect(RectF(center - column * .26f, py(fraction), center + column * .26f, plot.bottom), d(3f), d(3f), fillPaint)
        text(canvas, value, center, plot.top + d(11f), column - d(4f), tint, Paint.Align.CENTER)
        text(canvas, label, center, plot.bottom + d(11f), column - d(4f), tint, Paint.Align.CENTER)
    }
    private fun node(canvas: Canvas, box: RectF, label: String, tint: Int, strong: Boolean = true): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (strong) 0x24 else 0x0c)
        canvas.drawRoundRect(box, d(5f), d(5f), fillPaint)
        linePaint.color = tint; linePaint.pathEffect = if (strong) null else DashPathEffect(floatArrayOf(d(2f), d(2f)), 0f)
        canvas.drawRoundRect(box, d(5f), d(5f), linePaint); linePaint.pathEffect = null
        text(canvas, label, box.centerX(), box.centerY() - (textPaint.ascent() + textPaint.descent()) / 2,
            box.width() - d(5f), tint, Paint.Align.CENTER)
    }
    private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int): Unit {
        line(canvas, x1, y1, x2, y2, tint)
        val angle: Double = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val size: Double = d(4f).toDouble()
        line(canvas, x2, y2, x2 - (size * cos(angle - .5)).toFloat(), y2 - (size * sin(angle - .5)).toFloat(), tint)
        line(canvas, x2, y2, x2 - (size * cos(angle + .5)).toFloat(), y2 - (size * sin(angle + .5)).toFloat(), tint)
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun bandIndex(): Int = when (focus) { DdcChartFocus.LOW -> 0; DdcChartFocus.HIGH -> 1; DdcChartFocus.PEAK1 -> 2; else -> 3 }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        DdcChartFocus.FILE -> drawFile(canvas)
        DdcChartFocus.RATIO -> drawRatio(canvas)
        DdcChartFocus.COMPENSATION -> drawEq(canvas, false)
        DdcChartFocus.LOW, DdcChartFocus.HIGH, DdcChartFocus.PEAK1, DdcChartFocus.PEAK2 -> drawBand(canvas)
        DdcChartFocus.EAR_RATIO -> drawEarRatio(canvas)
        DdcChartFocus.EAR_LEVEL -> drawEarLevel(canvas)
        DdcChartFocus.PREAMP -> drawLevel(canvas, true)
        DdcChartFocus.HEADROOM -> drawHeadroom(canvas)
        DdcChartFocus.OUTPUT -> drawLevel(canvas, false)
        DdcChartFocus.RESPONSE -> drawEq(canvas, true)
        DdcChartFocus.MONITOR -> drawMonitor(canvas)
        DdcChartFocus.MATCH -> drawMatch(canvas)
        DdcChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        DdcChartFocus.FILE -> drawFileLayers(canvas)
        DdcChartFocus.RATIO -> drawPhaseExample(canvas)
        DdcChartFocus.COMPENSATION -> drawEqLayers(canvas)
        DdcChartFocus.LOW, DdcChartFocus.HIGH, DdcChartFocus.PEAK1, DdcChartFocus.PEAK2 -> drawBandArea(canvas)
        DdcChartFocus.EAR_RATIO -> drawHead(canvas, false)
        DdcChartFocus.EAR_LEVEL -> drawHead(canvas, true)
        DdcChartFocus.PREAMP -> drawPreampRoutes(canvas)
        DdcChartFocus.HEADROOM -> drawMargin(canvas)
        DdcChartFocus.OUTPUT -> drawOutputWave(canvas)
        DdcChartFocus.RESPONSE -> drawFileLayers(canvas)
        DdcChartFocus.MONITOR -> drawMonitor(canvas)
        DdcChartFocus.MATCH -> drawBalance(canvas)
        DdcChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawFile(canvas: Canvas): Unit {
        caption(canvas, configuration.fileLabel)
        val labels: List<String> = listOf("共同前级与自动余量", "原声 / VDC文件校正混合", "个人PEQ与左右耳", "完整处理后输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.88 - index * .25)
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, if (index == 2) color else secondary)
        }
        footer(canvas, "图为处理关系 · 原文件响应由驱动实际预测")
    }
    private fun drawRatio(canvas: Canvas): Unit {
        val ratio: Double = shown.settings.ratio / 1000.0
        caption(canvas, "主校正比例 " + fmt(ratio * 100) + "% · 线性混合")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - ratio, "原声系数", fmt(1 - ratio, 3) + "×", secondary)
        bar(canvas, 1, 2, ratio, "文件系数", fmt(ratio, 3) + "×", color)
        footer(canvas, "50%不等于文件曲线dB减半 · PEQ在混合后")
    }
    private fun drawEq(canvas: Canvas, levels: Boolean): Unit {
        caption(canvas, if (levels) "个人PEQ + 固定电平 · 不含原VDC" else "个人四级PEQ合成 · 不含原VDC")
        grid(canvas, "+24 dB", "−48", "20 Hz", "20 kHz")
        if (levels) {
            frequencyTrace(canvas, shown.leftDb, color, false, -48.0, 24.0)
            frequencyTrace(canvas, shown.rightDb, secondary, true, -48.0, 24.0)
        } else frequencyTrace(canvas, shown.personalEqDb, color, false, -48.0, 24.0)
        footer(canvas, if (levels) "左实/右虚 · 未含文件混合与自动余量" else "各级幅频dB相加 · 关闭段不参加合成")
    }
    private fun drawBand(canvas: Canvas): Unit {
        val band: DdcDashboardModel.Band = shown.bands[bandIndex()]
        caption(canvas, band.name + " " + fmt(band.frequencyHz, 0) + "Hz · " + signed(band.gainDb) + "dB")
        grid(canvas, "+24 dB", "−24", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.personalEqDb, secondary, true)
        frequencyTrace(canvas, band.responseDb, color)
        line(canvas, fx(band.frequencyHz), plot.top, fx(band.frequencyHz), plot.bottom, third, true)
        footer(canvas, (if (band.peak) "Q " + fmt(band.shape, 2) else "RBJ坡度 S " + fmt(band.shape, 3)) + " · 虚线为个人四段合成")
    }
    private fun drawEarRatio(canvas: Canvas): Unit {
        caption(canvas, "最终比例 = 主比例 × 各耳权重")
        grid(canvas, "100%", "0", "", "")
        bar(canvas, 0, 2, shown.leftRatio, "左校正", fmt(shown.leftRatio * 100) + "%", color)
        bar(canvas, 1, 2, shown.rightRatio, "右校正", fmt(shown.rightRatio * 100) + "%", secondary)
        footer(canvas, if (snapshot.settings.linked) "联动使用左耳参数 · 右耳保存值不改写" else "两耳分别混合相同VDC · 不等于声道音量")
    }
    private fun drawEarLevel(canvas: Canvas): Unit {
        caption(canvas, "耳电平 左 " + signed(shown.leftLevelDb) + " / 右 " + signed(shown.rightLevelDb) + " dB")
        grid(canvas, "+12 dB", "−12", "左耳", "右耳")
        for (index in 0..1) {
            val value: Double = if (index == 0) shown.leftLevelDb else shown.rightLevelDb
            val x: Float = px(if (index == 0) .28 else .72)
            val tint: Int = if (index == 0) color else secondary
            line(canvas, x, py(.5), x, py((value + 12) / 24), tint)
            dot(canvas, x, py((value + 12) / 24), tint, d(4f))
            text(canvas, signed(value) + "dB", x, plot.top + d(11f), plot.width() / 2, tint, Paint.Align.CENTER)
        }
        line(canvas, plot.left, py(.5), plot.right, py(.5), third, true)
        footer(canvas, "耳电平在个人PEQ后 · 与校正比例独立")
    }
    private fun drawLevel(canvas: Canvas, preamp: Boolean): Unit {
        val db: Double = if (preamp) shown.preampDb else shown.outputDb
        caption(canvas, (if (preamp) "共同前级 " else "输出 ") + signed(db) + "dB · ×" + fmt(10.0.pow(db / 20), 3))
        grid(canvas, "+1 FS", "−1", "输入幅度", "缩放幅度")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (x - .5) * 10.0.pow(db / 20) }
        footer(canvas, if (preamp) "原声与文件两路共同衰减 · 不改变混合比例" else "完整处理后电平 · 正增益可增加削波风险")
    }
    private fun drawHeadroom(canvas: Canvas): Unit {
        caption(canvas, "安全边际 " + fmt(shown.marginDb) + "dB · 参数判据")
        grid(canvas, "0 dB", "−60", "预测−24 dB", "+48 dB")
        trace(canvas) { x: Double -> (min(0.0, -shown.marginDb - (-24 + 72 * x)) + 60) / 60 }
        footer(canvas, "自动衰减 = min(0, −边际 − 完整预测峰值)")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, "内部率 / 文件身份 / 实际输出 / 静态预测")
        val labels: List<String> = listOf("内部采样率与系数率", "文件：所选 / 接受 / 活动", "输入与最终试听输出电平", "原文件 / 左右静态响应")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.88 - index * .25)
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, if (index == 1) third else color)
        }
        footer(canvas, "真实数据见实时区 · 采样峰值不是真峰值")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.ready) "测量就绪 · 固定衰减" else "同一有效文件 · 两快照对照")
        val labels: List<String> = listOf("保存 A / B", "预热 → 测量2秒", "只衰减较响一侧")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.84 - index * .33)
            node(canvas, RectF(plot.left, y - d(9f), plot.right, y + d(9f)), label, if (index == 2) third else color,
                if (index == 0) snapshot.savedA && snapshot.savedB else if (index == 2) snapshot.ready else true)
        }
        footer(canvas, "等RMS电平不是等响度 · 不改私有快照")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        val mode: Int = snapshot.audition.coerceIn(0, 6)
        caption(canvas, "当前：" + routeLabels[mode])
        val labels: List<String> = listOf("输入原声", if (mode == 3) "校正效果差分" else "完整DDC处理", "快照 A", "快照 B")
        val selected: Int = when (mode) { 1 -> 0; 4 -> 2; 5 -> 3; else -> 1 }
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .24)
            node(canvas, RectF(plot.left, y - d(7f), px(.70), y + d(7f)), label, if (index == selected) color else secondary, index == selected)
            line(canvas, px(.70), y, px(.93), py(.5), if (index == selected) color else secondary, index != selected)
        }
        dot(canvas, px(.93), py(.5), third, d(4f))
        footer(canvas, "静图保存配置 · A/B输出由实时区单独监测")
    }
    private fun drawFileLayers(canvas: Canvas): Unit {
        caption(canvas, "原文件保持 · 个人补偿独立叠加")
        val labels: List<String> = listOf("原声 + 文件校正", "个人：2架式 + 2峰式", "左右耳电平 → 输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.84 - index * .33)
            fillPaint.shader = LinearGradient(plot.left, y, plot.right, y,
                ColorUtils.setAlphaComponent(if (index == 1) color else secondary, 0x35), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(plot.left, y - d(11f), plot.right, y + d(11f)), d(5f), d(5f), fillPaint); fillPaint.shader = null
            text(canvas, label, plot.centerX(), y + d(3f), plot.width(), if (index == 1) color else secondary, Paint.Align.CENTER)
        }
        footer(canvas, "个人曲线不含文件 · 完整结果看驱动预测")
    }
    private fun drawPhaseExample(canvas: Canvas): Unit {
        val ratio: Double = shown.settings.ratio / 1000.0
        caption(canvas, "主比例 " + fmt(ratio * 100) + "% · 通用90°例")
        val ox: Float = px(.15); val oy: Float = py(.16)
        val span: Float = min(plot.width() * .68f, plot.height() * .72f)
        val dryX: Float = ox + span * (1 - ratio).toFloat()
        val wetY: Float = oy - span * ratio.toFloat()
        line(canvas, ox, oy, ox + span, oy, foreground, true)
        line(canvas, ox, oy, ox, oy - span, foreground, true)
        arrow(canvas, ox, oy, dryX, oy, secondary)
        arrow(canvas, dryX, oy, dryX, wetY, third)
        arrow(canvas, ox, oy, dryX, wetY, color)
        val example: Double = 20 * log10(hypot(1 - ratio, ratio))
        text(canvas, "例幅度 " + signed(example, 2) + "dB", plot.right, plot.top + d(10f), plot.width(), color, Paint.Align.RIGHT)
        text(canvas, "原声 + 旋转后的校正声", plot.centerX(), plot.bottom + d(11f), plot.width(), secondary, Paint.Align.CENTER)
        footer(canvas, "通用相位示意 · 不是当前VDC相位或响应")
    }
    private fun drawEqLayers(canvas: Canvas): Unit {
        caption(canvas, "四段个人补偿 · 原VDC另有自身响应")
        grid(canvas, "+24 dB", "−24", "20 Hz", "20 kHz")
        shown.bands.forEachIndexed { index: Int, band: DdcDashboardModel.Band ->
            val tint: Int = when (index) { 0 -> color; 1 -> secondary; else -> third }
            frequencyTrace(canvas, band.responseDb, if (band.active) tint else ColorUtils.setAlphaComponent(foreground, 0x45), !band.active)
        }
        footer(canvas, "彩线为启用段 · 灰虚线为保存段预览")
    }
    private fun drawBandArea(canvas: Canvas): Unit {
        val band: DdcDashboardModel.Band = shown.bands[bandIndex()]
        caption(canvas, band.name + " · " + fmt(band.frequencyHz, 0) + "Hz / " + signed(band.gainDb) + "dB")
        val boundaries: List<Double> = listOf(20.0, 180.0, 2500.0, 20000.0)
        for (index in 0..2) {
            fillPaint.color = ColorUtils.setAlphaComponent(if (index == 0) secondary else if (index == 1) color else third, 0x18)
            canvas.drawRect(fx(boundaries[index]), plot.top, fx(boundaries[index + 1]), plot.bottom, fillPaint)
        }
        path.rewind(); path.moveTo(plot.left, py(.5))
        var lastX: Float = plot.left
        shown.frequencies.forEachIndexed { index: Int, f: Double ->
            if (f < shown.rate / 2.0) { lastX = fx(f); path.lineTo(lastX, py(.5 + band.responseDb[index] / 48)) }
        }
        path.lineTo(lastX, py(.5)); path.close()
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x48)
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, fillPaint); canvas.restore()
        frequencyTrace(canvas, band.responseDb, color)
        line(canvas, fx(band.frequencyHz), plot.top, fx(band.frequencyHz), plot.bottom, third, true)
        text(canvas, "低音", fx(60.0), plot.bottom + d(11f), plot.width() / 3, secondary, Paint.Align.CENTER)
        text(canvas, "中频", fx(650.0), plot.bottom + d(11f), plot.width() / 3, color, Paint.Align.CENTER)
        text(canvas, "高频", fx(7000.0), plot.bottom + d(11f), plot.width() / 3, third, Paint.Align.CENTER)
        footer(canvas, if (band.gainDb == 0.0) "当前0dB · 调频率/Q/S不会凭空增减" else if (band.peak) "Q大更集中 · 不识别人声或齿音" else "S控制过渡形状 · 不是dB/oct斜率")
    }
    private fun drawHead(canvas: Canvas, level: Boolean): Unit {
        caption(canvas, if (snapshot.settings.linked && key in setOf(P.rightRatio.key, P.rightLevel.key))
            "右耳保存预览 · 真实仍保持联动" else if (snapshot.settings.linked)
            "双耳联动 · 两侧使用左耳参数" else "左右独立 · 相同文件分别作用")
        val cx: Float = px(.5); val cy: Float = py(.52)
        val rx: Float = min(plot.width() * .24f, plot.height() * .32f)
        val ry: Float = min(plot.height() * .39f, plot.width() * .29f)
        fillPaint.shader = RadialGradient(cx - rx * .25f, cy - ry * .3f, max(rx, ry) * 1.5f,
            ColorUtils.setAlphaComponent(color, 0x30), ColorUtils.setAlphaComponent(color, 0x08), Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), fillPaint); fillPaint.shader = null
        linePaint.color = color; canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), linePaint)
        path.rewind(); path.moveTo(cx - rx * .15f, cy - ry); path.lineTo(cx, cy - ry - d(5f)); path.lineTo(cx + rx * .15f, cy - ry)
        stroke(canvas, color)
        for (ear in 0..1) {
            val left: Boolean = ear == 0
            val x: Float = cx + if (left) -rx else rx
            val tint: Int = if (left) color else secondary
            val value: Double = if (level) (if (left) shown.leftLevelDb else shown.rightLevelDb) else (if (left) shown.leftRatio else shown.rightRatio)
            val strength: Double = if (level) (10.0.pow(value / 20) / 4).coerceIn(.05, 1.0) else value.coerceIn(0.0, 1.0)
            val box: RectF = RectF(x - d(5f), cy - ry * .50f, x + d(5f), cy + ry * .50f)
            fillPaint.color = ColorUtils.setAlphaComponent(tint, (35 + 170 * strength).toInt()); canvas.drawRoundRect(box, d(5f), d(5f), fillPaint)
            linePaint.color = tint; canvas.drawRoundRect(box, d(5f), d(5f), linePaint)
            for (ring in 1..3) {
                val spread: Float = d(5f + ring * 3f)
                linePaint.color = ColorUtils.setAlphaComponent(tint, (110 * strength / ring).toInt())
                canvas.drawArc(RectF(x - spread, cy - spread, x + spread, cy + spread), if (left) 120f else -60f, 120f, false, linePaint)
            }
            val label: String = (if (left) "左 " else "右 ") + if (level) signed(value) + "dB" else fmt(value * 100) + "%"
            text(canvas, label, if (left) plot.left else plot.right, plot.bottom + d(11f), plot.width() / 2, tint,
                if (left) Paint.Align.LEFT else Paint.Align.RIGHT)
        }
        footer(canvas, "参数比例/电平示意 · 非真实听力或空间距离")
    }
    private fun drawPreampRoutes(canvas: Canvas): Unit {
        caption(canvas, "前级共同 ×" + fmt(10.0.pow(shown.preampDb / 20), 3))
        val y: Float = py(.77)
        node(canvas, RectF(px(.1), y - d(10f), px(.9), y + d(10f)), "手动前级 + 自动余量", color)
        for (index in 0..1) {
            val x: Float = px(if (index == 0) .25 else .75)
            arrow(canvas, px(.5), y + d(10f), x, py(.33), secondary)
            node(canvas, RectF(x - plot.width() * .22f, py(.33), x + plot.width() * .22f, py(.08)), if (index == 0) "原声" else "文件校正", if (index == 0) secondary else third)
        }
        footer(canvas, "两路同时衰减 · 不只降低校正声")
    }
    private fun drawMargin(canvas: Canvas): Unit {
        caption(canvas, "安全边际 " + fmt(shown.marginDb) + "dB · 非当前峰值")
        val margin: Float = (shown.marginDb / 24 * plot.height()).toFloat()
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x38)
        canvas.drawRect(plot.left, plot.top, plot.right, plot.top + margin, fillPaint)
        line(canvas, plot.left, plot.top + margin, plot.right, plot.top + margin, third, true)
        text(canvas, "0 dBFS边界", plot.right, plot.top + d(10f), plot.width(), foreground, Paint.Align.RIGHT)
        node(canvas, RectF(plot.left, py(.42), plot.right, py(.12)), "完整文件 + 混合 + PEQ + 双耳", color)
        arrow(canvas, px(.5), py(.42), px(.5), plot.top + margin + d(4f), secondary)
        footer(canvas, "密集频点与极点邻域预测 · 有限预测非限制器")
    }
    private fun drawOutputWave(canvas: Canvas): Unit {
        caption(canvas, "完整处理后 ×" + fmt(10.0.pow(shown.outputDb / 20), 3))
        grid(canvas, "+1 FS", "−1", "示意波形", "")
        trace(canvas, secondary, true) { x: Double -> .5 + .30 * sin(6 * PI * x) }
        trace(canvas) { x: Double -> .5 + .30 * sin(6 * PI * x) * 10.0.pow(shown.outputDb / 20) }
        footer(canvas, "幅度参考 · 超出窗口不表示新增硬限幅")
    }
    private fun drawBalance(canvas: Canvas): Unit {
        caption(canvas, "同一文件 · 不同个人设置的等电平对照")
        val center: Float = py(.55)
        line(canvas, px(.5), py(.15), px(.5), py(.83), color)
        line(canvas, px(.12), center, px(.88), center, color)
        for (index in 0..1) {
            val x: Float = px(if (index == 0) .20 else .80)
            val saved: Boolean = if (index == 0) snapshot.savedA else snapshot.savedB
            line(canvas, x, center, x, py(.30), secondary)
            node(canvas, RectF(x - plot.width() * .17f, py(.30), x + plot.width() * .17f, py(.10)), if (index == 0) "A" else "B", color, saved)
        }
        text(canvas, "只降低较高RMS一侧", plot.centerX(), plot.top + d(10f), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "换文件后需重存快照 · 图纸不写入A/B")
    }
    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val state: String = if (enabled) "保存参数的显示模型；不是耳机或音乐实测。" else snapshot.reason(focus, key) + "。显示副本不启用任何声音开关。"
        val explanation: String = when (focus) {
            DdcChartFocus.FILE -> "原VDC文件保存原滤波系数；个人补偿、混合、双耳与电平独立保存，绝不重写文件。任意IIR系数不能唯一倒推原作者使用的PEQ频率/增益/Q。静图只解释路线；文件原响应和完整左右响应来自驱动实际已加载系数的预测。\n当前所选：" + configuration.filePath + "\n已确认接受指纹：" + (snapshot.acceptedSha ?: "尚未确认") +
                snapshot.bindings.joinToString("", prefix = "\n") { binding -> "\n接受标签：" + binding.label + "\n接受路径：" + binding.appliedPath +
                    "\n指纹：" + (binding.appliedSha256 ?: "无") + (if (binding.loading) "\n新文件装载中，旧文件继续处理" else "") + (binding.error?.let { "\n错误：" + it } ?: "") } +
                "\n接受新文件不等于已经听到新文件，实际活动身份与平滑交接请看实时区；这里不读取/修改文件内容。"
            DdcChartFocus.RATIO -> "原文件支路的复数响应为H，实际校正为(1−r)+r×H；每耳r=主比例×耳权重。0%旁路基础文件校正，但后方个人PEQ和耳电平仍可作用。50%不是把原曲线dB值除以2，延迟/滤波相位可能导致抵消。\n右图只以通用的H=1∠90°说明矢量相加，绝不是当前VDC的相位、距离、耳机测量或真实响应。左图两柱仅主混合系数，不是音频能量比例；双耳权重另有独立焦点。"
            DdcChartFocus.COMPENSATION -> "个人层为一低架、一高架、两路峰式滤波，位于原声与文件校正混合之后。各启用段串联，幅频dB可以相加；它们不改原文件。此图只画个人四级PEQ，没有原VDC响应、文件混合相位和自动余量，不能当完整DDC输出。关闭某段保留其保存参数，灰虚线只作预览。"
            DdcChartFocus.LOW, DdcChartFocus.HIGH, DdcChartFocus.PEAK1, DdcChartFocus.PEAK2 -> {
                val band: DdcDashboardModel.Band = shown.bands[bandIndex()]
                band.name + "是独立个人补偿，频率按内部处理率×0.43安全限制并四舍五入，原保存Hz不改。增益0dB时频率/Q/S变化不会凭空产生补偿。\n" +
                    (if (band.peak) "峰式Q是无量纲品质因数，Q越高频段越窄；不是压缩比，也不做人声识别或动态去齿音。" else "架式S是RBJ坡度参数0.250..1.000，控制过渡形状，不是6/12/24dB每倍频程。") +
                    "\n左图实线为本段保存目标，虚线为启用个人段合成；右图标示作用频段。两者都不含原VDC。关闭补偿总组或本段时只在灰色显示副本启用预览，不写原开关。"
            }
            DdcChartFocus.EAR_RATIO -> "主混合比例仅在主比例开关开启时使用，否则主比例为100%。双耳微调开启后，最终左/右r=主比例×对应耳权重，影响原声与同一VDC文件之间的混合，不是左右音量或听力损失估计。\n默认联动，两侧实际均使用左耳权重；右耳原保存值保留。触摸已锁定的右耳参数时，图纸在灰色独立显示副本里预览右耳保存值，不解除真实联动。右耳保存" + fmt(s.rightRatio / 10.0) + "% 。"
            DdcChartFocus.EAR_LEVEL -> "耳电平位于文件混合和个人PEQ之后，与校正比例独立。保存单位0.1dB，正值增加耳电平且可能增加削波风险。默认联动，两侧使用左耳电平；解除联动才恢复右耳独立保存值。右耳保存" + signed(s.rightLevel / 10.0) + "dB 。\n图中头部只是参数平衡示意，不代表真实声像角度、左右听力或声学测量。"
            DdcChartFocus.PREAMP -> "前级是原声与文件校正两路之前共同的固定衰减，保存0.1dB。0dB为×1，最小−48dB；它不改变两路相对混合比例，也不是只衰减文件声。自动预测余量在相同前级位置另行相乘。直线只表示本项缩放，不是完整DDC幅频或实际峰值。"
            DdcChartFocus.HEADROOM -> "驱动对完整文件复数响应、混合、个人PEQ、前级、双耳和输出计算预测峰值，再取auto_dB=min(0,−安全边际−预测峰值dB)。预测不含auto本身，auto只衰减。安全边际保存0.1dB，范围0..12dB。\n左图横轴是假设预测峰值，用来解释判据；没有从个人PEQ冒充当前完整预测峰值。真实预测/自动衰减由实时区读驱动。原生使用密集频点及各滤波极点邻域，不只是32点显示图；有限静态预测仍不是限制器，不能保证每首音乐瞬态不削波。"
            DdcChartFocus.OUTPUT -> "输出是文件混合、个人PEQ和耳电平之后的全模块固定增益，保存0.1dB，范围−24..+12dB。它也参与自动余量的完整峰值预测；开auto时提高输出可能被额外自动衰减抵消。图只说明该项缩放，不包含未知原文件/auto，不应以模型线条断言实际输出响度。"
            DdcChartFocus.RESPONSE -> "左图只画已知的个人PEQ与固定前级、耳电平及输出增益；现有模型明确foundationIncluded=false，不含VDC/文件混合/自动余量。它不能替代完整DDC频响。右图说明个人层和原文件层的区别。\n下方驱动原子响应才含当前已活动原文件和完整左右输出，可能在新文件交接前仍为旧内核；它是系数预测，不是麦克风实测或音乐FFT。原生FFT与静态系数预测属于不同视图口径。"
            DdcChartFocus.MONITOR -> "内部处理率与宿主率可能不同；当前VDC系数所用率由驱动报告，不能据此倒推原文件的44.1/48kHz系数bank。个人频率限制按内部率计算，原保存值不改。输入/最终试听输出采样峰值与RMS是当前音频块统计，不是真峰值、LUFS或耳内声压。最终输出随A/B路由变化；主配置目标、预测峰值、自动余量与文件身份须分别标注，不把它们冒充选中快照参数。"
            DdcChartFocus.MATCH -> "同一有效文件保存两快照，同一输入并行处理，预热后测量2秒RMS，只固定衰减较高RMS一侧，不提升、不持续跟随；等电平不是心理声学等响。换文件、内外采样率/会话等变化按原逻辑清除快照。静图只读公开是否保存与匹配状态，不查看或修改私有快照系数。"
            DdcChartFocus.AUDITION -> "七路保留正常、输入原声、处理后效果、效果差分、快照A、快照B与测量操作。效果差分包含VDC、混合、个人补偿、相位与电平共同差异，不是新增谐波或单独PEQ曲线。静图保持页面保存目标；真正试听输出由实时区采样，原保存/测量/停止回调不受图纸干扰。"
        }
        val controls: List<me.timschneeberger.rootlessjamesdsp.fusion.BassParameters.Control> = when (focus) {
            DdcChartFocus.RATIO -> listOf(P.ratio)
            DdcChartFocus.COMPENSATION -> P.compensationNumbers
            DdcChartFocus.LOW -> P.lowNumbers; DdcChartFocus.HIGH -> P.highNumbers
            DdcChartFocus.PEAK1 -> P.peak1Numbers; DdcChartFocus.PEAK2 -> P.peak2Numbers
            DdcChartFocus.EAR_RATIO -> listOf(P.ratio, P.leftRatio, P.rightRatio)
            DdcChartFocus.EAR_LEVEL -> listOf(P.leftLevel, P.rightLevel)
            DdcChartFocus.PREAMP -> listOf(P.preamp); DdcChartFocus.HEADROOM -> listOf(P.margin)
            DdcChartFocus.OUTPUT -> listOf(P.output); else -> emptyList()
        }
        val values: Map<String, Any> = s.values()
        val saved: String = controls.joinToString("\n") { control -> control.title + "：" + control.label(values[control.key] as Int) }
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + state + "\n\n" + explanation +
            (if (saved.isEmpty()) "" else "\n\n保存参数：\n" + saved) + "\n\n" +
            (if (snapshot.actualRate) "实际内部处理率 " else "参考内部处理率 ") + snapshot.rate + "Hz；固定20Hz–20kHz坐标，奈奎斯特以上不绘制，超出幅度窗口的曲线裁绘。长按仅查看，不改参数或文件。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; DdcStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
