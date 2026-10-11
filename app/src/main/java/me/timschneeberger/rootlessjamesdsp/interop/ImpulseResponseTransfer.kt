package me.timschneeberger.rootlessjamesdsp.interop

/** Transport an IR transaction without committing an incomplete or rejected upload. */
internal object ImpulseResponseTransfer {
    const val PARTITION_SAMPLES = 4096
    private const val MAX_ALLOCATION_BYTES = 32L * 1024 * 1024
    private const val BAD_VALUE = -4 // AudioEffect.ERROR_BAD_VALUE

    // Native allocates complete 4096-float partitions, including the last zero-padded tail.
    // sampleCount includes all channels; no additional channel multiplier is allocated.
    fun header(sampleCount: Int, channels: Int): IntArray? {
        if (channels !in setOf(1, 2, 4) || sampleCount <= 0 || sampleCount % channels != 0) return null
        val partitions = (sampleCount.toLong() + PARTITION_SAMPLES - 1) / PARTITION_SAMPLES
        if (partitions * PARTITION_SAMPLES * 4 > MAX_ALLOCATION_BYTES) return null
        return intArrayOf(sampleCount, channels, 0, partitions.toInt())
    }

    fun send(samples: FloatArray, channels: Int, prepare: (IntArray) -> Int,
             chunk: (FloatArray) -> Int, commit: () -> Int): Int {
        val allocation = header(samples.size, channels) ?: return BAD_VALUE
        if (samples.any { !it.isFinite() }) return BAD_VALUE
        val prepared = prepare(allocation)
        if (prepared != 0) return prepared
        val buffer = FloatArray(PARTITION_SAMPLES)
        for (offset in samples.indices step PARTITION_SAMPLES) {
            buffer.fill(0f)
            samples.copyInto(buffer, 0, offset, minOf(offset + PARTITION_SAMPLES, samples.size))
            val sent = chunk(buffer)
            if (sent != 0) return sent
        }
        return commit()
    }
}
