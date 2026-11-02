package me.timschneeberger.rootlessjamesdsp.utils.preferences

import androidx.preference.PreferenceDataStore

class NonPersistentDatastore : PreferenceDataStore() {
    private var preferenceChangedListener: OnPreferenceChanged? = null
    private val floats = HashMap<String, Float>()
    private val strings = HashMap<String, String?>()

    /** Populate a control before attaching it, without publishing an edit or touching preferences. */
    fun seedFloat(key: String, value: Float) {
        floats[key] = value
    }

    fun seedString(key: String, value: String?) {
        strings[key] = value
    }

    fun clear() {
        floats.clear()
        strings.clear()
    }

    override fun getFloat(key: String?, defValue: Float): Float = floats[key] ?: defValue

    override fun getString(key: String?, defValue: String?): String? =
        if (strings.containsKey(key)) strings[key] else defValue

    override fun putFloat(key: String?, value: Float) {
        key ?: return
        if (!value.isFinite() || floats[key] == value) return
        floats[key] = value
        this.preferenceChangedListener?.onFloatPreferenceChanged(key, value)
    }

    override fun putString(key: String?, value: String?) {
        key ?: return
        strings[key] = value
    }

    fun setOnPreferenceChanged(preferenceChangedListener: OnPreferenceChanged) {
        this.preferenceChangedListener = preferenceChangedListener
    }

    interface OnPreferenceChanged {
        fun onFloatPreferenceChanged(key: String, value: Float)
    }
}
