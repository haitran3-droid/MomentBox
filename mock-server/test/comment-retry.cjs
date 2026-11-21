const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const http = require('node:http');
const { startServer } = require('./fixtures/start-server.cjs');

async function login(origin, email) {
  const response = await fetch(origin + '/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: '123456' }),
  });
  return (await response.json()).data.accessToken;
}

async function createPost(origin, token, caption, allowComments = true) {
  const body = new FormData();
  body.append('caption', caption);
  body.append('privacy', 'public');
  body.append('allowComments', String(allowComments));
  const res = await fetch(origin + '/v1/posts', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body,
  });
  assert.equal(res.status, 201);
  return (await res.json()).data;
}

test('comment retry after 502 and concurrent retries create one root and one reply with proper scoping', async t => {
  const { origin, child } = await startServer();
  t.after(() => child.kill());

  const tokenA = await login(origin, 'minhanh@momentbox.dev');
  const tokenB = await login(origin, 'travel@momentbox.dev');
  const post1 = await createPost(origin, tokenA, 'Comment retry post 1', true);
  const post2 = await createPost(origin, tokenA, 'Comment retry post 2', true);
  const lockedPost = await createPost(origin, tokenA, 'Comment retry locked', false);

  function postComment(endpoint, token, postId, payload) {
    return fetch(`${endpoint}/v1/posts/${postId}/comments`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
  }

  async function listComments(token, postId) {
    const response = await fetch(`${origin}/v1/posts/${postId}/comments?sort=newest`, {
      headers: { Authorization: 'Bearer ' + token },
    });
    return (await response.json()).data;
  }

  // Proxy cho phép backend lưu (201) rồi trả 502 giả lập lỗi mất ACK về phía client.
  let savedBehind502 = null;
  const proxy502 = http.createServer((req, res) => {
    const headers = { ...req.headers, host: new URL(origin).host };
    const upstream = http.request(origin + req.url, { method: req.method, headers }, reply => {
      const chunks = [];
      reply.on('data', chunk => chunks.push(chunk));
      reply.on('end', () => {
        savedBehind502 = JSON.parse(Buffer.concat(chunks)).data;
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            success: false,
            error: { code: 'BAD_GATEWAY', message: 'Simulated 502 after save' },
          })
        );
      });
    });
    req.pipe(upstream);
  });
  await new Promise(resolve => proxy502.listen(0, '127.0.0.1', resolve));
  t.after(() => proxy502.close());
  const proxyOrigin = 'http://127.0.0.1:' + proxy502.address().port;

  // 1. Bình luận gốc: 502 sau khi lưu -> 2 retry đồng thời cùng clientId chỉ tạo 1 bản ghi.
  const rootClientId = randomUUID();
  const firstAttempt = await postComment(proxyOrigin, tokenA, post1.id, {
    content: '910001',
    clientId: rootClientId,
  });
  assert.equal(firstAttempt.status, 502);
  assert.ok(savedBehind502?.id);
  assert.equal(savedBehind502.clientId, rootClientId);
  assert.equal(savedBehind502.postCommentCount, 1);

  const concurrentRetries = await Promise.all([
    postComment(origin, tokenA, post1.id, { content: '910001', clientId: rootClientId }),
    postComment(origin, tokenA, post1.id, { content: '910001', clientId: rootClientId }),
  ]);
  for (const retry of concurrentRetries) {
    assert.equal(retry.status, 200);
    const body = (await retry.json()).data;
    assert.equal(body.id, savedBehind502.id);
    assert.equal(body.clientId, rootClientId);
    assert.equal(body.postCommentCount, 1);
  }

  // GET chỉ trả clientId cho tác giả, không lộ cho người dùng khác.
  const listForAuthor = await listComments(tokenA, post1.id);
  assert.equal(listForAuthor.length, 1);
  assert.equal(listForAuthor[0].clientId, rootClientId);

  const listForOther = await listComments(tokenB, post1.id);
  assert.equal(listForOther.length, 1);
  assert.equal(listForOther[0].clientId, undefined);

  // 2. Phản hồi con: 502 sau khi lưu -> retry giữ nguyên parentId, trả parentReplyCount=1, postCommentCount=2.
  const replyClientId = randomUUID();
  savedBehind502 = null;
  const reply502 = await postComment(proxyOrigin, tokenA, post1.id, {
    content: '910002',
    parentId: listForAuthor[0].id,
    clientId: replyClientId,
  });
  assert.equal(reply502.status, 502);
  assert.ok(savedBehind502?.id);
  assert.equal(savedBehind502.parentId, listForAuthor[0].id);
  assert.equal(savedBehind502.parentReplyCount, 1);
  assert.equal(savedBehind502.postCommentCount, 2);

  const replyRetry = await postComment(origin, tokenA, post1.id, {
    content: '910002',
    parentId: listForAuthor[0].id,
    clientId: replyClientId,
  });
  assert.equal(replyRetry.status, 200);
  const replyRetryData = (await replyRetry.json()).data;
  assert.equal(replyRetryData.id, savedBehind502.id);
  assert.equal(replyRetryData.parentId, listForAuthor[0].id);
  assert.equal(replyRetryData.parentReplyCount, 1);
  assert.equal(replyRetryData.postCommentCount, 2);

  // 3. Tách phạm vi theo (userId, postId): khác user hoặc khác bài viết vẫn tạo bình luận mới.
  const otherUserRes = await postComment(origin, tokenB, post1.id, {
    content: '910001',
    clientId: rootClientId,
  });
  assert.equal(otherUserRes.status, 201);
  assert.notEqual((await otherUserRes.json()).data.id, listForAuthor[0].id);

  const otherPostRes = await postComment(origin, tokenA, post2.id, {
    content: '910001',
    clientId: rootClientId,
  });
  assert.equal(otherPostRes.status, 201);
  assert.notEqual((await otherPostRes.json()).data.id, listForAuthor[0].id);

  // 4. Hai lần gửi chủ ý cùng nội dung nhưng khác clientId tạo 2 bản ghi riêng.
  const intentional1 = await postComment(origin, tokenA, post2.id, {
    content: '910099',
    clientId: randomUUID(),
  });
  const intentional2 = await postComment(origin, tokenA, post2.id, {
    content: '910099',
    clientId: randomUUID(),
  });
  assert.equal(intentional1.status, 201);
  assert.equal(intentional2.status, 201);
  assert.notEqual((await intentional1.json()).data.id, (await intentional2.json()).data.id);

  // 5. Request cũ không có clientId vẫn chạy; clientId sai định dạng trả 400; bài khóa trả 403.
  const legacyRes = await postComment(origin, tokenA, post2.id, { content: '910100' });
  assert.equal(legacyRes.status, 201);
  assert.equal((await legacyRes.json()).data.clientId, undefined);

  const invalidRes = await postComment(origin, tokenA, post2.id, {
    content: '910101',
    clientId: 'not-a-uuid',
  });
  assert.equal(invalidRes.status, 400);

  const lockedRes = await postComment(origin, tokenA, lockedPost.id, {
    content: '910102',
    clientId: randomUUID(),
  });
  assert.equal(lockedRes.status, 403);
});
