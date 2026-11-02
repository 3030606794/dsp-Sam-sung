package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.MediaOutputEvidence

/** Updated on the routing observer's main thread; preference workers consume an immutable snapshot. */
object SpeakerRouteBridge {
    data class RouteSnapshot(val evidence: MediaOutputEvidence, val generation: Long)
    @Volatile private var current = RouteSnapshot(MediaOutputEvidence.UNKNOWN, 0)
    private val evidence get() = current.evidence
    fun snapshot(): RouteSnapshot = current

    fun update(context: Context, next: MediaOutputEvidence) {
        synchronized(this) { current = RouteSnapshot(next, current.generation + 1) }
        ExpansionEffectsController.notifyChanged(context)
    }

    fun clear() { synchronized(this) { current = RouteSnapshot(MediaOutputEvidence.UNKNOWN, current.generation + 1) } }

    fun patchSmart(packet: IntArray) { packet[SmartAdaptProtocol.ROUTE_INDEX] = SmartAdaptProtocol.route(evidence) }

    fun patch(packet: IntArray) {
        packet[SpeakerOptimizationProtocol.ROUTE_INDEX] =
            if (SpeakerOptimizationProtocol.routeActive(packet[SpeakerOptimizationProtocol.SCOPE_INDEX], evidence)) 1000 else 0
    }

    fun bypassReason(values: Map<String, *>): String? {
        val scope = (values["p10"] as? Int)?.takeIf { it == 0 || it == 1000 } ?: 0
        return SpeakerOptimizationProtocol.bypassReason(scope, evidence)
    }
}
