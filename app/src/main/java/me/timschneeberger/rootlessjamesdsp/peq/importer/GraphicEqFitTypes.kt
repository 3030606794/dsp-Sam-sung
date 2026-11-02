package me.timschneeberger.rootlessjamesdsp.peq.importer

import me.timschneeberger.rootlessjamesdsp.peq.PeqState

data class GraphicEqFitOptions(
    val maxBands: Int = 30,
    val targetErrorDb: Double = 0.5,
    val sampleRate: Double = 48000.0,
    val minFrequency: Double = 20.0,
    val maxFrequency: Double = 20000.0
)

data class GraphicEqFitProgress(val fraction: Double, val message: String)

/** Errors describe the returned, wire-quantized state on the returned verification grid. */
data class GraphicEqFitResult(
    val state: PeqState,
    val frequencies: DoubleArray,
    val targetDb: DoubleArray,
    val fittedDb: DoubleArray,
    val meanAbsoluteErrorDb: Double,
    val rmsErrorDb: Double,
    val maxErrorDb: Double,
    val maxErrorFrequency: Double,
    val targetMet: Boolean,
    val elapsedMillis: Long,
    val clippedPointCount: Int,
    val offsetBandCount: Int
)
