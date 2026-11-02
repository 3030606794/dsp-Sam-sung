package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.children
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

class TonePreferenceFragment : PreferenceFragmentCompat() {
    private val professionalMode get() = ProfessionalEffectActivity.isProfessional(this)
    private var batching = false
    private var advanced: ToneAdvancedEditor? = null
    private var dashboard: ToneDashboardCoordinator? = null
    private lateinit var group: SwitchPreferenceGroup
    private val sliders = mutableListOf<ToneSliderPreference>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
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
        group.setProfessionalMode(professionalMode)
        findPreference<ToneRangePreference>(ToneController.RANGE)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val newLimit = newValue as? Int ?: return@setOnPreferenceChangeListener false
                if (ToneAdvancedParameters.takenOver(prefs.all)) return@setOnPreferenceChangeListener false
                if (newLimit !in ToneController.RANGES) return@setOnPreferenceChangeListener false
                batching = true
                prefs.edit().apply {
                    putString(ToneController.RANGE, newLimit.toString())
                    ToneController.KEYS.forEach { putInt(it, ((prefs.all[it] as? Int) ?: 0).coerceIn(-newLimit, newLimit)) }
                }.apply()
                updateSliders(newLimit)
                batching = false
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                true
            }
        }
        findPreference<Preference>("fusion_tone_reset")?.apply {
            setOnPreferenceClickListener {
                if (ToneAdvancedParameters.takenOver(prefs.all)) return@setOnPreferenceClickListener false
                batching = true
                ToneAdvancedController.stopAudition(ctx, clearSnapshots = true)
                prefs.edit().apply { ToneController.KEYS.forEach { putInt(it, 0) } }.apply()
                updateSliders(ToneController.range(prefs))
                batching = false
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                true
            }
        }
        val titles = resources.getStringArray(R.array.fusion_tone_names)
        val left = resources.getStringArray(R.array.fusion_tone_left_keywords)
        val right = resources.getStringArray(R.array.fusion_tone_right_keywords)
        if (!professionalMode) ToneController.KEYS.forEachIndexed { i, name ->
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
        if (professionalMode) {
            // The professional model retains all ten saved bases; their duplicate home controls are omitted.
            listOf(ToneController.RANGE, "fusion_tone_reset").forEach { key ->
                group.findPreference<Preference>(key)?.let(group::removePreference)
            }
        }
        val coreKeys = group.children.mapNotNull { it.key }.toSet()
        advanced=ToneAdvancedEditor(ctx,group,this)
        if (!professionalMode) {
            group.setHomeVisibleKeys(coreKeys + with(ToneAdvancedParameters) {
                setOf(PROFESSIONAL, CORE_ON, MIX_ON, OUTPUT_ON, DYNAMIC_ON,
                    mix.key, output.key, bassDynamicWeight.key, transientWeight.key)
            })
            group.setExpandedHighlightEnabled(false)
            group.onProfessionalClick = { ProfessionalEffectActivity.openTone(ctx, group.title) }
        }
    }

    private fun updateSliders(limit: Int) {
        val prefs = ToneController.preferences(requireContext())
        val values = prefs.all
        sliders.forEach {
            it.limit = limit
            it.setValueSilently(((values[it.key] as? Int) ?: 0).coerceIn(-limit, limit))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, group.isExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null
            isNestedScrollingEnabled = professionalMode
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (professionalMode && activity is ProfessionalEffectActivity) dashboard = ToneDashboardCoordinator(this)
    }

    override fun onPause() {
        dashboard?.onPause()
        advanced?.stopAudition()
        sliders.forEach { it.cancelButtonRepeats() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        dashboard?.onResume()
    }

    override fun onDestroyView() {
        dashboard?.close(); dashboard = null
        advanced?.close(); advanced=null
        sliders.forEach { it.disposeButtonRepeats() }
        super.onDestroyView()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        private const val STATE_EXPANDED = "tone_expanded"
    }
}
