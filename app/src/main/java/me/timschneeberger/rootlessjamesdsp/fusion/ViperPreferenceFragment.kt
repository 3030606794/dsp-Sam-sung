package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Stored in dsp_* so the existing profile/preset system includes every ViPER control. */
class ViperPreferenceFragment : PreferenceFragmentCompat() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                (parentFragment as? DspFragment)?.restartFragment(id, ViperPreferenceFragment())
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceManager.sharedPreferencesName = ViperParameters.PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        // Child preferences have no manager until attached to the screen.
        // Capture the fragment's store before entering any Preference.apply block.
        val storedPreferences = requireNotNull(preferenceManager.sharedPreferences)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        ViperParameters.groups.forEach { group ->
            val category = PreferenceCategory(ctx).apply { title = group.title }
            preferenceScreen.addPreference(category)
            val details = mutableListOf<Preference>()
            val toggle = group.enableParameter?.let {
                SwitchPreferenceCompat(ctx).apply {
                    key = group.key
                    title = "启用${group.title}"
                    setDefaultValue(false)
                    category.addPreference(this)
                    setOnPreferenceChangeListener { _, new ->
                        details.forEach { it.isVisible = new as Boolean }
                        true
                    }
                }
            }
            if (group.summary.isNotBlank()) {
                Preference(ctx).apply {
                    summary = group.summary
                    isSelectable = false
                    category.addPreference(this)
                    details.add(this)
                }
            }
            group.controls.forEach { control ->
                val pref: Preference = if (control.choices.isNotEmpty()) {
                    // Int-backed seekbar avoids mixing String and Int in old profiles.
                    SeekBarPreference(ctx).apply {
                        max = control.max
                        min = control.min
                        showSeekBarValue = false
                    }
                } else SeekBarPreference(ctx).apply {
                    max = control.max
                    min = control.min
                    showSeekBarValue = true
                }
                pref.apply {
                    key = control.key
                    title = control.title
                    setDefaultValue(control.default)
                    val value = (storedPreferences.all[control.key] as? Number)?.toInt()
                        ?: control.default
                    summary = control.label(value)
                    setOnPreferenceChangeListener { p, new ->
                        p.summary = control.label(new as Int)
                        true
                    }
                }
                (pref as SeekBarPreference).updatesContinuously = false
                category.addPreference(pref)
                details.add(pref)
            }
            if (group.key == "v_dynamic") {
                ListPreference(ctx).apply {
                    key = "v_dynamic_preset"
                    title = "设备响应"
                    entries = ViperParameters.dynamicPresets.keys.toTypedArray()
                    entryValues = ViperParameters.dynamicPresets.keys.indices.map { it.toString() }.toTypedArray()
                    setDefaultValue("0")
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    category.addPreference(this)
                    details.add(this)
                }
            }
            if (toggle != null) details.forEach { it.isVisible = toggle.isChecked }
        }
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        ctx.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED)
        })
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply {
            isNestedScrollingEnabled = false
            itemAnimator = null
        }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        requireContext().unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
