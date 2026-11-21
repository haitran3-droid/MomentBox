const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { startServer } = require('./fixtures/start-server.cjs');

test('a lost post response and concurrent retries create one post and one media file', async t => {
  const { origin, child } = await startServer();
  t.after(() => child.kill());
  async function login(email) {
    const response = await fetch(origin + '/v1/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: '123456' }),
    });
    return (await response.json()).data.accessToken;
  }
  const token = await login('minhanh@momentbox.dev');
  const clientId = randomUUID();
  const caption = 'retry-' + clientId;
  const extension = '.' + clientId;
  function submit(endpoint, authToken = token, key = clientId) {
    const body = new FormData();
    body.append('clientId', key);
    body.append('caption', caption);
    body.append('media', new Blob(['image'], { type: 'image/jpeg' }), 'image' + extension);
    return fetch(endpoint + '/v1/posts', {
      method: 'POST', headers: { Authorization: 'Bearer ' + authToken }, body,
    });
  }
  const uploadDir = path.join(__dirname, '../uploads');
  async function files() {
    return (await fs.readdir(uploadDir)).filter(f => f.endsWith(extension));
  }
  t.after(async () => {
    for (const file of await files()) await fs.unlink(path.join(uploadDir, file));
  });
  let accepted;
  const proxy = http.createServer((req, res) => {
    const headers = { ...req.headers, host: new URL(origin).host };
    const upstream = http.request(origin + req.url, { method: req.method, headers }, reply => {
      const chunks = [];
      reply.on('data', chunk => chunks.push(chunk));
      reply.on('end', () => {
        accepted = JSON.parse(Buffer.concat(chunks)).data;
        res.destroy();
      });
    });
    req.pipe(upstream);
  });
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  t.after(() => proxy.close());
  await assert.rejects(submit('http://127.0.0.1:' + proxy.address().port), TypeError);
  assert.ok(accepted.id);
  for (const retry of await Promise.all([submit(origin), submit(origin)])) {
    assert.equal(retry.status, 200);
    assert.deepEqual((await retry.json()).data, accepted);
  }
  const feed = await fetch(origin + '/v1/users/' + accepted.author.id + '/posts', {
    headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal((await feed.json()).data.filter(p => p.caption === caption).length, 1);
  assert.equal((await files()).length, 1);
  const other = await submit(origin, await login('travel@momentbox.dev'));
  assert.equal(other.status, 201);
  assert.notEqual((await other.json()).data.id, accepted.id);
  const invalid = await submit(origin, token, 'invalid');
  assert.equal(invalid.status, 400);
  assert.equal((await files()).length, 2);
  const deleted = await fetch(origin + '/v1/posts/' + accepted.id, {
    method: 'DELETE', headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal(deleted.status, 200);
  assert.equal((await submit(origin)).status, 404);
  assert.equal((await files()).length, 1);
});
