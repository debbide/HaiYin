package com.haiyin.player

import org.json.JSONObject

/**
 * Track metadata search + playback URL resolution.
 *
 * Architecture (mirrors desktop Vanta):
 *  - SEARCH is built-in but metadata-only: public NetEase/QQ APIs return
 *    track info + artwork, never playable URLs. This is not a "source".
 *  - RESOLUTION has no built-in path: playable URLs come ONLY from
 *    user-imported LX scripts (see SourceScripts).
 */
object Sources {

    data class Song(
        val id: String,
        val source: String,   // wy / tx  (LX provider codes)
        val name: String,
        val singer: String,
        val album: String,
        val interval: String,
        val img: String,
    )

    // ---------- metadata search (built-in, no playback URLs) ----------

    fun searchNetease(keyword: String, limit: Int = 20): List<Song> {
        val q = enc(keyword)
        val body = Http.get(
            "https://music.163.com/api/search/get?s=$q&type=1&limit=$limit",
            headers = mapOf("Referer" to "https://music.163.com/")
        ) ?: return emptyList()
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0)
            if (id <= 0) continue
            val artists = s.optJSONArray("artists")
            val singer = if (artists != null) {
                List(artists.length()) { artists.optJSONObject(it)?.optString("name", "") ?: "" }
                    .filter { it.isNotEmpty() }.joinToString("/")
            } else ""
            val album = s.optJSONObject("album")
            out.add(
                Song(
                    id = id.toString(),
                    source = "wy",
                    name = s.optString("name", ""),
                    singer = singer,
                    album = album?.optString("name", "") ?: "",
                    interval = fmtDurationMs(s.optLong("duration", 0)),
                    img = album?.optString("picUrl", "") ?: "",
                )
            )
        }
        return out
    }

    fun searchQQ(keyword: String, limit: Int = 20): List<Song> {
        // Mobile endpoint first (mirrors desktop vanta), desktop endpoint as fallback.
        val raws = searchQQMobile(keyword, limit).ifEmpty { searchQQDesktop(keyword, limit) }
        val out = ArrayList<Song>(raws.size)
        for (s in raws) {
            val mid = s.optString("mid", "")
            if (mid.isEmpty()) continue
            val singers = s.optJSONArray("singer")
            val singer = if (singers != null) {
                List(singers.length()) { singers.optJSONObject(it)?.optString("name", "") ?: "" }
                    .filter { it.isNotEmpty() }.joinToString("/")
            } else ""
            val album = s.optJSONObject("album")
            val albumMid = album?.optString("mid", "") ?: ""
            out.add(
                Song(
                    id = mid,
                    source = "tx",
                    name = s.optString("title", "").ifEmpty { s.optString("name", "") },
                    singer = singer,
                    album = album?.optString("name", "") ?: "",
                    interval = fmtDuration(s.optLong("interval", 0)),
                    img = if (albumMid.isNotEmpty())
                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumMid}.jpg"
                    else "",
                )
            )
        }
        return out
    }

    /** QQ mobile search: same payload shape as desktop vanta. */
    private fun searchQQMobile(keyword: String, limit: Int): List<JSONObject> {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", "11")
                put("cv", "14090508")
            })
            put("req", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicMobile")
                put("param", JSONObject().apply {
                    put("search_type", 0)
                    put("query", keyword)
                    put("page_num", 1)
                    put("num_per_page", limit)
                })
            })
        }.toString()
        val body = Http.post(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            payload,
            headers = mapOf("User-Agent" to "QQMusic 14090508(android 12)")
        ) ?: return emptyList()
        return extractQqItems(body, "req")
    }

    /** QQ desktop search (previous behavior, kept as fallback). */
    private fun searchQQDesktop(keyword: String, limit: Int): List<JSONObject> {
        val payload = JSONObject().apply {
            put("req_1", JSONObject().apply {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                put("param", JSONObject().apply {
                    put("num_per_page", limit)
                    put("page_num", 1)
                    put("query", keyword)
                    put("search_type", 0)
                })
            })
        }.toString()
        val body = Http.post(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            payload,
            headers = mapOf(
                "Referer" to "https://y.qq.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            )
        ) ?: return emptyList()
        return extractQqItems(body, "req_1")
    }

    /**
     * Pull the song array out of a musicu.fcg response. Mobile responses nest it
     * under data.body.item_song (see desktop vanta); desktop ones under
     * data.body.song.list.
     */
    private fun extractQqItems(body: String, key: String): List<JSONObject> {
        return try {
            val data = JSONObject(body).optJSONObject(key)?.optJSONObject("data")
                ?: return emptyList()
            val arr = data.optJSONObject("body")?.optJSONArray("item_song")
                ?: data.optJSONArray("item_song")
                ?: data.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
                ?: return emptyList()
            List(arr.length()) { arr.optJSONObject(it) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** aggregate metadata search across built-in public providers */
    fun searchAll(keyword: String): List<Song> {
        val results = ArrayList<Song>()
        try { results.addAll(searchNetease(keyword)) } catch (_: Exception) {}
        try { results.addAll(searchQQ(keyword)) } catch (_: Exception) {}
        return results
    }

    // ---------- playback URL resolution: user-imported scripts only ----------

    /**
     * Resolve a playable URL. ONLY user-imported LX scripts are consulted —
     * there is deliberately no built-in fallback. Returns null when the user
     * hasn't imported a script covering [song.source], or all fail.
     */
    fun resolveUrl(song: Song, quality: String = "320k"): String? {
        return try {
            SourceScripts.resolveMusicUrl(song.source, song, quality)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- playlist import (netease share links, metadata only) ----------

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

    // ---------- helpers ----------

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun fmtDurationMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmtDuration(sec: Long): String {
        if (sec <= 0) return "--:--"
        val s = if (sec > 10000) sec / 1000 else sec
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
