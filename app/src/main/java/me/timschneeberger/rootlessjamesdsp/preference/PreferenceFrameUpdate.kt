package me.timschneeberger.rootlessjamesdsp.preference

import android.os.Handler
import android.os.Looper

/** One latest-state presentation refresh per frame; audio writes remain immediate. */
class PreferenceFrameUpdate(private val refresh: (Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var queued = false
    private var fullRefresh = false
    private var closed = false
    private val callback = Runnable {
        queued = false
        val full = fullRefresh
        fullRefresh = false
        if (!closed) refresh(full)
    }

    fun request(full: Boolean = true) {
        if (closed) return
        fullRefresh = fullRefresh || full
        if (queued) return
        queued = true
        handler.postDelayed(callback, 16L)
    }

    fun close() {
        closed = true
        queued = false
        fullRefresh = false
        handler.removeCallbacks(callback)
    }
}
