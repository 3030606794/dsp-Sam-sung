package me.timschneeberger.rootlessjamesdsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.XmlRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.appbar.AppBarLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.fusion.DdcPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicBassPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.ProfessionalRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.TonePreferenceFragment
import me.timschneeberger.rootlessjamesdsp.fusion.ViperEffectPreferenceFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.ui.ProfessionalTitleBar
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** A second presentation of the original effect fragment, using its existing parameter store. */
class ProfessionalEffectActivity : BaseActivity() {
    private lateinit var fixedHeader: FrameLayout
    private lateinit var scrollingHeader: FrameLayout
    private lateinit var scrollingAppBar: AppBarLayout
    private var currentHeader: View? = null
    private var headerPinned = true
    private var reloadPending = false
    private lateinit var titleBar: ProfessionalTitleBar
    private lateinit var effectTitle: TextView
    private lateinit var effectSwitch: SpringMaterialSwitch
    private var bassGraphActionsOwner: View? = null
    private var bassGraphActions: List<View> = emptyList()
    private var effectGroup: SwitchPreferenceGroup? = null
    private var updatingEffectSwitch = false
    private var effectPreferences: SharedPreferences? = null
    private val effectPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        effectGroup?.takeIf { key == null || key == it.key }?.let {
            it.syncFromPreferences()
            refreshEffectSwitch()
        }
    }
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) reloadPreferences()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        val toolbar = MaterialToolbar(this)
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        titleBar = ProfessionalTitleBar.install(toolbar, if (usesCompactBassTitle()) compactBassTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            else "${intent.getStringExtra(EXTRA_TITLE).orEmpty()} · 专业")
        if (usesCompactBassTitle()) {
            titleBar.configureBassCompact()
            titleBar.setTrailingActions(bassGraphActions)
        }
        effectTitle = titleBar.titleLabel.apply {
            setOnLongClickListener {
                effectGroup?.let { ParameterHelp.show(this@ProfessionalEffectActivity, it.title?.toString().orEmpty()) }
                true
            }
        }
        effectSwitch = (layoutInflater.inflate(R.layout.preference_materialswitch, titleBar, false) as SpringMaterialSwitch).apply {
            isSaveEnabled = false
            springEnabled = true
            scaleX = 46f / 52f
            scaleY = 46f / 52f
            setOnCheckedChangeListener { _, checked ->
                if (!updatingEffectSwitch) {
                    val group = effectGroup
                    if (group != null && group.isEnabled && group.isSelectable && group.callChangeListener(checked)) {
                        group.setValue(checked)
                    } else {
                        refreshEffectSwitch()
                    }
                }
            }
        }
        titleBar.setSwitch(effectSwitch)
        fixedHeader = createChartHeaderContainer().apply { visibility = View.GONE }
        root.addView(fixedHeader, LinearLayout.LayoutParams(-1, -2))
        // Give the preference RecyclerView a bounded viewport. A wrap_content list inside
        // NestedScrollView eagerly creates every professional row and remeasures the whole
        // screen when live status text changes, defeating recycling while scrolling.
        val body = CoordinatorLayout(this)
        scrollingAppBar = AppBarLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            elevation = 0f
            outlineProvider = null
        }
        scrollingHeader = createChartHeaderContainer().apply { visibility = View.GONE }
        scrollingAppBar.addView(scrollingHeader, AppBarLayout.LayoutParams(-1, -2).apply {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
        })
        body.addView(scrollingAppBar, CoordinatorLayout.LayoutParams(-1, -2))
        body.addView(FragmentContainerView(this).apply { id = R.id.professional_effect_content },
            CoordinatorLayout.LayoutParams(-1, -1).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setPinnedHeader(currentHeader, headerPinned)
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        effectGroup?.let(::bindEffectHeader)
        if (savedInstanceState == null) replacePreferences()
        registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        // Restored 1088 pages own their original opt-in telemetry subscriptions.
        if (!uses1088RestoredVisualization() && intent.getStringExtra(EXTRA_KIND) !in setOf(KIND_CREATIVE, KIND_EXPANSION))
            ProfessionalRuntimeMonitor.bind(this, this, modules = runtimeModules())
    }

    private fun createChartHeaderContainer(): FrameLayout =
        if (intent.getStringExtra(EXTRA_KIND) in setOf(KIND_DYNAMIC_BASS, KIND_CREATIVE, KIND_EXPANSION) || uses1088RestoredVisualization() || usesCompactPresetCrossfeedTitle() || usesCompactHeadphoneCrossfeedTitle() || usesCompactReverbTitle() || usesCompactRoomReverbTitle() || usesCompactTubeTitle() || usesCompactAnalogTitle() || usesCompactTimbreTitle() || usesCompactDynamicTitle() || usesCompactCompanderTitle() || usesCompactToneTitle() || usesCompactDdcTitle() || usesCompactConvolutionTitle() || usesCompactLiveprogTitle()) FrameLayout(this)
        else me.timschneeberger.rootlessjamesdsp.ui.HalfScreenHeaderFrame(this)

    private fun uses1088RestoredVisualization(): Boolean =
        (intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
            intent.getStringExtra(EXTRA_GROUP) in setOf("v_clarity", "v_field", "v_diff")) || usesCompactWidthTitle()

    private fun usesCompactWidthTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == "dsp_stereowide"

    private fun usesCompactPresetCrossfeedTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_CROSSFEED

    private fun usesCompactHeadphoneCrossfeedTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
        intent.getStringExtra(EXTRA_GROUP) == "v_cure"

    private fun usesCompactReverbTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
        intent.getStringExtra(EXTRA_GROUP) == "v_reverb"

    private fun usesCompactRoomReverbTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_REVERB

    private fun usesCompactTubeTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
        intent.getStringExtra(EXTRA_GROUP) == "v_tube"

    private fun usesCompactAnalogTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
        intent.getStringExtra(EXTRA_GROUP) == "v_analog"

    private fun usesCompactTimbreTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_TUBE

    private fun usesCompactDynamicTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_VIPER &&
        intent.getStringExtra(EXTRA_GROUP) == "v_dynamic"

    private fun usesCompactCompanderTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_COMPANDER

    private fun usesCompactToneTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_TONE

    private fun usesCompactDdcTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_DDC

    private fun usesCompactConvolutionTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_CONVOLVER

    private fun usesCompactLiveprogTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP &&
        intent.getStringExtra(EXTRA_NAMESPACE) == Constants.PREF_LIVEPROG

    private fun usesCompactBassTitle(): Boolean = intent.getStringExtra(EXTRA_KIND) in setOf(KIND_DYNAMIC_BASS, KIND_CREATIVE, KIND_EXPANSION) ||
        (intent.getStringExtra(EXTRA_KIND) == KIND_VIPER && intent.getStringExtra(EXTRA_GROUP) in setOf("v_bass", "v_clarity", "v_spectrum", "v_field", "v_diff")) || usesCompactWidthTitle() || usesCompactPresetCrossfeedTitle() || usesCompactHeadphoneCrossfeedTitle() || usesCompactReverbTitle() || usesCompactRoomReverbTitle() || usesCompactTubeTitle() || usesCompactAnalogTitle() || usesCompactTimbreTitle() || usesCompactDynamicTitle() || usesCompactCompanderTitle() || usesCompactToneTitle() || usesCompactDdcTitle() || usesCompactConvolutionTitle() || usesCompactLiveprogTitle()

    private fun compactBassTitle(value: CharSequence): String {
        val title = value.toString().removeSuffix(" · 专业").removeSuffix("·专业").trim()
        return if (intent.getStringExtra(EXTRA_KIND) == KIND_CREATIVE &&
            intent.getStringExtra(EXTRA_CREATIVE) == CreativeEffectsKind.SHIMMER.name) "闪烁混响" else title
    }

    /** Low-frequency dashboards supply their original action views; no switch is rebuilt here. */
    fun setBassGraphActions(owner: View, actions: List<View>) {
        if (!usesCompactBassTitle()) return
        bassGraphActionsOwner = owner
        bassGraphActions = actions.toList()
        if (::titleBar.isInitialized) titleBar.setTrailingActions(bassGraphActions)
    }

    /** Old fragment cleanup cannot remove the replacement dashboard's actions. */
    fun clearBassGraphActions(owner: View) {
        if (bassGraphActionsOwner !== owner) return
        bassGraphActionsOwner = null
        bassGraphActions = emptyList()
        if (::titleBar.isInitialized) titleBar.setTrailingActions(emptyList())
    }

    private fun runtimeModules(): Set<ProfessionalRuntimeMonitor.Module> = when (intent.getStringExtra(EXTRA_KIND)) {
        KIND_GROUP -> ProfessionalRuntimeMonitor.Module.forPreferences(intent.getIntExtra(EXTRA_XML, 0))
        KIND_VIPER -> ProfessionalRuntimeMonitor.Module.forViper(intent.getStringExtra(EXTRA_GROUP).orEmpty())
        KIND_TONE -> setOf(ProfessionalRuntimeMonitor.Module.TONE)
        KIND_DDC -> setOf(ProfessionalRuntimeMonitor.Module.DDC)
        KIND_DYNAMIC_BASS -> setOf(ProfessionalRuntimeMonitor.Module.BASS)
        else -> emptySet()
    }

    /** The original preference remains the sole owner of effect state and validation. */
    fun bindEffectHeader(group: SwitchPreferenceGroup) {
        effectGroup = group
        if (!::effectSwitch.isInitialized) return
        val preferences = group.preferenceManager?.sharedPreferences
        if (effectPreferences !== preferences) {
            effectPreferences?.unregisterOnSharedPreferenceChangeListener(effectPreferenceListener)
            effectPreferences = preferences
            preferences?.registerOnSharedPreferenceChangeListener(effectPreferenceListener)
        }
        titleBar.setIcon(group.icon?.constantState?.newDrawable(resources)?.mutate() ?: group.icon)
        effectTitle.text = if (usesCompactBassTitle()) compactBassTitle(group.title ?: "") else "${group.title?.toString().orEmpty()} · 专业"
        effectSwitch.contentDescription = group.title
        refreshEffectSwitch()
    }

    private fun refreshEffectSwitch() {
        val group = effectGroup ?: return
        if (!::effectSwitch.isInitialized) return
        effectSwitch.isEnabled = group.isEnabled && group.isSelectable
        if (effectSwitch.isChecked != group.isChecked) {
            updatingEffectSwitch = true
            try { effectSwitch.setCheckedImmediately(group.isChecked) }
            finally { updatingEffectSwitch = false }
        }
    }

    /** Move a single chart/header between the fixed area and the scroll area's first slot. */
    fun setPinnedHeader(view: View?, pinned: Boolean) {
        headerPinned = pinned
        if (!::fixedHeader.isInitialized || !::scrollingHeader.isInitialized) { currentHeader = view; return }
        val target = if (pinned) fixedHeader else scrollingHeader
        if (view != null && currentHeader === view && view.parent === target) return
        currentHeader?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentHeader = view
        fixedHeader.visibility = View.GONE
        scrollingHeader.visibility = View.GONE
        scrollingAppBar.setExpanded(true, false)
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            target.addView(view, FrameLayout.LayoutParams(-1, -2))
            target.visibility = View.VISIBLE
        }
    }

    /** Ignore cleanup from an older fragment when its replacement already owns the header. */
    fun clearPinnedHeader(view: View) {
        if (currentHeader === view) setPinnedHeader(null, true)
    }

    fun reloadPreferences() {
        if (supportFragmentManager.isStateSaved) { reloadPending = true; return }
        reloadPending = false
        replacePreferences()
    }

    private fun replacePreferences() {
        val fragment = when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_GROUP -> {
                val name = intent.getStringExtra(EXTRA_NAMESPACE)
                val xml = intent.getIntExtra(EXTRA_XML, 0)
                if (name.isNullOrBlank() || xml == 0) { finish(); return }
                PreferenceGroupFragment.newInstance(name, xml, professionalMode = true)
            }
            KIND_VIPER -> ViperEffectPreferenceFragment.newInstance(
                intent.getStringExtra(EXTRA_GROUP).orEmpty(), professionalMode = true)
            KIND_TONE -> TonePreferenceFragment()
            KIND_DDC -> DdcPreferenceFragment()
            KIND_DYNAMIC_BASS -> DynamicBassPreferenceFragment()
            KIND_EXPANSION -> {
                val kind = runCatching { ExpansionEffectsKind.valueOf(intent.getStringExtra(EXTRA_EXPANSION).orEmpty()) }.getOrNull()
                if (kind == null) { finish(); return }
                ExpansionEffectPreferenceFragment.newInstance(kind)
            }
            KIND_CREATIVE -> {
                val kind = runCatching { CreativeEffectsKind.valueOf(intent.getStringExtra(EXTRA_CREATIVE).orEmpty()) }.getOrNull()
                if (kind == null) { finish(); return }
                CreativeEffectPreferenceFragment.newInstance(kind)
            }
            else -> { finish(); return }
        }
        fragment.arguments = (fragment.arguments ?: Bundle()).apply { putBoolean(ARG_PROFESSIONAL_MODE, true) }
        supportFragmentManager.beginTransaction().replace(R.id.professional_effect_content, fragment).commit()
    }

    override fun onResume() {
        super.onResume()
        if (reloadPending) reloadPreferences()
    }

    override fun onDestroy() {
        effectPreferences?.unregisterOnSharedPreferenceChangeListener(effectPreferenceListener)
        effectSwitch.setOnCheckedChangeListener(null)
        effectGroup = null
        unregisterLocalReceiver(presetReceiver)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        const val ARG_PROFESSIONAL_MODE = "professionalMode"
        private const val EXTRA_KIND = "professional.kind"
        private const val EXTRA_TITLE = "professional.title"
        private const val EXTRA_NAMESPACE = "professional.preferences"
        private const val EXTRA_XML = "professional.xml"
        private const val EXTRA_GROUP = "professional.viperGroup"
        private const val KIND_GROUP = "group"
        private const val KIND_VIPER = "viper"
        private const val KIND_TONE = "tone"
        private const val KIND_DDC = "ddc"
        private const val KIND_DYNAMIC_BASS = "dynamicBass"
        private const val KIND_CREATIVE = "creative"
        private const val KIND_EXPANSION = "expansion"
        private const val EXTRA_EXPANSION = "professional.expansionKind"
        private const val EXTRA_CREATIVE = "professional.creativeKind"

        fun isProfessional(fragment: Fragment) = fragment.arguments?.getBoolean(ARG_PROFESSIONAL_MODE, false) == true

        fun fromContext(context: Context): ProfessionalEffectActivity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is ProfessionalEffectActivity) return current
                val next = current.baseContext
                if (next === current) break
                current = next
            }
            return current as? ProfessionalEffectActivity
        }

        private fun intent(context: Context, kind: String, title: CharSequence?) =
            Intent(context, ProfessionalEffectActivity::class.java).putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title?.toString().orEmpty())

        fun openPreferenceGroup(context: Context, preferencesName: String, @XmlRes xmlRes: Int, title: CharSequence?) {
            context.startActivity(intent(context, KIND_GROUP, title).putExtra(EXTRA_NAMESPACE, preferencesName).putExtra(EXTRA_XML, xmlRes))
        }
        fun openViper(context: Context, groupKey: String, title: CharSequence?) {
            context.startActivity(intent(context, KIND_VIPER, title).putExtra(EXTRA_GROUP, groupKey))
        }
        fun openTone(context: Context, title: CharSequence?) { context.startActivity(intent(context, KIND_TONE, title)) }
        fun openDdc(context: Context, title: CharSequence?) { context.startActivity(intent(context, KIND_DDC, title)) }
        fun openDynamicBass(context: Context, title: CharSequence?) { context.startActivity(intent(context, KIND_DYNAMIC_BASS, title)) }
        fun openExpansion(context: Context, kind: ExpansionEffectsKind, title: CharSequence? = kind.title) {
            context.startActivity(intent(context, KIND_EXPANSION, title).putExtra(EXTRA_EXPANSION, kind.name))
        }
        fun openCreative(context: Context, kind: CreativeEffectsKind, title: CharSequence? = kind.title) {
            context.startActivity(intent(context, KIND_CREATIVE, title).putExtra(EXTRA_CREATIVE, kind.name))
        }
    }
}
