package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogBar
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogEdge
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogNode
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPoint
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogRegion
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.ui.ClarityPreviewModel
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Settled, read-only parameter illustrations. No control state, engine calls or fabricated live audio. */
internal object ClarityLinkedPlots {
    data class Focus(val key: String, val leftTitle: String, val rightTitle: String,
        val liveTitle: String = "实时增强与抑制 · 最近20秒")
    private enum class Group { BASE, OZONE, NATURAL, HIFI, HIFI_DELAY,
        DYNAMIC_BAND, DYNAMIC_TRANSFER, DYNAMIC_TIME, DEESS_BAND, DEESS_TRANSFER, DEESS_TIME }

    private val p = HighFrequencyParameters
    private val a = HighFrequencyAdvancedParameters
    private const val MODE = "v_clarity_mode"
    val relevantKeys: Set<String> = p.clarity.map { it.key }.toSet() +
        a.clarity.map { it.key } + a.switches + MODE

    private val ozoneKeys = setOf(p.shelfFrequency.key, p.shelfSlope.key)
    private val naturalKeys = a.natural.map { it.key }.toSet() + a.NATURAL_ENABLED
    private val hifiKeys = setOf(p.lowFrequency.key, p.highFrequency.key,
        p.lowTrim.key, p.midTrim.key, p.highTrim.key)
    private val hifiDelayKeys = setOf(p.lowDelay.key, p.midDelay.key)
    private val dynamicBandKeys = setOf(a.dynamicFrequency.key, a.dynamicQ.key,
        a.dynamicMaximum.key, a.DYNAMIC_ENABLED)
    private val deessBandKeys = setOf(a.deessLow.key, a.deessHigh.key, a.deessQ.key,
        a.deessMaximum.key, a.DEESS_ENABLED)

    fun focus(key: String?, values: Map<String, *>): Focus {
        val normalized = when {
            key == "v_clarity_amount" -> p.clarityAmount.key
            key != null && key in relevantKeys -> key
            else -> p.clarityAmount.key
        }
        val mode = mode(values)
        return when (group(normalized)) {
            Group.OZONE -> Focus(normalized, "Ozone高频高架", "高低频如何变化")
            Group.NATURAL -> Focus(normalized, "自然选频响应", "原声与选频增强")
            Group.HIFI -> Focus(normalized, "XHiFi合成参考", "低中高三段分工")
            Group.HIFI_DELAY -> Focus(normalized, "三路延迟相位", "三段相对到达时间")
            Group.DYNAMIC_BAND -> Focus(normalized, "高频检测与压制", "最大高频衰减范围")
            Group.DYNAMIC_TRANSFER -> Focus(normalized, "高频抑制触发关系", "弱声与强声压制")
            Group.DYNAMIC_TIME -> Focus(normalized, "高频检测时间响应", "启动与恢复速度")
            Group.DEESS_BAND -> Focus(normalized, "齿音检测与压制", "最大齿音衰减范围")
            Group.DEESS_TRANSFER -> Focus(normalized, "齿音抑制触发关系", "弱齿音与强齿音")
            Group.DEESS_TIME -> Focus(normalized, "齿音检测时间响应", "启动与恢复速度")
            Group.BASE -> Focus(normalized,
                when (mode) { 1 -> "Ozone参数频响"; 2 -> "XHiFi合成参考"; else -> "自然增强参数频响" },
                when (mode) { 1 -> "高低频如何变化"; 2 -> "低中高三路混合"; else -> "原声与差分增强" })
        }
    }

    fun pair(model: ClarityPreviewModel, values: Map<String, *>, key: String?, actualRate: Boolean): LiveprogChartPair {
        val selected = focus(key, values)
        val group = group(selected.key)
        val result = when (group) {
            Group.BASE -> base(model, selected)
            Group.OZONE -> ozone(model, selected)
            Group.NATURAL -> natural(model, selected)
            Group.HIFI -> hifi(model, selected)
            Group.HIFI_DELAY -> hifiDelay(model, selected)
            Group.DYNAMIC_BAND, Group.DEESS_BAND -> detector(model, selected, group == Group.DEESS_BAND)
            Group.DYNAMIC_TRANSFER, Group.DEESS_TRANSFER -> transfer(model, selected, group == Group.DEESS_TRANSFER)
            Group.DYNAMIC_TIME, Group.DEESS_TIME -> timing(model, selected, group == Group.DEESS_TIME)
        }
        val enabled = values["v_clarity"] == true
        val participates = when (group) {
            Group.OZONE -> model.mode == 1
            Group.HIFI, Group.HIFI_DELAY -> model.mode == 2
            Group.NATURAL -> model.mode == 0 && model.naturalEnabled && model.naturalLow < model.naturalHigh
            Group.DYNAMIC_BAND, Group.DYNAMIC_TRANSFER, Group.DYNAMIC_TIME -> model.dynamicEnabled
            Group.DEESS_BAND, Group.DEESS_TRANSFER, Group.DEESS_TIME -> model.deessEnabled && model.deessLow < model.deessHigh
            Group.BASE -> true
        }
        val state = when {
            !enabled -> "模块关闭 · 保存参数预览"
            !participates -> "未参与当前处理 · 参数预览"
            else -> null
        }
        val context = listOf("${if (actualRate) "实际" else "参考"}处理率 ${model.rate} Hz；当前模式 ${model.modeLabel}。" +
            (state ?: "当前模式已启用这组保存参数。"),
            "静图是参数稳定后的数学参考；不是当前音乐频谱、THD、实时抑制值或声场距离。只读绘图不会改变任何原生参数、试听状态和音频算法。",
            "正常顺序为基础增强→动态高频抑制→去齿音→公共输出衰减。基础增强RMS差遥测取自两种抑制与公共输出衰减之前，不是最终整链输入输出电平差。")
        fun present(plot: LiveprogStaticPlot) = plot.copy(inactive = !enabled || !participates,
            subtitle = if (state != null) "未参与 · ${plot.subtitle}" else plot.subtitle,
            notes = context + plot.notes)
        return LiveprogChartPair(present(result.professional), present(result.illustration))
    }

    private fun group(key: String): Group = when (key) {
        in ozoneKeys -> Group.OZONE
        in naturalKeys -> Group.NATURAL
        in hifiKeys -> Group.HIFI
        in hifiDelayKeys -> Group.HIFI_DELAY
        in dynamicBandKeys -> Group.DYNAMIC_BAND
        a.dynamicThreshold.key, a.dynamicRatio.key -> Group.DYNAMIC_TRANSFER
        a.dynamicAttack.key, a.dynamicRelease.key -> Group.DYNAMIC_TIME
        in deessBandKeys -> Group.DEESS_BAND
        a.deessThreshold.key, a.deessRatio.key -> Group.DEESS_TRANSFER
        a.deessAttack.key, a.deessRelease.key -> Group.DEESS_TIME
        else -> Group.BASE
    }

    private fun base(model: ClarityPreviewModel, focus: Focus): LiveprogChartPair {
        val label = if (model.mode == 2) "XHiFi分频近似 · 系数${n(model.amount)}" else
            "${model.modeLabel} · 系数${n(model.amount)} · 输出${n(model.outputDb)}dB"
        val left = response(model, focus.leftTitle, label, listOf(
            frequencyCurve(model, "当前基础增强", 0, model::enhancementDb)), listOf(
            "曲线包含当前模式的基础增强和保存的公共输出衰减，未叠加未知的实时动态抑制。",
            "增强系数 ${model.amount}，公共输出 ${model.outputDb} dB；增强系数在三种模式中的算法作用不同，不能一律当作固定dB增益。") + modeNotes(model))
        val right = when (model.mode) {
            1 -> ozone(model, focus).illustration
            2 -> hifiRouting(model, focus.rightTitle)
            else -> naturalRouting(model, focus.rightTitle)
        }
        return LiveprogChartPair(left, right)
    }

    private fun ozone(model: ClarityPreviewModel, focus: Focus): LiveprogChartPair {
        val preview = model.copy(mode = 1, modeLabel = "Ozone")
        val subtitle = "转折${hz(model.shelfHz)} · S${n(model.shelfSlope)}"
        val notes = listOf("Ozone高频高架由现有ClarityPreviewModel.enhancementDb计算，包含公共输出衰减。",
            "高频相对低频的平台增益为20log10(1＋增强系数)=${20 * log10(1 + model.amount)} dB；S调节转折形状，不是dB/oct斜率。",
            "保存的转折 ${model.shelfHz} Hz、S=${model.shelfSlope}；未处于Ozone模式时保持灰色保存参数预览。")
        val left = response(preview, focus.leftTitle, subtitle,
            listOf(frequencyCurve(preview, "高架＋输出", 0, preview::enhancementDb)), notes)
        val locations = listOf((model.shelfHz / 4).coerceAtLeast(20.0), model.shelfHz,
            (model.shelfHz * 4).coerceAtMost(model.rate * .5 - 100))
        val right = LiveprogStaticPlot(focus.rightTitle, "1低频 · 2转折 · 3高频", LiveprogPlotStyle.BARS,
            yLabel = "增益dB", yMinimum = -12.0, yMaximum = 24.0,
            bars = locations.mapIndexed { i, frequency -> LiveprogBar("${listOf("低于转折","转折附近","高于转折")[i]} ${frequency} Hz",
                preview.enhancementDb(frequency), i) },
            notes = notes + listOf("三个柱是同一保存频响在指定频率的增益，不是三个新增的EQ频段或音乐能量：" +
                locations.mapIndexed { i, frequency -> "${i + 1}=${frequency}Hz" }.joinToString("；")))
        return LiveprogChartPair(left, right)
    }

    private fun natural(model: ClarityPreviewModel, focus: Focus): LiveprogChartPair {
        val selected = model.copy(mode = 0, modeLabel = "自然", naturalEnabled = true)
        val full = selected.copy(naturalEnabled = false)
        val notes = listOf("自然选频只在自然模式参与。参数关闭或处于其它模式时，曲线仍按保存的选频设置预览，灰色表示未参与。",
            "选频结果经一采样差分乘增强系数，叠加原声，再经原生双抽头后处理。选频并不直接删除频带外原声。",
            "曲线复用现有ClarityPreviewModel，保留原NoiseSharpening手写历史更新形成的双抽头行为，不能当作同名系数类的一阶递归IIR。",
            "保存范围 ${model.naturalLow}…${model.naturalHigh} Hz，选频 Q=${model.naturalQ}。色区仅标出配置边界；滤波为柔和过渡，不是矩形通带。",
            "两个边界在当前采样率钳位后若相同，原驱动会使选频可用度淡出；模型不伪造新的合法频带。")
        val left = response(selected, focus.leftTitle, "${hz(model.naturalLow)}–${hz(model.naturalHigh)} · Q${n(model.naturalQ)}",
            listOf(frequencyCurve(selected, "保存选频", 0, selected::enhancementDb),
                frequencyCurve(full, "全频增强参考", 1, full::enhancementDb, true)), notes).copy(
            regions = if (model.naturalLow < model.naturalHigh)
                listOf(LiveprogRegion(model.naturalLow, model.naturalHigh, "配置选频范围", 0)) else emptyList())
        return LiveprogChartPair(left, naturalRouting(selected, focus.rightTitle).copy(notes = notes))
    }

    private fun naturalRouting(model: ClarityPreviewModel, title: String): LiveprogStaticPlot =
        LiveprogStaticPlot(title, if (model.naturalEnabled)
            "${hz(model.naturalLow)}–${hz(model.naturalHigh)} · Q${n(model.naturalQ)}" else "原声保留 · 全频差分增强", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .12, .18, 1),
                LiveprogNode("out", "输出${n(model.outputDb)}dB", .88, .18, 0),
                LiveprogNode("diff", "差分×${n(model.amount)}", .12, .78, 2),
                LiveprogNode("sum", "原声＋增强", .88, .78, 0)),
            edges = listOf(LiveprogEdge("in", "sum", "原声直接参与相加", 1),
                LiveprogEdge("in", "diff", if (model.naturalEnabled)
                    "HP ${model.naturalLow}Hz＋LP ${model.naturalHigh}Hz，Q=${model.naturalQ}" else "全频输入", 2),
                LiveprogEdge("diff", "sum", "选频输入与其上一采样的差×${model.amount}", 2),
                LiveprogEdge("sum", "out", "原双抽头后处理；最终公共输出${model.outputDb}dB", 0)),
            notes = listOf("方框为原生结构简写：原声和差分增强相加后处理，不是串联删除原声带外频率。",
                "基础增强后另有动态高频抑制、去齿音及公共输出缩放；图不假设这些信号相关环节当前一定发生。"))

    private fun hifi(model: ClarityPreviewModel, focus: Focus): LiveprogChartPair {
        val preview = model.copy(mode = 2, modeLabel = "XHiFi")
        val gains = doubleArrayOf(model.lowTrimDb + model.outputDb,
            model.midTrimDb + 20 * log10(1 + model.amount) + model.outputDb,
            model.highTrimDb + 20 * log10((1 + model.amount) * 1.2) + model.outputDb)
        val notes = modeNotes(preview) + listOf("低/中/高目标增益分别为trim、trim＋20log10(1＋amount)、trim＋20log10(1.2×(1＋amount))，此图还分别加同一公共输出衰减。",
            "分界 ${model.lowHz} / ${model.highHz} Hz；低频延迟 ${actualDelay(model, false)} ms，中频延迟 ${actualDelay(model, true)} ms，高频路径没有这两项补偿。",
            "右图平坦分段只说明三路各自的配置增益与频率分工，不是理想矩形滤波器的声学预测，更不是合成后的实测频响。",
            "各路滤波重叠、相位和延迟会共同改变合成响应；不能把三个目标dB直接相加或解释成能量占比。")
        val left = response(preview, focus.leftTitle, "XHiFi分频近似 · ${hz(model.lowHz)}/${hz(model.highHz)}",
            listOf(frequencyCurve(preview, "合成近似", 0, preview::enhancementDb)), notes)
        val upper = model.rate * .5 - 100
        val boundaries = listOf(20.0, model.lowHz, model.highHz, upper)
        val names = listOf("低频", "中频", "高频")
        val right = LiveprogStaticPlot(focus.rightTitle, "三段目标 · 非最终频响", LiveprogPlotStyle.BANDS,
            xLabel = "Hz", yLabel = "目标dB", xMinimum = 20.0, xMaximum = upper,
            yMinimum = -24.0, yMaximum = 24.0, logarithmicX = true,
            curves = gains.mapIndexed { i, gain -> LiveprogCurve(names[i],
                listOf(LiveprogPoint(boundaries[i], gain), LiveprogPoint(boundaries[i + 1], gain)), i) },
            regions = gains.indices.map { i -> LiveprogRegion(boundaries[i], boundaries[i + 1], names[i], i) },
            notes = notes + gains.mapIndexed { i, gain -> "${names[i]}目标增益${gain}dB，范围${boundaries[i]}…${boundaries[i + 1]}Hz。" })
        return LiveprogChartPair(left, right)
    }

    private fun hifiRouting(model: ClarityPreviewModel, title: String): LiveprogStaticPlot =
        LiveprogStaticPlot(title, "三路并行 · 目标系数与延迟", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .08, .5, 1), LiveprogNode("sum", "合成${n(model.outputDb)}dB", .92, .5, 0),
                LiveprogNode("low", "低频${hz(model.lowHz)}", .5, .08, 1),
                LiveprogNode("mid", "中频×${n(1 + model.amount)}", .5, .5, 0),
                LiveprogNode("high", "高频×${n((1 + model.amount) * 1.2)}", .5, .92, 2)),
            edges = listOf(LiveprogEdge("in", "low", "低通 ${model.lowHz}Hz", 1),
                LiveprogEdge("in", "mid", "带通 ${model.lowHz}…${model.highHz}Hz", 0),
                LiveprogEdge("in", "high", "高通 ${model.highHz}Hz", 2),
                LiveprogEdge("low", "sum", "低频trim${model.lowTrimDb}dB；延迟${actualDelay(model, false)}ms", 1),
                LiveprogEdge("mid", "sum", "中频trim${model.midTrimDb}dB；延迟${actualDelay(model, true)}ms", 0),
                LiveprogEdge("high", "sum", "高频trim${model.highTrimDb}dB；公共输出${model.outputDb}dB在相加后", 2)),
            notes = modeNotes(model) + listOf("高、中、低三路按原程序相加；1.2高频倍率及低/中频整数采样延迟保留，方框不是实际声源距离。"))

    private fun hifiDelay(model: ClarityPreviewModel, focus: Focus): LiveprogChartPair {
        val lowSamples = delayFrames(model, false)
        val midSamples = delayFrames(model, true)
        val frequencies = frequencies(model)
        val phaseMinimum = min(-360.0, -360 * frequencies.last() * max(lowSamples, midSamples) / model.rate)
        val left = LiveprogStaticPlot(focus.leftTitle, "整数采样延迟 · 相位参考",
            xLabel = "Hz", yLabel = "度", xMinimum = frequencies.first(), xMaximum = frequencies.last(),
            yMinimum = phaseMinimum, yMaximum = 0.0, logarithmicX = true,
            curves = listOf(LiveprogCurve("低频延迟", frequencies.map { LiveprogPoint(it, -360 * it * lowSamples / model.rate) }, 1),
                LiveprogCurve("中频延迟", frequencies.map { LiveprogPoint(it, -360 * it * midSamples / model.rate) }, 0),
                LiveprogCurve("高频直达", frequencies.map { LiveprogPoint(it, 0.0) }, 2)),
            notes = listOf("只展示低、中、高路径额外纯延迟的展开相位−360×频率×采样数/Fs，不含分频器相位，不冒充整模块总相位或声场方位。",
                "低频请求 ${model.lowDelayMs} ms→${lowSamples}采样→${actualDelay(model, false)} ms；中频请求 ${model.midDelayMs} ms→${midSamples}采样→${actualDelay(model, true)} ms。",
                "原驱动将微秒请求按采样率截断为整数采样；路径切换存在原有交叉淡化。此图给出保存目标，不伪造瞬时过渡。"))
        val right = LiveprogStaticPlot(focus.rightTitle, "1低频 · 2中频 · 3高频", LiveprogPlotStyle.BARS,
            yLabel = "ms", yMinimum = 0.0, yMaximum = 10.0,
            bars = listOf(LiveprogBar("低频额外延迟", actualDelay(model, false), 1),
                LiveprogBar("中频额外延迟", actualDelay(model, true), 0), LiveprogBar("高频额外延迟", 0.0, 2)),
            notes = left.notes + listOf("柱高只代表额外路径延迟的毫秒值；高频没有本组额外延迟，不代表整个设备零延迟。只有XHiFi模式应用这两项设置。"))
        return LiveprogChartPair(left, right)
    }

    private fun detector(model: ClarityPreviewModel, focus: Focus, deess: Boolean): LiveprogChartPair {
        val maximum = if (deess) model.deessMaximum else model.dynamicMaximum
        val q = if (deess) model.deessQ else model.dynamicQ
        val detection: (Double) -> Double = if (deess) model::deessDetectorDb else model::dynamicDetectorDb
        val attenuation: (Double) -> Double = if (deess) model::deessDb else model::dynamicDb
        val notes = dynamicsNotes(model, deess) + listOf("检测曲线和最大衰减曲线是不同的滤波器。检测曲线不直接削减声音；当前音乐并不一定达到最大衰减。",
            "检测Q=${q}。${if (deess) "实际衰减PEQ的中心为sqrt(low×high)，Q由中心/(high−low)计算并夹到0.1…20，不能拿检测Q替代。" else "实际衰减使用高频高架，原生S=1，不能拿高通检测Q替代高架斜率。"}")
        val detectorSubtitle = when (focus.key) {
            a.dynamicMaximum.key, a.deessMaximum.key -> "最多${n(maximum)}dB · 检测Q${n(q)}"
            else -> if (deess) "${hz(model.deessLow)}–${hz(model.deessHigh)} · Q${n(q)}"
                else "HP ${hz(model.dynamicHz)} · Q${n(q)}"
        }
        val left = response(model, focus.leftTitle, detectorSubtitle,
            listOf(frequencyCurve(model, "检测滤波", 1, detection), frequencyCurve(model, "最大衰减参考", 2, attenuation)), notes,
            low = -60.0, high = 12.0)
        val center = if (deess) sqrt(model.deessLow * model.deessHigh) else model.dynamicHz
        val upper = model.rate * .5 - 100
        val locations = if (deess) listOf((model.deessLow * .5).coerceAtLeast(20.0), center, (model.deessHigh * 1.5).coerceAtMost(upper))
            else listOf((center * .25).coerceAtLeast(20.0), center, (center * 4).coerceAtMost(upper))
        val right = LiveprogStaticPlot(focus.rightTitle, if (deess) "1带外低 · 2中心 · 3带外高" else "1低频 · 2转折 · 3高频",
            LiveprogPlotStyle.BARS, yLabel = "增益dB", yMinimum = -18.0, yMaximum = 0.0,
            bars = locations.mapIndexed { i, frequency -> LiveprogBar("最大衰减参考 ${frequency}Hz", attenuation(frequency), i) },
            notes = notes + locations.mapIndexed { i, frequency -> "${i + 1}：${frequency}Hz，达到最大抑制时参考增益${attenuation(frequency)}dB。" })
        return LiveprogChartPair(left, right)
    }

    private fun transfer(model: ClarityPreviewModel, focus: Focus, deess: Boolean): LiveprogChartPair {
        val threshold = if (deess) model.deessThreshold else model.dynamicThreshold
        val ratio = if (deess) model.deessRatio else model.dynamicRatio
        val reduction: (Double) -> Double = if (deess) model::deessReduction else model::dynamicReduction
        val notes = dynamicsNotes(model, deess) + listOf("横轴是假设已经滤波、峰值包络检测后的稳定电平，纵轴是目标抑制量。它不把模块输入RMS当作检测包络。",
            "目标抑制=clamp((检测dBFS−阈值)×(1−1/ratio),0,最大抑制)。1:1或最大抑制0会产生0目标，不虚构弯曲。",
            "原音频浮点可超0dBFS，因此专业图延伸至+12dBFS以完整展示数学关系；这不是建议增大播放音量。")
        val left = LiveprogStaticPlot(focus.leftTitle, "阈值${n(threshold)}dBFS · ${n(ratio)}:1",
            xLabel = "检测dBFS", yLabel = "抑制dB", xMinimum = -60.0, xMaximum = 12.0,
            yMinimum = 0.0, yMaximum = 18.0,
            curves = listOf(LiveprogCurve("稳态目标", List(193) { i -> val input = -60.0 + i * 72.0 / 192; LiveprogPoint(input, reduction(input)) }, 2)),
            notes = notes)
        val inputs = listOf(-36.0, -18.0, -6.0, 0.0)
        val right = LiveprogStaticPlot(focus.rightTitle, "1弱→4强 · 假设检测", LiveprogPlotStyle.BARS,
            yLabel = "抑制dB", yMinimum = 0.0, yMaximum = 18.0,
            bars = inputs.mapIndexed { i, input -> LiveprogBar("假设检测${input}dBFS", reduction(input), i % 3) },
            notes = notes + inputs.mapIndexed { i, input -> "${i + 1}：检测${input}dBFS→目标抑制${reduction(input)}dB。" })
        return LiveprogChartPair(left, right)
    }

    private fun timing(model: ClarityPreviewModel, focus: Focus, deess: Boolean): LiveprogChartPair {
        val attack = if (deess) model.deessAttackMs else model.dynamicAttackMs
        val release = if (deess) model.deessReleaseMs else model.dynamicReleaseMs
        val end = max(160.0, 80 + release * 4)
        val times = DoubleArray(193) { i -> .1 * (end / .1).pow(i / 192.0) }
        val notes = dynamicsNotes(model, deess) + listOf("专业曲线复用ClarityPreviewModel.envelope：假设单位峰值从t=0持续80ms后归零，显示检测包络的归一化启动与恢复，不是当前音乐包络或实际dB抑制。",
            "原驱动检测峰值上升用attack、下降用release；时间常数分别为${attack}ms/${release}ms，不能声称到该时刻已经100%完成。",
            "为了同时看清亚毫秒启动与长恢复，横轴使用0.1ms起的对数时间；这不是频率，也不是空间传播。")
        val left = LiveprogStaticPlot(focus.leftTitle, "80ms假设输入 · 归一化包络",
            xLabel = "ms", yLabel = "%", xMinimum = .1, xMaximum = end,
            yMinimum = 0.0, yMaximum = 100.0, logarithmicX = true,
            curves = listOf(LiveprogCurve("检测包络", times.map { LiveprogPoint(it, model.envelope(it, deess) * 100) }, 2)),
            regions = listOf(LiveprogRegion(.1, 80.0, "单位输入保持区", 1)), notes = notes)
        val right = LiveprogStaticPlot(focus.rightTitle, "1启动${n(attack)} · 2恢复${n(release)}ms", LiveprogPlotStyle.BARS,
            yLabel = "ms", yMinimum = 0.0, yMaximum = if (deess) 500.0 else 2000.0,
            bars = listOf(LiveprogBar("启动时间常数", attack, 2), LiveprogBar("恢复时间常数", release, 1)), notes = notes)
        return LiveprogChartPair(left, right)
    }

    private fun dynamicsNotes(model: ClarityPreviewModel, deess: Boolean): List<String> {
        val threshold = if (deess) model.deessThreshold else model.dynamicThreshold
        val ratio = if (deess) model.deessRatio else model.dynamicRatio
        val maximum = if (deess) model.deessMaximum else model.dynamicMaximum
        return listOf("${if (deess) "去齿音" else "动态高频抑制"}阈值${threshold}dBFS、ratio=${ratio}:1、最大抑制${maximum}dB；此组在三种基础增强模式之后独立工作。",
            if (deess) "检测为HP ${model.deessLow}Hz×LP ${model.deessHigh}Hz，Q=${model.deessQ}；使用左右检测支路较大绝对峰值，非RMS检测。" else
                "检测为高通${model.dynamicHz}Hz，Q=${model.dynamicQ}；使用左右检测支路较大绝对峰值，非RMS检测。",
            "保存的检测与最大衰减参考在开关关闭时仍可查看，灰色说明未参与；当前实际抑制值只来自下方原生遥测，不由这些静图推算。")
    }

    private fun modeNotes(model: ClarityPreviewModel): List<String> = when (model.mode) {
        2 -> listOf("XHiFi频响明确为分频近似：原生低路一阶、其它分支为原有高阶Butterworth结构；这里复用ClarityPreviewModel现有级联近似和保存延迟，不宣称逐系数完全复原。",
            "低频系数=trim，中央系数=(1＋amount)×trim，高频系数=(1＋amount)×1.2×trim。相加含相位与整数路径延迟，因此不是三段平坦增益简单拼接。")
        1 -> listOf("Ozone按原生高频高架方程的稳定系数预览，转折频率和S只在此模式参与。")
        else -> listOf("自然增强为原声加一采样差分再经原生双抽头后处理；amount=0也保留原生后处理，不能擅自画成严格0dB直通。")
    }

    private fun response(model: ClarityPreviewModel, title: String, subtitle: String, curves: List<LiveprogCurve>,
        notes: List<String>, low: Double = -36.0, high: Double = 24.0): LiveprogStaticPlot =
        LiveprogStaticPlot(title, subtitle, xLabel = "Hz", yLabel = "dB", xMinimum = 20.0,
            xMaximum = model.rate * .5 - 100, yMinimum = low, yMaximum = high,
            logarithmicX = true, curves = curves, notes = notes + "曲线越过固定纵轴范围的部分按图框裁切；完整模型不改值。")

    private fun frequencyCurve(model: ClarityPreviewModel, label: String, color: Int,
        compute: (Double) -> Double, dashed: Boolean = false) = LiveprogCurve(label,
        frequencies(model).map { LiveprogPoint(it, compute(it)) }, color, dashed)

    private fun frequencies(model: ClarityPreviewModel) = DoubleArray(192) { i ->
        20.0 * ((model.rate * .5 - 100) / 20).pow(i / 191.0)
    }
    private fun delayFrames(model: ClarityPreviewModel, mid: Boolean) =
        HighFrequencyParameters.delaySamples(
            ((if (mid) model.midDelayMs else model.lowDelayMs) * 1000).roundToInt(), model.rate).toDouble()
    private fun actualDelay(model: ClarityPreviewModel, mid: Boolean) = delayFrames(model, mid) * 1000.0 / model.rate
    private fun mode(values: Map<String, *>) = (values[MODE] as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()?.coerceIn(0, 2) ?: 0
    private fun n(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun hz(value: Double): String = if (value >= 1000) "${n(value / 1000)}k" else "${n(value)}Hz"
}
