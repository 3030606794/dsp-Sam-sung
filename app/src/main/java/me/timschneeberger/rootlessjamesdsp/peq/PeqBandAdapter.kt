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
    private val onBandSelected: (String) -> Unit = {},
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
            previous.qStep != next.qStep || previous.frequencyStep != next.frequencyStep ||
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
            binding.peqBandHeader.setOnClickListener { onBandSelected(band.id); onEdit(band.id) }
            binding.peqBandEdit.setOnClickListener { onBandSelected(band.id); onEdit(band.id) }
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
                val bandwidth = usesBandwidth(current)
                val details = if (bandwidth) {
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
                val quality = displayedQuality(current)
                val bounds = qualityBounds(current)
                binding.peqQValue.text = context.getString(if (bandwidth) R.string.peq_bw_value else R.string.peq_q_value,
                    number(quality, if (bandwidth) 6 else 3))
                binding.peqQSlider.contentDescription = context.getString(if (bandwidth) R.string.peq_bandwidth else R.string.peq_quality)
                binding.peqQMinus.contentDescription = context.getString(if (bandwidth) R.string.peq_bw_decrease else R.string.peq_quality_decrease)
                binding.peqQPlus.contentDescription = context.getString(if (bandwidth) R.string.peq_bw_increase else R.string.peq_quality_increase)
                binding.peqQMinus.isEnabled = quality > bounds.start
                binding.peqQPlus.isEnabled = quality < bounds.endInclusive
                binding.peqFrequencyValue.text = context.getString(R.string.peq_frequency_value, number(current.frequency, 3))
                binding.peqFrequencyMinus.isEnabled = current.frequency > PeqModel.MIN_FREQUENCY
                binding.peqFrequencyPlus.isEnabled = current.frequency < PeqModel.MAX_FREQUENCY
            }
            fun renderSliders() {
                val current = currentBand() ?: return
                slider.value = current.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                val bounds = qualityBounds(current)
                binding.peqQSlider.value = logarithmicPosition(displayedQuality(current), bounds.start, bounds.endInclusive)
                binding.peqFrequencySlider.value = logarithmicPosition(current.frequency,
                    PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY)
            }
            fun userGain(requested: Double) {
                val current = currentBand() ?: return
                if (!current.type.usesGain) return
                onBandSelected(current.id)
                val next = requested.coerceIn(-PeqModel.MAX_GAIN, PeqModel.MAX_GAIN)
                if (next != current.gain) onBandChanged(current.copy(gain = next))
                val updated = currentBand() ?: return
                slider.value = updated.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                renderLabels()
            }
            slider.addOnChangeListener { _, changed, fromUser ->
                if (fromUser) userGain(steppedGain(changed, state.gainRange, state.gainStep))
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = onBandSelected(band.id)
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
            fun changeBand(next: PeqBand) {
                onBandSelected(next.id)
                if (next != currentBand()) onBandChanged(next)
                // The active row is deliberately not rebound during a gesture. Update both
                // positions here because moving frequency also changes BW's digital bounds.
                renderSliders()
                renderLabels()
            }
            fun userQuality(requested: Double) {
                val current = currentBand() ?: return
                val bounds = qualityBounds(current)
                val value = requested.coerceIn(bounds.start, bounds.endInclusive)
                val q = if (usesBandwidth(current)) PeqResponse.qFromBandwidth(value, current.frequency) else value
                changeBand(current.copy(q = q.coerceIn(PeqModel.MIN_Q, PeqModel.MAX_Q)))
            }
            fun userFrequency(requested: Double) {
                currentBand()?.let { changeBand(withFrequency(it, requested)) }
            }
            fun bindLogSlider(control: Slider, bounds: (PeqBand) -> ClosedFloatingPointRange<Double>,
                              step: () -> Double, origin: Double, label: (PeqBand, Double) -> String,
                              update: (Double) -> Unit) {
                control.clearOnChangeListeners()
                control.clearOnSliderTouchListeners()
                control.isSaveEnabled = false
                control.stepSize = 0f
                control.valueFrom = 0f
                control.valueTo = 1f
                control.setLabelFormatter { raw ->
                    val current = currentBand() ?: band
                    val range = bounds(current)
                    label(current, logarithmicValue(raw, range.start, range.endInclusive))
                }
                control.addOnChangeListener { _, raw, fromUser ->
                    if (fromUser) {
                        val current = currentBand() ?: return@addOnChangeListener
                        val range = bounds(current)
                        update(steppedLogarithmicValue(raw, range.start, range.endInclusive, step(), origin))
                    }
                }
                control.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = onBandSelected(band.id)
                    override fun onStopTrackingTouch(slider: Slider) = onInteractionFinished()
                })
            }
            bindLogSlider(binding.peqQSlider, ::qualityBounds, { state.qStep }, 0.0,
                { current, value -> "${if (usesBandwidth(current)) "BW" else "Q"} ${number(value, 6)}" }, ::userQuality)
            bindLogSlider(binding.peqFrequencySlider, { PeqModel.MIN_FREQUENCY..PeqModel.MAX_FREQUENCY },
                { state.frequencyStep }, PeqModel.MIN_FREQUENCY, { _, value -> "${number(value, 2)} Hz" }, ::userFrequency)
            binding.peqQMinus.setOnClickListener { currentBand()?.let {
                userQuality(displayedQuality(it) - state.qStep)
                onInteractionFinished()
            } }
            binding.peqQPlus.setOnClickListener { currentBand()?.let {
                userQuality(displayedQuality(it) + state.qStep)
                onInteractionFinished()
            } }
            binding.peqFrequencyMinus.setOnClickListener { currentBand()?.let {
                userFrequency(it.frequency - state.frequencyStep)
                onInteractionFinished()
            } }
            binding.peqFrequencyPlus.setOnClickListener { currentBand()?.let {
                userFrequency(it.frequency + state.frequencyStep)
                onInteractionFinished()
            } }
            renderSliders()
            renderLabels()
        }
    }

    companion object {
        fun usesBandwidth(band: PeqBand): Boolean = band.qualityDisplay == PeqQualityDisplay.BW &&
            band.type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS)

        fun displayedQuality(band: PeqBand): Double = if (usesBandwidth(band))
            PeqResponse.bandwidthOctaves(band.q, band.frequency) else band.q

        fun qualityBounds(band: PeqBand): ClosedFloatingPointRange<Double> = if (usesBandwidth(band))
            PeqResponse.bandwidthOctaves(PeqModel.MAX_Q, band.frequency)..
                PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, band.frequency)
        else PeqModel.MIN_Q..PeqModel.MAX_Q

        /** Keep the edited unit stable when moving its center frequency. */
        fun withFrequency(band: PeqBand, requested: Double): PeqBand {
            val frequency = requested.coerceIn(PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY)
            if (frequency == band.frequency) return band
            val q = if (usesBandwidth(band))
                PeqResponse.qFromBandwidth(displayedQuality(band), frequency).coerceIn(PeqModel.MIN_Q, PeqModel.MAX_Q)
            else band.q
            return band.copy(frequency = frequency, q = q)
        }

        /** Snap the displayed parameter, while always allowing both real endpoints. */
        fun steppedGain(value: Float, range: Double, step: Double): Double = when {
            value <= -range.toFloat() -> -range
            value >= range.toFloat() -> range
            else -> (round(value / step) * step).coerceIn(-range, range)
        }

        /** Snap the displayed parameter, while always allowing both real endpoints. */
        fun steppedLogarithmicValue(position: Float, minimum: Double, maximum: Double,
                                    step: Double, origin: Double = 0.0): Double {
            if (position <= 0f) return minimum
            if (position >= 1f) return maximum
            val value = logarithmicValue(position, minimum, maximum)
            return (origin + round((value - origin) / step) * step).coerceIn(minimum, maximum)
        }

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
