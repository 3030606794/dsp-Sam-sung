package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.ThemesPreferenceAdapter
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.dpToPx

class ThemesPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ListPreference(context, attrs),
    ThemesPreferenceAdapter.OnItemClickListener {

    private var recycler: RecyclerView? = null
    private val adapter = ThemesPreferenceAdapter(this)
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            lastScrollPosition = recyclerView.computeHorizontalScrollOffset()
        }
    }

    var lastScrollPosition: Int? = null

    var entries: List<AppTheme> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            adapter.setItems(value)
        }

    init {
        layoutResource = R.layout.preference_themes_list
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val next = holder.findViewById(R.id.themes_list) as RecyclerView
        if (recycler !== next) {
            recycler?.removeOnScrollListener(scrollListener)
            recycler = next
            next.itemAnimator = null
            if (next.layoutManager == null)
                next.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            if (next.adapter !== adapter) next.adapter = adapter
            next.addOnScrollListener(scrollListener)
            // Restore only when a new row is attached, rather than resetting its scroll on rebind.
            lastScrollPosition?.let(::scrollToOffset)
        }
    }

    override fun onItemClick(position: Int) {
        if (position !in entries.indices) {
            return
        }

        val selectedTheme = entries[position].name
        if (callChangeListener(selectedTheme)) {
            value = selectedTheme
        }
    }

    override fun onClick() {
        // no-op; not actually a DialogPreference
    }

    private fun scrollToOffset(lX: Int) {
        recycler?.let {
            (it.layoutManager as LinearLayoutManager).apply {
                scrollToPositionWithOffset(
                    // 114dp is the width of the pref_theme_item layout
                    lX / 114.dpToPx,
                    -lX % 114.dpToPx,
                )
            }
            lastScrollPosition = it.computeHorizontalScrollOffset()
        }
    }
}
