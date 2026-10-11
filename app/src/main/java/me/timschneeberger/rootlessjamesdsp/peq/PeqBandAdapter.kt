package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandRowBinding
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import java.util.Locale
import kotlin.math.round

/** Only visible bands have views; slider binding never rewrites out-of-range or off-step gains. */
class PeqBandAdapter(
    private val onGainChanged: (String, Double) -> Boolean,
    private val onEdit: (String) -> Unit,
    private val onInteractionFinished: () -> Unit = {},
) : RecyclerView.Adapter<PeqBandAdapter.Holder>() {
    private var state = PeqState(emptyList())
    private val itemIds = mutableMapOf<String, Long>()
    private var nextItemId = 0L

    init { setHasStableIds(true) }

    @SuppressLint("NotifyDataSetChanged")
    fun submitState(next: PeqState, interactingBand: String? = null) {
        val previous = state
        state = next
        next.bands.forEach { itemIds.getOrPut(it.id) { nextItemId++ } }
        if (previous.bands.map { it.id } != next.bands.map { it.id } ||
            previous.gainRange != next.gainRange || previous.gainStep != next.gainStep) {
            notifyDataSetChanged()
        } else {
            next.bands.forEachIndexed { index, band ->
                if (band.id != interactingBand && band != previous.bands[index]) notifyItemChanged(index)
            }
        }
    }

    override fun getItemCount() = state.bands.size
    override fun getItemId(position: Int) = requireNotNull(itemIds[state.bands[position].id])
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(PeqBandRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(state.bands[position], position)

    inner class Holder(private val binding: PeqBandRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(band: PeqBand, index: Int) {
            val context = binding.root.context
            binding.root.isSaveFromParentEnabled = false
            binding.peqBandTitle.text = context.getString(R.string.peq_band_title, index + 1, number(band.frequency))
            val details = context.getString(R.string.peq_band_detail, typeLabel(context, band.type), number(band.q, 3))
            binding.peqBandSummary.text = if (band.enabled) details else
                context.getString(R.string.peq_band_disabled, details)
            binding.peqBandHeader.setOnClickListener { onEdit(band.id) }
            binding.peqBandEdit.setOnClickListener { onEdit(band.id) }
            binding.peqGainControls.isVisible = band.type.usesGain
            val slider = binding.peqBandSlider
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()
            slider.isSaveEnabled = false
            slider.stepSize = 0f
            slider.valueFrom = -state.gainRange.toFloat()
            slider.valueTo = state.gainRange.toFloat()
            slider.value = band.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
            slider.contentDescription = binding.peqBandTitle.text
            slider.setLabelFormatter { "${number(it.toDouble(), 2)} dB" }

            fun currentBand() = state.bands.firstOrNull { it.id == band.id }
            fun renderGain() {
                val current = currentBand() ?: return
                binding.peqBandGain.text = if (current.type.usesGain)
                    String.format(Locale.ROOT, "%+.2f dB", current.gain)
                else context.getString(R.string.peq_no_gain)
                binding.peqBandMinus.isEnabled = current.gain > -state.gainRange
                binding.peqBandPlus.isEnabled = current.gain < state.gainRange
            }
            fun userGain(requested: Double) {
                val current = currentBand() ?: return
                if (!current.type.usesGain) return
                val next = (round(requested.coerceIn(-PeqModel.MAX_GAIN, PeqModel.MAX_GAIN) * 10000) / 10000)
                if (next != current.gain) onGainChanged(current.id, next)
                val updated = currentBand() ?: return
                slider.value = updated.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                renderGain()
            }
            slider.addOnChangeListener { _, changed, fromUser ->
                if (fromUser) userGain(round(changed / state.gainStep) * state.gainStep)
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) = onInteractionFinished()
            })
            binding.peqBandMinus.setOnClickListener { currentBand()?.let {
                userGain((it.gain - state.gainStep).coerceAtLeast(-state.gainRange))
                onInteractionFinished()
            } }
            binding.peqBandPlus.setOnClickListener { currentBand()?.let {
                userGain((it.gain + state.gainStep).coerceAtMost(state.gainRange))
                onInteractionFinished()
            } }
            SliderDoubleTapReset.install(slider) { userGain(0.0); onInteractionFinished() }
            renderGain()
        }
    }

    companion object {
        fun number(value: Double, decimals: Int = 2): String =
            String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

        fun typeLabel(context: Context, type: PeqFilterType): String = context.getString(when (type) {
            PeqFilterType.PEAK -> R.string.peq_filter_peak
            PeqFilterType.LOW_SHELF -> R.string.peq_filter_low_shelf
            PeqFilterType.HIGH_SHELF -> R.string.peq_filter_high_shelf
            PeqFilterType.LOW_PASS -> R.string.peq_filter_low_pass
            PeqFilterType.HIGH_PASS -> R.string.peq_filter_high_pass
            PeqFilterType.NOTCH -> R.string.peq_filter_notch
            PeqFilterType.BAND_PASS -> R.string.peq_filter_band_pass
        })
    }
}
