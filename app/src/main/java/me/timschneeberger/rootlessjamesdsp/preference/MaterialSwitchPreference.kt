package me.timschneeberger.rootlessjamesdsp.preference
import android.content.Context
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R


class MaterialSwitchPreference : SwitchPreferenceCompat {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context) : super(context) {
        init()
    }

    private fun init() {
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        if (!me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp.isProfessionalContext(context)) return
        val label = holder.findViewById(android.R.id.title) as? android.widget.TextView ?: return
        val toggle = holder.findViewById(R.id.switchWidget) as? com.google.android.material.materialswitch.MaterialSwitch ?: return
        me.timschneeberger.rootlessjamesdsp.ui.ParameterSwitchUi.configure(toggle, label)
    }
}
