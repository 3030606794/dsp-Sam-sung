package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.peq.PeqEditorPreferences
import me.timschneeberger.rootlessjamesdsp.peq.PeqLinkedRealtimeChart
import me.timschneeberger.rootlessjamesdsp.peq.PeqRepository
import me.timschneeberger.rootlessjamesdsp.peq.PeqRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.peq.PeqState
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch

/** Home-only owner: opening a card never enables its audio effect. */
class EqualizerHomeSpectrumView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val display = context.getSharedPreferences("equalizer_home_spectrum", Context.MODE_PRIVATE)
    private var peq = true
    private var configured = false
    private var hostState: () -> Pair<Boolean, Boolean> = { false to false }
    private var audioPreferences: SharedPreferences? = null
    private var stylePreferences: SharedPreferences? = null
    private var spectrumPreferences: SpectrumDisplayPreferences? = null
    private var parentOwner: LifecycleOwner? = null
    private var registered = false
    private var captureOwner: CaptureOwner? = null
    private var eqMonitor: EqRuntimeMonitor? = null
    private var peqChart: PeqLinkedRealtimeChart? = null
    private var eqChart: EqLinkedRealtimeChart? = null
    private var currentPeqState = PeqState(emptyList())
    private var cachedPeqJson: String? = null
    private var peqStateCached = false
    private var refreshingPeqState = false
    private var restoringToggle = false
    private var scrollPaused = false
    private val visibleRect = Rect()
    private val graphHost = FrameLayout(context)
    private val status = TextView(context).apply {
        textSize = 10f; includeFontPadding = false; maxLines = 2
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private val toggle = SpringMaterialSwitch(context).apply {
        id = View.generateViewId()
        text = ""; contentDescription = "显示动图"; background = null
        minimumHeight = 0; minimumWidth = 0; isSaveEnabled = false
        setPadding(0, 0, 0, 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        springEnabled = true
        scaleX = .65f; scaleY = .65f
    }
    private val toggleHost = CompactToggleHost()
    private val resumeAfterScroll = Runnable {
        scrollPaused = false
        syncCapture()
    }
    private val observer = LifecycleEventObserver { _, _ -> syncCapture() }
    private val scrollObserver = ViewTreeObserver.OnScrollChangedListener {
        if (configured && isAttachedToWindow) {
            if (!scrollPaused) {
                scrollPaused = true
                syncCapture()
            }
            removeCallbacks(resumeAfterScroll)
            postDelayed(resumeAfterScroll, 150L)
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshAppearance()
        syncCapture()
    }
    private val audioPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (peq && (key == null || key == PeqRepository.KEY_STATE)) refreshPeqConfiguration()
        syncCapture()
    }

    private class CaptureOwner : LifecycleOwner {
        val state = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = state
    }

    /** Uniformly scale the same 52×30dp home switch inside its separate 44dp hit target. */
    private inner class CompactToggleHost : FrameLayout(context) {
        init {
            id = View.generateViewId()
            clipChildren = false; clipToPadding = false
            isClickable = true; isFocusable = true
            background = null; foreground = null
            contentDescription = "显示动图"
            addView(toggle, FrameLayout.LayoutParams(dp(52), dp(30)))
            setOnClickListener { toggle.performClick() }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            toggle.measure(MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(30), MeasureSpec.EXACTLY))
            setMeasuredDimension(resolveSize(dp(44), widthMeasureSpec), resolveSize(dp(44), heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val childWidth = toggle.measuredWidth
            val childHeight = toggle.measuredHeight
            val x = (width - childWidth) / 2
            val y = (height - childHeight) / 2
            toggle.pivotX = childWidth / 2f; toggle.pivotY = childHeight / 2f
            toggle.layout(x, y, x + childWidth, y + childHeight)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Switch"
            info.isCheckable = true
            info.isChecked = toggle.isChecked
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(8))
        val heading = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        heading.addView(TextView(context).apply {
            text = "显示动图"; textSize = 13f; includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            labelFor = toggleHost.id
            isClickable = true; isFocusable = true
            setOnClickListener { toggle.performClick() }
        }, LayoutParams(0, dp(44), 1f))
        heading.addView(toggleHost, LayoutParams(dp(44), dp(44)))
        heading.addView(TextView(context).apply {
            text = "频谱设置"; textSize = 13f; includeFontPadding = false
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            background = null; isClickable = true; isFocusable = true
            setOnClickListener { SpectrumAppearanceDialog.show(context, namespace()) }
        }, LayoutParams(dp(76), dp(44)))
        addView(heading, LayoutParams(-1, dp(44)))
        addView(graphHost, LayoutParams(-1, dp(152)))
        addView(status, LayoutParams(-1, dp(28)))
        toggle.setOnCheckedChangeListener { _, checked ->
            if (restoringToggle) return@setOnCheckedChangeListener
            if (configured) display.edit().putBoolean("${namespace()}.show", checked).apply()
            syncCapture()
        }
        graphHost.visibility = GONE; status.visibility = GONE
    }

    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun namespace() = if (peq) "peq" else "eq"

    fun configure(peq: Boolean, hostState: () -> Pair<Boolean, Boolean>) {
        val changed = !configured || this.peq != peq
        this.hostState = hostState
        if (changed) {
            release()
            this.peq = peq
            configured = true
            peqStateCached = false
            cachedPeqJson = null
            audioPreferences = context.getSharedPreferences(if (peq) Constants.PREF_GEQ else Constants.PREF_EQ,
                Context.MODE_PRIVATE)
            stylePreferences = context.getSharedPreferences(if (peq) PeqEditorPreferences.PREFERENCES else "eq_linked_display",
                Context.MODE_PRIVATE)
            spectrumPreferences = SpectrumDisplayPreferences(context, namespace())
            graphHost.removeAllViews()
            peqChart = if (peq) PeqLinkedRealtimeChart(context).also { graphHost.addView(it,
                android.widget.FrameLayout.LayoutParams(-1, -1)) } else null
            eqChart = if (!peq) EqLinkedRealtimeChart(context).also { graphHost.addView(it,
                android.widget.FrameLayout.LayoutParams(-1, -1)) } else null
        }
        if (isAttachedToWindow) attachOwner()
        refreshPeqConfiguration()
        refreshAppearance()
        syncCapture()
    }

    private fun refreshAppearance() {
        if (!configured) return
        val requested = display.getBoolean("${namespace()}.show", false)
        if (toggle.isChecked != requested) {
            restoringToggle = true
            try { toggle.setCheckedImmediately(requested) } finally { restoringToggle = false }
        }
        val options = spectrumPreferences?.load() ?: SpectrumDisplayOptions()
        val styles = stylePreferences?.all.orEmpty()
        peqChart?.apply {
            setChartStyle(styles["realtimeChartStyle"] as? Int ?: 0)
            setDisplayOptions(options)
        }
        eqChart?.apply {
            setChartStyle(styles["realtime_style"] as? Int ?: 0)
            setDisplayOptions(options)
        }
    }

    private fun refreshPeqConfiguration() {
        if (!configured || !peq || refreshingPeqState) return
        val preferences = audioPreferences ?: return
        val encoded = preferences.all[PeqRepository.KEY_STATE] as? String
        if (peqStateCached && cachedPeqJson == encoded) return
        refreshingPeqState = true
        try {
            currentPeqState = PeqRepository(context).load()
            // Loading a legacy bank can migrate its stored representation.
            cachedPeqJson = preferences.all[PeqRepository.KEY_STATE] as? String
            peqStateCached = true
        } finally { refreshingPeqState = false }
    }

    private fun attachOwner() {
        if (!configured || !isAttachedToWindow) return
        val owner = findViewTreeLifecycleOwner()
        if (parentOwner !== owner) {
            parentOwner?.lifecycle?.removeObserver(observer)
            parentOwner = owner
            owner?.lifecycle?.addObserver(observer)
        }
        if (!registered) {
            display.registerOnSharedPreferenceChangeListener(preferenceListener)
            audioPreferences?.registerOnSharedPreferenceChangeListener(audioPreferenceListener)
            stylePreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
            spectrumPreferences?.register(preferenceListener)
            if (viewTreeObserver.isAlive) viewTreeObserver.addOnScrollChangedListener(scrollObserver)
            registered = true
        }
    }

    private fun shouldCapture(): Boolean {
        val (expanded, enabled) = hostState()
        return configured && !scrollPaused && expanded && enabled && toggle.isChecked && isAttachedToWindow && isShown &&
            hasWindowFocus() &&
            windowVisibility == VISIBLE && graphHost.getGlobalVisibleRect(visibleRect) &&
            parentOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    }

    private fun syncCapture() {
        if (!configured) return
        val (expanded, enabled) = hostState()
        val showBody = expanded && enabled && toggle.isChecked
        val visibility = if (showBody) VISIBLE else GONE
        if (graphHost.visibility != visibility) graphHost.visibility = visibility
        if (status.visibility != visibility) status.visibility = visibility
        if (shouldCapture()) {
            if (captureOwner == null) startCapture()
        } else stopCapture()
    }

    private fun startCapture() {
        val owner = CaptureOwner()
        captureOwner = owner
        owner.state.currentState = Lifecycle.State.CREATED
        peqChart?.setActive(true); eqChart?.setActive(true)
        if (peq) {
            PeqRuntimeMonitor.bind(owner, context, configuration = {
                PeqRuntimeMonitor.Configuration(currentPeqState, hostState().second)
            }, captureVisible = { captureOwner === owner && shouldCapture() }) { frame ->
                if (captureOwner !== owner || !shouldCapture()) return@bind
                if (frame.clear) peqChart?.clear(frame.message)
                frame.sample?.let { peqChart?.setSample(it) }
                peqChart?.setStatus(frame.message)
                if (status.text.toString() != frame.message) status.text = frame.message
            }
        } else {
            eqMonitor = EqRuntimeMonitor(owner, context, onSample = { sample ->
                if (captureOwner === owner && shouldCapture()) {
                    eqChart?.setSample(sample)
                }
            }, onState = { value ->
                if (captureOwner === owner && shouldCapture()) {
                    eqChart?.setRuntimeState(value)
                    if (status.text.toString() != value.label) status.text = value.label
                }
            }).also { it.setEnabled(true) }
        }
        owner.state.currentState = Lifecycle.State.RESUMED
    }

    private fun stopCapture() {
        val owner = captureOwner ?: return
        captureOwner = null
        eqMonitor?.close(); eqMonitor = null
        owner.state.currentState = Lifecycle.State.DESTROYED
        peqChart?.setActive(false); eqChart?.setActive(false)
    }

    /** Release subscriptions; the same recycled preference may attach and resume later. */
    fun release() {
        removeCallbacks(resumeAfterScroll)
        scrollPaused = false
        stopCapture()
        parentOwner?.lifecycle?.removeObserver(observer); parentOwner = null
        if (registered) {
            display.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            audioPreferences?.unregisterOnSharedPreferenceChangeListener(audioPreferenceListener)
            stylePreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            spectrumPreferences?.unregister(preferenceListener)
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollObserver)
            registered = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (isAttachedToWindow) { attachOwner(); refreshPeqConfiguration(); refreshAppearance(); syncCapture() } }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (configured) syncCapture()
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (configured) syncCapture()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (configured) syncCapture()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (configured) syncCapture()
    }
}
