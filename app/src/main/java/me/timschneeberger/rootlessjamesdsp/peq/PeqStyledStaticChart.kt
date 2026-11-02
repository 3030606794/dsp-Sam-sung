package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogCurve
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Five PEQ presentation styles. Geometry consumes model points verbatim, never filter math. */
internal class PeqStyledStaticChart(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val surfaceInk = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val themePrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val darkTheme = ColorUtils.calculateLuminance(surface) < .5
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * d)
        fontFeatureSettings = "tnum"
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panel = RectF()
    private val graph = RectF()
    private val labelBox = RectF()
    private val dash = DashPathEffect(floatArrayOf(3.5f * d, 2.5f * d), 0f)
    private val fineDash = DashPathEffect(floatArrayOf(2f * d, 3f * d), 0f)
    private var plot = LiveprogStaticPlot("参数均衡器", "等待当前频段", LiveprogPlotStyle.TEXT)
    private var style = 0
    private var active = false
    private var pathsDirty = true
    private data class Drawing(val source: LiveprogCurve, val line: Path, val area: Path, val zeroOnly: Boolean)
    private var drawings = emptyList<Drawing>()
    private data class Palette(val background: Int, val ink: Int, val muted: Int, val grid: Int,
        val border: Int, val colors: List<Int>, val positive: Int, val negative: Int)

    init {
        isLongClickable = true
        setOnLongClickListener {
            GlassAlertDialogBuilder(context).setTitle(plot.title).setMessage(details())
                .setPositiveButton(android.R.string.ok, null).show()
            true
        }
    }

    fun setPlot(plot: LiveprogStaticPlot) {
        this.plot = plot
        pathsDirty = true
        contentDescription = details()
        if (active && isAttachedToWindow) invalidate()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value && isAttachedToWindow) invalidate()
    }

    fun setChartStyle(value: Int) {
        val next = value.coerceIn(0, 4)
        if (style == next) return
        style = next
        // Style changes preserve the exact same graph bounds, points, scales and text size.
        if (active && isAttachedToWindow) invalidate()
    }

    private fun palette(): Palette {
        if (style == 1) return Palette(0xff111b2d.toInt(), 0xffe0e8f4.toInt(), 0xff9aaac1.toInt(),
            0xff2b3b53.toInt(), 0xff405574.toInt(), listOf(0xffc1adff.toInt(), 0xff61d8e7.toInt(),
                0xffffc17e.toInt()), 0xffb9a0ff.toInt(), 0xff63d8df.toInt())
        val lightPrimary = if (ColorUtils.calculateLuminance(themePrimary) > .45)
            ColorUtils.blendARGB(themePrimary, 0xff49376f.toInt(), .55f) else themePrimary
        if (style == 0) return Palette(0xfff6f5fc.toInt(), 0xff333044.toInt(), 0xff706a80.toInt(),
            0xffddd9e9.toInt(), 0xffd2ccdf.toInt(), listOf(lightPrimary, 0xff168391.toInt(),
                0xffb16a27.toInt()), lightPrimary, 0xff168391.toInt())
        val background = if (darkTheme) 0xff1b1d25.toInt() else 0xfffcfcff.toInt()
        val ink = if (darkTheme) 0xffe1e2ec.toInt() else surfaceInk
        val colors = listOf(if (darkTheme) 0xffc4b0ff.toInt() else lightPrimary,
            if (darkTheme) 0xff77d7e2.toInt() else 0xff147c8a.toInt(),
            if (darkTheme) 0xffffc47e.toInt() else 0xffad6526.toInt())
        return Palette(background, ink, ColorUtils.blendARGB(ink, background, .4f),
            ColorUtils.blendARGB(ink, background, .84f), ColorUtils.blendARGB(ink, background, .72f), colors,
            if (darkTheme) 0xffffbd81.toInt() else 0xffbf682d.toInt(),
            if (darkTheme) 0xff77d2ed.toInt() else 0xff147f9e.toInt())
    }

    private fun tint(color: Int, alpha: Int): Int = ColorUtils.setAlphaComponent(color,
        if (plot.inactive) min(alpha, 85) else alpha)
    private fun curveColor(drawing: Drawing, palette: Palette): Int =
        if (plot.inactive || drawing.source.dashed) palette.muted else
            palette.colors[Math.floorMod(drawing.source.colorIndex, palette.colors.size)]
    private fun rawText(canvas: Canvas, value: String, x: Float, baseline: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT) {
        text.color = color; text.textAlign = align
        canvas.drawText(value, x, baseline, text)
    }
    private fun boundedText(canvas: Canvas, value: String, x: Float, baseline: Float, space: Float, color: Int) {
        if (space <= 0) return
        if (text.measureText(value) <= space) { rawText(canvas, value, x, baseline, color); return }
        val suffix = "…"
        val remaining = space - text.measureText(suffix)
        if (remaining < 0) return
        var end = text.breakText(value, true, remaining, null)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1])) end--
        rawText(canvas, value.take(end) + suffix, x, baseline, color)
    }
    private fun topBaseline(top: Float): Float = top - text.fontMetrics.top
    private fun number(value: Double, precision: Int = 3): String {
        if (!value.isFinite()) return "—"
        if (value == 0.0) return "0"
        return String.format(Locale.ROOT, "%.${precision}g", value).let {
            if ('.' in it && 'e' !in it && 'E' !in it) it.trimEnd('0').trimEnd('.') else it
        }.replace('-', '−')
    }
    private fun axisNumber(value: Double): String = if (abs(value) >= 1000.0)
        "${number(value / 1000.0)}k" else number(value)
    private fun x(value: Double): Float {
        val fraction = if (plot.logarithmicX && plot.xMinimum > 0 && value > 0)
            ln(value / plot.xMinimum) / ln(plot.xMaximum / plot.xMinimum)
        else (value - plot.xMinimum) / (plot.xMaximum - plot.xMinimum)
        return graph.left + (fraction * graph.width()).toFloat()
    }
    private fun y(value: Double): Float = graph.bottom -
        ((value - plot.yMinimum) / (plot.yMaximum - plot.yMinimum) * graph.height()).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathsDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val colors = palette()
        panel.set(d, d, width - d, height - d)
        fill.shader = null; fill.color = colors.background
        canvas.drawRoundRect(panel, 7f * d, 7f * d, fill)
        stroke.shader = null; stroke.color = colors.border; stroke.strokeWidth = .65f * d; stroke.pathEffect = null
        canvas.drawRoundRect(panel, 7f * d, 7f * d, stroke)
        val lineHeight = text.fontMetrics.bottom - text.fontMetrics.top
        // Two reserved caption rows stop changing digit lengths from moving the graph vertically.
        val captionTop = 4f * d
        val legendTop = captionTop + lineHeight * 2f + 2f * d
        graph.set(29f * d, legendTop + lineHeight + 5f * d, width - 8f * d,
            height - lineHeight - 9f * d)
        drawCaption(canvas, colors, captionTop, lineHeight)
        if (graph.width() < 35f * d || graph.height() < lineHeight * 1.7f) return
        if (plot.style == LiveprogPlotStyle.TEXT) {
            rawText(canvas, "参数模型", graph.centerX(), graph.centerY(), colors.muted, Paint.Align.CENTER)
            return
        }
        if (!plot.xMinimum.isFinite() || !plot.xMaximum.isFinite() || plot.xMinimum >= plot.xMaximum ||
            !plot.yMinimum.isFinite() || !plot.yMaximum.isFinite() || plot.yMinimum >= plot.yMaximum) return
        if (pathsDirty) rebuildPaths()
        drawLegend(canvas, colors, legendTop, lineHeight)
        drawGrid(canvas, colors)
        canvas.save(); canvas.clipRect(graph)
        drawRegions(canvas, colors)
        if (style == 0 || style == 1 || style == 3) drawAreas(canvas, colors)
        if (style == 4) drawBandwidth(canvas, colors)
        drawLines(canvas, colors)
        canvas.restore()
        // A center exactly on the upper scale still has a complete marker, not a clipped half-dot.
        canvas.save(); canvas.clipRect(panel)
        drawMarks(canvas, colors)
        canvas.restore()
        drawAxes(canvas, colors, lineHeight)
    }

    private fun wrap(value: String, space: Float): List<String> {
        if (value.isBlank() || space <= 0) return emptyList()
        val output = ArrayList<String>(2)
        var rest = value.trim()
        repeat(2) {
            if (rest.isEmpty()) return@repeat
            var count = text.breakText(rest, true, space, null)
            if (count == 0) return@repeat
            if (count < rest.length && Character.isHighSurrogate(rest[count - 1])) count--
            if (count == 0) return@repeat
            val prefix = rest.take(count)
            val split = prefix.lastIndexOf(' ').takeIf { count < rest.length && it > count / 2 }
            val end = split ?: count
            output.add(rest.take(end).trim())
            rest = rest.drop(end).trim()
        }
        return output
    }

    private fun drawCaption(canvas: Canvas, colors: Palette, top: Float, lineHeight: Float) {
        val rows = wrap(plot.subtitle, width - 12f * d)
        rows.forEachIndexed { index, value -> rawText(canvas, value, 6f * d,
            topBaseline(top + index * lineHeight), if (plot.inactive) colors.muted else colors.ink) }
        if (rows.size < 2) {
            val info = when {
                plot.inactive -> "关闭预览"
                style == 4 && plot.regions.isEmpty() -> "无定义带宽"
                style == 4 -> "已定义作用区 · 中心与边界"
                style == 3 -> "暖：增强 · 冷：削减"
                style == 2 -> plot.marks.firstOrNull { it.x.isFinite() && it.y.isFinite() }?.let {
                    "标记 ${number(it.y, 5)} ${plot.yLabel}"
                }.orEmpty()
                else -> ""
            }
            if (info.isNotBlank()) boundedText(canvas, info, 6f * d, topBaseline(top + lineHeight),
                width - 12f * d, colors.muted)
        }
    }

    private fun drawLegend(canvas: Canvas, colors: Palette, top: Float, lineHeight: Float) {
        rawText(canvas, plot.yLabel, 5f * d, topBaseline(top), colors.muted)
        val items = drawings.take(3)
        if (items.isEmpty()) return
        val slot = graph.width() / items.size
        items.forEachIndexed { index, drawing ->
            val left = graph.left + slot * index
            val baseline = topBaseline(top)
            val color = if (style == 3 && !plot.inactive && !drawing.source.dashed) colors.ink else curveColor(drawing, colors)
            stroke.color = color; stroke.strokeWidth = if (style == 2) d else 1.5f * d
            stroke.pathEffect = if (plot.inactive || drawing.source.dashed || style == 3 && index > 0) dash else null
            canvas.drawLine(left, baseline - lineHeight * .28f, left + 9f * d, baseline - lineHeight * .28f, stroke)
            stroke.pathEffect = null
            // The PEQ model supplies short labels (total/current or one filter type), not long prose.
            boundedText(canvas, drawing.source.label, left + 12f * d, baseline, slot - 14f * d, color)
        }
    }

    private fun drawGrid(canvas: Canvas, colors: Palette) {
        stroke.shader = null; stroke.pathEffect = null
        val divisions = if (style == 1) 8 else if (style == 2) 4 else 4
        repeat(divisions + 1) { index ->
            val yy = graph.top + graph.height() * index / divisions
            stroke.color = tint(colors.grid, if (style == 1 && index % 2 != 0) 105 else 210)
            stroke.strokeWidth = if (index % 2 == 0) .6f * d else .4f * d
            canvas.drawLine(graph.left, yy, graph.right, yy, stroke)
        }
        if (plot.logarithmicX && plot.xMinimum > 0) {
            val first = floor(log10(plot.xMinimum)).toInt()
            val last = ceil(log10(plot.xMaximum)).toInt()
            for (decade in first..last) {
                val factor = 10.0.pow(decade)
                val multipliers = if (style == 1 || style == 2) listOf(1.0, 2.0, 5.0) else listOf(1.0, 5.0)
                multipliers.forEach { multiplier ->
                    val frequency = factor * multiplier
                    if (frequency > plot.xMinimum && frequency < plot.xMaximum) {
                        stroke.color = tint(colors.grid, if (multiplier == 1.0) 225 else 125)
                        stroke.strokeWidth = if (multiplier == 1.0) .7f * d else .4f * d
                        val xx = x(frequency); canvas.drawLine(xx, graph.top, xx, graph.bottom, stroke)
                    }
                }
            }
        } else repeat(if (style == 1) 9 else 5) { index ->
            val count = if (style == 1) 8 else 4
            val xx = graph.left + graph.width() * index / count
            stroke.color = tint(colors.grid, if (index % 2 == 0) 210 else 120)
            stroke.strokeWidth = .5f * d; canvas.drawLine(xx, graph.top, xx, graph.bottom, stroke)
        }
        if (plot.yMinimum <= 0 && plot.yMaximum >= 0) {
            stroke.color = if (style == 3) colors.ink else tint(colors.ink, 110)
            stroke.strokeWidth = if (style == 3) 1.1f * d else .75f * d
            canvas.drawLine(graph.left, y(0.0), graph.right, y(0.0), stroke)
        }
        stroke.color = tint(colors.border, 230); stroke.strokeWidth = .65f * d
        canvas.drawRect(graph, stroke)
    }

    private fun rebuildPaths() {
        pathsDirty = false
        val zero = y(0.0.coerceIn(plot.yMinimum, plot.yMaximum))
        drawings = plot.curves.map { curve ->
            val line = Path(); val area = Path()
            var joined = false; var first = 0f; var last = 0f
            fun close() {
                if (!joined) return
                area.lineTo(last, zero); area.lineTo(first, zero); area.close(); joined = false
            }
            curve.points.forEach { point ->
                if (!point.x.isFinite() || !point.y.isFinite() || plot.logarithmicX && point.x <= 0) {
                    close(); return@forEach
                }
                val xx = x(point.x); val yy = y(point.y)
                if (!xx.isFinite() || !yy.isFinite()) { close(); return@forEach }
                if (joined) { line.lineTo(xx, yy); area.lineTo(xx, yy) }
                else { line.moveTo(xx, yy); area.moveTo(xx, zero); area.lineTo(xx, yy); first = xx; joined = true }
                last = xx
            }
            close()
            Drawing(curve, line, area, curve.points.all { !it.y.isFinite() || it.y == 0.0 })
        }
    }

    private fun drawAreas(canvas: Canvas, colors: Palette) {
        val zero = y(0.0.coerceIn(plot.yMinimum, plot.yMaximum)).coerceIn(graph.top, graph.bottom)
        drawings.forEachIndexed { index, drawing ->
            val inactive = plot.inactive || drawing.source.dashed
            val positive = if (inactive) colors.muted else if (style == 3) colors.positive else curveColor(drawing, colors)
            val negative = if (inactive) colors.muted else colors.negative
            val alpha = if (inactive) 12 else if (style == 3) 56 else if (index == 0) 40 else 22
            canvas.save(); canvas.clipRect(graph.left, graph.top, graph.right, zero)
            fill.color = tint(positive, alpha)
            fill.shader = LinearGradient(0f, graph.top, 0f, max(graph.top + d, zero),
                tint(positive, alpha + 20), tint(positive, 5), Shader.TileMode.CLAMP)
            canvas.drawPath(drawing.area, fill); canvas.restore()
            canvas.save(); canvas.clipRect(graph.left, zero, graph.right, graph.bottom)
            fill.color = tint(negative, alpha)
            fill.shader = LinearGradient(0f, zero, 0f, max(zero + d, graph.bottom),
                tint(negative, 5), tint(negative, alpha + 20), Shader.TileMode.CLAMP)
            canvas.drawPath(drawing.area, fill); canvas.restore()
        }
        fill.shader = null
    }

    private fun drawLines(canvas: Canvas, colors: Palette) {
        drawings.forEachIndexed { index, drawing ->
            val inactive = plot.inactive || drawing.source.dashed
            stroke.pathEffect = if (inactive || style == 3 && index > 0) dash else null
            stroke.strokeWidth = if (style == 2) (if (index == 0) 1.1f else .95f) * d else
                (if (index == 0) 1.7f else 1.3f) * d
            if (style == 1 && !inactive) {
                stroke.color = tint(curveColor(drawing, colors), 38)
                val oldWidth = stroke.strokeWidth; stroke.strokeWidth += 2.5f * d
                canvas.drawPath(drawing.line, stroke); stroke.strokeWidth = oldWidth
            }
            if (style == 3 && !inactive && !drawing.zeroOnly) {
                val zero = y(0.0.coerceIn(plot.yMinimum, plot.yMaximum)).coerceIn(graph.top, graph.bottom)
                canvas.save(); canvas.clipRect(graph.left, graph.top, graph.right, zero)
                stroke.color = colors.positive; canvas.drawPath(drawing.line, stroke); canvas.restore()
                canvas.save(); canvas.clipRect(graph.left, zero, graph.right, graph.bottom)
                stroke.color = colors.negative; canvas.drawPath(drawing.line, stroke); canvas.restore()
            } else {
                stroke.color = if (style == 3 && drawing.zeroOnly) colors.muted else curveColor(drawing, colors)
                canvas.drawPath(drawing.line, stroke)
            }
        }
        stroke.pathEffect = null
    }

    private fun drawRegions(canvas: Canvas, colors: Palette) {
        plot.regions.forEach { region ->
            if (!region.start.isFinite() || !region.end.isFinite() || region.start >= region.end) return@forEach
            val left = x(region.start).coerceIn(graph.left, graph.right)
            val right = x(region.end).coerceIn(graph.left, graph.right)
            fill.shader = null; fill.color = tint(colors.colors[1], if (style == 4) 42 else 15)
            canvas.drawRect(left, graph.top, right, graph.bottom, fill)
        }
    }

    private fun drawBandwidth(canvas: Canvas, colors: Palette) {
        val boundaryColor = if (plot.inactive) colors.muted else colors.colors[1]
        plot.regions.forEach { region ->
            if (!region.start.isFinite() || !region.end.isFinite() || region.start >= region.end) return@forEach
            stroke.color = tint(boundaryColor, 200); stroke.strokeWidth = .9f * d; stroke.pathEffect = fineDash
            // Clipped/DC/Nyquist continuations are not fabricated as extra -3dB crossings.
            listOf(region.start, region.end).filter { it > plot.xMinimum && it < plot.xMaximum }.forEach { edge ->
                val xx = x(edge); canvas.drawLine(xx, graph.top, xx, graph.bottom, stroke)
            }
            stroke.pathEffect = null
            val left = x(region.start).coerceIn(graph.left, graph.right)
            val right = x(region.end).coerceIn(graph.left, graph.right)
            if (right - left > 8f * d) {
                val yy = graph.top + 4f * d
                canvas.drawLine(left, yy, right, yy, stroke)
                canvas.drawLine(left, yy, left, yy + 3f * d, stroke)
                canvas.drawLine(right, yy, right, yy + 3f * d, stroke)
            }
        }
    }

    private fun drawMarks(canvas: Canvas, colors: Palette) {
        plot.marks.forEach { mark ->
            if (!mark.x.isFinite() || !mark.y.isFinite() || mark.x < plot.xMinimum || mark.x > plot.xMaximum ||
                plot.logarithmicX && mark.x <= 0) return@forEach
            val xx = x(mark.x); val yy = y(mark.y)
            val color = if (plot.inactive) colors.muted else colors.colors[0]
            stroke.color = tint(color, if (style == 4) 220 else 120)
            stroke.strokeWidth = if (style == 4) 1.1f * d else .65f * d
            stroke.pathEffect = if (style == 4) null else fineDash
            canvas.drawLine(xx, graph.top, xx, graph.bottom, stroke)
            if (style == 2) canvas.drawLine(graph.left, yy, graph.right, yy, stroke)
            stroke.pathEffect = null
            fill.shader = null; fill.color = colors.background
            canvas.drawCircle(xx, yy, if (style == 4) 3.3f * d else 2.7f * d, fill)
            stroke.color = color; stroke.strokeWidth = 1.2f * d
            canvas.drawCircle(xx, yy, if (style == 4) 3.3f * d else 2.7f * d, stroke)
            fill.color = color; canvas.drawCircle(xx, yy, 1.05f * d, fill)
        }
    }

    private fun drawAxes(canvas: Canvas, colors: Palette, lineHeight: Float) {
        val occupiedY = ArrayList<RectF>()
        val yValues = listOf(plot.yMaximum, plot.yMinimum, 0.0, (plot.yMinimum + plot.yMaximum) / 2.0).distinct()
        yValues.filter { it in plot.yMinimum..plot.yMaximum }.forEach { value ->
            val baseline = (y(value) - (text.ascent() + text.descent()) / 2f)
                .coerceIn(graph.top - text.ascent(), graph.bottom - text.descent())
            labelBox.set(0f, baseline + text.ascent() - d, graph.left - 3f * d, baseline + text.descent() + d)
            if (occupiedY.none { RectF.intersects(it, labelBox) }) {
                rawText(canvas, axisNumber(value), graph.left - 4f * d, baseline, colors.muted, Paint.Align.RIGHT)
                occupiedY.add(RectF(labelBox))
            }
        }
        val occupiedX = ArrayList<RectF>()
        val middle = if (plot.logarithmicX && plot.xMinimum > 0) sqrt(plot.xMinimum * plot.xMaximum)
            else (plot.xMinimum + plot.xMaximum) / 2.0
        val center = plot.marks.firstOrNull { it.x.isFinite() && it.x > plot.xMinimum && it.x < plot.xMaximum }?.x
        val xValues = listOfNotNull(plot.xMinimum, plot.xMaximum, center, middle).distinct()
        val baseline = graph.bottom + lineHeight + 2f * d
        xValues.forEach { value ->
            val caption = axisNumber(value) + if (value == plot.xMaximum && plot.xLabel.isNotBlank()) " ${plot.xLabel}" else ""
            val textWidth = text.measureText(caption)
            val xx = x(value)
            val left = (xx - textWidth / 2f).coerceIn(graph.left, max(graph.left, graph.right - textWidth))
            labelBox.set(left - 2f * d, baseline + text.ascent(), left + textWidth + 2f * d, baseline + text.descent())
            if (occupiedX.none { RectF.intersects(it, labelBox) }) {
                rawText(canvas, caption, left, baseline, colors.muted)
                occupiedX.add(RectF(labelBox))
            }
        }
    }

    private fun details(): String = buildString {
        append(plot.title).append('\n').append(plot.subtitle).append('\n')
        append("横轴 ${plot.xLabel}：${plot.xMinimum}…${plot.xMaximum}；纵轴 ${plot.yLabel}：${plot.yMinimum}…${plot.yMaximum}。\n")
        plot.curves.forEach { append(it.label).append(if (it.dashed || plot.inactive) "（虚线预览）" else "").append('\n') }
        plot.regions.forEach { append(it.label).append("：").append(it.start).append("…").append(it.end).append(' ').append(plot.xLabel).append('\n') }
        plot.marks.forEach { append(it.label.ifBlank { "模型标记" }).append("：").append(it.x).append(" / ").append(it.y).append('\n') }
        plot.notes.forEach { append(it).append('\n') }
        append("样式仅改变显示；所有曲线点、坐标、Q、增益与频段参数均来自原模型，未增加音频控制或测量数据。")
    }
}
