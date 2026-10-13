package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout

/** The plot's old fling must not keep moving the list after a pin/preset transition. */
class PeqResponseScrollBehavior @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppBarLayout.Behavior(context, attrs) {
    private var pinned = true
    private var acceptAutonomousOffsets = false
    private var controlledOffset = false

    init {
        setDragCallback(object : DragCallback() {
            override fun canDrag(appBarLayout: AppBarLayout) = !pinned
        })
    }

    fun stopMotion() {
        // Material's HeaderBehavior retains its fling bounds across setExpanded()/scrollFlags.
        // Its queued frames have no public cancellation API. Ignore those writes until a new
        // header touch, which aborts the previous scroller in HeaderBehavior itself. Nested list
        // scrolling and layout remain permitted; no reflection into Material's private fields.
        acceptAutonomousOffsets = false
    }

    fun setPinned(value: Boolean) {
        stopMotion()
        pinned = value
    }

    override fun setTopAndBottomOffset(offset: Int): Boolean = when {
        pinned -> super.setTopAndBottomOffset(0)
        !acceptAutonomousOffsets && !controlledOffset -> false
        else -> super.setTopAndBottomOffset(offset)
    }

    private inline fun <T> controlled(block: () -> T): T {
        val previous = controlledOffset
        controlledOffset = true
        return try { block() } finally { controlledOffset = previous }
    }

    override fun onLayoutChild(parent: CoordinatorLayout, child: AppBarLayout, layoutDirection: Int): Boolean =
        controlled { super.onLayoutChild(parent, child, layoutDirection) }

    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: AppBarLayout, event: MotionEvent): Boolean {
        if (pinned) return false
        // A real header DOWN aborts Material's previous fling before allowing a new one.
        val intercepted = super.onInterceptTouchEvent(parent, child, event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            parent.isPointInChildBounds(child, event.x.toInt(), event.y.toInt())) {
            acceptAutonomousOffsets = true
        }
        return intercepted
    }

    override fun onTouchEvent(parent: CoordinatorLayout, child: AppBarLayout, event: MotionEvent): Boolean =
        !pinned && controlled { super.onTouchEvent(parent, child, event) }

    override fun onStartNestedScroll(parent: CoordinatorLayout, child: AppBarLayout, directTargetChild: View,
        target: View, axes: Int, type: Int): Boolean =
        !pinned && super.onStartNestedScroll(parent, child, directTargetChild, target, axes, type)

    override fun onNestedPreScroll(parent: CoordinatorLayout, child: AppBarLayout, target: View,
        dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (!pinned) controlled { super.onNestedPreScroll(parent, child, target, dx, dy, consumed, type) }
    }

    override fun onNestedScroll(parent: CoordinatorLayout, child: AppBarLayout, target: View,
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray) {
        if (!pinned) controlled {
            super.onNestedScroll(parent, child, target, dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed, type, consumed)
        }
    }
}
