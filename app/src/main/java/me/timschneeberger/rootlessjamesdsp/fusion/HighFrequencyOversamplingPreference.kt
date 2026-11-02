package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.Context
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueObserver
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import android.view.View
import android.widget.EditText
import android.text.InputType
import kotlin.math.roundToInt

/** A discrete DSP choice: no fictitious 3× position or fractional multiplier. */
class HighFrequencyOversamplingPreference(context: Context) : Preference(context), PreferenceValueSync {
    private val factors = listOf(1, 2, 4)
    private var trackingTouch = false
    private var renderBoundValue: (() -> Unit)? = null
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences)
    var value = 1
        private set
    init {
        key = HighFrequencyAdvancedParameters.OVERSAMPLING
        title = "非线性过采样"
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_bass_numeric
        setDefaultValue(1)
    }
    override fun onSetInitialValue(defaultValue: Any?) {
        value = HighFrequencyAdvancedParameters.oversampling(sharedPreferences?.all ?: emptyMap<String, Any>())
    }
    override fun syncFromPreferences() {
        if (!isPersistent || trackingTouch) return
        val next = HighFrequencyAdvancedParameters.oversampling(sharedPreferences?.all ?: emptyMap<String, Any>())
        if (next == value) return
        value = next
        renderBoundValue?.invoke()
    }
    override fun onAttached() {
        super.onAttached()
        valueObserver.attach()
    }
    override fun onDetached() {
        valueObserver.detach()
        releaseBoundView()
        super.onDetached()
    }
    override fun releaseBoundView() {
        trackingTouch = false
        renderBoundValue = null
    }
    override fun finishInteraction() {
        trackingTouch = false
        syncFromPreferences()
        renderBoundValue?.invoke()
    }
    override fun getSummary(): CharSequence = "$value×"
    private fun accept(candidate: Int, notify: Boolean): Boolean {
        if (!isEnabled || candidate !in factors) return false
        if (candidate == value) return true
        if (!callChangeListener(candidate)) return false
        value = candidate
        persistInt(value)
        if (notify) renderBoundValue?.invoke()
        return true
    }
    fun requestValue(candidate: Int) = accept(candidate, true)
    fun step(delta: Int) = requestValue(factors[(factors.indexOf(value) + delta).coerceIn(0, 2)])
    fun resetToDefault() = requestValue(1)
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        super.onBindViewHolder(holder)
        holder.itemView.isSaveFromParentEnabled = false
        val slider = holder.findViewById(R.id.seekbar) as Slider
        slider.setTag(R.id.seekbar, this)
        SliderAppearance.apply(slider)
        val number = holder.findViewById(R.id.seekbar_value) as TextView
        val minus = holder.findViewById(R.id.bass_decrement)
        val plus = holder.findViewById(R.id.bass_increment)
        val reset = holder.findViewById(R.id.bass_reset)
        reset.visibility = View.GONE
        slider.clearOnChangeListeners(); slider.clearOnSliderTouchListeners()
        slider.isSaveEnabled = false
        slider.valueFrom = 0f; slider.valueTo = 2f; slider.stepSize = 1f
        slider.value = factors.indexOf(value).toFloat()
        slider.setGlassLabelFormatter { "${factors[it.roundToInt()]}×" }
        slider.contentDescription = title
        slider.isEnabled = isEnabled; number.isEnabled = isEnabled; reset.isEnabled = isEnabled
        fun renderValue() {
            if (slider.getTag(R.id.seekbar) !== this) return
            number.text = "$value×"
            number.contentDescription = "非线性过采样：$value 倍，点击选择"
            minus.isEnabled = isEnabled && value > 1; plus.isEnabled = isEnabled && value < 4
        }
        renderBoundValue = {
            if (slider.getTag(R.id.seekbar) === this && !trackingTouch) {
                val position = factors.indexOf(value).toFloat()
                if (slider.value != position) slider.value = position
                renderValue()
            }
        }
        renderValue()
        minus.contentDescription = "降低过采样倍率"; plus.contentDescription = "提高过采样倍率"
        reset.contentDescription = "过采样恢复 1 倍"
        number.setOnClickListener { onClick() }; minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }; reset.setOnClickListener { resetToDefault() }
        SliderDoubleTapReset.install(slider, 0f, onCancel = ::finishInteraction, resetLabel = "恢复默认值", thumbOnly = true) {
            resetToDefault()
            slider.value = factors.indexOf(value).toFloat()
            renderValue()
        }
        SliderPreferenceUi.bind(holder, title, super.getSummary() ?: summary, "可选：1×、2×、4×；默认：1×；步长：相邻合法档位")
        slider.addOnChangeListener { _, candidate, fromUser -> if (fromUser && slider.getTag(R.id.seekbar) === this) {
            accept(factors[candidate.roundToInt()], false)
            if (slider.value != factors.indexOf(value).toFloat()) slider.value = factors.indexOf(value).toFloat()
            renderValue()
        } }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { trackingTouch = true }
            override fun onStopTrackingTouch(slider: Slider) { finishInteraction() }
        })
    }
    override fun onClick() {
        if (!isEnabled) return
        syncFromPreferences()
        val input = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText(value.toString()); selectAll() }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString().trim().toIntOrNull()
                if (candidate == null || !requestValue(candidate)) input.error = "请输入 1、2 或 4"
                else dialog.dismiss()
            }
        }
        dialog.show()
    }
}
