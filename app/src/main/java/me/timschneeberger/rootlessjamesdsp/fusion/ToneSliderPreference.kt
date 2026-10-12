package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import kotlin.math.roundToInt

/** Material slider with integer storage, matching the native MSEB control packet. */
class ToneSliderPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    private val buttonRepeats = mutableListOf<ButtonPressRepeat>()
    var leftKeyword: String = ""
    var rightKeyword: String = ""
    var limit: Int = 100
        set(value) {
            require(value in ToneController.RANGES)
            cancelButtonRepeats()
            field = value
            setValueInternal(storedValue, false)
            notifyChanged()
        }
    val min: Int get() = -limit
    val max: Int get() = limit
    private var storedValue = 0
    var value: Int
        get() = storedValue
        set(value) { setValueInternal(value, true) }

    init {
        layoutResource = R.layout.preference_fusion_tone_slider
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        setValueInternal(getPersistedInt(defaultValue as? Int ?: 0), false)
    }

    private fun setValueInternal(value: Int, refresh: Boolean) {
        val clipped = value.coerceIn(min, max)
        if (storedValue != clipped) {
            storedValue = clipped
            persistInt(clipped)
        }
        if (refresh) notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        disposeButtonRepeats()
        super.onBindViewHolder(holder)
        val label = holder.findViewById(R.id.fusion_tone_slider_title) as TextView
        (holder.findViewById(R.id.fusion_tone_keyword_left) as TextView).text = leftKeyword
        (holder.findViewById(R.id.fusion_tone_keyword_right) as TextView).text = rightKeyword
        val slider = holder.findViewById(R.id.fusion_tone_slider) as Slider
        val minus = holder.findViewById(R.id.fusion_tone_decrease) as MaterialButton
        val plus = holder.findViewById(R.id.fusion_tone_increase) as MaterialButton

        fun updateLabels() {
            label.text = context.getString(R.string.fusion_tone_value, title, storedValue)
            minus.isEnabled = isEnabled && storedValue > min
            plus.isEnabled = isEnabled && storedValue < max
        }
        fun userValue(value: Int) {
            val next = value.coerceIn(min, max)
            if (next != storedValue && callChangeListener(next)) setValueInternal(next, false)
            if (slider.value != storedValue.toFloat()) slider.value = storedValue.toFloat()
            updateLabels()
        }

        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = 1f
        slider.value = storedValue.toFloat()
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
        SliderDoubleTapReset.install(slider, 0f) { userValue(0) }
        updateLabels()
    }

    fun cancelButtonRepeats() = buttonRepeats.forEach { it.cancel() }

    fun disposeButtonRepeats() {
        buttonRepeats.forEach { it.dispose() }
        buttonRepeats.clear()
    }

    override fun onDetached() {
        disposeButtonRepeats()
        super.onDetached()
    }
}
