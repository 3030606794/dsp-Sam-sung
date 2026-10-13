package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context

/** Local editor presentation, deliberately separate from sound parameters and portable presets. */
data class PeqEditorSettings(
    val confirmDelete: Boolean = false,
    val markerDurationSeconds: Int = 2,
    val markerTextSizeSp: Int = 9,
    val showMarkerFrequency: Boolean = true,
    val showMarkerGain: Boolean = true,
    val showMarkerQuality: Boolean = true,
    val previewDisabledBands: Boolean = true,
    val showMarkerFilterType: Boolean = true
) {
    fun validate(): PeqEditorSettings {
        require(markerDurationSeconds == 0 || markerDurationSeconds in 2..10)
        require(markerTextSizeSp in 8..20)
        return this
    }
}

class PeqEditorPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): PeqEditorSettings {
        val values = preferences.all
        fun boolean(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
        val duration = (values["markerDurationSeconds"] as? Int)?.takeIf { it == 0 || it in 2..10 } ?: 2
        val size = (values["markerTextSizeSp"] as? Int)?.takeIf { it in 8..20 } ?: 9
        return PeqEditorSettings(
            confirmDelete = boolean("confirmDelete", false),
            markerDurationSeconds = duration,
            markerTextSizeSp = size,
            showMarkerFrequency = boolean("showMarkerFrequency", true),
            showMarkerGain = boolean("showMarkerGain", true),
            showMarkerQuality = boolean("showMarkerQuality", true),
            previewDisabledBands = boolean("previewDisabledBands", true),
            showMarkerFilterType = boolean("showMarkerFilterType", true))
    }

    fun save(settings: PeqEditorSettings): Boolean {
        settings.validate()
        return preferences.edit()
            .putBoolean("confirmDelete", settings.confirmDelete)
            .putInt("markerDurationSeconds", settings.markerDurationSeconds)
            .putInt("markerTextSizeSp", settings.markerTextSizeSp)
            .putBoolean("showMarkerFrequency", settings.showMarkerFrequency)
            .putBoolean("showMarkerGain", settings.showMarkerGain)
            .putBoolean("showMarkerQuality", settings.showMarkerQuality)
            .putBoolean("previewDisabledBands", settings.previewDisabledBands)
            .putBoolean("showMarkerFilterType", settings.showMarkerFilterType).commit()
    }

    companion object { const val PREFERENCES = "peq_editor_display" }
}
