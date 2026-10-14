package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.roundToInt

class DynamicBassPreferenceFragment : PreferenceFragmentCompat() {
    lateinit var effectHeader: SwitchPreferenceGroup
        private set
    private lateinit var preferences: SharedPreferences
    private lateinit var status: SharedPreferences
    private lateinit var runtime: Preference
    private var supported = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        renderRuntime()
    }
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (supported != BassController.supported(requireContext())) build(effectHeader.isExpanded)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                preferences = BassController.preferences(context, false)
                build(false)
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            } else renderRuntime()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_BASS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        preferences = BassController.preferences(requireContext(), false)
        status = BassController.statusPreferences(requireContext())
        build(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        status.registerOnSharedPreferenceChangeListener(statusListener)
        requireContext().registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED); addAction(BassController.STATUS_ACTION)
        })
    }

    private fun build(expanded: Boolean) {
        val ctx = requireContext()
        BassParameters.migrate(preferences, false)
        supported = BassController.supported(ctx)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        effectHeader = SwitchPreferenceGroup(ctx).apply {
            key = getString(R.string.key_bass_enable)
            title = getString(R.string.bass_enable)
            setIcon(R.drawable.ic_twotone_speaker_24dp)
            isIconSpaceReserved = false
            setIsIconVisible(true)
            setDefaultValue(false)
        }
        preferenceScreen.addPreference(effectHeader)
        val packet = BassParameters.dynamicPacket(preferences.all)
        if (supported) effectHeader.addPreference(BassNumericPreference(ctx, BassParameters.maxGain, packet[0]))
        else {
            val wholeDb = (packet[0] / 10.0).roundToInt()
            effectHeader.addPreference(BassNumericPreference(ctx,
                BassParameters.Control("bass_legacy_gain_ui", "最大增益", 0, 15, 5, unit = "dB"), wholeDb).apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, candidate ->
                    val gain = candidate as Int
                    preferences.edit().putFloat(BassParameters.LEGACY_MAX_GAIN, gain.toFloat())
                        .putInt(BassParameters.MAX_GAIN, gain * 10).apply()
                    true
                }
            })
            effectHeader.addPreference(Preference(ctx).apply {
                key = "bass_legacy_precision"; setSummary(R.string.bass_driver_legacy_gain)
                isSelectable = false; isIconSpaceReserved = false
            })
        }
        BassParameters.dynamic.drop(1).forEachIndexed { index, control ->
            effectHeader.addPreference(BassNumericPreference(ctx, control, packet[index + 1]).apply {
                isEnabled = supported
                setOnPreferenceChangeListener { _, candidate ->
                    BassParameters.acceptsDynamic(preferences.all, control, candidate as Int).also {
                        if (!it) Toast.makeText(ctx, "跟踪下限必须小于上限", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        effectHeader.addPreference(Preference(ctx).apply {
            key = "bass_dynamic_help"; setSummary(R.string.bass_dynamic_help)
            isSelectable = false; isIconSpaceReserved = false
        })
        if (!supported) effectHeader.addPreference(Preference(ctx).apply {
            key = "bass_dynamic_driver"; setTitle(R.string.bass_driver_required)
            isSelectable = false; isIconSpaceReserved = false
        })
        runtime = Preference(ctx).apply {
            key = "bass_runtime"; setTitle(R.string.bass_runtime_title)
            isSelectable = false; isIconSpaceReserved = false
        }
        effectHeader.addPreference(runtime)
        effectHeader.setExpanded(expanded)
        renderRuntime()
    }

    private fun renderRuntime() {
        if (!::runtime.isInitialized) return
        val state = if (!preferences.getBoolean(getString(R.string.key_bass_enable), false)) BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.OFF)
            else if (!supported) BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNSUPPORTED) else BassController.runtime
        runtime.summary = when (state.state) {
            BassRuntimeStatus.State.ACTIVE -> getString(R.string.bass_runtime_value,
                String.format(Locale.ROOT, "%.2f", state.gainHundredths!! / 100.0),
                String.format(Locale.ROOT, "%.2f", state.frequencyHundredths!! / 100.0))
            BassRuntimeStatus.State.OFF -> getString(R.string.bass_runtime_off)
            BassRuntimeStatus.State.IDLE -> getString(R.string.bass_runtime_idle)
            BassRuntimeStatus.State.UNKNOWN -> getString(R.string.bass_runtime_unknown)
            BassRuntimeStatus.State.UNSUPPORTED -> getString(R.string.bass_runtime_unsupported)
            BassRuntimeStatus.State.MULTIPLE -> getString(R.string.bass_runtime_multiple)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, effectHeader.isExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply { isNestedScrollingEnabled = false; itemAnimator = null }
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> = RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        status.unregisterOnSharedPreferenceChangeListener(statusListener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
    companion object { private const val STATE_EXPANDED = "bass_dynamic_expanded" }
}
