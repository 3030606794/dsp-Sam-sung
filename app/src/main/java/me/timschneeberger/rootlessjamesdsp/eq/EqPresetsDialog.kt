package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder

/** The list applies presets; each independent + only adds a home shortcut. */
internal class EqPresetsDialog(
    private val context: Context,
    private val store: EqPresetStore,
    private val requestImport: () -> Unit,
    private val requestExport: (String?) -> Unit,
    private val requestShare: (String) -> Unit,
    private val onApplied: () -> Unit,
) {
    private var main: AlertDialog? = null
    private val dialogs = mutableListOf<AlertDialog>()
    private var entries = emptyList<EqPreset>()
    private var shortcutIds = emptySet<String>()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val preset = getItem(position)
            val added = preset.id in shortcutIds
            return FullWidthPresetRow(context).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                tag = "eq_preset_row_${preset.id}"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(16), 0, dp(8), 0)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { applyPreset(preset.id) }
                setOnLongClickListener { showActions(preset.id); true }
                addView(TextView(context).apply {
                    tag = "eq_preset_name_${preset.id}"
                    text = preset.name
                    textSize = 16f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                    setOnClickListener { applyPreset(preset.id) }
                    setOnLongClickListener { showActions(preset.id); true }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val add = button("+", "eq_preset_add_${preset.id}") {
                    attempt {
                        message(when (store.addShortcut(preset.id)) {
                            EqPresetStore.ShortcutResult.ADDED -> "已添加到首页快捷"
                            EqPresetStore.ShortcutResult.ALREADY_ADDED -> "已添加到首页快捷，请勿重复添加"
                            EqPresetStore.ShortcutResult.FULL -> "首页快捷最多 5 个，请先移除一个"
                            EqPresetStore.ShortcutResult.NOT_FOUND -> "此预设已不存在，请刷新列表"
                        })
                        refresh()
                    }
                }.apply {
                    textSize = 24f
                    highlightShortcut(!added)
                    contentDescription = "${preset.name}：加入首页快捷"
                }
                addView(button("−", "eq_preset_remove_${preset.id}") {
                    attempt {
                        if (store.shortcuts().any { it.id == preset.id }) {
                            store.removeShortcut(preset.id)
                            message("已从首页快捷移除")
                        } else message("此预设尚未加入首页快捷")
                        refresh()
                    }
                }.apply {
                    textSize = 24f
                    highlightShortcut(added)
                    contentDescription = "${preset.name}：移除首页快捷"
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(add, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
        }
    }

    fun show() {
        if (main?.isShowing == true) { refresh(); return }
        entries = store.all()
        shortcutIds = store.shortcuts().mapTo(mutableSetOf()) { it.id }
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(TextView(context).apply {
                text = "预设"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(-1, dp(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(button("保存当前", "eq_preset_save") { saveCurrent() }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("导入", "eq_preset_import") { attempt(requestImport) }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("导出", "eq_preset_export") { chooseExport() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(context).setCustomTitle(title)
            .setAdapter(adapter) { _, position -> applyPreset(entries[position].id) }
            .setNegativeButton(android.R.string.cancel, null).create()
        main = dialog
        track(dialog).show()
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            showActions(entries[position].id)
            true
        }
    }

    fun refresh() {
        entries = store.all()
        shortcutIds = store.shortcuts().mapTo(mutableSetOf()) { it.id }
        adapter.notifyDataSetChanged()
    }

    fun dismiss() {
        dialogs.toList().asReversed().forEach { it.dismiss() }
        dialogs.clear()
        main = null
    }

    private fun applyPreset(id: String) = attempt {
        if (store.apply(id)) {
            onApplied()
            dismiss()
        } else message("此预设已不存在，请刷新列表")
    }

    private fun saveCurrent() {
        val input = EditText(context).apply {
            tag = "eq_preset_name_input"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            hint = "预设名称"
        }
        val container = LinearLayout(context).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = track(MaterialAlertDialogBuilder(context).setTitle("保存当前预设")
            .setView(container).setPositiveButton("保存", null)
            .setNegativeButton(android.R.string.cancel, null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.saveCurrent(input.text.toString())
                    dialog.dismiss()
                    refresh()
                    message("预设已保存")
                } catch (failure: Exception) {
                    input.error = failure.message ?: "保存失败"
                }
            }
        }
        dialog.show()
    }

    private fun showActions(id: String) = attempt {
        val preset = requireNotNull(store.find(id)) { "此预设已不存在，请刷新列表" }
        track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setItems(arrayOf("编辑", "重命名", "副本", "分享", "删除")) { _, index ->
                attempt {
                    when (index) {
                        0 -> track(EqPresetEditorDialog.create(context, preset) { gains, filter, interpolation ->
                            store.update(id, gains, filter, interpolation)
                            refresh()
                            message("预设已保存")
                        }).show()
                        1 -> renamePreset(preset)
                        2 -> {
                            val copy = store.duplicate(id)
                            refresh()
                            message("已创建副本：${copy.name}")
                        }
                        3 -> requestShare(id)
                        4 -> confirmDelete(preset)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun renamePreset(preset: EqPreset) {
        val input = EditText(context).apply {
            tag = "eq_preset_name_input"
            setSingleLine()
            setText(preset.name)
            selectAll()
        }
        val container = LinearLayout(context).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = track(MaterialAlertDialogBuilder(context).setTitle("重命名预设")
            .setView(container).setPositiveButton("保存", null)
            .setNegativeButton(android.R.string.cancel, null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.rename(preset.id, input.text.toString())
                    dialog.dismiss()
                    refresh()
                    message("已重命名")
                } catch (failure: Exception) { input.error = failure.message ?: "重命名失败" }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(preset: EqPreset) {
        track(MaterialAlertDialogBuilder(context).setTitle("删除预设")
            .setMessage("确定删除“${preset.name}”？")
            .setPositiveButton("删除") { _, _ ->
                attempt {
                    if (store.delete(preset.id)) {
                        refresh()
                        message("预设已删除")
                    } else message("此预设已不存在，请刷新列表")
                }
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun chooseExport() = attempt {
        val presets = store.all()
        val labels = listOf("当前设置") + presets.map { it.name }
        track(MaterialAlertDialogBuilder(context).setTitle("选择要导出的预设")
            .setItems(labels.toTypedArray()) { _, index ->
                attempt { requestExport(if (index == 0) null else presets[index - 1].id) }
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun MaterialButton.highlightShortcut(highlighted: Boolean) {
        setTextColor(if (highlighted)
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        else ColorUtils.setAlphaComponent(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface), 0x61))
    }

    private fun button(label: String, buttonTag: String, action: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = buttonTag
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs += dialog
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (main === dialog) main = null
        }
        return dialog
    }

    private fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) { message(failure.message ?: "预设操作失败") }
    }
    private fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** ListView can measure adapter rows with AT_MOST even inside a wide dialog. */
    private class FullWidthPresetRow(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val fullWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST)
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
            else widthMeasureSpec
            super.onMeasure(fullWidth, heightMeasureSpec)
        }
    }
}
