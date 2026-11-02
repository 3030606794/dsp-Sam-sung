package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Three parameter-derived drawings. No audio capture, polling, driver calls or parameter writes. */
class EqListeningPreview @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val requests = MutableStateFlow<EqListeningModel.Input?>(null)
    private var worker: CoroutineScope? = null
    private var result: EqListeningModel.Result? = null
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 1.5f * density; style = Paint.Style.STROKE
    }
    private val reference = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 0x88)
        strokeWidth = density; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 0x20); strokeWidth = .6f * density; style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 0x88) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground }
    private val path = Path()
    private val barPlot = RectF()
    private val localPlot = RectF()
    private val impulsePlot = RectF()
    private enum class CursorPlot { BANDS, LOCAL, IMPULSE }
    private data class HeldFrame(val model: EqListeningModel.Result, val bars: RectF,
        val local: RectF, val impulse: RectF)
    private var heldFrame: HeldFrame? = null
    private var pendingFrame: EqListeningModel.Result? = null
    private var pendingPlot: CursorPlot? = null
    private var selectedPlot: CursorPlot? = null
    private var waitingForGesture = false
    private var activePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var cursorIndex = -1
    private var tooltip: StaticLayout? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 0x28) }
    private val tooltipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@EqListeningPreview, com.google.android.material.R.attr.colorSurface)
    }
    private val tooltipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(accent, 0xb3); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val tooltipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = 10.5f * scaled }
    private val tooltipBox = RectF()
    private val beginLongPress = Runnable { if (waitingForGesture) beginInspection(lastTouchX) }

    init {
        minimumHeight = dp(240).roundToInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
    }

    fun setState(levels: DoubleArray, filterType: Int, interpolation: Int,
        filterLabel: String, interpolationLabel: String, effectEnabled: Boolean, activeBand: Int = -1) {
        val snapshot = DoubleArray(15) { index -> levels.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0 }
        val next = EqListeningModel.Input(snapshot, filterType.coerceIn(0, 5), interpolation.coerceIn(0, 1),
            filterLabel, interpolationLabel, effectEnabled, activeBand.takeIf { it in 0..14 } ?: -1)
        if (next.sameAs(requests.value)) return
        requests.value = next
        contentDescription = "均衡器听感预览。频段增益倾向，并非实际音频能量。$filterLabel，$interpolationLabel。" +
            if (effectEnabled) "正在计算参数模型" else "曲线均衡器已关闭（预览模式）"
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        worker = scope
        scope.launch {
            requests.filterNotNull().collect { input ->
                val computation = currentCoroutineContext()
                val computed = try {
                    EqListeningModel.compute(input) { computation.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: LinkageError) {
                    EqListeningModel.unavailable(input)
                } catch (_: Exception) {
                    EqListeningModel.unavailable(input)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (worker === scope && isAttachedToWindow) {
                        result = computed
                        contentDescription = "频段增益倾向，非实际音频能量。${computed.summary}。" +
                            "${input.filterLabel}，${input.interpolationLabel}。单位脉冲为48k参考有限窗口模型，非完整驱动核。" +
                            if (requests.value?.effectEnabled != false) "" else "曲线均衡器已关闭（预览模式）"
                        invalidate()
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        finishGesture()
        worker?.cancel()
        worker = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320).roundToInt(), widthMeasureSpec),
            resolveSize(dp(240).roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        releaseChangedDomain()
        val latest = requests.value ?: return
        // Keep presenting complete, self-consistent frames while the next conflated state computes.
        val held = heldFrame
        val model = held?.model ?: result
        val input = model?.input ?: latest
        val half = width * .5f
        val unit = height / 240f
        fun y(value: Float) = value * unit
        barPlot.set(dp(11), y(33f), half - dp(10), y(88f))
        localPlot.set(half + dp(12), y(33f), width - dp(11), y(91f))
        impulsePlot.set(dp(14), y(159f), width - dp(11), y(209f))
        if (held != null) {
            barPlot.set(held.bars); localPlot.set(held.local); impulsePlot.set(held.impulse)
        }
        val layer = if (!latest.effectEnabled) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 175) else canvas.save()
        label(canvas, "频段增益倾向", dp(9), y(15f), half - dp(18), 10.5f)
        label(canvas, "局部插值 · ${input.interpolationLabel}", half + dp(10), y(15f), half - dp(20), 9.5f)
        label(canvas, "相对 0 dB · 非音频能量", dp(9), y(28f), half - dp(18), 8f, muted = true)
        label(canvas, if (model != null) "聚焦 ${hz(EqualizerSurface.SCALE[model.focus])} Hz" else "当前控制点邻区",
            half + dp(10), y(28f), half - dp(20), 8f, muted = true)
        drawBars(canvas, model, unit)
        drawLocal(canvas, input, model, unit)
        label(canvas, model?.summary ?: if (result == null) "正在计算参数预览…" else "正在更新参数预览…",
            dp(9), y(128f), width - dp(18), 10f)
        label(canvas, "单位脉冲 · ${input.filterLabel}", dp(9), y(148f), width * .62f, 10f)
        val peak = model?.impulse?.maxOfOrNull { abs(it) }
        if (peak != null) label(canvas, String.format(Locale.ROOT, "峰值 %.3f", peak), width * .67f,
            y(148f), width * .3f, 8f, muted = true)
        drawImpulse(canvas, model, unit)
        val method = if (input.filterType == 0) "最小相位幅频重建" else "原生复频响 IFFT"
        label(canvas, "$method · 48k 参考 · 有限窗口，非完整驱动核", dp(9), y(236f), width - dp(18), 8f, muted = true)
        canvas.restoreToCount(layer)
        if (!latest.effectEnabled) {
            text.color = ColorUtils.setAlphaComponent(foreground, 0xa8)
            text.textSize = 10f * scaled
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("曲线均衡器已关闭（预览模式）", width * .5f, y(139f), text)
        }
        drawCursor(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) finishGesture()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishGesture()
                val model = result ?: return false
                if (model.error != null) return false
                val plot = when {
                    barPlot.contains(event.x, event.y) && model.averages.size == 6 -> CursorPlot.BANDS
                    localPlot.contains(event.x, event.y) && model.localFrequencies.isNotEmpty() -> CursorPlot.LOCAL
                    impulsePlot.contains(event.x, event.y) && model.impulse.isNotEmpty() -> CursorPlot.IMPULSE
                    else -> return false
                }
                pendingPlot = plot
                pendingFrame = model
                waitingForGesture = true
                activePointer = event.getPointerId(0)
                downX = event.x; downY = event.y; lastTouchX = event.x
                postDelayed(beginLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(activePointer)
                if (pointer < 0) { finishGesture(); return false }
                val x = event.getX(pointer)
                val y = event.getY(pointer)
                if (heldFrame != null) { selectCursor(x); return true }
                if (!waitingForGesture) return false
                lastTouchX = x
                val dx = abs(x - downX)
                val dy = abs(y - downY)
                if (dy > touchSlop && dy >= dx) { finishGesture(); return false }
                if (dx > touchSlop && dx > dy * 1.2f) beginInspection(x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val consumed = waitingForGesture || heldFrame != null
                if (consumed) performClick()
                finishGesture()
                return consumed
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                finishGesture()
                return false
            }
        }
        return waitingForGesture || heldFrame != null
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginInspection(x: Float) {
        removeCallbacks(beginLongPress)
        val plot = pendingPlot ?: return
        val model = result ?: return
        if (!waitingForGesture || !sameDomain(pendingFrame, model)) { finishGesture(); return }
        heldFrame = HeldFrame(model, RectF(barPlot), RectF(localPlot), RectF(impulsePlot))
        selectedPlot = plot
        waitingForGesture = false
        cursorIndex = -1
        parent?.requestDisallowInterceptTouchEvent(true)
        selectCursor(x)
    }

    private fun selectCursor(x: Float) {
        val held = heldFrame ?: return
        val model = held.model
        val plot = when (selectedPlot) {
            CursorPlot.BANDS -> held.bars
            CursorPlot.LOCAL -> held.local
            CursorPlot.IMPULSE -> held.impulse
            null -> return
        }
        if (plot.width() <= 0f) { finishGesture(); return }
        val fraction = ((x - plot.left) / plot.width()).coerceIn(0f, 1f)
        val index = when (selectedPlot) {
            CursorPlot.BANDS -> (fraction * 6).toInt().coerceIn(0, 5)
            CursorPlot.LOCAL -> (fraction * model.localFrequencies.lastIndex).roundToInt()
            else -> (fraction * model.impulse.size).roundToInt().coerceIn(model.impulse.indices)
        }
        if (index == cursorIndex) return
        cursorIndex = index
        val lines = when (selectedPlot) {
            CursorPlot.BANDS -> listOf("${EqListeningModel.names[index]} · ${FULL_RANGES[index]} Hz",
                String.format(Locale.ROOT, "区域平均增益 %+.2f dB（非音频能量）", model.averages[index]))
            CursorPlot.LOCAL -> {
                val frequency = model.localFrequencies[index]
                val gray = controlLineValue(model, frequency)
                val comparison = gray?.let { String.format(Locale.ROOT, "灰线 %+.2f dB（控制点连线）", it) } ?: "灰线：无控制点连线"
                listOf(String.format(Locale.ROOT, "%.2f Hz · 紫线 %+.2f dB", frequency, model.interpolationDb[index]),
                    if (model.input.filterType == 0) comparison else "$comparison · 保存模型，不参与 IIR")
            }
            else -> listOf(String.format(Locale.ROOT, "%.4f ms · 样本索引 %d", index * 1000.0 / EqListeningModel.SAMPLE_RATE, index),
                String.format(Locale.ROOT, "原始线性幅度 %.8g", model.impulse[index]))
        }.let { if (requests.value?.effectEnabled == false) it + "曲线均衡器已关闭（预览模式）" else it }
        val padding = dp(8)
        val available = (width - dp(16) - padding * 2).toInt().coerceAtLeast(1)
        val message = lines.joinToString("\n")
        tooltip = StaticLayout.Builder.obtain(message, 0, message.length, tooltipText, available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        invalidate()
    }

    /** The dashed reference is the straight connection drawn on the logarithmic x axis. */
    private fun controlLineValue(model: EqListeningModel.Result, frequency: Double): Double? {
        val axis = EqualizerSurface.SCALE
        if (frequency !in axis.first()..axis.last()) return null
        val left = axis.indexOfLast { it <= frequency }.coerceIn(0, axis.lastIndex - 1)
        val fraction = ln(frequency / axis[left]) / ln(axis[left + 1] / axis[left])
        return model.input.levels[left] + (model.input.levels[left + 1] - model.input.levels[left]) * fraction
    }

    private fun sameDomain(first: EqListeningModel.Result?, second: EqListeningModel.Result?) =
        first != null && second != null && second.error == null && first.input.filterType == second.input.filterType &&
            first.input.interpolation == second.input.interpolation && first.focus == second.focus &&
            first.localFrequencies.size == second.localFrequencies.size &&
            first.localFrequencies.firstOrNull() == second.localFrequencies.firstOrNull() &&
            first.localFrequencies.lastOrNull() == second.localFrequencies.lastOrNull() && first.impulse.size == second.impulse.size

    private fun releaseChangedDomain() {
        val held = heldFrame?.model ?: pendingFrame ?: return
        val latest = requests.value
        if (latest == null || latest.filterType != held.input.filterType || latest.interpolation != held.input.interpolation ||
            !sameDomain(held, result)) finishGesture()
    }

    private fun finishGesture() {
        removeCallbacks(beginLongPress)
        val changed = waitingForGesture || heldFrame != null
        waitingForGesture = false
        activePointer = -1
        pendingPlot = null
        pendingFrame = null
        selectedPlot = null
        heldFrame = null
        cursorIndex = -1
        tooltip = null
        parent?.requestDisallowInterceptTouchEvent(false)
        if (changed) invalidate()
    }

    private fun drawCursor(canvas: Canvas) {
        val held = heldFrame ?: return
        val model = held.model
        val index = cursorIndex
        if (index < 0) return
        val plot: RectF
        val x: Float
        val y: Float
        var grayY: Float? = null
        when (selectedPlot) {
            CursorPlot.BANDS -> {
                plot = held.bars
                val slot = plot.width() / 6f
                val range = max(3.0, ceil(model.averages.maxOf { abs(it) } / 3.0) * 3.0)
                x = plot.left + slot * (index + .5f)
                y = plot.centerY() - (model.averages[index] / range * plot.height() * .5).toFloat()
                canvas.drawRect(plot.left + slot * index, plot.top, plot.left + slot * (index + 1), plot.bottom, selectionFill)
            }
            CursorPlot.LOCAL -> {
                plot = held.local
                val lower = model.localFrequencies.first()
                val upper = model.localFrequencies.last()
                val frequency = model.localFrequencies[index]
                val controlPeak = EqualizerSurface.SCALE.indices.filter { EqualizerSurface.SCALE[it] in lower..upper }
                    .maxOfOrNull { abs(model.input.levels[it]) } ?: 0.0
                val largest = max(model.interpolationDb.maxOf { abs(it) }, controlPeak)
                val range = max(3.0, ceil(largest / 3.0) * 3.0)
                x = plot.left + (ln(frequency / lower) / ln(upper / lower) * plot.width()).toFloat()
                y = plot.centerY() - (model.interpolationDb[index] / range * plot.height() * .45).toFloat()
                grayY = controlLineValue(model, frequency)?.let { plot.centerY() - (it / range * plot.height() * .45).toFloat() }
            }
            CursorPlot.IMPULSE -> {
                plot = held.impulse
                val scale = max(1e-12, model.impulse.maxOf { abs(it) })
                x = plot.left + (index.toDouble() / model.impulse.size * plot.width()).toFloat()
                y = plot.centerY() - (model.impulse[index] / scale * plot.height() * .44).toFloat()
            }
            null -> return
        }
        canvas.save()
        canvas.clipRect(plot)
        canvas.drawLine(x, plot.top, x, plot.bottom, cursorLine)
        canvas.drawLine(plot.left, y, plot.right, y, cursorLine)
        grayY?.let {
            dot.color = ColorUtils.setAlphaComponent(foreground, 0xb3)
            canvas.drawCircle(x, it, dp(3), dot)
        }
        dot.color = accent
        canvas.drawCircle(x, y, dp(3), dot)
        canvas.restore()
        val layout = tooltip ?: return
        val padding = dp(8)
        val boxWidth = (layout.width + padding * 2).coerceAtMost(width - dp(8))
        val boxHeight = (layout.height + padding * 2).coerceAtMost(height - dp(8))
        val left = ((width - boxWidth) * .5f).coerceAtLeast(0f)
        // Use the gap between the upper plots and the impulse grid, leaving the three plots readable.
        val top = (held.impulse.top - boxHeight - dp(5)).coerceIn(dp(4), max(dp(4), height - boxHeight - dp(4)))
        tooltipBox.set(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(tooltipBox, dp(5), dp(5), tooltipFill)
        canvas.drawRoundRect(tooltipBox, dp(5), dp(5), tooltipBorder)
        canvas.save()
        canvas.clipRect(tooltipBox)
        canvas.translate(left + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawBars(canvas: Canvas, model: EqListeningModel.Result?, unit: Float) {
        val values = model?.averages ?: DoubleArray(0)
        val range = max(3.0, ceil((values.maxOfOrNull { abs(it) } ?: 3.0) / 3.0) * 3.0)
        val zero = barPlot.centerY()
        frame(canvas, barPlot)
        canvas.drawLine(barPlot.left, zero, barPlot.right, zero, reference)
        val slot = barPlot.width() / 6f
        for (index in 0..5) {
            val x = barPlot.left + slot * (index + .5f)
            if (values.size == 6) {
                val tip = zero - (values[index] / range * barPlot.height() * .5).toFloat()
                canvas.drawRect(x - slot * .27f, min(zero, tip), x + slot * .27f, max(zero, tip), fill)
                centered(canvas, String.format(Locale.ROOT, "%+.1f", values[index]), x,
                    if (tip <= zero) max(barPlot.top + 7f * unit, tip - 2f * unit) else min(barPlot.bottom - 1f * unit, tip + 8f * unit), 6.8f)
            }
            centered(canvas, EqListeningModel.names[index], x, 101f * unit, 8f)
            centered(canvas, EqListeningModel.ranges[index], x, 113f * unit, 5.8f, muted = true)
        }
        if (values.isEmpty()) centered(canvas, model?.error ?: "计算中…", barPlot.centerX(), zero, 8f, muted = true)
    }

    private fun drawLocal(canvas: Canvas, input: EqListeningModel.Input, model: EqListeningModel.Result?, unit: Float) {
        frame(canvas, localPlot)
        if (model != null && model.localFrequencies.isNotEmpty()) {
            val lower = model.localFrequencies.first()
            val upper = model.localFrequencies.last()
            val visible = EqualizerSurface.SCALE.indices.filter { EqualizerSurface.SCALE[it] in lower..upper }
            val largest = max(model.interpolationDb.maxOfOrNull { abs(it) } ?: 0.0,
                visible.maxOfOrNull { abs(input.levels[it]) } ?: 0.0)
            val range = max(3.0, ceil(largest / 3.0) * 3.0)
            fun x(frequency: Double) = localPlot.left + (ln(frequency / lower) / ln(upper / lower) * localPlot.width()).toFloat()
            fun y(gain: Double) = localPlot.centerY() - (gain / range * localPlot.height() * .45).toFloat()
            canvas.save()
            canvas.clipRect(localPlot)
            path.reset()
            visible.forEachIndexed { position, index ->
                val px = x(EqualizerSurface.SCALE[index]); val py = y(input.levels[index])
                if (position == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, reference)
            path.reset()
            model.localFrequencies.indices.forEach { index ->
                val px = x(model.localFrequencies[index]); val py = y(model.interpolationDb[index])
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, line)
            visible.forEach { index ->
                dot.color = if (index == model.focus) accent else ColorUtils.setAlphaComponent(foreground, 0x99)
                canvas.drawCircle(x(EqualizerSurface.SCALE[index]), y(input.levels[index]), dp(if (index == model.focus) 3 else 2), dot)
            }
            canvas.restore()
            centered(canvas, hz(lower), localPlot.left, 101f * unit, 7f, muted = true)
            centered(canvas, hz(EqualizerSurface.SCALE[model.focus]), localPlot.centerX(), 101f * unit, 7f, muted = true)
            centered(canvas, hz(upper), localPlot.right, 101f * unit, 7f, muted = true)
        } else centered(canvas, model?.error ?: "计算中…", localPlot.centerX(), localPlot.centerY(), 8f, muted = true)
        val note = if (input.filterType != 0) "IIR 不使用插值；此处为保存模型"
            else if (input.levels.all { abs(it) < 1e-9 }) "平直设置，曲线重合"
            else "灰虚线：控制点 · 紫实线：插值"
        label(canvas, note, localPlot.left, 114f * unit, localPlot.width(), 7.5f, muted = true)
    }

    private fun drawImpulse(canvas: Canvas, model: EqListeningModel.Result?, unit: Float) {
        frame(canvas, impulsePlot)
        val zero = impulsePlot.centerY()
        canvas.drawLine(impulsePlot.left, zero, impulsePlot.right, zero, reference)
        val impulse = model?.impulse ?: DoubleArray(0)
        if (impulse.isNotEmpty()) {
            val scale = max(1e-12, impulse.maxOf { abs(it) })
            val columns = min(480, impulsePlot.width().toInt()).coerceAtLeast(2)
            path.reset()
            for (column in 0 until columns) {
                val start = column * impulse.size / columns
                val end = max(start + 1, (column + 1) * impulse.size / columns)
                var low = Double.POSITIVE_INFINITY
                var high = Double.NEGATIVE_INFINITY
                for (index in start until end) { low = min(low, impulse[index]); high = max(high, impulse[index]) }
                val x = impulsePlot.left + column * impulsePlot.width() / (columns - 1)
                path.moveTo(x, zero - (high / scale * impulsePlot.height() * .44).toFloat())
                path.lineTo(x, zero - (low / scale * impulsePlot.height() * .44).toFloat())
            }
            canvas.drawPath(path, line)
        } else centered(canvas, model?.error ?: "计算中…", impulsePlot.centerX(), zero - 4f * unit, 8f, muted = true)
        val duration = EqListeningModel.IMPULSE_SIZE * 1000.0 / EqListeningModel.SAMPLE_RATE
        for (index in 0..4) {
            val x = impulsePlot.left + impulsePlot.width() * index / 4f
            centered(canvas, String.format(Locale.ROOT, "%.0f", duration * index / 4), x, 222f * unit, 7f, muted = true)
        }
        label(canvas, "ms", impulsePlot.right - dp(12), 230f * unit, dp(15), 7f, muted = true)
    }

    private fun frame(canvas: Canvas, plot: RectF) {
        canvas.drawRect(plot, grid)
        for (index in 1..3) {
            val x = plot.left + plot.width() * index / 4
            val y = plot.top + plot.height() * index / 4
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            canvas.drawLine(plot.left, y, plot.right, y, grid)
        }
    }

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, available: Float, size: Float, muted: Boolean = false) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = size * scaled
        text.color = if (muted) ColorUtils.setAlphaComponent(foreground, 0x99) else foreground
        val fitted = TextUtils.ellipsize(value, android.text.TextPaint(text), available.coerceAtLeast(1f), TextUtils.TruncateAt.END)
        canvas.drawText(fitted.toString(), x, y, text)
    }

    private fun centered(canvas: Canvas, value: String, x: Float, y: Float, size: Float, muted: Boolean = false) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size * scaled
        text.color = if (muted) ColorUtils.setAlphaComponent(foreground, 0x99) else foreground
        canvas.drawText(value, x, y, text)
    }

    private fun hz(value: Double) = if (value >= 1000.0) String.format(Locale.ROOT, "%.1fk", value / 1000.0)
        else String.format(Locale.ROOT, "%.0f", value)
    private fun dp(value: Int) = value * density

    companion object {
        private val FULL_RANGES = arrayOf("1–60", "60–250", "250–500", "500–2000", "2000–6000", "6000–20000")
    }
}
