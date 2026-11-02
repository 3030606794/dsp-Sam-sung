package me.timschneeberger.rootlessjamesdsp.expansion

import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import me.timschneeberger.rootlessjamesdsp.R

internal object ExpansionEffectsActions {
    fun add(fragment: PreferenceFragmentCompat, group: PreferenceGroup, kind: ExpansionEffectsKind, professional: Boolean) {
        val context = fragment.requireContext()
        fun action(key: String, title: String, explanation: String, execute: () -> Unit) {
            group.addPreference(Preference(context).apply {
                this.key = key; this.title = title; summary = explanation
                isPersistent = false; isIconSpaceReserved = false
                layoutResource = R.layout.preference_liveprog_compact_row
                setOnPreferenceClickListener { execute(); true }
            })
        }
        when (kind) {
            ExpansionEffectsKind.SMART_ADAPT -> SmartAdaptActions.add(fragment, group, professional)
            ExpansionEffectsKind.SPEAKER_OPTIMIZATION -> if (professional) {
                action("speaker_restore_classic", "恢复经典参数", "恢复ViPER经典滤波与电平；保留音效开关和作用范围。") {
                    ExpansionEffectsController.restoreSpeakerClassic(context)
                    Toast.makeText(context, "已恢复经典参数，开关与作用范围保留", Toast.LENGTH_SHORT).show()
                }
            }
            ExpansionEffectsKind.MATCH_EQ -> {
                action("match_reference", "学习参考音乐", "先播放希望接近的音色，再点击；按学习时长累计有效声音。") {
                    ExpansionEffectsController.learn(context, true)
                    Toast.makeText(context, "正在学习参考音乐，进度见专业页实时读数", Toast.LENGTH_SHORT).show()
                }
                action("match_target", "学习当前音乐", "切换到需要调节的音乐后点击；两份学习完成后才应用匹配。") {
                    ExpansionEffectsController.learn(context, false)
                    Toast.makeText(context, "正在学习当前音乐，进度见专业页实时读数", Toast.LENGTH_SHORT).show()
                }
                if (professional) action("match_clear", "清除学习结果", "清除参考和当前频谱，参数设置保留。") {
                    ExpansionEffectsController.clearLearned(context)
                }
            }
            ExpansionEffectsKind.HEADTRACK -> {
                action("head_recenter", "将当前朝向设为正前方", "戴好支持头部跟踪的耳机并朝向前方，再点击居中。") {
                    HeadTrackerBridge.recenter(context)
                    Toast.makeText(context, HeadTrackerBridge.status(context), Toast.LENGTH_LONG).show()
                }
                if (professional) action("head_connection", "头部跟踪设备状态", HeadTrackerBridge.status(context)) {
                    Toast.makeText(context, HeadTrackerBridge.status(context), Toast.LENGTH_LONG).show()
                }
            }
            ExpansionEffectsKind.VOCAL -> {
                group.addPreference(Preference(context).apply {
                    key = "vocal_processing_note"; title = "神经网络分离 · 需要缓冲"
                    summary = "开启后等待模型与缓冲准备。实际就绪状态、延迟和分离读数见专业页；该模块适合听歌。"
                    isPersistent = false; isSelectable = false; isIconSpaceReserved = false
                    layoutResource = R.layout.preference_liveprog_compact_row
                })
            }
            else -> Unit
        }
    }
}
