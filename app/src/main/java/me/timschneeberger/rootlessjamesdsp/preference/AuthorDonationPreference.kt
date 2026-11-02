package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import kotlin.math.roundToInt

/** Opens the supplied QR image without changing its pixels or starting a payment. */
class AuthorDonationPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    private var qrDialog: AlertDialog? = null

    init {
        isPersistent = false
    }

    override fun onClick() {
        if (qrDialog?.isShowing == true) return
        val metrics = context.resources.displayMetrics
        val padding = (16 * metrics.density).roundToInt()
        val imageHeight = (metrics.heightPixels * .55f).roundToInt()
            .coerceAtMost((420 * metrics.density).roundToInt())
        val image = ImageView(context).apply {
            setImageResource(R.drawable.author_donation_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = context.getString(R.string.author_donation_qr_description)
            setPadding(padding, 0, padding, 0)
        }
        val frame = FrameLayout(context).apply {
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight))
        }
        qrDialog = GlassAlertDialogBuilder(context)
            .setTitle(R.string.author_donation_title)
            .setMessage(R.string.author_donation_scan_hint)
            .setView(frame)
            .setPositiveButton(R.string.author_donation_close, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { qrDialog = null }
                dialog.show()
            }
    }

    override fun onDetached() {
        qrDialog?.dismiss()
        qrDialog = null
        super.onDetached()
    }
}
