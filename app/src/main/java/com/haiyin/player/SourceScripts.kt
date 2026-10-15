package com.haiyin.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * User-imported LX source store — the ONLY music-URL resolution path in the app.
 * There are no built-in sources: every entry comes from a script the user imported.
 *
 * Storage: single JSON file (filesDir/imported_sources.json), one row per
 * (script x provider), mirroring the desktop Vanta model.
 * Playback auto-matches entries by providerId — no manual binding step.
 */
object SourceScripts {

    data class ImportedSource(
        val id: String,
        val name: String,
        val providerId: String,   // wy / tx / kw / kg / mg ...
        val providerName: String,
        val script: String,
        val qualities: List<String>,
        val enabled: Boolean,
        val author: String,
        val version: String,
        val description: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("providerId", providerId)
            put("providerName", providerName)
            put("script", script)
            put("qualities", JSONArray(qualities))
            put("enabled", enabled)
            put("author", author)
            put("version", version)
            put("description", description)
        }

        companion object {
            fun fromJson(o: JSONObject): ImportedSource? = try {
                val id = o.optString("id")
                val providerId = o.optString("providerId")
                val script = o.optString("script")
                if (id.isEmpty() || providerId.isEmpty() || script.isEmpty()) null
                else ImportedSource(
                    id = id,
                    name = o.optString("name"),
                    providerId = providerId,
                    providerName = o.optString("providerName"),
                    script = script,
                    qualities = o.optJSONArray("qualities")?.let { a ->
                        List(a.length()) { a.optString(it) }
                    } ?: emptyList(),
                    enabled = o.optBoolean("enabled", true),
                    author = o.optString("author"),
                    version = o.optString("version"),
                    description = o.optString("description"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    data class ImportResult(
        /** number of newly added provider entries (0 = already up to date) */
        val importedCount: Int,
        val providerNames: List<String>,
        val message: String,
    )

    private const val STORE_FILE = "imported_sources.json"
    private const val PREFS = "lx_source_prefs"
    private const val KEY_LAST_USED = "last_used:"

    private var appContext: Context? = null
    private var cache: MutableList<ImportedSource>? = null
    private var idSeed = 0
    // scriptHash -> runtime
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, LxRuntime>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        migrateLegacyDir()
    }

    // ---------------- persistence ----------------

    @Synchronized
    fun list(): List<ImportedSource> {
        cache?.let { return it.toList() }
        val loaded = try {
            val f = storeFile()
            if (f == null || !f.exists()) mutableListOf()
            else {
                val arr = JSONArray(f.readText())
                List(arr.length()) { ImportedSource.fromJson(arr.getJSONObject(it)) }
                    .filterNotNull().toMutableList()
            }
        } catch (_: Exception) {
            mutableListOf()
        }
        cache = loaded
        return loaded.toList()
    }

    @Synchronized
    private fun save(list: List<ImportedSource>) {
        cache = list.toMutableList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            storeFile()?.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun storeFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, STORE_FILE)
    }

    // ---------------- import ----------------

    private data class Payload(
        val script: String,
        val name: String,
        val author: String,
        val version: String,
        val description: String,
    )

    /** Accepts raw script text or a JSON wrapper {script/js/code, name, author, ...}. */
    private fun normalizePayload(rawInput: String): Payload {
        val t = rawInput.trim()
        if (t.isEmpty()) throw IllegalStateException("请先提供脚本内容")
        try {
            val o = JSONObject(t)
            val script = o.optString("script")
                .ifEmpty { o.optString("js") }
                .ifEmpty { o.optString("code") }
            if (script.isNotEmpty()) {
                return Payload(
                    script = script,
                    name = o.optString("name").ifEmpty { o.optString("title") },
                    author = o.optString("author"),
                    version = o.optString("version"),
                    description = o.optString("description"),
                )
            }
        } catch (_: Exception) {
            // not JSON — treat as raw script
        }
        return Payload(t, "", "", "", "")
    }

    private fun extractScriptName(script: String): String {
        return Regex("""@name\s+([^\r\n*]+)""").find(script.take(2000))
            ?.groupValues?.get(1)?.trim()?.take(24) ?: ""
    }

    /**
     * Import a script. Bootstraps it (blocking — call on IO thread),
     * expands to one entry per provider, dedups by (providerId + script hash).
     */
    @Synchronized
    fun importFromText(rawInput: String): ImportResult {
        val ctx = appContext ?: throw IllegalStateException("SourceScripts not initialized")
        val payload = normalizePayload(rawInput)
        val script = payload.script
        if (!script.contains("lx")) throw IllegalStateException("非有效的 LX 协议脚本")

        val rt = LxRuntime.get(ctx, script)
        val providers = try {
            rt.bootstrap()
        } catch (e: Exception) {
            throw IllegalStateException("脚本初始化失败：${e.message}")
        }
        if (providers.isEmpty()) throw IllegalStateException("脚本中没有可用的提供方")

        val bundleName = payload.name.ifEmpty { extractScriptName(script) }
        val scriptHash = sha1(script)
        val current = list().toMutableList()
        var added = 0
        val names = ArrayList<String>()

        for ((pid, prov) in providers) {
            val providerId = pid.trim().lowercase()
            if (providerId.isEmpty()) continue
            val label = prov.name.ifEmpty { providerLabel(providerId) }
            if (!names.contains(label)) names.add(label)

            val existing = current.find {
                it.providerId == providerId && sha1(it.script) == scriptHash
            }
            val entry = ImportedSource(
                id = existing?.id ?: "${providerId}_${System.currentTimeMillis()}_${idSeed++}",
                name = bundleName.ifEmpty { "${label}音源" },
                providerId = providerId,
                providerName = label,
                script = script,
                qualities = prov.qualitys,
                enabled = existing?.enabled ?: true,
                author = payload.author,
                version = payload.version,
                description = payload.description,
            )
            if (existing != null) {
                current[current.indexOf(existing)] = entry
            } else {
                current.add(0, entry)
                added++
            }
        }
        save(current)

        val displayName = bundleName.ifEmpty { names.firstOrNull() ?: "音源" }
        val message = if (added > 0)
            "已导入 $displayName，${names.size} 个平台可用（${names.joinToString("、")}）"
        else
            "$displayName 已是最新，无新增线路"
        return ImportResult(added, names, message)
    }

    fun importFromUrl(url: String): ImportResult {
        val u = url.trim()
        if (!u.startsWith("http")) throw IllegalStateException("导入地址格式不正确")
        val body = Http.get(u, timeoutMs = 15000)
            ?: throw IllegalStateException("下载脚本失败，请检查网络")
        return importFromText(body)
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val cur = list().toMutableList()
        val idx = cur.indexOfFirst { it.id == id }
        if (idx >= 0) {
            cur[idx] = cur[idx].copy(enabled = enabled)
            save(cur)
        }
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filter { it.id != id })
    }

    // ---------------- resolve (auto-match, no binding UI) ----------------

    private fun candidatesFor(sourceId: String): List<ImportedSource> {
        val key = sourceId.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val lastUsedId = lastUsedFor(key)
        return list()
            .filter { it.enabled && it.providerId.equals(key, ignoreCase = true) }
            .sortedBy { if (it.id == lastUsedId) 0 else 1 }
    }

    private fun musicInfoJson(song: Sources.Song, sourceId: String): String =
        JSONObject().apply {
            put("songmid", song.id)
            put("id", song.id)
            put("hash", song.id)
            put("name", song.name)
            put("singer", song.singer)
            put("source", sourceId)
        }.toString()

    /**
     * Resolve a playable URL via user-imported scripts whose provider matches
     * [sourceId]. Tries last-successful entry first, then the rest in order.
     * Returns null when nothing is imported for the platform or all fail.
     */
    fun resolveMusicUrl(sourceId: String, song: Sources.Song, quality: String): String? {
        val ctx = appContext ?: return null
        val key = sourceId.trim().lowercase()
        val candidates = candidatesFor(sourceId)
        if (candidates.isEmpty()) return null
        val info = musicInfoJson(song, sourceId)
        for (entry in candidates) {
            try {
                val rt = runtimes.getOrPut(sha1(entry.script)) { LxRuntime.get(ctx, entry.script) }
                if (rt.providers.isEmpty()) {
                    try { rt.bootstrap() } catch (_: Exception) {}
                }
                val url = rt.resolveMusicUrl(entry.providerId, info, quality)
                if (url.isNotBlank()) {
                    rememberLastUsed(key, entry.id)
                    return url
                }
            } catch (_: Exception) {
                // try next candidate
            }
        }
        return null
    }

    /**
     * Resolve lyrics via user-imported scripts. Returns the raw lyric JSON
     * string (caller extracts the lyric text), or null.
     */
    fun resolveLyrics(sourceId: String, song: Sources.Song): String? {
        val ctx = appContext ?: return null
        val candidates = candidatesFor(sourceId)
        if (candidates.isEmpty()) return null
        val info = musicInfoJson(song, sourceId)
        for (entry in candidates) {
            try {
                val rt = runtimes.getOrPut(sha1(entry.script)) { LxRuntime.get(ctx, entry.script) }
                if (rt.providers.isEmpty()) {
                    try { rt.bootstrap() } catch (_: Exception) {}
                }
                val actions = rt.providers.entries
                    .find { it.key.equals(entry.providerId, ignoreCase = true) }
                    ?.value?.actions
                if (actions != null && !actions.contains("lyric")) continue
                return rt.resolveLyrics(entry.providerId, info)
            } catch (_: Exception) {
                // try next candidate
            }
        }
        return null
    }

    // ---------------- last-used memory ----------------

    private fun lastUsedFor(providerId: String): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_USED + providerId, null)

    private fun rememberLastUsed(providerId: String, sourceId: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_LAST_USED + providerId, sourceId)?.apply()
    }

    // ---------------- legacy migration ----------------

    /** One-time: import old lx_scripts dir (.js files) into the new store. */
    private fun migrateLegacyDir() {
        val ctx = appContext ?: return
        val legacy = File(ctx.filesDir, "lx_scripts")
        if (!legacy.exists()) return
        try {
            legacy.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".js")) return@forEach
                try {
                    val content = f.readText()
                    if (content.length > 100) {
                        try { importFromText(content) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                }
            }
            legacy.renameTo(File(ctx.filesDir, "lx_scripts.migrated"))
        } catch (_: Exception) {
        }
    }

    // ---------------- helpers ----------------

    private fun providerLabel(providerId: String): String = when (providerId.trim().lowercase()) {
        "tx" -> "QQ音乐"
        "wy" -> "网易云音乐"
        "kw" -> "酷我音乐"
        "kg" -> "酷狗音乐"
        "mg" -> "咪咕音乐"
        else -> providerId
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
