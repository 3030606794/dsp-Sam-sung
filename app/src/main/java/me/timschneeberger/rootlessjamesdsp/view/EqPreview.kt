package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import kotlin.math.pow

object EqPreview {
    val defaultValue: String get() = (EqualizerSurface.SCALE + DoubleArray(15)).joinToString(";")
    fun parse(value: String): DoubleArray = value.split(';').drop(15).take(15)
        .map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0 }.toDoubleArray().copyOf(15)
    fun encode(levels: DoubleArray): String = (EqualizerSurface.SCALE + levels).joinToString(";")

    fun render(context: Context, chart: PeqResponseView, value: String) {
        val prefs = context.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        val type = prefs.getString(context.getString(R.string.key_eq_filter_type), "0")?.toIntOrNull() ?: 0
        val frequencies = DoubleArray(256) { 20.0 * 1000.0.pow(it / 255.0) }
        val response = FloatArray(256)
        val gains = parse(value)
        // Android release includes this native library; Robolectric does not.
        try {
            if (type == 0) {
                val interpolation = prefs.getString(context.getString(R.string.key_eq_interpolation), "0")?.toIntOrNull() ?: 0
                JdspImpResToolbox.ComputeEqResponse(15, EqualizerSurface.SCALE, gains, interpolation, 256, frequencies, response)
            } else {
                val re = DoubleArray(256)
                val im = DoubleArray(256)
                val order = when(type) { 1 -> 4; 2 -> 6; 3 -> 8; 4 -> 10; else -> 12 }
                JdspImpResToolbox.ComputeIIREqualizerCplx(48000, order, EqualizerSurface.SCALE, gains, 256, frequencies, re, im)
                JdspImpResToolbox.ComputeIIREqualizerResponse(256, re, im, response)
            }
        } catch (missingNative: LinkageError) {
            timber.log.Timber.w(missingNative, "EQ preview native library unavailable")
        }
        chart.setExternalResponse(frequencies, DoubleArray(256) { response[it].toDouble() })
    }
}
