package com.haiyin.player

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HTTP stream proxy on a raw ServerSocket (Android has no com.sun.httpserver):
 * renderer pulls http://<phone-ip>:<port>/stream/<id>; we forward Range requests
 * and inject UA so DLNA devices can play picky direct links.
 */
object StreamProxy {

    data class Target(val url: String, val headers: Map<String, String>)

    private var serverSocket: ServerSocket? = null
    private val targets = ConcurrentHashMap<String, Target>()
    private val seed = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-proxy").apply { isDaemon = true }
    }
    @Volatile private var running = false
    @Volatile private var cachedLocalUrl: String? = null

    /** Start (if needed) and return base URL like http://192.168.x.x:port */
    fun ensureRunning(): String? {
        if (running && cachedLocalUrl != null) return cachedLocalUrl
        try {
            if (!running) {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(0))
                serverSocket = ss
                running = true
                pool.execute { acceptLoop(ss) }
            }
            val ip = localIpAddress() ?: return null
            cachedLocalUrl = "http://$ip:${serverSocket!!.localPort}"
            return cachedLocalUrl
        } catch (_: Exception) { return null }
    }

    /** Register a proxied URL; returns the local URL to hand to the renderer. */
    fun proxy(url: String, headers: Map<String, String> = emptyMap()): String {
        val base = ensureRunning() ?: return url
        val id = "s${System.currentTimeMillis()}-${seed.incrementAndGet()}"
        targets[id] = Target(url, headers)
        return "$base/stream/$id"
    }

    fun clear() { targets.clear() }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept() ?: return
                pool.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: run { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { client.close(); return }
            val path = parts[1]
            // read headers
            val reqHeaders = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) reqHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val id = path.removePrefix("/stream/").substringBefore('?')
            val target = targets[id]
            val out = client.getOutputStream()
            if (target == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); client.close(); return
            }

            val conn = URL(target.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            reqHeaders["range"]?.let { conn.setRequestProperty("Range", it) }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")
            target.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code !in 200..399) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); client.close(); conn.disconnect(); return
            }

            val input: InputStream? = conn.inputStream
            if (input == null) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); client.close(); conn.disconnect(); return
            }

            // response head
            val sb = StringBuilder()
            val status = if (conn.getHeaderField("Content-Range") != null) "206 Partial Content" else "200 OK"
            sb.append("HTTP/1.1 $status\r\n")
            listOf("Content-Type", "Content-Length", "Accept-Ranges", "Content-Range").forEach { h ->
                conn.getHeaderField(h)?.let { sb.append(h).append(": ").append(it).append("\r\n") }
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            // pipe body
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) { /* renderer disconnected; fine */ }
            try { input.close() } catch (_: Exception) {}
            conn.disconnect()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun localIpAddress(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
