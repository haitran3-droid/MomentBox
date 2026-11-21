// Smoke test: node test/smoke.js [baseUrl]   (server must be running)
const { randomUUID } = require('node:crypto');
const { io } = require('socket.io-client');
const BASE = process.argv[2] || 'http://localhost:3000';
let failed = 0;
const check = (name, cond, extra = '') => { console.log(`${cond ? '✓' : '✗'} ${name}${extra ? ' — ' + extra : ''}`); if (!cond) failed++; };

async function api(method, path, { token, body, headers = {} } = {}) {
  const isForm = body instanceof FormData;
  const res = await fetch(BASE + path, {
    method,
    headers: { ...(token && { Authorization: 'Bearer ' + token }), ...(!isForm && body && { 'Content-Type': 'application/json' }), ...headers },
    body: isForm ? body : body && JSON.stringify(body),
  });
  return { status: res.status, json: await res.json() };
}
const wait = (ms) => new Promise(r => setTimeout(r, ms));
const once = (socket, event, ms = 6000) => new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('timeout waiting ' + event)), ms);
  socket.once(event, v => { clearTimeout(t); resolve(v); });
});

(async () => {
  // --- REST
  let r = await api('POST', '/v1/auth/login', { body: { email: 'minhanh@momentbox.dev', password: 'sai' }, headers: { 'Accept-Language': 'en' } });
  check('login sai mật khẩu -> 401 INVALID_CREDENTIALS (en)', r.status === 401 && r.json.error.code === 'INVALID_CREDENTIALS', r.json.error.message);
  r = await api('POST', '/v1/auth/login', { body: { email: 'minhanh@momentbox.dev', password: '123456' } });
  check('login đúng', r.status === 200 && r.json.data.accessToken && r.json.data.user.displayName === 'Nguyễn Minh Anh');
  let { accessToken, refreshToken } = r.json.data;

  r = await api('GET', '/v1/config');
  check('config có danh sách âm thanh', r.json.data.sounds.length === 8);
  r = await api('GET', '/v1/feed?page=1&pageSize=10', { token: accessToken });
  const kinds = r.json.data.map(x => x.kind);
  check('feed trang 1: 10 post + 1 khối suggestions', kinds.filter(k => k === 'post').length === 10 && kinds[3] === 'suggestions', JSON.stringify(r.json.meta));
  const types = new Set(r.json.data.filter(x => x.post).map(x => x.post.type));
  check('feed có đủ loại PHOTO/VIDEO/TEXT/VOICE', ['PHOTO', 'VIDEO', 'TEXT', 'VOICE'].every(t => types.has(t)), [...types].join(','));
  const voicePost = r.json.data.find(x => x.post && x.post.type === 'VOICE').post;
  check('URL audio là tuyệt đối', voicePost.media[0].url.startsWith(BASE + '/media/audio/'));
  const audio = await fetch(voicePost.media[0].url);
  check('file WAV tải được', audio.status === 200 && audio.headers.get('content-type').includes('audio'));
  r = await api('GET', '/v1/feed?page=5&pageSize=10', { token: accessToken });
  check('trang cuối hasMore=false', r.json.meta.hasMore === false, JSON.stringify(r.json.meta));

  r = await api('POST', '/v1/posts/2/like', { token: accessToken });
  check('like', r.json.data.isLiked === true);
  r = await api('GET', '/v1/posts/1/comments?sort=newest&page=1&pageSize=5', { token: accessToken });
  check('comments', r.status === 200 && Array.isArray(r.json.data));
  const before = (await api('GET', '/v1/posts/1', { token: accessToken })).json.data.commentCount;
  r = await api('POST', '/v1/posts/1/comments', { token: accessToken, body: { content: 'Bình luận từ smoke test' } });
  check('tạo comment', r.status === 201);
  const parentId = r.json.data.id;
  check('root write returns updated post comment count', r.json.data.postCommentCount === before + 1);
  r = await api('POST', '/v1/posts/1/comments', { token: accessToken, body: { content: 'Phản hồi', parentId: String(parentId) } });
  const replies = await api('GET', '/v1/posts/1/comments?parentId=' + parentId, { token: accessToken });
  check('reply write returns updated post comment count', r.json.data.postCommentCount === before + 2);
  check('phản hồi lưu parentId dạng số và tải lại được', r.status === 201 &&
    r.json.data.parentId === parentId && replies.json.data.some(comment => comment.id === r.json.data.id));
  r = await api('POST', '/v1/posts/2/comments', { token: accessToken, body: { content: 'Phản hồi nhầm bài', parentId } });
  check('không lưu phản hồi vào nhầm bài', r.status === 400 && r.json.error.code === 'VALIDATION_ERROR');

  const form = new FormData();
  form.append('caption', 'Bài viết test #smoke #android');
  form.append('privacy', 'public');
  form.append('media', new Blob([Buffer.from('fake-jpeg')], { type: 'image/jpeg' }), 'a.jpg');
  r = await api('POST', '/v1/posts', { token: accessToken, body: form });
  check('tạo bài viết multipart', r.status === 201 && r.json.data.type === 'PHOTO' && r.json.data.tags.includes('smoke'));
  const uploadedPost = r.json.data;
  const uploadedMediaUrl = uploadedPost.media[0].url;
  const beforeDelete = await fetch(uploadedMediaUrl);
  const deletedUploadPost = await api('DELETE', '/v1/posts/' + uploadedPost.id, { token: accessToken });
  const afterDelete = await fetch(uploadedMediaUrl);
  check('xóa bài viết dọn cả tệp media của bài',
    beforeDelete.status === 200 && deletedUploadPost.status === 200 && afterDelete.status === 404);

  const createTextPost = async privacy => {
    const body = new FormData();
    body.append('caption', 'Bài viết kiểm thử');
    body.append('privacy', privacy);
    return api('POST', '/v1/posts', { token: accessToken, body });
  };
  const privatePost = (await createTextPost('private')).json.data;
  const follower = await api('POST', '/v1/auth/login', { body: { email: 'lananh@momentbox.dev', password: '123456' } });
  const followerToken = follower.json.data.accessToken;
  const followerFeed = await api('GET', '/v1/feed?page=1&pageSize=10', { token: followerToken });
  check('bài riêng tư không xuất hiện trong feed của người theo dõi',
    followerFeed.status === 200 && !followerFeed.json.data.some(item => item.post?.id === privatePost.id));
  const privatePath = '/v1/posts/' + privatePost.id;
  await api('POST', privatePath + '/like', { token: accessToken });
  await api('POST', privatePath + '/bookmark', { token: accessToken });
  await api('POST', privatePath + '/comments', { token: accessToken, body: { content: 'Chỉ thuộc bài đã xóa' } });
  const deletion = await api('DELETE', privatePath, { token: accessToken });
  const newPost = (await createTextPost('public')).json.data;
  check('xóa rồi tạo bài mới không tái sử dụng ID hay tương tác',
    deletion.status === 200 && newPost.id > privatePost.id && newPost.commentCount === 0 &&
    newPost.likeCount === 0 && !newPost.isLiked && !newPost.isBookmarked);
  await api('DELETE', '/v1/posts/' + newPost.id, { token: accessToken });

  r = await api('GET', '/v1/feed', { token: accessToken, headers: { 'X-Simulate-Delay': '300', 'X-Simulate-Error': '500' } });
  check('giả lập lỗi 500', r.status === 500);

  // --- refresh token flow
  await api('POST', '/v1/debug/expire-token', { token: accessToken });
  r = await api('GET', '/v1/me', { token: accessToken });
  check('token hết hạn -> 401 TOKEN_EXPIRED', r.status === 401 && r.json.error.code === 'TOKEN_EXPIRED');
  r = await api('POST', '/v1/auth/refresh', { body: { refreshToken } });
  check('refresh thành công', r.status === 200 && r.json.data.accessToken !== accessToken);
  const oldRefresh = refreshToken;
  ({ accessToken, refreshToken } = r.json.data);
  r = await api('POST', '/v1/auth/refresh', { body: { refreshToken: oldRefresh } });
  check('refresh token cũ bị vô hiệu (rotation)', r.status === 401 && r.json.error.code === 'REFRESH_TOKEN_INVALID');
  r = await api('GET', '/v1/me', { token: accessToken });
  check('gọi lại bằng token mới', r.status === 200);

  // --- Socket.IO
  const bad = io(BASE, { auth: { token: 'nope' }, reconnection: false });
  const err = await once(bad, 'connect_error');
  check('socket token sai -> connect_error UNAUTHORIZED', err.message === 'UNAUTHORIZED');
  bad.close();

  const socket = io(BASE, { auth: { token: accessToken }, reconnectionDelay: 500, reconnectionDelayMax: 1000 });
  await once(socket, 'connect');
  check('socket connect', socket.connected);
  r = await api('GET', '/v1/conversations', { token: accessToken });
  check('danh sách hội thoại', r.json.data.length === 4, r.json.data.map(c => `${c.title}(${c.unreadCount})`).join(', '));
  const conv = r.json.data.find(c => c.title === 'Lan Anh');

  const typing = once(socket, 'typing');
  const clientId = randomUUID();
  const ack = await socket.timeout(5000).emitWithAck('message:send', { clientId, conversationId: conv.id, type: 'text', content: 'Đi cafe không?' });
  check('message:send ack ok', ack.ok && ack.message.clientId === clientId);
  check('bot đang soạn tin', (await typing).isTyping === true);
  const botMsg = await once(socket, 'message:new');
  check('bot trả lời', botMsg.message.senderId === 2, botMsg.message.content);

  const failAck = await socket.timeout(5000).emitWithAck('message:send', { clientId: randomUUID(), conversationId: conv.id, content: 'test #fail' });
  check('#fail -> ack lỗi', failAck.ok === false && failAck.error.code === 'SIMULATED_FAILURE');
  let timedOut = false;
  try { await socket.timeout(2000).emitWithAck('message:send', { clientId: randomUUID(), conversationId: conv.id, content: 'test #slow' }); } catch { timedOut = true; }
  check('#slow -> client ack timeout', timedOut);

  const reconnected = once(socket.io, 'reconnect', 8000);
  await api('POST', '/v1/debug/socket/drop?mode=transport', { token: accessToken });
  const attempt = await reconnected;
  if (!socket.connected) await once(socket, 'connect');
  check('drop transport -> tự reconnect', socket.connected, 'sau ' + attempt + ' lần thử');

  r = await api('GET', `/v1/conversations/${conv.id}/messages?limit=2`, { token: accessToken });
  check('phân trang tin nhắn theo cursor', r.json.data.length === 2 && r.json.meta.hasMore && r.json.meta.nextBefore, JSON.stringify(r.json.meta));

  socket.close();
  await wait(100);
  console.log(failed ? `\n${failed} kiểm tra lỗi` : '\nTất cả kiểm tra đều đạt');
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
