package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.AdaptiveBassCompactPreferenceAdapter
import me.timschneeberger.rootlessjamesdsp.fusion.BassNumericPreference
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogFullWidthDividers
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** The professional presentation uses the adaptive-bass controls, adapter and toolbar unchanged. */
class CreativeEffectPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var kind: CreativeEffectsKind
    private lateinit var preferences: SharedPreferences
    private lateinit var effectHeader: SwitchPreferenceGroup
    private var dashboard: CreativeEffectsDashboard? = null
    private var freezeActions: CreativeFreezeActionsPreference? = null
    private val freezeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { freezeActions?.refresh() }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, key) }
        if (::effectHeader.isInitialized && (key == null || key == kind.enableKey)) effectHeader.syncFromPreferences()
        if (kind == CreativeEffectsKind.FREEZE && key == kind.enableKey && preferences.all[kind.enableKey] != true)
            context?.let(CreativeEffectsController::resetTransient)
        context?.let(CreativeEffectsController::notifyChanged)
        dashboard?.configurationChanged(key)
        freezeActions?.refresh()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        kind = CreativeEffectsKind.valueOf(requireArguments().getString(ARG_KIND)!!)
        preferenceManager.sharedPreferencesName = kind.prefsName
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        preferences = CreativeEffectsController.preferences(requireContext(), kind)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val group = SwitchPreferenceGroup(requireContext()).apply {
            key = kind.enableKey
            title = kind.title
            setIcon(kind.icon)
            isIconSpaceReserved = false
            setIsIconVisible(true)
            setDefaultValue(false)
        }
        effectHeader = group
        preferenceScreen.addPreference(group)
        group.setProfessionalMode(true)
        if (kind == CreativeEffectsKind.FREEZE) {
            freezeActions = CreativeFreezeActionsPreference(requireContext()).also(group::addPreference)
        }
        CreativeEffectsParameters.controls(kind).forEach { control ->
            group.addPreference(BassNumericPreference(requireContext(), control,
                CreativeEffectsParameters.value(preferences.all, control)))
        }
        group.addPreference(Preference(requireContext()).apply {
            key = "creative_parameter_help"
            title = "参数说明"
            isPersistent = false
            isIconSpaceReserved = false
            layoutResource = R.layout.preference_liveprog_compact_row
            var expanded = savedInstanceState?.getBoolean("helpExpanded", false) ?: false
            val description = when (kind) {
                CreativeEffectsKind.GRANULAR -> "将片段从延迟缓冲区读出，按颗粒长度与密度重叠，可移调、随机倒放。延迟位置散布改变片段来源；反馈会让片段再次进入延迟。湿声比例越大，变化越明显。密度与长度同时调大时，重叠更密；极端移调会改变音质。"
                CreativeEffectsKind.FREEZE -> "开启后点击捕捉，保持当前声音的频谱；重新捕捉会替换保持的声音。释放按释放时间淡出。冻结状态与捕捉指令不写入预设；重建音频链或改变采样率后需重新捕捉。衰减、移调、模糊和宽度只处理冻结声音。"
                CreativeEffectsKind.SHIMMER -> "在混响反馈中加入移调尾声。湿声比例控制原声与尾声；闪烁量控制移调成分，移调决定尾声向上或向下的音程。空间大小、衰减、扩散改变尾声密度与长度；阻尼与低切控制尾声音色。"
            } + "\n第一张静图随当前调节项显示专业参数关系，第二张以切片、声音快照或空间反射示意作用；长按图表可查看解释。" +
                when (kind) {
                    CreativeEffectsKind.GRANULAR -> "下方显示驱动真实活跃颗粒数与生成周期相位；不推算单颗粒轨迹。"
                    CreativeEffectsKind.FREEZE -> "下方显示实际冻结频谱，位于移调与柔化之后、合成包络及低通之前。"
                    CreativeEffectsKind.SHIMMER -> "下方显示实际移调双读头相位、交接权重以及湿声尾音电平；不假称各条混响线的独立测量。"
                } +
                "顶部电源控制图表采样，标题旁开关控制音效。"
            summary = description.takeIf { expanded }
            setOnPreferenceClickListener {
                expanded = !expanded
                summary = description.takeIf { expanded }
                true
            }
        })
        preferences.registerOnSharedPreferenceChangeListener(listener)
        requireContext().registerLocalReceiver(freezeReceiver, IntentFilter(CreativeEffectsController.ACTION_FREEZE_CHANGED))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        dashboard = CreativeEffectsDashboard(this, kind, preferences)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            isNestedScrollingEnabled = true
            itemAnimator = null
            addItemDecoration(LiveprogFullWidthDividers(requireContext()))
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        AdaptiveBassCompactPreferenceAdapter(preferenceScreen)

    override fun onResume() {
        super.onResume()
        preferenceScreen?.let { PreferenceValueSynchronizer.refresh(it, finishInteractions = true) }
        effectHeader.syncFromPreferences()
        freezeActions?.refresh()
        dashboard?.onResume()
    }

    override fun onPause() {
        dashboard?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        dashboard?.close()
        dashboard = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(listener)
        context?.unregisterLocalReceiver(freezeReceiver)
        super.onDestroy()
    }

    companion object {
        private const val ARG_KIND = "creative.kind"
        fun newInstance(kind: CreativeEffectsKind) = CreativeEffectPreferenceFragment().apply {
            arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
        }
    }
}
