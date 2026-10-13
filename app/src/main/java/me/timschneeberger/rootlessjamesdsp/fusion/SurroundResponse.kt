package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ second-order Butterworth HP + LP, matching the native processing band. */
object SurroundResponse {
    data class Curve(val frequencies: DoubleArray, val decibels: DoubleArray)

    fun calculate(settings: SurroundController.Settings, sampleRate: Double = 48000.0): Curve {
        require(sampleRate.isFinite() && sampleRate > 0)
        val value = settings.sanitized()
        val scale = min(1.0, sampleRate * 0.45 / value.highHz)
        val hp = coefficients(value.lowHz * scale, sampleRate, true)
        val lp = coefficients(value.highHz * scale, sampleRate, false)
        val frequencies = DoubleArray(256) { 20.0 * 1000.0.pow(it / 255.0) }
        val gains = DoubleArray(256) {
            val omega = 2.0 * PI * min(frequencies[it], sampleRate * 0.499) / sampleRate
            10.0 * log10((magnitudeSquared(hp, omega) * magnitudeSquared(lp, omega)).coerceAtLeast(1e-12))
        }
        return Curve(frequencies, gains)
    }

    private fun coefficients(frequency: Double, rate: Double, highPass: Boolean): DoubleArray {
        val omega = 2.0 * PI * frequency / rate
        val c = cos(omega)
        val alpha = sin(omega) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = (if (highPass) 1.0 + c else 1.0 - c) / 2.0
        val b1 = if (highPass) -(1.0 + c) else 1.0 - c
        return doubleArrayOf(b0 / a0, b1 / a0, b0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
    }

    private fun magnitudeSquared(c: DoubleArray, omega: Double): Double {
        val nr = c[0] + c[1] * cos(omega) + c[2] * cos(2.0 * omega)
        val ni = -c[1] * sin(omega) - c[2] * sin(2.0 * omega)
        val dr = 1.0 + c[3] * cos(omega) + c[4] * cos(2.0 * omega)
        val di = -c[3] * sin(omega) - c[4] * sin(2.0 * omega)
        return (nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-24)
    }
}
