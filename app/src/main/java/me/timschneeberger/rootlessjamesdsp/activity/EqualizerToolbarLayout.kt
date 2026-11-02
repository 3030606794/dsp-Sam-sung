package me.timschneeberger.rootlessjamesdsp.activity

import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.graphics.Rect
import android.widget.TextView
import androidx.core.view.children
import com.google.android.material.appbar.MaterialToolbar
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Align the action row and keep the two text lines compact at the requested line pitch. */
internal object EqualizerToolbarLayout {
    fun useMainMenuTitleAppearance(toolbar: MaterialToolbar) {
        val attributes = toolbar.context.obtainStyledAttributes(intArrayOf(android.R.attr.textAppearanceListItem))
        try {
            val appearance = attributes.getResourceId(0, 0)
            if (appearance != 0) toolbar.setTitleTextAppearance(toolbar.context, appearance)
        } finally {
            attributes.recycle()
        }
    }

    fun alignTitle(toolbar: MaterialToolbar, maxTitleLines: Int = 2) {
        fun updateTitle() {
            val title = toolbar.children.filterIsInstance<TextView>().firstOrNull { it.text == toolbar.title }
                ?: return
            // includeFontPadding is a TextView property and is not consistently applied
            // when a toolbar creates its title from a TextAppearance alone.
            if (title.includeFontPadding) title.includeFontPadding = false
            if (title.maxLines != maxTitleLines) {
                title.setSingleLine(maxTitleLines == 1)
                title.maxLines = maxTitleLines
            }
            title.gravity = if (maxTitleLines == 1) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTitle() }
        toolbar.post { updateTitle() }
    }

    fun keepTitleSummaryGap(toolbar: MaterialToolbar, summary: View, maxTitleLines: Int = 2) {
        alignTitle(toolbar, maxTitleLines)
        fun inkBounds(text: TextView): Rect? {
            if (text.visibility != View.VISIBLE || text.baseline < 0) return null
            val value = text.text.toString()
            if (value.isBlank()) return null
            return Rect().also {
                text.paint.getTextBounds(value, 0, value.length, it)
                it.offset(0, text.baseline)
            }
        }
        fun summaryInk(view: View): Rect? {
            if (view.visibility != View.VISIBLE) return null
            return when (view) {
                is TextView -> inkBounds(view)
                is ViewGroup -> view.children.mapNotNull { child ->
                    summaryInk(child)?.apply { offset(child.left, child.top) }
                }.reduceOrNull { union, bounds -> union.apply { this.union(bounds) } }
                else -> null
            }
        }
        fun updateGap() {
            if (summary.visibility != View.VISIBLE) return
            val title = toolbar.children.filterIsInstance<TextView>().firstOrNull { it.text == toolbar.title }
                ?: return
            val titleInk = inkBounds(title)?.apply { offset(title.left, title.top) } ?: return
            val rowInk = summaryInk(summary) ?: return
            val wanted = toolbar.resources.getDimension(R.dimen.peq_toolbar_line_pitch)
            // At larger accessibility font sizes, preserve a small visible gap rather than
            // forcing overlapping glyphs into a line pitch shorter than their own heights.
            val minimumGap = 2f * toolbar.resources.displayMetrics.density
            val pitch = max(wanted, (titleInk.height() + rowInk.height()) / 2f + minimumGap)
            val parameters = summary.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val toolbarBottomMargin = (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val margin = (titleInk.exactCenterY() + pitch - rowInk.exactCenterY() -
                toolbar.height - toolbarBottomMargin).roundToInt()
            // The toolbar's 56 dp action height includes enough room for a compact subtitle.
            // Reclaim its unused space instead of adding a second block underneath it.
            (toolbar.parent as? View)?.let { parent ->
                if (parent.minimumHeight != toolbar.minimumHeight) parent.minimumHeight = toolbar.minimumHeight
            }
            if (parameters.topMargin != margin) {
                parameters.topMargin = margin
                summary.layoutParams = parameters
            }
        }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGap() }
        summary.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGap() }
        toolbar.post { updateGap() }
    }
}
