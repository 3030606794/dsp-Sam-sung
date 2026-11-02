package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import java.util.Locale
import kotlin.math.roundToInt

/** A separate confirmation owns these display preferences, even when opened from another dialog. */
object SpectrumAppearanceDialog {
    fun show(context: Context, namespace: String) {
        val preferences = SpectrumDisplayPreferences(context, namespace)
        val initial = preferences.load()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val primary = MaterialColors.getColor(body, com.google.android.material.R.attr.colorPrimary)
        val ink = MaterialColors.getColor(body, com.google.android.material.R.attr.colorOnSurface)
        fun caption(value: String) = TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(ink)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        fun switch(value: String, checked: Boolean): SpringMaterialSwitch = SpringMaterialSwitch(context).apply {
            text = value
            textSize = 13f
            springEnabled = true
            setCheckedImmediately(checked)
            minimumHeight = dp(44)
            setPadding(dp(2), 0, dp(2), 0)
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun control(title: String, from: Float, to: Float, value: Float,
            formatter: (Float) -> String): Slider {
            val heading = caption(title).apply { setPadding(dp(2), dp(6), 0, 0) }
            body.addView(heading, LinearLayout.LayoutParams(-1, dp(26)))
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val readout = caption(formatter(value)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(primary)
                setTypeface(typeface, Typeface.BOLD)
                fontFeatureSettings = "tnum"
                setPadding(dp(4), 0, dp(2), 0)
            }
            val slider = Slider(context).apply {
                valueFrom = from
                valueTo = to
                stepSize = 0f
                this.value = value.coerceIn(from, to)
                labelBehavior = LabelFormatter.LABEL_GONE
                contentDescription = title
                addOnChangeListener { _, next, _ -> readout.text = formatter(next) }
            }
            row.addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(readout, LinearLayout.LayoutParams(dp(66), dp(40)))
            body.addView(row, LinearLayout.LayoutParams(-1, dp(40)))
            return slider
        }

        val peakHold = switch("峰值保持", initial.peakHold)
        val followRate = switch("频率上限跟随实际分析采样率", initial.followSampleRate)
        val linear = switch("线性频率轴", initial.linearFrequency)
        val flatten = switch("频谱倾斜补偿（仅显示 +3 dB/oct）", initial.flatten)
        val sensitivity = control("显示灵敏度", .05f, 1f, initial.sensitivity) {
            String.format(Locale.ROOT, "%.2f", it)
        }
        val response = control("频谱平滑时间", 50f, 500f, initial.responseMs.toFloat()) { "${it.roundToInt()} ms" }
        val scale = control("显示比例", .5f, 2f, initial.scale) { String.format(Locale.ROOT, "%.2f ×", it) }

        val explanations = caption(
            "峰值保持：保留频谱峰值轮廓，便于观察变化。\n\n" +
                "频率上限跟随采样率：采用当前实际分析窗口的采样率，频率上限不超过其一半；不是音乐文件标称的源采样率。\n\n" +
                "线性频率轴：按均匀 Hz 间隔排列；关闭时使用对数频率轴。\n\n" +
                "倾斜补偿：仅在频谱显示中加入 +3 dB/oct 倾斜补偿，不生成粉红噪声，也不改变音频。\n\n" +
                "灵敏度：只调整频谱可见范围。频谱平滑时间为50–500 ms，越长变化越平缓；不是采样刷新间隔，不改变声音延迟。\n\n" +
                "显示比例：只缩放显示并同步改变刻度，真实采样读数、音量和均衡器参数保持原样。"
        ).apply {
            visibility = View.GONE
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        val more = caption("显示说明 ▾").apply {
            setTextColor(primary)
            isClickable = true
            isFocusable = true
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                val open = explanations.visibility != View.VISIBLE
                explanations.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "收起说明 ▴" else "显示说明 ▾"
            }
        }
        body.addView(more, LinearLayout.LayoutParams(-1, dp(38)))
        body.addView(explanations, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(context).apply { addView(body, android.widget.FrameLayout.LayoutParams(-1, -2)) }
        val dialog = GlassAlertDialogBuilder(context)
            .setTitle("频谱分析器设置")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val options = SpectrumDisplayOptions(
                    peakHold = peakHold.isChecked,
                    linearFrequency = linear.isChecked,
                    flatten = flatten.isChecked,
                    sensitivity = (sensitivity.value * 100f).roundToInt() / 100f,
                    responseMs = response.value.roundToInt(),
                    scale = (scale.value * 100f).roundToInt() / 100f,
                    followSampleRate = followRate.isChecked
                )
                if (preferences.save(options)) dialog.dismiss()
                else Toast.makeText(context, "显示设置保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
}
