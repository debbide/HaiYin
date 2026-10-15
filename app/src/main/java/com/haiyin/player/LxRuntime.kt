package com.haiyin.player

import android.content.Context
import app.cash.quickjs.QuickJs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * lx-protocol JS runtime (QuickJS), ported from Vanta's flutter_js bridge design:
 *  - JS land keeps a callback table; native <-> JS talk via __hyBridge.post()
 *  - lx.request sends a message to native (JSON string); native does HTTP on its own
 *    executor and calls back into JS with the response
 *  - requests are serialized per runtime (JS engines are single-threaded)
 *  - bootstrap window answers lx.request with a fake response so scripts don't break
 */
class LxRuntime private constructor(
    private val appContext: Context,
    private val script: String,
) {

    companion object {
        private val scriptRuntimes = ConcurrentHashMap<String, LxRuntime>()

        fun get(appContext: Context, script: String): LxRuntime =
            scriptRuntimes.getOrPut(script) { LxRuntime(appContext.applicationContext, script) }

        fun shutdownAll() {
            scriptRuntimes.values.forEach { it.destroy() }
            scriptRuntimes.clear()
        }

        /**
         * Diagnostic: does this QuickJS build run promise microtasks after
         * evaluate() returns? The whole LX invoke chain depends on it.
         */
        fun diagnosePromises(): String {
            val qjs = try { QuickJs.create() } catch (e: Exception) {
                return "QuickJS 创建失败：${e.message}"
            }
            return try {
                qjs.evaluate(
                    "globalThis.__diag = 'pending';" +
                    "Promise.resolve(42).then(v => { globalThis.__diag = 'resolved:' + v; });" +
                    "'sync';"
                )
                val afterFirst = qjs.evaluate("globalThis.__diag;")?.toString()
                qjs.evaluate("'noop';")
                val afterSecond = qjs.evaluate("globalThis.__diag;")?.toString()
                qjs.evaluate(
                    "(async () => { const v = await Promise.resolve(7); globalThis.__diagAsync = 'async:' + v; })();"
                )
                val afterAsync = qjs.evaluate("globalThis.__diagAsync;")?.toString()
                "promise: $afterFirst -> $afterSecond | async/await: $afterAsync"
            } catch (e: Exception) {
                "诊断异常：${e.message}"
            } finally {
                try { qjs.close() } catch (_: Exception) {}
            }
        }
    }

    data class Provider(
        val id: String,
        val name: String,
        val actions: List<String>,
        val qualitys: List<String>,
    )

    @Volatile var metadataName: String = ""
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
    @Volatile private var quickJs: QuickJs? = null
    @Volatile private var destroyed = false

    /** bridge interface exposed to JS as globalThis.__hyBridge */
    interface HyBridge {
        fun post(message: String): String
    }

    private val hyBridge = object : HyBridge {
        override fun post(message: String): String {
            handleBridgeMessage(message)
            return ""
        }
    }

    /** Bootstraps the script; returns providers on success. Blocking (IO). */
    fun bootstrap(): Map<String, Provider> {
        if (providers.isNotEmpty()) return LinkedHashMap(providers)
        val qjs = QuickJs.create()
        quickJs = qjs
        try {
            qjs.set("__hyBridge", HyBridge::class.java, hyBridge)
            qjs.evaluate(bridgeJs())
            qjs.evaluate(script)
            // pump the bootstrap window: script calls send('inited', {...}) synchronously
            val deadline = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < deadline && providers.isEmpty()) {
                Thread.sleep(20)
            }
            bootstrapping = false
            try {
                qjs.evaluate("if (globalThis.__hyState) { globalThis.__hyState.bootstrapping = false; }")
            } catch (_: Exception) {}
            return LinkedHashMap(providers)
        } catch (e: Exception) {
            try { qjs.close() } catch (_: Exception) {}
            quickJs = null
            throw e
        }
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
        val qjs = quickJs ?: throw IllegalStateException("runtime not bootstrapped")
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
                qjs.evaluate(js)
            } catch (e: Exception) {
                pendingInvokes.remove(requestId)?.invoke(Result.failure(e))
            }
        }
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
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
        if (obj.optString("eventName") != "inited") return
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
                    val js = if (err != null) {
                        "globalThis.__hyDeliverRequest(${JSONObject.quote(callbackId)}, ${JSONObject.quote(err)}, null);"
                    } else {
                        "globalThis.__hyDeliverRequest(${JSONObject.quote(callbackId)}, null, $responseJson);"
                    }
                    quickJs?.evaluate(js)
                } catch (_: Exception) {}
            }
        }
    }

    private fun doHttp(url: String, options: JSONObject): String {
        val method = options.optString("method", "GET").uppercase()
        val timeout = options.optInt("timeout", 8000)
        val headers = options.optJSONObject("headers") ?: JSONObject()
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeout.coerceAtMost(15000)
        conn.readTimeout = timeout.coerceAtMost(15000)
        conn.instanceFollowRedirects = true
        headers.keys().forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
        if (method != "GET") conn.requestMethod = method
        val code = conn.responseCode
        val bodyStream = if (code in 200..399) conn.inputStream else conn.errorStream
        val body = bodyStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        val bodyValue: Any = try {
            JSONObject(body)
        } catch (_: Exception) {
            try { JSONArray(body) } catch (_: Exception) { body }
        }
        val resp = JSONObject().apply {
            put("statusCode", code)
            put("body", bodyValue)
            put("headers", JSONObject())
        }
        return resp.toString()
    }

    private fun extractUrl(resultJson: String): String {
        return try {
            when {
                resultJson.startsWith("http") -> resultJson.trim().trim('"')
                else -> {
                    val o = JSONObject(resultJson)
                    o.optString("url", o.optString("data", ""))
                }
            }
        } catch (_: Exception) { resultJson.trim().trim('"') }
    }

    fun destroy() {
        destroyed = true
        pendingInvokes.clear()
        jsExecutor.shutdownNow()
        httpExecutor.shutdownNow()
        try { quickJs?.close() } catch (_: Exception) {}
        quickJs = null
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
          };
          const emit = (message) => globalThis.__hyBridge.post(JSON.stringify(message));
          const bootstrapResponse = {
            body: { code: -1, data: null, message: '', url: '' },
            headers: {},
            statusCode: 200,
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
              if (state.bootstrapping) {
                if (typeof callback === 'function') callback(null, bootstrapResponse);
                return () => {};
              }
              const callbackId = 'cb-' + (++state.nextCallbackId);
              if (typeof callback === 'function') state.callbacks[callbackId] = callback;
              emit({ type: 'request', callbackId, url, options: options || {} });
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
        })();
        """.trimIndent()
    }
}
