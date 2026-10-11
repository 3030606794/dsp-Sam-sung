package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

class TonePreferenceFragment : PreferenceFragmentCompat() {
    private var batching = false
    private var expanded = false
    private val details = mutableListOf<Preference>()
    private val sliders = mutableListOf<SeekBarPreference>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!batching) context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) {
                (parentFragment as? DspFragment)?.restartFragment(id, TonePreferenceFragment())
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = ToneController.PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        expanded = savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false
        buildScreen()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    private fun buildScreen() {
        val ctx = requireContext()
        val prefs = ToneController.preferences(ctx)
        val limit = ToneController.range(prefs)
        details.clear()
        sliders.clear()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        addPreferencesFromResource(R.xml.dsp_fusion_tone_preferences)
        findPreference<SwitchPreferenceCompat>(ToneController.ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            expanded = enabled
            updateExpansion(enabled)
            true
        }
        findPreference<Preference>(EXPAND)?.setOnPreferenceClickListener {
            expanded = !expanded
            updateExpansion()
            true
        }
        findPreference<ToneRangePreference>(ToneController.RANGE)?.apply {
            details.add(this)
            setOnPreferenceChangeListener { _, newValue ->
                val newLimit = newValue as? Int ?: return@setOnPreferenceChangeListener false
                if (newLimit !in ToneController.RANGES) return@setOnPreferenceChangeListener false
                batching = true
                prefs.edit().apply {
                    putString(ToneController.RANGE, newLimit.toString())
                    ToneController.KEYS.forEach { putInt(it, prefs.getInt(it, 0).coerceIn(-newLimit, newLimit)) }
                }.apply()
                updateSliders(newLimit)
                batching = false
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                true
            }
        }
        findPreference<Preference>("fusion_tone_reset")?.apply {
            details.add(this)
            setOnPreferenceClickListener {
                batching = true
                prefs.edit().apply {
                    ToneController.KEYS.forEach { putInt(it, 0) }
                    putString(ToneController.RANGE, "100")
                }.apply()
                findPreference<ToneRangePreference>(ToneController.RANGE)?.setRangeSilently(100)
                updateSliders(100)
                batching = false
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                true
            }
        }
        val titles = resources.getStringArray(R.array.fusion_tone_names)
        val directions = resources.getStringArray(R.array.fusion_tone_directions)
        ToneController.KEYS.forEachIndexed { i, name ->
            val slider = SeekBarPreference(ctx).apply {
                key = name
                title = titles[i]
                summary = directions[i]
                min = -limit
                max = limit
                seekBarIncrement = 1
                showSeekBarValue = true
                updatesContinuously = true
                isIconSpaceReserved = false
                setDefaultValue(0)
            }
            preferenceScreen.addPreference(slider)
            slider.dependency = ToneController.ENABLED
            sliders.add(slider)
            details.add(slider)
        }
        updateExpansion()
    }

    private fun updateSliders(limit: Int) {
        val prefs = ToneController.preferences(requireContext())
        sliders.forEach {
            it.min = -limit
            it.max = limit
            it.value = prefs.getInt(it.key, 0).coerceIn(-limit, limit)
        }
    }

    private fun updateExpansion(enabled: Boolean =
        findPreference<SwitchPreferenceCompat>(ToneController.ENABLED)?.isChecked == true) {
        findPreference<Preference>(EXPAND)?.apply {
            isVisible = enabled
            setTitle(if (expanded) R.string.fusion_tone_collapse else R.string.fusion_tone_expand)
        }
        details.forEach { it.isVisible = enabled && expanded }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, expanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null
            isNestedScrollingEnabled = false
        }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val EXPAND = "fusion_tone_expand"
        private const val STATE_EXPANDED = "tone_expanded"
    }
}
