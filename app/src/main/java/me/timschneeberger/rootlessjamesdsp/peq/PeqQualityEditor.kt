package me.timschneeberger.rootlessjamesdsp.peq

import java.math.BigDecimal
import java.math.RoundingMode

/** Keeps the exact Q behind formatted text, without treating genuine BW edits as unchanged. */
class PeqQualityEditor(q: Double, frequency: Double, initialMode: PeqQualityDisplay) {
    var mode: PeqQualityDisplay = initialMode
        private set
    private var canonicalQ = q
    private var renderedFrequency = frequency
    var renderedText = format(displayValue(q, frequency, initialMode))
        private set

    fun readQ(text: String, frequency: Double, qMaximum: Double = PeqModel.MAX_Q): Double? {
        if (text == renderedText) {
            if (mode == PeqQualityDisplay.Q || frequency == renderedFrequency) return canonicalQ
            // A frequency edit keeps the full BW, not the rounded three-decimal label.
            return runCatching {
                PeqResponse.qFromBandwidth(PeqResponse.bandwidthOctaves(canonicalQ, renderedFrequency), frequency)
            }.getOrNull()
        }
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (mode == PeqQualityDisplay.Q) value else runCatching {
            // The displayed three-decimal endpoints are aliases for their exact Q limits.
            // Only exact displayed values qualify; genuine finer edits retain their meaning.
            if (value == format(PeqResponse.bandwidthOctaves(qMaximum, frequency)).toDouble())
                return@runCatching qMaximum
            if (value == format(PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, frequency)).toDouble())
                return@runCatching PeqModel.MIN_Q
            PeqResponse.qFromBandwidth(value, frequency)
        }.getOrNull()
    }

    fun switchMode(next: PeqQualityDisplay, text: String, frequency: Double,
                   qMaximum: Double = PeqModel.MAX_Q): String? {
        val q = readQ(text, frequency, qMaximum)?.takeIf { it.isFinite() && it in PeqModel.MIN_Q..PeqModel.MAX_Q }
            ?: return null
        val displayed = runCatching { displayValue(q, frequency, next) }.getOrNull() ?: return null
        canonicalQ = q
        renderedFrequency = frequency
        mode = next
        renderedText = format(displayed)
        return renderedText
    }

    private fun displayValue(q: Double, frequency: Double, mode: PeqQualityDisplay): Double =
        if (mode == PeqQualityDisplay.Q) q else PeqResponse.bandwidthOctaves(q, frequency)

    companion object {
        fun format(value: Double): String = BigDecimal.valueOf(value)
            .setScale(3, RoundingMode.HALF_UP).toPlainString()
    }
}
