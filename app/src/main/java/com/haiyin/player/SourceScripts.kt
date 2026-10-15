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

    /** A script that told us a newer version of itself exists. */
    data class ScriptUpdate(
        val scriptHash: String,
        val scriptName: String,
        val message: String,
        val url: String,
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
     * Best-effort metadata for CeruMusic plugins, which declare a `pluginInfo`
     * object literal instead of the `@name` header LX scripts use. Returns whatever
     * of name/version/author/description it can find; empty map when absent.
     */
    private fun extractPluginMeta(script: String): Map<String, String> {
        val out = HashMap<String, String>()
        val block = Regex("""pluginInfo\s*=\s*\{([\s\S]{0,1500}?)\n?\}""")
            .find(script)?.groupValues?.get(1) ?: return out
        for (key in listOf("name", "version", "author", "description")) {
            Regex("""["']?""" + key + """["']?\s*:\s*["']([^"']*)["']""")
                .find(block)?.let { out[key] = it.groupValues[1].trim() }
        }
        return out
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
        // LX scripts touch `lx`; CeruMusic v1 plugins are CommonJS modules that talk
        // through `cerumusic`. Accept either and reject everything else early.
        val isLx = script.contains("lx")
        val isCr = script.contains("cerumusic") || script.contains("module.exports")
        if (!isLx && !isCr) throw IllegalStateException("非有效的 LX / CeruMusic 协议脚本")

        val rt = LxRuntime.get(ctx, script)
        val providers = try {
            rt.bootstrap()
        } catch (e: Exception) {
            throw IllegalStateException("脚本初始化失败：${e.message}")
        }
        if (providers.isEmpty()) throw IllegalStateException("脚本中没有可用的提供方")

        // CeruMusic plugins declare metadata in a `pluginInfo` object rather than in
        // the import dialog, so fill any gaps from the script text itself.
        val meta = extractPluginMeta(script)
        val bundleName = payload.name
            .ifEmpty { meta["name"].orEmpty() }
            .ifEmpty { extractScriptName(script) }
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
                author = payload.author.ifEmpty { meta["author"].orEmpty() },
                version = payload.version.ifEmpty { meta["version"].orEmpty() },
                description = payload.description.ifEmpty { meta["description"].orEmpty() },
            )
            if (existing != null) {
                current[current.indexOf(existing)] = entry
            } else {
                current.add(0, entry)
                added++
            }
        }
        save(current)
        // a re-import of a newer build leaves the previous build's runtime orphaned
        pruneRuntimes()

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
        pruneRuntimes()
    }

    /**
     * Tear down runtimes that no longer belong to any stored entry.
     *
     * Without this, removing a source — or importing a newer build of one, which
     * hashes differently and therefore gets a fresh runtime — leaves its QuickJS
     * context alive for the life of the process. Nothing else calls
     * [LxRuntime.shutdownAll], so this is the only place runtimes get freed.
     */
    private fun pruneRuntimes() {
        val entries = list()
        val liveHashes = entries.map { sha1(it.script) }.toSet()
        val liveScripts = entries.map { it.script }.toSet()

        // drop our own handles on scripts that are gone
        runtimes.keys.filter { it !in liveHashes }.forEach { runtimes.remove(it) }

        // and tear down the engines themselves. This also catches runtimes created
        // only during import, which never entered `runtimes`.
        LxRuntime.releaseAllExcept(liveScripts)
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

    private fun musicInfoJson(
        song: Sources.Song,
        sourceId: String,
        qualities: List<String> = emptyList(),
    ): String =
        JSONObject().apply {
            put("songmid", song.id)
            put("id", song.id)
            put("hash", song.id)
            put("name", song.name)
            put("singer", song.singer)
            put("source", sourceId)
            // Scripts read these for display and quality negotiation; omitting them
            // made some of them bail out or fall back to 128k.
            put("albumName", song.album)
            put("album", song.album)
            put("interval", song.interval)
            put("_interval", song.interval)
            put("picUrl", song.img)
            put("img", song.img)
            if (qualities.isNotEmpty()) put("qualitys", JSONArray(qualities))
        }.toString()

    /**
     * Resolve a playable URL via user-imported scripts whose provider matches
     * [sourceId]. Tries last-successful entry first, then the rest in order.
     * Returns null when nothing is imported for the platform or all fail.
     */
    /** Last resolve failure reason, for surfacing in UI diagnostics. */
    @Volatile
    var lastResolveError: String? = null
        private set

    /**
     * Native fallback for API-wrapper scripts, kept as a safety net for the case
     * the JS engine can't run async code. With the harlonwang engine promises work,
     * so this path is normally dormant. Extracts API_URL / API_KEY from the script
     * and performs the musicUrl HTTP directly.
     */
    private fun resolveMusicUrlNative(
        entry: ImportedSource,
        sourceId: String,
        song: Sources.Song,
        quality: String,
    ): String? {
        val script = entry.script
        val apiUrl = Regex("""(?:const|let|var)\s+API_URL\s*=\s*["']([^"']+)["']""")
            .find(script)?.groupValues?.get(1)?.trim()?.trimEnd('/')
            ?: return null
        val apiKey = Regex("""(?:const|let|var)\s+API_KEY\s*=\s*["']([^"']*)["']""")
            .find(script)?.groupValues?.get(1) ?: ""
        val songId = song.id
        if (songId.isBlank()) return null
        // Scripts declare API_URL either with the music endpoint already on it
        // (v7: ".../api/music") or as a plain base with the sub-path added later
        // (v8.9.1: ".../api" + "/music/url"). Normalise to the full path — hardcoding
        // "$apiUrl/url" produced ".../api/url" for the latter and 404'd.
        val base = apiUrl.trimEnd('/')
        val musicBase = if (base.endsWith("/music")) base else "$base/music"
        val reqUrl = "$musicBase/url?source=${urlEnc(sourceId)}&songId=${urlEnc(songId)}&quality=${urlEnc(quality)}"
        val body = try {
            val conn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "lx-music-mobile/2.0.0")
            if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-Key", apiKey)
            val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            text
        } catch (e: Exception) {
            lastResolveError = "网络请求失败：${e.message}"
            return null
        }
        val obj = try { JSONObject(body) } catch (_: Exception) {
            lastResolveError = "接口返回异常"
            return null
        }
        return when (val code = obj.optInt("code", -1)) {
            200 -> obj.optString("url", "").ifEmpty {
                lastResolveError = "接口未返回播放地址"
                null
            }
            403 -> { lastResolveError = "权限不足或Key失效"; null }
            429 -> { lastResolveError = "请求过速，请稍后再试"; null }
            else -> {
                lastResolveError = obj.optString("message", "未知错误").ifEmpty { "未知错误 ($code)" }
                null
            }
        }
    }

    private fun urlEnc(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    fun resolveMusicUrl(sourceId: String, song: Sources.Song, quality: String): String? {
        val ctx = appContext ?: run { lastResolveError = "SourceScripts 未初始化"; return null }
        val key = sourceId.trim().lowercase()
        val candidates = candidatesFor(sourceId)
        if (candidates.isEmpty()) {
            lastResolveError = "没有可用线路（${key}）"
            return null
        }
        // If the JS engine can't run promises, API-wrapper scripts go native directly.
        val jsAsyncWorks = LxRuntime.promisesWork()
        var lastErr: String? = null
        for (entry in candidates) {
            val info = musicInfoJson(song, sourceId, entry.qualities)
            try {
                if (!jsAsyncWorks) {
                    val url = resolveMusicUrlNative(entry, key, song, quality)
                    if (!url.isNullOrBlank()) {
                        rememberLastUsed(key, entry.id)
                        lastResolveError = null
                        return url
                    }
                    lastErr = lastResolveError ?: "原生解析失败"
                    continue
                }
                val rt = runtimes.getOrPut(sha1(entry.script)) { LxRuntime.get(ctx, entry.script) }
                if (rt.providers.isEmpty()) {
                    try { rt.bootstrap() } catch (_: Exception) {}
                }
                val url = rt.resolveMusicUrl(entry.providerId, info, quality)
                if (url.isNotBlank()) {
                    rememberLastUsed(key, entry.id)
                    lastResolveError = null
                    return url
                }
                lastErr = "脚本返回空地址"
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                // try next candidate
            }
        }
        lastResolveError = lastErr ?: "未知错误"
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
        for (entry in candidates) {
            val info = musicInfoJson(song, sourceId, entry.qualities)
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

    // ---------------- bundles (one card per imported script) ----------------

    data class ScriptBundle(
        val scriptHash: String,
        val name: String,
        val author: String,
        val version: String,
        val description: String,
        val entries: List<ImportedSource>,
    ) {
        val allEnabled: Boolean get() = entries.all { it.enabled }
    }

    /** Group provider entries by script: the UI shows one card per script. */
    @Synchronized
    fun bundles(): List<ScriptBundle> {
        return list().groupBy { sha1(it.script) }.values.map { entries ->
            val first = entries.first()
            ScriptBundle(
                scriptHash = sha1(first.script),
                name = first.name,
                author = first.author,
                version = first.version,
                description = first.description,
                entries = entries,
            )
        }
    }

    // ---------------- update alerts ----------------

    /**
     * Update alerts raised by scripts that have already bootstrapped in this
     * process. A script only learns about its own updates after it has run once,
     * so this fills in lazily; use [checkUpdates] to force the issue.
     */
    fun updateAlerts(): List<ScriptUpdate> = runtimes.mapNotNull { (hash, rt) ->
        val alert = rt.updateAlert ?: return@mapNotNull null
        ScriptUpdate(
            scriptHash = hash,
            scriptName = alert.scriptName.ifBlank { hash.take(8) },
            message = alert.message,
            url = alert.url,
        )
    }

    /**
     * Boot every enabled script so each can report its own update status, then
     * return how many have a newer version available. Blocking — call from IO.
     *
     * A script's update check fires during bootstrap and is now queued and
     * replayed (see LxRuntime), so it needs a moment to come back over the wire
     * before we count the alerts.
     */
    fun checkUpdates(): Int {
        val ctx = appContext ?: return 0
        for (bundle in bundles()) {
            if (!bundle.allEnabled) continue
            try {
                val rt = runtimes.getOrPut(bundle.scriptHash) {
                    LxRuntime.get(ctx, bundle.entries.first().script)
                }
                rt.bootstrap()
            } catch (_: Exception) {
                // one broken script must not stop the others from being checked
            }
        }
        // The update checks are now in flight (they were queued during bootstrap and
        // replayed). Wait for them to actually land rather than sleeping a guess —
        // a cold TLS handshake can easily outlast a fixed delay.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (runtimes.values.none { it.inFlightRequests() > 0 }) break
            try { Thread.sleep(120) } catch (_: InterruptedException) { break }
        }
        // let the last response's microtask chain reach lx.send('updateAlert')
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        return updateAlerts().size
    }

    @Synchronized
    fun setBundleEnabled(scriptHash: String, enabled: Boolean) {
        val cur = list().toMutableList()
        var changed = false
        for (i in cur.indices) {
            if (sha1(cur[i].script) == scriptHash && cur[i].enabled != enabled) {
                cur[i] = cur[i].copy(enabled = enabled)
                changed = true
            }
        }
        if (changed) save(cur)
    }

    @Synchronized
    fun removeBundle(scriptHash: String) {
        save(list().filter { sha1(it.script) != scriptHash })
        pruneRuntimes()
    }

    /** Chinese display label for a provider id (kw -> 酷我, ...). */
    fun providerLabel(providerId: String): String = when (providerId.trim().lowercase()) {
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
