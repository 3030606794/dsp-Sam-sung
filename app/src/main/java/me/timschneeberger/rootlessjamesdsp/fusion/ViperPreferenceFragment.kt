package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Stored in dsp_* so the existing profile/preset system includes every ViPER control. */
class ViperPreferenceFragment : PreferenceFragmentCompat() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                (parentFragment as? DspFragment)?.restartFragment(id, ViperPreferenceFragment())
            } else updateStatus()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceManager.sharedPreferencesName = ViperParameters.PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        // Child preferences have no manager until attached to the screen.
        // Capture the fragment's store before entering any Preference.apply block.
        val storedPreferences = requireNotNull(preferenceManager.sharedPreferences)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "DSP Fusion · 单驱动控制中心"
            summary = "处理顺序：JamesDSP → 听感调音 → ViPER。底部电源键控制整条音效链。"
            isSelectable = false
        })
        preferenceScreen.addPreference(Preference(ctx).apply {
            key = "fusion_status"
            title = "驱动连接状态"
            isPersistent = false
            isSelectable = false
        })
        updateStatus()
        preferenceScreen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = ViperParameters.JAMES_ENABLE
            title = "JamesDSP 处理段"
            summary = "控制下方原有 JamesDSP 功能（含 DDC 与卷积）。"
            setDefaultValue(true)
        })
        preferenceScreen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = ViperParameters.VIPER_ENABLE
            title = "ViPER 处理段"
            summary = "独立旁路所有 ViPER 效果，保留调节数值。"
            setDefaultValue(true)
        })

        ViperParameters.groups.forEach { group ->
            val category = PreferenceCategory(ctx).apply { title = group.title }
            preferenceScreen.addPreference(category)
            val details = mutableListOf<Preference>()
            val toggle = group.enableParameter?.let {
                SwitchPreferenceCompat(ctx).apply {
                    key = group.key
                    title = "启用${group.title}"
                    setDefaultValue(false)
                    category.addPreference(this)
                    dependency = ViperParameters.VIPER_ENABLE
                    setOnPreferenceChangeListener { _, new ->
                        details.forEach { it.isVisible = new as Boolean }
                        true
                    }
                }
            }
            if (group.summary.isNotBlank()) {
                Preference(ctx).apply {
                    summary = group.summary
                    isSelectable = false
                    category.addPreference(this)
                    details.add(this)
                }
            }
            group.controls.forEach { control ->
                val pref: Preference = if (control.choices.isNotEmpty()) {
                    // Int-backed seekbar avoids mixing String and Int in old profiles.
                    SeekBarPreference(ctx).apply {
                        max = control.max
                        min = control.min
                        showSeekBarValue = false
                    }
                } else SeekBarPreference(ctx).apply {
                    max = control.max
                    min = control.min
                    showSeekBarValue = true
                }
                pref.apply {
                    key = control.key
                    title = control.title
                    setDefaultValue(control.default)
                    val value = (storedPreferences.all[control.key] as? Number)?.toInt()
                        ?: control.default
                    summary = control.label(value)
                    setOnPreferenceChangeListener { p, new ->
                        p.summary = control.label(new as Int)
                        true
                    }
                }
                (pref as SeekBarPreference).updatesContinuously = false
                category.addPreference(pref)
                pref.dependency = ViperParameters.VIPER_ENABLE
                details.add(pref)
            }
            if (group.key == "v_dynamic") {
                ListPreference(ctx).apply {
                    key = "v_dynamic_preset"
                    title = "设备响应"
                    entries = ViperParameters.dynamicPresets.keys.toTypedArray()
                    entryValues = ViperParameters.dynamicPresets.keys.indices.map { it.toString() }.toTypedArray()
                    setDefaultValue("0")
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    category.addPreference(this)
                    dependency = ViperParameters.VIPER_ENABLE
                    details.add(this)
                }
            }
            if (toggle != null) details.forEach { it.isVisible = toggle.isChecked }
        }
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "DDC 校正与卷积"
            summary = "使用下方 JamesDSP 的 DDC 校正和卷积模块导入 VDC、WAV/IRS 文件，共享处理段。ViPER 原驱动中未完成的 AGC、FET 压缩控制暂未开放。"
            isSelectable = false
        })
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "重置 ViPER 调节"
            summary = "关闭 ViPER 各项效果并恢复默认数值。"
            setOnPreferenceClickListener {
                storedPreferences.edit().clear().apply()
                (parentFragment as? DspFragment)?.restartFragment(id, ViperPreferenceFragment())
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                true
            }
        })
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        ctx.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(ViperController.STATUS_ACTION)
        })
    }

    private fun updateStatus() {
        findPreference<Preference>("fusion_status")?.summary = requireContext()
            .getSharedPreferences(ViperController.STATUS_PREFS, Context.MODE_PRIVATE)
            .getString(ViperController.STATUS_KEY, "打开底部电源键以连接 DSP Fusion 驱动。")
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, state: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, state).apply {
            isNestedScrollingEnabled = false
            itemAnimator = null
        }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        requireContext().unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
