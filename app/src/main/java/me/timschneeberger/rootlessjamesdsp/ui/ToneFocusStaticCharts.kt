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
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedController
import me.timschneeberger.rootlessjamesdsp.fusion.ToneChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ToneDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

internal class ToneParameterChart(context: Context) : ToneFocusStaticBase(context, false)
internal class ToneIllustrationChart(context: Context) : ToneFocusStaticBase(context, true)

private data class ToneStaticSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean, val valid: Boolean,
    val supported: Boolean, val audition: Int, val matching: Boolean, val matchingReady: Boolean,
    val savedA: Boolean, val savedB: Boolean
) {
    fun group(key: String, focus: ToneChartFocus): Int = when {
        key in P.baseKeys -> 0
        key == P.SHAPE_ON || key in P.shapeNumbers.map { it.key } -> 2
        key == P.WEAK_ON || key == P.weakFloor.key -> 4
        key == P.DYNAMIC_ON || key in P.dynamicNumbers.map { it.key } -> 3
        key == P.MIX_ON || focus == ToneChartFocus.MIX -> 5
        key == P.OUTPUT_ON || focus == ToneChartFocus.LEVEL -> 6
        key == P.HEADROOM_ON || focus == ToneChartFocus.HEADROOM -> 7
        key == P.METERS || focus == ToneChartFocus.MONITOR -> 8
        key == P.RESPONSE || focus == ToneChartFocus.RESPONSE -> 9
        key in P.coreNumbers.map { it.key } || focus == ToneChartFocus.CORE -> 1
        else -> 0
    }
    fun active(focus: ToneChartFocus, key: String): Boolean {
        if (!valid || !moduleOn) return false
        val group: Int = group(key, focus)
        if (group == 0 && focus !in setOf(ToneChartFocus.MATCH, ToneChartFocus.AUDITION)) return true
        if (!supported || !settings.professional) return false
        return when (group) {
            1 -> settings.coreOn; 2 -> settings.shapeOn; 3 -> settings.dynamicOn
            4 -> settings.dynamicOn && settings.weakOn; 5 -> settings.mixOn
            6 -> settings.outputOn; 7 -> settings.headroomOn; 8 -> settings.meters
            9 -> settings.response; else -> true
        }
    }
    fun reason(focus: ToneChartFocus, key: String): String = when {
        !valid -> "基础保存值异常 · 安全参考预览"
        !moduleOn -> "听感音色调节关闭 · 保存目标预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "原版处理 · 专业保存目标预览"
        else -> when (group(key, focus)) {
            1 -> "核心精调关闭 · 保存修正预览"
            2 -> "形状精调关闭 · 保存频点预览"
            3 -> "弹性瞬态精调关闭 · 保存目标预览"
            4 -> "弱信号保护关闭 · 保存判据预览"
            5 -> "干湿比例关闭 · 保存比例预览"
            6 -> "输出增益关闭 · 保存衰减预览"
            7 -> "额外余量关闭 · 保存衰减预览"
            8 -> "电平显示关闭 · 测点示意"
            9 -> "响应显示关闭 · 保存模型预览"
            else -> "保存参数参考"
        }
    }
    fun target(focus: ToneChartFocus, key: String): P.Settings {
        val base: P.Settings = settings.copy(meters = false, response = false)
        return when (group(key, focus)) {
            1 -> base.copy(professional = true, coreOn = true)
            2 -> base.copy(professional = true, shapeOn = true)
            3 -> base.copy(professional = true, dynamicOn = true)
            4 -> base.copy(professional = true, dynamicOn = true, weakOn = true)
            5 -> base.copy(professional = true, mixOn = true)
            6 -> base.copy(professional = true, outputOn = true)
            7 -> base.copy(professional = true, headroomOn = true)
            else -> base
        }
    }
}

private data class ToneStaticRequest(val settings: P.Settings, val rate: Int, val actualRate: Boolean)
/** One worker, one latest waiting target per view, and a bounded shared settled-model cache. */
private object ToneStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tone-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: ToneStaticRequest, val callback: (ToneDashboardModel) -> Unit)
    private class Owner { var running: ToneStaticRequest? = null; var waiting: Task? = null; var future: Future<*>? = null }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<ToneStaticRequest, ToneDashboardModel> = object : LinkedHashMap<ToneStaticRequest, ToneDashboardModel>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ToneStaticRequest, ToneDashboardModel>?): Boolean = size > 16
    }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { it.waiting = null; it.future?.cancel(true); it.future = null }
    }
    @Synchronized fun request(owner: Any, request: ToneStaticRequest, callback: (ToneDashboardModel) -> Unit): Unit {
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
            val result: ToneDashboardModel = synchronized(this) { cache[task.request] }
                ?: ToneDashboardModel.fromSettings(task.request.settings, task.request.rate, task.request.actualRate)
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

internal open class ToneFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: ToneDashboardModel = ToneDashboardModel.from(emptyMap<String, Any>())
    private var shown: ToneDashboardModel = configuration
    private var snapshot: ToneStaticSnapshot = snapshot(configuration)
    private var focus: ToneChartFocus = ToneChartFocus.CORE
    private var key: String = P.PROFESSIONAL
    private var active: Boolean = true
    private var request: ToneStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x50)
    private val macroNames: List<String> = listOf("温度", "下潜", "弹性", "厚度", "人声", "女毒", "低齿音", "高齿音", "瞬态", "空气")
    private val routeLabels: List<String> = listOf("正常处理", "本模块输入原声", "仅处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B 电平")
    private fun snapshot(model: ToneDashboardModel): ToneStaticSnapshot = ToneStaticSnapshot(model.settings, model.rate,
        model.actualRate, model.enabled, model.validFoundation, ToneAdvancedController.supported(context),
        ToneAdvancedController.audition, ToneAdvancedController.match, ToneAdvancedController.matchingReady(),
        ToneAdvancedController.hasComparison(0), ToneAdvancedController.hasComparison(1))
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
    }
    fun setConfiguration(model: ToneDashboardModel): Unit {
        configuration = model
        val next: ToneStaticSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateTarget(); updateDescription()
        if (active) invalidate()
    }
    fun setFocus(focus: ToneChartFocus, key: String = ""): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; ToneStaticWork.release(this); request = null }
        this.focus = focus; this.key = key; updateTarget(); updateDescription()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) { generation++; ToneStaticWork.release(this); request = null; detailDialog?.dismiss(); detailDialog = null }
        else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val next: ToneStaticRequest = ToneStaticRequest(snapshot.target(focus, key), configuration.rate, configuration.actualRate)
        if (request == next) return
        request = next
        val token: Long = generation
        ToneStaticWork.request(this, next) { value ->
            if (active && token == generation) { shown = value; updateDescription(); invalidate() }
        }
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
            if (!value.isFinite()) { started = false; return@forEachIndexed }
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
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun filterIndex(): Int {
        val direct: Int = P.frequency.indexOfFirst { it.key == key }
        if (direct >= 0) return direct
        val qIndex: Int = P.q.indexOfFirst { it.key == key }
        if (qIndex >= 0) return qIndex + 3
        val macro: Int = P.delta.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: P.baseKeys.indexOf(key)
        return when (focus) {
            ToneChartFocus.TEMPERATURE -> if (key == P.temperatureHighWeight.key || key == P.slope[1].key) 1 else 0
            ToneChartFocus.DEPTH -> 2; ToneChartFocus.ELASTIC -> 3
            ToneChartFocus.PRESENCE -> if (macro in 3..5) macro + 1 else 5
            ToneChartFocus.SIBILANCE -> if (macro == 7) 8 else 7
            ToneChartFocus.AIR -> 9; else -> 5
        }
    }
    private fun elasticTiming(): Boolean = focus == ToneChartFocus.ELASTIC && key !in
        setOf(P.delta[2].key, P.baseKeys[2], P.frequency[3].key, P.q[0].key, P.elasticStaticWeight.key)
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        ToneChartFocus.CORE -> drawCore(canvas)
        ToneChartFocus.TEMPERATURE, ToneChartFocus.DEPTH, ToneChartFocus.PRESENCE,
        ToneChartFocus.SIBILANCE, ToneChartFocus.AIR -> drawFrequency(canvas)
        ToneChartFocus.ELASTIC -> if (elasticTiming()) drawOnset(canvas, true) else drawFrequency(canvas)
        ToneChartFocus.TRANSIENT -> drawOnset(canvas, false)
        ToneChartFocus.WEAK -> drawWeak(canvas)
        ToneChartFocus.MIX -> drawMix(canvas)
        ToneChartFocus.LEVEL -> drawLevel(canvas)
        ToneChartFocus.HEADROOM -> drawHeadroom(canvas)
        ToneChartFocus.RESPONSE -> drawResponse(canvas)
        ToneChartFocus.MONITOR -> drawMonitor(canvas)
        ToneChartFocus.MATCH -> drawMatch(canvas)
        ToneChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        ToneChartFocus.CORE -> drawColorBalance(canvas)
        ToneChartFocus.TEMPERATURE, ToneChartFocus.DEPTH, ToneChartFocus.PRESENCE,
        ToneChartFocus.SIBILANCE, ToneChartFocus.AIR -> drawListeningBand(canvas)
        ToneChartFocus.ELASTIC -> drawElastic(canvas)
        ToneChartFocus.TRANSIENT -> drawAttackPicture(canvas, false)
        ToneChartFocus.WEAK -> drawProtection(canvas)
        ToneChartFocus.MIX -> drawRoutes(canvas)
        ToneChartFocus.LEVEL -> drawWave(canvas)
        ToneChartFocus.HEADROOM -> drawSpace(canvas)
        ToneChartFocus.RESPONSE -> drawStaticDynamic(canvas)
        ToneChartFocus.MONITOR -> drawMonitor(canvas)
        ToneChartFocus.MATCH -> drawBalance(canvas)
        ToneChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawCore(canvas: Canvas): Unit {
        caption(canvas, "有效点值 · 总强度 " + fmt(shown.settings.amount / 10.0) + "%")
        grid(canvas, "+200", "−200", "温度", "空气")
        line(canvas, plot.left, py(.5), plot.right, py(.5), third, true)
        val selected: Int = P.delta.indexOfFirst { it.key == key }
        for (index in 0..9) {
            val x: Float = px((index + .5) / 10)
            val tint: Int = if (selected < 0 || selected == index) color else secondary
            val y: Float = py(.5 + shown.effective[index] / 400)
            line(canvas, x, py(.5), x, y, tint); dot(canvas, x, y, tint)
        }
        footer(canvas, "原值 + 0.1点修正 → 限±100 → 乘总强度")
    }
    private fun drawFrequency(canvas: Canvas): Unit {
        val index: Int = filterIndex()
        caption(canvas, fmt(shown.hz[index], 0) + "Hz · " + signed(shown.filterGainDb[index], 2) + "dB · 参数模型")
        grid(canvas, "+24 dB", "−24", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.staticDb, secondary, true)
        if (focus == ToneChartFocus.TEMPERATURE) {
            frequencyTrace(canvas, shown.componentDb(0), color); frequencyTrace(canvas, shown.componentDb(1), third)
        } else frequencyTrace(canvas, shown.componentDb(index), color)
        line(canvas, fx(shown.hz[index]), plot.top, fx(shown.hz[index]), plot.bottom, color, true)
        val shape: String = when (index) {
            0 -> "低架 S " + fmt(shown.slopes[0], 3)
            1 -> "高架 S " + fmt(shown.slopes[1], 3)
            2 -> "低架 S " + fmt(shown.slopes[2], 3)
            9 -> "高架 S " + fmt(shown.slopes[3], 3)
            else -> "Q " + fmt(shown.q[index - 3], 2)
        }
        footer(canvas, shape + " · 虚线为十级总和 · 不含动态起音")
    }
    private fun drawOnset(canvas: Canvas, bass: Boolean): Unit {
        val fast: Double = if (bass) shown.bassFastMs else shown.transientFastMs
        val slow: Double = if (bass) shown.bassSlowMs else shown.transientSlowMs
        val target: Double = if (bass) shown.bassTarget else shown.transientTarget
        val bound: Double = if (bass) shown.bassBoundDb else shown.transientBoundDb
        caption(canvas, "快 " + fmt(fast) + " / 慢 " + fmt(slow) + "ms · " + signed(sign(target) * bound) + "dB上界")
        grid(canvas, "+12 dB", "−12", "0 ms", "500 ms")
        line(canvas, plot.left, py(.5), plot.right, py(.5), third, true)
        trace(canvas) { x: Double -> .5 + shown.onsetDb(x * 500, bass) / 24 }
        footer(canvas, if (bass) "单位检测起音参考 · 分频 " + fmt(shown.bassSplitHz, 0) + "Hz" else "单位检测起音参考 · 非压缩攻击/释放")
    }
    private fun drawWeak(canvas: Canvas): Unit {
        caption(canvas, "保护电平 " + fmt(shown.weakFloorDbfs) + " dBFS")
        grid(canvas, "允许1", "停止0", "−120 dBFS", "0")
        trace(canvas) { x: Double -> if (-120 + 120 * x < shown.weakFloorDbfs) .05 else .95 }
        val threshold: Float = px((shown.weakFloorDbfs + 120) / 120)
        line(canvas, threshold, plot.top, threshold, plot.bottom, third, true)
        footer(canvas, "仅停止两路起音增减 · 静态音色继续保留")
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "干 " + fmt((1 - shown.mix) * 100) + "% · 湿 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - shown.mix, "输入原声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "音色效果", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "线性系数非能量占比 · 中间比例含滤波相位")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(shown.outputDb) + "dB · ×" + fmt(10.0.pow(shown.outputDb / 20), 3))
        grid(canvas, "+1 FS", "−1", "输入幅度", "输出幅度")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (x - .5) * 10.0.pow(shown.outputDb / 20) }
        footer(canvas, "完整干湿混合后固定衰减 · 不改音色参数")
    }
    private fun drawHeadroom(canvas: Canvas): Unit {
        caption(canvas, "自动 " + fmt(shown.automaticDb, 2) + "dB · 额外 " + fmt(shown.manualDb) + "dB")
        grid(canvas, "60 dB", "0", "", "")
        bar(canvas, 0, 3, -shown.automaticDb / 60, "自动", fmt(shown.automaticDb), color)
        bar(canvas, 1, 3, -shown.manualDb / 60, "额外", fmt(shown.manualDb), third)
        bar(canvas, 2, 3, -shown.outputDb / 60, "输出", fmt(shown.outputDb), secondary)
        footer(canvas, "自动余量预留滤波与起音上界 · 不是实时GR")
    }
    private fun drawResponse(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "宿主 " else "参考 ") + shown.rate + "Hz · 保存配置静态模型")
        grid(canvas, "+24 dB", "−36", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.staticDb, secondary, true, -36.0, 24.0)
        frequencyTrace(canvas, shown.totalDb, color, false, -36.0, 24.0)
        footer(canvas, "虚线滤波串联 · 实线含余量/干湿/输出")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, "主配置动态 / 最终试听电平 / 静态预测")
        val labels: List<String> = listOf("输入 L/R 峰值与 RMS", "低音起音 / 乐器瞬态增益", "正常处理或 A/B 试听输出", "十级滤波静态响应预测")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.88 - index * .25)
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, if (index == 1) third else color)
        }
        footer(canvas, "真实值见下方动态图 · 不模拟音乐频谱")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "测量就绪 · 固定衰减" else "保存 A / B → 预热 → 测量2秒")
        val labels: List<String> = listOf("快照 A", "快照 B", "最低 RMS")
        val saved: List<Boolean> = listOf(snapshot.savedA, snapshot.savedB, snapshot.matchingReady)
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.83 - index * .32)
            node(canvas, RectF(px(.10), y - d(9f), px(.90), y + d(9f)), label, if (index == 2) third else color, saved[index])
            if (index < 2) line(canvas, px(.5), y + d(9f), px(.5), y + plot.height() * .18f, secondary)
        }
        footer(canvas, "只比较 A / B · RMS匹配非心理声学等响")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        val mode: Int = snapshot.audition.coerceIn(0, 6)
        caption(canvas, "当前：" + routeLabels[mode])
        val labels: List<String> = listOf("输入原声", if (mode == 3) "效果差分" else "完整音色处理", "快照 A", "快照 B")
        val selected: Int = when (mode) { 1 -> 0; 4 -> 2; 5 -> 3; else -> 1 }
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .24)
            node(canvas, RectF(plot.left, y - d(7f), px(.70), y + d(7f)), label, if (index == selected) color else secondary, index == selected)
            line(canvas, px(.70), y, px(.93), py(.5), if (index == selected) color else secondary, index != selected)
        }
        dot(canvas, px(.93), py(.5), third, d(4f))
        footer(canvas, "静图保存配置 · A/B实际效果由真实图显示")
    }
    private fun drawColorBalance(canvas: Canvas): Unit {
        caption(canvas, "十项音色目标 · 正负方向随参数定义")
        val columns: Int = 5
        for (index in 0..9) {
            val column: Int = index % columns; val row: Int = index / columns
            val cell: RectF = RectF(px(column / 5.0 + .015), py(if (row == 0) .94 else .45),
                px((column + 1) / 5.0 - .015), py(if (row == 0) .55 else .06))
            val amount: Double = (abs(shown.effective[index]) / 200).coerceIn(0.0, 1.0)
            val tint: Int = if (shown.effective[index] >= 0) color else secondary
            fillPaint.shader = LinearGradient(cell.left, cell.top, cell.left, cell.bottom,
                ColorUtils.setAlphaComponent(tint, (35 + 150 * amount).toInt()), ColorUtils.setAlphaComponent(tint, 15), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(cell, d(5f), d(5f), fillPaint); fillPaint.shader = null
            text(canvas, macroNames[index], cell.centerX(), cell.top + d(11f), cell.width(), tint, Paint.Align.CENTER)
            text(canvas, signed(shown.effective[index], 0), cell.centerX(), cell.bottom - d(5f), cell.width(), tint, Paint.Align.CENTER)
        }
        footer(canvas, "点值不是dB · 弹性与瞬态还依赖真实起音")
    }
    private fun drawListeningBand(canvas: Canvas): Unit {
        val index: Int = filterIndex()
        val hz: Double = shown.hz[index]
        val gain: Double = shown.filterGainDb[index]
        caption(canvas, fmt(hz, 0) + "Hz · " + signed(gain, 2) + "dB · 音色频段示意")
        val bounds: List<Double> = listOf(20.0, 180.0, 2500.0, 20000.0)
        for (band in 0..2) {
            fillPaint.color = ColorUtils.setAlphaComponent(if (band == 0) secondary else if (band == 1) color else third, 0x17)
            canvas.drawRect(fx(bounds[band]), plot.top, fx(bounds[band + 1]), plot.bottom, fillPaint)
        }
        line(canvas, plot.left, py(.48), plot.right, py(.48), foreground, true)
        val values: DoubleArray = shown.componentDb(index)
        path.rewind(); path.moveTo(plot.left, py(.48))
        shown.frequencies.forEachIndexed { i: Int, f: Double -> if (values[i].isFinite()) path.lineTo(fx(f), py(.48 + values[i] / 48)) }
        path.lineTo(plot.right, py(.48)); path.close()
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x48); canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, fillPaint); canvas.restore()
        frequencyTrace(canvas, values, color)
        dot(canvas, fx(hz), py((gain / 48 + .5).coerceIn(.06, .94)), third, d(3f))
        text(canvas, "低音", fx(60.0), plot.bottom + d(11f), plot.width() / 3, secondary, Paint.Align.CENTER)
        text(canvas, "中频", fx(650.0), plot.bottom + d(11f), plot.width() / 3, color, Paint.Align.CENTER)
        text(canvas, "高频", fx(7000.0), plot.bottom + d(11f), plot.width() / 3, third, Paint.Align.CENTER)
        footer(canvas, if (gain == 0.0) "当前作用为0 · 频点改变不会凭空增加音色" else when (focus) {
            ToneChartFocus.TEMPERATURE -> "暖：低频增加/高频减少 · 两端权重独立"
            ToneChartFocus.DEPTH -> "影响低频厚薄 · 不生成本来不存在的低音"
            ToneChartFocus.PRESENCE -> "频段突出/退后 · 不识别人声或性别"
            ToneChartFocus.SIBILANCE -> "静态明暗频段 · 不是动态去齿音器"
            else -> "高频明亮/柔和 · 高频架式参数参考"
        })
    }
    private fun drawElastic(canvas: Canvas): Unit {
        if (elasticTiming()) { drawAttackPicture(canvas, true); return }
        caption(canvas, "静态隆起 " + signed(shown.filterGainDb[3], 2) + "dB · 起音上界 " + fmt(shown.bassBoundDb) + "dB")
        val left: RectF = RectF(plot.left, plot.top + d(5f), px(.47), plot.bottom - d(5f))
        val right: RectF = RectF(px(.53), plot.top + d(5f), plot.right, plot.bottom - d(5f))
        node(canvas, left, "静态隆起", color)
        node(canvas, right, "动态起音", secondary)
        text(canvas, fmt(shown.hz[3], 0) + "Hz", left.centerX(), left.bottom - d(11f), left.width(), color, Paint.Align.CENTER)
        text(canvas, fmt(shown.bassSplitHz, 0) + "Hz", right.centerX(), right.bottom - d(11f), right.width(), secondary, Paint.Align.CENTER)
        footer(canvas, "同一弹性基础 · 隆起与起音可分别加权")
    }
    private fun drawAttackPicture(canvas: Canvas, bass: Boolean): Unit {
        val target: Double = if (bass) shown.bassTarget else shown.transientTarget
        val sensitivity: Double = if (bass) shown.bassSensitivity else shown.transientSensitivity
        caption(canvas, "权重后目标 " + signed(target, 2) + " · 灵敏度 " + fmt(sensitivity * 100) + "%")
        grid(canvas, "幅度", "0", "起音", "500 ms")
        trace(canvas, secondary, true) { x: Double -> .35 + .04 * sin(x * 30 * PI) }
        trace(canvas) { x: Double ->
            val envelope: Double = 10.0.pow(shown.onsetDb(x * 500, bass) / 20)
            .35 * envelope + .035 * sin(x * 30 * PI) * envelope
        }
        footer(canvas, if (target == 0.0) "基础与修正为0 · 权重不会自行产生起音" else "单位检测起音参考 · 正值增强/负值柔化")
    }
    private fun drawProtection(canvas: Canvas): Unit {
        caption(canvas, "低于 " + fmt(shown.weakFloorDbfs) + "dBFS 停止动态起音")
        node(canvas, RectF(plot.left, plot.top, plot.right, py(.72)), "输入与静态音色保持", color)
        val y: Float = py(.40)
        node(canvas, RectF(plot.left, y - d(12f), px(.65), y + d(12f)), "两路起音", secondary)
        line(canvas, px(.65), y, plot.right, y, third, true)
        line(canvas, px(.78), y - d(10f), px(.90), y + d(10f), third)
        text(canvas, "弱信号保护", plot.centerX(), plot.bottom + d(11f), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "检测低音与全频起音分别判断 · 不静音原声")
    }
    private fun drawRoutes(canvas: Canvas): Unit {
        caption(canvas, "原声 " + fmt((1 - shown.mix) * 100) + "% + 效果 " + fmt(shown.mix * 100) + "%")
        for (index in 0..1) {
            val y: Float = py(if (index == 0) .75 else .25)
            val value: Double = if (index == 0) 1 - shown.mix else shown.mix
            val tint: Int = if (index == 0) secondary else color
            node(canvas, RectF(plot.left, y - d(10f), px(.76), y + d(10f)), if (index == 0) "本模块输入原声" else "完整音色效果", tint, value > 0)
            line(canvas, px(.76), y, px(.95), py(.5), tint, value == 0.0)
        }
        dot(canvas, px(.95), py(.5), third, d(4f))
        footer(canvas, "0%为本模块输入 · 效果支路自带自动余量")
    }
    private fun drawWave(canvas: Canvas): Unit {
        caption(canvas, "完整混合后 ×" + fmt(10.0.pow(shown.outputDb / 20), 3))
        grid(canvas, "+1 FS", "−1", "波形参考", "")
        trace(canvas, secondary, true) { x: Double -> .5 + .40 * sin(6 * PI * x) }
        trace(canvas) { x: Double -> .5 + .40 * sin(6 * PI * x) * 10.0.pow(shown.outputDb / 20) }
        footer(canvas, "示意波形只表幅度缩放 · 非真实音频")
    }
    private fun drawSpace(canvas: Canvas): Unit {
        caption(canvas, "自动预留 " + fmt(-shown.automaticDb) + "dB · 额外 " + fmt(-shown.manualDb) + "dB")
        val ceiling: Float = py(.87)
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x28)
        canvas.drawRect(plot.left, plot.top, plot.right, ceiling, fillPaint)
        line(canvas, plot.left, ceiling, plot.right, ceiling, third, true)
        val scale: Double = ((1 - shown.mix) + shown.mix * 10.0.pow(shown.automaticDb / 20)) * 10.0.pow(shown.manualDb / 20)
        trace(canvas) { x: Double -> .45 + .36 * sin(6 * PI * x) * scale }
        text(canvas, "电平余量", plot.right, plot.top + d(9f), plot.width(), third, Paint.Align.RIGHT)
        footer(canvas, "余量缩放参考 · 不含滤波频响与动态起音")
    }
    private fun drawStaticDynamic(canvas: Canvas): Unit {
        caption(canvas, "静态滤波 + 两路随起音变化的动态")
        node(canvas, RectF(plot.left, plot.top, plot.right, py(.68)), "十级滤波：固定频点/Q/S", color)
        node(canvas, RectF(plot.left, py(.52), plot.right, py(.23)), "低音起音 + 全频瞬态", secondary)
        line(canvas, px(.5), py(.68), px(.5), py(.52), third)
        text(canvas, "动态图读真实主内核增益", plot.centerX(), plot.bottom + d(10f), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "静态响应不含动态起音 · 不等于音乐FFT")
    }
    private fun drawBalance(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "等电平就绪 · 只衰减不提升" else "同一输入同时处理 A 与 B")
        val center: Float = py(.55)
        line(canvas, px(.5), py(.15), px(.5), py(.83), color)
        line(canvas, px(.12), center, px(.88), center, color)
        for (index in 0..1) {
            val x: Float = px(if (index == 0) .20 else .80)
            val saved: Boolean = if (index == 0) snapshot.savedA else snapshot.savedB
            line(canvas, x, center, x, py(.30), secondary)
            node(canvas, RectF(x - plot.width() * .17f, py(.30), x + plot.width() * .17f, py(.10)), if (index == 0) "A" else "B", color, saved)
        }
        text(canvas, "两路最低 RMS 为参考", plot.centerX(), plot.top + d(10f), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "不包含原声RMS · 不代表心理声学等响")
    }
    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val state: String = if (enabled) "保存配置的参数参考；实测状态由下方动态图提供。" else snapshot.reason(focus, key) + "。灰色显示副本不会启用任何声音参数。"
        val text: String = when (focus) {
            ToneChartFocus.CORE -> "十项原值为整数点，范围保存为字符串20/40/100；范围缩小会真实截断基础值，专业局部窗口只缩放修正滑杆。专业核心用 clamp(原值+修正/10,−100,+100)×总强度/1000，最大±200点。核心关闭恢复原十项；形状、弹性瞬态、混合等组可独立启用。\n" +
                macroNames.indices.joinToString("\n") { i -> macroNames[i] + "：原值 " + s.base[i] + "，修正 " + signed(s.delta[i] / 10.0) + "点，当前图示有效 " + signed(shown.effective[i]) + "点" }
            ToneChartFocus.TEMPERATURE -> "温度同一个基础同时驱动低架与高架：每100点为低架+4dB、高架−4dB，两个温度权重分别缩放两端。频率默认180/3500Hz，架式坡度是RBJ参数S，不是dB/oct。权重0可关闭对应端的温度作用。专业修正与基础不是两套串联滤波器。"
            ToneChartFocus.DEPTH -> "低音下潜是低架滤波，基础每100点对应6dB；默认60Hz。改变频率与S调整作用范围，不产生原音频没有的超低频，也不是低音谐波合成器。低频深度修正仍是点值，不能直接标为dB。"
            ToneChartFocus.ELASTIC -> "低音弹性同时有110Hz静态钟形隆起（每100点3dB）和真实低音起音作用。静态隆起权重只缩放钟形；低音动态起音权重只缩放动态目标。动态分频为一阶低通，默认150Hz；它作用于滤波及自动余量之后的低频。\n快慢包络跟随左右较强幅度，起音=max((快−慢)/(慢+1e−5),0)，先限制0..1，再乘灵敏度并限制0..1。动态dB=3×有效弹性点/100×起音权重×起音，最后限制最大作用量；正值增强、负值柔化。快包络0.1ms保存、慢包络1ms保存，快必须小于慢。\n参数图仅展示从静默到单位恒定信号的起音参考，不是音乐实测或普通压缩攻击/释放。基础与核心修正合成后为0时，调权重不会凭空产生作用。"
            ToneChartFocus.PRESENCE -> "厚度默认350Hz/每100点4dB，人声1800Hz/4dB，女毒3500Hz/3dB；三者都是静态钟形滤波。Q越大作用越窄。根据触摸的频率/Q/修正切换当前突出频段，不做人声识别、中心提取或性别分析。"
            ToneChartFocus.SIBILANCE -> "低/高齿音默认5500/8500Hz，均为每100点4dB的静态钟形滤波。负值降低该频段，正值提高。它不是带检测器的动态去齿音；不会识别单独的齿音事件。图中选中线和频率标签来自当前滑杆对应的频段。"
            ToneChartFocus.AIR -> "空气声是默认12000Hz的高架，每100点4dB；可改变频点、RBJ坡度S及点值。它改变现有高频明暗，不生成独立空气声源。所有实际滤波频点最多到宿主采样率×0.43，原保存Hz保留。"
            ToneChartFocus.TRANSIENT -> "乐器瞬态使用全频左右较强幅度的快慢包络差，默认快2ms/慢45ms。归一化起音与灵敏度计算方式与低音起音一致，动态dB=3×有效瞬态点/100×瞬态权重×起音，最后限制最大作用量。\n正值使起音更明显，负值柔化起音；持续平稳的声音趋近0dB动态作用。图纸假设单位恒定输入并从静默起音，不把参数预测当真实增益历史。这不是固定阈值压缩器，最大作用量不是输出音量。"
            ToneChartFocus.WEAK -> "弱信号保护仅在专业弹性瞬态组与保护开关都开启时生效。低音起音比较低通低音幅度，全频瞬态比较全频幅度；低于保存dBFS时将对应起音设0，保留静态滤波、原声及另一条未触发保护的动态。检测位置在自动余量和静态滤波之后，不是整模块静音门。"
            ToneChartFocus.MIX -> "正常混合=(1−mix)×输入原声 + mix×完整音色效果。0%是本模块输入，100%是含自动余量、静态滤波和两路起音的效果；中间比例按复数滤波相位叠加，并非能量百分比。输出和额外衰减余量在混合后相乘。"
            ToneChartFocus.LEVEL -> "模块输出增益是完整干湿混合后的固定衰减，保存单位0.1dB，0dB为×1。它不调整十项音色量，也不是自动RMS补偿、压缩器或真峰值保护。示意波形只用于解释幅度缩放。"
            ToneChartFocus.HEADROOM -> "原算法先估计静态滤波最大提升与两路动态上界，计算自动前级余量；专业改变滤波形状时用各级正增益之和作为保守上界，不以稀疏绘图采样代替安全边界。动态上界为min(3×|目标|,最大作用量)。\n额外衰减余量按实际算法乘完整干湿混合后的信号，与输出增益独立保存；不是限制器，也不是实时增益衰减GR。自动余量" + fmt(shown.automaticDb, 3) + "dB，额外" + fmt(shown.manualDb) + "dB。"
            ToneChartFocus.RESPONSE -> "虚线为十级RBJ滤波的串联响应，实线为稳定参数下 (1−mix + mix×滤波×自动余量)×额外余量×输出增益。复数合成保留滤波相位，因此中间比例不能简单加减dB。\n静态图不包含随音乐变化的低音起音与乐器瞬态、配置热身交接或A/B路由。下方驱动32点也是当前主内核的静态系数预测，不是麦克风测量或音乐FFT。"
            ToneChartFocus.MONITOR -> "输入/输出采样峰值和RMS为当前音频块；输出是最终实际试听路由。低音起音增益、瞬态增益、十项有效点与频点、自动余量来自主配置当前内核，A/B时不能冒充选中快照内核。两路动态增益是有符号末样本值，电平开关关闭仍可读取。自动余量不是压缩GR；采样峰值不是真峰值/LUFS/耳内声压。"
            ToneChartFocus.MATCH -> "快照A" + (if (snapshot.savedA) "已保存" else "未保存") + "，B" + (if (snapshot.savedB) "已保存" else "未保存") + "。同时处理同一输入，按滤波与包络状态预热后测量2秒RMS；只以A/B两路较低者为目标，对较高者固定衰减，不把原声RMS加入目标，不提升、不持续追踪。等RMS不等于心理声学等响。静图不读取或修改私有快照参数。"
            ToneChartFocus.AUDITION -> "七种原生试听包含正常、输入原声、仅处理效果、效果差分、A、B和测量操作。效果差分=处理效果−输入，包含静态音色、动态、相位和余量共同差异，不是纯泛音或单独动态控制量。静图显示保存配置，A/B真实路由和电平由动态图读取。所有保存、测量、匹配与停止仍由原按钮控制。"
        }
        val numbers: List<me.timschneeberger.rootlessjamesdsp.fusion.BassParameters.Control> = when (focus) {
            ToneChartFocus.CORE -> P.coreNumbers
            ToneChartFocus.TEMPERATURE -> listOf(P.delta[0], P.frequency[0], P.frequency[1], P.slope[0], P.slope[1], P.temperatureLowWeight, P.temperatureHighWeight)
            ToneChartFocus.DEPTH -> listOf(P.delta[1], P.frequency[2], P.slope[2])
            ToneChartFocus.ELASTIC -> listOf(P.delta[2], P.frequency[3], P.q[0], P.elasticStaticWeight, P.bassDynamicWeight, P.bassSplit, P.bassFast, P.bassSlow, P.bassSensitivity, P.bassMax)
            ToneChartFocus.PRESENCE -> P.delta.subList(3, 6) + P.frequency.subList(4, 7) + P.q.subList(1, 4)
            ToneChartFocus.SIBILANCE -> P.delta.subList(6, 8) + P.frequency.subList(7, 9) + P.q.subList(4, 6)
            ToneChartFocus.AIR -> listOf(P.delta[9], P.frequency[9], P.slope[3])
            ToneChartFocus.TRANSIENT -> listOf(P.delta[8], P.transientWeight, P.transientFast, P.transientSlow, P.transientSensitivity, P.transientMax)
            ToneChartFocus.WEAK -> listOf(P.weakFloor)
            ToneChartFocus.MIX -> listOf(P.mix); ToneChartFocus.LEVEL -> listOf(P.output); ToneChartFocus.HEADROOM -> listOf(P.headroom)
            else -> emptyList()
        }
        val values: Map<String, Any> = s.values()
        val saved: String = numbers.joinToString("\n") { control ->
            control.title + "：" + fmt((values[control.key] as Int) / control.divisor.toDouble(), control.decimals) + " " + control.unit
        }
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + state + "\n\n" + text +
            (if (saved.isEmpty()) "" else "\n\n保存参数：\n" + saved) + "\n\n" +
            (if (snapshot.actualRate) "宿主采样率 " else "参考采样率 ") + snapshot.rate + "Hz。频率轴固定20Hz–20kHz，奈奎斯特以外不画响应；长按仅查看，不写参数。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; ToneStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
