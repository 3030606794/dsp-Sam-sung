package me.timschneeberger.rootlessjamesdsp.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current app-owned shell authorization; a driver's installation is separate evidence. */
object RootAccessStatus {
    enum class State { UNKNOWN, AVAILABLE, UNAVAILABLE }
    private val mutable=MutableStateFlow(State.UNKNOWN)
    val state=mutable.asStateFlow()
    internal fun publish(next:State) {mutable.value=next}
}
