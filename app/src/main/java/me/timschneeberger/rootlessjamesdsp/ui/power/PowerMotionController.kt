package me.timschneeberger.rootlessjamesdsp.ui.power

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.abs
import kotlin.math.pow

/** One temporary, non-interactive full-window overlay for an already committed user toggle. */
class PowerMotionController(
    private val button: FloatingActionButton,
    private val animationsAllowed: Boolean,
    initialState: Boolean,
) {
    data class Snapshot(
        val enabled: Boolean, val style: PowerMotionStyle, val attached: Boolean,
        val resumed: Boolean, val breathing: Boolean, val transientRunning: Boolean,
        val pendingHaptic: Boolean, val pendingRelease: Boolean, val activeAnimatorCount: Int,
        val pressed: Boolean, val scale: Float, val tint: Int, val haloProgress: Float,
    )

    private var config = PowerMotionSettings.read(button.context)
    private var powered = initialState
    private var attached = false
    private var foregroundAllowed = true
    private var lifecycleResumed = true
    private var owner: LifecycleOwner? = null
    private var preferences: SharedPreferences? = null
    private var focusObserver: ViewTreeObserver? = null
    private var pressed = false
    private var generation = 0
    private var pressAnimator: ValueAnimator? = null
    private var transition: ValueAnimator? = null
    private var pendingRelease = false
    private var currentTint = targetColor()
    private var overlayHost: ViewGroup? = null
    private val screenPosition = IntArray(2)
    private val buttonPosition = IntArray(2)
    private val field = PowerFullscreenDrawable(button.resources.displayMetrics.density)
    private val bindOwner = Runnable { if (attached) bindLifecycleOwner() }
    private val releaseAfterClick = Runnable {
        pendingRelease = false
        releasePress()
    }
    private val refreshConfig = Runnable {
        if (attached) applyMotionConfig(PowerMotionSettings.read(button.context))
    }
    private val preferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == PowerMotionSettings.KEY_ENABLED || key == PowerMotionSettings.KEY_STYLE) {
            if (Looper.myLooper() == Looper.getMainLooper()) refreshConfig.run()
            else { button.removeCallbacks(refreshConfig); button.post(refreshConfig) }
        }
    }
    private val windowFocusChanged = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused) stopFeedback()
    }
    private val hostLayoutChanged = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) stopFeedback()
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { lifecycleResumed = true; refreshVisibility() }
        override fun onPause(owner: LifecycleOwner) { lifecycleResumed = false; stopFeedback() }
        override fun onStop(owner: LifecycleOwner) { lifecycleResumed = false; stopFeedback() }
        override fun onDestroy(owner: LifecycleOwner) = detach()
    }

    init { applyTint(targetColor()) }

    fun attach() {
        if (attached) return
        attached = true
        preferences = PowerMotionSettings.preferences(button.context).also {
            it.registerOnSharedPreferenceChangeListener(preferenceChanged)
        }
        focusObserver = button.viewTreeObserver.also { it.addOnWindowFocusChangeListener(windowFocusChanged) }
        applyMotionConfig(PowerMotionSettings.read(button.context))
        bindLifecycleOwner()
        button.post(bindOwner)
    }

    fun detach() {
        attached = false
        button.removeCallbacks(bindOwner)
        button.removeCallbacks(refreshConfig)
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceChanged)
        preferences = null
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(windowFocusChanged)
        focusObserver = null
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
        stopFeedback()
    }

    private fun bindLifecycleOwner() {
        val found = button.findViewTreeLifecycleOwner()
        if (found === owner) return
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = found
        lifecycleResumed = found?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        found?.lifecycle?.addObserver(lifecycleObserver)
        refreshVisibility()
    }

    fun applyMotionConfig(value: PowerMotionConfig) { config = value; stopFeedback() }
    fun setMotionResumed(value: Boolean) { foregroundAllowed = value; refreshVisibility() }
    fun refreshVisibility() { if (!canDisplay()) stopFeedback() }
    fun resize() { if (overlayHost != null) stopFeedback() }

    /** Saved state and service notifications never replay a manual-click animation. */
    fun showState(value: Boolean) {
        if (powered == value) return
        powered = value
        stopFeedback()
    }

    fun press() {
        pressed = true
        if (canDisplay()) {
            if (canAnimate()) animatePress(.94f) else setScale(.97f)
        }
    }

    fun releasePress() {
        pressed = false
        if (canAnimate()) animatePress(1f) else { pressAnimator?.cancel(); pressAnimator = null; setScale(1f) }
    }

    fun releaseAfterClickDispatch() {
        button.removeCallbacks(releaseAfterClick)
        pendingRelease = true
        button.post(releaseAfterClick)
    }

    fun cancelInteraction() { releasePress() }

    /** Only an accepted user request reaches here; native power changes have already happened. */
    fun accepted(value: Boolean) {
        if (powered == value) { releasePress(); return }
        val continuing = transition != null && overlayHost != null
        val fromProgress = if (continuing) field.progress else if (value) 0f else 1f
        val fromOpacity = if (continuing) field.opacity else 0f
        val fromPhase = if (continuing) field.phase else if (value) 0f else 1f
        val token = ++generation
        transition?.cancel()
        transition = null
        button.removeCallbacks(releaseAfterClick)
        pendingRelease = false
        powered = value
        applyTint(targetColor())
        releasePress()
        if (!canAnimate()) { removeOverlay(); return }
        haptic()
        if (!prepareOverlay()) return

        val baseDuration = if (value) when (config.style) {
            PowerMotionStyle.WAVE -> 900L
            PowerMotionStyle.SPECTRUM, PowerMotionStyle.MATRIX -> 1000L
            PowerMotionStyle.PHASE, PowerMotionStyle.REFRACTION -> 950L
            PowerMotionStyle.DOME -> 1100L
        } else if (config.style == PowerMotionStyle.DOME) 850L else 750L
        val remaining = if (value) 1f - fromProgress else fromProgress
        val runDuration = (baseDuration * remaining.coerceIn(.2f, 1f)).toLong().coerceAtLeast(150L)
        field.progress = fromProgress
        field.opacity = fromOpacity
        field.phase = fromPhase
        val animator = ValueAnimator.ofFloat(0f, 1f)
        transition = animator
        animator.apply {
            duration = runDuration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                if (token != generation) return@addUpdateListener
                if (!canAnimate()) { stopFeedback(); return@addUpdateListener }
                val t = animator.animatedValue as Float
                field.progress = if (value) fromProgress + (1f - fromProgress) * (1f - (1f - t).pow(2.4f))
                    else fromProgress * (1f - t.pow(2.4f))
                val entrance = fromOpacity + (1f - fromOpacity) * (t / .1f).coerceIn(0f, 1f)
                val exit = ((1f - t) / (if (value) .22f else .14f)).coerceIn(0f, 1f)
                field.opacity = entrance * exit
                field.phase = fromPhase + (if (value) 1f else -1f) * t * runDuration / 1000f
                field.invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != generation) return
                    transition = null
                    removeOverlay()
                    applyTint(targetColor())
                }
            })
            start()
        }
    }

    private fun prepareOverlay(): Boolean {
        val host = button.rootView as? ViewGroup ?: return false
        if (host.width <= 0 || host.height <= 0 || button.width <= 0 || button.height <= 0) return false
        if (overlayHost !== host) {
            removeOverlay()
            overlayHost = host
            host.overlay.add(field)
            host.addOnLayoutChangeListener(hostLayoutChanged)
        }
        host.getLocationOnScreen(screenPosition)
        button.getLocationOnScreen(buttonPosition)
        field.setBounds(0, 0, host.width, host.height)
        field.originX = buttonPosition[0] - screenPosition[0] + button.width / 2f
        field.originY = buttonPosition[1] - screenPosition[1] + button.height / 2f
        field.style = config.style
        field.primaryColor = MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimary, GREEN)
        field.secondaryColor = ColorUtils.blendARGB(GREEN, android.graphics.Color.WHITE, .22f)
        field.lightBackground = ColorUtils.calculateLuminance(
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE),
        ) > .5
        field.prepare()
        return true
    }

    private fun removeOverlay() {
        overlayHost?.let { host ->
            host.overlay.remove(field)
            host.removeOnLayoutChangeListener(hostLayoutChanged)
        }
        overlayHost = null
        field.opacity = 0f
        field.progress = 0f
    }

    private fun animatePress(destination: Float) {
        pressAnimator?.cancel()
        pressAnimator = null
        if (abs(button.scaleX - destination) < .001f) { setScale(destination); return }
        val animator = ValueAnimator.ofFloat(button.scaleX, destination)
        pressAnimator = animator
        animator.duration = 85L
        animator.interpolator = FastOutSlowInInterpolator()
        animator.addUpdateListener { if (pressAnimator === animator) setScale(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (pressAnimator === animator) pressAnimator = null }
        })
        animator.start()
    }

    private fun stopFeedback() {
        ++generation
        button.removeCallbacks(releaseAfterClick)
        pendingRelease = false
        pressAnimator?.cancel(); pressAnimator = null
        transition?.cancel(); transition = null
        pressed = false
        setScale(1f)
        applyTint(targetColor())
        removeOverlay()
    }

    private fun enabled() = animationsAllowed && config.enabled
    private fun canDisplay() = attached && foregroundAllowed && lifecycleResumed && button.isShown &&
        button.windowVisibility == View.VISIBLE && button.hasWindowFocus()
    private fun canAnimate() = enabled() && canDisplay() && ValueAnimator.areAnimatorsEnabled()
    private fun targetColor() = if (powered) GREEN else GRAY
    private fun setScale(value: Float) { button.scaleX = value; button.scaleY = value }
    private fun applyTint(color: Int) {
        currentTint = color
        val tint = ColorStateList.valueOf(color)
        button.supportImageTintList = tint
        button.imageTintList = tint
    }

    private fun haptic() {
        if (!button.isHapticFeedbackEnabled || !enabled() || !canDisplay()) return
        val allowed = runCatching {
            Settings.System.getInt(button.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }.getOrDefault(false)
        if (allowed) button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    internal fun snapshot() = Snapshot(enabled(), config.style, attached,
        foregroundAllowed && lifecycleResumed, false, transition != null, false, pendingRelease,
        (if (pressAnimator != null) 1 else 0) + (if (transition != null) 1 else 0),
        pressed, button.scaleX, currentTint, field.progress)

    companion object {
        private const val GREEN = 0xff2ead55.toInt()
        private const val GRAY = 0xff808080.toInt()
    }
}
