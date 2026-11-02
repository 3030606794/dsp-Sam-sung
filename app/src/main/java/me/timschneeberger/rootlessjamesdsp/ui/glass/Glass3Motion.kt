package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.WindowCallbackWrapper
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Observes the window's existing input dispatch. All control listeners keep their original owner. */
object Glass3Motion {
    class State internal constructor(view: View) {
        internal val owner = WeakReference(view)
        var press = 0f
            internal set
        var stretch = 0f
            internal set
        var flow = 0f
            internal set
        var velocity = 0f
            internal set
        var dragging = false
            internal set
        var touchX = .5f
            internal set
        var touchY = .5f
            internal set
        var ripple = 1f
            internal set
        var rippleActive = false
            internal set
        internal var targetPress = 0f
        internal var targetStretch = 0f
        internal var pressVelocity = 0f
        internal var stretchVelocity = 0f
        internal var animator: ValueAnimator? = null
        internal var speed = 1f
        internal var strength = 0f
        internal var baseScaleX = 1f
        internal var baseScaleY = 1f
        internal var scaleSaved = false
    }

    private val states = WeakHashMap<View, State>()
    private val windows = WeakHashMap<Window, WeakReference<Observer>>()

    fun stateFor(view: View): State = states.getOrPut(view) { State(view) }

    fun install(window: Window) {
        if (!GlassThemeUi.isThirdVersion(window.context) || windows.containsKey(window)) return
        val callback = window.callback ?: return
        val observer = Observer(window, callback)
        windows[window] = WeakReference(observer)
        window.callback = observer
    }

    fun decorate(view: View) {
        if (!GlassThemeUi.isThirdVersion(view.context)) return
        if (view is SpringMaterialSwitch) {
            val settings = Glass3Preferences.snapshot(view.context)
            view.glassMotionEnabled = settings.motionEnabled && animatorsEnabled()
            view.glassMotionStrength = settings.strength
            view.glassMotionSpeed = settings.speed
        }
    }

    fun dispose(window: Window) {
        val observer = windows.remove(window)?.get() ?: return
        observer.cancel()
        if (window.callback === observer) window.callback = observer.original
        val decor = window.decorView
        val toRemove = states.entries.filter { it.key.rootView === decor.rootView }
        toRemove.forEach { (view, state) ->
            stop(state)
            restoreScale(view, state)
            states.remove(view)
        }
    }

    private fun animatorsEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private class Observer(window: Window, val original: Window.Callback) : WindowCallbackWrapper(original) {
        private val owner = WeakReference(window)
        private var active: WeakReference<View>? = null
        private var lastX = 0f
        private var lastTime = 0L

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) begin(event)
            // The real widget handles the event first, including validation and checked changes.
            val handled = super.dispatchTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> move(event)
                MotionEvent.ACTION_UP -> release(cancelled = false)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> release(cancelled = true)
            }
            return handled
        }

        private fun begin(event: MotionEvent) {
            release(cancelled = true)
            val window = owner.get() ?: return
            if (!GlassThemeUi.isThirdVersion(window.context)) return
            val settings = Glass3Preferences.snapshot(window.context)
            if (!settings.motionEnabled || !animatorsEnabled()) return
            // Global visible rectangles are relative to this window's root, while raw touch
            // coordinates are on screen (dialogs can start well below the screen origin).
            val rootPosition = IntArray(2)
            window.decorView.getLocationOnScreen(rootPosition)
            val view = findTarget(window.decorView, event.rawX.toInt() - rootPosition[0],
                event.rawY.toInt() - rootPosition[1]) ?: return
            decorate(view)
            active = WeakReference(view)
            lastX = event.rawX
            lastTime = event.eventTime
            val state = stateFor(view)
            state.speed = settings.speed
            state.strength = settings.strength
            state.dragging = view is Slider
            state.velocity = 0f
            state.flow = 0f
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            state.touchX = ((event.rawX - position[0]) / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            state.touchY = ((event.rawY - position[1]) / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            state.ripple = 0f
            state.rippleActive = true
            state.targetPress = settings.strength
            state.targetStretch = 0f
            if (canScale(view) && !state.scaleSaved) {
                state.baseScaleX = view.scaleX
                state.baseScaleY = view.scaleY
                state.scaleSaved = true
            }
            animate(state)
        }

        private fun move(event: MotionEvent) {
            val view = active?.get() ?: return
            val state = stateFor(view)
            if (view is Slider) {
                val elapsed = (event.eventTime - lastTime).coerceAtLeast(1L)
                val density = view.resources.displayMetrics.density
                val movement = ((event.rawX - lastX) / density / elapsed).coerceIn(-2.5f, 2.5f)
                state.velocity = movement
                state.targetStretch = (movement * .7f).coerceIn(-1f, 1f) * state.strength
                state.targetPress = .65f * state.strength
                animate(state)
            }
            lastX = event.rawX
            lastTime = event.eventTime
        }

        private fun release(cancelled: Boolean) {
            val view = active?.get()
            active = null
            if (view == null) return
            val state = stateFor(view)
            state.targetPress = 0f
            state.targetStretch = 0f
            state.dragging = false
            state.velocity = 0f
            if (!cancelled) {
                state.ripple = 0f
                state.rippleActive = true
            } else {
                state.ripple = 1f
                state.rippleActive = false
            }
            if (!cancelled && view is CompoundButton) {
                // Only the material stretches; the checked value was already dispatched above.
                state.stretch = (if (view.isChecked) 1f else -1f) * state.strength * .7f
            }
            stop(state)
            animate(state)
        }

        fun cancel() = release(cancelled = true)
    }

    private fun findTarget(view: View, x: Int, y: Int): View? {
        // Do not layer glass waves over the main power button's selected preset.
        if (view.id == R.id.power_toggle) return null
        if (view.visibility != View.VISIBLE || !view.isEnabled || view.alpha <= 0f) return null
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || !visible.contains(x, y)) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findTarget(view.getChildAt(index), x, y)?.let { return it }
            }
        }
        return view.takeIf { it is Slider || it is CompoundButton || it.isClickable }
    }

    private fun canScale(view: View) = view.id!=R.id.power_toggle && view.id!=R.id.settings_button &&
        view !is CompoundButton && view !is EditText &&
        (view is Button || view is ImageView || view is TextView && view.isClickable)

    private fun animate(state: State) {
        if (state.animator != null) return
        val view = state.owner.get() ?: return
        if (!view.isAttachedToWindow || !animatorsEnabled()) {
            finish(state)
            return
        }
        var previousTime = SystemClock.uptimeMillis()
        val speed = state.speed.coerceIn(.5f, 1.5f)
        val spring = 360f * speed * speed
        val damping = 25f * speed
        state.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (1000L / speed).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val current = state.owner.get()
                if (current == null || !current.isAttachedToWindow) {
                    cancel()
                    return@addUpdateListener
                }
                val now = SystemClock.uptimeMillis()
                val dt = ((now - previousTime) / 1000f).coerceIn(.001f, .032f)
                previousTime = now
                state.pressVelocity += ((state.targetPress - state.press) * spring - damping * state.pressVelocity) * dt
                state.stretchVelocity += ((state.targetStretch - state.stretch) * spring - damping * state.stretchVelocity) * dt
                state.press += state.pressVelocity * dt
                state.stretch += state.stretchVelocity * dt
                state.flow = (state.flow + dt * 1.8f * speed).coerceAtMost(1f)
                state.ripple = (state.ripple + dt * 1.8f * speed).coerceAtMost(1f)
                state.rippleActive = state.ripple < 1f
                if (state.scaleSaved) {
                    current.scaleX = state.baseScaleX * (1f - .045f * state.press + .012f * abs(state.stretch))
                    current.scaleY = state.baseScaleY * (1f - .045f * state.press - .009f * abs(state.stretch))
                }
                current.invalidate()
                if (!state.rippleActive && currentPlayTime > (160L / speed).toLong() &&
                    abs(state.targetPress - state.press) + abs(state.targetStretch - state.stretch) < .003f &&
                    abs(state.pressVelocity) + abs(state.stretchVelocity) < .025f) cancel()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state.animator === animation) {
                        state.animator = null
                        finish(state)
                    }
                }
            })
            start()
        }
    }

    private fun finish(state: State) {
        state.press = state.targetPress
        state.stretch = state.targetStretch
        state.pressVelocity = 0f
        state.stretchVelocity = 0f
        state.owner.get()?.let { view ->
            if (state.targetPress == 0f) restoreScale(view, state)
            else if (state.scaleSaved) {
                view.scaleX = state.baseScaleX * (1f - .045f * state.press)
                view.scaleY = state.baseScaleY * (1f - .045f * state.press)
            }
            view.invalidate()
        }
    }

    private fun restoreScale(view: View, state: State) {
        if (!state.scaleSaved) return
        view.scaleX = state.baseScaleX
        view.scaleY = state.baseScaleY
        state.scaleSaved = false
    }

    private fun stop(state: State) {
        val previous = state.animator
        state.animator = null
        previous?.cancel()
    }
}
