package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import java.util.Locale
import kotlin.math.*

/** Electrical signal model and driver spectra, never a measurement of the phone's loudspeaker. */
internal object SpeakerOptimizationVisuals {
    private val kind get() = ExpansionEffectsKind.SPEAKER_OPTIMIZATION
    private val p = ExpansionEffectsParameters
    private const val REFERENCE_RATE = 48000.0
    private const val FFT_SIZE = 2048
    private const val BODY_PEAK = 3.88
    private const val MODEL_NOTE = "48 kHz 稳态参数参考，前提是模块开启且当前路由适用。不是 VIVO 扬声器的声学频响测量；实际参数变化还经过 20 ms 平滑。"

    internal data class Complex(val re: Double, val im: Double = 0.0) {
        operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
        operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(scale: Double) = Complex(re * scale, im * scale)
        operator fun div(other: Complex): Complex {
            val den = other.re * other.re + other.im * other.im
            return Complex((re * other.re + im * other.im) / den, (im * other.re - re * other.im) / den)
        }
        fun magnitude() = hypot(re, im)
    }
    internal data class Response(val highpass: Complex, val lowpass: Complex, val body: Complex,
        val wet: Complex, val total: Complex)

    private fun value(values: Map<String, *>, key: String) = p.raw(kind, values, key)
    private fun focus(key: String?) = p.metadata(kind).firstOrNull { it.control.key == key }?.logicalKey ?: "mix"
    private fun num(value: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f", value)
    private fun db(magnitude: Double) = (20 * log10(magnitude.coerceAtLeast(1e-6))).coerceAtLeast(-120.0)

    /** Unit-peak BP; the classic Q=3.88 amplitude is restored separately by BODY_PEAK. */
    private fun biquad(type: Int, frequency: Double, q: Double, at: Double, sampleRate: Double): Complex {
        val f = frequency.coerceIn(1.0, .48 * sampleRate)
        val omega = 2 * PI * f / sampleRate
        val co = cos(omega)
        val alpha = sin(omega) / (2 * q)
        val a0 = 1 + alpha
        val numerator = when (type) {
            0 -> doubleArrayOf((1 + co) / 2, -(1 + co), (1 + co) / 2) // HP
            1 -> doubleArrayOf((1 - co) / 2, 1 - co, (1 - co) / 2) // LP
            else -> doubleArrayOf(alpha, 0.0, -alpha) // constant peak BP
        }
        val phase = -2 * PI * at / sampleRate
        val z = Complex(cos(phase), sin(phase))
        val z2 = z * z
        val top = Complex(numerator[0]) + z * numerator[1] + z2 * numerator[2]
        val bottom = Complex(a0) + z * (-2 * co) + z2 * (1 - alpha)
        return top / bottom
    }

    internal fun response(values: Map<String, *>, frequency: Double, sampleRate: Double = REFERENCE_RATE): Response {
        fun v(key: String) = value(values, key)
        val hp = biquad(0, v("low_cut"), v("low_q"), frequency, sampleRate)
        val lp = biquad(1, v("high_cut"), v("high_q"), frequency, sampleRate)
        val front = hp * lp * v("base_level")
        val body = front * biquad(2, v("body_freq"), v("body_q"), frequency, sampleRate) * (BODY_PEAK * v("body_amount"))
        val wet = front + body
        val total = (Complex(1 - v("mix")) + wet * v("mix")) * 10.0.pow(v("output_gain") / 20)
        return Response(hp, lp, body, wet, total)
    }

    fun static(values: Map<String, *>, focusKey: String?): EffectVisualPair {
        val selected = focus(focusKey)
        val pair = if (selected == "scope") {
            EffectVisualPair(routeDiagram(values, false), routeDiagram(values, true))
        } else EffectVisualPair(professional(values, selected), illustration(values, selected))
        val inactive = values[kind.enableKey] != true
        return EffectVisualPair(pair.professional.copy(inactive = inactive), pair.illustration.copy(inactive = inactive))
    }

    private fun professional(values: Map<String, *>, selected: String): EffectVisual {
        fun v(key: String) = value(values, key)
        val highest = max(20000.0, v("high_cut")).coerceAtMost(REFERENCE_RATE * .48)
        val frequencies = List(129) { 20.0 * (highest / 20).pow(it / 128.0) }
        val responses = frequencies.map { response(values, it) }
        fun series(title: String, color: Int = 0, choose: (Response) -> Complex) =
            LiveprogCurve(title, responses.mapIndexed { i, r -> LiveprogPoint(frequencies[i], db(choose(r).magnitude())) }, color)
        val main = series("模块最终输出") { it.total }
        val curves: List<LiveprogCurve>
        val title: String
        val markHz: Double?
        when (selected) {
            "low_cut", "low_q" -> {
                title = "低频截止与低切 Q"
                curves = listOf(main, series("低切单级", 2) { it.highpass })
                markHz = v("low_cut")
            }
            "high_cut", "high_q" -> {
                title = "高频截止与高切 Q"
                curves = listOf(main, series("高切单级", 2) { it.lowpass })
                markHz = v("high_cut")
            }
            "body_freq", "body_q", "body_amount" -> {
                title = "补偿频带与叠加响应"
                curves = listOf(main, series("补偿支路（含前级）", 2) { it.body })
                markHz = v("body_freq")
            }
            "base_level" -> {
                title = "基础支路与完整输出"
                curves = listOf(main, series("基础支路", 2) { it.highpass * it.lowpass * v("base_level") })
                markHz = null
            }
            else -> {
                title = if (selected == "output_gain") "干湿重组后的输出增益" else "原声与优化支路重组"
                curves = listOf(main, series("优化湿支路", 2) { it.wet })
                markHz = null
            }
        }
        val points = curves.flatMap { it.points }
        val minimum = min(-36.0, floor((points.minOfOrNull { it.y } ?: -36.0) / 12) * 12).coerceAtLeast(-120.0)
        val maximum = max(12.0, ceil((points.maxOfOrNull { it.y } ?: 12.0) / 6) * 6)
        val caption = when {
            v("mix") == 0.0 -> "48 kHz · 原声支路，输出增益仍有效"
            v("base_level") == 0.0 && v("mix") == 1.0 -> "48 kHz · 基础电平为 0，输出静音"
            else -> "48 kHz · 稳态目标响应"
        }
        val notes = listOf(MODEL_NOTE,
            "优化湿支路=基础电平×低切×高切×(1+3.88×补偿强度×单位峰值带通)。补偿支路读取已经过低切、高切和基础电平的信号。",
            "最终输出=输出增益×[(1−混合)×原声+混合×优化湿支路]。按复数相位相加计算，不能直接相加 dB 或幅度。模块关闭或路由不适用时整体原声，不应用这里的输出增益。",
            "补偿 Q 改变带宽，补偿强度独立控制峰值；100% 强度对应经典峰值 3.88。零幅度以 −120 dB 图底表示。")
        val plot = LiveprogStaticPlot(title, caption, xLabel = "频率 Hz", yLabel = "幅度 dB",
            xMinimum = 20.0, xMaximum = highest, yMinimum = minimum, yMaximum = maximum, logarithmicX = true,
            curves = curves, marks = markHz?.let { listOf(LiveprogMark(it, db(response(values, it).total.magnitude()), "当前频率")) }.orEmpty(),
            notes = notes)
        return EffectVisual(title, caption, plot = plot, notes = notes)
    }

    private fun illustration(values: Map<String, *>, selected: String): EffectVisual {
        fun v(key: String) = value(values, key)
        val silent = v("mix") == 1.0 && v("base_level") == 0.0
        val tones = listOf(60.0, 420.0, 8000.0)
        val levels = tones.map { db(response(values, it).total.magnitude()) }
        val emphasized = when (selected) {
            "low_cut", "low_q" -> 0
            "high_cut", "high_q" -> 2
            else -> 1
        }
        val subtitle = when (selected) {
            "low_cut", "low_q" -> "低切 ${v("low_cut").toInt()} Hz · Q ${num(v("low_q"), 2)}"
            "high_cut", "high_q" -> "高切 ${v("high_cut").toInt()} Hz · Q ${num(v("high_q"), 2)}"
            "body_amount" -> if (v("body_amount") == 0.0) "补偿支路为 0 · 保留基础滤波"
                else "补偿支路强度 ${num(v("body_amount") * 100, 0)}%"
            "body_freq", "body_q" -> if (v("body_amount") == 0.0) "补偿支路为 0 · 保留基础滤波"
                else "补偿 ${v("body_freq").toInt()} Hz · Q ${num(v("body_q"), 2)}"
            "base_level" -> "基础电平 ${num(v("base_level") * 100, 0)}%"
            "output_gain" -> "输出增益 ${num(v("output_gain"))} dB"
            else -> "原声 ${num((1 - v("mix")) * 100, 0)}% · 优化 ${num(v("mix") * 100, 0)}%"
        }
        val elements = buildList<FigureElement> {
            tones.forEachIndexed { index, hz ->
                val x = .2 + index * .3
                val height = ((levels[index] + 36) / 60).coerceIn(0.0, 1.0) * .30
                add(FigureBox(x - .07, .46 - height, x + .07, .46, colorIndex = index,
                    alpha = if (index == emphasized) .6 else .3, rounded = false))
                add(FigureLabel(if (hz == 8000.0) "8 kHz" else "${hz.toInt()} Hz", x, .55, .3, colorIndex = index))
                add(FigureLabel(if (silent) "静音" else "${num(levels[index], 0)} dB", x, .12, .3, colorIndex = index))
            }
            // A speaker silhouette denotes the intended output, not a measured cone displacement.
            add(FigureBox(.36, .68, .48, .84, filled = false))
            add(FigurePolyline(listOf(FigurePoint(.48, .68), FigurePoint(.62, .61), FigurePoint(.62, .91), FigurePoint(.48, .84))))
            if (!silent) {
                val opacity = (.25 + (levels.average() + 36).coerceIn(0.0, 60.0) / 80).coerceIn(.25, 1.0)
                add(FigureArc(.61, .76, .13, .16, -60.0, 120.0, alpha = opacity))
                add(FigureArc(.61, .76, .20, .23, -60.0, 120.0, alpha = opacity * .7))
            }
            add(FigureLabel(when { silent -> "基础支路关闭 · 无输出"; v("mix") == 0.0 -> "仅原声与输出增益"; else -> "三音参考 · 电信号" }, .5, .95))
        }
        val notes = listOf(MODEL_NOTE,
            "三柱是固定 60 Hz、420 Hz、8 kHz 参考音经过完整模块的幅度变化，柱高显示 −36 至 +24 dB，数值保留实际参考增益。不是当前音乐各频段的能量，也不是测得的扬声器位移。",
            "基础电平为0时基础和补偿两条湿支路同时静音；混合为0时只保留原声和模块输出增益。补偿强度为0仅关闭补偿支路，不关闭低切和高切。")
        return EffectVisual("扬声器与低中高频", subtitle, scene = EffectScene(elements), notes = notes)
    }

    private fun routeDiagram(values: Map<String, *>, speaker: Boolean): EffectVisual {
        val all = value(values, "scope") >= .5
        val elements = if (speaker) listOf<FigureElement>(
            FigureBox(.33, .1, .67, .76, filled = false), FigureLine(.43, .17, .57, .17, width = 3.0),
            FigureCircle(.5, .57, .11, .1), FigureCircle(.5, .57, .045, .04, filled = true, alpha = .35),
            FigureArc(.5, .57, .2, .17, 20.0, 140.0, alpha = .5),
            FigureLabel(if (all) "所有输出都允许处理" else "仅内置扬声器允许处理", .5, .9)
        ) else listOf(
            FigureBox(.05, .13, .5, .35, "内置扬声器"), FigureLine(.52, .24, .63, .24, arrow = true), FigureBox(.65, .13, .95, .35, "处理"),
            FigureBox(.05, .52, .5, .74, "耳机／蓝牙", colorIndex = 2), FigureLine(.52, .63, .63, .63, colorIndex = 2, arrow = true),
            FigureBox(.65, .52, .95, .74, if (all) "处理" else "原声", colorIndex = if (all) 0 else 2),
            FigureLabel("路由不适用时，输出增益也旁路", .5, .92)
        )
        return EffectVisual(if (speaker) "适用输出设备" else "路由与处理条件", if (all) "所有输出" else "仅内置扬声器",
            scene = EffectScene(elements), notes = listOf("作用范围是处理策略，不是正在连接的设备。仅内置扬声器模式下，耳机、蓝牙、未知或多个同时输出默认旁路；所有输出模式是用户主动选择。实际路由状态看下方。"))
    }

    /** Exact native target-to-bin mapping, including frequencies below one FFT bin. */
    internal fun spectrumFrequencies(sampleRate: Int): List<Double> {
        val rate = sampleRate.toDouble()
        val high = min(20000.0, .48 * rate)
        return List(48) { index ->
            val nominal = 20 * (high / 20).pow(index / 47.0)
            val bin = floor(nominal * FFT_SIZE / rate + .5).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            bin * rate / FFT_SIZE
        }
    }

    fun live(values: Map<String, *>, focusKey: String?, sample: ExpansionEffectsRuntime,
             history: List<EffectVisualFrame>): EffectVisual {
        val reason = when {
            !sample.enabled -> "扬声器优化已关闭"
            sample.meter(4) < .5f -> "当前路由不适用 · 保持原声"
            !sample.isProcessing || sample.meter(5) < .5f -> "等待正常播放与活动音频"
            sample.meter(7) < 1f -> "正在等待完整音频分析窗"
            sample.meters.size < 116 -> "等待完整频谱数据"
            else -> null
        }
        if (reason != null) return EffectVisual("实际输入与处理后频谱", "等待有效音频读数", scene = EffectScene(listOf(
            FigureLabel(reason, .5, .43, .94, emphasis = true),
            FigureLabel(if (sample.meter(4) < .5f) "未处理时不生成效果频谱" else "收满一窗后显示真实频谱", .5, .68, .94)
        )), notes = listOf("频谱只在图表采样开启时按需采集；路由旁路、配置切换或未满完整分析窗时不画假数据。"))

        val frequencies = spectrumFrequencies(sample.sampleRate)
        fun curve(title: String, offset: Int, color: Int): LiveprogCurve {
            val points = frequencies.mapIndexed { index, frequency ->
                LiveprogPoint(frequency, sample.meter(offset + index).toDouble().takeIf { it.isFinite() } ?: -120.0)
            }.distinctBy { it.x }
            return LiveprogCurve(title, points, color)
        }
        val curves = listOf(curve("输入", 20, 2), curve("处理后最终输出", 68, 0))
        val upper = max(12.0, ceil((curves.flatMap { it.points }.maxOfOrNull { it.y } ?: 0.0) / 6) * 6)
        val actualMix = sample.meter(3).toDouble().coerceIn(0.0, 1.0)
        val subtitle = if (sample.meter(6) > .5f)
            "${sample.sampleRate} Hz · 低率适配：低切 ${num(sample.meter(8).toDouble(), 0)} / 高切 ${num(sample.meter(9).toDouble(), 0)} / 补偿 ${num(sample.meter(10).toDouble(), 0)} Hz"
        else "${sample.sampleRate} Hz · 实际混合 ${num(actualMix * 100, 0)}% · 输出 ${num(sample.meter(12).toDouble())} dB"
        val notes = listOf("输入和最终输出均由驱动真实 2048 点 Hann 窗分析得到，左右声道各自 FFT 后合成功率，反相信号不会因先合并声道而消失。不是扬声器声学测量。",
            "48个目标频点映射到实际FFT频点并去重。当前频率分辨率 ${num(sample.sampleRate.toDouble() / FFT_SIZE, 2)} Hz；最高分析频率受20 kHz与采样率48%共同约束。",
            "有效低切 ${num(sample.meter(8).toDouble(), 0)} Hz、高切 ${num(sample.meter(9).toDouble(), 0)} Hz、补偿中心 ${num(sample.meter(10).toDouble(), 0)} Hz。${if (sample.meter(6) > .5f) "当前采样率较低，截止频率已适配。" else "当前采样率无需限制设定频率。"}",
            "当前实际混合 ${num(actualMix * 100, 0)}%，实际输出增益 ${num(sample.meter(12).toDouble())} dB。",
            "已完成分析窗 ${num(sample.meter(7).toDouble(), 0)}。重复读取同一窗不会生成假跳动；曲线展示当前完整窗，而不是按界面计时器模拟波形。")
        return EffectVisual("实际输入与处理后频谱", subtitle,
            plot = LiveprogStaticPlot("实际输入与处理后频谱", subtitle, xLabel = "频率 Hz", yLabel = "幅度 dBFS",
                xMinimum = frequencies.first(), xMaximum = frequencies.last(), yMinimum = -120.0, yMaximum = upper,
                logarithmicX = true, curves = curves, notes = notes), notes = notes)
    }
}
