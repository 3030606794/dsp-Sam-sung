package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.view.ViewTreeObserver
import android.graphics.Rect
import androidx.annotation.XmlRes
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.children
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogEditorActivity
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogParamsActivity
import me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import me.timschneeberger.rootlessjamesdsp.preference.EqualizerPreference
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionEditor
import me.timschneeberger.rootlessjamesdsp.fusion.WidthEditor
import me.timschneeberger.rootlessjamesdsp.fusion.WidthDashboardCoordinator
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedDashboardCoordinator
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardCoordinator
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.roundToInt


class PreferenceGroupFragment : PreferenceFragmentCompat(), KoinComponent {
    private val prefsApp: Preferences.App by inject()
    private val professionalMode get() = ProfessionalEffectActivity.isProfessional(this)
    private val eelParser = EelParser()
    private var liveprogEditor: me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogProfessionalEditor? = null
    private var liveprogDashboard: me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogDashboardCoordinator? = null
    private var refreshLiveprogEntries: (() -> Unit)? = null
    private var recyclerView: RecyclerView? = null
    private var convolutionEditor: ConvolutionEditor? = null
    private var convolutionDashboard: me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardCoordinator? = null
    private var widthEditor: WidthEditor? = null
    private var widthDashboard: WidthDashboardCoordinator? = null
    private var presetCrossfeedDashboard: PresetCrossfeedDashboardCoordinator? = null
    private var roomReverbDashboard: RoomReverbDashboardCoordinator? = null
    private var companderDashboard: me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardCoordinator? = null
    private var timbreDashboard: me.timschneeberger.rootlessjamesdsp.fusion.TimbreDashboardCoordinator? = null
    private var companderEditor: me.timschneeberger.rootlessjamesdsp.fusion.CompanderEditor? = null
    private var timbreEditor: me.timschneeberger.rootlessjamesdsp.fusion.TimbreEditor? = null
    private var roomReverbEditor: me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbEditor? = null
    private var presetCrossfeedEditor: me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedEditor? = null
    private val convolutionScroll=ViewTreeObserver.OnScrollChangedListener {
        val card=view
        if(card!=null && (!card.isShown || (card.isLaidOut && !card.getGlobalVisibleRect(Rect())))){convolutionEditor?.stopAudition();widthEditor?.stopAudition();presetCrossfeedEditor?.stopAudition();roomReverbEditor?.stopAudition();timbreEditor?.stopAudition();companderEditor?.stopAudition()}
    }

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Script-variable commits have their own coalesced realtime update message.
            // Do not turn each variable into an unrelated full-chain configuration update.
            if (preferenceManager.sharedPreferencesName == Constants.PREF_LIVEPROG &&
                key?.startsWith("liveprog_parameter_v1:") == true) return@OnSharedPreferenceChangeListener
            preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
            context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        }

    private val listenerApp =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when(key) {
                context?.resources?.getString(R.string.key_appearance_show_icons) -> updateIconState()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_SERVICE_RELOAD_LIVEPROG) {
                refreshLiveprogEntries?.invoke()
                return
            }
            if(intent?.action == Constants.ACTION_PRESET_LOADED) {
                if (professionalMode) return
                val id = this@PreferenceGroupFragment.id
                Timber.d("Reloading group fragment for ${this@PreferenceGroupFragment.preferenceManager.sharedPreferencesName}")
                (requireParentFragment() as DspFragment).restartFragment(id, cloneInstance(this@PreferenceGroupFragment))
            }
        }
    }

    private fun updateIconState() {
        if(preferenceScreen.preferenceCount > 0) {
            (preferenceScreen.getPreference(0) as? SwitchPreferenceGroup?)
                ?.setIsIconVisible(prefsApp.get<Boolean>(R.string.key_appearance_show_icons))
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val args = requireArguments()
        preferenceManager.sharedPreferencesName = args.getString(BUNDLE_PREF_NAME)
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        addPreferencesFromResource(args.getInt(BUNDLE_XML_RES))
        val group = preferenceScreen.getPreference(0) as? SwitchPreferenceGroup
        val coreKeys = group?.children?.mapNotNull { it.key }?.toSet().orEmpty()
        group?.setProfessionalMode(professionalMode)

        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED).apply {
            if (args.getInt(BUNDLE_XML_RES) == R.xml.dsp_liveprog_preferences) addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        })

        when(args.getInt(BUNDLE_XML_RES)) {
            R.xml.dsp_tube_preferences -> {
                timbreEditor=me.timschneeberger.rootlessjamesdsp.fusion.TimbreEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup,this)
            }
            R.xml.dsp_reverb_preferences -> {
                roomReverbEditor=me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup,this)
            }
            R.xml.dsp_crossfeed_preferences -> {
                presetCrossfeedEditor=me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup,this)
            }
            R.xml.dsp_convolver_preferences -> {
                convolutionEditor=ConvolutionEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup, professionalMode = professionalMode)
            }
            R.xml.dsp_compander_preferences -> {
                // The curve remains editable in its original dialog, without a second inline Canvas.
                findPreference<CompanderPreference>(getString(R.string.key_compander_response))?.apply {
                    showInlineGraph = false
                    title = "原版七点动态曲线"
                    summary = "点击编辑原保存曲线；被专业核心或标准引擎接管时置灰，原值保留。"
                }
                findPreference<MaterialSeekbarPreference>(getString(R.string.key_compander_granularity))?.valueLabelOverride =
                    fun(it: Float): String {
                        return when(it.roundToInt()) {
                            0 -> getString(R.string.compander_granularity_very_low)
                            1 -> getString(R.string.compander_granularity_low)
                            2 -> getString(R.string.compander_granularity_medium)
                            3 -> getString(R.string.compander_granularity_high)
                            4 -> getString(R.string.compander_granularity_extreme)
                            else -> it.roundToInt().toString()
                        }
                    }
                companderEditor=me.timschneeberger.rootlessjamesdsp.fusion.CompanderEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup,this)
                // Home has only the original three basic controls and explicit quick controls, with no chart row.
                if (!professionalMode) group?.findPreference<Preference>(getString(R.string.key_compander_response))?.let {
                    group.removePreference(it)
                }
            }
            R.xml.dsp_stereowide_preferences -> {
                widthEditor=WidthEditor(requireContext(),preferenceScreen.getPreference(0) as SwitchPreferenceGroup)
            }
            R.xml.dsp_liveprog_preferences -> {
                val liveprogParams = findPreference<Preference>(getString(R.string.key_liveprog_params))
                val liveprogEdit = findPreference<Preference>(getString(R.string.key_liveprog_edit))
                val liveprogFile = findPreference<FileLibraryPreference>(getString(R.string.key_liveprog_file))

                fun updateLiveprog(newValue: String) {
                    eelParser.load(if (newValue.isBlank()) "" else FileLibraryPreference.createFullPathCompat(requireContext(), newValue))
                    val count = eelParser.properties.size
                    val filePresent = eelParser.contents != null
                    val uiUpdate = {
                        liveprogEdit?.isEnabled = filePresent

                        liveprogParams?.isEnabled = count > 0

                        try {
                            liveprogParams?.summary = if (count > 0)
                                resources.getQuantityString(R.plurals.custom_parameters, count, count)
                            else
                                getString(R.string.liveprog_additional_params_not_supported)
                        }
                        catch(ex: IllegalStateException) {
                            /* Because this lambda is executed async, it is possible that it is called
                               while the fragment is destroyed, leading to accessing a detached context */
                            Timber.d(ex)
                        }
                    }

                    if (recyclerView == null)
                        // Recycler view doesn't exist yet, directly setup the preference
                        uiUpdate()
                    else
                        // Recycler view does exist, queue on UI thread
                        recyclerView!!.post(uiUpdate)
                }

                refreshLiveprogEntries = {
                    updateLiveprog(preferenceManager.sharedPreferences?.getString(getString(R.string.key_liveprog_file), "").orEmpty())
                    liveprogFile?.refreshDescription()
                }

                liveprogFile?.summaryProvider = SummaryProvider<FileLibraryPreference> {
                    updateLiveprog(it.value)
                    if(it.value == null || it.value.isBlank() || !eelParser.isFileLoaded) {
                        getString(R.string.liveprog_no_script_selected)
                    }
                    else
                        eelParser.description
                }

                FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value)?.let {
                    updateLiveprog(it)
                }

                liveprogFile?.setOnPreferenceChangeListener { _, newValue ->
                    updateLiveprog(newValue as String)
                    true
                }

                liveprogParams?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), LiveprogParamsActivity::class.java)
                    intent.putExtra(LiveprogParamsActivity.EXTRA_TARGET_FILE, FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value))
                    startActivity(intent)
                    true
                }

                liveprogEdit?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), LiveprogEditorActivity::class.java)
                    intent.putExtra(LiveprogEditorActivity.EXTRA_TARGET_FILE, FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value))
                    startActivity(intent)
                    true
                }
                if (professionalMode && group != null) {
                    liveprogEditor = me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogProfessionalEditor(requireContext(), group) {
                        if (isAdded) {
                            @Suppress("DEPRECATION")
                            FileLibraryDialogFragment.newInstance(getString(R.string.key_liveprog_file), importImmediately = true).apply {
                                setTargetFragment(this@PreferenceGroupFragment, 0)
                            }.show(parentFragmentManager, "liveprog-import")
                        }
                    }
                }
            }
            R.xml.dsp_graphiceq_preferences -> {
                findPreference<Preference>(getString(R.string.key_geq_nodes))?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), GraphicEqualizerActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
        }

        group?.apply {
            if (!this@PreferenceGroupFragment.professionalMode) {
                // IR owns its compact core rows, including the dynamically created mix controls.
                if (args.getInt(BUNDLE_XML_RES) != R.xml.dsp_convolver_preferences) {
                    val visibleKeys = if (args.getInt(BUNDLE_XML_RES) == R.xml.dsp_stereowide_preferences)
                        coreKeys + setOf(me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters.MODE,
                            me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters.LEGACY,
                            me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters.master.key)
                    else if (args.getInt(BUNDLE_XML_RES) == R.xml.dsp_tube_preferences)
                        coreKeys + setOf(me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters.drive.key,
                            me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters.amount.key, "timbre_pro_baseline")
                    else if (args.getInt(BUNDLE_XML_RES) == R.xml.dsp_compander_preferences)
                        (coreKeys - me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters.BASE_CURVE) +
                            with(me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters) {
                                setOf(PROFESSIONAL, ENGINE, CORE_ON, MIX_ON, OUTPUT_ON,
                                    amount.key, mix.key, output.key, threshold.key)
                            }
                    else coreKeys
                    setHomeVisibleKeys(visibleKeys)
                    if (args.getInt(BUNDLE_XML_RES) == R.xml.dsp_compander_preferences)
                        setExpandedHighlightEnabled(false)
                }
                onProfessionalClick = {
                    when (args.getInt(BUNDLE_XML_RES)) {
                        R.xml.dsp_equalizer_preferences -> startActivity(Intent(requireContext(), EqualizerActivity::class.java))
                        R.xml.dsp_graphiceq_preferences -> startActivity(Intent(requireContext(), GraphicEqualizerActivity::class.java))
                        else -> ProfessionalEffectActivity.openPreferenceGroup(requireContext(),
                            requireNotNull(args.getString(BUNDLE_PREF_NAME)), args.getInt(BUNDLE_XML_RES), title)
                    }
                }
            }
        }

        updateIconState()

        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        prefsApp.registerOnSharedPreferenceChangeListener(listenerApp)
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null // Fix to prevent RecyclerView crash if group is toggled rapidly
            isNestedScrollingEnabled = professionalMode

            this@PreferenceGroupFragment.recyclerView = this
        }
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    }

    override fun onViewCreated(view: View,savedInstanceState: Bundle?) {
        super.onViewCreated(view,savedInstanceState)
        if (professionalMode && activity is ProfessionalEffectActivity) liveprogEditor?.let {
            setDivider(null)
            listView.setPadding(0, listView.paddingTop, 0, listView.paddingBottom)
            listView.addItemDecoration(me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogFullWidthDividers(requireContext()))
            liveprogDashboard = me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogDashboardCoordinator(this, it)
        }
        if(convolutionEditor!=null || widthEditor!=null || presetCrossfeedEditor!=null || roomReverbEditor!=null || timbreEditor!=null || companderEditor!=null)view.viewTreeObserver.addOnScrollChangedListener(convolutionScroll)
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_stereowide_preferences) {
            widthDashboard = WidthDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_crossfeed_preferences) {
            presetCrossfeedDashboard = PresetCrossfeedDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_reverb_preferences) {
            roomReverbDashboard = RoomReverbDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_tube_preferences) {
            timbreDashboard = me.timschneeberger.rootlessjamesdsp.fusion.TimbreDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_compander_preferences) {
            companderDashboard = me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardCoordinator(this)
        }
        if (professionalMode && activity is ProfessionalEffectActivity &&
            requireArguments().getInt(BUNDLE_XML_RES) == R.xml.dsp_convolver_preferences) {
            convolutionDashboard = me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardCoordinator(this)
        }
    }

    override fun onPause() {
        liveprogDashboard?.onPause()
        liveprogEditor?.flushParameters()
        widthDashboard?.onPause()
        presetCrossfeedDashboard?.onPause()
        roomReverbDashboard?.onPause()
        timbreDashboard?.onPause()
        companderDashboard?.onPause()
        convolutionDashboard?.onPause()
        roomReverbEditor?.stopAudition();timbreEditor?.stopAudition();companderEditor?.stopAudition()
        presetCrossfeedEditor?.stopAudition()
        widthEditor?.stopAudition()
        convolutionEditor?.stopAudition()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshLiveprogEntries?.invoke()
        liveprogEditor?.reload()
        liveprogDashboard?.onResume()
        widthDashboard?.onResume()
        presetCrossfeedDashboard?.onResume()
        roomReverbDashboard?.onResume()
        timbreDashboard?.onResume()
        companderDashboard?.onResume()
        convolutionDashboard?.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
    }

    override fun onDestroyView() {
        liveprogDashboard?.close(); liveprogDashboard = null
        widthDashboard?.close()
        widthDashboard = null
        presetCrossfeedDashboard?.close()
        presetCrossfeedDashboard = null
        roomReverbDashboard?.close()
        roomReverbDashboard = null
        timbreDashboard?.close(); timbreDashboard = null
        companderDashboard?.close(); companderDashboard = null
        convolutionDashboard?.close(); convolutionDashboard = null
        roomReverbEditor?.stopAudition();timbreEditor?.stopAudition();companderEditor?.stopAudition()
        presetCrossfeedEditor?.stopAudition()
        widthEditor?.stopAudition()
        view?.viewTreeObserver?.takeIf {it.isAlive}?.removeOnScrollChangedListener(convolutionScroll)
        convolutionEditor?.stopAudition()
        super.onDestroyView()
    }

    override fun onDestroy() {
        liveprogEditor?.close(); liveprogEditor = null
        roomReverbEditor?.close();roomReverbEditor=null
        timbreEditor?.close();timbreEditor=null
        companderEditor?.close();companderEditor=null
        presetCrossfeedEditor?.close();presetCrossfeedEditor=null
        widthEditor?.close();widthEditor=null
        convolutionEditor?.close();convolutionEditor=null
        super.onDestroy()
        requireContext().unregisterLocalReceiver(receiver)
        prefsApp.unregisterOnSharedPreferenceChangeListener(listenerApp)
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference) {
            is EqualizerPreference -> {
                startActivity(Intent(requireContext(), me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity::class.java))
            }
            is CompanderPreference -> {
                val dialogFragment = CompanderDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            }
            is FileLibraryPreference -> {
                val dialogFragment = FileLibraryDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    companion object {
        private const val BUNDLE_PREF_NAME = "preferencesName"
        private const val BUNDLE_XML_RES = "preferencesXmlRes"

        fun newInstance(preferencesName: String?, @XmlRes preferencesXmlRes: Int, professionalMode: Boolean = false): PreferenceGroupFragment {
            return PreferenceGroupFragment().apply {
                arguments = Bundle().apply {
                    putString(BUNDLE_PREF_NAME, preferencesName)
                    putInt(BUNDLE_XML_RES, preferencesXmlRes)
                    putBoolean(ProfessionalEffectActivity.ARG_PROFESSIONAL_MODE, professionalMode)
                }
            }
        }

        fun cloneInstance(fragment: PreferenceGroupFragment): PreferenceGroupFragment {
            return fragment.requireArguments().let { args ->
                 newInstance(args.getString(BUNDLE_PREF_NAME), args.getInt(BUNDLE_XML_RES),
                     args.getBoolean(ProfessionalEffectActivity.ARG_PROFESSIONAL_MODE, false))
            }
        }
    }
}
