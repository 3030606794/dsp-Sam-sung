package me.timschneeberger.rootlessjamesdsp.view

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import me.timschneeberger.rootlessjamesdsp.R

/** Moves the existing output card, keeping its fragments and parameter views intact. */
class OutputCardPinController(
    private val root: LinearLayout,
    private val preferences: SharedPreferences,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val card: View = root.findViewById(R.id.pinned_output_card)
    private val scrollingCards: LinearLayout = root.findViewById(R.id.card_container)
    private val pinnedParams = LinearLayout.LayoutParams(card.layoutParams as LinearLayout.LayoutParams)
    private val scrollingParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        val margin = root.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        setMargins(margin, 0, margin, 0)
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
        updatePlacement()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PREFERENCE_KEY) updatePlacement()
    }

    private fun updatePlacement() {
        val pinned = preferences.getBoolean(PREFERENCE_KEY, true)
        val target = if (pinned) root else scrollingCards
        if (card.parent === target) return
        val source = card.parent as? ViewGroup
        val sourceTransition = source?.layoutTransition
        val targetTransition = target.layoutTransition
        try {
            // A disappearing transition retains the old parent until its animation ends.
            // Move this card immediately, then restore both containers' animation settings.
            source?.layoutTransition = null
            target.layoutTransition = null
            source?.removeView(card)
            source?.endViewTransition(card)
            target.addView(card, 0, if (pinned) pinnedParams else scrollingParams)
        } finally {
            source?.layoutTransition = sourceTransition
            target.layoutTransition = targetTransition
        }
    }

    fun close() = preferences.unregisterOnSharedPreferenceChangeListener(this)

    companion object {
        const val PREFERENCE_KEY = "home_output_pinned"
    }
}
