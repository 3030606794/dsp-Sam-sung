package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueObserver
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.databinding.DialogTextinputBinding
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import kotlin.math.roundToInt

/** Native DSP slider presentation with the ViPER protocol's existing Int persistence. */
class ViperSliderPreference(context: Context, val control: ViperParameters.Control) : Preference(context), PreferenceValueSync {
    val min: Int get() = control.min
    val max: Int get() = control.max
    private var storedValue = control.default
    private var trackingTouch = false
    private var renderBoundValue: (() -> Unit)? = null
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences)
    var disabledHint: String? = null
        set(value) { if(field!=value){field=value;notifyChanged()} }
    var value: Int
        get() = storedValue
        set(value) { updateValue(value, true) }

    init {
        key = control.key
        title = control.title
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_materialslider
        setDefaultValue(control.default)
    }

    override fun getSummary(): CharSequence = control.label(value)+(if(!isEnabled && disabledHint!=null)" · $disabledHint" else "")

    override fun onSetInitialValue(defaultValue: Any?) {
        updateValue(getPersistedInt((defaultValue as? Int) ?: control.default), false)
    }

    override fun syncFromPreferences() {
        if (!isPersistent || trackingTouch) return
        val stored = sharedPreferences?.let { runCatching { it.getInt(key, control.default) }.getOrNull() } ?: return
        val next = stored.coerceIn(min, max)
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
    }

    override fun releaseBoundView() {
        trackingTouch = false
        renderBoundValue = null
    }

    override fun onDetached() {
        valueObserver.detach()
        trackingTouch = false
        renderBoundValue = null
        super.onDetached()
    }

    private fun updateValue(value: Int, notify: Boolean) {
        val next = value.coerceIn(min, max)
        val changed = storedValue != next
        storedValue = next
        persistInt(next)
        if (notify && changed && !trackingTouch) renderBoundValue?.invoke()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        super.onBindViewHolder(holder)
        // Recycler rows share child IDs. Persisted values must win over view-hierarchy state.
        holder.itemView.isSaveFromParentEnabled = false
        val slider = holder.findViewById(R.id.seekbar) as Slider
        slider.setTag(R.id.seekbar, this)
        SliderAppearance.apply(slider)
        val label = holder.findViewById(R.id.seekbar_value) as TextView
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.isSaveEnabled = false
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = 1f
        slider.value = value.toFloat()
        slider.isEnabled = isEnabled
        slider.contentDescription = title
        slider.setGlassLabelFormatter { control.label(it.roundToInt()) }
        label.text = control.label(value)
        renderBoundValue = {
            if (slider.getTag(R.id.seekbar) === this && !trackingTouch) {
                if (slider.value != value.toFloat()) slider.value = value.toFloat()
                label.text = control.label(value)
            }
        }

        fun acceptValue() {
            if (slider.getTag(R.id.seekbar) !== this) return
            if(!isEnabled){slider.value=value.toFloat();return}
            val next = slider.value.roundToInt().coerceIn(min, max)
            if (next != value && callChangeListener(next)) updateValue(next, false)
            slider.value = value.toFloat()
            label.text = control.label(value)
        }
        fun step(delta: Int) {
            if (slider.getTag(R.id.seekbar) !== this) return
            if (!isEnabled) return
            val next = (value.toLong() + delta).coerceIn(min.toLong(), max.toLong()).toInt()
            if (next != value && callChangeListener(next)) updateValue(next, false)
            slider.value = value.toFloat()
            label.text = control.label(value)
        }
        label.isEnabled = isEnabled
        label.setOnClickListener { onClick() }
        holder.findViewById(R.id.slider_decrease)?.apply { isEnabled = this@ViperSliderPreference.isEnabled; setOnClickListener { step(-1) } }
        holder.findViewById(R.id.slider_increase)?.apply { isEnabled = this@ViperSliderPreference.isEnabled; setOnClickListener { step(1) } }
        SliderDoubleTapReset.install(slider, control.default.toFloat(), onCancel = ::finishInteraction,
            resetLabel = "恢复默认值", thumbOnly = true) {
            if (isEnabled && value != control.default && callChangeListener(control.default)) updateValue(control.default, false)
            slider.value = value.toFloat(); label.text = control.label(value)
        }
        SliderPreferenceUi.bind(holder, title, super.getSummary() ?: summary,
            "范围：${control.label(min)}～${control.label(max)}；默认：${control.label(control.default)}；步长：" +
                if (control.choices.isNotEmpty()) "相邻合法档位" else control.label(1))
        slider.addOnChangeListener { _, newValue, fromUser ->
            label.text = control.label(newValue.roundToInt())
            // Persist each actual user step so the existing preference broadcast
            // reaches the driver while the finger is still on the slider.
            if (fromUser) acceptValue()
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { trackingTouch = true }
            override fun onStopTrackingTouch(slider: Slider) {
                // Keep the final-value check for release/cancel without generating
                // another preference write when that step was already accepted.
                acceptValue()
                trackingTouch = false
                syncFromPreferences()
                renderBoundValue?.invoke()
            }
        })
    }

    override fun onClick() {
        if(!isEnabled)return
        if (control.choices.isNotEmpty()) {
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(control.choices.toTypedArray(), value) { dialog, which ->
                    if (isEnabled && callChangeListener(which)) value = which
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
            builder.show()
            return
        }
        val content = DialogTextinputBinding.inflate(LayoutInflater.from(context))
        content.textInputLayout.hint = title
        content.textInputLayout.suffixText = control.unit
        content.text1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        content.text1.setText(value.toString())
        content.text1.selectAll()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.slider_dialog_title)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = content.text1.text?.toString()?.trim()?.toIntOrNull()
                if (entered == null || entered !in min..max) {
                    content.textInputLayout.error = context.getString(R.string.viper_slider_integer_range, min, max)
                } else if (isEnabled && callChangeListener(entered)) {
                    value = entered
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
