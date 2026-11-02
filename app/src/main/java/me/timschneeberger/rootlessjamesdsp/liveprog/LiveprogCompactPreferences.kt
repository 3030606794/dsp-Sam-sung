package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import kotlin.math.ceil

/** DSP-only presentation. The source parser, persistence and engine interfaces stay unchanged. */
internal open class LiveprogCompactRow(context: Context) : Preference(context) {
    var actionLabel: String = ""
        set(value) { if (field != value) { field = value; notifyChanged() } }
    init { layoutResource = R.layout.preference_liveprog_compact_row; isIconSpaceReserved = false; isPersistent = false; isSingleLineTitle = false }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.liveprog_row_action) as? TextView)?.apply {
            visibility = View.GONE
        }
    }
}

internal class LiveprogCompactDropdown(context: Context) : DropDownPreference(context) {
    init { layoutResource = R.layout.preference_liveprog_compact_row; isIconSpaceReserved = false; isSingleLineTitle = false }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.liveprog_row_action)?.visibility = View.GONE
    }
}

internal class LiveprogCompactChoices(context: Context, private val options: List<String>,
    private val current: () -> Int, private val choose: (Int) -> Unit) : Preference(context) {
    init { layoutResource = R.layout.preference_liveprog_choices; isIconSpaceReserved = false; isPersistent = false; isSelectable = false; isSingleLineTitle = false }
    fun refreshSelection() = notifyChanged()
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val row = holder.findViewById(R.id.liveprog_choices) as LinearLayout
        row.removeAllViews()
        val accent = MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary)
        val ink = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface)
        val gap = (4 * context.resources.displayMetrics.density).toInt()
        options.forEachIndexed { index, value ->
            val selected = index == current()
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = value; textSize = 14f; isAllCaps = false; includeFontPadding = false
                minWidth = 0; minimumWidth = 0; minHeight = (40 * resources.displayMetrics.density).toInt()
                insetTop = 0; insetBottom = 0; cornerRadius = gap * 2
                setPadding(gap, gap, gap, gap); setTextColor(if (selected) accent else ink)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, if (selected) 25 else 0))
                strokeColor = android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (selected) accent else ink, if (selected) 180 else 70))
                isEnabled = this@LiveprogCompactChoices.isEnabled
                contentDescription = "${title}：$value${if (selected) "，已选择" else ""}"
                setOnClickListener { if (index != current()) { choose(index); notifyChanged() } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
        }
    }
}

internal class LiveprogToolsRow(context: Context, private val importScript: () -> Unit,
    private val editScript: () -> Unit, private val more: (View) -> Unit,
    private val canEdit: () -> Boolean) : Preference(context) {
    init { layoutResource = R.layout.preference_liveprog_tools; isIconSpaceReserved = false; isPersistent = false; isSelectable = false }
    fun refresh() = notifyChanged()
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.liveprog_import)?.setOnClickListener { importScript() }
        holder.findViewById(R.id.liveprog_edit)?.apply {
            isEnabled = this@LiveprogToolsRow.isEnabled && canEdit(); setOnClickListener { editScript() }
        }
        holder.findViewById(R.id.liveprog_tools)?.setOnClickListener { more(it) }
    }
}

/** Dividers span the RecyclerView, including the normal row-content side padding. */
internal class LiveprogFullWidthDividers(context: Context) : RecyclerView.ItemDecoration() {
    private val thickness = ceil(context.resources.displayMetrics.density.toDouble() * .5).toInt().coerceAtLeast(1)
    private val paint = Paint().apply {
        color = ColorUtils.setAlphaComponent(MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.GRAY), 42)
    }
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position >= 0 && position < state.itemCount - 1) outRect.bottom = thickness
    }
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        repeat(parent.childCount) { index ->
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position >= 0 && position < state.itemCount - 1) {
                val y = child.bottom + child.translationY
                canvas.drawRect(0f, y, parent.width.toFloat(), y + thickness, paint)
            }
        }
    }
}
