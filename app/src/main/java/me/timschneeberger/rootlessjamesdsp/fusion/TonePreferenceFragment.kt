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
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

class TonePreferenceFragment : PreferenceFragmentCompat() {
    private var batching = false
    private lateinit var group: SwitchPreferenceGroup
    private val sliders = mutableListOf<ToneSliderPreference>()
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
        buildScreen()
        group.setExpanded(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    private fun buildScreen() {
        val ctx = requireContext()
        val prefs = ToneController.preferences(ctx)
        val limit = ToneController.range(prefs)
        sliders.clear()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        addPreferencesFromResource(R.xml.dsp_fusion_tone_preferences)
        group = requireNotNull(findPreference(ToneController.ENABLED))
        group.setIsIconVisible(true)
        findPreference<ToneRangePreference>(ToneController.RANGE)?.apply {
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
        val left = resources.getStringArray(R.array.fusion_tone_left_keywords)
        val right = resources.getStringArray(R.array.fusion_tone_right_keywords)
        ToneController.KEYS.forEachIndexed { i, name ->
            val slider = ToneSliderPreference(ctx).apply {
                key = name
                title = titles[i]
                leftKeyword = left[i]
                rightKeyword = right[i]
                this.limit = limit
                setDefaultValue(0)
            }
            group.addPreference(slider)
            sliders.add(slider)
        }
    }

    private fun updateSliders(limit: Int) {
        val prefs = ToneController.preferences(requireContext())
        sliders.forEach {
            it.limit = limit
            it.value = prefs.getInt(it.key, 0).coerceIn(-limit, limit)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, group.isExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null
            isNestedScrollingEnabled = false
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        private const val STATE_EXPANDED = "tone_expanded"
    }
}
