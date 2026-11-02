package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Professional controls keep a stable layout and gray out the inactive processing mode. */
class ViperBassEditor(private val context: Context, private val group: SwitchPreferenceGroup,
    private val onDescriptionChanged: () -> Unit = {}) {
    private var preferences = BassController.preferences(context, true)
    private val status = BassController.statusPreferences(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private val parameterKeys = BassParameters.viper.map { it.key }.toSet()
    private var closed = false
    private var previousMode: Int? = null
    private var previousSupport: Boolean? = null
    private val help = Preference(context).apply { key = "bass_viper_help"; isSelectable = false; isIconSpaceReserved = false; order = 90 }
    private val driver = Preference(context).apply {
        key = "bass_viper_driver"; setTitle(R.string.bass_driver_required)
        isSelectable = false; isIconSpaceReserved = false; order = 100
        if (group.isProfessionalMode) layoutResource = R.layout.preference_liveprog_compact_row
    }
    private val renderFrame = PreferenceFrameUpdate { full ->
        if (!closed) {
            if (full || currentMode() != previousMode || BassController.supported(context) != previousSupport) render()
            else PreferenceValueSynchronizer.refresh(group)
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null || key == "v_bass_mode" || key == "capabilities" || key == "session" -> renderFrame.request()
            key in parameterKeys || key == BassParameters.LEGACY_AMOUNT || key == "v_bass" -> renderFrame.request(false)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) refreshFromPreferences()
        }
    }

    init {
        BassParameters.migrate(preferences, true)
        BassParameters.viper.forEach { control ->
            rows[control.key] = BassNumericPreference(context, control, BassParameters.value(preferences.all, control))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        render()
    }

    private fun currentMode(): Int = (preferences.all["v_bass_mode"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0

    private fun render() {
        if (closed) return
        val mode = currentMode()
        val supported = BassController.supported(context)
        previousMode = mode
        previousSupport = supported
        val controls = if (mode == 2) listOf(BassParameters.amount,
            BassParameters.deepFrequency, BassParameters.deepGain, BassParameters.deepWidth,
            BassParameters.punchFrequency, BassParameters.punchGain, BassParameters.punchWidth)
        else listOf(BassParameters.frequency, BassParameters.amount, BassParameters.naturalQ)
        val visibleKeys = controls.map { it.key }.toSet()
        val displayed = if (group.isProfessionalMode) listOf(BassParameters.frequency,
            BassParameters.amount, BassParameters.naturalQ, BassParameters.deepFrequency,
            BassParameters.deepGain, BassParameters.deepWidth, BassParameters.punchFrequency,
            BassParameters.punchGain, BassParameters.punchWidth) else controls
        if (!group.isProfessionalMode)
            rows.filterKeys { it !in visibleKeys }.values.forEach { group.removePreference(it) }
        displayed.forEachIndexed { index, definition ->
            val row = rows.getValue(definition.key)
            row.order = (index + 1) * 10
            row.isEnabled = definition.key in visibleKeys && (!definition.advanced || supported)
            if (group.findPreference<Preference>(definition.key) == null) group.addPreference(row)
        }
        val description = context.getString(if (mode == 2) R.string.bass_subwoofer_help else R.string.bass_natural_help)
        if (help.summary?.toString() != description) {
            help.summary = description
            onDescriptionChanged()
        }
        if (group.findPreference<Preference>(help.key) == null) group.addPreference(help)
        if (!supported && group.findPreference<Preference>(driver.key) == null) group.addPreference(driver)
        else if (supported) group.removePreference(driver)
        // Read-only refresh also restores mode and values when a removed key has no callback.
        PreferenceValueSynchronizer.refresh(group)
    }

    fun refreshFromPreferences() {
        if (closed) return
        // A file-based profile can replace the namespace without notifying individual keys.
        val next = BassController.preferences(context, true)
        if (preferences !== next) {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            preferences = next
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
        PreferenceValueSynchronizer.refresh(group, finishInteractions = true)
        render()
    }

    fun close() {
        if (closed) return
        closed = true
        renderFrame.close()
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        status.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterLocalReceiver(receiver)
    }
}
