package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context

/** Editor protection is local UI state, independent of presets and the audio protocol. */
class PeqBandLockStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isBandLocked(id: String) = preferences.getBoolean("$id.band", true)
    fun isFieldLocked(id: String, field: PeqEditField) = preferences.getBoolean("$id.${field.name}", false)
    fun canEdit(id: String, field: PeqEditField? = null) =
        !isBandLocked(id) && (field == null || !isFieldLocked(id, field))

    fun setBandLocked(id: String, locked: Boolean) {
        preferences.edit().putBoolean("$id.band", locked).apply()
    }

    fun setFieldLocked(id: String, field: PeqEditField, locked: Boolean) {
        preferences.edit().putBoolean("$id.${field.name}", locked).apply()
    }

    companion object { const val PREFERENCES = "peq_editor_locks" }
}
