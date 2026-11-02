package me.timschneeberger.rootlessjamesdsp.interop

import android.media.audiofx.AudioEffect

/** Original UTF-8 bytes (including an optional BOM) are never split as characters. */
object DdcTransfer {
    fun send(bytes: ByteArray, prepare: (IntArray) -> Int, chunk: (ByteArray) -> Int,
             commit: () -> Int, current: () -> Boolean = { true }): Int {
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return AudioEffect.ERROR_BAD_VALUE
        val count=(bytes.size+255)/256
        if (!current()) return AudioEffect.ERROR_INVALID_OPERATION
        val prepared=prepare(intArrayOf(count,256))
        if (prepared!=AudioEffect.SUCCESS) return prepared
        for (i in 0 until count) {
            if (!current()) return AudioEffect.ERROR_INVALID_OPERATION
            val block=ByteArray(256)
            bytes.copyInto(block,0,i*256,minOf(bytes.size,(i+1)*256))
            val sent=chunk(block)
            if (sent!=AudioEffect.SUCCESS) return sent
        }
        if (!current()) return AudioEffect.ERROR_INVALID_OPERATION
        return commit()
    }
}
