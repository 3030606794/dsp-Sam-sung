package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyParameters as P
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A settled filter model and a fixed test-tone Chebyshev model, never a measured music spectrum. */
class SpectrumParameterPreview(context: Context) : InspectablePlotView(context) {
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var hp = 7600.0
    private var lp = 22000.0
    private var hpQ = .717
    private var lpQ = .717
    private var amount = .56
    private var outputDb = 0.0
    private var driveDb = 0.0
    private var even = 0.0
    private var highOrder = 1.0
    private var factor = 1
    private var automatic = true
    private var bars = DoubleArray(10)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sampleSine = DoubleArray(512) { sin(2 * PI * it / 512) }
    private val realBasis = Array(10) { h -> DoubleArray(512) { cos(2 * PI * (h + 1) * it / 512) } }
    private val imagBasis = Array(10) { h -> DoubleArray(512) { sin(2 * PI * (h + 1) * it / 512) } }

    fun setConfiguration(v: Map<String, *>, sampleRate: Int) {
        val nextRate = sampleRate.coerceIn(8000, 768000)
        if (values == v && rate == nextRate) return
        cancelInspection(); values = v.toMap(); rate = nextRate
        fun p(control: me.timschneeberger.rootlessjamesdsp.fusion.BassParameters.Control) = P.value(v, control).toDouble() / control.divisor
        fun a(control: me.timschneeberger.rootlessjamesdsp.fusion.BassParameters.Control) = A.value(v, control).toDouble() / control.divisor
        hp = p(P.spectrumFrequency).coerceIn(1.0, rate * .5 - 100)
        automatic = P.automaticLowpass(v)
        lp = if (automatic) rate * .5 - 2000 else p(P.lowpassFrequency).coerceIn(1.0, rate * .5 - 100)
        hpQ = p(P.highpassQ); lpQ = p(P.lowpassQ)
        amount = P.value(v, P.spectrumAmount) / 1000.0
        outputDb = p(P.spectrumOutput); driveDb = a(A.drive)
        even = a(A.evenMix) / 100; highOrder = a(A.highOrder) / 100; factor = A.oversampling(v)
        bars = harmonicModel()
        contentDescription = "激励支路参数模型，输入高通 ${hp.toInt()} Hz，输出低通 ${lp.toInt()} Hz；参考单音泛音图，长按查看"
        invalidate()
    }

    private fun magnitude(hz: Double, cutoff: Double, q: Double, highpass: Boolean): Double {
        val w = 2 * PI * cutoff / rate; val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val b0 = if (highpass) (1 + c) / 2 else (1 - c) / 2
        val b1 = if (highpass) -(1 + c) else 1 - c
        val b2 = b0
        val a0 = 1 + alpha; val a1 = -2 * c; val a2 = 1 - alpha
        val z = 2 * PI * hz.coerceIn(0.0, rate * .5) / rate
        val re = b0 + b1 * cos(z) + b2 * cos(2 * z)
        val im = -b1 * sin(z) - b2 * sin(2 * z)
        return hypot(re, im) / hypot(a0 + a1 * cos(z) + a2 * cos(2 * z), -a1 * sin(z) - a2 * sin(2 * z)).coerceAtLeast(1e-15)
    }
    private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-8))
    private fun response(hz: Double): Double = if (hz >= rate * .5) -120.0 else
        db(magnitude(hz, hp, hpQ, true) * magnitude(hz, lp, lpQ, false) * amount) + outputDb

    /** The driver's T1..T10 weights and bounded input shoulder, evaluated for a -12 dBFS sine. */
    private fun harmonicModel(): DoubleArray {
        val drive = 10.0.pow(driveDb / 20)
        val legacy = driveDb == 0.0 && even == 0.0 && highOrder == 1.0 && factor == 1
        val waveform = DoubleArray(512) { i ->
            val dry = sampleSine[i] * 10.0.pow(-12.0 / 20)
            val x = if (legacy) dry else (dry * drive / (1 + max(0.0, drive - 1) * abs(dry))).coerceIn(-1.0, 1.0)
            var previous = 1.0; var current = x; var out = 0.0
            for (order in 1..10) {
                val weight = .02 * (if (order > 2) highOrder else 1.0) * (if (order % 2 == 0) even else 1.0)
                out += weight * current
                val next = 2 * x * current - previous; previous = current; current = next
            }
            out * amount * 10.0.pow(outputDb / 20)
        }
        return DoubleArray(10) { h ->
            var real = 0.0; var imag = 0.0
            for (i in waveform.indices) { real += waveform[i] * realBasis[h][i]; imag += waveform[i] * imagBasis[h][i] }
            hypot(real, imag) * 2 / waveform.size
        }
    }

    override fun drawPlots(c: Canvas) {
        val half = width * .53f
        val work = RectF(26 * d, 35 * d, half - 10 * d, height - 30 * d)
        val harmonic = RectF(half + 25 * d, 35 * d, width - 3 * d, height - 30 * d)
        panes.add(work); panes.add(harmonic)
        title(c, "激励带通工作区 · dB", 0f, 11 * d)
        title(c, "Chebyshev 泛音 · dB", half, 11 * d)
        title(c, "Q ${number(hpQ, 3)} / ${number(lpQ, 3)} · ${number(amount * 100)}%", 0f, 24 * d)
        title(c, "单音峰值 −12 dBFS", half, 24 * d)
        axes(c, work, listOf(1000.0, 5000.0, 22000.0).map { logUnit(it, 1000.0, 22000.0) to "${hz(it)}${if (it == 22000.0) " Hz" else ""}" }, -60.0, 18.0)
        axes(c, harmonic, listOf(0f to "f₀", .5f to "6f₀", 1f to "10f₀"), -80.0, 0.0)
        band(c, work, logUnit(hp, 1000.0, 22000.0), logUnit(max(hp, lp), 1000.0, 22000.0), primary)
        curve(c, work, primary, -60.0, 18.0, shade = true, baseline = -60.0) { response(logValue(it, 1000.0, 22000.0)) }
        vertical(c, work, logUnit(lp, 1000.0, 22000.0), orange)
        for (h in bars.indices) {
            val x = harmonic.left + (h + .5f) / 10 * harmonic.width()
            val top = y(harmonic, db(bars[h]), -80.0, 0.0)
            barPaint.color = ColorUtils.setAlphaComponent(if (h % 2 == 1) orange else primary, 170)
            c.drawRect(x - harmonic.width() / 28, top, x + harmonic.width() / 28, harmonic.bottom, barPaint)
        }
        title(c, "${if (automatic) "自动" else "手动"}截止 ${number(lp, 0)} Hz", 0f, height - 3 * d)
        title(c, "紫奇 / 橙偶 · 驱动 ${number(driveDb)} dB", half, height - 3 * d)
    }

    override fun drawInspection(c: Canvas, pane: Int, unit: Float) {
        val r = panes[pane]
        val lines = if (pane == 0) {
            val f = logValue(unit, 1000.0, 22000.0)
            vertical(c, r, unit, primary); dot(c, r, unit, response(f), -60.0, 18.0, primary)
            listOf("${number(f, 1)} Hz · 支路模型 ${number(response(f), 2)} dB", "HP ${number(hp, 0)} / LP ${number(lp, 0)} Hz")
        } else {
            val index = (unit * 10).toInt().coerceIn(0, 9)
            vertical(c, r, (index + .5f) / 10, primary)
            listOf("第 ${index + 1} 阶 · ${if (index % 2 == 0) "奇次" else "偶次"}泛音", "模型幅度 ${number(bars[index], 6)} · ${number(db(bars[index]), 2)} dB")
        }
        tooltip(c, r, lines)
    }
}
