package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.min

/** An immutable display target. Existing response mathematics run only in the visible chart worker. */
class SurroundDashboardModel private constructor(
    val settings: SurroundController.Settings,
    val rate: Int,
    val actualRate: Boolean,
    val effectEnabled: Boolean,
    val presetSlot: Int
) {
    private val p = SurroundProfessionalParameters
    val professional = p.on(settings, "enabled")
    val responseView = p.value(settings, "response_view")
    val diagnosticView = p.value(settings, "diagnostics_view")
    val diagnosticsEnabled = p.on(settings, "diagnostics_on")
    val impulseWindowMs = p.value(settings, "impulse_window")
    val metersEnabled = p.on(settings, "meters")
    val rmsEnabled = professional && p.on(settings, "rms_on")
    val peakEnabled = professional && p.on(settings, "peak_on")
    val hpSlopeDb = if (professional) 6 * (1 shl p.value(settings, "hp_slope")) else 12
    val lpSlopeDb = if (professional) 6 * (1 shl p.value(settings, "lp_slope")) else 12
    private val scale = min(1.0, rate * .45 / settings.highHz)
    val lowHz = settings.lowHz * scale
    val highHz = settings.highHz * scale
    val linked = !professional || p.on(settings, "link")
    val lrDelayUs = if (professional) p.value(settings, "lr_delay") / 10.0 else settings.delayUs.toDouble()
    val rlDelayUs = if (professional && !linked) p.value(settings, "rl_delay") / 10.0 else lrDelayUs
    val speakerSpacingMm = p.value(settings, "speaker_spacing").toDouble()
    val listeningDistanceMm = p.value(settings, "listening_distance").toDouble()
    val earSpacingMm = p.value(settings, "ear_spacing").toDouble()
    val geometry = p.geometry(settings)
    val peakCeilingDb = p.value(settings, "peak_ceiling") / 10.0
    private val linearSettings = settings.copy(professional = settings.professional.filterKeys { it in LINEAR_KEYS })
    private val linearKey = LinearKey(linearSettings, rate)
    val maximumFrequencyGainDb: Double? get() = synchronized(cacheLock) { gainCache[linearKey] }
    val staticStatusLines: List<String> get() {
        val rateText = if (actualRate) "实际内部 $rate Hz" else "参考 $rate Hz"
        val mode = if (professional) "专业抵消与混合" else "基础抵消"
        val maximum = maximumFrequencyGainDb?.let { "频率增益估计 ${format(it, 2)} dB" } ?: "频率增益估计待计算"
        return listOf("预设 ${presetSlot + 1} · $rateText · $mode${if (effectEnabled) "" else " · 已关闭"}",
            "频段 ${format(lowHz, 0)}–${format(highHz, 0)} Hz · 边缘 $hpSlopeDb / $lpSlopeDb dB/oct",
            "$maximum · 非真峰值上界")
    }

    fun cachedFrequency(): SurroundProfessionalResponse.Curve? = synchronized(cacheLock) {
        frequencyCache[CurveKey(linearKey, responseView, 0)]
    }
    fun frequency(cancel: () -> Unit): SurroundProfessionalResponse.Curve {
        cachedFrequency()?.let { return it }
        cancel()
        val curve = if (responseView == 0) {
            val band = SurroundResponse.calculate(linearSettings, rate.toDouble())
            SurroundProfessionalResponse.Curve(band.frequencies, listOf("抵消处理频段"), listOf(band.decibels))
        } else SurroundProfessionalResponse.calculate(linearSettings, rate.toDouble(), responseView)
        cancel()
        synchronized(cacheLock) {
            frequencyCache[CurveKey(linearKey, responseView, 0)] = curve
            trim(frequencyCache, 24)
        }
        return curve
    }
    fun cachedDiagnostic(): SurroundDiagnostics.Result? = synchronized(cacheLock) {
        diagnosticCache[CurveKey(linearKey, diagnosticView, if (diagnosticView == 2) impulseWindowMs else 0)]
    }
    fun diagnostic(cancel: () -> Unit): SurroundDiagnostics.Result {
        cachedDiagnostic()?.let { return it }
        cancel()
        val result = SurroundDiagnostics.calculate(linearSettings, rate.toDouble(), diagnosticView, impulseWindowMs, cancel)
        cancel()
        synchronized(cacheLock) {
            diagnosticCache[CurveKey(linearKey, diagnosticView, if (diagnosticView == 2) impulseWindowMs else 0)] = result
            gainCache[linearKey] = result.gainDb
            trim(diagnosticCache, 3)
            trim(gainCache, 24)
        }
        return result
    }

    companion object {
        private val LINEAR_KEYS = setOf("enabled", "link", "lr_attenuation", "rl_attenuation", "lr_delay", "rl_delay",
            "hp_slope", "lp_slope", "mix", "pre_db", "output_db")
        private data class LinearKey(val settings: SurroundController.Settings, val rate: Int)
        private data class CurveKey(val linear: LinearKey, val view: Int, val window: Int)
        private val cacheLock = Any()
        private val frequencyCache = LinkedHashMap<CurveKey, SurroundProfessionalResponse.Curve>(24, .75f, true)
        private val diagnosticCache = LinkedHashMap<CurveKey, SurroundDiagnostics.Result>(3, .75f, true)
        private val gainCache = LinkedHashMap<LinearKey, Double>(24, .75f, true)
        private fun <K, V> trim(cache: LinkedHashMap<K, V>, limit: Int) {
            while (cache.size > limit) cache.remove(cache.keys.first())
        }
        private fun format(value: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", value)
        fun from(settings: SurroundController.Settings, rate: Int = 48000, actualRate: Boolean = false,
            effectEnabled: Boolean, presetSlot: Int): SurroundDashboardModel =
            SurroundDashboardModel(settings.sanitized().let { it.copy(professional = it.professional.toMap()) },
                rate.takeIf { it in 8000..768000 } ?: 48000, actualRate && rate in 8000..768000,
                effectEnabled, presetSlot.coerceIn(0, 7))
    }
}
