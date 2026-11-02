package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueObserver
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import android.widget.EditText
import android.text.InputType
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** Material slider with integer storage, matching the native MSEB control packet. */
class ToneSliderPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs), PreferenceValueSync {
    private val buttonRepeats = mutableListOf<ButtonPressRepeat>()
    var editGuard: () -> Boolean = { true }
    var leftKeyword: String = ""
    var rightKeyword: String = ""
    var limit: Int = 100
        set(value) {
            require(value in ToneController.RANGES)
            if (field == value) return
            cancelButtonRepeats()
            field = value
            setValueInternal(storedValue, false, false)
            notifyChanged()
        }
    val min: Int get() = -limit
    val max: Int get() = limit
    private var storedValue = 0
    private var defaultValue = 0
    private var trackingTouch = false
    private var renderBoundValue: (() -> Unit)? = null
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences, setOf(ToneController.RANGE))
    var value: Int
        get() = storedValue
        set(value) { setValueInternal(value, true) }

    init {
        layoutResource = R.layout.preference_fusion_tone_slider
        isSelectable = false
        isIconSpaceReserved = false
    }

    fun setValueSilently(value: Int) { if(storedValue != value.coerceIn(min,max)) setValueInternal(value,true,false) }

    override fun syncFromPreferences() {
        if (!isPersistent || key == null || trackingTouch) return
        val preferences = sharedPreferences ?: return
        limit = ToneController.range(preferences)
        val next = runCatching { preferences.getInt(key, defaultValue) }.getOrNull() ?: return
        setValueInternal(next, true, false)
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
        disposeButtonRepeats()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        this.defaultValue = (defaultValue as? Int) ?: 0
        setValueInternal((sharedPreferences?.all?.get(key) as? Int) ?: defaultValue as? Int ?: 0, false, false)
    }

    private fun setValueInternal(value: Int, refresh: Boolean, save: Boolean = true) {
        val clipped = value.coerceIn(min, max)
        if (storedValue != clipped) {
            storedValue = clipped
            if(save) persistInt(clipped)
            if (refresh && !trackingTouch) renderBoundValue?.invoke()
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        disposeButtonRepeats()
        super.onBindViewHolder(holder)
        val label = holder.findViewById(R.id.fusion_tone_slider_title) as TextView
        (holder.findViewById(R.id.fusion_tone_keyword_left) as TextView).text = leftKeyword
        (holder.findViewById(R.id.fusion_tone_keyword_right) as TextView).text = rightKeyword
        val slider = holder.findViewById(R.id.fusion_tone_slider) as Slider
        slider.setTag(R.id.seekbar, this)
        slider.isSaveEnabled = false
        holder.itemView.isSaveFromParentEnabled = false
        SliderAppearance.apply(slider)
        val minus = holder.findViewById(R.id.fusion_tone_decrease) as MaterialButton
        val plus = holder.findViewById(R.id.fusion_tone_increase) as MaterialButton
        val number = holder.findViewById(R.id.fusion_tone_value) as MaterialButton

        fun updateLabels() {
            if (slider.getTag(R.id.seekbar) !== this) return
            label.text = title
            number.text = storedValue.toString()
            number.isEnabled = isEnabled
            minus.isEnabled = isEnabled && storedValue > min
            plus.isEnabled = isEnabled && storedValue < max
        }
        fun userValue(value: Int): Boolean {
            if (!isEnabled || !editGuard()) return false
            val next = value.coerceIn(min, max)
            val accepted = next == storedValue || callChangeListener(next)
            if (accepted) setValueInternal(next, false)
            if (slider.getTag(R.id.seekbar) === this && slider.value != storedValue.toFloat())
                slider.value = storedValue.toFloat()
            updateLabels()
            return accepted
        }

        renderBoundValue = {
            if (slider.getTag(R.id.seekbar) === this && !trackingTouch) {
                if (slider.value != storedValue.toFloat()) slider.value = storedValue.toFloat()
                updateLabels()
            }
        }

        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = 1f
        slider.value = storedValue.toFloat()
        slider.setGlassLabelFormatter { it.roundToInt().toString() }
        slider.isEnabled = isEnabled
        slider.contentDescription = title
        minus.contentDescription = context.getString(R.string.fusion_tone_decrease_description, title)
        plus.contentDescription = context.getString(R.string.fusion_tone_increase_description, title)
        minus.setOnClickListener { userValue(storedValue - 1) }
        plus.setOnClickListener { userValue(storedValue + 1) }
        buttonRepeats.add(ButtonPressRepeat(minus))
        buttonRepeats.add(ButtonPressRepeat(plus))
        slider.addOnChangeListener { _, changed, fromUser ->
            if (fromUser) userValue(changed.roundToInt())
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { trackingTouch = true }
            override fun onStopTrackingTouch(slider: Slider) {
                trackingTouch = false
                syncFromPreferences()
                renderBoundValue?.invoke()
            }
        })
        SliderDoubleTapReset.install(slider, defaultValue.coerceIn(min, max).toFloat(), onCancel = ::finishInteraction,
            resetLabel = "恢复默认值", thumbOnly = true) {
            userValue(defaultValue)
            slider.value = storedValue.toFloat()
        }
        number.setOnClickListener {
            if (isEnabled && editGuard()) {
                val input = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    setSingleLine(); setText(storedValue.toString()); selectAll()
                }
                val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(input)
                    .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val entered = input.text.toString().trim().toIntOrNull()
                        if (entered == null || entered !in min..max || !userValue(entered)) input.error = "请输入 $min～$max 的整数"
                        else dialog.dismiss()
                    }
                }
                dialog.show()
            }
        }
        SliderPreferenceUi.bind(holder, title, listOf(summary?.toString().orEmpty(), leftKeyword, rightKeyword).filter(String::isNotBlank).joinToString("；"),
            "范围：$min～$max；默认：$defaultValue；步长：1", R.id.fusion_tone_slider_title)
        updateLabels()
    }

    fun cancelButtonRepeats() = buttonRepeats.forEach { it.cancel() }

    fun disposeButtonRepeats() {
        buttonRepeats.forEach { it.dispose() }
        buttonRepeats.clear()
    }

    override fun onDetached() {
        valueObserver.detach()
        trackingTouch = false
        renderBoundValue = null
        disposeButtonRepeats()
        super.onDetached()
    }
}
