package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import kotlin.math.roundToInt

/** The panel edits only the independent third theme's saved presentation settings. */
object Glass3SettingsDialog {
    fun show(context: Context) {
        val builder = GlassAlertDialogBuilder(context)
        val themed = builder.context
        fun dp(value: Int) = (value * themed.resources.displayMetrics.density).roundToInt()
        val refresh = mutableListOf<() -> Unit>()
        val body = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }

        fun label(text: String) = AppCompatTextView(themed).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun toggle(title: String, key: String, read: (Glass3Preferences.Snapshot) -> Boolean) {
            val row = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(label(title), LinearLayout.LayoutParams(0, dp(42), 1f))
            val control = MaterialSwitch(themed)
            row.addView(control, LinearLayout.LayoutParams(dp(52), dp(42)))
            var refreshing = false
            val update = {
                refreshing = true
                control.isChecked = read(Glass3Preferences.snapshot(themed))
                refreshing = false
            }
            update()
            control.setOnCheckedChangeListener { _, checked ->
                if (!refreshing) Glass3Preferences.setBoolean(themed, key, checked)
            }
            row.setOnClickListener { control.toggle() }
            refresh += update
            body.addView(row)
        }

        fun percent(title: String, key: String, min: Int, max: Int,
                    read: (Glass3Preferences.Snapshot) -> Float) {
            body.addView(label(title), LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(8) })
            val row = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val slider = Slider(themed).apply {
                valueFrom = min.toFloat()
                valueTo = max.toFloat()
                stepSize = 1f
                value = (read(Glass3Preferences.snapshot(themed)) * 100).roundToInt().toFloat()
                setGlassLabelFormatter { "${it.roundToInt()}%" }
                SliderAppearance.apply(this)
            }
            val valueButton = MaterialButton(themed, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
                contentDescription = "$title，输入百分比"
            }
            fun commit(value: Int) {
                val bounded = value.coerceIn(min, max)
                slider.value = bounded.toFloat()
                valueButton.text = "$bounded%"
                Glass3Preferences.setPercent(themed, key, bounded)
            }
            fun stepButton(text: String, amount: Int) = AppCompatTextView(themed).apply {
                this.text = text
                textSize = 22f
                gravity = Gravity.CENTER
                contentDescription = "$title${if (amount < 0) "减小" else "增大"}"
                setOnClickListener { commit(slider.value.roundToInt() + amount) }
            }
            val minus = stepButton("−", -1)
            val plus = stepButton("+", 1)
            fun sync(value: Int) {
                valueButton.text = "$value%"
                minus.isEnabled = value > min
                plus.isEnabled = value < max
                minus.alpha = if (minus.isEnabled) 1f else .35f
                plus.alpha = if (plus.isEnabled) 1f else .35f
            }
            row.addView(minus, LinearLayout.LayoutParams(dp(28), dp(40)))
            row.addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(plus, LinearLayout.LayoutParams(dp(28), dp(40)))
            row.addView(valueButton, LinearLayout.LayoutParams(dp(66), dp(42)))
            slider.addOnChangeListener { _, value, fromUser ->
                sync(value.roundToInt())
                if (fromUser) Glass3Preferences.setPercent(themed, key, value.roundToInt())
            }
            valueButton.setOnClickListener {
                val field = TextInputEditText(themed).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(slider.value.roundToInt().toString())
                    selectAll()
                }
                val input = TextInputLayout(themed).apply {
                    hint = "$min–$max %"
                    setPadding(dp(20), dp(4), dp(20), 0)
                    addView(field)
                }
                val inputDialog = GlassAlertDialogBuilder(themed).setTitle(title).setView(input)
                    .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
                inputDialog.setOnShowListener {
                    inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val number = field.text?.toString()?.trim()?.toIntOrNull()
                        if (number == null || number !in min..max) {
                            input.error = "请输入 $min–$max 之间的数值"
                        } else {
                            commit(number)
                            sync(number)
                            inputDialog.dismiss()
                        }
                    }
                }
                inputDialog.show()
            }
            refresh += {
                val value = (read(Glass3Preferences.snapshot(themed)) * 100).roundToInt()
                slider.value = value.toFloat()
                sync(value)
            }
            sync(slider.value.roundToInt())
            body.addView(row)
        }

        body.addView(AppCompatTextView(themed).apply {
            text = "仅影响液态玻璃Ⅲ，调整后即时保存。"
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        toggle("彩色背景渲染", Glass3Preferences.COLORFUL_BACKGROUND) { it.colorfulBackground }
        toggle("Q 弹与流动动画", Glass3Preferences.MOTION_ENABLED) { it.motionEnabled }
        toggle("液态实时数值窗", Glass3Preferences.LIQUID_TOOLTIP) { it.liquidTooltip }
        percent("Q 弹幅度", Glass3Preferences.STRENGTH, 0, 150) { it.strength }
        percent("流动速度", Glass3Preferences.SPEED, 50, 150) { it.speed }
        percent("玻璃浓度", Glass3Preferences.OPACITY, 30, 100) { it.opacity }
        percent("边缘折射", Glass3Preferences.REFRACTION, 0, 150) { it.refraction }
        percent("边缘高光", Glass3Preferences.HIGHLIGHT, 0, 150) { it.highlight }
        val scroll = ScrollView(themed).apply {
            isFillViewport = false
            addView(body)
        }
        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1,
                (themed.resources.displayMetrics.heightPixels * .62f).roundToInt()))
        }
        val dialog = builder.setTitle(R.string.glass3_settings_title).setView(container)
            .setNegativeButton("恢复默认", null).setPositiveButton("完成", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                Glass3Preferences.reset(themed)
                refresh.forEach { it() }
            }
        }
        dialog.show()
    }
}
