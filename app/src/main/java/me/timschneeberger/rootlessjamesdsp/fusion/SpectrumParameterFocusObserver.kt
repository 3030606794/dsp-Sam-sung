package me.timschneeberger.rootlessjamesdsp.fusion

import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R

/** Observes the high-frequency exciter parameter list without replacing any preference or control callbacks. */
class SpectrumParameterFocusObserver(
    fragment: PreferenceFragmentCompat,
    private val onFocus: (String) -> Unit,
) : AutoCloseable {
    private val recycler = fragment.listView
    private val lifecycle = fragment.viewLifecycleOwner.lifecycle
    private val parameterKeys = (HighFrequencyParameters.spectrum.map { it.key } + HighFrequencyAdvancedParameters.nonlinear.map { it.key } + HighFrequencyAdvancedParameters.OVERSAMPLING + HighFrequencyParameters.LP_AUTO).toSet()
    private val controlIds = intArrayOf(
        R.id.seekbar, R.id.seekbar_value,
        R.id.bass_decrement, R.id.bass_increment,
        R.id.slider_decrease, R.id.slider_increase, R.id.switchWidget,
        R.id.field_mode_legacy, R.id.field_mode_ms,
    )
    private val touchSlop = ViewConfiguration.get(recycler.context).scaledTouchSlop.toFloat()
    private val hitRect = Rect()
    private var closed = false
    private var observedTree: ViewTreeObserver? = null
    private var lastInputFocusKey: String? = null
    private var gestureRow: View? = null
    private var gestureKey: String? = null
    private var downX = 0f
    private var downY = 0f
    private var gestureMoved = false
    private var gestureNotified = false

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        observeInputFocus(focused)
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            bindFocusListener()
        }

        override fun onViewDetachedFromWindow(view: View) {
            unbindFocusListener()
            clearGesture()
            lastInputFocusKey = null
        }
    }
    private val keyListener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
        // Focus entry also observes keys consumed by Sliders. This fallback only observes
        // navigation/activation left unhandled by the original controls; it never takes a key.
        if (!closed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode in focusKeys) observeInputFocus(recycler.findFocus())
        false
    }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            if (dx != 0 || dy != 0) gestureMoved = true
        }

        override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) gestureMoved = true
        }
    }
    private val touchListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
            if (closed) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> observeDown(event)
                MotionEvent.ACTION_MOVE -> observeMove(event)
                MotionEvent.ACTION_UP -> observeUp(event)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearGesture()
            }
            // In particular, never take a Slider gesture away from SliderDoubleTapReset.
            return false
        }

        override fun onTouchEvent(view: RecyclerView, event: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            // A control owns the remaining gesture. Immediate control focus already happened.
            if (disallowIntercept) clearGesture()
        }
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = close()
    }

    init {
        recycler.addOnItemTouchListener(touchListener)
        recycler.addOnScrollListener(scrollListener)
        recycler.addOnAttachStateChangeListener(attachListener)
        ViewCompat.addOnUnhandledKeyEventListener(recycler, keyListener)
        lifecycle.addObserver(lifecycleObserver)
        bindFocusListener()
    }

    private fun bindFocusListener() {
        if (closed) return
        val tree = recycler.viewTreeObserver
        if (tree === observedTree || !tree.isAlive) return
        unbindFocusListener()
        tree.addOnGlobalFocusChangeListener(focusListener)
        observedTree = tree
    }

    private fun unbindFocusListener() {
        observedTree?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusListener)
        observedTree = null
    }

    private fun parameterKey(view: View?): String? {
        if (closed || view == null) return null
        val row = recycler.findContainingItemView(view) ?: return null
        val holder = recycler.findContainingViewHolder(row) as? PreferenceViewHolder ?: return null
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return null
        val adapter = holder.bindingAdapter as? PreferenceGroupAdapter ?: return null
        if (position !in 0 until adapter.itemCount) return null
        // RoundedRipplePreferenceGroupAdapter inherits this read-only item lookup.
        return adapter.getItem(position)?.key?.takeIf { it in parameterKeys }
    }

    private fun observeInputFocus(view: View?) {
        if (closed) return
        val key = parameterKey(view)
        if (key != lastInputFocusKey) {
            lastInputFocusKey = key
            if (key != null) onFocus(key)
        }
    }

    private fun notifyFocus(key: String) {
        if (closed) return
        lastInputFocusKey = key
        onFocus(key)
    }

    private fun observeDown(event: MotionEvent) {
        clearGesture()
        val row = recycler.findChildViewUnder(event.x, event.y) ?: return
        val key = parameterKey(row) ?: return
        gestureRow = row
        gestureKey = key
        downX = event.x
        downY = event.y
        gestureMoved = recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE
        val directControlHit = controlIds.any { id ->
            row.findViewById<View>(id)?.let { control ->
                if (!control.isShown || control.width == 0 || control.height == 0) false
                else {
                    control.getDrawingRect(hitRect)
                    recycler.offsetDescendantRectToMyCoords(control, hitRect)
                    hitRect.contains(event.x.toInt(), event.y.toInt())
                }
            } == true
        }
        if (directControlHit) {
            // Disabled controls may still be inspected; their enabled state is never changed.
            gestureNotified = true
            notifyFocus(key)
        }
    }

    private fun observeMove(event: MotionEvent) {
        if (gestureKey == null || gestureMoved) return
        val dx = event.x - downX
        val dy = event.y - downY
        if (dx * dx + dy * dy > touchSlop * touchSlop) gestureMoved = true
    }

    private fun observeUp(event: MotionEvent) {
        observeMove(event)
        val row = gestureRow
        val key = gestureKey
        val tap = !gestureNotified && !gestureMoved && row != null && key != null &&
            recycler.findChildViewUnder(event.x, event.y) === row && parameterKey(row) == key
        clearGesture()
        // Titles and row whitespace need a genuine tap, not the beginning of vertical scrolling.
        if (tap && key != null) notifyFocus(key)
    }

    private fun clearGesture() {
        gestureRow = null
        gestureKey = null
        gestureMoved = false
        gestureNotified = false
    }

    override fun close() {
        if (closed) return
        closed = true
        clearGesture()
        unbindFocusListener()
        lastInputFocusKey = null
        recycler.removeOnItemTouchListener(touchListener)
        recycler.removeOnScrollListener(scrollListener)
        recycler.removeOnAttachStateChangeListener(attachListener)
        ViewCompat.removeOnUnhandledKeyEventListener(recycler, keyListener)
        lifecycle.removeObserver(lifecycleObserver)
    }

    private companion object {
        val focusKeys = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        )
    }
}
