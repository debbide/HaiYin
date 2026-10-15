package com.haiyin.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Cover-image loader: memory LRU + disk cache + 2-thread fetch.
 * API is unchanged: preload / get (sync, IO thread) / getAsync (main-thread callback).
 */
object CoverLoader {

    private const val MAX_MEM_CACHE = 24 * 1024 * 1024 / 4   // ~6MB of bitmaps
    private const val MAX_DISK_CACHE = 100L * 1024 * 1024    // 100MB
    private const val DISK_DIR = "covers"

    private val cache = object : LruCache<String, Bitmap>(MAX_MEM_CACHE) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "cover-loader").apply { isDaemon = true }
    }
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())
    private var diskDir: File? = null

    fun init(context: Context) {
        if (diskDir != null) return
        diskDir = File(context.applicationContext.filesDir, DISK_DIR).apply { mkdirs() }
    }

    fun preload(url: String) {
        if (url.isBlank() || cache.get(url) != null || url in pending) return
        pending.add(url)
        executor.execute {
            val bmp = fetch(url)
            pending.remove(url)
            if (bmp != null) cache.put(url, bmp)
        }
    }

    /** Synchronous get (memory -> disk); call from IO thread. Returns cached or null. */
    fun get(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        return readDisk(url)?.also { cache.put(url, it) }
    }

    /** Async get with callback on main thread. */
    fun getAsync(url: String, onLoaded: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            onLoaded(null); return
        }
        cache.get(url)?.let { onLoaded(it); return }
        preload(url)
        executor.execute {
            val bmp = try {
                fetch(url) ?: cache.get(url)
            } catch (_: Exception) { null }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onLoaded(bmp) }
        }
    }

    // ---------------- disk cache ----------------

    private fun diskFile(url: String): File? {
        val d = diskDir ?: return null
        return File(d, sha1(url) + ".img")
    }

    private fun readDisk(url: String): Bitmap? {
        return try {
            val f = diskFile(url) ?: return null
            if (!f.exists() || f.length() == 0L) return null
            f.setLastModified(System.currentTimeMillis())
            val bytes = f.readBytes()
            if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(url: String, bytes: ByteArray) {
        try {
            val f = diskFile(url) ?: return
            // skip rewriting identical fresh file
            if (f.exists() && f.length() == bytes.size.toLong()) {
                f.setLastModified(System.currentTimeMillis())
                return
            }
            f.writeBytes(bytes)
            evictDiskIfNeeded()
        } catch (_: Exception) {
        }
    }

    private fun evictDiskIfNeeded() {
        try {
            val d = diskDir ?: return
            val files = d.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_CACHE) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= MAX_DISK_CACHE) return
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun sha1(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------- network ----------------

    private fun fetch(url: String): Bitmap? {
        return try {
            // disk first
            readDisk(url)?.let { return it }
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")
            if (conn.responseCode != 200) {
                conn.disconnect()
                null
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty()) {
                    null
                } else {
                    writeDisk(url, bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
