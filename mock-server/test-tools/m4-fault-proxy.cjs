/* Proxy chỉ dành cho kiểm thử local: giữ nguyên mock server nghiệp vụ ở3000.
 * Không log token/body; chỉ lưu mode, số request, ID remote trả về để đối chiếu retry.
 * Không khởi chạy cùng npm start, không thay behavior của server mặc định.
 */
const http = require('node:http');
const port = 3001;
let mode = 'normal';
let stats = { requests: 0, forwarded: 0, remoteIds: [], refreshes: 0 };
function json(res, status, value) { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(value)); }
function forward(req, res, body, loseResponse) {
  stats.forwarded += req.method === 'POST' && req.url === '/v1/posts' ? 1 : 0;
  const upstream = http.request({ host: '127.0.0.1', port: 3000, path: req.url, method: req.method, headers: req.headers }, response => {
    const chunks = [];
    response.on('data', chunk => chunks.push(chunk));
    response.on('end', () => {
      const raw = Buffer.concat(chunks);
      if (req.method === 'POST' && req.url === '/v1/posts' && response.statusCode < 300) {
        try { const value = JSON.parse(raw); const post = value.data?.post ?? value.data ?? value.post ?? value; if (Number.isInteger(post.id)) stats.remoteIds.push(post.id); } catch {}
      }
      if (loseResponse) { res.socket?.destroy(); return; }
      if (!res.destroyed) { res.writeHead(response.statusCode, response.headers); res.end(raw); }
    });
  });
  upstream.on('error', () => { if (!res.destroyed) json(res, 502, { code: 'FIXTURE_UPSTREAM_ERROR' }); });
  res.on('close', () => { if (!res.writableEnded) upstream.destroy(); });
  upstream.end(body);
}
http.createServer((req, res) => {
  if (req.url === '/__m4-review') {
    if (req.method === 'GET') return json(res, 200, { mode, ...stats });
    let body = ''; req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const input = JSON.parse(body);
        if (!['normal', '500', '401-once', 'delay', 'timeout', 'lost-response'].includes(input.mode)) return json(res, 400, { error: 'invalid mode' });
        mode = input.mode; stats = { requests: 0, forwarded: 0, remoteIds: [], refreshes: 0 }; json(res, 200, { mode });
      } catch { json(res, 400, { error: 'invalid JSON' }); }
    }); return;
  }
  if (req.url?.includes('/auth/refresh')) stats.refreshes++;
  const isPost = req.method === 'POST' && req.url === '/v1/posts';
  if (isPost) stats.requests++;
  const chunks = []; let bytes = 0;
  req.on('data', chunk => { bytes += chunk.length; if (bytes > 55 * 1024 * 1024) req.destroy(); else chunks.push(chunk); });
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    if (!isPost) return forward(req, res, body, false);
    if (mode === '500') return json(res, 500, { code: 'FIXTURE_500', message: 'Intentional local review failure' });
    if (mode === '401-once' && stats.requests === 1) return json(res, 401, { success: false, error: { code: 'TOKEN_EXPIRED', message: 'Intentional first response' } });
    if (mode === 'delay' || mode === 'timeout') {
      const timer = setTimeout(() => { if (!res.destroyed) { if (mode === 'timeout') json(res, 500, { code: 'FIXTURE_TIMEOUT_FINISHED' }); else forward(req, res, body, false); } }, 25000);
      res.on('close', () => clearTimeout(timer)); return;
    }
    forward(req, res, body, mode === 'lost-response');
  });
}).listen(port, '127.0.0.1', () => console.log(`M4 local fault proxy on127.0.0.1:${port}; upstream127.0.0.1:3000`));
