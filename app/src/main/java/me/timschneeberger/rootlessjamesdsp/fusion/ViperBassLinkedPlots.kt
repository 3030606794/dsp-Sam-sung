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
import java.util.Locale

/** Read-only presentations of the existing ViPERBass/Subwoofer model; no engine/control writes. */
internal object ViperBassLinkedPlots {
    data class Focus(val key: String, val leftTitle: String, val rightTitle: String)
    private enum class Group { MODE, LOWPASS, GAIN, DEEP, PUNCH }
    private val p = BassParameters
    private const val MODE = "v_bass_mode"
    val relevantKeys: Set<String> = p.viper.map { it.key }.toSet() + MODE

    fun focus(key: String?, values: Map<String, *>): Focus {
        val normalized = when {
            key == p.LEGACY_AMOUNT -> p.amount.key
            key != null && key in relevantKeys -> key
            else -> MODE
        }
        val mode = (values[MODE] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
        return when (group(normalized)) {
            Group.LOWPASS -> Focus(normalized, "低通与叠加参考", "转折附近的变化")
            Group.DEEP -> Focus(normalized, "深潜峰形与合成", "深潜附近的变化")
            Group.PUNCH -> Focus(normalized, "冲击峰形与合成", "冲击附近的变化")
            Group.GAIN -> when (mode) {
                1 -> Focus(normalized, "线性局部参数参考", "多相与低通叠加")
                2 -> Focus(normalized, "强度与合成响应", "深潜与冲击变化")
                else -> Focus(normalized, "强度与合成响应", "原声与增强支路")
            }
            Group.MODE -> Focus(normalized, if (mode == 1) "Pure Bass+局部参考" else "本模式合成参数参考", "本模式处理结构")
        }
    }

    fun pair(model: BassLinkedModel, key: String?): LiveprogChartPair {
        val selected = focus(key, mapOf(MODE to model.mode))
        val group = group(selected.key)
        // Reconstruct only immutable display snapshots to preview controls dormant in this mode.
        // This never changes the selected mode or any persisted/native setting.
        val previewMode = when (group) {
            Group.LOWPASS -> if (model.mode == 2) 0 else model.mode
            Group.DEEP, Group.PUNCH -> 2
            else -> model.mode
        }
        val preview = if (previewMode == model.mode) model else snapshot(model, previewMode, defaults = false)
        val defaults = snapshot(preview, previewMode, defaults = true)
        val pair = when (group) {
            Group.MODE -> LiveprogChartPair(totalPlot(preview, defaults, selected.leftTitle), routing(preview, selected.rightTitle))
            Group.LOWPASS -> lowpass(preview, defaults, selected)
            Group.GAIN -> gain(preview, defaults, selected)
            Group.DEEP -> peak(preview, defaults, selected, deep = true)
            Group.PUNCH -> peak(preview, defaults, selected, deep = false)
        }
        val participates = when (group) {
            Group.LOWPASS -> model.mode != 2
            Group.DEEP, Group.PUNCH -> model.mode == 2
            else -> true
        }
        val state = when {
            !model.enabled -> "模块关闭 · 保存参数预览"
            !participates -> "当前模式未使用此组参数 · 保存预览"
            else -> "当前模式已启用这组保存参数"
        }
        val notes = listOf("${model.rateLabel}；当前实际选择${model.modeName}。${state}。",
            "默认对照使用同一种参考模式的完整默认参数：低通70Hz、系数0.50、Q0.53、深潜44Hz/修正0dB/BW0.75oct、冲击80Hz/修正0dB/BW0.20oct。",
            "静图只读稳定参数，不是当前音乐、实际能量、THD或实时峰值；曲线默认对照会同时包含其它保存参数与默认值的差异，不声称只隔离当前一根滑杆。",
            if (previewMode != model.mode) "本组按${preview.modeName}的原模型展开保存参数，灰色明确表示未参与当前${model.modeName}；没有自动切换模式。" else
                "模型对应当前选择；原生参数平滑、启用时整路防爆音淡入、缓冲过渡等时域行为不伪装成这张稳态频响。") + approximationNotes(preview)
        fun present(plot: LiveprogStaticPlot) = plot.copy(inactive = !model.enabled || !participates,
            subtitle = if (!model.enabled || !participates) "未参与 · ${plot.subtitle}" else plot.subtitle,
            notes = notes + plot.notes)
        return LiveprogChartPair(present(pair.professional), present(pair.illustration))
    }

    private fun group(key: String) = when (key) {
        p.frequency.key, p.naturalQ.key -> Group.LOWPASS
        p.amount.key -> Group.GAIN
        p.deepFrequency.key, p.deepGain.key, p.deepWidth.key -> Group.DEEP
        p.punchFrequency.key, p.punchGain.key, p.punchWidth.key -> Group.PUNCH
        else -> Group.MODE
    }

    private fun totalPlot(model: BassLinkedModel, defaults: BassLinkedModel, title: String): LiveprogStaticPlot =
        response(model, title, if (model.approximation) "线性局部 · 未含多相核心" else "${model.modeName} · 系数${n(model.factor)}",
            listOf(sampled("当前参数", model.frequencies, model.totalDb, 0),
                sampled("本模式默认", defaults.frequencies, defaults.totalDb, 1, true)),
            listOf("当前与默认曲线使用现有BassLinkedModel.total；低音炮保留双峰相乘、减原声、380Hz低通和0.5/0.6混合，不能把两峰的参数dB直接当作最终合成增益。") + approximationNotes(model))

    private fun lowpass(model: BassLinkedModel, defaults: BassLinkedModel, focus: Focus): LiveprogChartPair {
        val left = response(model, focus.leftTitle, "${if (model.approximation) "线性局部 · " else ""}${n(model.frequency)}Hz · Q${n(model.q)}",
            listOf(sampled("输入低通支路", model.frequencies, model.lowpassDb, 2),
                sampled(if (model.approximation) "线性局部叠加" else "原声＋增强", model.frequencies, model.totalDb, 0),
                sampled("默认叠加参考", defaults.frequencies, defaults.totalDb, 1, true)),
            listOf("低通曲线只代表LP(freq,Q)，叠加线为原声1＋LP×系数的复数响应，保留相位相加。不能把LP的dB曲线简单加到原声上。",
                "Q控制转折形状；配置频率${model.frequency}Hz处LP幅度为Q，即${model.lowpass.at(model.frequency).db()}dB，不一概固定为−3dB。",
                "低音炮不使用这两个控制，它有独立的固定380Hz/Q0.6输出低通；查看灰色参数预览不会改变原模式。") + approximationNotes(model))
        val probes = listOf((model.frequency * .5).coerceAtLeast(10.0), model.frequency, (model.frequency * 2).coerceAtMost(500.0))
        val right = relativeBars(model, defaults, focus.rightTitle, "1低侧 · 2转折 · 3高侧", probes,
            listOf("分别在当前转折的低侧、转折点和高侧比较保存线性响应与同模式默认响应。正数表示该频点相对默认增加，负数表示减少；不是音乐能量。"))
        return LiveprogChartPair(left, right)
    }

    private fun gain(model: BassLinkedModel, defaults: BassLinkedModel, focus: Focus): LiveprogChartPair {
        val left = totalPlot(model, defaults, focus.leftTitle).copy(subtitle =
            "系数${n(model.factor)}${if (model.approximation) " · 仅线性局部" else " · ${model.modeName}"}")
        val right = when (model.mode) {
            2 -> relativeBars(model, defaults, focus.rightTitle, "1深潜 · 2冲击 · 3尾部",
                listOf(model.deepHz, model.punchHz, 380.0),
                listOf("低音炮将系数×2.5换算为共同基础dB，再分别加入深潜和冲击修正；不是把最终输出直接乘增强系数。",
                    "当前深潜PEQ目标${model.deepDb}dB、冲击PEQ目标${model.punchDb}dB；这些是内部滤波器参数，仍需经完整差分/低通/回混才能得到最终频点增益。"))
            1 -> routing(model, focus.rightTitle)
            else -> LiveprogStaticPlot(focus.rightTitle, "1原声 · 2支路 · 3默认支路", LiveprogPlotStyle.BARS,
                yLabel = "倍率×", yMinimum = 0.0, yMaximum = 6.0,
                bars = listOf(LiveprogBar("保留原声乘数", 1.0, 1), LiveprogBar("当前低通支路乘数", model.factor, 0),
                    LiveprogBar("默认低通支路乘数", defaults.factor, 2)),
                notes = listOf("自然低音正常结构为原声1×＋LP(freq,Q)×系数；图中三个数是乘数，不是三份声能，不能相加解释成总响度。",
                    "低通还会改变不同频率的幅度和相位；整路实际合成频响见专业图。Pure Bass+的主路含多相处理，不能把该主路当作恒定原声1×套用此图。"))
        }
        return LiveprogChartPair(left, right)
    }

    private fun peak(model: BassLinkedModel, defaults: BassLinkedModel, focus: Focus, deep: Boolean): LiveprogChartPair {
        val center = if (deep) model.deepHz else model.punchHz
        val width = if (deep) model.deepWidth else model.punchWidth
        val gain = if (deep) model.deepDb else model.punchDb
        val label = if (deep) "深潜" else "冲击"
        val linkedFocus = if (deep) BassLinkedModel.Focus.DEEP else BassLinkedModel.Focus.PUNCH
        val bounds = model.bounds(linkedFocus)
        val notes = listOf("${label}当前中心${center}Hz、原生oct带宽${width}、内部目标${gain}dB。增益修正不是单独最终增益，还包含共同基础20log10(系数×2.5)${if (deep) "" else "和冲击固定−18.06179974dB"}。",
            "专业图分别画当前${label}PEQ、默认${label}PEQ以及当前完整低音炮合成；不是同时叠加三个独立效果。",
            "带宽边界复用现有BassLinkedModel.bounds的双线性数字频率映射，不能把oct当Q，也不能直接说等于合成后总频响的−3dB带宽。",
            "半透明色区仅用于定位当前PEQ带宽参考；低音炮的另一峰、差分减原声和380Hz低通仍会改变最终结果。")
        val left = response(model, focus.leftTitle, "${n(center)}Hz · BW${n(width)}oct · ${n(gain)}dB",
            listOf(sampled("当前${label}PEQ", model.frequencies, if (deep) model.deepResponseDb else model.punchResponseDb, 2),
                sampled("默认${label}PEQ", defaults.frequencies, if (deep) defaults.deepResponseDb else defaults.punchResponseDb, 1, true),
                sampled("完整合成", model.frequencies, model.totalDb, 0)), notes).copy(
            regions = bounds?.let { listOf(LiveprogRegion(it.first, it.second, "oct带宽参考", 2)) } ?: emptyList())
        val probes = bounds?.let { listOf(it.first, center, it.second) } ?: listOf(center, center, center)
        val right = relativeBars(model, defaults, focus.rightTitle, "1低边 · 2中心 · 3高边", probes,
            notes + listOf("直观图取这三个指定频率的完整低音炮响应相对本模式默认的变化，保留两PEQ和回混影响；不是当前音乐三段的能量。"))
        return LiveprogChartPair(left, right)
    }

    private fun routing(model: BassLinkedModel, title: String): LiveprogStaticPlot = when (model.mode) {
        1 -> LiveprogStaticPlot(title, "多相主路＋对齐低通支路", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .12, .5, 1),
                LiveprogNode("core", "原多相核心", .5, .18, 2),
                LiveprogNode("lp", "LP×${n(model.factor)}", .5, .82, 0),
                LiveprogNode("out", "对齐叠加", .88, .5, 0)),
            edges = listOf(LiveprogEdge("in", "core", "保留原polyphase处理；其响应未由本线性图复原", 2),
                LiveprogEdge("core", "out", "原核心输出", 2),
                LiveprogEdge("in", "lp", "低通${model.frequency}Hz/Q${model.q}，系数${model.factor}", 0),
                LiveprogEdge("lp", "out", "原waveBuffer按多相核心延迟对齐后叠加", 0)),
            notes = approximationNotes(model) + listOf("这里明确画出未建模多相核心，而不是把Pure Bass+画成自然低音的完整频响。缓冲延迟没有从参数猜测成具体毫秒。"))
        2 -> LiveprogStaticPlot(title, "0.5原声＋0.6差分低频", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .12, .18, 1),
                LiveprogNode("out", "相加输出", .88, .18, 0),
                LiveprogNode("eq", "深潜→冲击", .12, .78, 2),
                LiveprogNode("diff", "减去原声", .5, .78, 1),
                LiveprogNode("lp", "380Hz低通", .88, .78, 0)),
            edges = listOf(LiveprogEdge("in", "out", "原声×0.5", 1),
                LiveprogEdge("in", "eq", "深潜中心${model.deepHz}Hz/BW${model.deepWidth}；冲击中心${model.punchHz}Hz/BW${model.punchWidth}", 2),
                LiveprogEdge("eq", "diff", "两PEQ串联后再做差分", 2),
                LiveprogEdge("in", "diff", "减去未经过两PEQ的原声（−1×）", 1),
                LiveprogEdge("diff", "lp", "差分后低通380Hz/Q0.6", 0),
                LiveprogEdge("lp", "out", "低通结果×0.6，再加0.5原声", 0)),
            notes = listOf("原生公式y=0.5x＋0.6×LP380[(深潜PEQ×冲击PEQ)x−x]，所有减号、固定乘数和滤波位置保留。",
                "深潜${model.deepDb}dB与冲击${model.punchDb}dB只是两个PEQ内部目标；即使两峰参数都为0dB，这条结构也不是1×原声直通。"))
        else -> LiveprogStaticPlot(title, "原声保留＋低通增强", LiveprogPlotStyle.ROUTING,
            nodes = listOf(LiveprogNode("in", "原声输入", .12, .18, 1), LiveprogNode("out", "叠加输出", .88, .18, 0),
                LiveprogNode("lp", "LP ${n(model.frequency)}Hz", .12, .78, 2),
                LiveprogNode("gain", "增强×${n(model.factor)}", .88, .78, 0)),
            edges = listOf(LiveprogEdge("in", "out", "保留原声1×", 1),
                LiveprogEdge("in", "lp", "低通Q=${model.q}", 2), LiveprogEdge("lp", "gain", "只缩放低通支路", 0),
                LiveprogEdge("gain", "out", "复数幅相相加；不是dB相加", 0)),
            notes = listOf("自然低音稳态y=x＋LP(freq,Q)x×factor；低通不会从保留原声中删除高频，系数不代表整段音乐的实测增益。"))
    }

    private fun relativeBars(model: BassLinkedModel, defaults: BassLinkedModel, title: String,
        subtitle: String, probes: List<Double>, notes: List<String>): LiveprogStaticPlot {
        val differences = probes.map { frequency -> model.total(frequency).db() - defaults.total(frequency).db() }
        return LiveprogStaticPlot(title, "$subtitle · ${if (model.approximation) "局部对比" else "对比默认"}", LiveprogPlotStyle.BARS,
            yLabel = "变化dB", yMinimum = -60.0, yMaximum = 60.0,
            bars = probes.mapIndexed { index, frequency -> LiveprogBar("${frequency}Hz相对同参考模式默认", differences[index], index) },
            notes = notes + approximationNotes(model) + probes.mapIndexed { index, frequency ->
                "${index + 1}：${frequency}Hz，当前参考${model.total(frequency).db()}dB，默认参考${defaults.total(frequency).db()}dB，变化${differences[index]}dB。"
            } + "这是指定频点的模型增益差，不是音乐能量、音量百分比或主观听感断言；固定±60dB坐标，超界数值仍完整保留。")
    }

    private fun response(model: BassLinkedModel, title: String, subtitle: String,
        curves: List<LiveprogCurve>, notes: List<String>): LiveprogStaticPlot =
        LiveprogStaticPlot(title, subtitle, xLabel = "Hz", yLabel = "dB", xMinimum = 10.0, xMaximum = 500.0,
            yMinimum = if (model.mode == 2) -60.0 else -24.0,
            yMaximum = if (model.mode == 2) 54.0 else 24.0, logarithmicX = true,
            curves = curves, notes = notes + "纵轴固定；深衰减或超界部分按图框裁切，不擅自改变模型数值或自动补偿增益。")

    private fun sampled(label: String, frequencies: DoubleArray, values: DoubleArray, color: Int, dashed: Boolean = false) =
        LiveprogCurve(label, frequencies.indices.map { LiveprogPoint(frequencies[it], values[it]) }, color, dashed)

    private fun snapshot(source: BassLinkedModel, mode: Int, defaults: Boolean): BassLinkedModel {
        val values = linkedMapOf<String, Any>()
        p.viper.forEachIndexed { index, control -> values[control.key] = if (defaults) control.default else source.values[index] }
        values[MODE] = mode
        values["v_bass"] = source.enabled
        return BassLinkedModel.from(values, source.rate.takeIf { source.actualRate })
    }

    private fun approximationNotes(model: BassLinkedModel): List<String> = if (model.approximation) listOf(
        "Pure Bass+部分参考：现有BassLinkedModel用1＋LP×系数描述线性局部，省略原polyphase核心及其实际缓冲/相位响应，不能当作此模式完整频响。",
        "当前和默认线条/频点差都只对比这个明确限定的线性局部，不是完整Pure Bass+模式的音乐实测结果。") else emptyList()

    private fun n(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
