package me.timschneeberger.rootlessjamesdsp.fusion

import android.animation.LayoutTransition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Each effect is a native DSP card; all cards retain the existing profile namespace. */
class ViperPreferenceFragment : Fragment() {
    private var observedPreferences: SharedPreferences? = null
    private var reloadPending = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) reloadProfile()
        }
    }

    val effectFragments: List<ViperEffectPreferenceFragment>
        get() = ViperParameters.groups.mapNotNull { effectFragment(it.key) }

    fun effectFragment(groupKey: String): ViperEffectPreferenceFragment? =
        childFragmentManager.findFragmentByTag(groupKey) as? ViperEffectPreferenceFragment

    fun <T : Preference> findPreference(key: CharSequence): T? =
        effectFragments.firstNotNullOfOrNull { it.findPreference<T>(key) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = ContextCompat.getDrawable(context, R.drawable.shape_card_divider)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            ViperParameters.groups.forEach { group ->
                addView(MaterialCardView(context).apply {
                    tag = "viper_card_${group.key}"
                    addView(FragmentContainerView(context).apply {
                        // Resource IDs, rather than generated IDs, survive fragment restoration.
                        id = containerId(group.key)
                    }, ViewGroup.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        val missing = ViperParameters.groups.filter { effectFragment(it.key) == null }
        if (missing.isNotEmpty()) {
            childFragmentManager.beginTransaction().apply {
                missing.forEach { group ->
                    add(containerId(group.key), ViperEffectPreferenceFragment.newInstance(group.key), group.key)
                }
            }.commitNow()
        }
        @Suppress("DEPRECATION")
        val prefs = requireContext().getSharedPreferences(ViperParameters.PREFS, Context.MODE_MULTI_PROCESS)
        observedPreferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    private fun reloadProfile() {
        if (view == null || childFragmentManager.isStateSaved) {
            reloadPending = true
            return
        }
        reloadPending = false
        // The existing preset loader replaces preference XML files. New preference managers
        // reopen the store in MODE_MULTI_PROCESS and bind the newly selected profile.
        childFragmentManager.beginTransaction().apply {
            ViperParameters.groups.forEach { group ->
                replace(containerId(group.key), ViperEffectPreferenceFragment.newInstance(group.key), group.key)
            }
        }.commitNow()
    }

    override fun onResume() {
        super.onResume()
        if (reloadPending) reloadProfile()
    }

    override fun onDestroyView() {
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        observedPreferences = null
        context?.unregisterLocalReceiver(receiver)
        super.onDestroyView()
    }

    private fun containerId(key: String): Int = when (key) {
        "v_spectrum" -> R.id.viper_effect_spectrum
        "v_field" -> R.id.viper_effect_field
        "v_diff" -> R.id.viper_effect_diff
        "v_reverb" -> R.id.viper_effect_reverb
        "v_dynamic" -> R.id.viper_effect_dynamic
        "v_tube" -> R.id.viper_effect_tube
        "v_bass" -> R.id.viper_effect_bass
        "v_clarity" -> R.id.viper_effect_clarity
        "v_cure" -> R.id.viper_effect_cure
        "v_analog" -> R.id.viper_effect_analog
        else -> error("Unknown ViPER effect: $key")
    }
}

class ViperEffectPreferenceFragment : PreferenceFragmentCompat() {
    lateinit var effectHeader: SwitchPreferenceGroup
        private set

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val group = ViperParameters.groups.single { it.key == requireArguments().getString(ARG_GROUP) }
        preferenceManager.sharedPreferencesName = ViperParameters.PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        effectHeader = SwitchPreferenceGroup(ctx).apply {
            key = group.key
            title = group.title
            setIcon(effectIcon(group.key))
            setIsIconVisible(true)
            setDefaultValue(false)
        }
        preferenceScreen.addPreference(effectHeader)
        group.controls.forEach { effectHeader.addPreference(ViperSliderPreference(ctx, it)) }
        if (group.key == "v_dynamic") {
            effectHeader.addPreference(ListPreference(ctx).apply {
                key = "v_dynamic_preset"
                title = "设备响应"
                entries = ViperParameters.dynamicPresets.keys.toTypedArray()
                entryValues = ViperParameters.dynamicPresets.keys.indices.map { it.toString() }.toTypedArray()
                setDefaultValue("0")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                isIconSpaceReserved = false
            })
        }
        effectHeader.setExpanded(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, effectHeader.isExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply {
            isNestedScrollingEnabled = false
            itemAnimator = null
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    private fun effectIcon(key: String): Int = when (key) {
        "v_spectrum" -> R.drawable.ic_baseline_chart_timeline_variant_24dp
        "v_field" -> R.drawable.ic_baseline_cone_24dp
        "v_diff" -> R.drawable.ic_baseline_image_filter_tilt_shift_24dp
        "v_reverb" -> R.drawable.ic_baseline_cube_outline_24dp
        "v_dynamic" -> R.drawable.ic_baseline_arrow_collapse_24dp
        "v_tube" -> R.drawable.ic_baseline_led_outline_24dp
        "v_bass" -> R.drawable.ic_twotone_speaker_24dp
        "v_clarity" -> R.drawable.ic_twotone_auto_fix_high_24
        "v_cure" -> R.drawable.ic_twotone_headphones_24dp
        "v_analog" -> R.drawable.ic_baseline_sine_wave_24dp
        else -> error("Unknown ViPER effect: $key")
    }

    companion object {
        private const val ARG_GROUP = "viper_group"
        private const val STATE_EXPANDED = "viper_expanded"

        fun newInstance(groupKey: String) = ViperEffectPreferenceFragment().apply {
            arguments = Bundle().apply { putString(ARG_GROUP, groupKey) }
        }
    }
}
