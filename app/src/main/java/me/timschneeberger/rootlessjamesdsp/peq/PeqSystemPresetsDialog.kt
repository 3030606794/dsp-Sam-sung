package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R

class PeqSystemPresetsDialog(
    private val context: Context,
    private val presets: List<PeqSystemPreset>,
    private val applyPreset: (PeqSystemPreset) -> Boolean
) {
    private val dialogs = mutableListOf<AlertDialog>()
    private var main: AlertDialog? = null

    fun show() {
        if (main?.isShowing == true) return
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.peq_system_presets_dialog, null)
        view.findViewById<TextView>(R.id.peq_system_presets_count).text =
            context.getString(R.string.peq_system_presets_count, presets.size)
        val list = view.findViewById<ListView>(R.id.peq_system_presets_list)
        var visiblePresets = presets
        val adapter = object : BaseAdapter() {
            override fun getCount() = visiblePresets.size
            override fun getItem(position: Int) = visiblePresets[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val row = recycled ?: inflater.inflate(R.layout.peq_preset_row, parent, false)
                val preset = getItem(position)
                row.tag = preset.id
                // ListView owns item clicks; the personal-library row otherwise consumes them.
                row.isClickable = false
                row.isFocusable = false
                row.contentDescription = "${preset.name}，${preset.bands.size} 段"
                row.findViewById<TextView>(R.id.peq_preset_row_name).text = preset.name
                row.findViewById<TextView>(R.id.peq_preset_row_count).text = context.getString(
                    R.string.peq_system_presets_row, context.getString(if (preset.category == "parametric")
                        R.string.peq_system_presets_parametric else R.string.peq_system_presets_graphic), preset.bands.size)
                return row
            }
        }
        list.adapter = adapter
        list.emptyView = view.findViewById(R.id.peq_system_presets_empty)
        list.setOnItemClickListener { _, _, position, _ -> details(visiblePresets[position]) }
        view.findViewById<EditText>(R.id.peq_system_presets_search).doAfterTextChanged { text ->
            val query = text.toString().trim()
            visiblePresets = presets.filter { it.name.contains(query, ignoreCase = true) }
            adapter.notifyDataSetChanged()
        }
        main = track(MaterialAlertDialogBuilder(context).setTitle(R.string.peq_system_presets)
            .setView(view).setNegativeButton(R.string.close, null).create()).also { it.show() }
    }

    private fun details(preset: PeqSystemPreset) {
        val message = buildList {
            add(context.getString(R.string.peq_system_presets_details, preset.bands.size,
                PeqBandAdapter.number(preset.bands.minOf { it.frequency }),
                PeqBandAdapter.number(preset.bands.maxOf { it.frequency })))
            if (preset.adaptedFrequencyCount > 0) add(context.getString(R.string.peq_system_presets_compat,
                preset.adaptedFrequencyCount))
            if (preset.sourcePreampDb != 0.0) add(context.getString(R.string.peq_system_presets_preamp,
                PeqBandAdapter.number(preset.sourcePreampDb)))
            if (preset.sourceBandwidths.any { it != null }) add(context.getString(R.string.peq_system_presets_quality))
        }.joinToString("\n\n")
        val dialog = track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setMessage(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.peq_system_presets_apply, null).create())
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (applyPreset(preset)) dismissAll()
        }
    }

    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog); if (main === dialog) main = null }
        return dialog
    }

    fun dismissAll() { dialogs.toList().forEach { it.dismiss() } }
}
