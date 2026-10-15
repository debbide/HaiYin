package com.haiyin.player

import android.content.Context
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * lx-protocol JS runtime (QuickJS via wang.harlon.quickjs:wrapper-android),
 * ported from Vanta's flutter_js bridge design:
 *  - JS land keeps a callback table; native <-> JS talk via __hyBridge.post()
 *  - lx.request sends a message to native (JSON string); native does HTTP on its own
 *    executor and calls back into JS with the response
 *  - requests are serialized per runtime (JS engines are single-threaded)
 *  - bootstrap window answers lx.request with a fake response so scripts don't break
 *
 * Engine notes (harlonwang wrapper):
 *  - every evaluate()/call() drains the promise microtask queue (JS_ExecutePendingJob),
 *    so async/await actually works — unlike app.cash.quickjs 0.9.2, which never did
 *  - all context access MUST happen on the thread that created it (checkSameThread);
 *    everything funnels through onJsThread()
 *  - native lib is 16KB page-aligned (ELF LOAD segments = 0x4000), no 16KB-device warning
 *  - an unhandled promise rejection makes the next evaluate() throw QuickJSException;
 *    our invoke chain always ends with .catch(), so only genuinely buggy scripts trip it
 */
class LxRuntime private constructor(
    private val appContext: Context,
    private val script: String,
) {

    companion object {
        init {
            try { QuickJSLoader.init() } catch (_: Exception) { /* already loaded */ }
        }

        private val scriptRuntimes = ConcurrentHashMap<String, LxRuntime>()

        /**
         * Keys worth walking when a script hands back a structure instead of a bare
         * URL. Mirrors Vanta's `_extractStreamUrl` ordering.
         */
        private val URL_KEYS = listOf(
            "url", "playUrl", "play_url", "musicUrl", "music_url",
            "src", "body", "data", "result",
        )
        private val HTTP_URL = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

        /** Used when a script does not set its own User-Agent. */
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun get(appContext: Context, script: String): LxRuntime =
            scriptRuntimes.getOrPut(script) { LxRuntime(appContext.applicationContext, script) }

        /**
         * Destroy every runtime whose script is not in [keep]. Called after the
         * source store changes so removed sources — and superseded builds, which
         * hash differently and therefore got their own runtime — stop holding a
         * QuickJS context open.
         */
        fun releaseAllExcept(keep: Set<String>) {
            val it = scriptRuntimes.entries.iterator()
            while (it.hasNext()) {
                val (script, rt) = it.next()
                if (script in keep) continue
                it.remove()
                rt.destroy()
            }
        }

        fun shutdownAll() {
            scriptRuntimes.values.forEach { it.destroy() }
            scriptRuntimes.clear()
        }

        @Volatile private var promisesWorkCache: Boolean? = null

        /** Cached: can this QuickJS build run promise microtasks? */
        fun promisesWork(): Boolean {
            promisesWorkCache?.let { return it }
            val works = diagnosePromises().contains("resolved:42")
            promisesWorkCache = works
            return works
        }

        /**
         * Diagnostic: does this QuickJS build run promise microtasks after
         * evaluate() returns? The whole LX invoke chain depends on it.
         */
        fun diagnosePromises(): String {
            val ctx = try { QuickJSContext.create() } catch (e: Exception) {
                return "QuickJS 创建失败：${e.message}"
            }
            return try {
                // NOTE: this wrapper drains the microtask queue at the end of every
                // evaluate(), so .then() has already run when evaluate() returns.
                ctx.evaluate(
                    "globalThis.__diag = 'pending';" +
                        "Promise.resolve(42).then(v => { globalThis.__diag = 'resolved:' + v; });" +
                        "'sync';"
                )
                val afterFirst = ctx.evaluate("globalThis.__diag;")?.toString()
                ctx.evaluate("(async () => { const v = await Promise.resolve(7); globalThis.__diagAsync = 'async:' + v; })();")
                val afterAsync = ctx.evaluate("globalThis.__diagAsync;")?.toString()
                "promise: $afterFirst | async/await: $afterAsync"
            } catch (e: Exception) {
                "诊断异常：${e.message}"
            } finally {
                try { ctx.destroy() } catch (_: Exception) {}
            }
        }
    }

    data class Provider(
        val id: String,
        val name: String,
        val actions: List<String>,
        val qualitys: List<String>,
    )

    /** A script's self-reported newer version, raised via `lx.send('updateAlert', ...)`. */
    data class UpdateAlert(
        val scriptName: String,
        val message: String,
        val url: String,
    )

    @Volatile var metadataName: String = ""
        private set
    /** Latest updateAlert from this script, or null. Read by the sources UI. */
    @Volatile var updateAlert: UpdateAlert? = null
        private set
    val providers = LinkedHashMap<String, Provider>()

    private val jsExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lx-js-${seq.incrementAndGet()}").apply { isDaemon = true }
    }
    private val httpExecutor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "lx-http").apply { isDaemon = true }
    }
    private val pendingInvokes = ConcurrentHashMap<String, (Result<String>) -> Unit>()
    private val seq = AtomicInteger(0)
    private val invokeSeed = AtomicInteger(0)
    @Volatile private var bootstrapping = true
    @Volatile private var context: QuickJSContext? = null
    @Volatile private var jsThread: Thread? = null
    @Volatile private var destroyed = false

    init {
        // create the JS context on its dedicated thread (the engine enforces thread affinity)
        jsExecutor.submit {
            jsThread = Thread.currentThread()
            context = QuickJSContext.create()
        }.get()
    }

    /** Run [task] on the JS thread; inline if we're already on it. */
    private fun <T> onJsThread(task: () -> T): T {
        val t = jsThread
        if (t != null && Thread.currentThread() === t) return task()
        return jsExecutor.submit(Callable<T> { task() }).get()
    }

    /** Bootstraps the script; returns providers on success. Blocking (IO). */
    fun bootstrap(): Map<String, Provider> {
        if (providers.isNotEmpty()) return LinkedHashMap(providers)
        onJsThread {
            val ctx = context ?: throw IllegalStateException("JS context destroyed")
            // inject the native bridge as globalThis.__hyBridge.post(jsonString)
            val global = ctx.getGlobalObject()
            val bridgeObj = ctx.createNewJSObject()
            bridgeObj.setProperty("post", JSCallFunction { args ->
                handleBridgeMessage(args.firstOrNull()?.toString().orEmpty())
                ""
            })
            global.setProperty("__hyBridge", bridgeObj)
            bridgeObj.release()
            ctx.evaluate(bridgeJs())
            ctx.evaluate(script)
            // CeruMusic plugins register themselves through module.exports, which is
            // only populated once the body has run — so the bridge goes in after it.
            // A no-op for LX scripts.
            try {
                ctx.evaluate(crAdapterJs())
            } catch (_: Exception) {}
            bootstrapping = false
            try {
                // Close the bootstrap window, then replay whatever the script queued
                // while it was open (its update check, config fetch, ...) so those
                // requests actually reach the network and return real data.
                ctx.evaluate(
                    "if (globalThis.__hyState) { globalThis.__hyState.bootstrapping = false; }" +
                        "if (globalThis.__hyFlushPendingRequests) { globalThis.__hyFlushPendingRequests(); }"
                )
            } catch (_: Exception) {}
        }
        // lx.send('inited') fires synchronously during evaluate(script) via __hyBridge.post,
        // so providers are populated by the time we get here
        return LinkedHashMap(providers)
    }

    /**
     * How many lx.request callbacks are still waiting for a response. Lets a
     * caller wait for a script's bootstrap-time requests (e.g. its update check)
     * to actually come back, instead of sleeping a guessed amount of time.
     */
    fun inFlightRequests(): Int = try {
        onJsThread {
            val ctx = context
            if (ctx == null) 0
            else {
                val v = ctx.evaluate(
                    "(globalThis.__hyState && Object.keys(globalThis.__hyState.callbacks).length) || 0"
                )
                (v as? Number)?.toInt() ?: 0
            }
        }
    } catch (_: Exception) {
        0
    }

    /**
     * Invoke the script's request handler for musicUrl.
     * Returns the resolved direct URL or throws.
     */
    fun resolveMusicUrl(providerId: String, musicInfoJson: String, quality: String, timeoutMs: Long = 18_000): String {
        val payload = JSONObject().apply {
            put("action", "musicUrl")
            put("source", providerId)
            put("info", JSONObject().put("musicInfo", JSONObject(musicInfoJson)).put("type", quality))
        }
        val result = invokeRequest(payload, timeoutMs)
        val url = extractUrl(result)
        if (url.isBlank()) throw IllegalStateException("LX script did not return a playable URL")
        return url
    }

    /**
     * Invoke the script's lyric handler for [providerId].
     * Returns the raw JSON string (caller extracts the lyric text), or throws.
     */
    fun resolveLyrics(providerId: String, musicInfoJson: String, timeoutMs: Long = 12_000): String {
        val payload = JSONObject().apply {
            put("action", "lyric")
            put("source", providerId)
            put("info", JSONObject().put("musicInfo", JSONObject(musicInfoJson)))
        }
        val result = invokeRequest(payload, timeoutMs)
        if (result.isBlank() || result == "null") throw IllegalStateException("LX script returned no lyrics")
        return result
    }

    /** Generic serialized invoke; result delivered as JSON string. */
    private fun invokeRequest(payload: JSONObject, timeoutMs: Long): String {
        if (context == null) throw IllegalStateException("runtime not bootstrapped")
        val requestId = "invoke-${invokeSeed.incrementAndGet()}"
        val latch = java.util.concurrent.CountDownLatch(1)
        val box = arrayOfNulls<Result<String>>(1)
        pendingInvokes[requestId] = { r ->
            box[0] = r
            latch.countDown()
        }
        // run on the JS thread
        jsExecutor.execute {
            try {
                val ctx = context ?: throw IllegalStateException("runtime destroyed")
                val js = """
                    (() => {
                      const requestId = ${JSONObject.quote(requestId)};
                      const payload = ${payload.toString()};
                      Promise.resolve()
                        .then(() => globalThis.__hyInvokeRequest(payload))
                        .then((value) => {
                          globalThis.__hyDeliverInvoke(requestId, null, value === undefined ? null : value);
                        })
                        .catch((error) => {
                          globalThis.__hyDeliverInvoke(requestId, String(error && error.message ? error.message : error), null);
                        });
                      return true;
                    })()
                """.trimIndent()
                ctx.evaluate(js)
            } catch (e: Exception) {
                pendingInvokes.remove(requestId)?.invoke(Result.failure(e))
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pendingInvokes.remove(requestId)
            throw java.util.concurrent.TimeoutException("LX invoke timed out")
        }
        return box[0]!!.fold({ it }, { throw it })
    }

    /** Handles messages coming OUT of the JS engine. Runs on the JS thread. */
    private fun handleBridgeMessage(message: String) {
        try {
            val obj = JSONObject(message)
            when (obj.optString("type")) {
                "event" -> handleEvent(obj)
                "invoke-response" -> handleInvokeResponse(obj)
                "request" -> handleHttpMessage(obj)
            }
        } catch (_: Exception) {}
    }

    private fun handleEvent(obj: JSONObject) {
        // Previously anything that was not `inited` was dropped on the floor, which
        // silently killed updateAlert (scripts tell us here that a newer version
        // exists). Dispatch by name instead.
        when (obj.optString("eventName")) {
            "inited" -> handleInited(obj)
            "updateAlert" -> handleUpdateAlert(obj)
        }
    }

    private fun handleInited(obj: JSONObject) {
        val payload = obj.optJSONObject("payload") ?: return
        val sources = payload.optJSONObject("sources") ?: return
        metadataName = payload.optString("name", "")
        providers.clear()
        for (key in sources.keys()) {
            val s = sources.optJSONObject(key) ?: continue
            val actions = s.optJSONArray("actions")?.let { arr ->
                List(arr.length()) { arr.optString(it) }
            } ?: continue
            if (actions.isEmpty()) continue
            providers[key] = Provider(
                id = key,
                name = s.optString("name", key),
                actions = actions,
                qualitys = s.optJSONArray("qualitys")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }
                } ?: listOf("128k"),
            )
        }
    }

    /**
     * The script announced a newer version of itself. Surfaced in the sources UI
     * so a stale script stops being a silent failure mode.
     */
    private fun handleUpdateAlert(obj: JSONObject) {
        val payload = obj.optJSONObject("payload") ?: return
        val message = payload.optString("log", "").trim()
        val url = payload.optString("updateUrl", "").trim()
        if (message.isEmpty() && url.isEmpty()) return
        updateAlert = UpdateAlert(
            scriptName = scriptDisplayName(),
            message = message,
            url = url,
        )
    }

    /** Best-effort display name: LX scripts carry it in their `@name` header. */
    private fun scriptDisplayName(): String {
        if (metadataName.isNotBlank()) return metadataName
        return Regex("""@name\s+([^\r\n*]+)""").find(script.take(2000))
            ?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun handleInvokeResponse(obj: JSONObject) {
        val requestId = obj.optString("requestId")
        if (requestId.isEmpty()) return
        val cb = pendingInvokes.remove(requestId) ?: return
        val err = obj.optString("error", "")
        val value = obj.opt("value")
        if (err.isNotBlank()) cb(Result.failure(IllegalStateException(err)))
        else cb(Result.success(if (value == null || value == JSONObject.NULL) "null" else value.toString()))
    }

    private fun handleHttpMessage(obj: JSONObject) {
        val callbackId = obj.optString("callbackId")
        val url = obj.optString("url")
        val options = obj.optJSONObject("options") ?: JSONObject()
        httpExecutor.execute {
            val responseJson = try { doHttp(url, options) } catch (e: Exception) { null }
            // deliver back into JS (on JS thread)
            val err = if (responseJson == null) "network error" else null
            jsExecutor.execute {
                try {
                    val ctx = context ?: return@execute
                    val js = if (err != null) {
                        "globalThis.__hyDeliverRequest(${JSONObject.quote(callbackId)}, ${JSONObject.quote(err)}, null);"
                    } else {
                        "globalThis.__hyDeliverRequest(${JSONObject.quote(callbackId)}, null, $responseJson);"
                    }
                    ctx.evaluate(js)
                } catch (_: Exception) {}
            }
        }
    }

    private fun doHttp(url: String, options: JSONObject): String {
        val method = options.optString("method", "GET").uppercase().ifEmpty { "GET" }
        val timeout = options.optInt("timeout", 8000).coerceIn(1000, 30000)
        val headers = options.optJSONObject("headers") ?: JSONObject()
        val binary = options.optBoolean("binary", false)

        // options.params -> query string
        var target = url
        options.optJSONObject("params")?.let { params ->
            if (params.length() > 0) {
                val q = params.keys().asSequence()
                    .joinToString("&") { k -> enc(k) + "=" + enc(params.optString(k)) }
                target += (if (target.contains("?")) "&" else "?") + q
            }
        }

        // request body: `form` goes out url-encoded, plain `body` as JSON
        var bodyBytes: ByteArray? = null
        var contentType: String? = null
        val form = options.optJSONObject("form")
        if (form != null && form.length() > 0) {
            bodyBytes = form.keys().asSequence()
                .joinToString("&") { k -> enc(k) + "=" + enc(form.optString(k)) }
                .toByteArray(Charsets.UTF_8)
            contentType = "application/x-www-form-urlencoded"
        } else if (options.has("body") && !options.isNull("body")) {
            val raw = options.opt("body")
            val text = when (raw) {
                is JSONObject, is JSONArray -> raw.toString()
                is String -> raw
                else -> raw?.toString() ?: ""
            }
            if (text.isNotEmpty()) {
                bodyBytes = text.toByteArray(Charsets.UTF_8)
                contentType = "application/json"
            }
        }

        val conn = java.net.URL(target).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.instanceFollowRedirects = true

        var hasUa = false
        headers.keys().forEach { k ->
            if (k.equals("user-agent", ignoreCase = true)) hasUa = true
            try { conn.setRequestProperty(k, headers.optString(k)) } catch (_: Exception) {}
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", DEFAULT_UA)
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)

        if (bodyBytes != null) {
            conn.doOutput = true
            conn.requestMethod = if (method == "GET") "POST" else method
            conn.outputStream.use { it.write(bodyBytes) }
        } else if (method != "GET") {
            conn.requestMethod = method
        }

        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        var bytes = try { stream?.use { it.readBytes() } ?: ByteArray(0) } catch (_: Exception) { ByteArray(0) }

        // decompress when the script asked for it via Accept-Encoding
        val encoding = (conn.getHeaderField("Content-Encoding") ?: "").lowercase()
        try {
            if (encoding.contains("gzip")) bytes = gunzip(bytes)
            else if (encoding.contains("deflate")) bytes = inflate(bytes)
        } catch (_: Exception) {}

        // scripts legitimately read response headers, so hand back the real ones
        val respHeaders = JSONObject()
        try {
            conn.headerFields.forEach { (k, v) ->
                if (!k.isNullOrEmpty() && v != null && v.isNotEmpty()) {
                    respHeaders.put(k.lowercase(), v.joinToString(","))
                }
            }
        } catch (_: Exception) {}

        val finalUrl = try { conn.url.toString() } catch (_: Exception) { target }
        val statusMessage = try { conn.responseMessage ?: "" } catch (_: Exception) { "" }
        try { conn.disconnect() } catch (_: Exception) {}

        val bodyValue: Any = if (binary) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else {
            val text = String(bytes, Charsets.UTF_8)
            try {
                JSONObject(text)
            } catch (_: Exception) {
                try { JSONArray(text) } catch (_: Exception) { text }
            }
        }

        val resp = JSONObject().apply {
            put("statusCode", code)
            put("statusMessage", statusMessage)
            put("body", bodyValue)
            put("headers", respHeaders)
            put("bytes", bytes.size)
            put("url", finalUrl)
        }
        return resp.toString()
    }

    private fun enc(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /**
     * Scripts may hand back a bare URL, `{ url }`, `{ data: { url } }`, a list, or a
     * string with the URL buried in surrounding text. Walk the structure first, then
     * fall back to a regex sweep — the two-key lookup this replaced returned
     * `[object Object]` for nested `data` and nothing at all for `playUrl`/arrays.
     */
    private fun extractUrl(resultJson: String): String {
        val text = resultJson.trim()
        if (text.isEmpty() || text == "null") return ""
        val parsed: Any? = try {
            JSONObject(text)
        } catch (_: Exception) {
            try { JSONArray(text) } catch (_: Exception) { null }
        }
        if (parsed != null) {
            val found = walkUrl(parsed, 0)
            if (found.isNotEmpty()) return found
        }
        return regexUrl(text)
    }

    private fun walkUrl(value: Any?, depth: Int): String {
        if (depth > 4 || value == null || value == JSONObject.NULL) return ""
        when (value) {
            is String -> return regexUrl(value)
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val nested = walkUrl(value.opt(i), depth + 1)
                    if (nested.isNotEmpty()) return nested
                }
            }
            is JSONObject -> {
                for (key in URL_KEYS) {
                    val nested = walkUrl(value.opt(key), depth + 1)
                    if (nested.isNotEmpty()) return nested
                }
            }
        }
        return ""
    }

    private fun regexUrl(text: String): String {
        val match = HTTP_URL.find(text.replace("\\/", "/")) ?: return ""
        return match.value.trimEnd(')', '"', '\'', ']', ',', ';')
    }

    fun destroy() {
        destroyed = true
        pendingInvokes.clear()
        // context.destroy() must run on the JS thread
        try {
            if (Thread.currentThread() === jsThread) {
                try { context?.destroy() } catch (_: Exception) {}
            } else {
                jsExecutor.submit {
                    try { context?.destroy() } catch (_: Exception) {}
                }.get(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {}
        context = null
        jsExecutor.shutdownNow()
        httpExecutor.shutdownNow()
    }

    // ---------------- JS bridge source (ported from Vanta) ----------------

    private fun bridgeJs(): String {
        val rawScriptJson = JSONObject.quote(script)
        return """
        (function () {
          const state = globalThis.__hyState = {
            bootstrapping: true,
            callbacks: Object.create(null),
            requestHandler: null,
            nextCallbackId: 0,
            // Requests issued while bootstrapping (typically a script's own update
            // check, which fires synchronously at the end of its body) used to be
            // answered with a fake response and were therefore lost forever. Hold
            // them here instead and replay them for real once the window closes.
            pendingRequests: [],
          };
          const emit = (message) => globalThis.__hyBridge.post(JSON.stringify(message));

          // Called by native immediately after bootstrapping ends.
          globalThis.__hyFlushPendingRequests = () => {
            const queued = state.pendingRequests;
            state.pendingRequests = [];
            let replayed = 0;
            for (const r of queued) {
              if (!state.callbacks[r.callbackId]) continue; // script cancelled it
              emit({ type: 'request', callbackId: r.callbackId, url: r.url, options: r.options });
              replayed++;
            }
            return replayed;
          };

          globalThis.__hyDeliverRequest = (callbackId, error, response) => {
            const callback = state.callbacks[callbackId];
            if (!callback) return;
            delete state.callbacks[callbackId];
            if (error) { callback(new Error(String(error)), undefined); return; }
            callback(null, response);
          };

          globalThis.__hyInvokeRequest = async (payload) => {
            if (!state.requestHandler) throw new Error('No LX request handler registered.');
            return await state.requestHandler(payload);
          };

          globalThis.__hyDeliverInvoke = (requestId, error, value) => {
            emit({ type: 'invoke-response', requestId, ok: !error, error: error || undefined, value: value === undefined ? null : value });
          };

          globalThis.window = globalThis;
          globalThis.document = globalThis.document || {
            getElementsByTagName: () => [{ innerText: $rawScriptJson }],
          };

          globalThis.lx = {
            EVENT_NAMES: { inited: 'inited', request: 'request', showConfigView: 'showConfigView', updateAlert: 'updateAlert' },
            env: 'mobile',
            version: '2.0.0',
            currentScriptInfo: { rawScript: $rawScriptJson },
            on: (eventName, handler) => {
              if (eventName === 'request' && typeof handler === 'function') {
                state.requestHandler = handler;
              }
              return Promise.resolve();
            },
            request: (url, options, callback) => {
              const callbackId = 'cb-' + (++state.nextCallbackId);
              if (typeof callback === 'function') state.callbacks[callbackId] = callback;
              const opts = options || {};
              if (state.bootstrapping) {
                // queue it — answered for real by __hyFlushPendingRequests()
                state.pendingRequests.push({ callbackId, url, options: opts });
                return () => { delete state.callbacks[callbackId]; };
              }
              emit({ type: 'request', callbackId, url, options: opts });
              return () => { delete state.callbacks[callbackId]; };
            },
            send: (eventName, payload) => {
              emit({ type: 'event', eventName, payload });
              return Promise.resolve();
            },
            utils: {
              buffer: {
                bufToString: (v, encoding) => {
                  const bytes = Array.isArray(v) ? v : (v && v.data) ? v.data : [];
                  const enc = String(encoding || 'utf-8').toLowerCase();
                  if (enc === 'base64') {
                    let bin = '';
                    for (const b of bytes) bin += String.fromCharCode(b);
                    // btoa polyfill (QuickJS lacks it)
                    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    let out = '', i = 0;
                    while (i < bin.length) {
                      const a = bin.charCodeAt(i++), b2 = bin.charCodeAt(i++), c = bin.charCodeAt(i++);
                      const e1 = a >> 2, e2 = ((a & 3) << 4) | (b2 >> 4);
                      const e3 = isNaN(b2) ? 64 : (((b2 & 15) << 2) | (c >> 6));
                      const e4 = isNaN(c) ? 64 : (c & 63);
                      out += chars[e1] + chars[e2] + chars[e3] + chars[e4];
                    }
                    return out;
                  }
                  if (enc === 'hex') return bytes.map(b => b.toString(16).padStart(2, '0')).join('');
                  let s = '';
                  for (const b of bytes) s += String.fromCharCode(b);
                  try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
                },
                from: (v, encoding) => {
                  const enc = String(encoding || 'utf-8').toLowerCase();
                  if (enc === 'base64') {
                    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    const clean = String(v || '').replace(/=+$/, '');
                    const bytes = [];
                    let bc = 0, bs = 0, buffer;
                    for (let i = 0; (buffer = clean.charAt(i++)); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4)
                        ? bytes.push(255 & bs >> (-2 * bc & 6)) : 0) {
                      buffer = chars.indexOf(buffer);
                    }
                    return bytes;
                  }
                  if (enc === 'hex') {
                    const clean = String(v || '').replace(/[^0-9a-f]/gi, '');
                    const bytes = [];
                    for (let i = 0; i < clean.length; i += 2) {
                      const seg = clean.slice(i, i + 2);
                      if (seg.length === 2) bytes.push(parseInt(seg, 16));
                    }
                    return bytes;
                  }
                  const s = unescape(encodeURIComponent(String(v)));
                  return Array.from(s).map(c => c.charCodeAt(0));
                },
              },
              crypto: {
                aesEncrypt: () => { throw new Error('LX crypto bridge not implemented: aesEncrypt'); },
                md5: () => { throw new Error('LX crypto bridge not implemented: md5'); },
                randomBytes: (size) => {
                  const n = Math.max(0, Math.min(Number(size) || 0, 4096));
                  const bytes = [];
                  for (let i = 0; i < n; i++) bytes.push(Math.floor(Math.random() * 256));
                  return bytes;
                },
                rsaEncrypt: () => { throw new Error('LX crypto bridge not implemented: rsaEncrypt'); },
              },
            },
          };
          globalThis.window.lx = globalThis.lx;

          // ---------------- CeruMusic (`cr`) v1 plugin compat ----------------
          // A `cr` plugin is a CommonJS module: it assigns
          //   module.exports = { pluginInfo, sources, musicUrl }
          // and talks HTTP through `cerumusic.request`. Neither global exists for a
          // plain LX script, so everything here is inert for those. The half that
          // actually registers the plugin runs later — see crAdapterJs().
          globalThis.console = globalThis.console || {
            log: () => {}, info: () => {}, warn: () => {}, error: () => {}, debug: () => {},
          };
          globalThis.module = globalThis.module || { exports: {} };
          globalThis.exports = globalThis.module.exports;
          globalThis.cerumusic = globalThis.cerumusic || {};
          // Plugins gate themselves on this via checkAppVersion(); it must parse as
          // a version string ("1.2.12" mirrors the reference host).
          globalThis.cerumusic.version = globalThis.cerumusic.version || '1.2.12';
          globalThis.cerumusic.request = globalThis.cerumusic.request || function (url, options) {
            return new Promise((resolve, reject) => {
              try {
                lx.request(url, options || { method: 'GET' }, (err, resp) => {
                  if (err) reject(err); else resolve(resp);
                });
              } catch (e) { reject(e); }
            });
          };
          globalThis.cerumusic.NoticeCenter = globalThis.cerumusic.NoticeCenter || function () {};
          globalThis.cerumusic.stopRequests = globalThis.cerumusic.stopRequests || function () {};

          // cr plugins read `resp.body.code` / `resp.body.url`. Native already hands
          // back a parsed object; if a body arrives as a JSON string, wrap it in a
          // String that also carries the parsed fields so both styles work.
          const __hyNativeRequest = lx.request;
          lx.request = function (url, options, callback) {
            return __hyNativeRequest(url, options, function (err, resp) {
              try {
                if (!err && resp && typeof resp === 'object' && typeof resp.body === 'string') {
                  const raw = resp.body;
                  const first = raw.replace(/^\s+/, '').charAt(0);
                  if (first === '{' || first === '[') {
                    try {
                      const parsed = JSON.parse(raw);
                      if (parsed && typeof parsed === 'object') {
                        const hybrid = new String(raw);
                        Object.assign(hybrid, parsed);
                        hybrid.raw = raw;
                        hybrid.text = raw;
                        resp.body = hybrid;
                        if (resp.text == null) resp.text = raw;
                        if (resp.json == null) resp.json = parsed;
                        if (resp.data == null) resp.data = parsed;
                      }
                    } catch (_) {}
                  }
                }
              } catch (_) {}
              callback(err, resp);
            });
          };
        })();
        """.trimIndent()
    }

    /**
     * CeruMusic (`cr`) v1 → LX bridge, evaluated **after** the script body so that
     * `module.exports` is already populated. If the module does not look like a cr
     * plugin this is a no-op, which is why LX scripts are unaffected.
     *
     * Ported from 歌一刀 (com.macxk.KMusic)'s embedded shim; verified against the
     * real 聆澜 `cr` plugin.
     */
    private fun crAdapterJs(): String = """
        (function () {
          try {
            const exp = globalThis.module && globalThis.module.exports;
            if (!exp || typeof exp.musicUrl !== 'function' || !exp.sources) return;
            const pluginInfo = exp.pluginInfo || {};
            const mapped = {};
            for (const id of Object.keys(exp.sources)) {
              const s = exp.sources[id];
              if (!s || typeof s !== 'object') continue;
              mapped[id] = {
                name: (pluginInfo.name ? (pluginInfo.name + ' - ') : '') + (s.name || id),
                type: 'music',
                actions: ['musicUrl'],
                qualitys: Array.isArray(s.qualitys) ? s.qualitys : [],
              };
            }
            if (!Object.keys(mapped).length) return;
            lx.send('inited', { sources: mapped, name: pluginInfo.name || '' });
            lx.on('request', function (payload) {
              const action = payload && payload.action;
              if (action !== 'musicUrl') return;
              const source = payload.source;
              const info = payload.info || {};
              const musicInfo = info.musicInfo || {};
              return Promise.resolve(exp.musicUrl(source, musicInfo, info.type)).then((res) => {
                if (typeof res === 'string') return res;
                if (res && typeof res.url === 'string') return res.url;
                return '';
              });
            });
          } catch (e) {}
        })();
    """.trimIndent()
}
