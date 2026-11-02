package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqActivity
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqController
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqModel
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqProtocol
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRepository
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqResponseView
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRuntime
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.dp
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.dynamicNumber
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.mutedColor
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.ui.EffectIconStyle
import me.timschneeberger.rootlessjamesdsp.ui.HelpPreferences
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassControlUi
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassThemeUi
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Independent sound bank; its home header shares the existing PEQ row layout. */
class DynamicPeqHomeFragment : Fragment() {
    private var preferences: SharedPreferences? = null
    private var appearance: SharedPreferences? = null
    private var header: View? = null
    private var preview: LinearLayout? = null
    private var chart: DynamicPeqResponseView? = null
    private var status: TextView? = null
    private var toggle: SpringMaterialSwitch? = null
    private var settings: AppCompatImageButton? = null
    private var expanded = false
    private var rendering = false
    private var professionalAnimating = false
    private var state = DynamicPeqModel.defaultState()
    private var corrupt = false
    private var runtime: DynamicPeqRuntime? = null
    private var supported: Boolean? = null
    private val visibleBounds = Rect()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == DynamicPeqRepository.KEY_ENABLED || key == DynamicPeqRepository.KEY_STATE) reload()
    }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == EffectIconStyle.KEY ||
            key == context?.getString(R.string.key_appearance_show_icons)) renderAppearance()
        if (key == null || key == HelpPreferences.HIDE_QUESTIONS || key == HelpPreferences.HIDE_EXCLAMATIONS)
            view?.let(HelpPreferences::refresh)
    }
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                runtime = null
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expanded = savedInstanceState?.getBoolean(STATE_EXPANDED) ?: false
        preferences = PreferenceCache.getPreferences(requireContext(), Constants.PREF_DYNAMIC_PEQ).also {
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        appearance = EffectIconStyle.preferences(requireContext()).also {
            it.registerOnSharedPreferenceChangeListener(appearanceListener)
        }
        requireContext().registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Shared layouts keep text metrics, widget styling and header spacing identical.
        val row = inflater.inflate(R.layout.preference_switchgroup, page, false)
        header = row
        row.minimumHeight = 0
        row.setPadding(row.paddingLeft, context.dp(6), row.paddingRight, context.dp(6))
        row.background = if (GlassThemeUi.isEnabled(context)) null
            else ContextCompat.getDrawable(context, R.drawable.shape_rounded_highlight)
        row.findViewById<View>(android.R.id.summary).isVisible = false
        val title = row.findViewById<TextView>(android.R.id.title).apply {
            text = getString(R.string.dynamic_peq_title)
        }
        val help = row.findViewById<View>(R.id.effect_help)
        val showHelp = {
            ParameterHelp.show(context, getString(R.string.dynamic_peq_title),
                "每个频段独立检测信号，超过阈值时按压缩比压低；基础增益与动态压低分别设置。首页点击展开频响图，齿轮进入完整调节。",
                "最多 ${DynamicPeqModel.MAX_BANDS} 段；关闭模块后仍可查看和预先调节参数。")
        }
        help.contentDescription = "${title.text} · 参数说明"
        help.setOnClickListener { showHelp() }
        HelpPreferences.bindTitleHelp(title, help, onHelp = showHelp)
        (title.parent as? View)?.addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
            val maximum = (parent.width - parent.paddingLeft - parent.paddingRight - context.dp(24)).coerceAtLeast(1)
            if (title.maxWidth != maximum) title.maxWidth = maximum
        }

        settings = row.findViewById<AppCompatImageButton>(R.id.effect_professional).apply {
            id = R.id.dynamic_peq_home_edit
            isVisible = true
            contentDescription = "${title.text} · 专业调节"
            setOnClickListener { openEditorAnimated() }
            GlassControlUi.decorate(this)
        }
        val widgetFrame = row.findViewById<ViewGroup>(android.R.id.widget_frame)
        toggle = (inflater.inflate(R.layout.preference_materialswitch, widgetFrame, false) as SpringMaterialSwitch).apply {
            id = R.id.dynamic_peq_home_switch
            isSaveEnabled = false
            springEnabled = true
            scaleX = HOME_SWITCH_SCALE
            scaleY = HOME_SWITCH_SCALE
            contentDescription = "${title.text}开关"
            setOnCheckedChangeListener { _, enabled ->
                if (!rendering) {
                    val repository = DynamicPeqRepository(context)
                    val loaded = repository.loadResult()
                    if (!loaded.corrupt && repository.save(loaded.state.copy(enabled = enabled), synchronous = false)) {
                        DynamicPeqController.notifyChanged(context)
                    } else {
                        reload()
                        Toast.makeText(context, "无法保存开关，请进入模块检查参数", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            GlassControlUi.decorate(this)
        }
        widgetFrame.addView(toggle)
        row.setOnClickListener { setExpanded(!expanded) }
        page.addView(row)
        if (GlassThemeUi.isEnabled(context)) GlassThemeUi.decorateSurface(row, GlassThemeUi.Role.HEADER)

        preview = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = PREVIEW_TAG
            setPadding(context.dp(12), 0, context.dp(12), context.dp(6))
            val legend = TextView(context).apply {
                text = "┄ 基础   ━ 当前   ░ 最大压低"
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setTextColor(context.mutedColor())
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 10, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(22)))
            chart = DynamicPeqResponseView(context).apply {
                id = R.id.dynamic_peq_chart
                // Read-only preview: the header owns expansion and the gear owns editing.
                isClickable = false
                isFocusable = false
            }
            addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(154)))
            status = TextView(context).apply {
                id = R.id.dynamic_peq_status
                textSize = 11f
                includeFontPadding = false
                setPadding(0, context.dp(2), 0, context.dp(3))
                setTextColor(context.mutedColor())
                maxLines = 2
            }
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        page.addView(preview)
        renderAppearance()
        reload()
        renderExpansion()
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (isActive) {
                        if (previewVisible()) {
                            val appContext = requireContext().applicationContext
                            val snapshot = withContext(Dispatchers.IO) {
                                runCatching { DynamicPeqController.supported(appContext) to
                                    DynamicPeqController.currentRuntime(appContext) }.getOrElse { null to null }
                            }
                            // Collapse or scroll during a query cannot restart hidden curve motion.
                            if (previewVisible()) {
                                supported = snapshot.first
                                runtime = snapshot.second
                                renderRuntime()
                            }
                        } else if (runtime != null) {
                            runtime = null
                            chart?.setReductions(emptyList(), animate = false)
                        }
                        delay(250)
                    }
                } finally {
                    runtime = null
                    chart?.setReductions(emptyList(), animate = false)
                }
            }
        }
    }

    private fun previewVisible(): Boolean = expanded && !isHidden && preview?.let {
        it.isShown && it.windowVisibility == View.VISIBLE && it.getGlobalVisibleRect(visibleBounds)
    } == true

    private fun renderAppearance() {
        val context = context ?: return
        val row = header ?: return
        row.findViewById<ImageView>(android.R.id.icon)
            .setImageResource(EffectIconStyle.resolve(context, R.drawable.ic_dynamic_peq))
        settings?.setImageResource(EffectIconStyle.resolve(context, R.drawable.ic_effect_settings_24))
        row.findViewById<View>(R.id.icon_frame).isVisible = appearance?.getBoolean(
            getString(R.string.key_appearance_show_icons), resources.getBoolean(R.bool.default_appearance_show_icons)) == true
    }

    private fun reload() {
        val context = context ?: return
        val loaded = DynamicPeqRepository(context).loadResult()
        state = loaded.state
        corrupt = loaded.corrupt
        rendering = true
        toggle?.let {
            it.isEnabled = !corrupt
            if (it.isChecked != state.enabled) it.setCheckedImmediately(state.enabled)
        }
        rendering = false
        renderRuntime()
    }

    private fun renderRuntime() {
        // Edits in the separate activity also notify this retained fragment.
        // Keep its saved state current without calculating an invisible chart.
        if (!expanded || !isResumed || preview?.isShown != true ||
            preview?.windowVisibility != View.VISIBLE) return
        val context = context ?: return
        val current = runtime
        val hash = DynamicPeqProtocol.payload(state).contentHashCode()
        val active = expanded && current?.let {
            it.isProcessing && it.enabled && state.enabled && it.parameterHash != 0 && it.parameterHash == hash
        } == true
        val rate = current?.sampleRate?.takeIf { it >= 8000 }?.toDouble() ?: 48000.0
        chart?.setState(state, null, rate)
        chart?.setReductions(if (active) current!!.reductions else emptyList(), animate = active)
        status?.text = when {
            corrupt -> "保存数据无法读取 · 请进入模块检查参数"
            supported == false -> "驱动待更新 · 需要配套动态均衡器驱动"
            !state.enabled -> "模块已关闭 · 显示已保存的频响"
            supported == null -> "等待驱动连接"
            current != null && (!current.enabled || current.parameterHash != hash) -> "参数同步中 · 等待生效"
            active -> "实时处理 · ${dynamicNumber(rate / 1000.0, 1)} kHz"
            else -> "待机 · 等待播放"
        }
        status?.setTextColor(if (active) android.graphics.Color.rgb(46, 170, 83) else context.mutedColor())
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (!expanded) runtime = null
        renderExpansion()
        renderRuntime()
    }

    private fun renderExpansion() {
        preview?.isVisible = expanded
        if (!GlassThemeUi.isEnabled(requireContext())) header?.background?.alpha = if (expanded) 255 else 0
        header?.contentDescription = "${getString(R.string.dynamic_peq_title)}，${if (expanded) "已展开，点按收起" else "已收起，点按展开频响图"}"
    }

    private fun openEditorAnimated() {
        val button = settings ?: return
        if (professionalAnimating) return
        professionalAnimating = true
        button.animate().cancel()
        button.rotation = 0f
        button.animate().rotation(180f).setDuration(160L).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                button.rotation = 0f
                professionalAnimating = false
                if (isAdded && view != null) startActivity(Intent(requireContext(), DynamicPeqActivity::class.java))
            }.start()
    }

    override fun onResume() {
        super.onResume()
        runtime = null
        reload()
        renderAppearance()
        view?.let(HelpPreferences::refresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, expanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        settings?.animate()?.cancel()
        professionalAnimating = false
        runtime = null
        chart?.setReductions(emptyList(), animate = false)
        header = null
        preview = null
        chart = null
        status = null
        toggle = null
        settings = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        appearance?.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        preferences = null
        appearance = null
        requireContext().unregisterLocalReceiver(presetReceiver)
        super.onDestroy()
    }

    companion object {
        private const val STATE_EXPANDED = "dynamic_peq_home_expanded"
        private const val PREVIEW_TAG = "dynamic_peq_home_preview"
        private const val HOME_SWITCH_SCALE = 46f / 52f
    }
}
