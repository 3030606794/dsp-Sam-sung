package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Keeps the original dialog lifecycle and button/input handlers; only V2 changes its material. */
class GlassAlertDialogBuilder @JvmOverloads constructor(
    context: Context,
    overrideThemeResId: Int = 0
) : MaterialAlertDialogBuilder(context, overrideThemeResId) {
    override fun create(): AlertDialog = super.create().also { dialog ->
        if (GlassThemeUi.hasLensMaterial(context)) {
            dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    GlassThemeUi.decorateDialog(dialog.window)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    dialog.window?.let(Glass3Motion::dispose)
                }
            })
        }
    }
}
