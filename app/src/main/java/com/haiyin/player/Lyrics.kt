package com.haiyin.player

import org.json.JSONObject

/** LRC lyric fetch + parse (qishui krc format + generic lrc). */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun fetch(song: Sources.Song): List<Line> {
        // qishui: luna/h5 track carries lyric in krc-like content
        if (song.source == "qs") {
            try {
                val body = Http.get(
                    "https://api.qishui.com/luna/h5/track?track_id=${song.id}",
                    headers = mapOf("User-Agent" to UA)
                ) ?: return emptyList()
                val raw = JSONObject(body).optJSONObject("lyric")?.optString("content", "") ?: ""
                if (raw.isNotBlank()) return parseKrc(raw)
            } catch (_: Exception) {}
        }
        // netease: direct by id
        if (song.source == "wy") {
            try {
                val body = Http.get(
                    "https://music.163.com/api/song/lyric?id=${song.id}&lv=1&kv=1",
                    headers = mapOf("Referer" to "https://music.163.com/")
                ) ?: return emptyList()
                val lrc = JSONObject(body).optJSONObject("lrc")?.optString("lyric", "") ?: ""
                if (lrc.isNotBlank()) return parseLrc(lrc)
            } catch (_: Exception) {}
        }
        // fallback for kw/mg etc: search netease by name+singer, then fetch its lyric
        if (song.source != "wy") {
            try {
                val q = java.net.URLEncoder.encode("${song.name} ${song.singer}", "UTF-8")
                val body = Http.get(
                    "https://music.163.com/api/search/get?s=$q&type=1&limit=1",
                    headers = mapOf("Referer" to "https://music.163.com/")
                ) ?: return emptyList()
                val id = JSONObject(body)
                    .optJSONObject("result")?.optJSONArray("songs")
                    ?.optJSONObject(0)?.optLong("id", 0) ?: 0L
                if (id > 0) {
                    val lb = Http.get(
                        "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1",
                        headers = mapOf("Referer" to "https://music.163.com/")
                    ) ?: return emptyList()
                    val lrc = JSONObject(lb).optJSONObject("lrc")?.optString("lyric", "") ?: ""
                    if (lrc.isNotBlank()) return parseLrc(lrc)
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

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

    /** standard lrc parser (kept for future sources) */
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
}
