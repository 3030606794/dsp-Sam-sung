package me.timschneeberger.rootlessjamesdsp.effectvisuals

import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot

/** Coordinates of diagrams are normalized; text is always rendered in sp, never scaled with geometry. */
internal data class FigurePoint(val x: Double, val y: Double)
internal sealed interface FigureElement
internal data class FigureLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val colorIndex: Int = 0, val arrow: Boolean = false, val dashed: Boolean = false,
    val width: Double = 1.6, val alpha: Double = 1.0) : FigureElement
internal data class FigurePolyline(val points: List<FigurePoint>, val colorIndex: Int = 0,
    val width: Double = 1.6, val filled: Boolean = false, val alpha: Double = 1.0,
    val dashed: Boolean = false) : FigureElement
internal data class FigureBox(val left: Double, val top: Double, val right: Double, val bottom: Double,
    val label: String = "", val colorIndex: Int = 0, val filled: Boolean = true,
    val alpha: Double = .14, val rounded: Boolean = true) : FigureElement
internal data class FigureCircle(val x: Double, val y: Double, val rx: Double, val ry: Double = rx,
    val label: String = "", val colorIndex: Int = 0, val filled: Boolean = false,
    val alpha: Double = 1.0) : FigureElement
internal data class FigureArc(val x: Double, val y: Double, val rx: Double, val ry: Double,
    val startDegrees: Double, val sweepDegrees: Double, val colorIndex: Int = 0,
    val width: Double = 1.6, val alpha: Double = 1.0) : FigureElement
internal enum class FigureAlign { LEFT, CENTER, RIGHT }
internal data class FigureLabel(val text: String, val x: Double, val y: Double,
    val width: Double = .9, val align: FigureAlign = FigureAlign.CENTER,
    val colorIndex: Int = -1, val emphasis: Boolean = false) : FigureElement
internal data class EffectScene(val elements: List<FigureElement>)
internal data class EffectVisual(val title: String, val subtitle: String = "",
    val plot: LiveprogStaticPlot? = null, val scene: EffectScene? = null,
    val notes: List<String> = emptyList(), val inactive: Boolean = false)
internal data class EffectVisualPair(val professional: EffectVisual, val illustration: EffectVisual)
/** History only contains actual confirmed driver samples. A gap starts a new path. */
internal data class EffectVisualFrame(val timeMs: Long, val meters: List<Float>,
    val inputDb: Float, val outputDb: Float, val wetDb: Float, val sampleRate: Int,
    val split: Boolean = false) {
    fun meter(index: Int): Double = meters.getOrElse(index) { 0f }.toDouble()
}
