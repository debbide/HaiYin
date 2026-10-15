package com.haiyin.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Tiny cover-image loader: LRU memory cache + single-thread disk fetch. */
object CoverLoader {

    private const val MAX_CACHE = 24 * 1024 * 1024 / 4   // ~6MB of bitmaps
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "cover-loader").apply { isDaemon = true }
    }
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())

    fun preload(url: String) {
        if (url.isBlank() || cache.get(url) != null || url in pending) return
        pending.add(url)
        executor.execute {
            val bmp = fetch(url)
            pending.remove(url)
            if (bmp != null) cache.put(url, bmp)
        }
    }

    /** Synchronous get; call from IO thread. Returns cached or null. */
    fun get(url: String): Bitmap? = cache.get(url)

    /** Async get with callback on main thread. */
    fun getAsync(url: String, onLoaded: (Bitmap?) -> Unit) {
        if (url.isBlank()) { onLoaded(null); return }
        cache.get(url)?.let { onLoaded(it); return }
        preload(url)
        executor.execute {
            val bmp = try { fetch(url) ?: cache.get(url) } catch (_: Exception) { null }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onLoaded(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")
            if (conn.responseCode != 200) { conn.disconnect(); null }
            else {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) { null }
    }
}
