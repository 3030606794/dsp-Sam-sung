package me.timschneeberger.rootlessjamesdsp.preference

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference

/** Refresh presentation from storage without invoking a user edit or writing it back. */
interface PreferenceValueSync {
    fun syncFromPreferences()
    fun finishInteraction() = Unit
    /** Release transient row state without notifying the adapter during a layout pass. */
    fun releaseBoundView() = Unit
}

internal class PreferenceValueObserver(
    private val preference: Preference,
    private val refresh: () -> Unit,
    private val extraKeys: Set<String> = emptySet(),
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private var observed: SharedPreferences? = null

    fun attach() {
        val next = preference.sharedPreferences.takeIf { preference.isPersistent && preference.preferenceDataStore == null }
        if (observed !== next) {
            detach()
            observed = next
            next?.registerOnSharedPreferenceChangeListener(this)
        }
        refresh()
    }

    fun detach() {
        observed?.unregisterOnSharedPreferenceChangeListener(this)
        observed = null
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (key == null || key == preference.key || key in extraKeys) refresh()
    }
}

object PreferenceValueSynchronizer {
    fun refresh(group: PreferenceGroup, key: String? = null, finishInteractions: Boolean = false) {
        val values by lazy { group.sharedPreferences?.all.orEmpty() }
        fun update(preference: Preference) {
            if (key == null || key == preference.key) {
                when (preference) {
                    is PreferenceValueSync -> {
                        if (finishInteractions) preference.finishInteraction()
                        preference.syncFromPreferences()
                    }
                    is SwitchPreferenceGroup -> preference.syncFromPreferences()
                    is EqualizerPreference -> preference.updateFromPreferences()
                    is CompanderPreference -> preference.updateFromPreferences()
                    else -> if (preference.isPersistent && preference.key != null && preference.preferenceDataStore == null) {
                        val persisted = preference.isPersistent
                        preference.isPersistent = false
                        try {
                            when (preference) {
                                is TwoStatePreference -> (values[preference.key] as? Boolean)?.let {
                                    if (preference.isChecked != it) preference.isChecked = it
                                }
                                is ListPreference -> (values[preference.key] as? String)?.let {
                                    if (preference.value != it) preference.value = it
                                }
                                is EditTextPreference -> (values[preference.key] as? String)?.let {
                                    if (preference.text != it) preference.text = it
                                }
                                is MultiSelectListPreference -> (values[preference.key] as? Set<*>)
                                    ?.filterIsInstance<String>()?.toSet()?.let {
                                        if (preference.values != it) preference.values = it
                                    }
                            }
                        } finally { preference.isPersistent = persisted }
                    }
                }
            }
            if (preference is PreferenceGroup) repeat(preference.preferenceCount) {
                update(preference.getPreference(it))
            }
        }
        if (key == null) update(group) else group.findPreference<Preference>(key)?.let(::update)
    }
}
