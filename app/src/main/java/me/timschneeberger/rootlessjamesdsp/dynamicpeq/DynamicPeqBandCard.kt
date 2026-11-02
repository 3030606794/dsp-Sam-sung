package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassControlUi

internal class DynamicPeqBandCard(
    context: Context,
    initial: DynamicPeqBand,
    private val index: Int,
    expanded: Boolean,
    private val changed: (DynamicPeqBand) -> Unit,
    private val delete: (DynamicPeqBand) -> Unit,
    private val trackingChanged: (Boolean) -> Unit,
    private val editFinished: () -> Unit,
    private val expandedChanged: (String, Boolean) -> Unit
) : MaterialCardView(context) {
    private var band = initial
    private var opened = expanded
    private var advanced = false
    private var binding = false
    private var lastTelemetryColor: Int? = null
    private val column = context.column()
    private val details = context.column()
    private val advancedControls = context.column()
    private val dynamicControls = context.column()
    private val title = context.text("", 14f, true).apply {
        maxLines = 1
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 14, 1,
            android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val summary = context.text("", 11f).apply {
        setTextColor(context.mutedColor()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val reduction = context.text("压低 0.00 dB", 11f).apply { setTextColor(context.primaryColor()); gravity = Gravity.END }
    private val power = context.action("").apply {
        id = R.id.dynamic_peq_band_power
        icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_twotone_power_settings_24dp)
        iconPadding = 0; iconSize = context.dp(24); setPadding(context.dp(10), 0, context.dp(10), 0)
        setOnClickListener { edit(band.copy(enabled = !band.enabled)) }
    }
    private val expand = context.action("").apply {
        id = R.id.dynamic_peq_band_expand; textSize = 20f
        setOnClickListener { setExpanded(!opened, true) }
    }
    private val dynamics = SpringMaterialSwitch(context).apply {
        id = R.id.dynamic_peq_band_dynamic; contentDescription = "频段动态控制"
        minimumHeight = 0; minHeight = 0
        background = null
        scaleX = 46f / 52f; scaleY = 46f / 52f
        springEnabled = true
        isSaveEnabled = false
        isChecked = band.dynamicEnabled
        setOnCheckedChangeListener { _, checked -> if (!binding) edit(band.copy(dynamicEnabled = checked)) }
    }
    private val controls = linkedMapOf<Int, DynamicPeqParameterControl>()
    private val advancedButton = context.action("响应速度与软拐点  ﹀") {
        advanced = !advanced
        TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(160))
        advancedControls.isVisible = advanced
        renderAdvancedTitle()
    }
    private val typeButtons = mutableListOf<MaterialButton>()

    init {
        tag = band.id
        // Keep the same MaterialCard theme defaults as PeqBandCardView / peq_band_row.xml.
        preventCornerOverlap = false; useCompatPadding = false; clipToOutline = true
        setContentPadding(0, 0, 0, 0)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val header = context.row()
        val number = context.text("${index + 1}", 14f, true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.peq_band_number_background)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSecondaryContainer))
            contentDescription = "频段 ${index + 1}"
        }
        header.addView(number, LinearLayout.LayoutParams(context.dp(38), LinearLayout.LayoutParams.MATCH_PARENT))
        val heading = context.column().apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(context.dp(10), 0, 0, 0)
            addFull(title); addFull(summary)
            isClickable = true; isFocusable = true
            setOnClickListener { setExpanded(!opened, true) }
        }
        header.addWeighted(heading, context.dp(40))
        header.addView(power, LinearLayout.LayoutParams(context.dp(44), context.dp(40)))
        header.addView(context.action("").apply {
            id = R.id.dynamic_peq_band_delete
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_twotone_delete_24dp)
            iconTint = ColorStateList.valueOf(Color.rgb(211, 47, 47)); iconPadding = 0; iconSize = context.dp(20)
            setPadding(context.dp(10), 0, context.dp(10), 0)
            contentDescription = "删除频段 ${index + 1}"
            setOnClickListener { delete(band) }
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        header.addView(expand, LinearLayout.LayoutParams(context.dp(36), context.dp(40)))
        column.addFull(header, context.dp(40))
        column.addFull(details)
        details.addFull(divider(), context.dp(1))

        val types = MaterialButtonToggleGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
        listOf("峰值", "低架", "高架").forEachIndexed { type, label ->
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = label; textSize = 12f
                minWidth = 0; minimumWidth = 0; insetTop = 0; insetBottom = 0
                icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context,
                    when (type) { 1 -> R.drawable.ic_peq_filter_low_shelf; 2 -> R.drawable.ic_peq_filter_high_shelf; else -> R.drawable.ic_peq_filter_peak })
                iconSize = context.dp(18); iconPadding = context.dp(4); setPadding(context.dp(4), 0, context.dp(4), 0)
            }
            typeButtons += button
            types.addView(button, LinearLayout.LayoutParams(0, context.dp(36), 1f))
            button.isChecked = band.type == type
        }
        types.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !binding) {
                val type = typeButtons.indexOfFirst { it.id == id }
                if (type >= 0 && type != band.type) edit(band.copy(type = type))
            }
        }
        details.addView(types, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(36)).apply {
            setMargins(context.dp(8), context.dp(6), context.dp(8), context.dp(4))
        })
        control(details, R.id.dynamic_peq_gain, "基础增益", -24.0, 24.0, .1, band.gain, "dB", gain = true) { band.copy(gain = it) }
        control(details, R.id.dynamic_peq_q, "Q 值", .1, 30.0, .01, band.q, "", log = true) { band.copy(q = it) }
        control(details, R.id.dynamic_peq_frequency, "频率", 20.0, 20000.0, 1.0, band.frequency, "Hz", log = true,
            format = ::dynamicFrequency) { band.copy(frequency = it) }

        details.addFull(divider(), context.dp(1))
        val dynamicRow = context.row().apply { setPadding(context.dp(12), 0, context.dp(10), 0) }
        dynamicRow.addView(context.text("动态控制", 13f, true), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44)))
        dynamicRow.addWeighted(reduction)
        // Match preference_materialswitch's compact 30dp track while keeping a 48dp touch area.
        val switchTouch = FrameLayout(context).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { dynamics.performClick() }
            addView(dynamics, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(30), Gravity.CENTER))
        }
        dynamicRow.addView(switchTouch, LinearLayout.LayoutParams(context.dp(54), context.dp(48)).apply { marginStart = context.dp(4) })
        details.addFull(dynamicRow)
        details.addFull(dynamicControls)
        control(dynamicControls, R.id.dynamic_peq_threshold, "阈值", -90.0, 0.0, .5, band.threshold, "dBFS") { band.copy(threshold = it) }
        control(dynamicControls, R.id.dynamic_peq_ratio, "压缩比", 1.0, 20.0, .1, band.ratio, "", format = { "${dynamicNumber(it)}:1" }) { band.copy(ratio = it) }
        control(dynamicControls, R.id.dynamic_peq_range, "最大压低", 0.0, 24.0, .1, band.range, "dB") { band.copy(range = it) }
        dynamicControls.addFull(advancedButton, context.dp(40))
        dynamicControls.addFull(advancedControls)
        control(advancedControls, R.id.dynamic_peq_attack, "启动时间", .1, 500.0, .1, band.attack, "ms", log = true) { band.copy(attack = it) }
        control(advancedControls, R.id.dynamic_peq_release, "释放时间", 5.0, 3000.0, 1.0, band.release, "ms", log = true) { band.copy(release = it) }
        control(advancedControls, R.id.dynamic_peq_knee, "软拐点", 0.0, 24.0, .5, band.knee, "dB") { band.copy(knee = it) }
        advancedControls.isVisible = advanced
        details.addFull(View(context), context.dp(4))
        setExpanded(expanded, false)
        render(null)
        GlassControlUi.decorate(dynamics)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorOutlineVariant))
    }
        .also { it.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)) }

    private fun control(parent: LinearLayout, id: Int, label: String, min: Double, max: Double,
                        step: Double, initial: Double, unit: String, log: Boolean = false,
                        gain: Boolean = false, format: ((Double) -> String)? = null,
                        update: (Double) -> DynamicPeqBand) {
        val view = DynamicPeqParameterControl(context, label, min, max, step, initial, unit, log, gain, format,
            trackingChanged = trackingChanged, editFinished = editFinished) { edit(update(it)) }
        view.id = id; controls[id] = view
        parent.addFull(view, context.dp(maxOf(34, (resources.configuration.fontScale * 28).toInt())))
    }

    private fun edit(next: DynamicPeqBand) {
        if (next == band) return
        val previous = band; band = next
        render(previous)
        changed(next)
    }

    private fun render(previous: DynamicPeqBand?) {
        binding = true
        if (previous == null || previous.frequency != band.frequency || previous.type != band.type)
            title.text = "${dynamicFrequency(band.frequency)} · ${dynamicType(band.type)}"
        if (previous == null || previous.q != band.q || previous.gain != band.gain)
            summary.text = "Q ${dynamicNumber(band.q)} · 基础 ${if (band.gain > 0) "+" else ""}${dynamicNumber(band.gain)} dB"
        if (previous == null || previous.enabled != band.enabled) {
            power.iconTint = ColorStateList.valueOf(if (band.enabled) Color.rgb(46, 170, 83) else Color.GRAY)
            power.isSelected = band.enabled
            power.contentDescription = if (band.enabled) "关闭频段 ${index + 1}" else "开启频段 ${index + 1}"
        }
        if (previous == null || previous.dynamicEnabled != band.dynamicEnabled) {
            if (dynamics.isChecked != band.dynamicEnabled) dynamics.isChecked = band.dynamicEnabled
            dynamicControls.isVisible = band.dynamicEnabled
            reduction.text = if (!band.dynamicEnabled) "仅基础均衡" else "压低 0.00 dB"
        }
        if (previous == null || previous.type != band.type)
            typeButtons.forEachIndexed { type, button -> if (button.isChecked != (type == band.type)) button.isChecked = type == band.type }
        binding = false
    }

    fun setExpanded(value: Boolean, animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(180))
        opened = value; details.isVisible = value
        expand.text = if (value) "⌃" else "⌄"
        expand.contentDescription = if (value) "收起频段 ${index + 1}" else "展开频段 ${index + 1}"
        expandedChanged(band.id, value)
    }

    fun setTelemetry(amount: Double, detector: Double?, active: Boolean, available: Boolean = true) {
        if (!band.dynamicEnabled) return
        val text = when {
            !available -> "超出采样范围"
            !band.enabled -> "频段已关闭"
            !active -> "压低 0.00 dB"
            else -> "压低 ${dynamicNumber(amount, 2)} dB"
        }
        if (reduction.text.toString() != text) reduction.text = text
        reduction.contentDescription = text +
            (if (active && detector != null) "，检测电平 ${dynamicNumber(detector)} dBFS" else "")
        val color = if (active && amount > .05) Color.rgb(25, 118, 210) else context.primaryColor()
        if (lastTelemetryColor != color) { lastTelemetryColor = color; reduction.setTextColor(color) }
    }

    private fun renderAdvancedTitle() { advancedButton.text = if (advanced) "响应速度与软拐点  ﹂" else "响应速度与软拐点  ﹀" }
}
