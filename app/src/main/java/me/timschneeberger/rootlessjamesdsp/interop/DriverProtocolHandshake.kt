package me.timschneeberger.rootlessjamesdsp.interop

/** A failed query says nothing about the version of the installed driver. */
internal object DriverProtocolHandshake {
    enum class State { Available, NotReady, Unsupported }

    fun classify(protocol: Int?, capabilities: Int?, requiredProtocol: Int, requiredCapabilities: Int): State =
        when {
            protocol == null || capabilities == null -> State.NotReady
            protocol != requiredProtocol || capabilities and requiredCapabilities != requiredCapabilities -> State.Unsupported
            else -> State.Available
        }
}
