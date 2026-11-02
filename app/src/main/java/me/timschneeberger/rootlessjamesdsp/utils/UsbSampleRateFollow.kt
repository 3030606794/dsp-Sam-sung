package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus

/** Follows observed processing, never a USB device's advertised sample-rate capabilities. */
class UsbSampleRateFollow {
    enum class Stage { OFF, WAITING_USB, WAITING_AUDIO, ACTIVE }
    data class Status(val stage: Stage = Stage.OFF, val rates: List<Int> = emptyList())
    data class Decision(val status: Status, val synchronize: Boolean = false)
    private var previous = emptyMap<Any, OutputRuntimeStatus.Session>()
    private val confirmedRates = mutableMapOf<Any, Int>()

    fun update(enabled: Boolean, usbRoute: Boolean, sessions: List<OutputRuntimeStatus.Session>): Decision {
        if (!enabled || !usbRoute) {
            previous = emptyMap(); confirmedRates.clear()
            return Decision(Status(if (enabled) Stage.WAITING_USB else Stage.OFF))
        }
        confirmedRates.keys.retainAll(sessions.map { it.identity }.toSet())
        var synchronize = false
        val observed = sessions.mapNotNull { session ->
            val before = previous[session.identity]
            val rate = session.hostRate?.takeIf { it in 8000..768000 }
            val frames = session.processedFrames
            val beforeFrames = before?.processedFrames
            val advance = if (frames != null && beforeFrames != null && frames >= 0 && beforeFrames >= 0)
                (frames.toLong() - beforeFrames.toLong()) and 0x7fffffffL else 0L
            if (session.enabled && before?.enabled == true && rate != null && before.hostRate == rate &&
                advance in 1L..0x3fffffffL) {
                if (confirmedRates[session.identity] != rate) synchronize = true
                confirmedRates[session.identity] = rate
                rate
            } else null
        }.distinct().sorted()
        previous = sessions.associateBy { it.identity }
        if (observed.isEmpty()) {
            return Decision(Status(Stage.WAITING_AUDIO))
        }
        return Decision(Status(Stage.ACTIVE, observed), synchronize)
    }
}
