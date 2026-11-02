package me.timschneeberger.rootlessjamesdsp.interop

/** One worker consumes the latest preferences; forced namespace requests are combined, never lost. */
internal class PreferenceSyncQueue {
    private val forced = linkedSetOf<String>()
    private var pending = false
    private var running = false
    private var closed = false
    @Synchronized fun request(namespaces: Array<String>?): Boolean {
        if (closed) return false
        pending = true
        namespaces?.let { forced.addAll(it) }
        if (running) return false
        running = true
        return true
    }
    @Synchronized fun take(): Array<String>? {
        if (closed || !pending) { running = false; return null }
        pending = false
        return forced.toTypedArray().also { forced.clear() }
    }
    @Synchronized fun close() { closed = true; pending = false; forced.clear() }
}
