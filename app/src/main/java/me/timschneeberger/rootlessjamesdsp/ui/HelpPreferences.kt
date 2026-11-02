package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.lang.ref.WeakReference

/** App presentation settings. Audio preferences and parameter callbacks are not involved. */
object HelpPreferences {
    const val HIDE_QUESTIONS = "help_hide_questions"
    const val HIDE_EXCLAMATIONS = "help_hide_exclamations"

    fun hideQuestions(context: Context): Boolean = preferences(context).getBoolean(HIDE_QUESTIONS, false)
    fun hideExclamations(context: Context): Boolean = preferences(context).getBoolean(HIDE_EXCLAMATIONS, false)

    fun bindTitleHelp(label: TextView, question: View?, professional: View? = null, onHelp: () -> Unit) {
        val previous = label.getTag(R.id.help_title_binding) as? Binding
        val binding = Binding(question?.let(::WeakReference), professional?.let(::WeakReference), onHelp)
        label.setTag(R.id.help_title_binding, binding)
        // A title with an existing long-press action keeps that action.
        if (previous?.ownsLongClick == true || !label.isLongClickable) {
            binding.ownsLongClick = true
            // A long-clickable child consumes short taps too. Forward them to the existing
            // preference/card click so adding help never blocks expansion or selection.
            if (!label.hasOnClickListeners()) label.setOnClickListener {
                var owner = label.parent as? View
                while (owner != null) {
                    if (owner.isClickable && owner.hasOnClickListeners()) {
                        owner.performClick()
                        break
                    }
                    owner = owner.parent as? View
                }
            }
            label.setOnLongClickListener {
                (label.getTag(R.id.help_title_binding) as? Binding)?.onHelp?.invoke()
                true
            }
        }
        apply(label.context, binding)
    }

    /** Recycler rows can become status or empty rows; never revive their old help icons. */
    fun clearTitleHelp(label: TextView) {
        val previous = label.getTag(R.id.help_title_binding) as? Binding ?: return
        if (previous.ownsLongClick) {
            label.setOnLongClickListener(null)
            label.isLongClickable = false
        }
        label.setTag(R.id.help_title_binding, null)
    }

    /** Called when returning from Settings, including already inflated inline and pinned views. */
    fun refresh(rootView: View) {
        fun visit(view: View) {
            (view.getTag(R.id.help_title_binding) as? Binding)?.let { apply(view.context, it) }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(rootView)
    }

    private fun apply(context: Context, binding: Binding) {
        binding.question?.get()?.visibility = if (hideQuestions(context)) View.GONE else View.VISIBLE
        binding.professional?.get()?.visibility = if (hideExclamations(context)) View.GONE else View.VISIBLE
    }

    private fun preferences(context: Context) = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)

    private class Binding(
        val question: WeakReference<View>?,
        val professional: WeakReference<View>?,
        val onHelp: () -> Unit,
        var ownsLongClick: Boolean = false
    )
}
