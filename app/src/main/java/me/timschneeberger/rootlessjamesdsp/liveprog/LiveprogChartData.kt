package me.timschneeberger.rootlessjamesdsp.liveprog

/** Immutable presentation snapshots. These objects never own or write script parameters. */
internal data class LiveprogChartParameter(
    val key: String, val title: String, val value: Double,
    val minimum: Double, val maximum: Double, val defaultValue: Double? = null,
    val choices: List<String> = emptyList()
)

internal data class LiveprogChartConfiguration(
    val identity: String, val path: String, val fileName: String, val title: String,
    val source: String, val parameters: List<LiveprogChartParameter>,
    val knownScript: String?, val rate: Int = 48000, val actualRate: Boolean = false,
    val enabled: Boolean = false, val expectedSourceHash: Int? = null
) {
    val values: Map<String, Double> get() = parameters.associate { it.key to it.value }
}

internal enum class LiveprogRealtimeMode { SPECTRUM, LEVEL, WAVEFORM, STEREO, TIME_FREQUENCY }
internal data class LiveprogChartFocus(
    val key: String, val group: String, val leftTitle: String, val rightTitle: String,
    val liveTitle: String, val realtimeMode: LiveprogRealtimeMode
)
internal enum class LiveprogPlotStyle { LINES, BARS, ROUTING, STEREO, QUANTIZATION, BANDS, PROBABILITY, TIMELINE, TEXT }
internal data class LiveprogPoint(val x: Double, val y: Double)
internal data class LiveprogCurve(val label: String, val points: List<LiveprogPoint>, val colorIndex: Int = 0, val dashed: Boolean = false)
internal data class LiveprogBar(val label: String, val value: Double, val colorIndex: Int = 0, val inactive: Boolean = false)
internal data class LiveprogRegion(val start: Double, val end: Double, val label: String = "", val colorIndex: Int = 0)
internal data class LiveprogNode(val id: String, val label: String, val x: Double, val y: Double, val colorIndex: Int = 0)
internal data class LiveprogEdge(val from: String, val to: String, val label: String = "", val colorIndex: Int = 0, val dashed: Boolean = false)
internal data class LiveprogMark(val x: Double, val y: Double, val label: String = "", val colorIndex: Int = 0)
/** Named, fixed reference inputs transformed by the display model, never live music positions. */
internal data class LiveprogStereoProbe(
    val label: String, val side: Double, val mid: Double,
    val referenceSide: Double, val referenceMid: Double, val colorIndex: Int = 0
)
internal data class LiveprogStereoDiagram(
    val probes: List<LiveprogStereoProbe>,
    val leftGain: Double, val rightGain: Double,
    val referenceLeftGain: Double = 1.0, val referenceRightGain: Double = 1.0,
    val emphasis: String = "all", val maximum: Double = 2.0,
    val referenceLabel: String = "默认"
)
internal data class LiveprogStaticPlot(
    val title: String, val subtitle: String = "", val style: LiveprogPlotStyle = LiveprogPlotStyle.LINES,
    val xLabel: String = "", val yLabel: String = "", val xMinimum: Double = 0.0, val xMaximum: Double = 1.0,
    val yMinimum: Double = 0.0, val yMaximum: Double = 1.0, val logarithmicX: Boolean = false,
    val curves: List<LiveprogCurve> = emptyList(), val bars: List<LiveprogBar> = emptyList(),
    val regions: List<LiveprogRegion> = emptyList(), val nodes: List<LiveprogNode> = emptyList(),
    val edges: List<LiveprogEdge> = emptyList(), val marks: List<LiveprogMark> = emptyList(),
    val notes: List<String> = emptyList(), val inactive: Boolean = false,
    val stereoDiagram: LiveprogStereoDiagram? = null
)
internal data class LiveprogChartPair(val professional: LiveprogStaticPlot, val illustration: LiveprogStaticPlot)

internal data class LiveprogAudioFrame(
    val elapsedRealtimeMs: Long, val sessionIdentity: Any?, val sessionId: Int?,
    val pcm: LiveprogPcmTelemetry.Snapshot?, val status: String,
    val actualRate: Int? = null, val discontinuity: Boolean = false
)
