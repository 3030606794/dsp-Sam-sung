package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale

internal object SmartAdaptActions {
    fun add(fragment: PreferenceFragmentCompat, group: PreferenceGroup, professional: Boolean) {
        val context = fragment.requireContext()
        val preferences = ExpansionEffectsController.preferences(context, ExpansionEffectsKind.SMART_ADAPT)
        if (professional) group.addPreference(PreferenceCategory(context).apply {
            key = "smart_adapt_actions"; title = "锁定与重置"; isIconSpaceReserved = false
        })
        val freeze = Preference(context).apply {
            key = "smart_adapt_capture"; isPersistent = false; isIconSpaceReserved = false
            layoutResource = R.layout.preference_liveprog_compact_row
        }
        group.addPreference(freeze)
        var job: Job? = null
        var busy = false
        fun refresh() {
            val snapshot = SmartAdaptState.read(preferences.all)
            freeze.isEnabled = !busy
            freeze.title = when { busy -> "正在读取当前适配…"; snapshot != null -> "恢复自动适配"; else -> "锁定当前适配" }
            freeze.summary = when {
                busy -> "核对当前播放、参数与实际适配目标。"
                snapshot != null -> String.format(Locale.getDefault(),
                    "%s档已锁定：低 %+.2f / 中 %+.2f / 高 %+.2f dB，侧声道 +%.1f%%。强度和频区处理量继续有效；换目标或力度会解除锁定。",
                    SmartAdaptIntensity.level(snapshot.intensity).label, snapshot.low / 1000.0, snapshot.mid / 1000.0, snapshot.high / 1000.0, snapshot.width / 10.0)
                else -> "正常播放并等待分析就绪后，保存驱动当前目标；不会改动其他均衡器。"
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                job?.cancel()
                preferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
        fragment.viewLifecycleOwnerLiveData.observe(fragment) { owner ->
            owner?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) { job?.cancel() }
                override fun onDestroy(owner: LifecycleOwner) { job?.cancel() }
            })
        }
        freeze.setOnPreferenceClickListener {
            if (SmartAdaptState.read(preferences.all) != null) {
                ExpansionEffectsController.resumeSmartAdapt(context)
                refresh()
            } else if (preferences.all[ExpansionEffectsKind.SMART_ADAPT.enableKey] != true ||
                ExpansionEffectsParameters.value(preferences.all, ExpansionEffectsParameters.control(ExpansionEffectsKind.SMART_ADAPT, "strength")) <= 0) {
                Toast.makeText(context, "请先开启智能适配、设置非零强度并正常播放音乐", Toast.LENGTH_SHORT).show()
            } else if (ExpansionEffectsController.supported(context, ExpansionEffectsKind.SMART_ADAPT) == false) {
                Toast.makeText(context, "五档智能适配需要新版配套驱动，请更新驱动并重启手机", Toast.LENGTH_LONG).show()
            } else if (!busy && fragment.view != null) {
                busy = true; refresh()
                job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val capture = ExpansionEffectsController.captureSmartAdapt(context.applicationContext)
                        val saved = capture != null && ExpansionEffectsController.saveSmartCapture(context.applicationContext, capture)
                        Toast.makeText(context, if (saved) "已锁定当前适配；重新打开应用后保留" else
                            "暂不能锁定：请保持单路正常播放，等待分析就绪；参数或输出切换后请重试", Toast.LENGTH_LONG).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, "未能读取有效适配结果，请稍后重试", Toast.LENGTH_SHORT).show()
                    } finally {
                        busy = false; refresh()
                    }
                }
            }
            true
        }
        if (professional) group.addPreference(Preference(context).apply {
            key = "smart_adapt_restore"; title = "恢复智能适配默认参数"
            summary = "恢复自然目标、强劲档和 40% 强度，解除锁定；保留本模块开关，其他音效不变。"
            isPersistent = false; isIconSpaceReserved = false
            layoutResource = R.layout.preference_liveprog_compact_row
            setOnPreferenceClickListener {
                job?.cancel()
                ExpansionEffectsController.restoreSmartDefaults(context)
                Toast.makeText(context, "已恢复智能适配默认参数", Toast.LENGTH_SHORT).show()
                true
            }
        })
        refresh()
    }
}
