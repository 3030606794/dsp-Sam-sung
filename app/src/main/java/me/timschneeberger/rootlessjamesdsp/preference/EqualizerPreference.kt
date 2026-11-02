package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.preference.DialogPreference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceGraphicEqualizerBinding
import me.timschneeberger.rootlessjamesdsp.eq.EqualizerHomeSpectrumView
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.EqualizerSurface
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import android.view.ViewGroup

class EqualizerPreference : DialogPreference, SharedPreferences.OnSharedPreferenceChangeListener {

    private var equalizerView: PeqResponseView? = null
    private var homeSpectrum: EqualizerHomeSpectrumView? = null
    var initialValue: String = ""

    var entries = arrayOf<CharSequence>()
    var entryValues = arrayOf<CharSequence>()

    private val eqPrefs = PreferenceCache.getPreferences(context, Constants.PREF_EQ);

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?
    ) : this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(
        context: Context
    ) : this(context, null)

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_graphic_equalizer
        dialogLayoutResource = R.layout.preference_equalizer_dialog

        this.positiveButtonText = context.getString(android.R.string.ok)
        this.negativeButtonText = context.getString(android.R.string.cancel)

        val a = context.obtainStyledAttributes(
            attrs, R.styleable.EqualizerPreference, defStyleAttr, defStyleRes
        )

        entries = a.getTextArray(R.styleable.EqualizerPreference_android_entries)
        entryValues = a.getTextArray(R.styleable.EqualizerPreference_android_entryValues)
        a.recycle()
    }

    override fun onAttached() {
        super.onAttached()
        eqPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDetached() {
        homeSpectrum?.release()
        eqPrefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetached()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        initialValue = getPersistedString(defaultValue as? String ?: "")
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index).toString()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val binding = PreferenceGraphicEqualizerBinding.bind(holder.itemView)
        val host = binding.eqHomeSpectrumHost
        val spectrum = homeSpectrum ?: EqualizerHomeSpectrumView(context).also { homeSpectrum = it }
        spectrum.configure(peq = false, hostState = {
            val group = parent as? SwitchPreferenceGroup
            (group?.isExpanded == true) to (group?.isChecked == true)
        })
        if (spectrum.parent !== host) {
            (spectrum.parent as? ViewGroup)?.removeView(spectrum)
            host.removeAllViews()
            host.addView(spectrum, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        binding.nodeCount.text = "15 个频段 · 点击调节"
        binding.nodeCount.isSingleLine = true
        // The PEQ shares this layout but is intentionally left unchanged.
        (binding.nodeCount.parent as? android.view.View)?.let { row ->
            (row.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = (12 * context.resources.displayMetrics.density).toInt()
                bottomMargin = topMargin
                row.layoutParams = this
            }
        }
        ParameterHelp.attachTitle(binding.nodeCount, "曲线均衡器",
            "15 个频段分别调节增益，滤波器类型和插值器沿用当前设置；点击曲线进入完整调节页。",
            "增益范围 −12 至 +12 dB；默认各频段 0 dB。右侧快捷预设点击即可应用。")
        binding.eqQuickPresets.activate()
        equalizerView = binding.layoutPeq
        updateFromPreferences()
    }

    fun updateFromPreferences() {
        initialValue = getPersistedString(initialValue)
        equalizerView?.let { EqPreview.render(context, it, initialValue) }
    }

    fun updateFilterType() = updateFromPreferences()

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if(key == this.key || key == context.getString(R.string.key_eq_filter_type) || key == context.getString(R.string.key_eq_interpolation)) {
            updateFilterType()
        }
    }
}
