package me.timschneeberger.rootlessjamesdsp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/** Only owns newly-added chart presentation. Native preference rows never enter this container. */
internal class ChartWorkspacePager(
    context: Context,
    key: String,
    private val pages: List<Pair<String, View>>,
    power: View,
    pin: View,
    private val onSelected: (Int) -> Unit
) : LinearLayout(context) {
    private val prefs = context.applicationContext.getSharedPreferences("observer_chart_workspaces", Context.MODE_PRIVATE)
    private val prefix = "$key."
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val tabs = LinearLayout(context)
    private val pager = ViewPager2(context)
    private val empty = TextView(context)
    private var visible = prefs.getStringSet(prefix + "visible", pages.indices.map { it.toString() }.toSet())
        .orEmpty().mapNotNull { it.toIntOrNull()?.takeIf { id -> id in pages.indices } }.sorted()
    private var selected = visible.firstOrNull() ?: -1
    private var muted = false
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = visible.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = pages[visible[position]].second
            holder.box.removeAllViews()
            (view.parent as? ViewGroup)?.removeView(view)
            holder.box.addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        override fun onViewRecycled(holder: Holder) { holder.box.removeAllViews() }
    }
    init {
        orientation = VERTICAL
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = dp(5).toFloat()
                setStroke(dp(1).coerceAtLeast(1), ColorUtils.setAlphaComponent(ink, 50))
            }
            clipToOutline = true
        }
        bar.addView(tabs, LayoutParams(0, -1, 1f))
        divider(bar)
        val gear = TextView(context).apply {
            text = "⚙"; textSize = 21f; gravity = Gravity.CENTER
            setTextColor(ink); background = backgroundForSegment()
            contentDescription = "图纸管理，仅控制新增图纸"
            setOnClickListener { showManager() }
        }
        bar.addView(gear, LayoutParams(dp(38), -1))
        listOf(power, pin).forEach { control ->
            divider(bar)
            (control.parent as? ViewGroup)?.removeView(control)
            control.background = backgroundForSegment()
            bar.addView(control, LayoutParams(dp(40), -1))
        }
        addView(bar, LayoutParams(-1, dp(44)))
        val box = FrameLayout(context)
        pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (muted) return
                val next = visible.getOrNull(position) ?: return
                if (next != selected) { selected = next; rebuildTabs(); onSelected(next) }
            }
        })
        box.addView(pager, FrameLayout.LayoutParams(-1, -1))
        empty.apply {
            text = "新增图纸已隐藏\n⚙ 图纸管理可重新开启\n下方原生内容完整保留"
            textSize = 12f; gravity = Gravity.CENTER
            setTextColor(ColorUtils.setAlphaComponent(ink, 170))
        }
        box.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(box, LayoutParams(-1, 0, 1f))
        refreshVisibility()
    }
    fun select(id: Int): Boolean {
        if (id !in visible) return false
        selected = id
        muted = true
        try { pager.setCurrentItem(visible.indexOf(id), false) } finally { muted = false }
        rebuildTabs()
        return true
    }
    private fun refreshVisibility() {
        muted = true
        try {
            adapter.notifyDataSetChanged()
            if (selected !in visible) selected = visible.firstOrNull() ?: -1
            visible.indexOf(selected).takeIf { it >= 0 }?.let { pager.setCurrentItem(it, false) }
        } finally { muted = false }
        pager.visibility = if (visible.isEmpty()) GONE else VISIBLE
        empty.visibility = if (visible.isEmpty()) VISIBLE else GONE
        rebuildTabs()
    }
    private fun rebuildTabs() {
        tabs.removeAllViews()
        visible.forEachIndexed { index, id ->
            if (index > 0) divider(tabs)
            tabs.addView(TextView(context).apply {
                text = pages[id].first; textSize = 10.5f; maxLines = 2; gravity = Gravity.CENTER
                isSelected = id == selected
                setTextColor(if (isSelected) accent else ink)
                background = backgroundForSegment()
                setOnClickListener { pager.setCurrentItem(visible.indexOf(id), true) }
            }, LayoutParams(0, -1, 1f))
        }
    }
    private fun showManager() {
        if (dialog?.isShowing == true) return
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(18), dp(5), dp(18), dp(5)) }
        pages.forEachIndexed { id, page ->
            body.addView(SwitchMaterial(context).apply {
                text = page.first; isChecked = id in visible; textSize = 15f
                setOnCheckedChangeListener { _, checked ->
                    visible = visible.toMutableSet().apply { if (checked) add(id) else remove(id) }.sorted()
                    prefs.edit().putStringSet(prefix + "visible", visible.map { it.toString() }.toSet()).apply()
                    refreshVisibility(); onSelected(selected)
                }
            }, LayoutParams(-1, -2))
        }
        dialog = MaterialAlertDialogBuilder(context).setTitle("图纸管理").setView(body)
            .setPositiveButton("完成", null).create().also { it.show() }
    }
    override fun onDetachedFromWindow() {
        dialog?.dismiss(); dialog = null
        super.onDetachedFromWindow()
    }
    private fun divider(parent: LinearLayout) = parent.addView(View(context).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(ink, 50))
    }, LayoutParams(dp(1).coerceAtLeast(1), -1))
    private fun backgroundForSegment() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(ColorUtils.setAlphaComponent(accent, 48)))
        addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(ColorUtils.setAlphaComponent(accent, 22)))
        addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
    private class Holder(val box: FrameLayout) : RecyclerView.ViewHolder(box)
    companion object {
        /** Uses the current window so split-screen and landscape cannot exceed half a screen. */
        fun halfHeight(view: View): Int {
            var c: Context? = view.context
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
            while (c != null && c !is Activity && visited.add(c)) c = (c as? ContextWrapper)?.baseContext
            val host = c as? Activity
            val heights = ArrayList<Int>()
            if (host != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val m = host.windowManager.currentWindowMetrics
                    val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    heights += m.bounds.height() - i.top - i.bottom
                }
                val frame = Rect(); host.window.decorView.getWindowVisibleDisplayFrame(frame)
                if (frame.height() > 0) heights += frame.height()
                host.findViewById<View>(android.R.id.content)?.height?.takeIf { it > 0 }?.let { heights += it }
            }
            if (heights.isEmpty()) heights += (view.resources.configuration.screenHeightDp * view.resources.displayMetrics.density).toInt()
            return (heights.filter { it > 0 }.minOrNull()?.div(2) ?: view.resources.displayMetrics.heightPixels / 2).coerceAtLeast(1)
        }
        fun measuredHeight(view: View, parentSpec: Int, preferredDp: Int = 328): Int {
            val cap = halfHeight(view)
            val limit = if (MeasureSpec.getMode(parentSpec) == MeasureSpec.UNSPECIFIED) cap else MeasureSpec.getSize(parentSpec)
            return minOf(cap, limit, (preferredDp * view.resources.displayMetrics.density).toInt()).coerceAtLeast(1)
        }
    }
}

/** Safety net for any existing plot header; preference scrolling is a separate sibling below it. */
internal class HalfScreenHeaderFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = ChartWorkspacePager.halfHeight(this)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit.coerceAtLeast(1), MeasureSpec.AT_MOST))
    }
}
