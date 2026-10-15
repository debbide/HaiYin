package com.haiyin.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent favorites store (JSON file in filesDir) with reactive version. */
object Favorites {

    private var file: File? = null
    private val songs = ArrayList<Sources.Song>()

    // bumps on every change; UI collects this and re-reads [all]
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "favorites.json")
        load()
    }

    val all: List<Sources.Song> get() = synchronized(songs) { ArrayList(songs) }

    fun isFav(song: Sources.Song): Boolean = synchronized(songs) {
        songs.any { it.source == song.source && it.id == song.id }
    }

    fun toggle(song: Sources.Song): Boolean = synchronized(songs) {
        val idx = songs.indexOfFirst { it.source == song.source && it.id == song.id }
        if (idx >= 0) {
            songs.removeAt(idx)
            save(); bump(); false
        } else {
            songs.add(0, song)
            save(); bump(); true
        }
    }

    private fun bump() { _version.value = _version.value + 1 }

    private fun load() {
        try {
            val f = file ?: return
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            songs.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                songs.add(
                    Sources.Song(
                        id = o.getString("id"),
                        source = o.getString("source"),
                        name = o.getString("name"),
                        singer = o.optString("singer", ""),
                        album = o.optString("album", ""),
                        interval = o.optString("interval", ""),
                        img = o.optString("img", ""),
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            val f = file ?: return
            val arr = JSONArray()
            for (s in songs) {
                arr.put(
                    JSONObject()
                        .put("id", s.id).put("source", s.source).put("name", s.name)
                        .put("singer", s.singer).put("album", s.album)
                        .put("interval", s.interval).put("img", s.img)
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
