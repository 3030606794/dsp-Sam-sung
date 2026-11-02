package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueObserver
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.databinding.DialogTextinputBinding
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import kotlin.math.roundToInt

/** An integer tick represents the smallest real DSP step, not an approximate displayed float. */
class BassNumericPreference(context: Context, val control: BassParameters.Control,
    initialValue: Int = control.default, private val allowedValues: List<Int>? = null) : Preference(context), PreferenceValueSync {
    // Optional presentation for sample-quantized delay rows; existing bass behavior is unchanged.
    // Optional epoch rejects an input dialog opened against an obsolete module foundation.
    var dialogToken: (() -> Any?)? = null
    var valueFormatter: ((Int) -> String)? = null
        set(value) {if(field!==value){field=value;notifyChanged()}}
    // Keeps a short label beside its slider when the separate title has scrolled above the viewport.
    var inlineTitleWithValue = false
        set(value) {if(field!=value){field=value;notifyChanged()}}
    var resetAction: (() -> Boolean)? = null
    // Convolution pre-delay uses an actual audio sample, and IR windows use a 0.1 ms step.
    var stepAction: ((Int) -> Boolean)? = null
    var valueTransform: ((Int) -> Int)? = null
    var stepLabel: (() -> String)? = null
    // An optional bounded slider is presentation only; numeric input retains the complete stored range.
    var sliderRange: IntRange? = null
        set(value) {
            if (trackingTouch) {
                pendingSliderRange = value
                sliderRangePending = true
            } else if (field != value) {
                field = value
                if (renderBoundValue != null) notifyChanged()
            }
        }
    private var pendingSliderRange: IntRange? = null
    private var sliderRangePending = false
    var sliderStep = 1
    var sliderEnabled = true
        set(value) {if(field!=value){field=value;notifyChanged()}}
    var notifyUnchangedEdits = false
    var rejectedButtonFeedback: (() -> Unit)? = null
    var rejectedValueMessage = "跟踪下限必须小于上限"
    private fun label(value: Int) = valueFormatter?.invoke(value) ?: control.label(value)
    private var storedValue = initialValue.coerceIn(control.min, control.max)
    private var trackingTouch = false
    private var renderBoundValue: (() -> Unit)? = null
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences)
    var value: Int
        get() = storedValue
        set(value) { updateValue(value, true) }

    private fun updateValue(value: Int, notify: Boolean) {
        val bounded = value.coerceIn(control.min, control.max)
        val next = if (allowedValues != null && bounded !in allowedValues) control.default else bounded
        val changed = storedValue != next
        storedValue = next
        persistInt(next)
        if (changed && notify && !trackingTouch) renderBoundValue?.invoke()
    }

    init {
        key = control.key
        title = control.title
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_bass_numeric
        setDefaultValue(initialValue)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = if (isPersistent && sharedPreferences?.contains(key) == true)
            BassParameters.value(sharedPreferences!!.all, control)
        else (defaultValue as? Int) ?: control.default
    }

    override fun syncFromPreferences() {
        if (!isPersistent || trackingTouch) return
        val preferences = sharedPreferences ?: return
        val bounded = BassParameters.value(preferences.all, control).coerceIn(control.min, control.max)
        val next = if (allowedValues != null && bounded !in allowedValues) control.default else bounded
        if (storedValue == next) return
        storedValue = next
        renderBoundValue?.invoke()
    }

    override fun onAttached() {
        super.onAttached()
        valueObserver.attach()
    }

    override fun finishInteraction() {
        trackingTouch = false
        syncFromPreferences()
        renderBoundValue?.invoke()
        if (sliderRangePending) {
            sliderRangePending = false
            sliderRange = pendingSliderRange
        }
    }

    override fun releaseBoundView() {
        trackingTouch = false
        renderBoundValue = null
        if (sliderRangePending) {
            sliderRangePending = false
            sliderRange = pendingSliderRange
        }
    }

    override fun onDetached() {
        valueObserver.detach()
        trackingTouch = false
        renderBoundValue = null
        super.onDetached()
    }

    override fun getSummary(): CharSequence = label(value)

    /** Returns false when a linked range rejects this edit; the slider then restores its last value. */
    fun requestValue(candidate: Int): Boolean = acceptValue(candidate, true)

    private fun acceptValue(candidate: Int, notify: Boolean): Boolean {
        if (!isEnabled || candidate !in control.min..control.max || (allowedValues != null && candidate !in allowedValues)) return false
        val next=valueTransform?.invoke(candidate) ?: candidate
        if(next !in control.min..control.max || (allowedValues!=null && next !in allowedValues))return false
        if (next == value && !notifyUnchangedEdits) return true
        if (!callChangeListener(next)) return false
        updateValue(next, notify)
        return true
    }

    fun step(delta: Int): Boolean = stepAction?.invoke(delta) ?: if (allowedValues != null)
        requestValue(allowedValues[(allowedValues.indexOf(value)+delta).coerceIn(allowedValues.indices)])
    else requestValue((value.toLong() + delta).coerceIn(control.min.toLong(), control.max.toLong()).toInt())
    fun resetToDefault(): Boolean = resetAction?.invoke() ?: requestValue(control.default)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        super.onBindViewHolder(holder)
        holder.itemView.isSaveFromParentEnabled = false
        val slider = holder.findViewById(R.id.seekbar) as Slider
        slider.setTag(R.id.seekbar, this)
        SliderAppearance.apply(slider)
        val number = holder.findViewById(R.id.seekbar_value) as TextView
        number.maxLines = if (inlineTitleWithValue) 2 else 1
        number.minHeight = ((if (inlineTitleWithValue) 40 else 30) * context.resources.displayMetrics.density).roundToInt()
        val minus = holder.findViewById(R.id.bass_decrement)
        val plus = holder.findViewById(R.id.bass_increment)
        val reset = holder.findViewById(R.id.bass_reset)
        reset.visibility = View.GONE
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.isSaveEnabled = false
        val range=sliderRange?.let {it.first.coerceIn(control.min,control.max)..it.last.coerceIn(control.min,control.max)}
        val mapped=range!=null
        val ticks=if(mapped)((range!!.last.toLong()-range.first)/sliderStep).coerceAtLeast(1).toInt()else 0
        fun sliderValue() = if(mapped)((value.toDouble()-range!!.first)/sliderStep).roundToInt().coerceIn(0,ticks).toFloat()
            else (allowedValues?.indexOf(value) ?: value).toFloat()
        fun actualValue(position: Float) = if(mapped)(range!!.first.toLong()+position.roundToInt().toLong()*sliderStep).coerceIn(control.min.toLong(),control.max.toLong()).toInt()
            else allowedValues?.get(position.roundToInt()) ?: position.roundToInt()
        slider.valueFrom = if (allowedValues != null || mapped) 0f else control.min.toFloat()
        slider.valueTo = if(mapped)ticks.toFloat()else allowedValues?.lastIndex?.toFloat() ?: control.max.toFloat()
        slider.stepSize = 1f
        slider.value = sliderValue()
        slider.setGlassLabelFormatter { label(actualValue(it)) }
        slider.contentDescription = control.title
        slider.isEnabled = isEnabled && sliderEnabled && (!mapped || range!!.last>range.first)
        number.isEnabled = isEnabled
        number.setTextColor(MaterialColors.getColor(number, if (isEnabled)
            com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
        number.alpha = if (isEnabled) 1f else 0.38f
        reset.isEnabled = isEnabled
        fun renderValue() {
            if (slider.getTag(R.id.seekbar) !== this) return
            number.text = if (inlineTitleWithValue) "${control.title}\n${label(value)}" else label(value)
            number.contentDescription = "${control.title}：${label(value)}，点击输入"
            minus.isEnabled = isEnabled && value > control.min
            plus.isEnabled = isEnabled && value < control.max
        }
        renderBoundValue = {
            if (slider.getTag(R.id.seekbar) === this && !trackingTouch) {
                if (slider.value != sliderValue()) slider.value = sliderValue()
                renderValue()
            }
        }
        renderValue()
        minus.contentDescription = if (allowedValues == null) "${control.title}减少 ${stepLabel?.invoke() ?: control.label(1)}" else "${control.title}上一档"
        plus.contentDescription = if (allowedValues == null) "${control.title}增加 ${stepLabel?.invoke() ?: control.label(1)}" else "${control.title}下一档"
        reset.contentDescription = "${control.title}恢复 ${control.label(control.default)}"
        number.setOnClickListener { onClick() }
        minus.setOnClickListener { if (!step(-1)) rejectedButtonFeedback?.invoke() }
        plus.setOnClickListener { if (!step(1)) rejectedButtonFeedback?.invoke() }
        reset.setOnClickListener { if (!resetToDefault()) rejectedButtonFeedback?.invoke() }
        val defaultPosition = if (mapped)
            ((control.default.toDouble() - range!!.first) / sliderStep).roundToInt().coerceIn(0, ticks).toFloat()
        else (allowedValues?.indexOf(control.default)?.coerceAtLeast(0) ?: control.default).toFloat()
        SliderDoubleTapReset.install(slider, defaultPosition, onCancel = ::finishInteraction,
            resetLabel = "恢复默认值", thumbOnly = true) {
            if (!resetToDefault()) rejectedButtonFeedback?.invoke()
            slider.value = sliderValue()
            renderValue()
        }
        SliderPreferenceUi.bind(holder, title, super.getSummary() ?: summary,
            "范围：${control.label(control.min)}～${control.label(control.max)}；默认：${control.label(control.default)}；步长：${stepLabel?.invoke() ?: control.label(1)}" +
                (allowedValues?.joinToString(prefix = "；可选：", separator = "、") { control.label(it) } ?: ""))
        slider.addOnChangeListener { _, candidate, fromUser ->
            if (slider.getTag(R.id.seekbar) !== this) return@addOnChangeListener
            if (fromUser) {
                // A drag must not recycle/rebind its own row between ACTION_MOVE events.
                acceptValue(actualValue(candidate), false)
                if (slider.value != sliderValue()) slider.value = sliderValue()
                renderValue()
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { trackingTouch = true }
            override fun onStopTrackingTouch(slider: Slider) {
                finishInteraction()
            }
        })
    }

    override fun onClick() {
        if (!isEnabled) return
        val openedToken = dialogToken?.invoke()
        val content = DialogTextinputBinding.inflate(LayoutInflater.from(context))
        content.textInputLayout.hint = allowedValues?.joinToString("、") { control.label(it) }
            ?: "${control.label(control.min)} … ${control.label(control.max)}"
        content.textInputLayout.suffixText = control.unit
        content.text1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
            (if (control.decimals > 0) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        content.text1.setText(control.number(value))
        content.text1.selectAll()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(content.root)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = control.parse(content.text1.text?.toString().orEmpty())
                if (dialogToken?.invoke() != openedToken) content.textInputLayout.error = rejectedValueMessage
                else if (entered == null) content.textInputLayout.error = if (allowedValues != null) rejectedValueMessage else
                    "请输入 ${control.label(control.min)} 至 ${control.label(control.max)}，步长 ${control.label(1)}"
                else if (requestValue(entered)) dialog.dismiss()
                else content.textInputLayout.error = rejectedValueMessage
            }
        }
        dialog.show()
    }
}
