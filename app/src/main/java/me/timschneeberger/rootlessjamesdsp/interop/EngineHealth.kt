package me.timschneeberger.rootlessjamesdsp.interop

/** A failed status query is evidence of a connection problem, not proof of a native crash. */
internal class EngineHealth {
    data class Reading(val pid: Int?, val pidStatus: Int, val rate: Int?, val rateStatus: Int, val control: Boolean?)
    enum class Action { APPLY, RETRY, WAIT_CONTROL, RECOVER, STOP }
    private var failures = 0
    private var lastFailureAt = Long.MIN_VALUE
    private var healthySince: Long? = null
    private val recoveries = ArrayDeque<Long>()

    fun observe(now: Long, reading: Reading): Action {
        while (recoveries.isNotEmpty() && now - recoveries.first() >= 30_000) recoveries.removeFirst()
        // Losing control to another effect client must never start a priority/restart fight.
        if (reading.control == false && reading.pidStatus != -7 && reading.rateStatus != -7) {
            failures = 0; healthySince = null
            return Action.WAIT_CONTROL
        }
        // Zero sample rate is legal before a stream starts. It is not a dead process.
        if (reading.pidStatus == 4 && (reading.pid ?: 0) > 0 && reading.rateStatus == 4 &&
            (reading.rate == 0 || reading.rate in 8000..768000) && reading.control == true) {
            failures = 0
            if (healthySince == null) healthySince = now
            if (now - healthySince!! >= 10_000) recoveries.clear()
            return Action.APPLY
        }
        healthySince = null
        // Count distinct, spaced observations, not the number of sliders in a preset.
        if (lastFailureAt == Long.MIN_VALUE || now - lastFailureAt >= 200) {
            failures++; lastFailureAt = now
        }
        val dead = reading.pidStatus == -7 || reading.rateStatus == -7
        if (!dead && failures < 3) return Action.RETRY
        if (recoveries.size >= 2) return Action.STOP
        if (recoveries.isNotEmpty() && now - recoveries.last() < 5_000) return Action.RETRY
        recoveries.addLast(now)
        failures = 0
        return Action.RECOVER
    }
}
