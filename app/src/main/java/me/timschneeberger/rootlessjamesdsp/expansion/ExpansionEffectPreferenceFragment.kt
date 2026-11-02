package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.AdaptiveBassCompactPreferenceAdapter
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogFullWidthDividers
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup

class ExpansionEffectPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var kind: ExpansionEffectsKind
    private lateinit var preferences: SharedPreferences
    private lateinit var effectHeader: SwitchPreferenceGroup
    private var dashboard: ExpansionEffectsDashboard? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
        if (::effectHeader.isInitialized && (key == null || key == kind.enableKey)) effectHeader.syncFromPreferences()
        context?.let(ExpansionEffectsController::notifyChanged)
        dashboard?.configurationChanged(key)
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        kind = ExpansionEffectsKind.valueOf(requireArguments().getString(ARG_KIND)!!)
        preferenceManager.sharedPreferencesName = kind.prefsName
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        val ctx = requireContext()
        preferences = ExpansionEffectsController.preferences(ctx, kind)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        effectHeader = SwitchPreferenceGroup(ctx).apply {
            key = kind.enableKey; title = kind.title; setIcon(kind.icon)
            isIconSpaceReserved = false; setIsIconVisible(true); setDefaultValue(false)
        }
        preferenceScreen.addPreference(effectHeader)
        effectHeader.setProfessionalMode(true)
        if (kind != ExpansionEffectsKind.SMART_ADAPT) ExpansionEffectsActions.add(this, effectHeader, kind, professional = true)
        if (kind == ExpansionEffectsKind.MS_EQ) {
            ExpansionMsBandEditor.add(this, effectHeader, preferences)
        } else {
            ExpansionEffectsParameters.controls(kind).groupBy { ExpansionEffectsParameters.section(kind, it.key) }.forEach { (section, controls) ->
                if (section.isNotEmpty()) {
                    effectHeader.addPreference(PreferenceCategory(ctx).apply {
                        key = "section_${controls.first().key}"; title = section; isIconSpaceReserved = false
                    })
                }
                controls.forEach { control -> effectHeader.addPreference(ExpansionEffectsRows.numeric(ctx, kind, control, preferences)) }
            }
        }
        if (kind == ExpansionEffectsKind.SMART_ADAPT) ExpansionEffectsActions.add(this, effectHeader, kind, professional = true)
        effectHeader.addPreference(Preference(ctx).apply {
            key = "expansion_help"; title = "原理与使用说明"; isPersistent = false
            isIconSpaceReserved = false; layoutResource = R.layout.preference_liveprog_compact_row
            var expanded = false
            setOnPreferenceClickListener {
                expanded = !expanded
                summary = if (expanded) ExpansionEffectsParameters.description(kind) +
                    "\n静图随保存参数变化，图内标明参考模型；实时图只显示驱动处理读数。顶部电源只控制采样，标题旁开关控制音效。所有模块默认关闭。" else null
                true
            }
        })
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        dashboard = ExpansionEffectsDashboard(this, kind, preferences)
    }
    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            isNestedScrollingEnabled = true; itemAnimator = null
            addItemDecoration(LiveprogFullWidthDividers(requireContext()))
        }
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> = AdaptiveBassCompactPreferenceAdapter(preferenceScreen)
    override fun onResume() {
        super.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        effectHeader.syncFromPreferences(); dashboard?.onResume()
    }
    override fun onPause() { dashboard?.onPause(); super.onPause() }
    override fun onDestroyView() { dashboard?.close(); dashboard = null; super.onDestroyView() }
    override fun onDestroy() {
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }
    companion object {
        private const val ARG_KIND = "expansion.kind"
        fun newInstance(kind: ExpansionEffectsKind) = ExpansionEffectPreferenceFragment().apply {
            arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
        }
    }
}
