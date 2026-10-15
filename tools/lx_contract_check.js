#!/usr/bin/env node
/**
 * 契约验证台：直接从 app/.../LxRuntime.kt 抽出内嵌 JS（bridgeJs + crAdapterJs），
 * 在 vm 沙箱里复刻 QuickJS 全局，把真实音源脚本跑一遍。
 *
 * 为什么用 vm 而不是直接 eval：Node 里 `module` 是模块作用域变量，会遮蔽
 * `globalThis.module`，CeruMusic 插件的 `module.exports = {...}` 就写不进全局。
 * vm.createContext 给的是一个干净全局，才等价于 QuickJS 的 globalThis。
 *
 * 复刻来源：app/src/main/java/com/haiyin/player/LxRuntime.kt（工作树）
 *   - bridgeJs()      : lx.* + console + module/exports/cerumusic + hybrid body 拦截
 *   - crAdapterJs()   : module.exports → lx.send('inited') / lx.on('request')
 *   - doHttp()        : method/timeout/headers/params/form/body/binary
 *   - bootstrap() 顺序 : bridge → script → adapter → 关窗 flush
 *
 * 用法:
 *   node tools/lx_contract_check.js <脚本路径> [source] [songId] [quality]
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const scriptPath = process.argv[2];
const SOURCE = process.argv[3] || 'wy';
const SONG_ID = process.argv[4] || '1901371647';
const QUALITY = process.argv[5] || '320k';

if (!scriptPath) {
  console.error('用法: node tools/lx_contract_check.js <脚本路径> [source] [songId] [quality]');
  process.exit(2);
}

const KT = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'haiyin', 'player', 'LxRuntime.kt');
const kt = fs.readFileSync(KT, 'utf8');

/** 抽出 Kotlin 里 `return """ ... """.trimIndent()` 之间的 JS 文本。 */
function extractJs(fnMarker, opener) {
  const i = kt.indexOf(fnMarker);
  if (i < 0) throw new Error('找不到 ' + fnMarker);
  const a = kt.indexOf(opener, i);
  if (a < 0) throw new Error('找不到 ' + opener);
  const start = a + opener.length;
  const b = kt.indexOf('""".trimIndent()', start);
  if (b < 0) throw new Error('找不到 JS 结尾');
  return kt.slice(start, b);
}

const rawScript = fs.readFileSync(scriptPath, 'utf8');
const rawScriptJson = JSON.stringify(rawScript);

const bridgeSrc = extractJs('private fun bridgeJs(): String', 'return """')
  .replace(/\$rawScriptJson/g, rawScriptJson);
const adapterSrc = extractJs('private fun crAdapterJs(): String = """', '= """');

// ---------------- 复刻 doHttp() ----------------
// 对齐 item ④：params->query、form(urlencoded)/body(JSON)、真响应头、
// statusMessage/bytes/url、binary->Base64、gzip/deflate、DEFAULT_UA 兜底。
const DEFAULT_UA =
  'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/120.0.0.0 Mobile Safari/537.36';

const httpLog = [];
async function doHttp(url, options) {
  options = options || {};
  const method = String(options.method || 'GET').toUpperCase();
  const timeout = Math.min(Math.max(Number(options.timeout || 8000), 1000), 30000);
  const headers = Object.assign({}, options.headers || {});
  const binary = !!options.binary;

  let target = url;
  if (options.params && typeof options.params === 'object') {
    const q = Object.entries(options.params)
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
    if (q) target += (target.includes('?') ? '&' : '?') + q;
  }

  let bodyStr, contentType;
  if (options.form && typeof options.form === 'object' && Object.keys(options.form).length) {
    bodyStr = Object.entries(options.form)
      .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
    contentType = 'application/x-www-form-urlencoded';
  } else if (options.body !== undefined && options.body !== null) {
    bodyStr = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
    contentType = 'application/json';
  }

  const hasUa = Object.keys(headers).some((k) => k.toLowerCase() === 'user-agent');
  if (!hasUa) headers['User-Agent'] = DEFAULT_UA;
  if (contentType) headers['Content-Type'] = contentType;

  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeout);
  let res;
  try {
    res = await fetch(target, {
      method: bodyStr !== undefined && method === 'GET' ? 'POST' : method,
      headers, body: bodyStr, signal: ac.signal, redirect: 'follow',
    });
  } finally {
    clearTimeout(timer);
  }

  const buf = Buffer.from(await res.arrayBuffer());
  const respHeaders = {};
  res.headers.forEach((v, k) => { respHeaders[k.toLowerCase()] = v; });

  let bodyValue;
  if (binary) {
    bodyValue = buf.toString('base64');
  } else {
    const text = buf.toString('utf8');
    try { bodyValue = JSON.parse(text); } catch { bodyValue = text; }
  }

  httpLog.push({ url: target, status: res.status, bytes: buf.length });
  return {
    statusCode: res.status, statusMessage: res.statusText || '',
    body: bodyValue, headers: respHeaders, bytes: buf.length, url: target,
  };
}
// 复刻 request() 里真正发出请求那一步（标记：dohttp_contract_test.js 从这里切源码）

// ---------------- 干净全局（≈ QuickJS globalThis）----------------
const sandbox = {
  console, JSON, Math, Date, Promise, Object, Array, String, Number, Boolean, Error,
  TypeError, RangeError, RegExp, Symbol, Map, Set, WeakMap, WeakSet, Proxy, Reflect,
  isNaN, isFinite, parseInt, parseFloat, encodeURIComponent, decodeURIComponent,
  escape, unescape, setTimeout, clearTimeout, setInterval, clearInterval,
  fetch, TextEncoder, TextDecoder, URL, URLSearchParams, AbortController, Buffer,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

// ---------------- 复刻 native 侧（handleBridgeMessage / invokeRequest）----------------
const state = { inited: null, updateAlerts: [], requestHandler: null };
const callbacksSeen = () => {
  const s = sandbox.__hyState;
  return s ? Object.keys(s.callbacks).length : 0;
};

sandbox.__hyBridge = {
  post: (json) => {
    let m; try { m = JSON.parse(json); } catch { return ''; }
    if (m.type === 'event') {
      if (m.eventName === 'inited') state.inited = m.payload;
      if (m.eventName === 'updateAlert') state.updateAlerts.push(m.payload);
    } else if (m.type === 'request') {
      doHttp(m.url, m.options)
        .then((resp) => sandbox.__hyDeliverRequest(m.callbackId, null, resp))
        .catch((e) => sandbox.__hyDeliverRequest(m.callbackId, String(e && e.message ? e.message : e), null));
    }
    return '';
  },
};

// ---------------- 按 bootstrap() 的顺序跑 ----------------
const t0 = Date.now();
try { vm.runInContext(bridgeSrc, sandbox); } catch (e) { console.error('bridge 求值抛出:', e.message); }
try { vm.runInContext(rawScript, sandbox); } catch (e) { console.error('脚本求值抛出:', e.message); }
const bootstrapMs = Date.now() - t0;
let adapterErr = '';
try { vm.runInContext(adapterSrc, sandbox); } catch (e) { adapterErr = e.message; }
vm.runInContext('if (globalThis.__hyState) globalThis.__hyState.bootstrapping = false;', sandbox);
let replayed = 0;
try { replayed = vm.runInContext('globalThis.__hyFlushPendingRequests ? globalThis.__hyFlushPendingRequests() : 0', sandbox); } catch (_) {}

console.log('=== 初始化 (bootstrap) ===');
console.log('  耗时:', bootstrapMs, 'ms', adapterErr ? '  adapter 抛出: ' + adapterErr : '');
const inited = state.inited;
if (!inited) {
  console.log('  ✗ 脚本未发出 inited —— 契约不匹配');
} else {
  const sources = inited.sources || {};
  console.log('  inited.status:', inited.status, ' name:', JSON.stringify(inited.name || ''));
  console.log('  注册音源:', Object.keys(sources).join(', ') || '（无）');
  for (const [id, s] of Object.entries(sources)) {
    console.log(`    ${id.padEnd(4)} name=${s.name} type=${s.type} actions=${JSON.stringify(s.actions)} qualitys=${JSON.stringify(s.qualitys)}`);
  }
}
console.log('  requestHandler:', sandbox.__hyState && sandbox.__hyState.requestHandler ? '已注册 ✓' : '未注册 ✗');
console.log(`  关窗后 flush 了 ${replayed} 个排队请求`);

(async () => {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    if (callbacksSeen() === 0) break;
    await new Promise((r) => setTimeout(r, 120));
  }
  await new Promise((r) => setTimeout(r, 300));

  console.log('\n=== 更新提醒 (updateAlert) ===');
  if (!state.updateAlerts.length) {
    console.log('  （脚本未发出 updateAlert —— 可能已是最新版本）');
  } else {
    for (const a of state.updateAlerts) console.log('  ✓ 收到:', JSON.stringify(a).slice(0, 200));
  }

  console.log(`\n=== 调用 musicUrl  source=${SOURCE} songId=${SONG_ID} quality=${QUALITY} ===`);
  const payload = {
    action: 'musicUrl', source: SOURCE,
    info: { musicInfo: { songmid: SONG_ID, hash: SONG_ID, id: SONG_ID }, type: QUALITY },
  };
  try {
    const value = await vm.runInContext(
      `__hyInvokeRequest(${JSON.stringify(payload)}).then(v => v).catch(e => 'ERR:' + (e && e.message))`,
      sandbox
    );
    console.log('  返回类型:', typeof value);
    const u = typeof value === 'string' && !value.startsWith('ERR:')
      ? value : (value && value.url);
    console.log('  返回内容:', JSON.stringify(value).slice(0, 220));
    if (u && /^https?:\/\//.test(u)) {
      console.log('  ✓ 拿到直链:', u.slice(0, 110) + (u.length > 110 ? '…' : ''));
      const head = await fetch(u, { method: 'HEAD' });
      console.log(`  ✓ HEAD ${head.status} ${head.headers.get('content-type')} ${Math.round((head.headers.get('content-length') || 0) / 1024)} KB`);
    } else {
      console.log('  ✗ 未返回可用直链');
    }
  } catch (e) {
    console.log('  ✗ 调用失败:', e && e.message ? e.message : String(e));
  }

  console.log('\n=== HTTP 轨迹 ===');
  for (const h of httpLog) console.log(`  ${h.status}  ${h.bytes}B  ${h.url.slice(0, 100)}`);
})();
