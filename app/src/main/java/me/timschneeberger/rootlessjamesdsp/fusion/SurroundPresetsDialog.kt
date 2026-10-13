package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import java.util.Locale

/** Owns modal UI only. The activity owns document pickers and applying the four audio values. */
class SurroundPresetsDialog(
    private val context: Context,
    private val store: SurroundPresetStore,
    private val readCurrent: () -> SurroundController.Settings,
    private val applyState: (SurroundController.Settings) -> Unit,
    private val requestImport: () -> Unit,
    private val requestExport: (String?) -> Unit
) {
    private val inflater = LayoutInflater.from(context)
    private val dialogs = mutableListOf<AlertDialog>()
    private var mainDialog: AlertDialog? = null
    private var mainView: View? = null

    fun show() {
        if (!canShow()) return
        if (mainDialog?.isShowing == true) {
            refreshStatus()
            return
        }
        val view = inflater.inflate(R.layout.surround_presets_dialog, null)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.surround_presets_title)
            .setView(view).setNegativeButton(R.string.close, null).create()
        mainView = view
        mainDialog = dialog
        track(dialog) {
            if (mainDialog === dialog) { mainDialog = null; mainView = null }
        }
        view.findViewById<View>(R.id.surround_presets_save).setOnClickListener { editName(null) }
        view.findViewById<View>(R.id.surround_presets_import).setOnClickListener { attempt(requestImport) }
        view.findViewById<View>(R.id.surround_presets_export_all).setOnClickListener {
            attempt { requestExport(null) }
        }
        dialog.show()
        refreshStatus()
    }

    /** Refresh after document-picker completion; an error remains visible alongside saved entries. */
    fun refreshStatus(error: String? = null) {
        val view = mainView ?: return
        val list = view.findViewById<LinearLayout>(R.id.surround_presets_list)
        val count = view.findViewById<TextView>(R.id.surround_presets_count)
        val status = view.findViewById<TextView>(R.id.surround_presets_status)
        val save = view.findViewById<MaterialButton>(R.id.surround_presets_save)
        val importButton = view.findViewById<MaterialButton>(R.id.surround_presets_import)
        val export = view.findViewById<MaterialButton>(R.id.surround_presets_export_all)
        refreshScope()
        list.removeAllViews()
        val presets = try { store.load() } catch (failure: Exception) {
            count.setText(R.string.peq_presets_load_failed)
            status.text = errorMessage(failure)
            status.isVisible = true
            save.isEnabled = false
            importButton.isEnabled = false
            export.isEnabled = false
            return
        }
        count.text = context.getString(R.string.peq_presets_count, presets.size, SurroundPresetStore.MAX_PRESETS)
        val hasSpace = presets.size < SurroundPresetStore.MAX_PRESETS
        save.isEnabled = hasSpace
        importButton.isEnabled = hasSpace
        export.isEnabled = presets.isNotEmpty()
        status.text = error?.takeIf { it.isNotBlank() } ?: when {
            presets.isEmpty() -> context.getString(R.string.peq_presets_empty)
            !hasSpace -> context.getString(R.string.peq_presets_full, SurroundPresetStore.MAX_PRESETS)
            else -> ""
        }
        status.isVisible = status.text.isNotEmpty()
        presets.forEach { preset ->
            val row = inflater.inflate(R.layout.peq_preset_row, list, false)
            row.tag = preset.id
            val summary = context.getString(R.string.surround_preset_summary, preset.settings.lowHz,
                preset.settings.highHz,
                String.format(Locale.ROOT, "%.3f", preset.settings.attenuationMilliDb / 1000.0).trimEnd('0').trimEnd('.'),
                preset.settings.delayUs)
            row.findViewById<TextView>(R.id.peq_preset_row_name).text = preset.name
            row.findViewById<TextView>(R.id.peq_preset_row_count).text = summary
            row.contentDescription = "${preset.name}，$summary"
            row.setOnClickListener { showActions(preset) }
            list.addView(row)
        }
    }

    /** Device profiles can change the active slot while this dialog remains open. */
    fun refreshScope() {
        mainView?.findViewById<TextView>(R.id.surround_presets_scope)?.text =
            context.getString(R.string.surround_presets_scope, SurroundController.selected(context) + 1)
    }

    /** The host calls this before releasing the activity and its themed context. */
    fun dismiss() {
        dialogs.toList().asReversed().forEach { it.dismiss() }
        dialogs.clear()
        mainDialog = null
        mainView = null
    }

    private fun showActions(preset: SurroundPreset) {
        if (!canShow()) return
        val actions = intArrayOf(R.string.peq_preset_apply, R.string.peq_preset_rename,
            R.string.peq_preset_overwrite, R.string.peq_preset_export, R.string.peq_preset_delete)
        track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setItems(actions.map { context.getString(it) }.toTypedArray()) { _, index ->
                when (index) {
                    0 -> attempt { applyState(currentPreset(preset.id).settings); dismiss() }
                    1 -> editName(preset)
                    2 -> confirm(R.string.peq_preset_overwrite,
                        context.getString(R.string.surround_preset_overwrite_confirm, preset.name)) {
                        store.overwrite(preset.id, readCurrent())
                    }
                    3 -> attempt { requestExport(currentPreset(preset.id).id) }
                    4 -> confirm(R.string.peq_preset_delete,
                        context.getString(R.string.surround_preset_delete_confirm, preset.name)) {
                        store.delete(preset.id)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun editName(preset: SurroundPreset?) {
        if (!canShow()) return
        val view = inflater.inflate(R.layout.peq_preset_name_dialog, null)
        val input = view.findViewById<TextInputEditText>(R.id.peq_preset_name)
        val box = view.findViewById<TextInputLayout>(R.id.peq_preset_name_box)
        box.isCounterEnabled = true
        box.counterMaxLength = SurroundPresetStore.MAX_NAME_LENGTH
        box.helperText = context.getString(R.string.peq_preset_name_hint, SurroundPresetStore.MAX_NAME_LENGTH)
        input.setText(preset?.name.orEmpty())
        input.doAfterTextChanged { box.error = null }
        val dialog = track(MaterialAlertDialogBuilder(context)
            .setTitle(if (preset == null) R.string.peq_presets_save else R.string.peq_preset_rename)
            .setView(view).setPositiveButton(R.string.peq_preset_save, null)
            .setNegativeButton(android.R.string.cancel, null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val name = input.text?.toString().orEmpty()
                    if (preset == null) store.save(name, readCurrent()) else store.rename(preset.id, name)
                    dialog.dismiss()
                    refreshStatus()
                } catch (failure: Exception) {
                    box.error = errorMessage(failure)
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

    private fun confirm(title: Int, message: String, action: () -> Unit) {
        if (!canShow()) return
        val dialog = track(MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (attempt(action)) { dialog.dismiss(); refreshStatus() }
            }
        }
        dialog.show()
    }

    private fun currentPreset(id: String): SurroundPreset = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException(context.getString(R.string.peq_preset_missing))

    private fun attempt(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (failure: Exception) {
        if (canShow()) track(MaterialAlertDialogBuilder(context).setTitle(R.string.peq_presets_error_title)
            .setMessage(errorMessage(failure)).setPositiveButton(android.R.string.ok, null).create()).show()
        false
    }

    private fun errorMessage(failure: Exception): String = failure.localizedMessage?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.peq_presets_unknown_error)

    private fun track(dialog: AlertDialog, onDismiss: () -> Unit = {}): AlertDialog {
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog); onDismiss() }
        return dialog
    }

    private fun canShow(): Boolean {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return !current.isFinishing && !current.isDestroyed
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return true
    }
}
