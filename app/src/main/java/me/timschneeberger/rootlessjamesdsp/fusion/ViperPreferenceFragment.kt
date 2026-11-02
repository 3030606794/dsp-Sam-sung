package me.timschneeberger.rootlessjamesdsp.fusion

import android.Manifest
import android.animation.LayoutTransition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.CompoundButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogFullWidthDividers
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.ParameterSwitchUi
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
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
    private val professionalMode get() = ProfessionalEffectActivity.isProfessional(this)
    private val compactLinkedControls get() = professionalMode &&
        requireArguments().getString(ARG_GROUP) in setOf("v_spectrum", "v_clarity", "v_bass")
    private var bassEditor: ViperBassEditor? = null
    private var bassVisualization: BassLinkedPageVisualization? = null
    private var clarityVisualization: ClarityPageVisualization? = null
    private var fieldVisualization: FieldPageVisualization? = null
    private var diffVisualization: DiffPageVisualization? = null
    private var crossfeedDashboard: CrossfeedDashboardCoordinator? = null
    private var reverbDashboard: ReverbDashboardCoordinator? = null
    private var tubeDashboard: TubeDashboardCoordinator? = null
    private var analogDashboard: AnalogDashboardCoordinator? = null
    private var spectrumDashboard: SpectrumDashboardCoordinator? = null
    private var dynamicDashboard: DynamicDashboardCoordinator? = null
    private val requestBassAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bassVisualization?.onPermissionResult(granted)
    }
    private var highFrequencyEditor: HighFrequencyEditor? = null
    private var fieldEditor: FieldEditor? = null
    private var diffEditor: DiffEditor? = null
    private var crossfeedEditor: CrossfeedEditor? = null
    private var reverbEditor: ReverbEditor? = null
    private var tubeEditor: TubeEditor? = null
    private var analogEditor: AnalogEditor? = null
    private var dynamicEditor: DynamicEditor? = null
    private var spectrumDetails: HighFrequencyDetailsPreference? = null
    private var spectrumDetailsFrame: me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate? = null
    private var spectrumDetailsReceiverRegistered = false
    private val spectrumDetailsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isResumed) spectrumDetailsFrame?.request(false)
        }
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val card = view
        if (card != null && (!card.isShown || !card.getGlobalVisibleRect(Rect()))) {
            highFrequencyEditor?.stopAudition(); fieldEditor?.stopAudition()
            diffEditor?.stopAudition()
            crossfeedEditor?.stopAudition()
            reverbEditor?.stopAudition()
            tubeEditor?.stopAudition()
            analogEditor?.stopAudition()
            dynamicEditor?.stopAudition()
        }
    }
    lateinit var effectHeader: SwitchPreferenceGroup
        private set

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val group = ViperParameters.groups.single { it.key == requireArguments().getString(ARG_GROUP) }
        if (group.key == "v_clarity" || group.key == "v_spectrum")
            HighFrequencyParameters.migrate(HighFrequencyController.preferences(ctx))
        if (group.key == "v_field") FieldParameters.migrate(FieldController.preferences(ctx))
        if (group.key == "v_diff") DiffParameters.migrate(DiffController.preferences(ctx))
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
        effectHeader.setProfessionalMode(professionalMode)
        group.controls.filter {
            when (group.key) {
                "v_bass" -> it.key == "v_bass_mode"
                "v_clarity" -> it.key == "v_clarity_mode"
                "v_spectrum", "v_field", "v_diff" -> false
                else -> true
            }
        }.forEach {
            effectHeader.addPreference(ViperSliderPreference(ctx, it).apply {
                if (group.key == "v_bass" || group.key == "v_clarity" || group.key == "v_analog") order = 0
            })
        }
        if (group.key == "v_bass") bassEditor = ViperBassEditor(ctx, effectHeader) {
            spectrumDetailsFrame?.request(false)
        }
        if (group.key == "v_clarity" || group.key == "v_spectrum")
            highFrequencyEditor = HighFrequencyEditor(ctx, effectHeader, group.key == "v_spectrum")
        if (group.key == "v_field") fieldEditor = FieldEditor(ctx,effectHeader)
        if (group.key == "v_diff") diffEditor = DiffEditor(ctx,effectHeader)
        if (group.key == "v_cure") crossfeedEditor = CrossfeedEditor(ctx,effectHeader,this)
        if (group.key == "v_reverb") reverbEditor = ReverbEditor(ctx,effectHeader,this)
        if (group.key == "v_tube") tubeEditor = TubeEditor(ctx,effectHeader,this)
        if (group.key == "v_analog") analogEditor = AnalogEditor(ctx,effectHeader,this)
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
        if (group.key == "v_dynamic") dynamicEditor = DynamicEditor(ctx,effectHeader,this)
        if (compactLinkedControls) {
            // Keep the original note objects owned by their editors. Only their presentation
            // moves into one folded section; runtime text and parameter help still read them.
            spectrumDetails = HighFrequencyDetailsPreference(ctx, effectHeader, group.key).also { effectHeader.addPreference(it) }
            val detailKeys = when (group.key) {
                "v_spectrum" -> SPECTRUM_DETAIL_KEYS
                "v_bass" -> BASS_DETAIL_KEYS
                else -> CLARITY_DETAIL_KEYS
            }
            effectHeader.setChildVisibilityFilter { row -> row.key.orEmpty() !in detailKeys }
        }
        if (!professionalMode) {
            val replacements = when (group.key) {
                "v_bass" -> BassParameters.viper.filter { !it.advanced }.map { it.key }
                "v_spectrum" -> listOf(HighFrequencyParameters.spectrumAmount.key,
                    HighFrequencyParameters.spectrumFrequency.key, HighFrequencyParameters.spectrumOutput.key)
                "v_clarity" -> HighFrequencyParameters.clarity.filter { !it.advanced }.map { it.key }
                "v_field" -> listOf(FieldParameters.MODE, FieldParameters.legacyWidth.key,
                    FieldParameters.legacyCenter.key, FieldParameters.width.key)
                "v_diff" -> listOf(DiffParameters.MODE, DiffParameters.legacy.key, DiffParameters.delay.key)
                "v_tube" -> listOf(TubeParameters.PROFESSIONAL, TubeParameters.SAT_ON, TubeParameters.drive.key,
                    TubeParameters.MIX_ON, TubeParameters.mix.key, TubeParameters.OUTPUT_ON, TubeParameters.output.key)
                "v_dynamic" -> listOf(DynamicParameters.BASE, DynamicParameters.LEGACY, DynamicParameters.PROFESSIONAL,
                    DynamicParameters.CORE_ON, DynamicParameters.ADAPTIVE_ON, DynamicParameters.threshold.key,
                    DynamicParameters.MIX_ON, DynamicParameters.mix.key, DynamicParameters.OUTPUT_ON, DynamicParameters.output.key)
                else -> emptyList()
            }
            effectHeader.setHomeVisibleKeys(group.controls.map { it.key }.toSet() + replacements)
            if (group.key == "v_tube" || group.key == "v_dynamic") effectHeader.setExpandedHighlightEnabled(false)
            effectHeader.onProfessionalClick = { ProfessionalEffectActivity.openViper(ctx, group.key, effectHeader.title) }
        }
        effectHeader.setExpanded(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, effectHeader.isExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (compactLinkedControls) {
            setDivider(null)
            spectrumDetailsFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {
                if (isResumed) spectrumDetails?.refreshIfExpanded()
            }
            if (requireArguments().getString(ARG_GROUP) != "v_bass") {
                requireContext().registerLocalReceiver(spectrumDetailsReceiver,
                    IntentFilter(HighFrequencyController.STATUS_ACTION).apply { addAction(Constants.ACTION_PRESET_LOADED) })
                spectrumDetailsReceiverRegistered = true
            }
        }
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        if (professionalMode && requireArguments().getString(ARG_GROUP) == "v_bass" &&
            activity is ProfessionalEffectActivity) {
            bassVisualization = BassLinkedPageVisualization(this) {
                requestBassAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_clarity") {
            clarityVisualization = ClarityPageVisualization(this,
                spectrum = requireArguments().getString(ARG_GROUP) == "v_spectrum")
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_field") {
            fieldVisualization = FieldPageVisualization(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_diff") {
            diffVisualization = DiffPageVisualization(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_cure") {
            crossfeedDashboard = CrossfeedDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_reverb") {
            reverbDashboard = ReverbDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_tube") {
            tubeDashboard = TubeDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_analog") {
            analogDashboard = AnalogDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_spectrum") {
            spectrumDashboard = SpectrumDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getString(ARG_GROUP) == "v_dynamic") {
            dynamicDashboard = DynamicDashboardCoordinator(this)
        }
    }

        
    override fun onPause() {
        spectrumDashboard?.onPause()
        dynamicDashboard?.onPause()
        bassVisualization?.onPause()
        clarityVisualization?.onPause()
        fieldVisualization?.onPause()
        diffVisualization?.onPause()
        crossfeedDashboard?.onPause()
        reverbDashboard?.onPause()
        tubeDashboard?.onPause()
        analogDashboard?.onPause()
        highFrequencyEditor?.stopAudition()
        fieldEditor?.stopAudition()
        diffEditor?.stopAudition()
        crossfeedEditor?.stopAudition()
        reverbEditor?.stopAudition()
        tubeEditor?.stopAudition()
        analogEditor?.stopAudition()
        dynamicEditor?.stopAudition()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bassVisualization?.onResume()
        clarityVisualization?.onResume()
        fieldVisualization?.onResume()
        diffVisualization?.onResume()
        crossfeedDashboard?.onResume()
        reverbDashboard?.onResume()
        tubeDashboard?.onResume()
        analogDashboard?.onResume()
        spectrumDashboard?.onResume()
        dynamicDashboard?.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        bassEditor?.refreshFromPreferences()
        if (requireArguments().getString(ARG_GROUP) == "v_clarity") highFrequencyEditor?.refreshFromPreferences()
        spectrumDetails?.refreshIfExpanded()
    }

    override fun onDestroyView() {
        spectrumDetailsFrame?.close(); spectrumDetailsFrame = null
        if (spectrumDetailsReceiverRegistered) {
            context?.unregisterLocalReceiver(spectrumDetailsReceiver)
            spectrumDetailsReceiverRegistered = false
        }
        spectrumDashboard?.close(); spectrumDashboard = null
        dynamicDashboard?.close(); dynamicDashboard = null
        bassVisualization?.close()
        bassVisualization = null
        clarityVisualization?.close()
        clarityVisualization = null
        fieldVisualization?.close()
        fieldVisualization = null
        diffVisualization?.close()
        diffVisualization = null
        crossfeedDashboard?.close()
        crossfeedDashboard = null
        reverbDashboard?.close()
        reverbDashboard = null
        tubeDashboard?.close()
        tubeDashboard = null
        analogDashboard?.close()
        analogDashboard = null
        view?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
        highFrequencyEditor?.stopAudition()
        fieldEditor?.stopAudition()
        diffEditor?.stopAudition()
        crossfeedEditor?.stopAudition()
        reverbEditor?.stopAudition()
        tubeEditor?.stopAudition()
        analogEditor?.stopAudition()
        dynamicEditor?.stopAudition()
        super.onDestroyView()
    }

    override fun onDestroy() {
        bassEditor?.close()
        bassEditor = null
        highFrequencyEditor?.close()
        highFrequencyEditor = null
        fieldEditor?.close()
        fieldEditor = null
        diffEditor?.close()
        diffEditor = null
        crossfeedEditor?.close()
        crossfeedEditor = null
        reverbEditor?.close()
        reverbEditor = null
        tubeEditor?.close()
        analogEditor?.close()
        dynamicEditor?.close()
        tubeEditor = null
        analogEditor = null
        dynamicEditor = null
        spectrumDetails = null
        super.onDestroy()
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply {
            isNestedScrollingEnabled = professionalMode
            itemAnimator = null
            if (compactLinkedControls)
                addItemDecoration(LiveprogFullWidthDividers(requireContext()))
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        if (compactLinkedControls)
            HighFrequencyCompactPreferenceAdapter(preferenceScreen)
        else RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    private fun effectIcon(key: String): Int = when (key) {
        "v_spectrum" -> R.drawable.ic_effect_harmonic_exciter_24
        "v_field" -> R.drawable.ic_effect_stereo_shape_24
        "v_diff" -> R.drawable.ic_effect_channel_delay_24
        "v_reverb" -> R.drawable.ic_effect_viper_reverb_24
        "v_dynamic" -> R.drawable.ic_effect_dynamic_bass_shape_24
        "v_tube" -> R.drawable.ic_effect_tube_softening_24
        "v_bass" -> R.drawable.ic_effect_bass_shape_24
        "v_clarity" -> R.drawable.ic_effect_clarity_24
        "v_cure" -> R.drawable.ic_effect_headphone_crossfeed_24
        "v_analog" -> R.drawable.ic_effect_harmonic_color_24
        else -> error("Unknown ViPER effect: $key")
    }

    companion object {
        private const val ARG_GROUP = "viper_group"
        private const val STATE_EXPANDED = "viper_expanded"

        fun newInstance(groupKey: String, professionalMode: Boolean = false) = ViperEffectPreferenceFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_GROUP, groupKey)
                putBoolean(ProfessionalEffectActivity.ARG_PROFESSIONAL_MODE, professionalMode)
            }
        }
    }
}

private val SPECTRUM_DETAIL_KEYS = setOf(
    "high_frequency_help", "high_frequency_actual", "high_frequency_advanced_actual",
    "high_frequency_nonlinear", "high_frequency_oversampling_help", "high_frequency_dynamics_help",
)

/** The source notes stay with their original editors, including current runtime descriptions. */
private val CLARITY_DETAIL_KEYS = setOf(
    "high_frequency_help", "high_frequency_actual", "high_frequency_advanced_actual",
    "high_frequency_dynamics_help", "high_frequency_delays",
)

private val BASS_DETAIL_KEYS = setOf("bass_viper_help")

private class HighFrequencyDetailsPreference(context: Context, private val group: PreferenceGroup,
    groupKey: String) : RuntimeStatusPreference(context) {
    private val detailKeys = when (groupKey) {
        "v_spectrum" -> SPECTRUM_DETAIL_KEYS
        "v_bass" -> BASS_DETAIL_KEYS
        else -> CLARITY_DETAIL_KEYS
    }
    private val instructionKeys = when (groupKey) {
        "v_spectrum" -> setOf(HighFrequencyParameters.LP_AUTO, "high_frequency_audition")
        "v_bass" -> emptySet()
        else -> HighFrequencyAdvancedParameters.switches.toSet()
    }
    private var expanded = false
    init {
        key = when (groupKey) {
            "v_spectrum" -> "spectrum_compact_details"
            "v_bass" -> "bass_compact_details"
            else -> "clarity_compact_details"
        }
        title = "参数说明"
        order = 2000
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        layoutResource = R.layout.preference_liveprog_compact_row
        setOnPreferenceClickListener {
            expanded = !expanded
            refreshIfExpanded()
            if (!expanded) summary = null
            notifyChanged()
            true
        }
    }

    fun refreshIfExpanded() {
        if (!expanded) return
        val parts = (0 until group.preferenceCount).map(group::getPreference)
            .filter { it.key.orEmpty() in detailKeys || it.key.orEmpty() in instructionKeys }
            .sortedBy { it.order }
            .mapNotNull { note ->
                note.summary?.toString()?.takeIf(String::isNotBlank)?.let { body ->
                    listOfNotNull(note.title?.toString()?.takeIf(String::isNotBlank), body).joinToString("\n")
                }
            }.distinct()
        val next = parts.joinToString("\n\n")
        if (summary?.toString() != next) summary = next
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        refreshIfExpanded()
        super.onBindViewHolder(holder)
        // The whole row toggles; keep the same unobstructed right edge as the compact DSP list.
        holder.findViewById(R.id.liveprog_row_action)?.visibility = View.GONE
        holder.itemView.contentDescription = "参数说明，${if (expanded) "已展开，点按收起" else "已收起，点按展开"}"
    }
}

/** Selected linked professional pages only: retain original integer controls and all callbacks. */
@Suppress("RestrictedApi")
private class HighFrequencyCompactPreferenceAdapter(private val group: PreferenceGroup) : PreferenceGroupAdapter(group) {
    init {
        normalizeSwitches(group)
        ParameterHelp.preparePreferences(group)
    }

    private fun normalizeSwitches(preference: Preference) {
        if (preference is SwitchPreferenceCompat && preference.widgetLayoutResource != R.layout.preference_materialswitch)
            preference.widgetLayoutResource = R.layout.preference_materialswitch
        if (preference is PreferenceGroup) repeat(preference.preferenceCount) {
            normalizeSwitches(preference.getPreference(it))
        }
    }

    override fun onPreferenceHierarchyChange(preference: Preference) {
        normalizeSwitches(group)
        super.onPreferenceHierarchyChange(preference)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position) ?: return
        if (preference is SwitchPreferenceGroup) return
        ParameterHelp.bindPreference(preference, holder)
        fun text(view: View?, size: Float) {
            (view as? TextView)?.apply {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                includeFontPadding = false
                isSingleLine = false
                maxLines = Int.MAX_VALUE
                ellipsize = null
            }
        }
        text(holder.findViewById(android.R.id.title), 14f)
        text(holder.findViewById(android.R.id.summary), 13f)
        val density = holder.itemView.resources.displayMetrics.density
        fun dp(value: Int) = (density * value + .5f).toInt()
        if (preference is BassNumericPreference || preference is HighFrequencyOversamplingPreference || preference is ViperSliderPreference) {
            holder.itemView.setPaddingRelative(dp(16), dp(4), dp(16), dp(4))
            (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.topMargin != 0 || params.bottomMargin != 0) {
                    params.topMargin = 0; params.bottomMargin = 0; holder.itemView.layoutParams = params
                }
            }
            holder.findViewById(R.id.slider_help)?.let { help ->
                text(help, 13f)
                help.layoutParams = help.layoutParams.apply { width = dp(28); height = dp(28) }
            }
            listOf(R.id.bass_decrement, R.id.bass_increment, R.id.slider_decrease, R.id.slider_increase).forEach { id ->
                (holder.findViewById(id) as? TextView)?.apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    minWidth = 0; minimumWidth = 0; minHeight = dp(40)
                    layoutParams = layoutParams.apply { width = dp(36); height = dp(40) }
                }
            }
            holder.findViewById(R.id.seekbar)?.let { slider ->
                slider.layoutParams = slider.layoutParams.apply { height = dp(40) }
            }
            holder.findViewById(R.id.seekbar_value)?.let { number ->
                text(number, 14f)
                (number as? TextView)?.apply { minHeight = dp(36); maxLines = 2 }
                number.layoutParams = number.layoutParams.apply { width = dp(84); height = ViewGroup.LayoutParams.WRAP_CONTENT }
                number.setPaddingRelative(dp(4), dp(5), dp(4), dp(5))
                (number as? MaterialButton)?.apply {
                    cornerRadius = dp(8); strokeWidth = dp(1)
                    insetTop = 0; insetBottom = 0; minWidth = 0; minimumWidth = 0
                }
            }
        }
        if (preference is SwitchPreferenceCompat) {
            val label = holder.findViewById(android.R.id.title) as? TextView
            val toggle = holder.findViewById(R.id.switchWidget) as? com.google.android.material.materialswitch.MaterialSwitch
            if (label != null && toggle != null) ParameterSwitchUi.configure(toggle, label)
            fun clearSwitchSurface(view: View) {
                if (view is CompoundButton) { view.background = null; view.foreground = null }
                if (view is ViewGroup) repeat(view.childCount) { clearSwitchSurface(view.getChildAt(it)) }
            }
            holder.itemView.background = null; holder.itemView.foreground = null
            clearSwitchSurface(holder.itemView)
        } else holder.itemView.background = ContextCompat.getDrawable(preference.context, R.drawable.ripple_rounded)
    }

    override fun onViewRecycled(holder: PreferenceViewHolder) {
        fun release(view: View) {
            (view.getTag(R.id.button_press_repeat_binding) as? ButtonPressRepeat)?.dispose()
            if (view is Slider) {
                (view.getTag(R.id.seekbar) as? PreferenceValueSync)?.releaseBoundView()
                SliderDoubleTapReset.clear(view)
                view.clearOnChangeListeners(); view.clearOnSliderTouchListeners()
                view.setTag(R.id.seekbar, null)
            }
            if (view is ViewGroup) repeat(view.childCount) { release(view.getChildAt(it)) }
        }
        release(holder.itemView)
        super.onViewRecycled(holder)
    }
}
