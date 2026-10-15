package com.haiyin.player

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal sync HTTP GET with redirect following; runs on caller's IO thread. */
object Http {

    @Volatile
    var lastUrl: String? = null
        private set

    fun get(
        url: String,
        timeoutMs: Int = 8000,
        followRedirects: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        lastUrl = url
        return try {
            var current = url
            var redirects = 0
            while (true) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = followRedirects
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                )
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                val code = conn.responseCode
                if (followRedirects && code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    if (++redirects > 5) return null
                    conn.disconnect()
                    continue
                }
                if (code != 200) { conn.disconnect(); return null }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                return body
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Follow redirects manually and return the FINAL direct URL (for media playback). */
    fun resolveRedirect(url: String, timeoutMs: Int = 8000): String? {
        return try {
            var current = url
            var hops = 0
            while (hops++ < 6) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (code in 300..399 && !loc.isNullOrEmpty()) {
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                } else if (code == 200) {
                    return current
                } else {
                    return null
                }
            }
            current
        } catch (e: Exception) {
            null
        }
    }
}
