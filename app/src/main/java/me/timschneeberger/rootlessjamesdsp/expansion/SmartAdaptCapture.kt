package me.timschneeberger.rootlessjamesdsp.expansion

import java.util.IdentityHashMap

/** Selection is identity-based: an idle or replaced native instance can never provide a lock. */
internal object SmartAdaptCapture {
    fun select(first: List<Pair<Any, ExpansionEffectsRuntime>>, second: List<Pair<Any, ExpansionEffectsRuntime>>,
        expected: IntArray): Pair<Any, SmartAdaptState.Snapshot>? {
        val previous = IdentityHashMap<Any, ExpansionEffectsRuntime>()
        first.forEach { previous[it.first] = it.second }
        if (first.size != second.size || second.any { !previous.containsKey(it.first) }) return null
        val advancing = second.filter { (identity, runtime) ->
            previous[identity]?.let { SmartAdaptProtocol.framesAdvanced(it, runtime) } == true
        }
        val (identity, runtime) = advancing.singleOrNull() ?: return null
        val old = previous.getValue(identity)
        if (old.parameterHash != runtime.parameterHash || SmartAdaptState.snapshot(old, expected) == null) return null
        return SmartAdaptState.snapshot(runtime, expected)?.let { identity to it }
    }
}
