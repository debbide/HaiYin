// item ④ 行为测试：从 lx_contract_check.js 里切出「真实」doHttp 源码来跑，
// 而不是重写一份，确保测的就是落盘的那段逻辑。
const fs = require('fs');
const http = require('http');
const path = require('path');

const harness = fs.readFileSync(path.join(__dirname, 'lx_contract_check.js'), 'utf8');
const start = harness.indexOf('const DEFAULT_UA =');
const end = harness.indexOf('// 复刻 request() 里真正发出请求那一步');
if (start < 0 || end < 0) { console.error('切片标记未找到'); process.exit(2); }
const slice = harness.slice(start, end);
const httpLog = [];
const doHttp = eval(`(function(){ ${slice}; return doHttp; })()`);

// ---- 本地 echo 服务器：回显 method / url / headers / body ----
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    if (req.url === '/gzip') {
      const zlib = require('zlib');
      const gz = zlib.gzipSync(Buffer.from(JSON.stringify({ ok: true, note: 'gzipped' })));
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip', 'X-Test': 'gz' });
      res.end(gz); return;
    }
    if (req.url === '/bin') {
      res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
      res.end(Buffer.from([0, 1, 2, 250, 251, 255])); return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json', 'X-Test': 'echo', 'X-Server': 'local' });
    res.end(JSON.stringify({
      method: req.method,
      url: req.url,
      ua: req.headers['user-agent'] || '',
      ctype: req.headers['content-type'] || '',
      body,
    }));
  });
});

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const base = `http://127.0.0.1:${port}`;
  let pass = 0, total = 0;
  const check = (name, cond, extra = '') => {
    total++; if (cond) pass++;
    console.log(`  ${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`);
  };

  // 1) params -> query
  let r = await doHttp(base + '/p', { params: { a: '1', b: '你 好' } });
  check('params 拼进 query', r.body.url === '/p?a=1&b=' + encodeURIComponent('你 好'), r.body.url);

  // 2) 已有 ? 时用 &
  r = await doHttp(base + '/p?x=1', { params: { y: '2' } });
  check('已有 ? 用 & 续接', r.body.url === '/p?x=1&y=2', r.body.url);

  // 3) form -> urlencoded POST
  r = await doHttp(base + '/f', { method: 'POST', form: { u: 'a', p: 'b c' } });
  check('form 走 urlencoded', r.body.method === 'POST' && r.body.ctype === 'application/x-www-form-urlencoded', `ctype=${r.body.ctype}`);
  check('form body 正确', r.body.body === 'u=a&p=b+c' || r.body.body === 'u=a&p=b%20c', r.body.body);

  // 4) body -> JSON POST（对象序列化）
  r = await doHttp(base + '/j', { method: 'POST', body: { k: 1, s: 'v' } });
  check('body 走 JSON', r.body.ctype === 'application/json' && r.body.body === '{"k":1,"s":"v"}', r.body.body);

  // 5) DEFAULT_UA 兜底
  r = await doHttp(base + '/ua', {});
  check('未带 UA 时注入 DEFAULT_UA', /Chrome\/120/.test(r.body.ua), r.body.ua.slice(0, 40));

  // 6) 自带 UA 优先
  r = await doHttp(base + '/ua', { headers: { 'User-Agent': 'my-ua/9' } });
  check('自带 UA 覆盖默认', r.body.ua === 'my-ua/9', r.body.ua);

  // 7) 真响应头
  r = await doHttp(base + '/h', {});
  check('回传真响应头', r.headers['x-test'] === 'echo' && r.headers['x-server'] === 'local', JSON.stringify(r.headers));

  // 8) bytes / url / statusMessage 字段
  r = await doHttp(base + '/meta', {});
  check('含 bytes 字段且>0', typeof r.bytes === 'number' && r.bytes > 0, 'bytes=' + r.bytes);
  check('含 url 字段', typeof r.url === 'string' && r.url.endsWith('/meta'), r.url);
  check('含 statusMessage 字段', typeof r.statusMessage === 'string', JSON.stringify(r.statusMessage));

  // 9) gzip 解压
  r = await doHttp(base + '/gzip', { headers: { 'Accept-Encoding': 'gzip' } });
  check('gzip 自动解压成对象', r.body && r.body.ok === true && r.body.note === 'gzipped', JSON.stringify(r.body));

  // 10) binary -> base64
  r = await doHttp(base + '/bin', { binary: true });
  check('binary 回 base64', r.body === Buffer.from([0, 1, 2, 250, 251, 255]).toString('base64'), r.body);

  // 11) 非 JSON 文本原样返回
  r = await doHttp(base + '/gzip', { headers: { 'Accept-Encoding': 'gzip' } });
  check('解析后仍是对象', typeof r.body === 'object');

  console.log(`\n  doHttp 行为: ${pass}/${total}`);
  server.close();
  process.exit(pass === total ? 0 : 1);
})();
