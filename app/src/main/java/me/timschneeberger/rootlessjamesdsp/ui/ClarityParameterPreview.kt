package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Three permanently mounted, read-only models of the saved clarity controls. */
class ClarityParameterPreview(context: Context) : InspectablePlotView(context) {
    private var model = ClarityPreviewModel.from(emptyMap<String, Any>(), emptyMap<String, Any>())
    private val muted get() = ColorUtils.setAlphaComponent(fg, 110)
    private val maxHz get() = min(20000.0, model.rate * .5 - 100.0).coerceAtLeast(1000.0)
    fun setModel(value: ClarityPreviewModel) {
        if (model == value) return
        cancelInspection(); model = value
        contentDescription = "清晰度参数模型，${model.modeLabel}，长按幅频图、压缩曲线或时间包络查看数值"
        invalidate()
    }

    override fun drawPlots(c: Canvas) {
        val half = width * .53f
        val magnitude = RectF(25 * d, 35 * d, half - 10 * d, 121 * d)
        val transfer = RectF(half + 24 * d, 35 * d, width - 3 * d, 121 * d)
        val envelope = RectF(25 * d, 178 * d, width - 3 * d, height - 18 * d)
        panes.add(magnitude); panes.add(transfer); panes.add(envelope)
        title(c, "幅频/抑制 · ${model.modeLabel}", 0f, 11 * d)
        title(c, "阈值/压缩比 · dB", half, 11 * d)
        val dy = if (model.dynamicEnabled) orange else muted
        val de = if (model.deessEnabled) teal else muted
        title(c, "紫：增强  橙：抑制  青：齿音", 0f, 24 * d)
        title(c, "${number(model.dynamicThreshold, 0)} / ${number(model.deessThreshold, 0)} dBFS", half, 24 * d)
        val freqTicks = listOf(100.0, 1000.0, maxHz).map { logUnit(it, 100.0, maxHz) to "${hz(it)}${if (it == maxHz) " Hz" else ""}" }
        axes(c, magnitude, freqTicks, -24.0, 24.0)
        axes(c, transfer, listOf(0f to "−60", .5f to "−30", 1f to "0 dBFS"), 0.0, 18.0)
        // Detector bands are separate from the maximum attenuation filters: their Q is not the shelf/PEQ Q.
        val detectorHalf = model.dynamicHz / (2 * model.dynamicQ)
        band(c, magnitude, logUnit((model.dynamicHz - detectorHalf).coerceAtLeast(100.0), 100.0, maxHz),
            logUnit(model.dynamicHz + detectorHalf, 100.0, maxHz), dy)
        band(c, magnitude, logUnit(model.deessLow, 100.0, maxHz), logUnit(model.deessHigh, 100.0, maxHz), de)
        if (model.mode == 0) {
            band(c, magnitude, logUnit(model.naturalLow, 100.0, maxHz), logUnit(model.naturalHigh, 100.0, maxHz), if (model.naturalEnabled) primary else muted)
        } else if (model.mode == 2) {
            vertical(c, magnitude, logUnit(model.lowHz, 100.0, maxHz), primary)
            vertical(c, magnitude, logUnit(model.highHz, 100.0, maxHz), primary)
        }
        curve(c, magnitude, primary, -24.0, 24.0, shade = true) { model.enhancementDb(logValue(it, 100.0, maxHz)) }
        curve(c, magnitude, dy, -24.0, 24.0, dashed = !model.dynamicEnabled, shade = true) { model.dynamicDb(logValue(it, 100.0, maxHz)) }
        curve(c, magnitude, de, -24.0, 24.0, dashed = !model.deessEnabled, shade = true) { model.deessDb(logValue(it, 100.0, maxHz)) }
        curve(c, magnitude, ColorUtils.setAlphaComponent(dy, 100), -24.0, 24.0, dashed = true) { model.dynamicDetectorDb(logValue(it, 100.0, maxHz)) }
        curve(c, magnitude, ColorUtils.setAlphaComponent(de, 100), -24.0, 24.0, dashed = true) { model.deessDetectorDb(logValue(it, 100.0, maxHz)) }
        vertical(c, transfer, ((model.dynamicThreshold + 60) / 60).toFloat(), dy)
        vertical(c, transfer, ((model.deessThreshold + 60) / 60).toFloat(), de)
        curve(c, transfer, dy, 0.0, 18.0, dashed = !model.dynamicEnabled) { model.dynamicReduction(-60.0 + it * 60) }
        curve(c, transfer, de, 0.0, 18.0, dashed = !model.deessEnabled) { model.deessReduction(-60.0 + it * 60) }
        title(c, "Q检测 ${number(model.dynamicQ, 2)} / ${number(model.deessQ, 2)} · 浅虚线为检测滤波", 0f, 146 * d)
        title(c, "启动/恢复：橙 ${number(model.dynamicAttackMs)}/${number(model.dynamicReleaseMs, 0)} ms · 青 ${number(model.deessAttackMs)}/${number(model.deessReleaseMs, 0)} ms", 0f, 159 * d)
        val samplesLow = (model.lowDelayMs * model.rate / 1000).toInt()
        val samplesMid = (model.midDelayMs * model.rate / 1000).toInt()
        title(c, "延时：低 $samplesLow 样本 · 中 $samplesMid 样本${if (model.mode != 2) "（XHiFi 未启用）" else ""}", 0f, 172 * d)
        axes(c, envelope, listOf(0.0, 20.0, 200.0, 5000.0).map { timeUnit(it) to "${number(it, 0)}${if (it == 5000.0) " ms" else ""}" }, 0.0, 100.0)
        band(c, envelope, 0f, timeUnit(model.lowDelayMs), if (model.mode == 2) primary else muted)
        band(c, envelope, 0f, timeUnit(model.midDelayMs), if (model.mode == 2) teal else muted)
        vertical(c, envelope, timeUnit(80.0), muted)
        curve(c, envelope, dy, 0.0, 100.0, dashed = !model.dynamicEnabled, shade = true) { model.envelope(timeValue(it), false) * 100 }
        curve(c, envelope, de, 0.0, 100.0, dashed = !model.deessEnabled) { model.envelope(timeValue(it), true) * 100 }
    }

    override fun drawInspection(c: Canvas, pane: Int, unit: Float) {
        val r = panes[pane]
        vertical(c, r, unit, primary)
        val lines = when (pane) {
            0 -> {
                val f = logValue(unit, 100.0, maxHz)
                dot(c, r, unit, model.enhancementDb(f), -24.0, 24.0, primary)
                listOf("${number(f, 1)} Hz · 增强 ${number(model.enhancementDb(f), 2)} dB",
                    "抑制轮廓 ${number(model.dynamicDb(f), 2)} · 齿音 ${number(model.deessDb(f), 2)} dB")
            }
            1 -> {
                val input = -60.0 + unit * 60
                dot(c, r, unit, model.dynamicReduction(input), 0.0, 18.0, orange)
                dot(c, r, unit, model.deessReduction(input), 0.0, 18.0, teal)
                listOf("输入 ${number(input, 2)} dBFS", "抑制 ${number(model.dynamicReduction(input), 2)} · 齿音 ${number(model.deessReduction(input), 2)} dB")
            }
            else -> {
                val ms = timeValue(unit)
                dot(c, r, unit, model.envelope(ms, false) * 100, 0.0, 100.0, orange)
                dot(c, r, unit, model.envelope(ms, true) * 100, 0.0, 100.0, teal)
                listOf("${number(ms, 2)} ms · 80 ms 单位阶跃模型", "高频 ${number(model.envelope(ms, false) * 100)}% · 齿音 ${number(model.envelope(ms, true) * 100)}%")
            }
        }
        tooltip(c, r, lines)
    }
}
