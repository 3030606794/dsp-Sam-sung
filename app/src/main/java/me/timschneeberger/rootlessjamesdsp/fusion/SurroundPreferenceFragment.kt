package me.timschneeberger.rootlessjamesdsp.fusion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Opening the surround card only exposes its editor; the right switch owns enabling. */
@SuppressLint("RestrictedApi")
class SurroundPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var group: SwitchPreferenceGroup
    private lateinit var editor: Preference
    private lateinit var driverStatus: Preference
    private var stores = emptyList<SharedPreferences>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { render() }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceManager.sharedPreferencesName = SurroundController.PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        group = SwitchPreferenceGroup(ctx).apply {
            key = SurroundController.ENABLED
            title = getString(R.string.surround_title)
            setIcon(R.drawable.ic_twotone_speaker_24dp)
            isIconSpaceReserved = false
            setIsIconVisible(true)
            // The controller checks current driver capabilities before persisting power.
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                val supported = SurroundController.supported(ctx)
                val savedEnabled = SurroundController.enabled(ctx)
                val requested = if (savedEnabled && !supported) false else newValue as Boolean
                if (!SurroundController.setEnabled(ctx, requested))
                    Toast.makeText(ctx, R.string.surround_driver_required, Toast.LENGTH_LONG).show()
                render()
                // render supplies the controller's accepted state, including a rejected enable.
                false
            }
        }
        preferenceScreen.addPreference(group)
        editor = Preference(ctx).apply {
            key = EDITOR_KEY
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(ctx, SurroundActivity::class.java))
                true
            }
        }
        driverStatus = Preference(ctx).apply {
            key = DRIVER_KEY
            setTitle(R.string.surround_driver_pending)
            isIconSpaceReserved = false
            isSelectable = false
        }
        group.addPreference(editor)
        group.setExpanded(savedInstanceState?.getBoolean(STATE_EXPANDED, false) ?: false)
        stores = listOf(SurroundController.preferences(ctx), SurroundController.statusPreferences(ctx))
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        ctx.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        render()
    }

    private fun powerDescription(): String = getString(when {
        SurroundController.enabled(requireContext()) && !SurroundController.supported(requireContext()) -> R.string.surround_power_pending
        SurroundController.enabled(requireContext()) -> R.string.surround_power_on
        else -> R.string.surround_power_off
    })

    private fun render() {
        if (!::group.isInitialized) return
        val ctx = requireContext()
        val supported = SurroundController.supported(ctx)
        group.setValue(SurroundController.enabled(ctx) && supported)
        val settings = SurroundController.settings(ctx)
        editor.title = getString(R.string.surround_summary,
            SurroundController.selected(ctx) + 1, settings.lowHz, settings.highHz)
        // Group binding controls all child visibility, so omit this row while supported.
        if (!supported && group.findPreference<Preference>(DRIVER_KEY) == null) group.addPreference(driverStatus)
        else if (supported) group.removePreference(driverStatus)
        view?.findViewById<MaterialSwitch>(R.id.switchWidget)?.contentDescription = powerDescription()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPANDED, group.isExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView =
        super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null
            isNestedScrollingEnabled = false
        }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                when (getItem(position)) {
                    group -> (holder.findViewById(R.id.switchWidget) as MaterialSwitch).contentDescription = powerDescription()
                    editor -> holder.itemView.id = R.id.surround_editor_entry
                    driverStatus -> holder.itemView.id = R.id.surround_driver_status
                }
                if (getItem(position) !is androidx.preference.PreferenceGroup)
                    holder.itemView.background = ContextCompat.getDrawable(requireContext(), R.drawable.ripple_rounded)
            }
        }

    override fun onDestroy() {
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        stores = emptyList()
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        private const val EDITOR_KEY = "surround_editor"
        private const val DRIVER_KEY = "surround_driver"
        private const val STATE_EXPANDED = "surround_expanded"
    }
}
