package me.timschneeberger.rootlessjamesdsp.peq.importer

import kotlin.math.ln
import kotlin.math.ln1p

data class GraphicEqPoint(val frequency: Double, val gainDb: Double)

/** The points retain the file's gains; [gainAt] includes its optional preamp. */
data class GraphicEqCurve(val points: List<GraphicEqPoint>, val preampDb: Double = 0.0) {
    init {
        require(points.size in 2..GraphicEqParser.MAX_POINTS) { "至少需要 2 个不同频率，最多支持 ${GraphicEqParser.MAX_POINTS} 个控制点" }
        require(preampDb.isFinite() && preampDb in -100.0..100.0) { "前置增益必须在 -100 到 +100 dB 之间" }
        points.forEachIndexed { index, point ->
            require(point.frequency.isFinite() && point.frequency > 0.0 && point.frequency <= 768000.0) { "频率必须大于 0 且不超过 768000 Hz" }
            require(point.gainDb.isFinite() && point.gainDb in -100.0..100.0 &&
                point.gainDb + preampDb in -100.0..100.0) { "增益及叠加前置增益后的电平必须在 -100 到 +100 dB 之间" }
            require(index == 0 || points[index - 1].frequency < point.frequency) { "控制点频率必须严格递增" }
        }
    }

    /** Equalizer APO GraphicEQ: linear dB interpolation on log Hz, flat beyond endpoints. */
    fun gainAt(frequency: Double): Double {
        require(frequency.isFinite() && frequency > 0.0) { "查询频率必须为正有限数" }
        if (frequency <= points.first().frequency) return points.first().gainDb + preampDb
        if (frequency >= points.last().frequency) return points.last().gainDb + preampDb
        var low = 0
        var high = points.lastIndex
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (points[middle].frequency <= frequency) low = middle else high = middle
        }
        val left = points[low]
        val right = points[high]
        val fraction = logRatio(frequency, left.frequency) / logRatio(right.frequency, left.frequency)
        return left.gainDb + fraction * (right.gainDb - left.gainDb) + preampDb
    }

    private fun logRatio(upper: Double, lower: Double): Double {
        val relative = (upper - lower) / lower
        // log1p retains adjacent representable frequencies; the fallback avoids overflow.
        return if (relative.isFinite()) ln1p(relative) else ln(upper) - ln(lower)
    }
}

object GraphicEqParser {
    const val MAX_INPUT_BYTES = 256 * 1024
    const val MAX_POINTS = 4096
    private val graphicHeader = Regex("^GraphicEQ\\s*:", RegexOption.IGNORE_CASE)
    private val preampHeader = Regex("^Preamp\\s*:", RegexOption.IGNORE_CASE)
    private val preampValue = Regex("^([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*dB$", RegexOption.IGNORE_CASE)

    fun parse(text: String): GraphicEqCurve {
        require(text.length <= MAX_INPUT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "文本不能超过 256 KiB" }
        require(text.none { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }) { "文本含有不支持的控制字符" }
        var foundGraphic = false
        var foundPreamp = false
        var preamp = 0.0
        val body = StringBuilder()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, original ->
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            when {
                graphicHeader.containsMatchIn(line) -> {
                    require(!foundGraphic) { "第 ${index + 1} 行：只支持一条 GraphicEQ 曲线" }
                    foundGraphic = true
                    body.append(line.substringAfter(':')).append('\n')
                }
                preampHeader.containsMatchIn(line) -> {
                    require(!foundPreamp) { "第 ${index + 1} 行：Preamp 不能重复" }
                    foundPreamp = true
                    val match = preampValue.matchEntire(line.substringAfter(':').trim())
                        ?: throw IllegalArgumentException("第 ${index + 1} 行：前置增益格式应为 Preamp: -3 dB")
                    preamp = match.groupValues[1].toDoubleOrNull()
                        ?: throw IllegalArgumentException("第 ${index + 1} 行：前置增益不是有效数字")
                }
                ':' in line || !foundGraphic -> throw IllegalArgumentException("第 ${index + 1} 行：不支持的指令，只接受 GraphicEQ 和可选 Preamp")
                else -> body.append(line).append('\n')
            }
        }
        require(foundGraphic) { "没有找到 GraphicEQ: 频率 增益 控制点" }
        val tokens = body.toString().trim().split(Regex("[;\\s]+")).filter { it.isNotEmpty() }
        require(tokens.size >= 4 && tokens.size % 2 == 0) { "每个控制点必须包含一组频率和增益，至少需要两组" }
        require(tokens.size / 2 <= MAX_POINTS) { "控制点不能超过 $MAX_POINTS 个" }
        val byFrequency = sortedMapOf<Double, Double>()
        for (index in tokens.indices step 2) {
            val frequency = tokens[index].toDoubleOrNull()
                ?: throw IllegalArgumentException("第 ${index / 2 + 1} 个控制点：频率不是有效数字")
            val gain = tokens[index + 1].toDoubleOrNull()
                ?: throw IllegalArgumentException("第 ${index / 2 + 1} 个控制点：增益不是有效数字")
            require(frequency.isFinite() && gain.isFinite()) { "控制点不允许 NaN 或无穷大" }
            val previous = byFrequency.put(frequency, gain)
            require(previous == null || previous == gain) { "$frequency Hz 出现冲突增益，请保留一个控制点" }
        }
        return GraphicEqCurve(byFrequency.map { GraphicEqPoint(it.key, it.value) }, preamp)
    }
}
