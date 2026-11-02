package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.children
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.fragment.FileLibraryDialogFragment
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Keeps the original DDC power and file rows; expansion never enables the effect. */
class DdcPreferenceFragment : PreferenceFragmentCompat(), KoinComponent {
    private val professionalMode get() = ProfessionalEffectActivity.isProfessional(this)
    private val appPrefs: Preferences.App by inject()
    private var editor: DdcEditor? = null
    private var dashboard: DdcDashboardCoordinator? = null
    private lateinit var group: SwitchPreferenceGroup
    private var lastExpanded = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val appListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == getString(R.string.key_appearance_show_icons)) updateIcons()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED)
                (parentFragment as? DspFragment)?.restartFragment(id, DdcPreferenceFragment())
        }
    }
    private val scroll = ViewTreeObserver.OnScrollChangedListener {
        val card = view
        if (card != null && (!card.isShown || (card.isLaidOut && !card.getGlobalVisibleRect(Rect())))) editor?.stopAudition()
    }
    private fun updateIcons() = group.setIsIconVisible(appPrefs.get<Boolean>(R.string.key_appearance_show_icons))
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_DDC
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        buildScreen(savedInstanceState?.getBoolean("ddc_expanded", false) ?: false)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        appPrefs.registerOnSharedPreferenceChangeListener(appListener)
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }
    private fun buildScreen(expanded: Boolean) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        addPreferencesFromResource(R.xml.dsp_ddc_preferences)
        group = requireNotNull(findPreference(getString(R.string.key_ddc_enable)))
        group.setProfessionalMode(professionalMode)
        group.setExpanded(expanded)
        lastExpanded = expanded
        updateIcons()
        val coreKeys = group.children.mapNotNull { it.key }.toSet()
        editor = DdcEditor(requireContext(), group, this)
        if (!professionalMode) {
            group.setHomeVisibleKeys(coreKeys)
            group.onProfessionalClick = { ProfessionalEffectActivity.openDdc(requireContext(), group.title) }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // A retained fragment can lose only its view. Rebuild its closed callbacks before
        // VIEW_CREATED, while registering the new document result handlers is still legal.
        if (editor == null) buildScreen(lastExpanded)
        return super.onCreateView(inflater, container, savedInstanceState)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ddc_expanded", group.isExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply { itemAnimator = null; isNestedScrollingEnabled = professionalMode }
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> = RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is FileLibraryPreference && preference.isVdc()) {
            val tag = "ddc_file_library"
            if (parentFragmentManager.findFragmentByTag(tag) == null) {
                FileLibraryDialogFragment.newInstance(preference.key).apply {
                    setTargetFragment(this@DdcPreferenceFragment, 0)
                }.show(parentFragmentManager, tag)
            }
        } else super.onDisplayPreferenceDialog(preference)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.viewTreeObserver.addOnScrollChangedListener(scroll)
        if (professionalMode && activity is ProfessionalEffectActivity) dashboard = DdcDashboardCoordinator(this)
    }
    override fun onPause() { dashboard?.onPause(); editor?.stopAudition(); super.onPause() }
    override fun onResume() {
        super.onResume()
        dashboard?.onResume()
        preferenceScreen?.let { me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
    }
    override fun onDestroyView() {
        dashboard?.close(); dashboard = null
        lastExpanded = group.isExpanded
        view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scroll)
        editor?.close(); editor = null
        super.onDestroyView()
    }
    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        appPrefs.unregisterOnSharedPreferenceChangeListener(appListener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
