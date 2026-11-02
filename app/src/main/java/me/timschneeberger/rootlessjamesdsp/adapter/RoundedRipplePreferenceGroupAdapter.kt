package me.timschneeberger.rootlessjamesdsp.adapter

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset

@SuppressLint("RestrictedApi")
class RoundedRipplePreferenceGroupAdapter(private val preferenceGroup: PreferenceGroup) : PreferenceGroupAdapter(preferenceGroup) {
    init {
        normalizeSwitches(preferenceGroup)
        ParameterHelp.preparePreferences(preferenceGroup)
    }

    private fun normalizeSwitches(preference: Preference) {
        if (!ParameterHelp.isProfessionalContext(preference.context)) return
        if (preference is SwitchPreferenceCompat && preference.widgetLayoutResource != R.layout.preference_materialswitch)
            preference.widgetLayoutResource = R.layout.preference_materialswitch
        if (preference is PreferenceGroup) for (index in 0 until preference.preferenceCount)
            normalizeSwitches(preference.getPreference(index))
    }

    override fun onPreferenceHierarchyChange(preference: Preference) {
        normalizeSwitches(preferenceGroup)
        super.onPreferenceHierarchyChange(preference)
    }

    override fun onViewRecycled(holder: PreferenceViewHolder) {
        fun release(view: View) {
            (view.getTag(R.id.button_press_repeat_binding) as? ButtonPressRepeat)?.dispose()
            if (view is Slider) {
                // A preference object outlives its row. Retire gesture callbacks and its
                // ownership tag before the holder is reused for another parameter.
                (view.getTag(R.id.seekbar) as? PreferenceValueSync)?.releaseBoundView()
                SliderDoubleTapReset.clear(view)
                view.clearOnChangeListeners()
                view.clearOnSliderTouchListeners()
                view.setTag(R.id.seekbar, null)
            }
            if (view is ViewGroup) repeat(view.childCount) { release(view.getChildAt(it)) }
        }
        release(holder.itemView)
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(
        holder: PreferenceViewHolder,
        position: Int
    ) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position)
        preference ?: return
        if (preference !is SwitchPreferenceGroup && preference.javaClass.simpleName != "IrHelpPreference")
            ParameterHelp.bindPreference(preference, holder)

        if (preference is me.timschneeberger.rootlessjamesdsp.preference.DeviceProfilePreference) {
            // The route capsule, diagnostics and pin each own a bounded press surface.
            holder.itemView.background = null
            holder.itemView.foreground = null
            return
        }

        if (preference is TwoStatePreference && ParameterHelp.isProfessionalContext(preference.context)) {
            if (preference !is SwitchPreferenceGroup) {
                val label = holder.findViewById(android.R.id.title) as? android.widget.TextView
                val toggle = holder.findViewById(R.id.switchWidget) as? com.google.android.material.materialswitch.MaterialSwitch
                if (label != null && toggle != null)
                    me.timschneeberger.rootlessjamesdsp.ui.ParameterSwitchUi.configure(toggle, label)
            }
            // Drop only the press/ripple surface; the original toggle and change listener remain intact.
            holder.itemView.background = null
            holder.itemView.foreground = null
            fun clearSwitchSurface(view: View) {
                if (view is CompoundButton) { view.background = null; view.foreground = null }
                if (view is ViewGroup) (0 until view.childCount).forEach { clearSwitchSurface(view.getChildAt(it)) }
            }
            clearSwitchSurface(holder.itemView)
        } else if(preference !is PreferenceGroup) {
            holder.itemView.background = ContextCompat.getDrawable(
                preference.context,
                R.drawable.ripple_rounded
            )
        }
    }
}
