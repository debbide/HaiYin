package com.haiyin.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages user-imported lx-protocol JS source scripts.
 * Scripts are stored as plain files; runtime instances are cached per script.
 */
object SourceScripts {

    private var dir: File? = null
    private val listeners = ArrayList<() -> Unit>()

    // id -> script
    private val scripts = LinkedHashMap<String, String>()
    // id -> bootstrapped providers (id -> providerId -> Provider)
    private val providers = LinkedHashMap<String, Map<String, LxRuntime.Provider>>()
    // (scriptId, providerId) -> runtime
    private val runtimes = HashMap<String, LxRuntime>()

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "lx_scripts").apply { mkdirs() }
        reload()
    }

    fun reload() {
        scripts.clear()
        providers.clear()
        val d = dir ?: return
        d.listFiles()?.sortedBy { it.name }?.forEach { f ->
            try {
                val content = f.readText()
                scripts[f.nameWithoutExtension] = content
            } catch (_: Exception) {}
        }
    }

    fun add(name: String, content: String): Boolean {
        val d = dir ?: return false
        val safeName = name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
        val f = File(d, "$safeName.js")
        f.writeText(content)
        reload()
        bootstrapAll()
        return true
    }

    fun remove(id: String) {
        val d = dir ?: return
        File(d, "$id.js").delete()
        reload()
    }

    fun list(): List<Triple<String, String, Int>> =
        scripts.map { Triple(it.key, it.value, providers[it.key]?.size ?: 0) }

    /** Try bootstrapping every script; safe to call repeatedly. */
    fun bootstrapAll() {
        val appCtx = Player.appContextPublic()
        for ((id, script) in scripts) {
            if (providers.containsKey(id)) continue
            try {
                val rt = LxRuntime.get(appCtx, script)
                val provs = rt.bootstrap()
                if (provs.isNotEmpty()) {
                    providers[id] = provs
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Resolve a music URL via all lx scripts that support [sourceId].
     * Returns a playable URL or null.
     */
    fun resolveViaScripts(sourceId: String, song: Sources.Song, quality: String): String? {
        val appCtx = try { Player.appContextPublic() } catch (_: Exception) { return null }
        for ((scriptId, provs) in providers) {
            val providerId = provs.keys.firstOrNull { it.equals(sourceId, ignoreCase = true) }
                ?: continue
            val rt = runtimes.getOrPut(scriptId) { LxRuntime.get(appCtx, scripts[scriptId] ?: return null) }
            try {
                val info = JSONObject().apply {
                    put("songmid", song.id)
                    put("id", song.id)
                    put("hash", song.id)
                    put("name", song.name)
                    put("singer", song.singer)
                    put("source", sourceId)
                }
                return rt.resolveMusicUrl(providerId, info.toString(), quality)
            } catch (_: Exception) {}
        }
        return null
    }

    fun onChange(listener: () -> Unit) { listeners.add(listener) }
    private fun notifyChanged() { listeners.forEach { it() } }
}
