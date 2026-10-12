package me.timschneeberger.rootlessjamesdsp.activity

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** The home page has no visible app bar to keep its first card below the status bar. */
internal object MainWindowInsets {
    fun install(content: View) {
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Always use the original padding: repeated dispatch/rotation must not accumulate it.
            // fitsSystemWindows on this child prevents CoordinatorLayout adding a second inset.
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            // Preserve navigation-bar insets for the separate BottomAppBar and power button.
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
