package me.timschneeberger.rootlessjamesdsp.interop

/** IR-local recovery. Sleeping audio sessions never exhaust into a fatal transfer error. */
internal class ConvolutionTransferRecovery {
    data class Target(val identity: Any, val crc: Int, val frames: Int, val channels: Int, val rate: Int)
    data class KernelAck(val rate: Int, val flags: Int, val channels: Int, val frames: Int, val crc: Int) {
        val loaded: Boolean get() = flags and 1 != 0
        fun matches(target: Target): Boolean = loaded && channels == target.channels &&
            frames == target.frames && crc == target.crc && (rate == 0 || rate == target.rate)
    }
    enum class Action { ATTEMPT, WAIT, FAILED }
    private var target: Target? = null
    private var failures = 0
    private var retryAt = 0L
    private var waitingForStream = false
    private var terminal = false
    private var notified = false

    @Synchronized fun select(value: Target) {
        if (target != value) { reset(); target = value }
    }

    @Synchronized fun action(now: Long, active: Boolean): Action {
        if (target == null) return Action.WAIT
        if (terminal) return Action.FAILED
        if (waitingForStream) {
            if (!active) return Action.WAIT
            waitingForStream = false; failures = 0; retryAt = now
        }
        return if (now >= retryAt) Action.ATTEMPT else Action.WAIT
    }

    /** Initial send plus three quiet retries, then wait for playback or report a real failure. */
    @Synchronized fun failed(now: Long, active: Boolean): Action {
        failures++
        if (failures >= 4) {
            if (active) terminal = true else waitingForStream = true
        } else retryAt = now + longArrayOf(150L, 500L, 1000L)[failures - 1]
        return if (terminal) Action.FAILED else Action.WAIT
    }

    @Synchronized fun succeeded() { failures = 0; retryAt = 0; waitingForStream = false; terminal = false; notified = false }
    @Synchronized fun takeFailureNotice(): Boolean = if (notified) false else { notified = true; true }
    @Synchronized fun reset() { target = null; succeeded() }

    companion object {
        /** Unknown telemetry cannot invalidate a kernel that this effect already acknowledged. */
        fun waitForAcknowledgement(matches: Boolean?, previouslyConfirmed: Boolean): Boolean =
            matches == null && previouslyConfirmed

        /** Atomic driver metadata, independently valid before any process() call. */
        fun decodeAck(values: IntArray?): KernelAck? {
            val v = values ?: return null
            if (v.size != 32 || v[0] != 1 || v[1] and 1 != 0 ||
                (v[2] != 0 && v[2] !in 8000..768000) || v[3] !in 0..15 ||
                v[4] !in listOf(0, 1, 2, 4) || v[5] < 0) return null
            if (v[2] == 0 && v[3] and 12 != 0) return null
            return KernelAck(v[2], v[3], v[4], v[5], v[6])
        }
    }
}
