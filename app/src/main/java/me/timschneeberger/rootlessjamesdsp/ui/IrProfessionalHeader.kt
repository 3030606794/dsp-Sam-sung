package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity

/** One IR chart header is moved by the host when its pin changes. */
class IrProfessionalHeader(
    context: Context,
    chart: View,
    fallback: View,
    private val onModeChanged: (Int) -> Unit,
    private val onPinChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val tabs = mutableListOf<TextView>()
    private val pin = ImageButton(context)
    private val metadata = LinearLayout(context)
    private val metadataLines = List(3) { TextView(context) }
    private val runtime = TextView(context)
    private var runtimeDetails = ""
    private var selectedMode = -1
    var pinned = true
        private set
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(MaterialColors.getColor(this@IrProfessionalHeader, com.google.android.material.R.attr.colorSurface))
                setStroke(dp(1).coerceAtLeast(1), (foregroundColor and 0x00ffffff) or 0x33000000)
            }
            clipToOutline = true
        }
        listOf("幅度", "相位", "群延迟", "能量").forEachIndexed { index, label ->
            if (index > 0) bar.addView(View(context).apply {
                setBackgroundColor((foregroundColor and 0x00ffffff) or 0x33000000)
            }, LayoutParams(dp(1).coerceAtLeast(1), LayoutParams.MATCH_PARENT))
            val tab = TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                contentDescription = "IR 分析：$label"
                setOnClickListener { onModeChanged(index) }
            }
            tabs += tab
            bar.addView(tab, LayoutParams(0, dp(42), 1f))
        }
        bar.addView(View(context).apply {
            setBackgroundColor((foregroundColor and 0x00ffffff) or 0x33000000)
        }, LayoutParams(dp(1).coerceAtLeast(1), LayoutParams.MATCH_PARENT))
        pin.apply {
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setColorFilter(accent)
            setOnClickListener {
                pinned = !pinned
                updatePin()
                onPinChanged(pinned)
            }
        }
        bar.addView(pin, LayoutParams(dp(44), dp(42)))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        addView(FrameLayout(context).apply {
            addView(fallback, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(chart, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(212)))
        val details = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        metadata.orientation = VERTICAL
        metadataLines.forEach { line ->
            line.apply {
                textSize = 11f; setTextColor(foregroundColor); setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            metadata.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        details.addView(metadata, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val openGraphHelp: () -> Unit = {
            ParameterHelp.show(context, "IR 图表与实时状态",
                technical = "幅度页对照原 IR 与工作核；彩色依次表示核 1–4。相位和群延迟以工作核起点为参考，深凹陷或静音位置留空。能量衰减由工作核计算，不代表房间实测混响时间。曲线不包含干湿混合、输出校准及后续音效。CRC 仅核对当前生成的工作核与驱动装载内容；实时读数属于卷积模块。" + if(runtimeDetails.isBlank()) "" else "\n\n当前状态\n$runtimeDetails",
                range = "点击横栏切换幅度、相位、群延迟或能量；相位单位为 °，群延迟为 ms，能量为 dB。深度分析关闭时，幅度页显示基础幅频与波形预览。图钉默认固定，关闭后整块图表随参数页滚动。")
        }
        val graphHelp = TextView(context).apply {
            text = "?"; textSize = 17f; includeFontPadding = false; gravity = Gravity.CENTER; setTextColor(accent)
            contentDescription = "IR 图表与监测说明"
            setOnClickListener { openGraphHelp() }
        }
        details.addView(graphHelp, LayoutParams(dp(36), dp(40)))
        HelpPreferences.bindTitleHelp(metadataLines.first(), graphHelp, onHelp = openGraphHelp)
        tabs.forEach { HelpPreferences.bindTitleHelp(it, null, onHelp = openGraphHelp) }
        addView(details, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        runtime.apply {
            textSize = 11f
            setTextColor(foregroundColor)
            // Preparing a replacement IR must not resize the fixed chart area.
            setLines(4)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(runtime, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        select(0)
        updatePin()
    }
    fun select(mode: Int) {
        if (selectedMode == mode) return
        selectedMode = mode
        tabs.forEachIndexed { index, tab ->
            tab.isSelected = index == mode
            tab.setTextColor(if (index == mode) accent else foregroundColor)
            tab.setTypeface(null, if (index == mode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tab.setBackgroundColor(if (index == mode) (accent and 0x00ffffff) or 0x18000000 else android.graphics.Color.TRANSPARENT)
        }
    }
    fun updateDetails(metadata: String, runtime: String, details: String = "") {
        runtimeDetails = details
        val lines = metadata.split('\n', limit = 3)
        metadataLines.forEachIndexed { index, line ->
            val next = lines.getOrElse(index) { "" }
            if (line.text.toString() != next) line.text = next
            line.contentDescription = next
        }
        if (this.runtime.text.toString() != runtime) this.runtime.text = runtime
    }
    private fun updatePin() {
        pin.setImageResource(if (pinned) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
        pin.isSelected = pinned
        pin.contentDescription = if (pinned) "图表已固定，点击随页面滚动" else "图表随页面滚动，点击固定"
    }
}

class IrHelpPreference(context: Context, key: String, title: String, order: Int,
    private val technical: String, private val range: String = "") : me.timschneeberger.rootlessjamesdsp.fusion.RuntimeStatusPreference(context) {
    init {
        this.key = key; this.title = title; this.order = order
        isIconSpaceReserved = false
        summary = null
        setOnPreferenceClickListener { showHelp(); true }
    }
    private fun showHelp() = ParameterHelp.show(context, title.toString(), technical = technical, range = range)
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(android.R.id.widget_frame)?.visibility = View.GONE
        val label = holder.findViewById(android.R.id.title) as? TextView ?: return
        ParameterHelp.attachTitle(label, title.toString(), technical, range)
    }
}

fun Context.irProfessionalHost(): ProfessionalEffectActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ProfessionalEffectActivity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? ProfessionalEffectActivity
}
