package com.haiyin.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native music source resolver — the same public/observed endpoints that the
 * lx-protocol "非常刀" script uses, verified alive 2026-10-14:
 *  - kuwo search + url:  oiapi.net/api/Kuwo        (search returns rid; url direct)
 *  - netease url:        oiapi.net/api/Music_163   (id -> url, flaky)
 *  - kuwo template:      musicapi.haitangw.net/music/kw.php (302 -> direct ogg/mp3)
 *  - qishui search:      api-vehicle.volcengine.com/v2/search/type
 *  - qishui track:       api.qishui.com/luna/h5/track (needs app sign headers; may 403)
 */
object Sources {

    data class Song(
        val id: String,
        val source: String,   // kw / wy / qs / mg
        val name: String,
        val singer: String,
        val album: String,
        val interval: String,
        val img: String,
    )

    private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    // ---------- search ----------

    fun searchKuwo(keyword: String, page: Int = 1): List<Song> {
        val url = "https://oiapi.net/api/Kuwo?msg=${enc(keyword)}&n=$page&br=5"
        val body = Http.get(url) ?: return emptyList()
        val root = JSONObject(body)
        val d = root.optJSONObject("data") ?: return emptyList()
        val rid = d.optString("rid", "")
        return listOf(
            Song(
                id = rid.removePrefix("MUSIC_"),
                source = "kw",
                name = d.optString("song", ""),
                singer = d.optString("singer", ""),
                album = d.optString("album", ""),
                interval = d.optString("time", ""),
                img = d.optString("picture", ""),
            )
        )
    }

    fun searchQishui(keyword: String, limit: Int = 30): List<Song> {
        val url = "https://api-vehicle.volcengine.com/v2/search/type?keyword=${enc(keyword)}&search_type=music&limit=$limit&real_offset=0&search_source=qishui"
        val body = Http.get(url) ?: return emptyList()
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            out.add(
                Song(
                    id = s.optString("item_id", ""),
                    source = "qs",
                    name = s.optString("title", ""),
                    singer = s.optJSONObject("author_info")?.optString("name", "") ?: "",
                    album = "",
                    interval = fmtDuration(s.optLong("duration", 0)),
                    img = s.optString("cover_url", ""),
                )
            )
        }
        return out
    }

    /** aggregate search across all working sources */
    fun searchAll(keyword: String): List<Song> {
        val results = ArrayList<Song>()
        // qishui first (rich results), then kuwo pages
        try { results.addAll(searchQishui(keyword, 20)) } catch (e: Exception) { }
        try { results.addAll(searchKuwo(keyword, 1)) } catch (e: Exception) { }
        try { results.addAll(searchKuwo(keyword, 2)) } catch (e: Exception) { }
        return results
    }

    // ---------- url resolve ----------

    /**
     * Resolve a playable direct URL. Falls back across providers,
     * then through user-imported lx scripts for any platform.
     */
    fun resolveUrl(song: Song, quality: String = "320k"): String? {
        // 1. lx script sources first for platforms native chain can't reach (tx/kg/mg...)
        try {
            SourceScripts.resolveViaScripts(song.source, song, quality)?.let { return it }
        } catch (_: Exception) {}
        // 1. haitangw kuwo template (works for kw ids, 302 -> direct)
        if (song.source == "kw") {
            val level = if (quality == "flac") "lossless" else if (quality == "128k") "standard" else "exhigh"
            val u = "https://musicapi.haitangw.net/music/kw.php?type=mp3&id=${song.id}&level=$level"
            Http.get(u, followRedirects = true)?.let { body ->
                extractUrl(body)?.let { return it }
            }
        }
        // 2. oiapi kuwo search-based resolve (most reliable today)
        try {
            val kw = searchKuwoExact(song)
            if (kw != null) {
                val url = "https://oiapi.net/api/Kuwo?msg=${enc(kw)}&n=1&br=${brFor(quality)}"
                val body = Http.get(url)
                if (body != null) {
                    extractUrl(JSONObject(body).optJSONObject("data")?.optString("url", null))?.let { return it }
                }
            }
        } catch (_: Exception) { }
        // 3. netease by id
        if (song.source == "wy") {
            val body = Http.get("https://oiapi.net/api/Music_163?id=${song.id}")
            if (body != null) {
                val data = JSONObject(body).optJSONArray("data")
                val obj = if (data != null && data.length() > 0) data.getJSONObject(0) else JSONObject(body).optJSONObject("data")
                extractUrl(obj?.optString("url", null))?.let { return it }
            }
        }
        return null
    }

    private fun searchKuwoExact(song: Song): String? {
        val kw = "${song.name} ${song.singer}".trim()
        return kw.ifEmpty { null }
    }

    private fun brFor(quality: String): Int = when (quality) {
        "flac" -> 1
        "320k" -> 5
        else -> 7
    }

    private fun extractUrl(raw: String?): String? {
        if (raw == null) return null
        if (raw.startsWith("http")) return raw
        // pull first http url from a text blob
        val m = Regex("""https?://[^\s"'<>]+""").find(raw) ?: return null
        return m.value
    }

    // ---------- playlist import (netease share links) ----------

    /**
     * Parse a netease playlist id from a share link like
     * https://music.163.com/playlist?id=123456 or pure id string.
     * Uses the /api/v3/playlist/detail endpoint (ar/al short fields).
     */
    fun importNeteasePlaylist(input: String): List<Song> {
        val id = Regex("""[?&/]id=(\d+)""").find(input)?.groupValues?.get(1)
            ?: Regex("""^(\d{4,})$""").find(input.trim())?.groupValues?.get(1)
            ?: return emptyList()
        val body = Http.get(
            "https://music.163.com/api/v3/playlist/detail?id=$id&n=500",
            headers = mapOf("Referer" to "https://music.163.com")
        ) ?: return emptyList()
        val arr = JSONObject(body).optJSONObject("playlist")?.optJSONArray("tracks")
            ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(
                Song(
                    id = t.optLong("id", 0).toString(),
                    source = "wy",
                    name = t.optString("name", ""),
                    singer = t.optJSONArray("ar")?.let { a ->
                        List(a.length()) { a.getJSONObject(it).optString("name") }.joinToString("/")
                    } ?: "",
                    album = t.optJSONObject("al")?.optString("name", "") ?: "",
                    interval = fmtDurationMs(t.optLong("dt", 0)),
                    img = t.optJSONObject("al")?.optString("picUrl", "") ?: "",
                )
            )
        }
        return out
    }

    private fun fmtDurationMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- helpers ----------

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun fmtDuration(sec: Long): String {
        if (sec <= 0) return "--:--"
        // qishui returns ms sometimes
        val s = if (sec > 10000) sec / 1000 else sec
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
