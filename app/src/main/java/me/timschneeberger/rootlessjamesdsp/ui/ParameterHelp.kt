package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import java.util.WeakHashMap
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassThemeUi

/** Descriptions only. This class never changes a preference or an audio parameter. */
object ParameterHelp {
    private val moduleDescriptions = WeakHashMap<PreferenceGroup, Map<String, String>>()
    private val hiddenRows = WeakHashMap<View, Int>()

    /** Keep summary-only technical notes in help instead of creating an empty help row. */
    fun preparePreferences(group: PreferenceGroup) {
        if (isPeqContext(group.context)) return
        val children = (0 until group.preferenceCount).map(group::getPreference)
        val common = children.filter { isStaticNote(it) &&
            (it.title.isNullOrBlank() || it.key.orEmpty().endsWith("_help") || it.key.orEmpty().endsWith("_notice")) }
            .mapNotNull { it.summary?.toString()?.takeIf(String::isNotBlank) }.distinct().joinToString("\n\n")
        val descriptions = linkedMapOf<String, String>()
        if (common.isNotBlank()) {
            group.title?.toString()?.takeIf(String::isNotBlank)?.let { descriptions[it] = common }
        }
        children.filter { isStaticNote(it) && !it.title.isNullOrBlank() }.forEach { child ->
            val name = child.title.toString()
            descriptions[name] = listOf(child.summary.toString(), descriptions[name].orEmpty())
                .filter(String::isNotBlank).distinct().joinToString("\n\n")
        }
        moduleDescriptions[group] = descriptions
        children.filterIsInstance<PreferenceGroup>().forEach(::preparePreferences)
    }

    fun show(context: Context, title: String, technical: String = "", range: String = "") {
        // Mode-dependent notes are refreshed when opening help, never on every slider rebind.
        val groups = moduleDescriptions.keys.toList().filter { host(it.context) === host(context) }
        groups.filter { it.parent !in groups }.forEach(::preparePreferences)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 16))
        }
        fun section(heading: String, text: String) {
            body.addView(TextView(context).apply {
                this.text = heading
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(context, 12), 0, dp(context, 6))
            })
            body.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextIsSelectable(true)
            })
        }
        val notes = moduleDescriptions.entries.filter { host(it.key.context) === host(context) }
            .mapNotNull { it.value[title] }.distinct().singleOrNull()?.takeUnless { technical.contains(it) }.orEmpty()
        section("专业说明", listOf(technical.ifBlank { definition(title) }, range, notes)
            .filter { it.isNotBlank() }.joinToString("\n"))
        section("翻译说明", plain(title))
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("知道了", null).show()
        GlassThemeUi.decorateDialog(dialog.window)
    }

    /** Keep the original TextView and click handler; only insert the adjacent help control. */
    fun attachTitle(label: TextView, title: String = label.text.toString(),
                    technical: String = "", range: String = "", professional: Boolean = false) {
        val parent = label.parent as? ViewGroup ?: return
        if (title.isBlank()) { hideAttachedHelp(label); return }
        label.includeFontPadding = false
        // Assign once before deriving the marker, including labels without a resource ID.
        if (label.id == View.NO_ID) label.id = View.generateViewId()
        val marker = "parameter_help_${label.id}"
        val help = parent.findViewWithTag<TextView>(marker) ?: TextView(label.context).apply {
            tag = marker
            text = "?"
            textSize = 18f
            includeFontPadding = false
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val value = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
            if (value.resourceId != 0) setBackgroundResource(value.resourceId)
            val color = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, color, true)
            setTextColor(color.data)
        }.also { control ->
            when (parent) {
                is LinearLayout -> {
                    val index = parent.indexOfChild(label)
                    if (parent.orientation == LinearLayout.HORIZONTAL) {
                        parent.gravity = (parent.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
                        parent.isBaselineAligned = false
                        (label.layoutParams as? LinearLayout.LayoutParams)?.let {
                            it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                            it.weight = 0f
                            label.layoutParams = it
                        }
                        parent.addView(control, index + 1, LinearLayout.LayoutParams(dp(label.context, 24), dp(label.context, 30)))
                        parent.addOnLayoutChangeListener { row, _, _, _, _, _, _, _, _ ->
                            val occupied = (0 until parent.childCount).map { parent.getChildAt(it) }
                                .filter { it !== label && it.visibility != View.GONE }.sumOf {
                                    val params = it.layoutParams as? ViewGroup.MarginLayoutParams
                                    it.width + (params?.leftMargin ?: 0) + (params?.rightMargin ?: 0)
                                }
                            val available = (row.width - row.paddingLeft - row.paddingRight - occupied).coerceAtLeast(1)
                            if (label.maxWidth != available) label.maxWidth = available
                        }
                    } else {
                        val original = label.layoutParams
                        parent.removeView(label)
                        val row = LinearLayout(label.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        row.addView(label, LinearLayout.LayoutParams(-2, -2))
                        row.addView(control, LinearLayout.LayoutParams(dp(label.context, 24), dp(label.context, 30)))
                        parent.addView(row, index, original)
                        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            val available = (row.width - row.paddingLeft - row.paddingRight - control.width).coerceAtLeast(1)
                            if (label.maxWidth != available) label.maxWidth = available
                        }
                    }
                }
                is RelativeLayout -> {
                    (label.layoutParams as? RelativeLayout.LayoutParams)?.let {
                        it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        it.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                        it.removeRule(RelativeLayout.ALIGN_PARENT_END)
                        label.layoutParams = it
                    }
                    parent.addView(control, RelativeLayout.LayoutParams(dp(label.context, 24), dp(label.context, 30)).apply {
                        addRule(RelativeLayout.END_OF, label.id)
                        addRule(RelativeLayout.ALIGN_BASELINE, label.id)
                    })
                    parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val parameters = label.layoutParams as? RelativeLayout.LayoutParams
                        val endAnchor = parameters?.getRule(RelativeLayout.LEFT_OF)?.takeIf { it != 0 }
                            ?: parameters?.getRule(RelativeLayout.START_OF)?.takeIf { it != 0 }
                        val end = endAnchor?.let { parent.findViewById<View>(it)?.left }
                            ?: (parent.width - parent.paddingRight)
                        val professionalWidth = parent.findViewWithTag<View>("parameter_professional_${label.id}")
                            ?.takeIf { it.visibility != View.GONE }?.width ?: 0
                        val helpWidth = if (control.visibility == View.GONE) 0 else control.width
                        val available = (end - label.left - helpWidth - professionalWidth - (parameters?.rightMargin ?: 0)).coerceAtLeast(1)
                        if (label.maxWidth != available) label.maxWidth = available
                    }
                }
                else -> return
            }
        }
        help.text = "?"
        help.visibility = View.VISIBLE
        help.includeFontPadding = false
        help.contentDescription = "$title：参数说明"
        help.setOnClickListener { show(label.context, title, technical, range) }
        val professionalMarker = "parameter_professional_${label.id}"
        val previousProfessional = (label.parent as? ViewGroup)?.findViewWithTag<TextView>(professionalMarker)
        var professionalControl: View? = null
        if (!professional) previousProfessional?.visibility = View.GONE
        else if (isProfessionalContext(label.context)) {
            val row = label.parent as? ViewGroup ?: return
            val extra = previousProfessional ?: TextView(label.context).apply {
                tag = professionalMarker; text = "!"; textSize = 18f; includeFontPadding = false
                gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(help.currentTextColor)
                isClickable = true; isFocusable = true
            }.also { control ->
                when (row) {
                    is LinearLayout -> row.addView(control, row.indexOfChild(help) + 1,
                        LinearLayout.LayoutParams(dp(label.context, 24), dp(label.context, 30)))
                    is RelativeLayout -> {
                        if (help.id == View.NO_ID) help.id = View.generateViewId()
                        row.addView(control, RelativeLayout.LayoutParams(dp(label.context, 24), dp(label.context, 30)).apply {
                            addRule(RelativeLayout.END_OF, help.id); addRule(RelativeLayout.ALIGN_BASELINE, label.id)
                        })
                    }
                    else -> return
                }
            }
            extra.visibility = View.VISIBLE
            extra.contentDescription = "$title：专业设置说明"
            extra.setOnClickListener { show(label.context, title, technical, range) }
            professionalControl = extra
        }
        HelpPreferences.bindTitleHelp(label, help, professionalControl) {
            show(label.context, title, technical, range)
        }
    }
    fun bindPreference(preference: Preference, holder: PreferenceViewHolder) {
        restoreRow(holder.itemView)
        // These DSP rows own their compact captions and a single explicitly folded help section.
        if (preference.layoutResource == R.layout.preference_liveprog_compact_row ||
            preference.layoutResource == R.layout.preference_liveprog_tools ||
            preference.layoutResource == R.layout.preference_liveprog_choices) return
        if (preference.key.orEmpty().startsWith("help_")) {
            (holder.findViewById(android.R.id.title) as? TextView)?.let(::hideAttachedHelp)
            return
        }
        // The parametric equalizer is explicitly excluded from this UI update.
        if (isPeqContext(preference.context) || preference.key.orEmpty().contains("peq", true) ||
            preference is SwitchPreferenceGroup || preference.javaClass.simpleName == "IrHelpPreference") return
        if (holder.findViewById(R.id.slider_help) != null) return // Numeric controls bind their own range and help.
        val title = holder.findViewById(android.R.id.title) as? TextView ?: return
        val summary = preference.summary?.toString().orEmpty()
        val name = preference.title?.toString().orEmpty()
        hideAttachedHelp(title)
        // Summary-only notes are not controls. Do not turn their invisible title into a lone icon.
        if (name.isBlank()) {
            if (isStaticNote(preference)) hideRow(holder.itemView)
            return
        }
        if (isStatus(preference)) return // Keep actual measurements and their original value/status text.
        val description = preference is TwoStatePreference || isStaticNote(preference) ||
            (preference !is ListPreference && preference !is MultiSelectListPreference && summary.length > 90)
        if (description) holder.findViewById(android.R.id.summary)?.visibility = View.GONE
        val range = when (preference) {
            is ListPreference -> "可选：${preference.entries?.joinToString("、").orEmpty()}" +
                (preference.entry?.let { "；当前：$it" } ?: "")
            is MultiSelectListPreference -> "可选：${preference.entries?.joinToString("、").orEmpty()}；可以选择多个项目"
            is SeekBarPreference -> "范围：${preference.min}～${preference.max}；步长：${preference.seekBarIncrement}"
            is TwoStatePreference -> "可选：关闭、开启；当前：${if (preference.isChecked) "开启" else "关闭"}"
            else -> if (summary.isNotBlank() && summary.length <= 32 && summary.any(Char::isDigit))
                "当前值：$summary。可用范围、单位和默认值以当前控件及数值输入窗口为准。"
            else "可用选项、单位和默认值以当前控件或输入窗口为准；不适用的选项不会启用。"
        }
        val technical = if (summary.length <= 32 && summary.any(Char::isDigit)) "" else summary
        attachTitle(title, name, technical, range, professional = isProfessionalHeading(preference))
    }

    private fun isStatus(preference: Preference): Boolean {
        if (preference is TwoStatePreference || preference is ListPreference || preference is MultiSelectListPreference ||
            preference is SeekBarPreference || preference.javaClass.simpleName.endsWith("SliderPreference") ||
            preference.javaClass.simpleName.endsWith("NumericPreference") || preference.javaClass.simpleName.endsWith("RangePreference")) return false
        val key = preference.key.orEmpty()
        return Regex("status|runtime|metadata|meter|warning|diagnostic|telemetry|actual|target|driver|capabilit|support|source_file|loaded", RegexOption.IGNORE_CASE)
            .containsMatchIn(key) || matches(preference.title?.toString().orEmpty(), "实时", "当前配置的实际", "装载状态", "监测", "监控")
    }

    private fun isStaticNote(preference: Preference): Boolean {
        val key = preference.key.orEmpty()
        val explicitNote = key.endsWith("_help") || key.endsWith("_notice")
        return !isStatus(preference) && !preference.isSelectable && !preference.summary.isNullOrBlank() &&
            (preference.javaClass == Preference::class.java || explicitNote)
    }

    private fun isProfessionalHeading(preference: Preference): Boolean {
        if (!isStaticNote(preference) || preference.title.isNullOrBlank()) return false
        val parent = preference.parent ?: return false
        val children = (0 until parent.preferenceCount).map(parent::getPreference)
        val index = children.indexOf(preference)
        // A visible professional marker belongs to a section with actual following controls.
        return children.drop(index + 1).takeWhile { !isStaticNote(it) || it.title.isNullOrBlank() }
            .any { it is TwoStatePreference || it is ListPreference || it is SeekBarPreference ||
                it.javaClass.simpleName.endsWith("SliderPreference") || it.javaClass.simpleName == "BassNumericPreference" ||
                it.javaClass.simpleName == "HighFrequencyOversamplingPreference" }
    }

    private fun hideAttachedHelp(label: TextView) {
        HelpPreferences.clearTitleHelp(label)
        val parent = label.parent as? ViewGroup ?: return
        parent.findViewWithTag<View>("parameter_help_${label.id}")?.visibility = View.GONE
        parent.findViewWithTag<View>("parameter_professional_${label.id}")?.visibility = View.GONE
        label.maxWidth = Int.MAX_VALUE
    }

    private fun hideRow(view: View) {
        val params = view.layoutParams
        if (!hiddenRows.containsKey(view)) hiddenRows[view] = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        view.visibility = View.GONE
        if (params != null && params.height != 0) { params.height = 0; view.layoutParams = params }
    }

    private fun restoreRow(view: View) {
        val height = hiddenRows.remove(view) ?: return
        view.visibility = View.VISIBLE
        view.layoutParams?.let { it.height = height; view.layoutParams = it }
    }

    fun isProfessionalContext(context: Context): Boolean = host(context) is ProfessionalEffectActivity

    private fun host(context: Context): Context {
        var current = context
        repeat(16) {
            if (current is android.app.Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return current
            if (next === current) return current
            current = next
        }
        return current
    }

    private fun isPeqContext(context: Context): Boolean {
        var current = context
        repeat(16) {
            if (current is GraphicEqualizerActivity) return true
            val next = (current as? ContextWrapper)?.baseContext ?: return false
            if (next === current) return false
            current = next
        }
        return false
    }

    private fun definition(title: String): String = when {
        matches(title, "频率", "frequency", "Hz") -> "设置当前处理使用的频率位置或频带边界；单位为 Hz。具体量程以数值输入窗口为准，处理类型由本模块原有模式决定。"
        matches(title, "Q 值", "Q值", "品质", "带宽", "BW") -> "控制滤波器的品质因数或带宽。Q 与带宽含义不同，数值单位与范围沿用当前参数定义。"
        matches(title, "增益", "电平", "音量", "衰减", "gain") -> "控制本处理环节的电平或倍率。dB 表示相对电平变化；非 dB 系数沿用原参数定义。"
        matches(title, "延迟", "延时", "预延", "时间", "释放", "攻击", "保持") -> "控制本模块的时间参数。单位以数值框为准；实际响应还受当前模式和音频采样率约束。"
        matches(title, "混合", "干湿", "湿声", "比例") -> "控制当前信号支路的混合或作用比例。具体作用范围、干声和湿声关系沿用该模块定义。"
        matches(title, "阈值", "门限") -> "设置动态处理的触发电平；低于或高于该电平时的行为由当前处理模式决定。"
        matches(title, "平衡", "声像") -> "控制左右声道的相对电平或声像位置，中间位置表示左右平衡。"
        matches(title, "宽度", "立体声", "侧声") -> "调节本模块的左右声道空间关系；实际作用与输入信号、频带和当前模式有关。"
        else -> "当前功能的选项或参数。选择、量程和生效条件沿用原有模块设置；禁用项保留已保存的数值。"
    }

    private fun plain(title: String): String = when {
        matches(title, "Q 值", "Q值", "品质") -> "它决定滤波作用有多集中。向左通常更宽、更平缓；向右更窄、更突出。太大可能让某个音特别尖或产生明显共振，建议小幅调整。"
        matches(title, "带宽", "BW") -> "它决定一段处理覆盖多宽。向左覆盖更窄，向右覆盖更宽；不是音量大小。调得太宽可能连旁边本来正常的声音一起改变。"
        matches(title, "频率", "frequency", "Hz") -> "它决定处理落在哪个音高。向左移向更低的声音，向右移向更高的声音；本身不等于增强更多。边界调得过头可能让想保留的声音也被削弱。"
        matches(title, "平衡", "声像") -> "它管声音偏左还是偏右。向左偏左，向右偏右，中间两边平衡。偏得太多，一侧会明显变小。"
        matches(title, "释放") -> "它管处理结束后多久恢复。向左恢复更快，向右恢复更慢；太快可能忽大忽小，太慢可能让下一段声音仍被压着。"
        matches(title, "攻击") -> "它管处理开始得有多快。向左反应快，向右反应慢；太快可能削弱鼓点冲劲，太慢可能放过突发的大声音。"
        matches(title, "延迟", "延时", "预延", "时间", "保持") -> "它管等待或持续多久。向左时间短，向右时间长。太长可能听到拖尾、回声或声画不同步；具体是哪一种，由当前标签和专业说明决定。"
        matches(title, "阈值", "门限") -> "它相当于处理的门槛。向左门槛更低，向右更高。压缩、扩展和噪声门的触发方向不同，请结合当前模式；设得太极端可能让正常声音也受到持续处理。"
        matches(title, "混合", "干湿", "湿声") -> "它管原声与处理后声音的比例。湿声或干湿混合向右通常处理声更多，向左更少；若标签写的是干声，方向以干声为准。太大可能掩盖原声细节，也可能出现声音相互抵消。"
        matches(title, "增益", "音量", "电平", "gain") -> "它管这一部分声音有多大。增益向左更小，向右更大；若写的是衰减量，方向相反。加得太多可能刺耳、失真或触发限幅。"
        matches(title, "宽度", "侧声") -> "它管声音铺得有多开。向左通常更集中，向右更宽。过宽可能让中间变空，或在单声道播放时丢失一部分声音。"
        matches(title, "模式", "类型", "插值", "预设", "文件", "路由", "过采样") -> "这些是不同处理方式或选项。左右切换代表选择不同方案，不一定是效果从弱到强。先用默认项，再对比试听；某些复杂方案会增加处理负担或延迟。"
        matches(title, "增强", "强度", "系数", "驱动", "饱和") -> "它管当前效果的作用量。通常向左更轻，向右更明显；具体含义以专业说明为准。拉得太大可能让声音发闷、发尖或失真，建议一点点加。"
        else -> "它只调整当前标签对应的功能。开关打开后才按模块规则生效；滑杆向左数值减小，向右数值增大，但不一定代表声音更好。先从默认值小幅调整，听到不自然时双击滑杆球恢复默认。"
    }

    private fun matches(title: String, vararg words: String) = words.any { title.contains(it, true) }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

