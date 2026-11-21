const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./fixtures/start-server.cjs');

let origin;
let child;

before(async () => {
  ({ origin, child } = await startServer());
});

after(() => {
  child?.kill();
});

async function login(email) {
  const response = await fetch(`${origin}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: '123456' }),
  });
  assert.equal(response.status, 200);
  return (await response.json()).data;
}

test('GET /v1/config includes version matching ConfigDto contract', async () => {
  const response = await fetch(`${origin}/v1/config`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.success, true);
  assert.equal(typeof body.data.version, 'string');
  assert.ok(body.data.version.length > 0);
  assert.equal(body.data.minSupportedVersionCode, 1);
});

test('POST /v1/auth/logout with expired accessToken succeeds and revokes refreshToken', async () => {
  const session = await login('minhanh@momentbox.dev');

  const expireRes = await fetch(`${origin}/v1/debug/expire-token`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${session.accessToken}` },
  });
  assert.equal(expireRes.status, 200);

  const meRes = await fetch(`${origin}/v1/me`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  });
  assert.equal(meRes.status, 401);
  assert.equal((await meRes.json()).error.code, 'TOKEN_EXPIRED');

  const logoutRes = await fetch(`${origin}/v1/auth/logout`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  assert.equal(logoutRes.status, 200);
  assert.equal((await logoutRes.json()).success, true);

  const refreshRes = await fetch(`${origin}/v1/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  assert.equal(refreshRes.status, 401);
  assert.equal((await refreshRes.json()).error.code, 'REFRESH_TOKEN_INVALID');
});

test('POST /v1/auth/logout with refreshToken only revokes session and rejects unknown tokens', async () => {
  const session = await login('lananh@momentbox.dev');

  const logoutRes = await fetch(`${origin}/v1/auth/logout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  assert.equal(logoutRes.status, 200);

  const refreshRes = await fetch(`${origin}/v1/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  assert.equal(refreshRes.status, 401);

  const anonymousLogout = await fetch(`${origin}/v1/auth/logout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: 'rt_unknown' }),
  });
  assert.equal(anonymousLogout.status, 401);
  assert.equal((await anonymousLogout.json()).error.code, 'UNAUTHORIZED');
});

test('POST /v1/auth/logout cannot revoke another user refreshToken', async () => {
  const userA = await login('minhanh@momentbox.dev');
  const userB = await login('lananh@momentbox.dev');

  const logoutRes = await fetch(`${origin}/v1/auth/logout`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${userA.accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ refreshToken: userB.refreshToken }),
  });
  assert.equal(logoutRes.status, 200);

  const refreshB = await fetch(`${origin}/v1/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: userB.refreshToken }),
  });
  assert.equal(refreshB.status, 200);
});
