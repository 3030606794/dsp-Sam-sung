package me.timschneeberger.rootlessjamesdsp.ui

import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R

/** Presentation only: value acceptance and persistence stay with each preference. */
object SliderPreferenceUi {
    fun bind(holder: PreferenceViewHolder, title: CharSequence?, technical: CharSequence?, range: String,
             titleId: Int = android.R.id.title) {
        val root = holder.itemView
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val bottom = (4 * root.resources.displayMetrics.density).toInt()
            if (it.topMargin != 0 || it.bottomMargin != bottom) {
                it.topMargin = 0; it.bottomMargin = bottom; root.layoutParams = it
            }
        }
        holder.findViewById(android.R.id.summary)?.visibility = View.GONE
        val help = holder.findViewById(R.id.slider_help) as? TextView ?: return
        val label = holder.findViewById(titleId) as? TextView
        help.text = "?"
        help.includeFontPadding = false
        help.gravity = Gravity.CENTER
        help.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
        label?.includeFontPadding = false
        (label?.parent as? LinearLayout)?.let { row ->
            row.gravity = (row.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
            row.isBaselineAligned = false
        }
        help.isEnabled = true
        help.contentDescription = "${title?.toString().orEmpty()}：参数说明"
        help.setOnClickListener { ParameterHelp.show(root.context, title?.toString().orEmpty(),
            technical?.toString().orEmpty(), range) }
        if (label != null) {
            if (title.isNullOrBlank()) HelpPreferences.clearTitleHelp(label)
            else HelpPreferences.bindTitleHelp(label, help) {
                ParameterHelp.show(root.context, title.toString(), technical?.toString().orEmpty(), range)
            }
        }
        if (label != null && help.getTag(R.id.slider_help) != true) {
            help.setTag(R.id.slider_help, true)
            (label.parent as? ViewGroup)?.addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
                val helpWidth = if (help.visibility == View.GONE) 0 else help.width
                val available = (parent.width - parent.paddingLeft - parent.paddingRight - helpWidth).coerceAtLeast(1)
                if (label.maxWidth != available) label.maxWidth = available
            }
        }
    }
}
