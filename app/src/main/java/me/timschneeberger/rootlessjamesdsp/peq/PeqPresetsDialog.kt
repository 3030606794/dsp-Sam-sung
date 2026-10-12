package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R

/** Owns only the preset dialogs. The host owns audio changes and document picker lifetimes. */
class PeqPresetsDialog(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val readCurrent: () -> PeqState,
    private val applyState: (PeqState) -> Boolean,
    private val onImportRequested: () -> Unit,
    private val onExportRequested: (filename: String, json: String) -> Unit,
    private val store: PeqPresetStore = PeqPresetStore(context)
) {
    private val dialogs = mutableListOf<AlertDialog>()
    private var mainDialog: AlertDialog? = null
    private var mainView: View? = null

    fun show() {
        if (mainDialog?.isShowing == true) {
            refresh()
            return
        }
        val view = inflater.inflate(R.layout.peq_presets_dialog, null)
        mainView = view
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.peq_presets_title)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()
        mainDialog = dialog
        track(dialog) {
            if (mainDialog === dialog) {
                mainDialog = null
                mainView = null
            }
        }
        view.findViewById<View>(R.id.peq_presets_save).setOnClickListener {
            editName(null)
        }
        view.findViewById<View>(R.id.peq_presets_import).setOnClickListener {
            attempt { onImportRequested() }
        }
        view.findViewById<View>(R.id.peq_presets_export_all).setOnClickListener {
            attempt { onExportRequested("peq-presets.json", store.exportJson()) }
        }
        dialog.show()
        refresh()
    }

    /** Call after importing to update the open list without opening another dialog. */
    fun refresh() {
        val view = mainView ?: return
        val list = view.findViewById<LinearLayout>(R.id.peq_presets_list)
        val count = view.findViewById<TextView>(R.id.peq_presets_count)
        val status = view.findViewById<TextView>(R.id.peq_presets_status)
        val save = view.findViewById<MaterialButton>(R.id.peq_presets_save)
        val export = view.findViewById<MaterialButton>(R.id.peq_presets_export_all)
        list.removeAllViews()
        val presets = try {
            store.load()
        } catch (error: Exception) {
            count.setText(R.string.peq_presets_load_failed)
            status.text = errorMessage(error)
            status.isVisible = true
            save.isEnabled = false
            export.isEnabled = false
            showError(error)
            return
        }
        count.text = context.getString(R.string.peq_presets_count, presets.size, PeqPresetStore.MAX_PRESETS)
        save.isEnabled = presets.size < PeqPresetStore.MAX_PRESETS
        export.isEnabled = presets.isNotEmpty()
        status.text = when {
            presets.isEmpty() -> context.getString(R.string.peq_presets_empty)
            !save.isEnabled -> context.getString(R.string.peq_presets_full, PeqPresetStore.MAX_PRESETS)
            else -> ""
        }
        status.isVisible = status.text.isNotEmpty()
        presets.forEach { preset ->
            val row = inflater.inflate(R.layout.peq_preset_row, list, false)
            row.tag = preset.id
            val bands = context.getString(R.string.peq_preset_band_count, preset.state.bands.size)
            row.findViewById<TextView>(R.id.peq_preset_row_name).text = preset.name
            row.findViewById<TextView>(R.id.peq_preset_row_count).text = bands
            row.contentDescription = "${preset.name}，$bands"
            row.setOnClickListener { showActions(preset) }
            list.addView(row)
        }
    }

    /** The fragment calls this before releasing its view and themed context. */
    fun dismissAll() {
        dialogs.toList().asReversed().forEach { it.dismiss() }
        dialogs.clear()
        mainDialog = null
        mainView = null
    }

    private fun showActions(preset: PeqPreset) {
        val actions = intArrayOf(R.string.peq_preset_apply, R.string.peq_preset_rename,
            R.string.peq_preset_overwrite, R.string.peq_preset_export, R.string.peq_preset_delete)
        track(MaterialAlertDialogBuilder(context)
            .setTitle(preset.name)
            .setItems(actions.map { context.getString(it) }.toTypedArray()) { _, index ->
                when (index) {
                    0 -> attempt {
                        if (!applyState(currentPreset(preset.id).state)) {
                            throw IllegalStateException(context.getString(R.string.peq_preset_apply_failed))
                        }
                        dismissAll()
                    }
                    1 -> editName(preset)
                    2 -> confirmOverwrite(preset)
                    3 -> attempt {
                        val latest = currentPreset(preset.id)
                        onExportRequested(exportFilename(latest.name), store.exportJson(latest.id))
                    }
                    4 -> confirmDelete(preset)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()).show()
    }

    private fun editName(preset: PeqPreset?) {
        val view = inflater.inflate(R.layout.peq_preset_name_dialog, null)
        val input = view.findViewById<TextInputEditText>(R.id.peq_preset_name)
        val box = view.findViewById<TextInputLayout>(R.id.peq_preset_name_box)
        box.isCounterEnabled = true
        box.counterMaxLength = PeqPresetStore.MAX_NAME_LENGTH
        box.helperText = context.getString(R.string.peq_preset_name_hint, PeqPresetStore.MAX_NAME_LENGTH)
        input.setText(preset?.name.orEmpty())
        input.doAfterTextChanged { box.error = null }
        val dialog = track(MaterialAlertDialogBuilder(context)
            .setTitle(if (preset == null) R.string.peq_presets_save else R.string.peq_preset_rename)
            .setView(view)
            .setPositiveButton(R.string.peq_preset_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val name = input.text?.toString().orEmpty()
                    if (preset == null) store.save(name, readCurrent()) else store.rename(preset.id, name)
                    dialog.dismiss()
                    refresh()
                } catch (error: Exception) {
                    // Keep the entered name and dialog open so the user can correct or retry it.
                    box.error = errorMessage(error)
                }
            }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else false
            }
        }
        dialog.show()
    }

    private fun confirmOverwrite(preset: PeqPreset) {
        confirm(R.string.peq_preset_overwrite,
            context.getString(R.string.peq_preset_overwrite_confirm, preset.name)) {
            store.overwrite(preset.id, readCurrent())
        }
    }

    private fun confirmDelete(preset: PeqPreset) {
        confirm(R.string.peq_preset_delete,
            context.getString(R.string.peq_preset_delete_confirm, preset.name)) {
            store.delete(preset.id)
        }
    }

    private fun confirm(title: Int, message: String, action: () -> Unit) {
        val dialog = track(MaterialAlertDialogBuilder(context)
            .setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (attempt(action)) {
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun currentPreset(id: String): PeqPreset = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException(context.getString(R.string.peq_preset_missing))

    private fun exportFilename(name: String): String {
        val cleaned = name.map { char ->
            if (char.isISOControl() || char in "\\/:*?\"<>|") '_' else char
        }.joinToString("").trim().trim('.')
        return "${cleaned.ifBlank { "peq-preset" }}.json"
    }

    private fun attempt(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Exception) {
        showError(error)
        false
    }

    private fun errorMessage(error: Exception): String = error.localizedMessage?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.peq_presets_unknown_error)

    private fun showError(error: Exception) {
        track(MaterialAlertDialogBuilder(context).setTitle(R.string.peq_presets_error_title)
            .setMessage(context.getString(R.string.peq_presets_error, errorMessage(error)))
            .setPositiveButton(android.R.string.ok, null).create()).show()
    }

    private fun track(dialog: AlertDialog, onDismiss: () -> Unit = {}): AlertDialog {
        dialogs += dialog
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            onDismiss()
        }
        return dialog
    }
}
