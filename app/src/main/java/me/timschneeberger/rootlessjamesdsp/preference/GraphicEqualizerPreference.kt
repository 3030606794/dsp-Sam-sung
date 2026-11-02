package me.timschneeberger.rootlessjamesdsp.preference

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceGraphicEqualizerBinding
import me.timschneeberger.rootlessjamesdsp.eq.EqualizerHomeSpectrumView
import me.timschneeberger.rootlessjamesdsp.peq.PeqRepository
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

class GraphicEqualizerPreference : Preference {

    private var binding: PreferenceGraphicEqualizerBinding? = null
    private var homeSpectrum: EqualizerHomeSpectrumView? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFromPreferences()
        }
    }

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
    }

    override fun onAttached() {
        context.registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_GRAPHIC_EQ_CHANGED))
        super.onAttached()
    }

    override fun onDetached() {
        homeSpectrum?.release()
        context.unregisterLocalReceiver(broadcastReceiver)
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        binding = PreferenceGraphicEqualizerBinding.bind(holder.itemView)
        val host = binding!!.eqHomeSpectrumHost
        val spectrum = homeSpectrum ?: EqualizerHomeSpectrumView(context).also { homeSpectrum = it }
        spectrum.configure(peq = true, hostState = {
            val group = parent as? SwitchPreferenceGroup
            (group?.isExpanded == true) to (group?.isChecked == true)
        })
        if (spectrum.parent !== host) {
            (spectrum.parent as? ViewGroup)?.removeView(spectrum)
            host.removeAllViews()
            host.addView(spectrum, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        updateFromPreferences()
    }

    fun updateFromPreferences() {
        val state = PeqRepository(context).load()
        binding?.layoutPeq?.setState(state)
        binding?.nodeCount?.text = context.getString(R.string.fusion_peq_band_count, state.bands.size)
    }
}
