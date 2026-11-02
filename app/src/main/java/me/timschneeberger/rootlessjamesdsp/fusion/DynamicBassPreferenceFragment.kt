package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogFullWidthDividers
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.roundToInt

class DynamicBassPreferenceFragment : PreferenceFragmentCompat() {
    private val professionalMode get() = ProfessionalEffectActivity.isProfessional(this)
    lateinit var effectHeader: SwitchPreferenceGroup
        private set
    private lateinit var preferences: SharedPreferences
    private lateinit var status: SharedPreferences
    private lateinit var runtime: Preference
    private var dashboard: AdaptiveBassDashboardCoordinator? = null
    private var detailsExpanded = false
    private var supported = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
        synchronizeLegacyGain()
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        renderRuntime()
    }
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (supported != BassController.supported(requireContext())) build(effectHeader.isExpanded)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                // Use the fragment's owning context. A broadcast dispatcher may retain another
                // application context; the preference manager and controller must read one store.
                preferences = BassController.preferences(requireContext(), false)
                build(false)
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            } else renderRuntime()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_BASS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        preferences = BassController.preferences(requireContext(), false)
        status = BassController.statusPreferences(requireContext())
        detailsExpanded = savedInstanceState?.getBoolean(STATE_DETAILS_EXPANDED, false) ?: false
        build(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        status.registerOnSharedPreferenceChangeListener(statusListener)
        requireContext().registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED); addAction(BassController.STATUS_ACTION)
        })
    }

    private fun build(expanded: Boolean) {
        val ctx = requireContext()
        BassParameters.migrate(preferences, false)
        supported = BassController.supported(ctx)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        effectHeader = SwitchPreferenceGroup(ctx).apply {
            key = getString(R.string.key_bass_enable)
            title = getString(R.string.bass_enable)
            setIcon(R.drawable.ic_effect_adaptive_bass_24)
            isIconSpaceReserved = false
            setIsIconVisible(true)
            setDefaultValue(false)
        }
        preferenceScreen.addPreference(effectHeader)
        effectHeader.setProfessionalMode(professionalMode)
        val packet = BassParameters.dynamicPacket(preferences.all)
        if (supported) effectHeader.addPreference(BassNumericPreference(ctx, BassParameters.maxGain, packet[0]))
        else {
            val wholeDb = (packet[0] / 10.0).roundToInt()
            effectHeader.addPreference(BassNumericPreference(ctx,
                BassParameters.Control("bass_legacy_gain_ui", "最大增益", 0, 15, 5, unit = "dB"), wholeDb).apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, candidate ->
                    val gain = candidate as Int
                    preferences.edit().putFloat(BassParameters.LEGACY_MAX_GAIN, gain.toFloat())
                        .putInt(BassParameters.MAX_GAIN, gain * 10).apply()
                    true
                }
            })
            effectHeader.addPreference(Preference(ctx).apply {
                key = "bass_legacy_precision"; setSummary(R.string.bass_driver_legacy_gain)
                isSelectable = false; isIconSpaceReserved = false
                if (professionalMode) {
                    title = "当前驱动精度"
                    layoutResource = R.layout.preference_liveprog_compact_row
                }
            })
        }
        BassParameters.dynamic.drop(1).forEachIndexed { index, control ->
            effectHeader.addPreference(BassNumericPreference(ctx, control, packet[index + 1]).apply {
                isEnabled = supported
                setOnPreferenceChangeListener { _, candidate ->
                    BassParameters.acceptsDynamic(preferences.all, control, candidate as Int).also {
                        if (!it) Toast.makeText(ctx, "跟踪下限必须小于上限", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        val help = Preference(ctx).apply {
            key = "bass_dynamic_help"; setSummary(R.string.bass_dynamic_help)
            isSelectable = false; isIconSpaceReserved = false
        }
        effectHeader.addPreference(help)
        if (professionalMode) {
            effectHeader.setChildVisibilityFilter { it.key != "bass_dynamic_help" }
            effectHeader.addPreference(AdaptiveBassDetailsPreference(ctx, help.summary?.toString().orEmpty(),
                expanded = { detailsExpanded }, toggle = { detailsExpanded = !detailsExpanded }))
        }
        if (!supported) effectHeader.addPreference(Preference(ctx).apply {
            key = "bass_dynamic_driver"; setTitle(R.string.bass_driver_required)
            isSelectable = false; isIconSpaceReserved = false
            if (professionalMode) layoutResource = R.layout.preference_liveprog_compact_row
        })
        runtime = Preference(ctx).apply {
            key = "bass_runtime"; setTitle(R.string.bass_runtime_title)
            isSelectable = false; isIconSpaceReserved = false
        }
        if (!professionalMode) effectHeader.addPreference(runtime)
        if (!professionalMode) {
            effectHeader.setHomeVisibleKeys(BassParameters.dynamic.take(3).map { it.key }.toSet() + "bass_legacy_gain_ui")
            effectHeader.onProfessionalClick = { ProfessionalEffectActivity.openDynamicBass(ctx, effectHeader.title) }
        }
        effectHeader.setExpanded(expanded)
        renderRuntime()
    }

    private fun renderRuntime() {
        if (professionalMode) {
            dashboard?.refreshConfiguration()
            return
        }
        if (!::runtime.isInitialized) return
        val state = if (!preferences.getBoolean(getString(R.string.key_bass_enable), false)) BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.OFF)
            else if (!supported) BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNSUPPORTED) else BassController.runtime
        runtime.summary = when (state.state) {
            BassRuntimeStatus.State.ACTIVE -> getString(R.string.bass_runtime_value,
                String.format(Locale.ROOT, "%.2f", state.gainHundredths!! / 100.0),
                String.format(Locale.ROOT, "%.2f", state.frequencyHundredths!! / 100.0))
            BassRuntimeStatus.State.OFF -> getString(R.string.bass_runtime_off)
            BassRuntimeStatus.State.IDLE -> getString(R.string.bass_runtime_idle)
            BassRuntimeStatus.State.UNKNOWN -> getString(R.string.bass_runtime_unknown)
            BassRuntimeStatus.State.UNSUPPORTED -> getString(R.string.bass_runtime_unsupported)
            BassRuntimeStatus.State.MULTIPLE -> getString(R.string.bass_runtime_multiple)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (professionalMode && activity is ProfessionalEffectActivity) {
            setDivider(null)
            dashboard = AdaptiveBassDashboardCoordinator(this)
        }
    }

    private fun synchronizeLegacyGain() {
        if (!::preferences.isInitialized) return
        val row = findPreference<BassNumericPreference>("bass_legacy_gain_ui") ?: return
        val next = (BassParameters.dynamicPacket(preferences.all)[0] / 10.0).roundToInt()
        // This fallback view intentionally has no persistence; actual driver keys stay intact.
        if (row.value != next) row.value = next
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, effectHeader.isExpanded)
        outState.putBoolean(STATE_DETAILS_EXPANDED, detailsExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        synchronizeLegacyGain()
        dashboard?.onResume()
    }
    override fun onPause() {
        dashboard?.onPause()
        super.onPause()
    }
    override fun onDestroyView() {
        dashboard?.close(); dashboard = null
        super.onDestroyView()
    }
    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply {
            isNestedScrollingEnabled = professionalMode; itemAnimator = null
            if (professionalMode) addItemDecoration(LiveprogFullWidthDividers(requireContext()))
        }
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        if (professionalMode) AdaptiveBassCompactPreferenceAdapter(preferenceScreen)
        else RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        status.unregisterOnSharedPreferenceChangeListener(statusListener)
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
    companion object {
        private const val STATE_EXPANDED = "bass_dynamic_expanded"
        private const val STATE_DETAILS_EXPANDED = "bass_dynamic_details_expanded"
    }
}

/** Keep the original help text available without taking space from the native controls. */
private class AdaptiveBassDetailsPreference(context: Context, private val description: String,
    private val expanded: () -> Boolean, private val toggle: () -> Unit) : Preference(context) {
    init {
        key = "bass_dynamic_compact_details"
        title = "参数说明"
        order = 2000
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        layoutResource = R.layout.preference_liveprog_compact_row
        summary = description.takeIf { expanded() }
        setOnPreferenceClickListener {
            toggle()
            summary = description.takeIf { expanded() }
            true
        }
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.liveprog_row_action)?.visibility = View.GONE
        holder.itemView.contentDescription = "参数说明，${if (expanded()) "已展开，点按收起" else "已收起，点按展开"}"
    }
}

/** Only this professional page adopts the compact presentation; controls keep their ownership. */
@Suppress("RestrictedApi")
internal class AdaptiveBassCompactPreferenceAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {
    init { ParameterHelp.preparePreferences(group) }

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
        if (preference is BassNumericPreference) {
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
            listOf(R.id.bass_decrement, R.id.bass_increment).forEach { id ->
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
        holder.itemView.background = ContextCompat.getDrawable(preference.context, R.drawable.ripple_rounded)
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
