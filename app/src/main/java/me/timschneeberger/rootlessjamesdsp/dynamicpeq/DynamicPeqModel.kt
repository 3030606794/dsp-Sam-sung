package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import java.util.UUID

/** This bank is intentionally independent of the static parametric equalizer. */
data class DynamicPeqBand(
    val id: String = UUID.randomUUID().toString(),
    val type: Int = 0,
    val enabled: Boolean = true,
    val frequency: Double = 1000.0,
    val gain: Double = 0.0,
    val q: Double = 1.0,
    val dynamicEnabled: Boolean = true,
    val threshold: Double = -24.0,
    val ratio: Double = 2.0,
    val range: Double = 6.0,
    val attack: Double = 10.0,
    val release: Double = 150.0,
    val knee: Double = 6.0
)

data class DynamicPeqState(
    val enabled: Boolean = false,
    val bands: List<DynamicPeqBand> = listOf(100.0, 1000.0, 6000.0).mapIndexed { index, hz ->
        DynamicPeqBand(id = "dynamic-default-${index + 1}", frequency = hz)
    }
)

object DynamicPeqModel {
    const val MAX_BANDS = 16
    const val MIN_FREQUENCY = 20.0
    const val MAX_FREQUENCY = 20000.0
    const val MAX_GAIN = 24.0
    const val MIN_Q = 0.1
    const val MAX_Q = 30.0
    const val PEAK = 0
    const val LOW_SHELF = 1
    const val HIGH_SHELF = 2

    fun defaultState(): DynamicPeqState = DynamicPeqState()
    fun newBand(): DynamicPeqBand = DynamicPeqBand()

    fun validateBand(band: DynamicPeqBand): DynamicPeqBand {
        require(band.id.isNotBlank() && band.id.length <= 128 && band.id.none { it.isISOControl() }) { "频段标识无效" }
        require(band.type in PEAK..HIGH_SHELF) { "不支持的滤波类型" }
        checkRange(band.frequency, MIN_FREQUENCY, MAX_FREQUENCY, "频率", "Hz")
        checkRange(band.gain, -MAX_GAIN, MAX_GAIN, "增益", "dB")
        checkRange(band.q, MIN_Q, MAX_Q, "Q", "")
        checkRange(band.threshold, -90.0, 0.0, "阈值", "dBFS")
        checkRange(band.ratio, 1.0, 20.0, "压缩比", "")
        checkRange(band.range, 0.0, 24.0, "最大衰减", "dB")
        checkRange(band.attack, 0.1, 500.0, "启动时间", "ms")
        checkRange(band.release, 5.0, 3000.0, "释放时间", "ms")
        checkRange(band.knee, 0.0, 24.0, "软拐点", "dB")
        return band
    }

    fun validate(state: DynamicPeqState): DynamicPeqState {
        require(state.bands.size <= MAX_BANDS) { "最多支持 $MAX_BANDS 个动态频段" }
        state.bands.forEach(::validateBand)
        require(state.bands.map { it.id }.toSet().size == state.bands.size) { "频段标识不能重复" }
        return state
    }

    private fun checkRange(value: Double, min: Double, max: Double, label: String, unit: String) {
        require(value.isFinite() && value in min..max) { "$label 需要在 $min–$max $unit 范围内" }
    }
}
