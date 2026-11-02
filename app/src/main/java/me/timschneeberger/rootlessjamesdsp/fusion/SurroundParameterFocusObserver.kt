package me.timschneeberger.rootlessjamesdsp.fusion

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import java.util.Locale
import kotlin.math.abs

/** Passive window-event observation: never replaces or consumes a native control listener. */
internal class SurroundParameterFocusObserver(private val root: ViewGroup,
    private val onFocus: (String) -> Unit) : AutoCloseable {
    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var titleKey: String? = null
    private var downX = 0f
    private var downY = 0f
    private var closed = false
    private val tree = root.viewTreeObserver
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
        if (!closed && root.isShown && root.hasWindowFocus()) {
            next?.takeIf { belongsToRoot(it) }?.let { keyFor(it)?.first }?.let(onFocus)
        }
    }

    init { tree.addOnGlobalFocusChangeListener(focusListener) }

    fun observe(event: MotionEvent) {
        if (closed || !root.isShown) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                titleKey = null; downX = event.rawX; downY = event.rawY
                hit(root, downX.toInt(), downY.toInt())?.let(::keyFor)?.let { (key, title) ->
                    if (title) titleKey = key else onFocus(key)
                }
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) titleKey = null
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> titleKey = null
            MotionEvent.ACTION_UP -> {
                val pending = titleKey; titleKey = null
                if (pending != null && abs(event.rawX - downX) <= slop && abs(event.rawY - downY) <= slop &&
                    hit(root, event.rawX.toInt(), event.rawY.toInt())?.let(::keyFor)?.first == pending) onFocus(pending)
            }
        }
    }

    private fun belongsToRoot(view: View): Boolean {
        var current: View? = view
        while (current != null) { if (current === root) return true; current = current.parent as? View }
        return false
    }

    private fun hit(view: View, x: Int, y: Int): View? {
        if (view.visibility != View.VISIBLE) return null
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return null
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            hit(view.getChildAt(index), x, y)?.let { return it }
        }
        return view
    }

    private fun keyFor(view: View): Pair<String, Boolean>? {
        var current: View? = view
        while (current != null && current !== root) {
            val tag = current.tag as? String
            val title = tag?.startsWith("surround_focus_") == true
            val key = when {
                tag == null -> null
                title -> tag.removePrefix("surround_focus_")
                tag == "surround_match_remeasure" -> "remeasure"
                tag == "surround_calibration_start_stop" -> "calibration"
                tag.startsWith("surround_slider_") -> "legacy_" + tag.removePrefix("surround_slider_").lowercase(Locale.ROOT)
                tag.startsWith("surround_value_") -> "legacy_" + tag.removePrefix("surround_value_").lowercase(Locale.ROOT)
                tag.startsWith("surround_legacy_") -> "legacy_" + tag.removePrefix("surround_legacy_").substringBeforeLast('_').lowercase(Locale.ROOT)
                else -> listOf("slider_", "minus_", "plus_", "value_", "toggle_", "choice_")
                    .firstOrNull { tag.startsWith("surround_pro_$it") }
                    ?.let { tag.removePrefix("surround_pro_$it") }
                    ?: tag.takeIf { it.startsWith("surround_pro_") }?.removePrefix("surround_pro_")
            }
            if (key != null && SurroundChartFocus.fromKey(key) != null) return key to title
            current = current.parent as? View
        }
        return null
    }

    override fun close() {
        closed = true; titleKey = null
        if (tree.isAlive) tree.removeOnGlobalFocusChangeListener(focusListener)
        else root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusListener)
    }
}
