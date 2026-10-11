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
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.round

/** Only visible bands have views; slider binding never rewrites out-of-range or off-step gains. */
class PeqBandAdapter(
    private val onBandChanged: (PeqBand) -> Boolean,
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
            previous.gainRange != next.gainRange || previous.gainStep != next.gainStep ||
            previous.showQSlider != next.showQSlider || previous.showFrequencySlider != next.showFrequencySlider) {
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
            binding.peqBandHeader.setOnClickListener { onEdit(band.id) }
            binding.peqBandEdit.setOnClickListener { onEdit(band.id) }
            binding.peqGainControls.isVisible = band.type.usesGain
            binding.peqQControls.isVisible = state.showQSlider
            binding.peqFrequencyControls.isVisible = state.showFrequencySlider
            val slider = binding.peqBandSlider
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()
            slider.isSaveEnabled = false
            slider.stepSize = 0f
            slider.valueFrom = -state.gainRange.toFloat()
            slider.valueTo = state.gainRange.toFloat()
            slider.value = band.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
            slider.contentDescription = context.getString(R.string.peq_band_title, index + 1, number(band.frequency))
            slider.setLabelFormatter { "${number(it.toDouble(), 2)} dB" }

            fun currentBand() = state.bands.firstOrNull { it.id == band.id }
            fun renderLabels() {
                val current = currentBand() ?: return
                binding.peqBandTitle.text = context.getString(R.string.peq_band_title, index + 1, number(current.frequency))
                val details = if (current.qualityDisplay == PeqQualityDisplay.BW &&
                    current.type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS)) {
                    context.getString(R.string.peq_band_detail_bw, typeLabel(context, current.type),
                        number(PeqResponse.bandwidthOctaves(current.q, current.frequency), 6))
                } else context.getString(R.string.peq_band_detail, typeLabel(context, current.type), number(current.q, 3))
                binding.peqBandSummary.text = if (current.enabled) details else
                    context.getString(R.string.peq_band_disabled, details)
                binding.peqBandGain.text = if (current.type.usesGain)
                    String.format(Locale.ROOT, "%+.2f dB", current.gain)
                else context.getString(R.string.peq_no_gain)
                binding.peqBandMinus.isEnabled = current.gain > -state.gainRange
                binding.peqBandPlus.isEnabled = current.gain < state.gainRange
                binding.peqQValue.text = context.getString(R.string.peq_q_value, number(current.q, 3))
                binding.peqFrequencyValue.text = context.getString(R.string.peq_frequency_value, number(current.frequency, 3))
            }
            fun userGain(requested: Double) {
                val current = currentBand() ?: return
                if (!current.type.usesGain) return
                val next = (round(requested.coerceIn(-PeqModel.MAX_GAIN, PeqModel.MAX_GAIN) * 10000) / 10000)
                if (next != current.gain) onBandChanged(current.copy(gain = next))
                val updated = currentBand() ?: return
                slider.value = updated.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                renderLabels()
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
            fun bindLogSlider(control: Slider, minimum: Double, maximum: Double, currentValue: Double,
                              label: (Double) -> String, update: (PeqBand, Double) -> PeqBand) {
                control.clearOnChangeListeners()
                control.clearOnSliderTouchListeners()
                control.isSaveEnabled = false
                control.stepSize = 0f
                control.valueFrom = 0f
                control.valueTo = 1f
                control.value = logarithmicPosition(currentValue, minimum, maximum)
                control.setLabelFormatter { label(logarithmicValue(it, minimum, maximum)) }
                control.addOnChangeListener { _, raw, fromUser ->
                    if (fromUser) {
                        val current = currentBand() ?: return@addOnChangeListener
                        val next = (round(logarithmicValue(raw, minimum, maximum) * 1000) / 1000)
                            .coerceIn(minimum, maximum)
                        onBandChanged(update(current, next))
                        renderLabels()
                    }
                }
                control.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = onInteractionFinished()
                })
            }
            bindLogSlider(binding.peqQSlider, PeqModel.MIN_Q, PeqModel.MAX_Q, band.q,
                { "Q ${number(it, 3)}" }) { current, value -> current.copy(q = value) }
            bindLogSlider(binding.peqFrequencySlider, PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY, band.frequency,
                { "${number(it, 2)} Hz" }) { current, value -> current.copy(frequency = value) }
            renderLabels()
        }
    }

    companion object {
        fun logarithmicPosition(value: Double, minimum: Double, maximum: Double): Float =
            (ln(value.coerceIn(minimum, maximum) / minimum) / ln(maximum / minimum)).toFloat().coerceIn(0f, 1f)

        fun logarithmicValue(position: Float, minimum: Double, maximum: Double): Double =
            (minimum * exp(position.coerceIn(0f, 1f) * ln(maximum / minimum))).coerceIn(minimum, maximum)

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
