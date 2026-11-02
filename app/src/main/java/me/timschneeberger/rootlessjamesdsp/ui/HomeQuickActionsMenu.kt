package me.timschneeberger.rootlessjamesdsp.ui

import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.PopupMenu
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.session.root.RootProcessingPolicy
import me.timschneeberger.rootlessjamesdsp.utils.isRoot

/** The bottom anchor naturally places this compact popup above the navigation bar. */
class HomeQuickActionsMenu(
    private val anchor: View,
    private val onPresets: () -> Unit,
    private val onBlockedApps: () -> Unit,
    private val onSelectedApps: () -> Unit,
    private val onReset: () -> Unit
) {
    private var popup: PopupMenu? = null

    fun show() {
        if (!anchor.isAttachedToWindow || popup != null) return
        val menu = PopupMenu(anchor.context, anchor, Gravity.END)
        menu.inflate(R.menu.menu_main_bottom)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_presets -> { onPresets(); true }
                R.id.action_blocked_apps -> { if (appActionsEnabled()) onBlockedApps(); true }
                R.id.action_selected_apps -> { if (appActionsEnabled()) onSelectedApps(); true }
                R.id.action_revert -> { onReset(); true }
                else -> false
            }
        }
        menu.setOnDismissListener { if (popup === it) popup = null }
        popup = menu
        refreshProcessingMode()
        menu.show()
    }

    /** Lists stay saved; the system mix cannot apply them to individual applications. */
    fun refreshProcessingMode() {
        val menu = popup?.menu ?: return
        val enabled = appActionsEnabled()
        menu.findItem(R.id.action_blocked_apps).isEnabled = enabled
        menu.findItem(R.id.action_selected_apps).isEnabled = enabled
    }

    private fun appActionsEnabled() = !isRoot() || RootProcessingPolicy.playerSessions(anchor.context)

    fun dismiss() { popup?.dismiss(); popup = null }
}
