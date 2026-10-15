package com.haiyin.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 3-layer lyric resolution (mirrors desktop Vanta):
 *  1. user-imported LX script lyrics (auto-matched by provider)
 *  2. built-in public fallbacks: NetEase / QQ / LRCLIB (no auth needed)
 *  3. local disk cache (14-day TTL, 200 entries max)
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    private const val CACHE_FILE = "lyrics_cache.json"
    private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val CACHE_MAX = 200

    private const val UA_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    private var cacheDir: File? = null
    // key -> Pair(expiresAtMs, lines)
    private var memCache: LinkedHashMap<String, Pair<Long, List<Line>>>? = null

    fun init(context: Context) {
        if (cacheDir != null) return
        cacheDir = context.applicationContext.filesDir
    }

    fun fetch(song: Sources.Song): List<Line> {
        val key = "${song.source}::${song.id}"

        // L0: cache
        readCache(key)?.let { return it }

        // L1: imported LX script
        try {
            SourceScripts.resolveLyrics(song.source, song)?.let { raw ->
                parseScriptLyric(raw)?.let { lines ->
                    if (lines.isNotEmpty()) {
                        writeCache(key, lines)
                        return lines
                    }
                }
            }
        } catch (_: Exception) {
        }

        // L2: built-in public fallbacks
        val lines = fetchBuiltin(song)
        if (lines.isNotEmpty()) writeCache(key, lines)
        return lines
    }

    private fun fetchBuiltin(song: Sources.Song): List<Line> {
        // direct by platform id
        if (song.source == "wy") {
            lyricFromNeteaseId(song.id)?.let { return it }
        }
        if (song.source == "tx") {
            lyricFromQQMid(song.id)?.let { return it }
        }
        // LRCLIB by metadata (open, no key)
        if (song.name.isNotBlank()) {
            lyricFromLrclib(song.name, song.singer)?.let { return it }
        }
        // netease search fallback
        if (song.name.isNotBlank()) {
            try {
                val q = java.net.URLEncoder.encode("${song.name} ${song.singer}".trim(), "UTF-8")
                val body = Http.get(
                    "https://music.163.com/api/search/get?s=$q&type=1&limit=1",
                    headers = mapOf("Referer" to "https://music.163.com/")
                ) ?: return emptyList()
                val id = JSONObject(body)
                    .optJSONObject("result")?.optJSONArray("songs")
                    ?.optJSONObject(0)?.optLong("id", 0) ?: 0L
                if (id > 0) lyricFromNeteaseId(id.toString())?.let { return it }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun lyricFromNeteaseId(id: String): List<Line>? {
        return try {
            val body = Http.get(
                "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1",
                headers = mapOf("Referer" to "https://music.163.com/")
            ) ?: return null
            val o = JSONObject(body)
            val lrc = o.optJSONObject("lrc")?.optString("lyric", "")
                .ifEmpty { o.optJSONObject("yrc")?.optString("lyric", "") ?: "" }
            if (lrc.isBlank()) null else parseLrc(lrc).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun lyricFromQQMid(mid: String): List<Line>? {
        return try {
            val body = Http.get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=$mid&format=json&nobase64=1&inCharset=utf8&outCharset=utf-8",
                headers = mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA_WIN)
            ) ?: return null
            val lyric = JSONObject(body).optString("lyric", "")
            if (lyric.isBlank()) null else parseLrc(lyric).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun lyricFromLrclib(title: String, artist: String): List<Line>? {
        return try {
            val params = buildString {
                append("track_name=").append(enc(title))
                if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
            }
            val body = Http.get("https://lrclib.net/api/get?$params") ?: return null
            val o = JSONObject(body)
            if (o.optBoolean("instrumental", false)) return null
            val lyric = o.optString("syncedLyrics", "")
                .ifEmpty { o.optString("plainLyrics", "") }
            if (lyric.isBlank()) null else parseLrc(lyric).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** LX script lyric response shapes: {lyric, tlyric} / {lyricStr} / raw lrc string. */
    private fun parseScriptLyric(raw: String): List<Line>? {
        return try {
            val t = raw.trim()
            if (t.startsWith("[")) return parseLrc(t).ifEmpty { null }
            val o = JSONObject(t)
            val lyric = o.optString("lyric", "")
                .ifEmpty { o.optString("lyricStr", "") }
            if (lyric.isBlank()) null else parseLrc(lyric).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- disk cache ----------------

    @Synchronized
    private fun cacheMap(): LinkedHashMap<String, Pair<Long, List<Line>>> {
        memCache?.let { return it }
        val map = LinkedHashMap<String, Pair<Long, List<Line>>>()
        try {
            val f = cacheFile()
            if (f != null && f.exists()) {
                val o = JSONObject(f.readText())
                val now = System.currentTimeMillis()
                for (k in o.keys()) {
                    val e = o.optJSONObject(k) ?: continue
                    val exp = e.optLong("exp", 0)
                    if (exp <= now) continue
                    val arr = e.optJSONArray("lines") ?: continue
                    val lines = ArrayList<Line>(arr.length())
                    for (i in 0 until arr.length()) {
                        val ln = arr.optJSONObject(i) ?: continue
                        val text = ln.optString("t", "")
                        if (text.isNotEmpty()) lines.add(Line(ln.optLong("ms", 0), text))
                    }
                    if (lines.isNotEmpty()) map[k] = exp to lines
                }
            }
        } catch (_: Exception) {
        }
        // eldest-first trim
        while (map.size > CACHE_MAX) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
        memCache = map
        return map
    }

    private fun readCache(key: String): List<Line>? {
        val (exp, lines) = try {
            cacheMap()[key]
        } catch (_: Exception) {
            null
        } ?: return null
        if (exp <= System.currentTimeMillis() || lines.isEmpty()) {
            try {
                cacheMap().remove(key)
            } catch (_: Exception) {
            }
            return null
        }
        return lines
    }

    @Synchronized
    private fun writeCache(key: String, lines: List<Line>) {
        try {
            val map = cacheMap()
            map[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to lines
            while (map.size > CACHE_MAX) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
            val o = JSONObject()
            for ((k, v) in map) {
                val arr = org.json.JSONArray()
                for (ln in v.second) {
                    arr.put(JSONObject().apply {
                        put("ms", ln.timeMs)
                        put("t", ln.text)
                    })
                }
                o.put(k, JSONObject().apply {
                    put("exp", v.first)
                    put("lines", arr)
                })
            }
            cacheFile()?.writeText(o.toString())
            memCache = map
        } catch (_: Exception) {
        }
    }

    private fun cacheFile(): File? {
        val d = cacheDir ?: return null
        return File(d, CACHE_FILE)
    }

    // ---------------- parsers (kept) ----------------

    /** krc-like: [offset,duration]text<ts,len,0>word…  -> lrc lines */
    private fun parseKrc(krc: String): List<Line> {
        val out = ArrayList<Line>()
        for (line in krc.split("\n")) {
            val m = Regex("""^\[(\d+),(\d+)\](.*)$""").find(line) ?: continue
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val text = m.groupValues[3].replace(Regex("""<\d+,\d+,\d+>"""), "").trim()
            if (text.isNotEmpty()) out.add(Line(startMs, text))
        }
        return out.sortedBy { it.timeMs }
    }

    /** standard lrc parser */
    fun parseLrc(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (line in lrc.split("\n")) {
            val times = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?]""").findAll(line).toList()
            if (times.isEmpty()) continue
            val text = line.substringAfterLast("]").trim()
            if (text.isEmpty()) continue
            for (m in times) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val msFrac = m.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                out.add(Line(min * 60000 + sec * 1000 + msFrac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
