package me.timschneeberger.rootlessjamesdsp.liveprog

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LiveprogProtocol {
    const val EXTENDED_CAPABILITIES = 131234
    const val REALTIME_CAPABILITY = 4
    const val PARAMETER = 196656
    const val COMPILE_RESULT = 131241

    fun parameter(name: String, value: Double): ByteArray? {
        if (!value.isFinite() || !name.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,62}"))) return null
        return ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name.toByteArray(Charsets.US_ASCII))
            position(64)
            putDouble(value)
        }.array()
    }
}
