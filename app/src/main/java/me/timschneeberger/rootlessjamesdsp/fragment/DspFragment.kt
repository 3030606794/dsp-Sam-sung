package me.timschneeberger.rootlessjamesdsp.fragment

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
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDspBinding
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.TonePreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.ViperEffectPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.ViperParameters
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()

    private lateinit var binding: FragmentDspBinding
    private var viperPreferences: SharedPreferences? = null
    private var viperReloadPending = false
    private var selectedPeq = false
    private val equalizerStores = mutableListOf<SharedPreferences>()
    private val equalizerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderEqualizerTabs() }

    private fun renderEqualizerTabs() {
        val root = view ?: return
        val eqOn = requireContext().getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
            .getBoolean(getString(R.string.key_eq_enable), false)
        val peqOn = requireContext().getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
            .getBoolean(getString(R.string.key_geq_enable), false)
        root.findViewById<View>(R.id.card_eq).isVisible = !selectedPeq
        root.findViewById<View>(R.id.card_geq).isVisible = selectedPeq
        listOf(Triple(R.id.eq_tab, "EQ", eqOn), Triple(R.id.peq_tab, "PEQ", peqOn)).forEachIndexed { index, (id, label, enabled) ->
            root.findViewById<TextView>(id).apply {
                text = if (enabled) "$label  ●" else label
                setTextColor(if (enabled) android.graphics.Color.rgb(46, 173, 85) else
                    com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                paintFlags = if (selectedPeq == (index == 1)) paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    else paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
                isSelected = selectedPeq == (index == 1)
            }
        }
        root.findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.eq_power).apply {
            isSelected = if (selectedPeq) peqOn else eqOn
            imageTintList = requireContext().getColorStateList(R.color.selector_floating_toggle_foreground_tint)
            contentDescription = "${if (selectedPeq) "PEQ" else "EQ"}：${if (isSelected) "已开启，点击关闭" else "已关闭，点击开启"}"
        }
    }
    private val viperListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) reloadViperEffects()
        }
    }

    private fun reloadViperEffects() {
        if (view == null || childFragmentManager.isStateSaved) {
            viperReloadPending = true
            return
        }
        viperReloadPending = false
        childFragmentManager.beginTransaction().apply {
            viperContainers.forEach { (key, container) ->
                replace(container, ViperEffectPreferenceFragment.newInstance(key), key)
            }
        }.commitNow()
    }

    override fun onResume() {
        super.onResume()
        if (viperReloadPending) reloadViperEffects()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedPeq = savedInstanceState?.getBoolean("selected_peq", false) ?: false
        listOf(Constants.PREF_EQ, Constants.PREF_GEQ).forEach { namespace ->
            equalizerStores += requireContext().getSharedPreferences(namespace, Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(equalizerListener)
            }
        }
        view.findViewById<View>(R.id.eq_tab).setOnClickListener { selectedPeq = false; renderEqualizerTabs() }
        view.findViewById<View>(R.id.peq_tab).setOnClickListener { selectedPeq = true; renderEqualizerTabs() }
        view.findViewById<View>(R.id.eq_power).setOnClickListener {
            childFragmentManager.executePendingTransactions()
            val fragment = childFragmentManager.findFragmentById(if (selectedPeq) R.id.card_geq else R.id.card_eq)
                as? androidx.preference.PreferenceFragmentCompat
            val key = getString(if (selectedPeq) R.string.key_geq_enable else R.string.key_eq_enable)
            fragment?.findPreference<me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup>(key)?.let {
                it.setValue(!it.isChecked)
            }
            renderEqualizerTabs()
        }
        renderEqualizerTabs()
        categoryContainers.forEach { (headerId, contentId) ->
            val header = view.findViewById<TextView>(headerId)
            val content = view.findViewById<View>(contentId)
            fun expand(expanded: Boolean) {
                content.isVisible = expanded
                ViewCompat.setStateDescription(header, getString(if (expanded)
                    R.string.effect_group_expanded else R.string.effect_group_collapsed))
                header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    if (expanded) R.drawable.ic_chevron_up_24dp else R.drawable.ic_chevron_down_24dp, 0)
            }
            expand(savedInstanceState?.getBoolean("category_$contentId", false) ?: false)
            header.setOnClickListener { expand(!content.isVisible) }
        }
        @Suppress("DEPRECATION")
        val prefs = requireContext().getSharedPreferences(ViperParameters.PREFS, Context.MODE_MULTI_PROCESS)
        viperPreferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(viperListener)
        requireContext().registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selected_peq", selectedPeq)
        categoryContainers.forEach { (_, contentId) ->
            view?.findViewById<View>(contentId)?.let { outState.putBoolean("category_$contentId", it.isVisible) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        equalizerStores.forEach { it.unregisterOnSharedPreferenceChangeListener(equalizerListener) }
        equalizerStores.clear()
        viperPreferences?.unregisterOnSharedPreferenceChangeListener(viperListener)
        viperPreferences = null
        requireContext().unregisterLocalReceiver(presetReceiver)
        super.onDestroyView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(layoutInflater, container, false)

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        binding.cardContainer.layoutTransition = transition

        childFragmentManager.beginTransaction()
            .addIfMissing(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
            .addIfMissing(R.id.card_fusion_tone, TonePreferenceFragment())
            .addIfMissing(R.id.card_surround, SurroundPreferenceFragment())
            .addIfMissing(R.id.card_output_control, OutputControlFragment())
            .addIfMissing(
                R.id.card_compressor, PreferenceGroupFragment.newInstance(Constants.PREF_COMPANDER,
                    R.xml.dsp_compander_preferences
                ))
            .addIfMissing(
                R.id.card_bass, PreferenceGroupFragment.newInstance(Constants.PREF_BASS,
                    R.xml.dsp_bass_preferences
                ))
            .addIfMissing(
                R.id.card_eq, PreferenceGroupFragment.newInstance(Constants.PREF_EQ,
                    R.xml.dsp_equalizer_preferences
                ))
            .addIfMissing(
                R.id.card_geq, PreferenceGroupFragment.newInstance(Constants.PREF_GEQ,
                    R.xml.dsp_graphiceq_preferences
                ))
            .addIfMissing(
                R.id.card_ddc, PreferenceGroupFragment.newInstance(Constants.PREF_DDC,
                    R.xml.dsp_ddc_preferences
                ))
            .addIfMissing(
                R.id.card_convolver, PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER,
                    R.xml.dsp_convolver_preferences
                ))
            .addIfMissing(
                R.id.card_liveprog, PreferenceGroupFragment.newInstance(Constants.PREF_LIVEPROG,
                    R.xml.dsp_liveprog_preferences
                ))
            .addIfMissing(
                R.id.card_tube, PreferenceGroupFragment.newInstance(Constants.PREF_TUBE,
                    R.xml.dsp_tube_preferences
                ))
            .addIfMissing(
                R.id.card_stereowide, PreferenceGroupFragment.newInstance(Constants.PREF_STEREOWIDE,
                    R.xml.dsp_stereowide_preferences
                ))
            .addIfMissing(
                R.id.card_crossfeed, PreferenceGroupFragment.newInstance(Constants.PREF_CROSSFEED,
                    R.xml.dsp_crossfeed_preferences
                ))
            .addIfMissing(
                R.id.card_reverb, PreferenceGroupFragment.newInstance(Constants.PREF_REVERB,
                    R.xml.dsp_reverb_preferences
                ))
            .apply {
                viperContainers.forEach { (key, container) ->
                    if (childFragmentManager.findFragmentById(container) == null)
                        add(container, ViperEffectPreferenceFragment.newInstance(key), key)
                }
            }
            .commit()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }

        return binding.root
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                binding.cardDeviceProfiles.isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun FragmentTransaction.addIfMissing(id: Int, fragment: Fragment): FragmentTransaction {
        // FragmentManager restores child instances before the new view is created.
        // Keep those instances so rotation preserves each effect's expanded state.
        if (childFragmentManager.findFragmentById(id) == null) add(id, fragment)
        return this
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                childFragmentManager.beginTransaction()
                    .replace(id, newFragment)
                    .commitAllowingStateLoss()
            }
            catch(ex: IllegalStateException) {
                Timber.e("Failed to restart fragment")
                Timber.i(ex)
            }
        }
    }

    companion object {
        private val viperContainers = listOf(
            "v_spectrum" to R.id.viper_effect_spectrum,
            "v_field" to R.id.viper_effect_field,
            "v_diff" to R.id.viper_effect_diff,
            "v_reverb" to R.id.viper_effect_reverb,
            "v_dynamic" to R.id.viper_effect_dynamic,
            "v_tube" to R.id.viper_effect_tube,
            "v_bass" to R.id.viper_effect_bass,
            "v_clarity" to R.id.viper_effect_clarity,
            "v_cure" to R.id.viper_effect_cure,
            "v_analog" to R.id.viper_effect_analog,
        )
        private val categoryContainers = listOf(
            R.id.group_equalizers_header to R.id.group_equalizers_content,
            R.id.group_bass_header to R.id.group_bass_content,
            R.id.group_clarity_header to R.id.group_clarity_content,
            R.id.group_spatial_header to R.id.group_spatial_content,
            R.id.group_crossfeed_header to R.id.group_crossfeed_content,
            R.id.group_reverb_header to R.id.group_reverb_content,
            R.id.group_analog_header to R.id.group_analog_content,
            R.id.group_dynamics_header to R.id.group_dynamics_content,
            R.id.group_advanced_header to R.id.group_advanced_content,
        )

        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
