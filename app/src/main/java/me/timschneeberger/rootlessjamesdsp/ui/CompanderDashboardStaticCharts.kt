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
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** Independent static observers. Sample callbacks deliberately do not schedule static redraws. */
internal abstract class CompanderStaticChart(context: Context) : View(context) {
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
    protected var model: CompanderDashboardModel? = null
        private set
    protected var heldModel: CompanderDashboardModel? = null
    protected var cursor: Float? = null
    private var active = false
    private var attached = false
    protected val canDraw get() = active && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height < 116f * dp
    private val gesture = CompanderStaticCursor(this, { x, y -> plot.contains(x, y) }, {
        if (heldModel == null) heldModel = model
        cursor = it.coerceIn(plot.left, plot.right); invalidate()
    }, { cursor = null; heldModel = null; if (canDraw) invalidate() })
    fun setModel(value: CompanderDashboardModel) { if (model === value) return; model = value; if (canDraw) invalidate() }
    fun setActive(value: Boolean) { active = value; if (canDraw) invalidate() }
    @Suppress("UNUSED_PARAMETER")
    fun showSample(value: CompanderDashboardRuntimeMonitor.Sample) = Unit
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

/** Seven frequency-control references share one explicitly stated background, never seven thresholds. */
internal class CompanderIoView(context: Context) : CompanderStaticChart(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val m = heldModel ?: model ?: return
        val title = if (m.standard) "标准${m.typeName} · 稳态 I/O" else "七频率控制点 · I/O参考族"
        label(canvas, title, 2f * dp, text.textSize + dp, width - 4f * dp)
        label(canvas, if (m.standard) "全局 ${fmt(m.thresholdDb)}dBFS / ${fmt(m.ratio, 2)}:1${if (!m.coreOn) "（未接管）" else ""}"
            else "共同背景 −36dBFS · 非7个独立压缩器", 2f * dp, text.textSize * 2.3f, width - 4f * dp, secondary)
        val mini = !compact && height > 146f * dp
        plot.set(25f * dp, text.textSize * 3.1f, width - 6f * dp,
            height - (if (mini) 43f else if (compact) 20f else 28f) * dp)
        if (plot.width() <= dp || plot.height() <= dp) return
        fun x(v: Double) = plot.left + ((v + 80) / 80).toFloat() * plot.width()
        fun y(v: Double) = plot.bottom - ((v + 100) / 120).toFloat() * plot.height()
        grid(canvas, -100.0, 20.0, listOf(-80.0 to "−80", -40.0 to "−40", 0.0 to "0 dBFS"), ::x)
        canvas.save(); canvas.clipRect(plot)
        line.color = ColorUtils.setAlphaComponent(foreground, 100); line.pathEffect = dashed
        canvas.drawLine(x(-80.0), y(-80.0), x(0.0), y(0.0), line); line.pathEffect = null
        val activeCount = if (m.standard) 1 else 7
        for (i in 0 until activeCount) curve(canvas, m.ioCurves[i], palette[i], !m.enabled,
            if (m.standard && !m.coreOn) 100 else 215, { x(m.inputLevels[it]) }, ::y)
        if (m.standard && m.settings.type == 3) curve(canvas, m.gateClosingCurve, secondary, true, 255, { x(m.inputLevels[it]) }, ::y)
        val threshold = if (m.standard) m.thresholdDb else m.backgroundReferenceDb + m.relativeDb
        if (m.kneeDb > 0) {
            fill.color = ColorUtils.setAlphaComponent(primary, 20)
            canvas.drawRect(x(threshold - m.kneeDb / 2), plot.top, x(threshold + m.kneeDb / 2), plot.bottom, fill)
        }
        line.color = ColorUtils.setAlphaComponent(secondary, 160); line.pathEffect = dashed
        canvas.drawLine(x(threshold), plot.top, x(threshold), plot.bottom, line); line.pathEffect = null
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        if (!m.enabled && cursor == null) label(canvas, "压扩器已关闭（参数预览）", plot.centerX(), plot.top + text.textSize * 1.4f,
            plot.width() - dp * 3, secondary, Paint.Align.CENTER)
        if (!compact) {
            if (m.standard) label(canvas, if (m.settings.type == 3) "实线开门 / 虚线已开门回差 · 非瞬时状态"
                else "斜线为1:1 · 阴影为软拐点 · I/O不含选频相位", plot.left, plot.bottom + text.textSize * 2.4f, plot.width())
            else for (i in 0..6) {
                val each = width / 7f; label(canvas, "${i + 1}:${hz(m.nodes[i])}", each * (i + .5f), plot.bottom + text.textSize * 2.4f,
                    each - dp, palette[i], Paint.Align.CENTER)
            }
        }
        if (mini) {
            val bottom = height - 9f * dp; val top = bottom - 12f * dp
            line.color = grid.color; canvas.drawLine(plot.left, bottom, plot.right, bottom, line)
            listOf(m.attackEnvelope, m.releaseEnvelope, m.backgroundEnvelope).forEachIndexed { i, data ->
                path.rewind(); data.forEachIndexed { j, value -> val xx = plot.left + j.toFloat() / data.lastIndex * plot.width(); val yy = bottom - value.toFloat() * (bottom - top)
                    if (j == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy) }
                line.color = if (i == 0) primary else if (i == 1) secondary else cyan; line.pathEffect = if (i == 2) dashed else null
                canvas.drawPath(path, line); line.pathEffect = null
            }
            label(canvas, "A${fmt(m.attackMs)} / H${fmt(m.holdMs, 0)} / R${fmt(m.releaseMs, 0)}ms${if (!m.standard && !m.timeOn) "（平滑未接管）" else ""} · 背景${fmt(m.backgroundMs, 0)}ms", plot.left, height - dp, plot.width())
        }
        cursor?.let { xx ->
            val level = -80.0 + ((xx - plot.left) / plot.width()) * 80.0
            val rows = mutableListOf("输入 ${fmt(level)} dBFS")
            if (m.standard) {
                val gain = m.standardTarget(level)
                rows += "控制增益 ${fmt(gain)}dB · 输出 ${fmt(m.outputForGain(level, gain))}dBFS"
                rows += "阈值${fmt(m.thresholdDb)} · 比率${fmt(m.ratio, 2)}:1"
                if (m.settings.type == 3) rows += "已开门增益 ${fmt(m.standardTarget(level, true))}dB"
            } else {
                for (i in 0..6) {
                    val gain = m.targetGain(level, m.nodeFrequencies[i], m.coefficients[i])
                    rows += "${i + 1}点 ${hz(m.nodes[i])}Hz · 系数${fmt(m.coefficients[i], 3)} · ${fmt(gain)}dB"
                }
            }
            tooltip(canvas, rows, xx + 3f * dp)
        }
    }
}

/** Continuous frequency action masks; old control nodes and global standard dynamics remain separate. */
internal class CompanderBandsView(context: Context) : CompanderStaticChart(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val m = heldModel ?: model ?: return
        label(canvas, "七点频率作用包络 · 模型", 2f * dp, text.textSize + dp, width - 4f * dp)
        label(canvas, if (m.standard) "标准全局控制 · 6/12/24dB越阈参考"
            else "越背景6/12/24dB · ${if (m.frequencyScale == 4.0) "原FFT f/4" else "实际Hz"}映射", 2f * dp,
            text.textSize * 2.3f, width - 4f * dp, secondary)
        plot.set(25f * dp, text.textSize * 3.1f, width - 20f * dp, height - (if (compact) 20f else 30f) * dp)
        if (plot.width() <= dp || plot.height() <= dp) return
        val low = min(-24.0, (m.actionCurves.minOfOrNull { it.minOrNull() ?: 0.0 } ?: 0.0).coerceAtLeast(-100.0) - 3)
        val high = max(18.0, (m.actionCurves.maxOfOrNull { it.maxOrNull() ?: 0.0 } ?: 0.0).coerceAtMost(60.0) + 3)
        fun x(hz: Double) = plot.left + (ln(hz / m.frequencies.first()) / ln(m.frequencies.last() / m.frequencies.first())).toFloat() * plot.width()
        fun y(v: Double) = plot.bottom - ((v - low) / (high - low)).toFloat() * plot.height()
        fun yCoefficient(v: Double) = plot.bottom - ((v + 1.2) / 2.4).toFloat() * plot.height()
        grid(canvas, low, high, listOf(20.0 to "20", 200.0 to "200", 2000.0 to "2k", m.frequencies.last() to "${hz(m.frequencies.last())}Hz"), ::x)
        canvas.save(); canvas.clipRect(plot)
        m.frequencies.forEachIndexed { i, f ->
            if (i == m.frequencies.lastIndex || m.rangeMask[i] <= .01) return@forEachIndexed
            fill.color = ColorUtils.setAlphaComponent(cyan, (m.rangeMask[i].coerceIn(0.0, 1.0) * 20).toInt())
            canvas.drawRect(x(f), plot.top, x(m.frequencies[i + 1]), plot.bottom, fill)
        }
        line.color = ColorUtils.setAlphaComponent(foreground, 100); line.pathEffect = dashed
        canvas.drawLine(plot.left, y(0.0), plot.right, y(0.0), line); line.pathEffect = null
        m.actionCurves.forEachIndexed { i, values ->
            path.rewind(); path.moveTo(x(m.frequencies.first()), y(m.outputDb))
            values.forEachIndexed { j, v -> path.lineTo(x(m.frequencies[j]), y(v)) }
            path.lineTo(x(m.frequencies.last()), y(m.outputDb)); path.close()
            fill.color = ColorUtils.setAlphaComponent(palette[i], 17); canvas.drawPath(path, fill)
            curve(canvas, values, palette[i], !m.enabled, 220, { x(m.frequencies[it]) }, ::y)
        }
        curve(canvas, m.coefficientCurve, secondary, true, if (m.standard) 60 else 175, { x(m.frequencies[it]) }, ::yCoefficient)
        if (m.standard && m.settings.sidechainOn) curve(canvas, m.sidechainDb, foreground, true, 120, { x(m.frequencies[it]) }, ::y)
        if (!m.standard) m.nodeFrequencies.forEachIndexed { i, f ->
            if (f !in m.frequencies.first()..m.frequencies.last()) return@forEachIndexed
            line.color = ColorUtils.setAlphaComponent(palette[i], 90); line.pathEffect = dashed
            canvas.drawLine(x(f), plot.top, x(f), plot.bottom, line); line.pathEffect = null
            fill.color = palette[i]; canvas.drawCircle(x(f), yCoefficient(m.coefficients[i]), 2.3f * dp, fill)
        }
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "+1.2", plot.right + dp, plot.top + text.textSize, width - plot.right - 2f * dp, secondary)
        label(canvas, "系数", plot.right + dp, plot.centerY(), width - plot.right - 2f * dp, secondary)
        label(canvas, "−1.2", plot.right + dp, plot.bottom, width - plot.right - 2f * dp, secondary)
        if (!compact) label(canvas, if (m.standard) "彩线=选频合成dB · 灰虚线=检测侧链 · 旧系数未接管"
            else "彩线=作用dB · 橙虚线/右轴=系数 · 非固定音频频响", plot.left, height - dp, width - plot.left - 2f * dp)
        if (!m.enabled && cursor == null) label(canvas, "压扩器已关闭（参数预览）", plot.centerX(), plot.top + text.textSize * 1.5f,
            plot.width() - 4f * dp, primary, Paint.Align.CENTER)
        cursor?.let { xx ->
            val f = m.frequencies.first() * (m.frequencies.last() / m.frequencies.first()).pow(((xx - plot.left) / plot.width()).toDouble())
            val rows = mutableListOf("${fmt(f)} Hz · 系数 ${fmt(m.coefficientAt(f), 3)}")
            m.referenceExcessDb.forEach { excess ->
                val level = if (m.standard) if (m.settings.type == 0) m.thresholdDb + excess else m.thresholdDb - excess
                    else m.backgroundReferenceDb + excess
                rows += "越阈/背景${fmt(excess, 0)}dB → ${fmt(m.responseAt(level.coerceIn(-100.0, 0.0), f))}dB"
            }
            rows += "处理带 ${if (m.rangeLowHz == 0.0) "不限" else hz(m.rangeLowHz)}–${if (m.rangeHighHz == 0.0) "不限" else hz(m.rangeHighHz)}Hz"
            tooltip(canvas, rows, xx + 2f * dp)
        }
    }
}

/** Only a deliberate long press takes the gesture; ordinary vertical scrolling stays native. */
private class CompanderStaticCursor(
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
