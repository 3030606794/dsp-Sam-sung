package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassControlUi
import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter
import kotlin.math.*

/** The same compact slider / direct value / +/- arrangement used by the static PEQ. */
internal class DynamicPeqParameterControl(
    context: Context,
    private val label: String,
    private val minimum: Double,
    private val maximum: Double,
    private val step: Double,
    initial: Double,
    private val suffix: String,
    private val logarithmic: Boolean = false,
    private val gainTint: Boolean = false,
    private val formatter: ((Double) -> String)? = null,
    private val trackingChanged: (Boolean) -> Unit = {},
    private val editFinished: () -> Unit = {},
    private val changed: (Double) -> Unit
) : LinearLayout(context) {
    private var value = initial.coerceIn(minimum, maximum)
    private var binding = false
    private var tracking = false
    private var appliedTint: Int? = null
    private var dialog: AlertDialog? = null
    private val row = LayoutInflater.from(context).inflate(R.layout.dynamic_peq_parameter_row, this, true)
    private val slider = row.findViewById<Slider>(R.id.dynamic_peq_parameter_slider).apply {
        valueFrom = if (logarithmic) 0f else minimum.toFloat()
        valueTo = if (logarithmic) 1f else maximum.toFloat()
        stepSize = 0f
        contentDescription = label
        setGlassLabelFormatter { display(valueAt(it)) }
    }
    private val readout = row.findViewById<TextView>(R.id.dynamic_peq_parameter_value).apply {
        setOnClickListener { editExact() }
    }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), 0, context.dp(4), 0)
        row.findViewById<TextView>(R.id.dynamic_peq_parameter_label).text = label
        row.findViewById<MaterialButton>(R.id.dynamic_peq_parameter_minus).apply {
            contentDescription = "减少$label"; setOnClickListener { commit(value - step) }
        }
        row.findViewById<MaterialButton>(R.id.dynamic_peq_parameter_plus).apply {
            contentDescription = "增加$label"; setOnClickListener { commit(value + step) }
        }
        setValue(value)
        slider.addOnChangeListener { _, position, fromUser ->
            if (fromUser && !binding) commit(valueAt(position), moveThumb = false)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                tracking = true; trackingChanged(true)
            }
            override fun onStopTrackingTouch(slider: Slider) {
                tracking = false; trackingChanged(false); editFinished()
            }
        })
        GlassControlUi.decorateSlider(slider)
        GlassControlUi.decorate(readout)
    }

    private fun display(number: Double): String = formatter?.invoke(number)
        ?: "${if (gainTint && number > 0) "+" else ""}${dynamicNumber(number)}${if (suffix.isEmpty()) "" else " $suffix"}"

    private fun valueAt(position: Float): Double {
        val raw = if (logarithmic) minimum * (maximum / minimum).pow(position.toDouble()) else position.toDouble()
        return (round(raw / step) * step).coerceIn(minimum, maximum)
    }

    fun setValue(next: Double, moveThumb: Boolean = true) {
        value = next.coerceIn(minimum, maximum)
        binding = true
        if (moveThumb) slider.value = if (logarithmic) (ln(value / minimum) / ln(maximum / minimum)).toFloat().coerceIn(0f, 1f)
            else value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        val formatted = display(value)
        if (readout.text.toString() != formatted) readout.text = formatted
        readout.contentDescription = "$label：$formatted，点击精确输入"
        if (gainTint) {
            val tint = if (value > .00001) Color.rgb(211, 47, 47)
                else if (value < -.00001) Color.rgb(25, 118, 210) else context.primaryColor()
            if (appliedTint != tint) {
                appliedTint = tint
                readout.setTextColor(tint)
                slider.thumbTintList = ColorStateList.valueOf(tint)
                slider.trackActiveTintList = ColorStateList.valueOf(tint)
            }
        }
        binding = false
    }

    private fun commit(next: Double, moveThumb: Boolean = true) {
        val valid = next.coerceIn(minimum, maximum)
        if (abs(valid - value) < 1e-9) return
        setValue(valid, moveThumb)
        changed(valid)
        if (!tracking) editFinished()
    }

    private fun editExact() {
        if (dialog?.isShowing == true) return
        val box = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = label; suffixText = suffix.takeIf { it.isNotEmpty() }
            helperText = "${dynamicNumber(minimum)} ～ ${dynamicNumber(maximum)}"
        }
        val input = TextInputEditText(box.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
            setText(dynamicNumber(value, 4)); setSelectAllOnFocus(true)
        }
        box.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val wrapper = context.column().apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addFull(box)
        }
        val editor = GlassAlertDialogBuilder(context).setTitle("精确调节 · $label").setView(wrapper)
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog = editor
        editor.setOnShowListener {
            val apply = View.OnClickListener {
                val number = input.text?.toString()?.trim()?.toDoubleOrNull()
                if (number == null || !number.isFinite() || number !in minimum..maximum) {
                    box.error = "请输入 ${dynamicNumber(minimum)} ～ ${dynamicNumber(maximum)} 之间的数值"
                } else { commit(number); editor.dismiss() }
            }
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(apply)
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { apply.onClick(input); true } else false
            }
        }
        editor.setOnDismissListener { dialog = null }
        editor.show()
    }

    override fun onDetachedFromWindow() {
        if (tracking) { tracking = false; trackingChanged(false) }
        dialog?.dismiss(); dialog = null; super.onDetachedFromWindow()
    }
}
