package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** A single level entry; its power controls only the new surround processor. */
class SurroundPreferenceFragment : Fragment() {
    private var stores = emptyList<SharedPreferences>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { render() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(12))
        }
        val row = LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(TextView(ctx).apply {
            text = getString(R.string.surround_title)
            textSize = 16f
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { openEditor() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(AppCompatImageButton(ctx).apply {
            id = R.id.surround_power
            background = null
            setImageResource(R.drawable.ic_twotone_power_settings_24dp)
            imageTintList = ctx.getColorStateList(R.color.selector_floating_toggle_foreground_tint)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (!SurroundController.setEnabled(ctx, !SurroundController.enabled(ctx)))
                    Toast.makeText(ctx, R.string.surround_driver_required, Toast.LENGTH_LONG).show()
                render()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(row)
        root.addView(TextView(ctx).apply {
            id = R.id.surround_editor_entry
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setOnClickListener { openEditor() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(ctx).apply {
            id = R.id.surround_driver_status
            text = getString(R.string.surround_driver_pending)
            textSize = 12f
        })
        return root
    }

    private fun openEditor() = startActivity(Intent(requireContext(), SurroundActivity::class.java))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stores = listOf(SurroundController.preferences(requireContext()), SurroundController.statusPreferences(requireContext()))
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        render()
    }

    private fun render() {
        val root = view ?: return
        val ctx = requireContext()
        val supported = SurroundController.supported(ctx)
        val selected = SurroundController.enabled(ctx)
        root.findViewById<AppCompatImageButton>(R.id.surround_power).apply {
            isSelected = selected && supported
            contentDescription = getString(when {
                selected && !supported -> R.string.surround_power_pending
                selected -> R.string.surround_power_on
                else -> R.string.surround_power_off
            })
        }
        val settings = SurroundController.settings(ctx)
        root.findViewById<TextView>(R.id.surround_editor_entry).text =
            getString(R.string.surround_summary, SurroundController.selected(ctx) + 1, settings.lowHz, settings.highHz)
        root.findViewById<TextView>(R.id.surround_driver_status).isVisible = !supported
    }

    override fun onDestroyView() {
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        stores = emptyList()
        requireContext().unregisterLocalReceiver(receiver)
        super.onDestroyView()
    }
}
