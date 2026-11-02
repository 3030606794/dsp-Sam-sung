package me.timschneeberger.rootlessjamesdsp.creative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.fusion.BassNumericPreference
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.ui.HomeEffectCardExpansion
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** The same header, switch, rotating settings icon and numeric rows as Adaptive Bass. */
class CreativeEffectsHomeFragment : PreferenceFragmentCompat() {
    private val kind get() = CreativeEffectsKind.valueOf(requireArguments().getString(ARG_KIND)!!)
    private lateinit var preferences: SharedPreferences
    lateinit var effectHeader: SwitchPreferenceGroup
        private set
    private var freezeActions: CreativeFreezeActionsPreference? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == kind.enableKey && preferences.all[kind.enableKey] != true && kind == CreativeEffectsKind.FREEZE)
            CreativeEffectsController.resetTransient(requireContext())
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
        effectHeader.syncFromPreferences()
        freezeActions?.refresh()
        context?.let(CreativeEffectsController::notifyChanged)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
            effectHeader.syncFromPreferences()
            freezeActions?.refresh()
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = kind.prefsName
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        val ctx = requireContext()
        preferences = CreativeEffectsController.preferences(ctx, kind)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        effectHeader = SwitchPreferenceGroup(ctx).apply {
            key = kind.enableKey; title = kind.title; setIcon(kind.icon)
            isIconSpaceReserved = false; setIsIconVisible(true); setDefaultValue(false)
            onProfessionalClick = { ProfessionalEffectActivity.openCreative(ctx, kind, title) }
        }
        preferenceScreen.addPreference(effectHeader)
        CreativeEffectsParameters.basic(kind).forEach {
            effectHeader.addPreference(BassNumericPreference(ctx, it, CreativeEffectsParameters.value(preferences.all, it)))
        }
        if (kind == CreativeEffectsKind.FREEZE) {
            freezeActions = CreativeFreezeActionsPreference(ctx)
            effectHeader.addPreference(freezeActions!!)
        }
        effectHeader.setExpanded(HomeEffectCardExpansion.read(ctx, kind.enableKey))
        preferences.registerOnSharedPreferenceChangeListener(listener)
        ctx.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED); addAction(CreativeEffectsController.ACTION_FREEZE_CHANGED)
        })
    }
    override fun onResume() {
        super.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        effectHeader.syncFromPreferences(); freezeActions?.refresh()
    }
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        effectHeader.onExpandedChanged = null
        super.onViewStateRestored(savedInstanceState)
        HomeEffectCardExpansion.bind(requireContext(), effectHeader, kind.enableKey)
    }
    override fun onDestroyView() {
        effectHeader.onExpandedChanged = null
        super.onDestroyView()
    }
    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply { isNestedScrollingEnabled = false; itemAnimator = null }
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
    companion object {
        private const val ARG_KIND = "creative_kind"
        fun newInstance(kind: CreativeEffectsKind) = CreativeEffectsHomeFragment().apply {
            arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
        }
    }
}
