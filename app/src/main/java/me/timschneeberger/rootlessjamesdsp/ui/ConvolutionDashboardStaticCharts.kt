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
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** Independent static observers. Sample callbacks deliberately do not schedule static redraws. */
internal abstract class ConvolutionStaticChart(context: Context) : View(context) {
    var onStatusChanged: (() -> Unit)? = null
    protected val dp = resources.displayMetrics.density
    protected val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    protected val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    protected val secondary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    protected val cyan = if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(103, 218, 242) else Color.rgb(0, 118, 148)
    protected val palette = intArrayOf(primary, secondary, cyan,
        Color.rgb(154, 112, 30), Color.rgb(172, 62, 114), Color.rgb(49, 143, 101), Color.rgb(146, 96, 199))
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * dp }
    protected val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(foreground, 0x28); style = Paint.Style.STROKE; strokeWidth = dp }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val dashed = DashPathEffect(floatArrayOf(3f * dp, 2f * dp), 0f)
    protected val plot = RectF()
    protected var model: ConvolutionDashboardModel? = null
        private set
    protected var heldModel: ConvolutionDashboardModel? = null
    protected var cursor: Float? = null
    private var active = false
    private var attached = false
    protected val canDraw get() = active && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height < 116f * dp
    private val gesture = ConvolutionStaticCursor(this, { x, y -> plot.contains(x, y) }, {
        if (heldModel == null) heldModel = model
        cursor = it.coerceIn(plot.left, plot.right); invalidate()
    }, { cursor = null; heldModel = null; if (canDraw) invalidate() })
    fun setModel(value: ConvolutionDashboardModel) { if (model === value) return; model = value; if (canDraw) invalidate() }
    fun setActive(value: Boolean) { active = value; if (canDraw) invalidate() }
    @Suppress("UNUSED_PARAMETER")
    fun showSample(value: ConvolutionDashboardRuntimeMonitor.Sample) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun pause(reason: String) = Unit
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; if (active) invalidate() }
    override fun onDetachedFromWindow() { attached = false; gesture.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (canDraw) invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = min((if (compact) 6.5f else 8.2f) * resources.displayMetrics.scaledDensity, (if (compact) 8f else 10f) * dp)
    }
    protected fun fmt(v: Double, digits: Int = 1) = if (v.isFinite()) String.format(Locale.ROOT, "%.${digits}f", v) else "—"
    protected fun hz(v: Double) = if (v >= 1000) "${fmt(v / 1000, 1)}k" else fmt(v, 0)
    protected fun label(canvas: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0) return
        val oldSize = text.textSize; text.color = color; text.textAlign = align
        val measured = text.measureText(value); if (measured > available) text.textSize *= available / measured
        canvas.drawText(value, x, y, text); text.textSize = oldSize
    }
    protected fun tooltip(canvas: Canvas, rows: List<String>, x: Float) {
        val pad = 3f * dp; val step = text.textSize * 1.2f
        val w = min(width - dp * 2, (rows.maxOfOrNull { text.measureText(it) } ?: 0f) + pad * 2)
        val h = rows.size * step + pad * 2; val left = x.coerceIn(dp, max(dp, width - w - dp))
        val top = (plot.top + 2f * dp).coerceAtMost(max(dp, height - h - dp))
        fill.color = ColorUtils.setAlphaComponent(surface, 0xf4)
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), 3f * dp, 3f * dp, fill)
        rows.forEachIndexed { i, s -> label(canvas, s, left + pad, top + pad + step * (i + .85f), w - pad * 2) }
    }
    protected fun grid(canvas: Canvas, yMin: Double, yMax: Double, xTicks: List<Pair<Double, String>>, xAt: (Double) -> Float) {
        for (i in 0..4) {
            val y = plot.top + plot.height() * i / 4; canvas.drawLine(plot.left, y, plot.right, y, grid)
            if (i % 2 == 0) label(canvas, fmt(yMax - (yMax - yMin) * i / 4, 0), plot.left - 2f * dp,
                y + 2f * dp, plot.left - 3f * dp, align = Paint.Align.RIGHT)
        }
        xTicks.forEach { (v, s) -> val x = xAt(v); canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            label(canvas, s, x, plot.bottom + text.textSize + dp, plot.width() / 4, align = Paint.Align.CENTER) }
    }
    protected fun curve(canvas: Canvas, data: DoubleArray, color: Int, dashedLine: Boolean = false,
        alpha: Int = 255, xAt: (Int) -> Float, yAt: (Double) -> Float) {
        path.rewind()
        data.forEachIndexed { i, v -> if (i == 0) path.moveTo(xAt(i), yAt(v)) else path.lineTo(xAt(i), yAt(v)) }
        line.color = ColorUtils.setAlphaComponent(color, alpha); line.strokeWidth = 1.4f * dp; line.pathEffect = if (dashedLine) dashed else null
        canvas.drawPath(path, line); line.pathEffect = null
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouch(event) || super.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
}


/** Cached working-kernel peak buckets, not fabricated samples or an assumed room decay. */
internal class ConvolutionTimeView(context: Context) : ConvolutionStaticChart(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val m = heldModel ?: model ?: return
        label(canvas, "工作IR · 时间与强度", 2f * dp, text.textSize + dp, width - 4f * dp)
        label(canvas, if (m.hasIr) "${m.channels}核 · 峰值包络箱 ${fmt(m.bucketWidthMs, 3)}ms${if (m.pending) " · 旧核预览" else ""}"
            else "等待有效工作IR · 灰线仅附加塑形目标", 2f * dp, text.textSize * 2.3f, width - 4f * dp, secondary)
        plot.set(24f * dp, text.textSize * 3.1f, width - 7f * dp, height - (if (compact) 17f else 28f) * dp)
        if (plot.width() <= dp || plot.height() <= dp) return
        val maximum = max(20.0, m.predelayMs + if (m.hasIr) m.durationMs else max(200.0, m.windowLengthMs))
        fun x(ms: Double) = plot.left + (ms / maximum).toFloat() * plot.width()
        fun y(amplitude: Double) = plot.centerY() - amplitude.toFloat() * plot.height() * .48f
        grid(canvas, -1.0, 1.0, listOf(0.0 to "0", maximum / 2 to fmt(maximum / 2, 0), maximum to "${fmt(maximum, 0)}ms"), ::x)
        canvas.save(); canvas.clipRect(plot)
        fill.color = ColorUtils.setAlphaComponent(secondary, 20)
        canvas.drawRect(plot.left, plot.top, x(m.predelayMs), plot.bottom, fill)
        line.pathEffect = dashed; line.color = secondary
        canvas.drawLine(x(m.predelayMs), plot.top, x(m.predelayMs), plot.bottom, line); line.pathEffect = null
        if (m.shapeOn || !m.hasIr) {
            val reference = DoubleArray(160) { i -> 10.0.pow(m.shapingDb(i / 159.0 * maximum) / 20).coerceAtMost(1.0) }
            curve(canvas, reference, foreground, true, 85, { x(it / 159.0 * maximum + m.predelayMs) }, ::y)
        }
        m.waveform.forEachIndexed { c, wave ->
            if (wave.size != m.timeMs.size) return@forEachIndexed
            val gain = m.waveGain(c)
            curve(canvas, DoubleArray(wave.size) { wave[it] * gain }, palette[c % palette.size], !m.enabled,
                240, { x(m.timeMs[it] + m.predelayMs) }, ::y)
        }
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        if (!m.hasIr && cursor == null) label(canvas, "等待真实IR，不使用示例脉冲", plot.centerX(), plot.centerY(), plot.width() - 3f * dp, secondary, Paint.Align.CENTER)
        if (!compact) label(canvas, "预延时${fmt(m.predelayMs, 3)}ms · 湿声${fmt(m.wetGainDb)}dB · 幅度±1，超幅截断", plot.left, height - dp, plot.width())
        cursor?.let { xx ->
            val t = (xx - plot.left) / plot.width() * maximum
            val irTime = t - m.predelayMs
            val rows = mutableListOf("时间 ${fmt(t, 3)}ms · 核位置 ${fmt(irTime, 3)}ms")
            val index = if (m.timeMs.isEmpty()) -1 else (irTime / m.durationMs.coerceAtLeast(.001) * m.timeMs.size).toInt().coerceIn(m.timeMs.indices)
            if (irTime < 0 || irTime >= m.durationMs || index < 0) rows += if (m.hasIr) "核包络范围外" else "未准备有效工作IR"
            else m.waveform.forEachIndexed { c, wave -> rows += "核${c + 1} 桶峰幅度 ${fmt(wave[index] * m.waveGain(c), 5)}" }
            if (m.hasIr) rows += "桶宽${fmt(m.bucketWidthMs, 3)}ms，非逐样本重建"
            rows += "塑形参考 ${fmt(m.shapingDb(max(0.0, irTime)))}dB"
            tooltip(canvas, rows, xx + dp * 2)
        }
    }
}

/** Exact cached-IR DTFT points plus the existing wet-filter section response, without a fake IR. */
internal class ConvolutionFrequencyView(context: Context) : ConvolutionStaticChart(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val m = heldModel ?: model ?: return
        label(canvas, "频响与湿声EQ · 模型", 2f * dp, text.textSize + dp, width - 4f * dp)
        label(canvas, "彩色湿声路径 · 橙EQ合成 · 淡线各滤波段", 2f * dp, text.textSize * 2.3f, width - 4f * dp, secondary)
        plot.set(25f * dp, text.textSize * 3.1f, width - 6f * dp, height - (if (compact) 17f else 28f) * dp)
        if (plot.width() <= dp || plot.height() <= dp) return
        val peak = (m.wetPathDb + listOf(m.eqDb)).maxOfOrNull { data -> data.filter { it.isFinite() }.maxOrNull() ?: 0.0 } ?: 0.0
        val high = max(12.0, ceil(peak / 12) * 12); val low = high - 84
        fun x(hz: Double) = plot.left + (ln(hz / m.frequencies.first()) / ln(m.frequencies.last() / m.frequencies.first())).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db.coerceIn(low, high) - low) / (high - low)).toFloat() * plot.height()
        grid(canvas, low, high, listOf(20.0 to "20", 200.0 to "200", 2000.0 to "2k", m.frequencies.last() to "${hz(m.frequencies.last())}Hz"), ::x)
        canvas.save(); canvas.clipRect(plot)
        m.eqBandsDb.forEachIndexed { i, data -> curve(canvas, data, palette[(i + 2) % palette.size], true,
            if (m.filterEnabled[i]) 100 else 35, { x(m.frequencies[it]) }, ::y) }
        m.wetPathDb.forEachIndexed { c, data -> curve(canvas, data, palette[c % palette.size], !m.enabled, 225,
            { x(m.frequencies[it]) }, ::y) }
        curve(canvas, m.eqDb, secondary, false, 255, { x(m.frequencies[it]) }, ::y)
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        if (!m.hasIr && cursor == null) label(canvas, "工作IR待准备 · 仅显示附加EQ", plot.centerX(), plot.centerY(), plot.width() - dp * 3, secondary, Paint.Align.CENTER)
        if (!compact) label(canvas, "原核已含矩阵/时间窗；此图不冒充混合后音乐频谱", plot.left, height - dp, plot.width())
        cursor?.let { xx ->
            val f = m.frequencies.first() * (m.frequencies.last() / m.frequencies.first()).pow(((xx - plot.left) / plot.width()).toDouble())
            val i = m.frequencies.indices.minByOrNull { abs(ln(m.frequencies[it] / f)) } ?: 0
            val rows = mutableListOf("频率 ${fmt(m.frequencies[i])}Hz · EQ ${fmt(m.eqDb[i])}dB")
            m.wetPathDb.forEachIndexed { c, data -> if (i in data.indices) rows += "核${c + 1} 湿声路径 ${fmt(data[i])}dB" }
            m.phaseDegrees.firstOrNull()?.getOrNull(i)?.takeIf { it.isFinite() }?.let { rows += "核1缓存相位 ${fmt(it.toDouble())}°" }
            m.groupDelayMs.firstOrNull()?.getOrNull(i)?.takeIf { it.isFinite() }?.let { rows += "核1缓存群延迟 ${fmt(it.toDouble(), 3)}ms" }
            if (!m.hasIr) rows += "IR幅频暂无真实数据"
            tooltip(canvas, rows, xx + 2f * dp)
        }
    }
}

/** Path-routing schematic: geometry observes saved controls and genuine kernel peaks, never L/R music. */
internal class ConvolutionStereoView(context: Context) : ConvolutionStaticChart(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val m = heldModel ?: model ?: return
        label(canvas, "立体声路径矩阵 · 空间模型", 2f * dp, text.textSize + dp, width - 4f * dp)
        label(canvas, if (m.hasIr) "${m.channels}核 · 线宽=工作核相对峰值 · 非实测声场" else "未有真实IR · 虚线仅路径参数", 2f * dp,
            text.textSize * 2.3f, width - 4f * dp, secondary)
        plot.set(12f * dp, text.textSize * 3.1f, width - 12f * dp, height - (if (compact) 13f else 24f) * dp)
        if (plot.width() <= dp || plot.height() <= dp) return
        val cx = plot.centerX(); val cy = plot.centerY(); val r = min(plot.width() * .2f, plot.height() * .44f)
        line.color = grid.color; line.strokeWidth = dp
        for (j in 1..3) canvas.drawOval(cx - r * j / 2, cy - r * j / 2, cx + r * j / 2, cy + r * j / 2, line)
        canvas.drawLine(plot.left, cy, plot.right, cy, grid)
        val left = plot.left + plot.width() * .14f; val right = plot.right - plot.width() * .14f
        val inputY = plot.top + 6f * dp; val outputY = plot.bottom - 5f * dp
        for (i in 0..3) {
            val available = !m.hasIr || m.channels == 4 || i == 0 || i == 3
            if (!available) continue
            val sx = if (i < 2) left else right
            val ex = if (i == 0 || i == 2) left else right
            val cross = sx != ex
            val gain = if (m.pathMuted[i]) 0.0 else 10.0.pow(m.pathGainsDb[i] / 20)
            val intensity = if (m.hasIr) m.pathPeak[i] else gain
            line.strokeWidth = ((1 + 3 * (intensity / (1 + intensity))) * dp).toFloat()
            line.color = ColorUtils.setAlphaComponent(palette[i], if (m.pathMuted[i]) 45 else 190)
            line.pathEffect = if (!m.hasIr || !m.enabled) dashed else null
            path.rewind(); path.moveTo(sx, inputY)
            val bow = (if (cross) .22f else .07f) * plot.width() + (m.pathDelayMs[i] / 20 * .10 * plot.width()).toFloat()
            path.cubicTo(sx + if (sx < cx) bow else -bow, cy - r * .4f,
                ex + if (ex < cx) bow else -bow, cy + r * .4f, ex, outputY)
            canvas.drawPath(path, line); line.pathEffect = null
            val arrowY = outputY - 3f * dp
            canvas.drawLine(ex - 2f * dp, arrowY, ex, outputY, line); canvas.drawLine(ex + 2f * dp, arrowY, ex, outputY, line)
        }
        fill.color = ColorUtils.setAlphaComponent(surface, 245); canvas.drawCircle(cx, cy, r * .55f, fill)
        line.color = primary; line.strokeWidth = dp; canvas.drawCircle(cx, cy, r * .55f, line)
        label(canvas, "头部", cx, cy + text.textSize * .3f, r, primary, Paint.Align.CENTER)
        label(canvas, "L输入", left, plot.top, plot.width() * .3f, primary, Paint.Align.CENTER)
        label(canvas, "R输入", right, plot.top, plot.width() * .3f, primary, Paint.Align.CENTER)
        label(canvas, "L输出", left, plot.bottom + text.textSize, plot.width() * .3f, secondary, Paint.Align.CENTER)
        label(canvas, "R输出", right, plot.bottom + text.textSize, plot.width() * .3f, secondary, Paint.Align.CENTER)
        if (!compact) label(canvas, "${if (m.route == 1) "输入平均→立体声" else "保留原路由"} · ${if (m.swap) "湿声交换" else "不交换"} · ${if (m.fractionalOn) "分数校准${m.fractionalMethod + 1}" else "分数校准关"}", 2f * dp, height - dp, width - 4f * dp)
        cursor?.let { xx ->
            val i = (((xx - plot.left) / plot.width()) * 4).toInt().coerceIn(0, 3)
            tooltip(canvas, listOf(m.pathNames[i], "请求增益${fmt(m.pathGainsDb[i])}dB · ${if (m.pathMuted[i]) "静音" else "有声"}",
                "请求延时${fmt(m.pathDelayMs[i], 3)}ms · ${if (m.pathInverted[i]) "反相" else "同相"}",
                if (m.hasIr) "工作核峰值${fmt(m.pathPeak[i], 5)}${if (m.pending) "（旧核）" else ""}" else "等待真实工作IR"), xx + 2f * dp)
        }
    }
}

/** Only a deliberate long press takes the gesture; ordinary vertical scrolling stays native. */
private class ConvolutionStaticCursor(
    private val view: View,
    private val hit: (Float, Float) -> Boolean,
    private val update: (Float) -> Unit,
    private val release: () -> Unit,
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var pending = false
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var currentX = 0f
    private val hold = Runnable {
        if (pending && view.isAttachedToWindow) { pending = false; held = true; view.parent?.requestDisallowInterceptTouchEvent(true); update(currentX) }
    }
    fun cancel() {
        pending = false; val wasHeld = held; held = false; view.removeCallbacks(hold)
        view.parent?.requestDisallowInterceptTouchEvent(false); if (wasHeld) release()
    }
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hit(e.x, e.y) || e.pointerCount != 1) return false
                cancel(); downX = e.x; currentX = e.x; downY = e.y; pending = true
                view.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount != 1) { cancel(); return false }
                currentX = e.x; if (held) { update(currentX); return true }
                if (pending && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { pending = false; view.removeCallbacks(hold) }
                return true
            }
            MotionEvent.ACTION_UP -> { val click = pending && !held; cancel(); if (click) view.performClick(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { cancel(); return false }
        }
        return pending || held
    }
}
