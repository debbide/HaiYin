package com.haiyin.player

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.regex.Pattern

/**
 * Minimal DLNA/UPnP cast: SSDP discovery + AVTransport SOAP control.
 * No third-party libs; works with any UPnP renderer (TV, speaker, box).
 */
object Dlna {

    data class Device(
        val name: String,
        val controlUrl: String,
    )

    // ---------- SSDP discovery ----------

    /** M-SEARCH for AVTransport renderers; multicast lock + multiple ST probes. */
    fun discover(timeoutMs: Int = 5000): List<Device> {
        val locations = LinkedHashSet<String>()
        var socket: DatagramSocket? = null
        var mlock: Any? = null
        try {
            mlock = acquireMulticastLock()

            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            socket.broadcast = true
            val target = InetAddress.getByName("239.255.255.250")
            val probes = listOf(
                "urn:schemas-upnp-org:service:AVTransport",
                "ssdp:all",
                "upnp:rootdevice",
            )
            for (st in probes) {
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: $st\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                repeat(2) {
                    try {
                        socket.send(DatagramPacket(search, search.size, target, 1900))
                    } catch (_: Exception) {}
                }
            }
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    Regex("""(?i)LOCATION\s*:\s*(.+)""").find(resp)?.groupValues?.get(1)?.trim()
                        ?.let { locations.add(it) }
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) { break }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            releaseMulticastLock(mlock)
        }
        return locations.toList().parallelMapNotNull { loc -> parseDevice(loc) }
    }

    private var multicastLock: Any? = null

    private fun acquireMulticastLock(): Any? = try {
        val ctxField = Class.forName("com.haiyin.player.Player")
        // no-op reflection path removed; use appContext via Player
        val ctx = Player.appContextPublic()
        val wm = ctx.getSystemService("wifi") as android.net.wifi.WifiManager
        val lock = wm.createMulticastLock("haiyin-ssdp").apply { setReferenceCounted(false); acquire() }
        multicastLock = lock
        lock
    } catch (_: Throwable) { null }

    private fun releaseMulticastLock(lock: Any?) {
        try {
            (lock as? android.net.wifi.WifiManager.MulticastLock)?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun <T, R> List<T>.parallelMapNotNull(f: (T) -> R?): List<R> {
        val out = ArrayList<R>()
        val threads = map { item ->
            Thread {
                try { f(item)?.let { synchronized(out) { out.add(it) } } } catch (_: Exception) {}
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        return out
    }

    private fun parseDevice(location: String): Device? {
        val xml = httpGet(location) ?: return null
        // find AVTransport service
        val services = splitServices(xml)
        for (svc in services) {
            if (!svc.contains("AVTransport", ignoreCase = true)) continue
            val controlPath = Regex("""(?i)<controlURL>(.*?)</controlURL>""").find(svc)
                ?.groupValues?.get(1)?.trim() ?: continue
            val name = Regex("""(?i)<friendlyName>(.*?)</friendlyName>""").find(xml)
                ?.groupValues?.get(1)?.trim()
                ?: Regex("""(?i)<modelName>(.*?)</modelName>""").find(xml)?.groupValues?.get(1)?.trim()
                ?: "DLNA 设备"
            val base = Regex("""(?i)<URLBase>(.*?)</URLBase>""").find(xml)?.groupValues?.get(1)?.trim()
                ?: location
            val controlUrl = resolveUrl(base, controlPath) ?: return null
            return Device(name, controlUrl)
        }
        return null
    }

    private fun splitServices(xml: String): List<String> {
        val out = ArrayList<String>()
        val m = Pattern.compile("""(?is)<service>(.*?)</service>""").matcher(xml)
        while (m.find()) out.add(m.group(1) ?: continue)
        return out
    }

    private fun resolveUrl(base: String, path: String): String? = try {
        if (path.startsWith("http")) path
        else URL(URL(base), path).toString()
    } catch (_: Exception) { null }

    // ---------- SOAP control ----------

    /** Set the media URI on the renderer; returns true on HTTP 200. Play is NOT sent. */
    fun setUri(device: Device, url: String, title: String, artist: String): Boolean {
        val didl = (
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"0\">" +
                "<dc:title>${xmlEsc("$title - $artist")}</dc:title>" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:*:*\">${xmlEsc(url)}</res>" +
                "</item></DIDL-Lite>"
            )
        return soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEsc(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEsc(didl)}</CurrentURIMetaData>"
        )
    }

    /** Set the media URL on the renderer; returns true on HTTP 200. */
    fun cast(device: Device, url: String, title: String, artist: String): Boolean {
        if (!setUri(device, url, title, artist)) return false
        // some renderers auto-play on set; Play is idempotent
        soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )
        return true
    }

    /** Seek renderer to absolute ms. */
    fun seek(device: Device, targetMs: Long): Boolean {
        val h = targetMs / 3_600_000
        val m = (targetMs % 3_600_000) / 60_000
        val s = (targetMs % 60_000) / 1000
        val time = "%d:%02d:%02d".format(h, m, s)
        return soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$time</Target>"
        )
    }

    fun pause(device: Device): Boolean =
        soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Pause",
            "<InstanceID>0</InstanceID>"
        )

    fun resume(device: Device): Boolean =
        soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )

    fun stop(device: Device): Boolean =
        soap(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Stop",
            "<InstanceID>0</InstanceID>"
        )

    /**
     * GetMediaInfo -> TrackDuration in ms, or null.
     */
    fun durationMs(device: Device): Long? {
        val resp = soapBody(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "GetMediaInfo",
            "<InstanceID>0</InstanceID>"
        ) ?: return null
        val dur = Regex("""(?i)<TrackDuration>(.*?)</TrackDuration>""").find(resp)?.groupValues?.get(1) ?: return null
        val m = Regex("""(\d+):(\d{2}):(\d{2})""").find(dur) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val mi = m.groupValues[2].toLongOrNull() ?: return null
        val s = m.groupValues[3].toLongOrNull() ?: return null
        return ((h * 3600) + (mi * 60) + s) * 1000
    }

    /**
     * GetPositionInfo -> current position in ms, or null.
     */
    fun positionMs(device: Device): Long? {
        val resp = soapBody(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "GetPositionInfo",
            "<InstanceID>0</InstanceID>"
        ) ?: return null
        val rel = Regex("""(?i)<RelTime>(.*?)</RelTime>""").find(resp)?.groupValues?.get(1) ?: return null
        val m = Regex("""(\d+):(\d{2}):(\d{2})""").find(rel) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val mi = m.groupValues[2].toLongOrNull() ?: return null
        val s = m.groupValues[3].toLongOrNull() ?: return null
        return ((h * 3600) + (mi * 60) + s) * 1000
    }

    /**
     * GetTransportInfo -> current state string (PLAYING / PAUSED_PLAYBACK / STOPPED / ...), or null.
     */
    fun transportState(device: Device): String? {
        val resp = soapBody(
            device.controlUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "GetTransportInfo",
            "<InstanceID>0</InstanceID>"
        ) ?: return null
        return Regex("""(?i)<CurrentTransportState>(.*?)</CurrentTransportState>""").find(resp)
            ?.groupValues?.get(1)?.trim()?.uppercase()
    }

    private fun soapBody(controlUrl: String, service: String, action: String, args: String): String? {
        return try {
            val body = (
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xml.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xml.org/soap/encoding/\">" +
                    "<s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body>" +
                    "</s:Envelope>"
                )
            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
            conn.disconnect()
            text
        } catch (_: Exception) { null }
    }

    private fun soap(controlUrl: String, service: String, action: String, args: String): Boolean {
        return try {
            val body = (
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xml.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xml.org/soap/encoding/\">" +
                    "<s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body>" +
                    "</s:Envelope>"
                )
            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) { false }
    }

    private fun xmlEsc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode != 200) { conn.disconnect(); null }
        else {
            val bytes = ByteArrayOutputStream().also { out ->
                conn.inputStream.use { it.copyTo(out) }
            }.toByteArray()
            conn.disconnect()
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) { null }
}
