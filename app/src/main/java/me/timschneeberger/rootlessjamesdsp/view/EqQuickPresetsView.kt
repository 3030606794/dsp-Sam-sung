package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetStore
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** The hint stays outside this viewport; only the user's shortcuts scroll. */
class EqQuickPresetsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {
    private val store = EqPresetStore(context)
    private val eqPreferences = PreferenceCache.getPreferences(context, Constants.PREF_EQ)
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var active = false
    private var listening = false
    private var shortcutMenu: PopupMenu? = null

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        isVisible = false
    }

    /** Called only by the multi-modal EQ preference, never by the PEQ preview. */
    fun activate() {
        active = true
        if (isAttachedToWindow) register()
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) {
            register()
            render()
        }
    }

    override fun onDetachedFromWindow() {
        shortcutMenu?.dismiss()
        shortcutMenu = null
        if (listening) {
            store.preferences.unregisterOnSharedPreferenceChangeListener(this)
            eqPreferences.unregisterOnSharedPreferenceChangeListener(this)
            listening = false
        }
        super.onDetachedFromWindow()
    }

    private fun register() {
        if (listening) return
        store.preferences.registerOnSharedPreferenceChangeListener(this)
        eqPreferences.registerOnSharedPreferenceChangeListener(this)
        listening = true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        post { if (active && isAttachedToWindow) render() }
    }

    private fun render() {
        val shortcuts = runCatching { store.shortcuts().take(5) }.getOrDefault(emptyList())
        val selected = runCatching { store.selectedShortcutId() }.getOrNull()
        buttons.removeAllViews()
        isVisible = shortcuts.isNotEmpty()
        shortcuts.forEach { preset ->
            val selectedPreset = preset.id == selected
            val button = TextView(context).apply {
                tag = preset.id
                text = preset.name
                textSize = 13f
                isSingleLine = true
                gravity = Gravity.CENTER
                includeFontPadding = false
                minimumHeight = 0
                setPadding(dp(10), 0, dp(10), 0)
                isSelected = selectedPreset
                setTextColor(MaterialColors.getColor(this, if (selectedPreset)
                    com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorPrimary))
                background = if (selectedPreset) GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(MaterialColors.getColor(this@EqQuickPresetsView,
                        com.google.android.material.R.attr.colorPrimaryContainer))
                } else null
                isClickable = true
                isFocusable = true
                contentDescription = "${preset.name} 快捷预设"
                setOnClickListener {
                    if (!runCatching { store.apply(preset.id) }.getOrDefault(false))
                        Toast.makeText(context, "应用预设失败", Toast.LENGTH_SHORT).show()
                    render()
                }
                setOnLongClickListener {
                    shortcutMenu?.dismiss()
                    shortcutMenu = PopupMenu(context, this).apply {
                        menu.add(0, 1, 0, "移除快捷预设")
                        setOnDismissListener { shortcutMenu = null }
                        setOnMenuItemClickListener {
                            val removed = runCatching { store.removeShortcut(preset.id) }.getOrDefault(false)
                            Toast.makeText(context, if (removed) "已移除快捷预设" else "移除失败", Toast.LENGTH_SHORT).show()
                            render()
                            true
                        }
                    }
                    shortcutMenu?.show()
                    true
                }
            }
            buttons.addView(button, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
