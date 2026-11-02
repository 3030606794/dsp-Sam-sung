package me.timschneeberger.rootlessjamesdsp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.FieldChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.FieldPageRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.FieldParameters
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import java.util.Locale
import kotlin.math.min

/** Two jointly locked parameter views and one focus-driven, independently powered live view. */
internal class FieldVisualizationHeader(context: Context,
    private val onMonitorChanged: (Boolean) -> Unit,
    private val onPinChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val display = context.applicationContext.getSharedPreferences("field_visualization_display", Context.MODE_PRIVATE)
    private val power = ImageButton(context)
    private val pin = ImageButton(context)
    val toolbarActions: List<View> = listOf(power, pin)
    private val leftTitle = title()
    private val rightTitle = title()
    private val lockText = title()
    private val liveTitle = title()
    private val foldText = title()
    private val leftColumn = LinearLayout(context)
    private val rightColumn = LinearLayout(context)
    private val rightHeading = LinearLayout(context)
    private val liveHeading = LinearLayout(context)
    private val parameter = FieldParameterChart(context)
    private val illustration = FieldIllustrationChart(context)
    private val realtime = FieldRealtimeChart(context)
    private val dynamicBody = LinearLayout(context)
    private val lines = List(3) { title() }
    private var monitoring = false
    private var pinned = true
    private var expanded = display.getBoolean("expanded", true)
    private var locked = display.getBoolean("static_locked", false)
    private var staticKey = display.getString("static_focus_key", FieldParameters.width.key)
        ?.takeIf { FieldChartFocus.fromKey(it) != null } ?: FieldParameters.width.key
    private var liveKey = staticKey
    private var liveFocus = FieldChartFocus.fromKey(liveKey) ?: FieldChartFocus.WIDTH
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var latest: FieldPageRuntimeMonitor.Sample? = null
    private var pausedLabel = "实时采样已关闭"
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun title() = TextView(context).apply {
        textSize = 11f; setTextColor(ink); setTypeface(null, Typeface.BOLD)
        setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(6)); setBackgroundColor(surface)
        listOf(power, pin).forEach { button ->
            button.minimumWidth = 0; button.minimumHeight = 0
            button.setPadding(dp(8), dp(8), dp(8), dp(8))
            button.background = ColorDrawable(Color.TRANSPARENT)
            button.backgroundTintList = null; button.foreground = null
        }
        power.setImageResource(R.drawable.ic_twotone_power_settings_24dp)
        power.setOnClickListener { onMonitorChanged(!monitoring) }
        pin.setImageResource(R.drawable.ic_peq_pin)
        pin.setOnClickListener { onPinChanged(!pinned) }
        leftColumn.orientation = VERTICAL; rightColumn.orientation = VERTICAL
        leftTitle.setPadding(0, 0, 0, dp(4))
        leftColumn.addView(leftTitle, LayoutParams(-1, -2))
        leftColumn.addView(parameter, LayoutParams(-1, dp(144)))
        rightHeading.orientation = HORIZONTAL; rightHeading.gravity = Gravity.CENTER_VERTICAL
        rightHeading.isBaselineAligned = true
        rightTitle.setPadding(0, 0, 0, dp(4))
        lockText.setPadding(dp(5), 0, 0, dp(4)); lockText.setTextColor(accent)
        lockText.background = ColorDrawable(Color.TRANSPARENT)
        lockText.setOnClickListener {
            locked = !locked
            display.edit().putBoolean("static_locked", locked).apply()
            // Unlocking retains this pair until the next intentional parameter focus.
            updateLock()
        }
        rightHeading.addView(rightTitle, LayoutParams(0, -2, 1f))
        rightHeading.addView(lockText, LayoutParams(-2, -2))
        rightColumn.addView(rightHeading, LayoutParams(-1, -2))
        rightColumn.addView(illustration, LayoutParams(-1, dp(144)))
        val pair = LinearLayout(context).apply { orientation = HORIZONTAL; isBaselineAligned = false }
        pair.addView(leftColumn, LayoutParams(0, -2, 1f))
        pair.addView(View(context), LayoutParams(dp(8), 1))
        pair.addView(rightColumn, LayoutParams(0, -2, 1f))
        addView(pair, LayoutParams(-1, -2))
        liveHeading.orientation = HORIZONTAL; liveHeading.gravity = Gravity.CENTER_VERTICAL
        liveHeading.isBaselineAligned = true
        liveTitle.setPadding(0, dp(5), 0, dp(3))
        foldText.setPadding(dp(8), dp(5), 0, dp(3)); foldText.setTextColor(accent)
        foldText.background = ColorDrawable(Color.TRANSPARENT)
        foldText.setOnClickListener {
            expanded = !expanded
            display.edit().putBoolean("expanded", expanded).apply()
            if (!expanded) realtime.cancelPendingInputEvents()
            updateFold()
        }
        liveHeading.addView(liveTitle, LayoutParams(0, -2, 1f))
        liveHeading.addView(foldText, LayoutParams(-2, -2))
        addView(liveHeading, LayoutParams(-1, -2))
        dynamicBody.orientation = VERTICAL
        dynamicBody.addView(realtime, LayoutParams(-1, dp(150)))
        lines.forEachIndexed { index, row ->
            row.textSize = if (index == 0) 11f else 10f
            if (index > 0) row.setTypeface(null, Typeface.NORMAL)
            row.setTextColor(if (index == 0) accent else ColorUtils.setAlphaComponent(ink, 190))
            row.setPadding(0, dp(1), 0, dp(1))
            dynamicBody.addView(row, LayoutParams(-1, -2))
        }
        addView(dynamicBody, LayoutParams(-1, -2))
        setStaticFocus(staticKey)
        realtime.setFocus(liveFocus, liveKey)
        liveTitle.text = liveFocus.liveTitle
        updateLock(); updateFold(); setControls(false, true)
        showPaused(pausedLabel)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = halfWindowHeight().coerceAtLeast(1)
        val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val limit = min(cap, parentLimit)
        val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val columnWidth = MeasureSpec.makeMeasureSpec(((width - dp(8)) / 2).coerceAtLeast(1), MeasureSpec.EXACTLY)
        val fullWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        leftTitle.measure(columnWidth, unbounded); rightHeading.measure(columnWidth, unbounded)
        liveHeading.measure(fullWidth, unbounded)
        if (expanded) lines.forEach { it.measure(fullWidth, unbounded) }
        val fixed = paddingTop + paddingBottom + maxOf(leftTitle.measuredHeight, rightHeading.measuredHeight) +
            liveHeading.measuredHeight + if (expanded) lines.sumOf { it.measuredHeight } else 0
        val desiredStatic = dp(144); val desiredLive = if (expanded) dp(150) else 0
        val factor = min(1f, (limit - fixed).coerceAtLeast(0).toFloat() / (desiredStatic + desiredLive).coerceAtLeast(1))
        val staticHeight = (desiredStatic * factor).toInt()
        val liveHeight = (desiredLive * factor).toInt()
        parameter.layoutParams.height = staticHeight; illustration.layoutParams.height = staticHeight
        realtime.layoutParams.height = liveHeight
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(min(limit, fixed + staticHeight + liveHeight), MeasureSpec.EXACTLY))
    }

    fun followParameter(key: String) {
        val focus = FieldChartFocus.fromKey(key) ?: return
        liveKey = key; liveFocus = focus
        realtime.setFocus(focus, key); liveTitle.text = focus.liveTitle
        if (!locked) setStaticFocus(key)
        renderNumbers()
    }
    private fun setStaticFocus(key: String) {
        val focus = FieldChartFocus.fromKey(key) ?: return
        staticKey = key; leftTitle.text = focus.leftTitle; rightTitle.text = focus.rightTitle
        parameter.setFocus(focus, key); illustration.setFocus(focus, key)
        display.edit().putString("static_focus_key", key).apply()
    }
    private fun updateLock() {
        lockText.text = if (locked) "取消定位" else "定位"
        lockText.contentDescription = if (locked) "取消两个静态图的定位，下一次调节恢复自动换图" else "一起定位两个静态图，保持图纸类型"
    }
    private fun updateFold() {
        foldText.text = if (expanded) "折叠" else "展开"
        foldText.contentDescription = "${foldText.text}动态图与读数；实时采样由电源按钮独立控制"
        dynamicBody.visibility = if (expanded) VISIBLE else GONE
        updateActive(); requestLayout()
    }
    fun setControls(monitoring: Boolean, pinned: Boolean) {
        this.monitoring = monitoring; this.pinned = pinned
        power.setColorFilter(if (monitoring) accent else ColorUtils.setAlphaComponent(ink, 100))
        power.contentDescription = if (monitoring) "实时采样已开启，点击关闭" else "实时采样已关闭，点击开启"
        pin.setColorFilter(if (pinned) accent else ColorUtils.setAlphaComponent(ink, 150))
        pin.contentDescription = if (pinned) "图纸已固定，点击随参数列表滚动" else "固定图纸区域"
    }
    fun setConfiguration(values: Map<String, *>, sampleRate: Int? = null) {
        val nextRate = sampleRate?.takeIf { it in 8000..768000 } ?: rate
        // Support and audition state can change without a stored parameter changing.
        this.values = values.toMap()
        rate = nextRate
        parameter.setConfiguration(this.values, rate); illustration.setConfiguration(this.values, rate)
        realtime.setConfiguration(this.values, rate)
        renderNumbers()
    }
    fun showPaused(label: String) {
        latest = null; pausedLabel = label; realtime.pause(label); renderNumbers()
    }
    fun showRuntime(sample: FieldPageRuntimeMonitor.Sample) {
        if (!monitoring) return
        latest = sample
        sample.reading?.rate?.takeIf { it in 8000..768000 && it != rate }?.let { setConfiguration(values, it) }
        realtime.showSample(sample); renderNumbers()
    }
    private fun renderNumbers() {
        val sample = latest.takeIf { monitoring }
        val r = sample?.reading?.takeIf { sample.unavailableReason == null && it.meterReady &&
            it.processing == OutputRuntimeStatus.Processing.ACTIVE }
        val a = r?.advanced?.takeIf { it.ready }
        fun fmt(value: Double?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"
        fun db(value: Int?) = if (value == Int.MIN_VALUE) "−∞" else fmt(value?.div(100.0))
        val correlation = r?.correlation?.takeIf { it in -10000..10000 }?.div(10000.0)
        val first = when (liveFocus) {
            FieldChartFocus.GAIN, FieldChartFocus.FOCUS -> "Mid ${db(r?.midRms)} · Side ${db(r?.sideRms)} dBFS"
            FieldChartFocus.DYNAMIC -> "Side 衰减 ${fmt(a?.dynamicReduction?.div(100.0))} dB"
            FieldChartFocus.GUARD -> "相关度 ${fmt(correlation)} · 空间回收 ${fmt(a?.guardRecovery?.div(10.0))}%"
            FieldChartFocus.TRANSIENT -> "瞬态保护 ${fmt(a?.transientProtection?.div(10.0))}%"
            FieldChartFocus.AUDITION -> "相关度 ${fmt(correlation)} · 折叠差 ${db(a?.monoFoldDifference)} dB"
            else -> "相关度 ${fmt(correlation)} · Mid ${db(r?.midRms)} / Side ${db(r?.sideRms)} dBFS"
        }
        val state = sample?.unavailableReason ?: if (r == null) pausedLabel
            else sample.visualizationReason ?: "真实成像监测 · 试听之前"
        val second = if (r != null) "${r.rate} Hz · 输出峰值 ${db(r.peak)} dBFS · ${if (FieldParameters.mode(values) == 0) "原版兼容" else "专业 M/S"}"
            else "${if (FieldParameters.mode(values) == 0) "原版兼容" else "专业 M/S"} · 参数模型保留"
        listOf(first, second, state).forEachIndexed { index, text ->
            if (lines[index].text.toString() != text) lines[index].text = text
            lines[index].contentDescription = text
        }
    }
    private fun updateActive() {
        parameter.setActive(isAttachedToWindow); illustration.setActive(isAttachedToWindow)
        realtime.setActive(isAttachedToWindow && expanded)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateActive() }
    override fun onDetachedFromWindow() { parameter.setActive(false); illustration.setActive(false); realtime.setActive(false); super.onDetachedFromWindow() }
    private fun halfWindowHeight(): Int {
        var current: Context? = context
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (current != null && current !is Activity && seen.add(current)) current = (current as? ContextWrapper)?.baseContext
        val host = current as? Activity
        val sizes = ArrayList<Int>(4)
        if (host != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val metrics = host.windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                sizes += metrics.bounds.height() - insets.top - insets.bottom
            }
            val frame = Rect(); host.window.decorView.getWindowVisibleDisplayFrame(frame)
            if (frame.height() > 0) sizes += frame.height()
            host.findViewById<View>(android.R.id.content)?.height?.takeIf { it > 0 }?.let { sizes += it }
        }
        if (sizes.isEmpty()) sizes += (resources.configuration.screenHeightDp * resources.displayMetrics.density).toInt()
        return sizes.filter { it > 0 }.minOrNull()?.div(2) ?: resources.displayMetrics.heightPixels / 2
    }
}
