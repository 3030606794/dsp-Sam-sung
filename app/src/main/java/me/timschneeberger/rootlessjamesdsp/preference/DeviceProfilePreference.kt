package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.DriverStatusDialogFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.OutputCardPinController
import me.timschneeberger.rootlessjamesdsp.ui.HelpPreferences

/** The route title keeps the profile menu; the separate pin only controls card placement. */
class DeviceProfilePreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : DropDownPreference(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    private val appPreferences = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
    private var pin: ImageButton? = null

    init {
        layoutResource = R.layout.preference_device_profile_compact
    }

    override fun onAttached() {
        super.onAttached()
        appPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDetached() {
        appPreferences.unregisterOnSharedPreferenceChangeListener(this)
        pin = null
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Only the route capsule opens the existing device/profile menu.
        holder.itemView.apply {
            setOnClickListener(null)
            isClickable=false
            isFocusable=false
            isLongClickable=false
            background=null
            foreground=null
        }
        holder.itemView.findViewById<View>(R.id.output_device_title_area).apply {
            isEnabled=this@DeviceProfilePreference.isEnabled
            setOnClickListener { onClick() }
        }
        val showDriverStatus: () -> Unit = {
            var owner: Context? = context
            while (owner is ContextWrapper && owner !is FragmentActivity) owner = owner.baseContext
            val manager = (owner as? FragmentActivity)?.supportFragmentManager
            if (manager != null && !manager.isStateSaved && manager.findFragmentByTag(DriverStatusDialogFragment.TAG) == null)
                DriverStatusDialogFragment().showNow(manager, DriverStatusDialogFragment.TAG)
        }
        val driverHelp = holder.itemView.findViewById<ImageButton>(R.id.output_driver_info)
        driverHelp.visibility=View.VISIBLE
        driverHelp.setOnClickListener { showDriverStatus() }
        (holder.findViewById(android.R.id.summary) as? TextView)?.let { label ->
            // Driver diagnostics are a permanent action, independent of optional help markers.
            HelpPreferences.clearTitleHelp(label)
            label.setOnClickListener(null)
            label.isClickable=false
            label.isLongClickable=false
            label.isFocusable=false
        }
        holder.itemView.findViewById<View>(R.id.output_device_title_area).setOnLongClickListener {
            showDriverStatus()
            true
        }
        pin = holder.itemView.findViewById<ImageButton>(R.id.output_card_pin).apply {
            setOnClickListener {
                val key = OutputCardPinController.PREFERENCE_KEY
                appPreferences.edit().putBoolean(key, !appPreferences.getBoolean(key, true)).apply()
            }
        }
        renderPin()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == OutputCardPinController.PREFERENCE_KEY) renderPin()
    }

    private fun renderPin() {
        val pinned = appPreferences.getBoolean(OutputCardPinController.PREFERENCE_KEY, true)
        pin?.apply {
            isSelected = pinned
            setImageResource(if (pinned) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
            contentDescription = context.getString(if (pinned) R.string.output_card_unpin else R.string.output_card_pin)
        }
    }
}
