package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.ContextWrapper
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/** A stopped home page can own another editor for the same module; do not render that copy. */
internal fun runtimeStatusVisible(context: Context): Boolean {
    var current = context
    while (true) {
        if (current is LifecycleOwner)
            return current.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (current !is ContextWrapper || current.baseContext === current) return true
        current = current.baseContext
    }
}

/** Live readouts update their text, rather than asking RecyclerView to rebind the row. */
open class RuntimeStatusPreference(context: Context) : Preference(context) {
    private var latestSummary: CharSequence? = null
    private var boundHolder: WeakReference<PreferenceViewHolder>? = null

    override fun getSummary(): CharSequence? = latestSummary

    override fun setSummary(summary: CharSequence?) {
        if (TextUtils.equals(latestSummary, summary)) return
        latestSummary = summary
        val holder = boundHolder?.get() ?: return
        val list = holder.itemView.parent as? RecyclerView ?: return
        val position = holder.bindingAdapterPosition
        // A cached holder can have been recycled for another preference in the meantime.
        if (position == RecyclerView.NO_POSITION ||
            (list.adapter as? PreferenceGroupAdapter)?.getItem(position) !== this) return
        updateSummary(holder)
    }

    private fun updateSummary(holder: PreferenceViewHolder) {
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            val visible = !TextUtils.isEmpty(latestSummary)
            if (!TextUtils.equals(text, latestSummary)) text = latestSummary
            val nextVisibility = if (visible) View.VISIBLE else View.GONE
            if (visibility != nextVisibility) visibility = nextVisibility
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        boundHolder = WeakReference(holder)
        updateSummary(holder)
    }
}
