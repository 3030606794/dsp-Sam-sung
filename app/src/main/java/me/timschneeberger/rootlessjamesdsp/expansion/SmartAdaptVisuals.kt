package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import java.util.Locale
import kotlin.math.*

/** Parameter references and observed driver state are deliberately separate sources. */
internal object SmartAdaptVisuals {
    private val kind get() = ExpansionEffectsKind.SMART_ADAPT
    private const val RATE = 48000.0
    private val targets = listOf("自然", "人声", "节奏", "空间")
    private val timingKeys = setOf("analysis", "follow", "silence_hold")
    private val spaceKeys = setOf("width", "center")
    private val outputKeys = setOf("headroom", "trim")
    private fun v(values: Map<String, *>, name: String) = ExpansionEffectsParameters.raw(kind, values, name)
    private fun focus(key: String?) = ExpansionEffectsParameters.metadata(kind)
        .firstOrNull { it.control.key == key }?.logicalKey ?: "target"
    private fun target(values: Map<String, *>) = targets[v(values, "target").roundToInt().coerceIn(0, 3)]
    private fun n(value: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f", value)
    private fun percent(value: Double) = "${n(value * 100, 0)}%"
    private fun amounts(values: Map<String, *>) = listOf("low_amount", "mid_amount", "high_amount").map { v(values, it) }
    private fun level(values: Map<String, *>) = SmartAdaptIntensity.level(values)
    private data class Limits(val boost: Double, val cut: Double, val side: Double)

    /** No route observation is available to a static plot. These are policy/parameter ceilings. */
    private fun referenceLimits(values: Map<String, *>, multiplier: Double = level(values).multiplier): Limits {
        val speakerPolicy = v(values, "device").roundToInt() == 2
        return Limits(
            min(12.0, min(v(values, "max_boost"), if (speakerPolicy) 1.0 else 6.0) * multiplier),
            min(12.0, min(v(values, "max_cut"), if (speakerPolicy) 3.0 else 6.0) * multiplier),
            if (speakerPolicy) 0.0 else min(.6, v(values, "width") * multiplier))
    }
    private fun referenceNote(values: Map<String, *>) = if (v(values, "device").roundToInt() == 2)
        "按扬声器策略计算参数上界，已将基准上限与档位倍率相乘、限制到12 dB，再乘适配强度和频区处理量。静图不是音频测量。"
    else "参数上界按完整单路耳机条件参考，已将基准上限与档位倍率相乘、限制到12 dB，再乘适配强度和频区处理量；静图不知道实际输出，外放、未知或多路输出可能进一步收紧。实际范围以下方驱动读数为准。"

    /** The same RBJ shapes as native: S=1 shelves, Q=.7 middle, min(f, .4*rate). */
    internal fun filterDb(index: Int, gain: Double, frequency: Double, rate: Double = RATE): Double {
        if (gain == 0.0) return 0.0
        val center = min(doubleArrayOf(120.0, 1800.0, 6500.0)[index], .4 * rate)
        val w = 2 * PI * center / rate
        val c = cos(w)
        val a = 10.0.pow(gain / 40)
        val alpha = sin(w) / (if (index == 1) 1.4 else sqrt(2.0))
        val t = 2 * sqrt(a) * alpha
        val b: DoubleArray
        val d: DoubleArray
        when (index) {
            0 -> {
                b = doubleArrayOf(a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t))
                d = doubleArrayOf((a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t)
            }
            1 -> {
                b = doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a)
                d = doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
            }
            else -> {
                b = doubleArrayOf(a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t))
                d = doubleArrayOf((a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t)
            }
        }
        val phase = 2 * PI * frequency / rate
        fun power(z: DoubleArray): Double {
            val re = z[0] + z[1] * cos(phase) + z[2] * cos(2 * phase)
            val im = -z[1] * sin(phase) - z[2] * sin(2 * phase)
            return re * re + im * im
        }
        return 10 * log10((power(b) / power(d).coerceAtLeast(1e-24)).coerceAtLeast(1e-24))
    }

    /** Original Side is retained; only its one-pole highpassed part receives additional gain. */
    internal fun sideDb(frequency: Double, center: Double, extra: Double, rate: Double = RATE): Double {
        val a = exp(-2 * PI * center.coerceAtMost(.4 * rate) / rate)
        val w = 2 * PI * frequency / rate
        val re = 1 - a * cos(w)
        val im = a * sin(w)
        val den = re * re + im * im
        val lpRe = (1 - a) * re / den
        val lpIm = -(1 - a) * im / den
        return 20 * log10(hypot(1 + extra * (1 - lpRe), -extra * lpIm).coerceAtLeast(1e-12))
    }

    private fun plot(title: String, subtitle: String, xLabel: String, yLabel: String,
        minX: Double, maxX: Double, minY: Double, maxY: Double, curves: List<LiveprogCurve>,
        logX: Boolean = false, notes: List<String> = emptyList(), bars: List<LiveprogBar> = emptyList()): EffectVisual =
        EffectVisual(title, subtitle, plot = LiveprogStaticPlot(title, subtitle,
            xLabel = xLabel, yLabel = yLabel, xMinimum = minX, xMaximum = maxX,
            yMinimum = minY, yMaximum = maxY, logarithmicX = logX, curves = curves, bars = bars, notes = notes), notes = notes)

    fun static(values: Map<String, *>, focusKey: String?): EffectVisualPair {
        val key = focus(focusKey)
        val pair = when {
            key == "intensity" -> intensity(values)
            key in timingKeys -> timing(values, key)
            key in spaceKeys || (key == "target" && v(values, "target").roundToInt() == 3) -> space(values)
            key in outputKeys -> output(values)
            key == "transient" -> transient(values)
            key == "device" -> device(values)
            else -> tone(values, key)
        }
        val inactive = values[kind.enableKey] != true
        return EffectVisualPair(pair.professional.copy(inactive = inactive), pair.illustration.copy(inactive = inactive))
    }

    private fun tone(values: Map<String, *>, key: String): EffectVisualPair {
        val amounts = amounts(values)
        val strength = v(values, "strength")
        val limits = referenceLimits(values)
        val frequencies = List(97) { 20.0 * 1000.0.pow(it / 96.0) }
        fun curve(label: String, gain: Double, color: Int) = LiveprogCurve(label,
            frequencies.map { f -> LiveprogPoint(f, amounts.indices.sumOf { filterDb(it, gain * strength * amounts[it], f) }) }, color)
        val curves = listOf(curve("提升上界", limits.boost, 0), curve("削减下界", -limits.cut, 2))
        val points = curves.flatMap { it.points }
        val notes = listOf(referenceNote(values), "48 kHz 参数范围参考：低架120 Hz、中峰1800 Hz/Q0.7、高架6500 Hz。曲线将各频区同时置于当前档位、强度和处理量允许的增减上限，不是当前音乐的实际修正。",
            "手选目标决定规则方向；此图不预测歌曲分类。实际调整还受音频条件、设备策略、瞬态保护和输出余量限制，下方动图只显示驱动实际处理量。")
        val professional = plot("三频区边界", "${level(values).label} · ${percent(strength)}",
            "Hz", "修正 dB", 20.0, 20000.0,
            min(-1.0, floor(points.minOf { it.y })), max(1.0, ceil(points.maxOf { it.y })), curves, true, notes)
        val selected = listOf("low_amount", "mid_amount", "high_amount").indexOf(key)
        val illustration = EffectVisual("频区参与", "${target(values)} · 强度${percent(strength)}", scene = EffectScene(buildList {
            listOf("低频", "中频", "高频").forEachIndexed { i, label ->
                val y = .22 + i * .25
                add(FigureLabel(label, .02, y, .27, FigureAlign.LEFT, i))
                add(FigureBox(.34, y - .055, .96, y + .055, filled = false, colorIndex = i))
                if (amounts[i] * strength > 0) add(FigureBox(.34, y - .055, .34 + .62 * amounts[i] * strength, y + .055,
                    colorIndex = i, alpha = if (i == selected) .65 else .32))
                add(FigureLabel(percent(amounts[i] * strength), .96, y - .12, .62, FigureAlign.RIGHT, i))
            }
            add(FigureLabel("${target(values)}目标", .5, .94, .98))
        }), notes = listOf("长度为全局强度×该频区参与量，表示自动目标实际允许参与的比例；不是当前低中高音能量。目标由用户选择，不是模型识别结果。",
            "自然目标：温和均衡；人声目标：突出人声层次；节奏目标：保留节奏冲击；空间目标：适度扩展中高频空间。"))
        return EffectVisualPair(professional, illustration)
    }

    private fun intensity(values: Map<String, *>): EffectVisualPair {
        val selected = level(values)
        val strength = v(values, "strength")
        val participation = amounts(values)
        val scale = strength * participation.max()
        val limits = SmartAdaptIntensity.levels.map { referenceLimits(values, it.multiplier) }
        val boost = limits.map { it.boost * scale }
        val cut = limits.map { it.cut * scale }
        val current = SmartAdaptIntensity.levels.indexOf(selected)
        val notes = listOf(referenceNote(values),
            "1轻柔、2标准、3增强、4强劲、5极强，倍率依次1、1.75、2.75、4、6。曲线表示参与频区中的最大允许修正，并非把整首音乐统一加大音量；触及12 dB上限后继续升档可能不再增加该上界。",
            listOf("低", "中", "高").mapIndexed { i, name ->
                "$name −${n(limits[current].cut * strength * participation[i])}～+${n(limits[current].boost * strength * participation[i])} dB"
            }.joinToString("；") + "。瞬态保护和平滑会影响实际修正，实时图不以这些参考值代替测量。")
        val professional = plot("五档修正范围", "${selected.label} · ${percent(strength)}", "档位", "修正 dB",
            1.0, 5.0, min(-1.0, -ceil(cut.max())), max(1.0, ceil(boost.max())),
            listOf(LiveprogCurve("提升界", boost.mapIndexed { i, y -> LiveprogPoint(i + 1.0, y) }),
                LiveprogCurve("衰减界", cut.mapIndexed { i, y -> LiveprogPoint(i + 1.0, -y) }, 2)), notes = notes)
        val marked = professional.copy(plot = professional.plot!!.copy(marks = listOf(
            LiveprogMark(current + 1.0, boost[current]), LiveprogMark(current + 1.0, -cut[current], colorIndex = 2))))
        val illustration = EffectVisual("力度范围示意", "${selected.label} · 参数参考", scene = EffectScene(buildList {
            SmartAdaptIntensity.levels.forEachIndexed { i, item ->
                val y = .12 + i * .17
                add(FigureLabel(item.label, .02, y, .27, FigureAlign.LEFT, emphasis = i == current))
                add(FigureLine(.34, y, .96, y, colorIndex = 2, alpha = .2))
                add(FigureLine(.65, y - .055, .65, y + .055, colorIndex = 2, alpha = .6))
                if (cut[i] > 0) add(FigureBox(.65 - .31 * cut[i] / 12, y - .04, .65, y + .04,
                    colorIndex = 2, alpha = if (i == current) .7 else .3))
                if (boost[i] > 0) add(FigureBox(.65, y - .04, .65 + .31 * boost[i] / 12, y + .04,
                    alpha = if (i == current) .7 else .3))
            }
            add(FigureLabel("−  0  +", .65, .97, .64))
        }), notes = listOf("横条表示已乘强度与最大频区处理量的允许增减范围，统一按±12 dB比例绘制，突出当前档位。它说明调节幅度，不是音量计、听感评分或五种已识别曲风。") + notes)
        return EffectVisualPair(marked, illustration)
    }

    private fun timing(values: Map<String, *>, key: String): EffectVisualPair {
        val labels = listOf("分析", "跟随", "保持")
        val keys = listOf("analysis", "follow", "silence_hold")
        val seconds = keys.map { v(values, it) / 1000 }
        val notes = listOf("三个时间分别是有效音频分析窗口、参数平滑跟随时间、静音保持时间，作用于不同阶段，不相加为总延迟。这里只显示所设时长，不生成虚构的声音包络。")
        val professional = plot("观察、跟随与静音", "当前：${labels[keys.indexOf(key)]}", "阶段", "秒",
            0.0, 3.0, 0.0, max(1.0, seconds.max()), emptyList(), notes = notes,
            bars = seconds.mapIndexed { i, time -> LiveprogBar(labels[i], time, i) })
        val illustration = EffectVisual("适配过程", "阶段说明 · 非音频延迟", scene = EffectScene(listOf(
            FigureBox(.05, .04, .95, .23, "观察 ${n(seconds[0])} s"),
            FigureLine(.5, .25, .5, .34, arrow = true),
            FigureBox(.05, .36, .95, .57, "跟随 ${n(seconds[1])} s", colorIndex = 1),
            FigureLine(.5, .59, .5, .68, colorIndex = 2, arrow = true),
            FigureBox(.05, .7, .95, .91, "静音保持 ${n(seconds[2])} s", colorIndex = 2)
        )), notes = notes)
        return EffectVisualPair(professional, illustration)
    }

    private fun space(values: Map<String, *>): EffectVisualPair {
        val selected = v(values, "target").roundToInt() == 3
        val extra = if (selected) referenceLimits(values).side * v(values, "strength") else 0.0
        val center = v(values, "center")
        val frequencies = List(97) { 20.0 * 1000.0.pow(it / 96.0) }
        val notes = listOf("${level(values).label}档参数参考：额外侧增益先乘档位倍率、限制到60%，再乘适配强度；扬声器策略不增宽。实际外放或多路输出也不额外增宽，静图不能确认实际路由。",
            "48 kHz 侧声道上限参考：Side_out=Side+额外增益×(Side−一阶低通Side)。低通系数exp(−2π×中心频率/采样率)，原有低频声像不会被清除。",
            "只有手选空间目标才授权额外侧增益，实际还受媒体输出设备、相关度与保护规则限制。这不是当前声像测量，也不是头部跟踪。")
        val professional = plot("侧声道响应", "${center.toInt()} Hz · 上限${percent(extra)}", "Hz", "侧增益 dB",
            20.0, 20000.0, 0.0, max(1.0, ceil(20 * log10(1 + extra))),
            listOf(LiveprogCurve("允许侧增益", frequencies.map { LiveprogPoint(it, sideDb(it, center, extra)) })), true, notes)
        val spread = .14 + extra * .32
        val illustration = EffectVisual("声像示意", if (selected) "空间上限示意" else "当前不增宽", scene = EffectScene(listOf(
            FigureLine(.08, .7, .92, .7, colorIndex = 2),
            FigureCircle(.5, .68, .055, .06, filled = true, alpha = .5),
            FigureLabel("中心", .5, .87, .55),
            FigureArc(.5, .67, spread, .38, 200.0, 140.0, colorIndex = 1),
            FigureCircle(.5 - spread, .35, .045, .06, colorIndex = 1, filled = true, alpha = .5),
            FigureCircle(.5 + spread, .35, .045, .06, colorIndex = 1, filled = true, alpha = .5),
            FigureLabel("${center.toInt()} Hz 低频保护", .5, .08, .98),
            FigureLabel("两侧", .5, .45, .6, colorIndex = 1)
        )), notes = notes)
        return EffectVisualPair(professional, illustration)
    }

    private fun transient(values: Map<String, *>): EffectVisualPair {
        val amount = v(values, "transient") * 100
        val professional = plot("瞬态保护设置", "配置强度 · 不是检测概率", "参数", "%", 0.0, 1.0, 0.0, 100.0,
            emptyList(), bars = listOf(LiveprogBar("保护", amount)),
            notes = listOf("此柱显示保护设置；是否触发及实际保护多少取决于原始音频包络。下方显示音频处理器返回的实际保护量。"))
        val illustration = EffectVisual("短促声与持续声", "保护快变化 · 缓慢调音色", scene = EffectScene(listOf(
            FigureBox(.05, .08, .95, .29, "短促起音", colorIndex = 2),
            FigureLine(.5, .31, .5, .41, colorIndex = 2, arrow = true),
            FigureBox(.05, .43, .95, .64, "保护冲击轮廓", colorIndex = 2, alpha = .1 + amount / 250),
            FigureLabel("持续音色仍可适配", .5, .87, .98)
        )), notes = professional.notes)
        return EffectVisualPair(professional, illustration)
    }

    private fun output(values: Map<String, *>): EffectVisualPair {
        val active = v(values, "strength") > 0
        val trim = if (active) v(values, "trim") else 0.0
        val headroom = v(values, "headroom") > .5
        val professional = plot("输出微调参考", if (active) "自动余量另由音频决定" else "强度0：最终完全旁路",
            "Hz", "dB", 20.0, 20000.0, -12.0, 1.0,
            listOf(LiveprogCurve("手动微调", listOf(LiveprogPoint(20.0, trim), LiveprogPoint(20000.0, trim)))), true,
            listOf("只画用户设置的输出微调。自动余量根据本模块增益预算与音频条件衰减，不能预先假定一个值；下方读取实际衰减。它不是LUFS响度归一化或真峰值限制器。"))
        val illustration = EffectVisual("增益预算与输出", if (headroom) "自动余量已授权" else "自动余量已关闭", scene = EffectScene(listOf(
            FigureBox(.08, .04, .92, .24, "三频区适配"), FigureLine(.5, .26, .5, .34, arrow = true),
            FigureBox(.08, .36, .92, .58, if (headroom) "自动保留余量" else "不自动衰减", colorIndex = 2),
            FigureLine(.5, .6, .5, .68, arrow = true),
            FigureBox(.08, .7, .92, .91, "微调 ${n(trim)} dB", colorIndex = 1)
        )), notes = professional.notes)
        return EffectVisualPair(professional, illustration)
    }

    private fun device(values: Map<String, *>): EffectVisualPair {
        val policy = listOf("自动策略", "耳机策略", "扬声器策略")[v(values, "device").roundToInt().coerceIn(0, 2)]
        val notes = listOf("此项选择处理策略，不会伪造媒体实际输出设备。实际路由决定基准限制，再由力度档位、12 dB硬上限和适配强度共同决定范围；实际路由和允许增益以下方驱动状态为准，不是实测设备校准。")
        return EffectVisualPair(
            EffectVisual("策略与真实输出", policy, scene = EffectScene(listOf(
                FigureBox(.04, .04, .96, .24, "手选处理策略"), FigureLine(.5, .26, .5, .35, arrow = true),
                FigureBox(.04, .37, .96, .58, "核对媒体输出", colorIndex = 1), FigureLine(.5, .6, .5, .69, arrow = true),
                FigureBox(.04, .71, .96, .93, "核算当前允许范围", colorIndex = 2)
            )), notes = notes),
            EffectVisual("设备策略示意", "$policy · 非设备测量", scene = EffectScene(listOf(
                FigureBox(.12, .13, .39, .63, filled = false), FigureCircle(.255, .46, .075, .09),
                FigureArc(.74, .42, .17, .27, 180.0, 180.0, colorIndex = 1),
                FigureBox(.55, .4, .63, .62, colorIndex = 1), FigureBox(.85, .4, .93, .62, colorIndex = 1),
                FigureLabel("外放", .255, .77, .4), FigureLabel("耳机", .74, .77, .4, colorIndex = 1),
                FigureLabel("实际路由看实时状态", .5, .95, .98)
            )), notes = notes))
    }

    internal fun phaseName(phase: Int) = when (phase) {
        0 -> "已关闭"; 1 -> "等待音频"; 2 -> "分析积累"; 3 -> "自动跟随"; 4 -> "已锁定目标"; 5 -> "静音保持／归中"; 6 -> "设备策略适配"; else -> "状态未就绪"
    }
    internal fun routeName(route: Int) = when (route) { 1 -> "内置扬声器"; 2 -> "耳机等单路输出"; 3 -> "多路输出"; else -> "输出待确认" }

    fun live(values: Map<String, *>, focusKey: String?, sample: ExpansionEffectsRuntime,
        history: List<EffectVisualFrame>): EffectVisual {
        val key = focus(focusKey)
        val reason = when {
            !sample.enabled -> "智能适配已关闭"
            !sample.isProcessing -> "等待活动音频"
            sample.meters.size < 30 || sample.meters.take(30).any { !it.isFinite() } -> "等待完整有效读数"
            sample.meter(0).roundToInt() !in 0..6 -> "等待有效适配状态"
            sample.meter(28) !in 0f..4f || sample.meter(29) !in 1f..6f -> "等待有效力度状态"
            sample.meter(0).roundToInt() == 0 -> "智能适配正在旁路"
            sample.meter(0).roundToInt() == 1 -> "等待有效音频积累"
            else -> null
        }
        if (reason != null) return EffectVisual("实际适配状态", "等待有效音频", scene = EffectScene(listOf(
            FigureLabel(reason, .5, .42, .98, emphasis = true),
            FigureLabel("收到有效读数后绘图", .5, .69, .98)
        )), notes = listOf("实时曲线仅采用驱动读数。等待、失效或断流时不以设置值代替实际结果。"))
        val phase = sample.meter(0).roundToInt()
        val status = phaseName(phase)
        val strength = v(values, "strength")
        val participation = amounts(values)
        val evidence = "时间轴只包含已确认的驱动采样；配置改变、采样中断和无效读数会断开曲线，不补画缺失的数据。"
        return when {
            phase == 2 || key == "analysis" || key == "silence_hold" -> historyPlot("有效音频积累", status,
                "秒", 0.0, max(1.0, max(v(values, "analysis") / 1000, sample.meter(1) / 1000.0)), history,
                listOf(Series("已积累", 1, 1.0 / 1000)), listOf(evidence,
                    "积累量是实际有效音频时长，不是置信度。分析窗口${n(v(values, "analysis") / 1000)}秒；静音保持${n(v(values, "silence_hold") / 1000)}秒。阶段状态由驱动决定。"))
            key in spaceKeys || (key == "target" && v(values, "target").roundToInt() == 3) -> historyPlot("实际侧声道增益", status,
                "额外 %", 0.0, 60.0, history, listOf(Series("额外侧增益", 5, 100.0)),
                listOf(evidence, "值为实际额外侧声道增益，0表示不额外增宽。当前原始左右相关度${n(sample.meter(11).toDouble(), 2)}，范围[-1,1]，不是识别概率。"))
            key == "transient" -> historyPlot("实际瞬态保护", status, "保护 %", 0.0, 100.0, history,
                listOf(Series("实际保护", 15, 100.0)), listOf(evidence, "保护量来自音频处理器，未根据滑杆设置伪造瞬态事件。"))
            key in outputKeys -> historyPlot("实际自动余量衰减", status, "dB", min(-12.0, sample.meter(6).toDouble()), 0.0,
                history, listOf(Series("余量衰减", 6)), listOf(evidence, "显示本模块实际自动衰减；手动输出微调为${n(v(values, "trim"))}dB。不是响度或真峰值测量。"))
            key == "device" -> historyPlot("当前允许增减范围", status, "dB", -1.0, 1.0, history,
                listOf(Series("允许提升", 24, strength), Series("允许衰减", 25, -strength, 2)),
                listOf(evidence, "驱动上限已包含实际路由、档位与12 dB限制，此处再乘当前适配强度；各频区还乘各自处理量，瞬态保护可进一步减少修正。它不是已经施加的增益；未知或多路输出不会伪报成耳机。"))
            key == "intensity" -> historyPlot("实际修正与范围", status, "修正 dB", -1.0, 1.0, history,
                listOf(Series("低频", 2), Series("中频", 3, color = 1), Series("高频", 4, color = 2),
                    Series("提升界", 24, strength * participation.max(), 0, dashed = true),
                    Series("衰减界", 25, -strength * participation.max(), 2, dashed = true)),
                listOf(evidence, "${SmartAdaptIntensity.level(sample.meter(28).roundToInt() * 1000).label}档：实线为实际平滑修正，虚线为驱动确认的全频区最大目标范围，已乘强度与最大频区处理量，不再重复乘档位倍率。",
                    listOf("低", "中", "高").mapIndexed { i, name ->
                        "$name −${n(sample.meter(25) * strength * participation[i])}～+${n(sample.meter(24) * strength * participation[i])} dB"
                    }.joinToString("；") + "。收紧档位、强度或设备限额时，实际曲线会平滑回到新目标范围；瞬态保护继续生效。"))
            else -> historyPlot(if (key == "follow") "三频区平滑跟随" else "实际低中高频修正", status,
                "修正 dB", -6.0, 6.0, history,
                listOf(Series("低频", 2), Series("中频", 3, color = 1), Series("高频", 4, color = 2)),
                listOf(evidence, "显示实际平滑后的三个增益。锁定只固定自动目标；强度、保护和输出余量仍有真实作用。${target(values)}是用户选择，不是识别结果。"))
        }
    }

    private data class Series(val name: String, val meter: Int, val scale: Double = 1.0, val color: Int = 0, val dashed: Boolean = false)

    /** Missing/invalid frames create gaps; no current sample is stretched backwards into history. */
    private fun historyPlot(title: String, subtitle: String, unit: String, lower: Double, upper: Double,
        history: List<EffectVisualFrame>, series: List<Series>, notes: List<String>): EffectVisual {
        val end = history.lastOrNull()?.timeMs ?: 0L
        val curves = series.flatMap { selected ->
            val segments = mutableListOf<LiveprogCurve>()
            var points = mutableListOf<LiveprogPoint>()
            fun flush() {
                if (points.isNotEmpty()) {
                    segments += LiveprogCurve(if (segments.isEmpty()) selected.name else "", points.toList(), selected.color, selected.dashed)
                    points = mutableListOf()
                }
            }
            history.forEach { frame ->
                val phase = frame.meters.getOrNull(0)?.takeIf { it.isFinite() }?.roundToInt()
                val reading = frame.meters.getOrNull(selected.meter)?.toDouble()
                if (frame.split) flush()
                if (phase == null || phase !in 2..6 || reading == null || !reading.isFinite() || frame.timeMs < end - 20000) flush()
                else points += LiveprogPoint((frame.timeMs - end) / 1000.0, reading * selected.scale)
            }
            flush(); segments
        }
        val actual = curves.flatMap { it.points }.map { it.y }
        val low = min(lower, actual.minOrNull() ?: lower)
        val high = max(upper, actual.maxOrNull() ?: upper)
        return plot(title, subtitle, "最近 s", unit, -20.0, 0.0, low, max(high, low + .001), curves, notes = notes)
    }
}
