package me.timschneeberger.rootlessjamesdsp.peq

import java.math.BigDecimal
import java.math.RoundingMode

/** Keeps the exact Q behind formatted text, without treating genuine BW edits as unchanged. */
class PeqQualityEditor(q: Double, frequency: Double, initialMode: PeqQualityDisplay) {
    var mode: PeqQualityDisplay = initialMode
        private set
    private var canonicalQ = q
    var renderedText = formatInput(displayValue(q, frequency, initialMode))
        private set

    fun readQ(text: String, frequency: Double, qMaximum: Double = PeqModel.MAX_Q): Double? {
        if (text == renderedText) {
            return canonicalQ
        }
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (mode == PeqQualityDisplay.Q) value else runCatching {
            if (value !in PeqModel.MIN_BW..PeqModel.MAX_BW) return@runCatching Double.NaN
            PeqResponse.qFromBandwidth(value, frequency)
        }.getOrNull()?.takeIf { it.isFinite() }
    }

    fun switchMode(next: PeqQualityDisplay, text: String, frequency: Double,
                   qMaximum: Double = PeqModel.MAX_Q): String? {
        val q = readQ(text, frequency, qMaximum)?.takeIf {
            it.isFinite() && it in PeqModel.MIN_Q..PeqModel.MAX_Q &&
                (mode == PeqQualityDisplay.BW || it <= qMaximum || text == renderedText)
        }
            ?: return null
        val displayed = runCatching { displayValue(q, frequency, next) }.getOrNull() ?: return null
        canonicalQ = q
        mode = next
        renderedText = formatInput(displayed)
        return renderedText
    }

    private fun displayValue(q: Double, frequency: Double, mode: PeqQualityDisplay): Double =
        if (mode == PeqQualityDisplay.Q) q else PeqResponse.bandwidthOctaves(q, frequency)

    companion object {
        /** Display only: absorb sub-picounit conversion noise before decimal HALF_UP. */
        fun format(value: Double): String = BigDecimal.valueOf(value)
            .setScale(12, RoundingMode.HALF_UP).setScale(3, RoundingMode.HALF_UP).toPlainString()

        /** Editors retain the decimal form of the complete saved double, including Q=0.0001. */
        fun formatInput(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

        /** Decimal button increments, without binary 0.1+0.2 accumulation or display round-trips. */
        fun step(value: Double, increment: Double): Double = BigDecimal.valueOf(value)
            .add(BigDecimal.valueOf(increment)).toDouble()
    }
}
