package me.timschneeberger.rootlessjamesdsp.fusion

import android.media.audiofx.AudioEffectHidden
import android.os.SystemClock
import java.util.IdentityHashMap
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine

/** Optional native observation leases. No profile, route, audition or persistent parameter changes. */
object NativeVisualizationTelemetry {
    const val CAPABILITY = 16 // Extended capabilities, 131234.
    const val ARM = 196657
    const val ENERGY_QUERY = 131242
    const val FIELD_QUERY = 131243
    const val ENERGY_WORDS = 12
    const val FIELD_WORDS = 272
    enum class Module(val id: Int) { CLARITY(1), SPECTRUM(2), FIELD(3) }

    data class EnergySample(
        val elapsedRealtimeMs: Long,
        val rate: Int,
        val clarityGeneration: Int?,
        val claritySequence: Int?,
        val spectrumGeneration: Int?,
        val spectrumSequence: Int?,
        val clarityEnhancementDb: Double?,
        val spectrumEnergyDbfs: Double?,
        val clarityClamped: Boolean,
        val spectrumClamped: Boolean,
    )
    /** LR amplitudes retain the native linear scale; they are never normalized to each point cloud. */
    data class FieldSample(
        val generation: Int,
        val sequence: Int,
        val rate: Int,
        val frames: Int,
        val pointSpacingFrames: Int,
        val left: List<Double>,
        val right: List<Double>,
        val sideReductionDb: Double?,
        val clamped: Boolean,
        val elapsedRealtimeMs: Long,
    )
    private class Leases(val effect: AudioEffectHidden) {
        val owners = Module.values().associateWith { IdentityHashMap<Any, Boolean>() }
    }
    private val leases = IdentityHashMap<JamesDspRemoteEngine, Leases>()

    /** Call from the page's IO subscription. Repeated ownership never resends the arm command. */
    @Synchronized fun setEnabled(engine: JamesDspRemoteEngine, module: Module, owner: Any, enabled: Boolean): Boolean {
        val effect = engine.effect ?: return false
        if (!engine.visualizationTelemetrySupported()) return false
        val entry = leases[engine]?.takeIf { it.effect === effect }
            ?: Leases(effect).also { leases[engine] = it }
        val owners = entry.owners.getValue(module)
        if (enabled) {
            if (owners.containsKey(owner)) return true
            if (owners.isEmpty() && !engine.setVisualizationTelemetry(module.id, true)) {
                if (entry.owners.values.all { it.isEmpty() }) leases.remove(engine)
                return false
            }
            owners[owner] = true
        } else {
            if (owners.remove(owner) == null) return true
            if (owners.isEmpty()) {
                val stopped = engine.setVisualizationTelemetry(module.id, false)
                if (entry.owners.values.all { it.isEmpty() }) leases.remove(engine)
                return stopped
            }
        }
        return true
    }

    /** Release this subscriber only; another page's lease and every DSP effect stay untouched. */
    @Synchronized fun release(owner: Any) {
        leases.entries.toList().forEach { (engine, entry) ->
            entry.owners.forEach { (module, owners) ->
                if (owners.remove(owner) != null && owners.isEmpty() && engine.effect === entry.effect)
                    engine.setVisualizationTelemetry(module.id, false)
            }
            if (entry.owners.values.all { it.isEmpty() }) leases.remove(engine)
        }
    }

    @Synchronized fun isEnabled(engine: JamesDspRemoteEngine, module: Module): Boolean =
        leases[engine]?.takeIf { it.effect === engine.effect }?.owners?.get(module)?.isNotEmpty() == true

    fun decodeEnergy(words: IntArray, expectedRate: Int): EnergySample? {
        if (words.size != ENERGY_WORDS || words[0] != 1 || words[1] and 1 != 0 ||
            words[2] != expectedRate || expectedRate !in 8000..768000 || words[5] !in 0..63) return null
        val clarityReady = words[3] and 1 != 0 && words[5] and 4 != 0 && words[6] > 0
        val spectrumReady = words[4] and 1 != 0 && words[5] and 8 != 0 && words[7] > 0
        fun level(index: Int, ready: Boolean, flag: Int, clipped: Int) =
            words[index].takeIf { ready && words[5] and flag != 0 && words[5] and clipped == 0 &&
                it != Int.MIN_VALUE && it in -24000..24000 }?.div(100.0)
        return EnergySample(SystemClock.elapsedRealtime(), expectedRate,
            if (clarityReady) words[3] ushr 1 else null, if (clarityReady) words[6] else null,
            if (spectrumReady) words[4] ushr 1 else null, if (spectrumReady) words[7] else null,
            level(8, clarityReady, 1, 16), level(9, spectrumReady, 2, 32),
            words[5] and 16 != 0, words[5] and 32 != 0)
    }

    fun decodeField(words: IntArray, expectedRate: Int): FieldSample? {
        if (words.size != FIELD_WORDS || words[0] != 1 || words[1] and 1 == 0 ||
            words[2] <= 0 || words[3] != expectedRate || expectedRate !in 8000..768000 ||
            words[4] and 1 == 0 || words[4] and 5 != words[4] || words[5] < 0 ||
            words[6] != 128 || words[7] < 1 || words[8] != 1_000_000) return null
        return FieldSample(words[1] ushr 1, words[2], words[3], words[5], words[7],
            List(128) { words[16 + it * 2] / 1_000_000.0 },
            List(128) { words[17 + it * 2] / 1_000_000.0 },
            words[9].takeIf { it in 0..1200 }?.div(100.0), words[4] and 4 != 0,
            SystemClock.elapsedRealtime())
    }
}
