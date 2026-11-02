package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import kotlinx.serialization.json.*

/** Actual imported values, after adapting source frequencies to the driver's supported range. */
data class PeqSystemPresetOrigin(
    val frequency: Double,
    val gain: Double,
    val q: Double,
    val qualityDisplay: PeqQualityDisplay
) {
    fun qualityValue(display: PeqQualityDisplay): Double = if (display == PeqQualityDisplay.BW)
        PeqResponse.bandwidthOctaves(q, frequency) else q

    fun restoreQuality(band: PeqBand): PeqBand {
        val restoredQ = if (band.qualityDisplay == PeqQualityDisplay.BW && band.frequency != frequency)
            PeqResponse.qFromBandwidth(qualityValue(PeqQualityDisplay.BW), band.frequency)
        else q
        return band.copy(q = restoredQ.coerceIn(PeqModel.MIN_Q, PeqModel.MAX_Q))
    }
}

/** Local reset baselines follow imported card IDs, never matching cards by their parameter values. */
class PeqSystemPresetOrigins(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun find(id: String): PeqSystemPresetOrigin? = try {
        preferences.getString(id, null)?.let { encoded ->
            val value = Json.parseToJsonElement(encoded).jsonObject
            require(value.getValue("version").jsonPrimitive.int == VERSION)
            val band = PeqBand(id = id, frequency = value.getValue("frequency").jsonPrimitive.double,
                gain = value.getValue("gain").jsonPrimitive.double, q = value.getValue("q").jsonPrimitive.double,
                qualityDisplay = PeqQualityDisplay.valueOf(value.getValue("qualityDisplay").jsonPrimitive.content))
            validateImportedBand(band)
            PeqSystemPresetOrigin(band.frequency, band.gain, band.q, band.qualityDisplay)
        }
    } catch (_: RuntimeException) {
        // Corrupt or newer metadata cannot supply a valid reset target. Keep the saved bank untouched.
        null
    }

    /** Call only after the new system bank has been successfully saved and marked new. */
    fun recordImportedBands(bands: List<PeqBand>): Boolean {
        require(bands.size <= PeqModel.MAX_BANDS)
        require(bands.map { it.id }.toSet().size == bands.size)
        bands.forEach(::validateImportedBand)
        val editor = preferences.edit().clear()
        bands.forEach { band ->
            editor.putString(band.id, buildJsonObject {
                put("version", VERSION)
                put("frequency", band.frequency)
                put("gain", band.gain)
                put("q", band.q)
                put("qualityDisplay", band.qualityDisplay.name)
            }.toString())
        }
        return editor.commit()
    }

    /** Remove deleted/replaced identities without changing any surviving card's original values. */
    fun retain(ids: Set<String>): Boolean {
        val removed = preferences.all.keys.filterNot { it in ids }
        if (removed.isEmpty()) return true
        return preferences.edit().apply { removed.forEach(::remove) }.commit()
    }

    private fun validateImportedBand(band: PeqBand) {
        PeqModel.validateBand(band)
        require(band.frequency >= PeqModel.MIN_FREQUENCY)
    }

    companion object {
        const val PREFERENCES = "peq_system_preset_origins"
        private const val VERSION = 1
    }
}
