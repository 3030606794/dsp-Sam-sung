package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButtonToggleGroup
import me.timschneeberger.rootlessjamesdsp.R

/** Keeps the original String-backed range value while presenting three inline choices. */
class ToneRangePreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    var range: Int = 100
        private set

    init {
        layoutResource = R.layout.preference_fusion_tone_range
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        range = getPersistedString("100").toIntOrNull()?.takeIf { it in ToneController.RANGES } ?: 100
    }

    fun selectRange(value: Int): Boolean {
        if (value !in ToneController.RANGES || value == range) return false
        if (!callChangeListener(value)) return false
        setRangeSilently(value)
        return true
    }

    fun setRangeSilently(value: Int) {
        require(value in ToneController.RANGES)
        range = value
        persistString(value.toString())
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val buttons = holder.findViewById(R.id.fusion_tone_range_buttons) as MaterialButtonToggleGroup
        buttons.clearOnButtonCheckedListeners()
        val selected = when (range) {
            20 -> R.id.fusion_tone_range_small
            40 -> R.id.fusion_tone_range_medium
            else -> R.id.fusion_tone_range_large
        }
        buttons.check(selected)
        buttons.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val value = when (id) {
                    R.id.fusion_tone_range_small -> 20
                    R.id.fusion_tone_range_medium -> 40
                    else -> 100
                }
                if (value != range && !selectRange(value)) buttons.check(selected)
            }
        }
    }
}
