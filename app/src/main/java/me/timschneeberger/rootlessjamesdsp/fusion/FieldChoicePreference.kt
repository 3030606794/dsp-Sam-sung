package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder

/** Discrete choices do not claim a numeric precision that the algorithm does not have. */
class FieldChoicePreference(context: Context, key: String, title: String,
    private var choices: List<String>, private val resetLabel: String = "恢复默认",private val resetSelection: Int=0,
    private val inline: Boolean = false) : Preference(context) {
    var value: Int = 0
        set(value) { if (field != value) { field = value; notifyChanged() } }
    init {
        this.key = key; this.title = title; isPersistent = false; isIconSpaceReserved = false
        if (inline) { layoutResource = R.layout.preference_field_mode_inline; isSelectable = false }
    }
    override fun getSummary(): CharSequence = choices.getOrElse(value) { choices.first() }
    fun updateChoices(next: List<String>) {
        if (next == choices) return
        choices=next; value=value.coerceIn(choices.indices); notifyChanged()
    }
    fun choose(candidate: Int): Boolean {
        if (!isEnabled || candidate !in choices.indices) return false
        if (candidate == value) return true
        if (!callChangeListener(candidate)) return false
        value = candidate; return true
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val buttons = if (inline) holder.findViewById(R.id.field_mode_choices) as? RadioGroup else null
        buttons?.setOnCheckedChangeListener(null)
        super.onBindViewHolder(holder)
        if (buttons == null) return
        val ids = listOf(R.id.field_mode_legacy, R.id.field_mode_ms)
        ids.forEachIndexed { index, id ->
            (holder.findViewById(id) as? RadioButton)?.apply {
                text = choices.getOrElse(index) { "" }
                isEnabled = this@FieldChoicePreference.isEnabled && index in choices.indices
            }
        }
        buttons.isEnabled = isEnabled
        buttons.check(ids.getOrNull(value) ?: -1)
        buttons.setOnCheckedChangeListener(object : RadioGroup.OnCheckedChangeListener {
            override fun onCheckedChanged(group: RadioGroup, checkedId: Int) {
                val selected = ids.indexOf(checkedId)
                if (selected < 0 || choose(selected)) return
                group.setOnCheckedChangeListener(null)
                group.check(ids.getOrNull(value) ?: -1)
                group.setOnCheckedChangeListener(this)
            }
        })
    }
    override fun onClick() {
        if (!isEnabled || inline) return
        MaterialAlertDialogBuilder(context).setTitle(title).setSingleChoiceItems(choices.toTypedArray(),value) { dialog, selected ->
            if (choose(selected)) dialog.dismiss()
        }.setNeutralButton(resetLabel) { _, _ -> choose(resetSelection) }.setNegativeButton(android.R.string.cancel,null).show()
    }
}
