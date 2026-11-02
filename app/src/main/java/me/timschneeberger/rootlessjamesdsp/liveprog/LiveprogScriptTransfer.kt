package me.timschneeberger.rootlessjamesdsp.liveprog

/** Stops at the first failed packet and publishes a source hash only after a successful compile. */
object LiveprogScriptTransfer {
    data class Result(val status: Int, val committed: Boolean)

    fun send(source: String, prepare: (IntArray) -> Int, chunk: (ByteArray) -> Int, commit: () -> Int): Result {
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (bytes.size >= 1024 * 1024 || '\u0000' in source) return Result(-4, false)
        // Include a terminator even when UTF-8 source bytes fill the last packet exactly.
        val count = (bytes.size + 1 + 255) / 256
        val allocation = prepare(intArrayOf(count, 256))
        if (allocation != 0) return Result(allocation, false)
        repeat(count) { index ->
            val packet = ByteArray(256)
            bytes.copyInto(packet, 0, index * 256, minOf(bytes.size, (index + 1) * 256))
            val status = chunk(packet)
            if (status != 0) return Result(status, false)
        }
        return Result(commit(), true)
    }
}
