package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

/** Seven equal-width filter choices shared by precise, bulk and standalone editors. */
class PeqFilterSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private data class Option(val view: LinearLayout, val label: TextView, val icon: ImageView)
    private val options = linkedMapOf<PeqFilterType, Option>()
    private var selection: PeqFilterType? = null
    private val normalText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val selectedBackground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
    private val selectedText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
    private val monitorBackground = Color.rgb(46, 125, 50)

    var selectedType: PeqFilterType?
        get() = selection
        set(value) { setSelection(value) }

    var monitoring: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    var onTypeSelected: ((PeqFilterType) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(1), dp(1), dp(1), dp(1))
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), MaterialColors.getColor(this@PeqFilterSelector,
                com.google.android.material.R.attr.colorOutline))
        }
        clipToOutline = true
        val feedback = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        PeqFilterType.entries.forEach { type ->
            val option = LinearLayout(context).apply {
                id = PeqFilterTypeDialog.buttonId(type)
                orientation = VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = 0
                minimumHeight = dp(66)
                setPadding(0, dp(7), 0, dp(7))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                contentDescription = PeqBandAdapter.typeLabel(context, type)
                if (feedback != 0) foreground = context.getDrawable(feedback)
                setOnClickListener {
                    if (this@PeqFilterSelector.isEnabled && isEnabled) setSelection(type, notify = true)
                }
            }
            val label = TextView(context).apply {
                text = PeqBandAdapter.typeLabel(context, type)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(context).apply {
                setImageResource(iconId(type))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(dp(28), dp(20)).apply { topMargin = dp(5) }
            }
            option.addView(label)
            option.addView(icon)
            options[type] = Option(option, label, icon)
            addView(option)
        }
        render()
        setEnabled(isEnabled)
    }

    /** Restoring or binding a value is silent; only an explicit notify dispatches a draft edit. */
    fun setSelection(type: PeqFilterType?, notify: Boolean = false) {
        if (selection == type) return
        selection = type
        render()
        if (notify && type != null) onTypeSelected?.invoke(type)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // Avoid accessing subclass fields while Android is constructing the base View.
        for (index in 0 until childCount) {
            val option = getChildAt(index) as? LinearLayout ?: continue
            option.isEnabled = enabled
            option.alpha = if (enabled) 1f else 0.38f
            for (child in 0 until option.childCount) option.getChildAt(child).isEnabled = enabled
        }
    }

    private fun render() {
        options.forEach { (type, option) ->
            val selected = selection == type
            option.view.isSelected = selected
            option.view.setBackgroundColor(when {
                !selected -> Color.TRANSPARENT
                monitoring -> monitorBackground
                else -> selectedBackground
            })
            val foreground = when {
                !selected -> normalText
                monitoring -> Color.WHITE
                else -> selectedText
            }
            option.label.setTextColor(foreground)
            option.icon.imageTintList = ColorStateList.valueOf(foreground)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun iconId(type: PeqFilterType): Int = when (type) {
        PeqFilterType.PEAK -> R.drawable.ic_peq_filter_peak
        PeqFilterType.LOW_SHELF -> R.drawable.ic_peq_filter_low_shelf
        PeqFilterType.HIGH_SHELF -> R.drawable.ic_peq_filter_high_shelf
        PeqFilterType.LOW_PASS -> R.drawable.ic_peq_filter_low_pass
        PeqFilterType.HIGH_PASS -> R.drawable.ic_peq_filter_high_pass
        PeqFilterType.NOTCH -> R.drawable.ic_peq_filter_notch
        PeqFilterType.BAND_PASS -> R.drawable.ic_peq_filter_band_pass
    }
}
