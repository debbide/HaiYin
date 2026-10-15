package com.haiyin.player

/**
 * In-memory ring buffer of DLNA SOAP actions and transport-state transitions,
 * for diagnosing picky renderers (pause/resume quirks). Viewable from
 * Settings -> "DLNA 日志". Timestamps are ms relative to the first entry.
 */
object DlnaTrace {
    private data class E(val t: Long, val msg: String)
    private val buf = ArrayDeque<E>()
    private var t0 = 0L

    @Synchronized
    fun log(msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (t0 == 0L) t0 = now
        buf.addLast(E(now - t0, msg))
        while (buf.size > 400) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        if (buf.isEmpty()) ""
        else buf.joinToString("\n") { e -> "[+${e.t}ms] ${e.msg}" }

    @Synchronized
    fun clear() {
        buf.clear()
        t0 = 0L
    }
}
