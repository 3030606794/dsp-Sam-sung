package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import java.util.Locale
import kotlin.math.*

/** 48 kHz steady-state parameter models, never a claim about the running driver or measured audio. */
class BassStaticResponseView(context: Context, private val dynamic: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val deepColor = if (dark) Color.rgb(255, 181, 105) else Color.rgb(167, 79, 20)
    private val punchColor = if (dark) Color.rgb(115, 217, 172) else Color.rgb(18, 123, 83)
    private val dynamicColor = if (dark) Color.rgb(122, 197, 255) else Color.rgb(28, 114, 180)
    private val inactiveColor = ColorUtils.setAlphaComponent(foregroundColor, 0x58)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foregroundColor
        textSize = min(11f * resources.displayMetrics.scaledDensity, 13f * density)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0xbe)
        textSize = min(9f * resources.displayMetrics.scaledDensity, 11f * density)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x25)
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val dashed = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(dynamicColor, 0x16) }
    private val plot = RectF()
    private val ticks = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
    private val paths = Array(5) { Path() }
    private val labels = arrayOf("模式合成", "低通支路", "深潜", "冲击", "动态示意")
    private val colors = intArrayOf(accent, foregroundColor, deepColor, punchColor, dynamicColor)
    private var effectEnabled = false
    private var baseEnabled = false
    private var mode = 0
    private var values = BassParameters.viper.map { it.default }.toIntArray()
    private var packet = BassParameters.dynamic.map { it.default }.toIntArray()
    private var lowPass = Biquad.lowPass(70.0, .53)
    private var deep = Biquad.peak(44.0, 0.0, .75)
    private var punch = Biquad.peak(80.0, 0.0, .2)
    private val subLowPass = Biquad.lowPass(380.0, .6)
    private var factor = .5
    private var cursorHz: Double? = null
    private var cursorLabel = ""
    private val gesture = BassChartGesture(this, { x, y -> plot.contains(x, y) },
        { x -> selectFrequency(x) }, { cursorHz = null; cursorLabel = ""; invalidate() })

    init { updateFilters() }

    fun setEffectEnabled(enabled: Boolean) {
        if (effectEnabled == enabled) return
        gesture.clear()
        effectEnabled = enabled
        invalidate()
        updateDescription()
    }

    fun setViperConfiguration(source: Map<String, *>) {
        val next = BassParameters.viper.map { BassParameters.value(source, it) }.toIntArray()
        val nextMode = (source["v_bass_mode"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
        val nextEnabled = source["v_bass"] as? Boolean ?: false
        if (next.contentEquals(values) && nextMode == mode && nextEnabled == baseEnabled) return
        gesture.clear()
        values = next
        mode = nextMode
        baseEnabled = nextEnabled
        updateFilters()
    }

    fun setDynamicConfiguration(source: IntArray) {
        val next = normalizedDynamicBassPacket(source)
        if (next.contentEquals(packet)) return
        gesture.clear()
        packet = next
        rebuildPaths()
        updateDescription()
        invalidate()
    }

    private fun updateFilters() {
        factor = values[1] / 100.0
        lowPass = Biquad.lowPass(values[0].toDouble(), values[2] / 100.0)
        val baseDb = 20.0 * log10(max(1e-6, factor * 2.5))
        // The driver maps deep/punch offsets and octave bandwidths in Subwoofer::Refresh.
        deep = Biquad.peak(values[3].toDouble(), baseDb + values[5] / 10.0, values[7] / 100.0)
        punch = Biquad.peak(values[4].toDouble(), baseDb - 18.06179974 + values[6] / 10.0, values[8] / 100.0)
        rebuildPaths()
        updateDescription()
        invalidate()
    }

    private fun seriesDb(index: Int, hz: Double): Double = when (index) {
        0 -> if (mode == 2) {
            (Complex(.5, 0.0) + subLowPass.at(hz) * (deep.at(hz) * punch.at(hz) - Complex.ONE) * .6).db()
        } else (Complex.ONE + lowPass.at(hz) * factor).db()
        1 -> (if (mode == 2) subLowPass else lowPass).at(hz).db()
        2 -> deep.at(hz).db()
        3 -> punch.at(hz).db()
        else -> {
            // The adaptive center is only illustrative; actual tracking is data-dependent.
            val center = (packet[1] + packet[2]) * .5
            val gain = packet[0] / 10.0
            val q = packet[3] / 100.0
            val ratio = hz / max(.5, center)
            val a = 10.0.pow(gain / 40.0)
            val base = (1.0 - ratio * ratio).pow(2.0)
            10.0 * log10((base + (a * ratio / q).pow(2.0)) / (base + (ratio / (a * q)).pow(2.0)))
        }
    }

    private fun active(index: Int): Boolean = when {
        index == 4 -> dynamic && effectEnabled
        index >= 2 -> (if (dynamic) baseEnabled else effectEnabled) && mode == 2
        else -> if (dynamic) baseEnabled else effectEnabled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gesture.clear()
        plot.set(30f * density, 43f * density, w - 8f * density, h - 69f * density)
        rebuildPaths()
    }

    private fun rebuildPaths() {
        paths.forEach { it.rewind() }
        if (plot.width() <= 0f || plot.height() <= 0f) return
        for (index in paths.indices) {
            if (index == 4 && !dynamic) continue
            for (step in 0..256) {
                val fraction = step / 256.0
                val hz = 10.0 * exp(ln(50.0) * fraction)
                val x = plot.left + plot.width() * fraction.toFloat()
                val y = valueY(seriesDb(index, hz))
                if (step == 0) paths[index].moveTo(x, y) else paths[index].lineTo(x, y)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        titlePaint.textAlign = Paint.Align.LEFT
        fitted(canvas, "10–500 Hz · 静态参数模型", density, 14f * density, width - 2f * density, titlePaint)
        textPaint.textAlign = Paint.Align.LEFT
        val baseOn = if (dynamic) baseEnabled else effectEnabled
        fitted(canvas, "基础模块：${modeName()} · ${if (baseOn) "已启用" else "未启用（灰色）"}",
            density, 29f * density, width - 2f * density, textPaint)
        if (plot.width() <= 0f || plot.height() <= 0f) return
        if (dynamic) {
            rangePaint.color = ColorUtils.setAlphaComponent(if (effectEnabled) dynamicColor else foregroundColor, 0x16)
            val low = packet[1].toDouble().coerceIn(10.0, 500.0)
            val high = packet[2].toDouble().coerceIn(10.0, 500.0)
            if (high > low) canvas.drawRect(frequencyX(low), plot.top, frequencyX(high), plot.bottom, rangePaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        for (db in -24..36 step 12) {
            val y = valueY(db.toDouble())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(if (db > 0) "+$db" else db.toString(), plot.left - 4f * density, y + 3f * density, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("dB", density, plot.top - 3f * density, textPaint)
        ticks.forEachIndexed { index, hz ->
            val x = frequencyX(hz)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            textPaint.textAlign = when (index) { 0 -> Paint.Align.LEFT; ticks.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(if (index == ticks.lastIndex) "500 Hz" else hz.toInt().toString(), x, plot.bottom + 13f * density, textPaint)
        }
        canvas.save(); canvas.clipRect(plot)
        // Component curves remain visible in gray when the selected processing mode does not use them.
        for (index in intArrayOf(1, 2, 3, 0, 4)) {
            if (index == 4 && !dynamic) continue
            linePaint.color = if (active(index)) colors[index] else inactiveColor
            linePaint.pathEffect = if (!active(index) || index == 1 || index == 4) dashed else null
            linePaint.strokeWidth = (if (index == 0 || index == 4) 1.8f else 1.15f) * density
            canvas.drawPath(paths[index], linePaint)
        }
        cursorHz?.let { hz ->
            val index = if (dynamic) 4 else 0
            val x = frequencyX(hz)
            val y = valueY(seriesDb(index, hz)).coerceIn(plot.top, plot.bottom)
            linePaint.color = if (active(index)) colors[index] else inactiveColor
            linePaint.strokeWidth = density
            linePaint.pathEffect = dashed
            canvas.drawLine(x, plot.top, x, plot.bottom, linePaint)
            canvas.drawLine(plot.left, y, plot.right, y, linePaint)
            dotPaint.color = linePaint.color
            canvas.drawCircle(x, y, 3f * density, dotPaint)
        }
        canvas.restore()
        val legendY = plot.bottom + 29f * density
        val count = if (dynamic) 5 else 4
        val cellWidth = width.toFloat() / count
        for (index in 0 until count) {
            val left = cellWidth * index
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = if (active(index)) colors[index] else inactiveColor
            fitted(canvas, labels[index], left + density, legendY, cellWidth - 2f * density, textPaint)
        }
        textPaint.color = ColorUtils.setAlphaComponent(foregroundColor, 0xbe)
        textPaint.textAlign = Paint.Align.LEFT
        val note = when (mode) {
            0 -> "48 kHz 稳态模型；深潜/冲击在此模式未启用"
            1 -> "48 kHz 低通混合参考；未含多相处理，双峰未启用"
            else -> "48 kHz 双峰与 380 Hz 低通混合模型；非实测"
        }
        fitted(canvas, if (cursorLabel.isNotEmpty()) cursorLabel else note, density, height - 24f * density,
            width - 2f * density, textPaint)
        val bottom = if (dynamic) "动态${if (effectEnabled) "已启用" else "未启用"}：${packet[1]}–${packet[2]} Hz · Q ${format(packet[3] / 100.0)} · 最大 ${format(packet[0] / 10.0)} dB（范围中点示意）"
            else "低通 Q ${format(values[2] / 100.0)} · 系数 ${format(factor)} · 长按/横拖查看模型"
        fitted(canvas, bottom, density, height - 8f * density, width - 2f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }

    private fun selectFrequency(x: Float) {
        if (plot.width() <= 0f) return
        val hz = 10.0 * exp(ln(50.0) * ((x - plot.left) / plot.width()).coerceIn(0f, 1f))
        cursorHz = hz
        val value = seriesDb(if (dynamic) 4 else 0, hz)
        cursorLabel = "${if (dynamic) "动态上限示意" else "当前模式模型"} · ${format(hz)} Hz · ${format(value)} dB" +
            if (value < -24.0 || value > 36.0) "（超出图幅）" else ""
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = "静态参数模型，10 至 500 Hz，对数频率，假设采样率 48 kHz。基础模块${modeName()}。" +
            "自然和纯净模式使用低通支路，纯净模式模型不含多相处理；仅低音炮模式使用深潜和冲击峰。" +
            if (dynamic) "动态虚线是跟踪范围中点、最大增益与 Q 的示意，不是实时驱动响应。" else "长按或横拖查看模型数值。"
    }

    private fun modeName() = when (mode) { 1 -> "纯净低音"; 2 -> "低音炮"; else -> "自然低音" }
    private fun frequencyX(hz: Double) = plot.left + plot.width() * (ln(hz / 10.0) / ln(50.0)).toFloat()
    private fun valueY(db: Double) = plot.bottom - plot.height() * ((db + 24.0) / 60.0).toFloat()
    private fun format(value: Double) = if (value.isFinite()) String.format(Locale.ROOT, "%.4g", value) else "—"
    private fun fitted(canvas: Canvas, text: String, x: Float, y: Float, available: Float, paint: Paint) {
        if (available <= 0f) return
        val original = paint.textSize
        val width = paint.measureText(text)
        if (width > available) paint.textSize *= available / width
        canvas.drawText(text, x, y, paint)
        paint.textSize = original
    }

    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
        operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
        operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(value: Double) = Complex(re * value, im * value)
        operator fun div(other: Complex): Complex {
            val magnitude = other.re * other.re + other.im * other.im
            return Complex((re * other.re + im * other.im) / magnitude, (im * other.re - re * other.im) / magnitude)
        }
        fun db() = 10.0 * log10((re * re + im * im).coerceAtLeast(1e-24))
        companion object { val ONE = Complex(1.0, 0.0) }
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double) {
        fun at(hz: Double): Complex {
            val w = 2.0 * PI * hz / RATE
            val numerator = Complex(b0 + b1 * cos(w) + b2 * cos(2.0 * w), -b1 * sin(w) - b2 * sin(2.0 * w))
            val denominator = Complex(a0 + a1 * cos(w) + a2 * cos(2.0 * w), -a1 * sin(w) - a2 * sin(2.0 * w))
            return numerator / denominator
        }
        companion object {
            private const val RATE = 48000.0
            fun lowPass(hz: Double, q: Double): Biquad {
                val w = 2.0 * PI * hz / RATE
                val cosine = cos(w)
                val alpha = sin(w) / (2.0 * q)
                return Biquad((1.0 - cosine) * .5, 1.0 - cosine, (1.0 - cosine) * .5,
                    1.0 + alpha, -2.0 * cosine, 1.0 - alpha)
            }
            fun peak(hz: Double, db: Double, widthOctaves: Double): Biquad {
                val w = 2.0 * PI * hz / RATE
                val sine = sin(w)
                val alpha = sinh(widthOctaves * ln(2.0) * w / (2.0 * sine)) * sine
                val a = 10.0.pow(db / 40.0)
                return Biquad(1.0 + alpha * a, -2.0 * cos(w), 1.0 - alpha * a,
                    1.0 + alpha / a, -2.0 * cos(w), 1.0 - alpha / a)
            }
        }
    }
}

internal fun normalizedDynamicBassPacket(source: IntArray): IntArray {
    val result = BassParameters.dynamic.mapIndexed { index, control ->
        source.getOrNull(index)?.coerceIn(control.min, control.max) ?: control.default
    }.toIntArray()
    if (result[1] >= result[2]) { result[1] = BassParameters.trackLow.default; result[2] = BassParameters.trackHigh.default }
    return result
}
