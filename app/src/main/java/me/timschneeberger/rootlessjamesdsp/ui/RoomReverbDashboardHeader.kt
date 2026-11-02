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
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbParameters
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import kotlin.math.min

/** Two jointly locked parameter views and one focus-driven, independently powered live view. */
internal class RoomReverbDashboardHeader(context: Context,
    private val onMonitorChanged: (Boolean) -> Unit,
    private val onPinChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val display = context.applicationContext.getSharedPreferences("room_reverb_dashboard_display", Context.MODE_PRIVATE)
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
    private val parameter = RoomReverbParameterChart(context)
    private val illustration = RoomReverbIllustrationChart(context)
    private val realtime = RoomReverbFocusRealtimeChart(context)
    private val dynamicBody = LinearLayout(context)
    private val readouts = StableDashboardReadouts(context)
    private var monitoring = false
    private var pinned = true
    private var expanded = display.getBoolean("expanded", true)
    private var locked = display.getBoolean("static_locked", false)
    private var staticKey = display.getString("static_focus_key", RoomReverbParameters.ENABLED)
        ?.takeIf { RoomReverbChartFocus.fromKey(it) != null } ?: RoomReverbParameters.ENABLED
    private var liveKey = staticKey
    private var liveFocus = RoomReverbChartFocus.fromKey(liveKey) ?: RoomReverbChartFocus.ROOM
    private var configuration = RoomReverbDashboardModel.from(RoomReverbController.preferences(context).all)
    private var latest: RoomReverbDashboardRuntimeMonitor.Sample? = null
    private var pausedLabel = "实时采样已关闭"
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun title() = TextView(context).apply {
        textSize = 11f; setTextColor(ink); setTypeface(null, Typeface.BOLD)
        includeFontPadding = false; fontFeatureSettings = "tnum"
        minHeight = dp(18)
        setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(6)); setBackgroundColor(surface)
        isBaselineAligned = false
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
        dynamicBody.addView(readouts, LayoutParams(-1, readouts.fixedContentHeight))
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
        val fixed = paddingTop + paddingBottom + maxOf(leftTitle.measuredHeight, rightHeading.measuredHeight) +
            liveHeading.measuredHeight + if (expanded) readouts.fixedContentHeight else 0
        val desiredStatic = dp(144); val desiredLive = if (expanded) dp(150) else 0
        val factor = min(1f, (limit - fixed).coerceAtLeast(0).toFloat() / (desiredStatic + desiredLive).coerceAtLeast(1))
        val staticHeight = (desiredStatic * factor).toInt()
        val liveHeight = (desiredLive * factor).toInt()
        parameter.layoutParams.height = staticHeight; illustration.layoutParams.height = staticHeight
        realtime.layoutParams.height = liveHeight
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(min(limit, fixed + staticHeight + liveHeight), MeasureSpec.EXACTLY))
    }

    fun followParameter(key: String) {
        val focus = RoomReverbChartFocus.fromKey(key) ?: return
        liveKey = key; liveFocus = focus
        realtime.setFocus(focus, key); liveTitle.text = focus.liveTitle
        if (!locked) setStaticFocus(key)
        renderNumbers()
    }
    private fun setStaticFocus(key: String) {
        val focus = RoomReverbChartFocus.fromKey(key) ?: return
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
    fun setConfiguration(model: RoomReverbDashboardModel) {
        configuration = model
        parameter.setConfiguration(model); illustration.setConfiguration(model)
        realtime.setConfiguration(model)
        renderNumbers()
    }
    fun showPaused(label: String) {
        latest = null; pausedLabel = label; realtime.pause(label); renderNumbers()
    }
    fun showRuntime(sample: RoomReverbDashboardRuntimeMonitor.Sample) {
        if (!monitoring) return
        latest = sample
        realtime.showSample(sample); renderNumbers()
    }
    private fun renderNumbers() {
        val first = when (liveFocus) {
            RoomReverbChartFocus.MATCH -> "真实 A/B 预热与测量进度 · 固定衰减"
            RoomReverbChartFocus.TONE -> "原声 / 总湿声 / 试听输出 · 实时 FFT"
            RoomReverbChartFocus.WIDTH -> "真实总湿声 M/S 能量 · 左右相关度"
            else -> "原声 / 保存总湿声 / 试听输出 · 采样窗口 RMS"
        }
        val model = configuration
        val second = "${model.rate} Hz${if (model.actualRate) " 配置采样率" else " 参考采样率"} · " +
            if (model.professional) "专业场景混响 · 静图为参数预测" else "原房型混响 · 静图为参数预测"
        val sample = latest.takeIf { monitoring }
        val third = when {
            !monitoring || sample == null -> pausedLabel
            !sample.output.connected -> "驱动未连接 · 参数预览保留"
            sample.output.globalIssue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
            sample.output.globalIssue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
            sample.pcm != null -> "同一采样窗口 · 湿声取保存配置，输出取当前试听"
            sample.levelUnavailableReason == null -> "真实输出电平 · 原声与湿声等待分离采样"
            else -> sample.pcmUnavailableReason ?: sample.unavailableReason ?: "等待真实采样"
        }
        readouts.setLines(listOf(first, second, third))
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
