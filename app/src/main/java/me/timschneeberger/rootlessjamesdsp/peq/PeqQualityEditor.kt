package me.timschneeberger.rootlessjamesdsp.peq

import java.math.BigDecimal
import java.math.MathContext

/** Keeps the exact Q behind formatted text, without treating genuine BW edits as unchanged. */
class PeqQualityEditor(q: Double, frequency: Double, initialMode: PeqQualityDisplay) {
    var mode: PeqQualityDisplay = initialMode
        private set
    private var canonicalQ = q
    private var renderedFrequency = frequency
    var renderedText = format(displayValue(q, frequency, initialMode))
        private set

    fun readQ(text: String, frequency: Double): Double? {
        if (text == renderedText && (mode == PeqQualityDisplay.Q || frequency == renderedFrequency)) {
            return canonicalQ
        }
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (mode == PeqQualityDisplay.Q) value else runCatching {
            PeqResponse.qFromBandwidth(value, frequency)
        }.getOrNull()
    }

    fun switchMode(next: PeqQualityDisplay, text: String, frequency: Double): String? {
        val q = readQ(text, frequency)?.takeIf { it.isFinite() && it in PeqModel.MIN_Q..PeqModel.MAX_Q }
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
        private fun format(value: Double): String = BigDecimal.valueOf(value)
            .round(MathContext(12)).stripTrailingZeros().toPlainString()
    }
}
