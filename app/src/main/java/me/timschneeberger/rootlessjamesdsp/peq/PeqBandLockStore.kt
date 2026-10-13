package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context

/** Editor protection is local UI state, independent of presets and the audio protocol. */
class PeqBandLockStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isBandLocked(id: String) = preferences.getBoolean("$id.band", true)
    fun isFieldLocked(id: String, field: PeqEditField) = preferences.getBoolean("$id.${field.name}", false)
    fun canEdit(id: String, field: PeqEditField? = null) =
        !isBandLocked(id) && (field == null || !isFieldLocked(id, field))

    fun isNew(id: String) = preferences.getBoolean("$id.new", false)

    /** A new or copied card is immediately adjustable, independent of the source card's locks. */
    fun markNew(id: String) {
        preferences.edit().putBoolean("$id.band", false).putBoolean("$id.new", true).apply {
            PeqEditField.entries.forEach { putBoolean("$id.${it.name}", false) }
        }.apply()
    }

    fun markUsed(id: String) {
        if (isNew(id)) preferences.edit().putBoolean("$id.new", false).apply()
    }

    fun setBandLocked(id: String, locked: Boolean) {
        preferences.edit().putBoolean("$id.band", locked).apply()
    }

    fun setFieldLocked(id: String, field: PeqEditField, locked: Boolean) {
        preferences.edit().putBoolean("$id.${field.name}", locked).apply()
    }

    companion object { const val PREFERENCES = "peq_editor_locks" }
}
