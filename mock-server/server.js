// MomentBox mock server — REST (Express) + realtime (Socket.IO) for the Android fresher project.
// Data is in memory and resets on restart. See README.md for the full API contract.
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const http = require('http');
const express = require('express');
const cors = require('cors');
const multer = require('multer');
const { Server } = require('socket.io');
const seed = require('./seed');
const { ensureAudio } = require('./audio');

const PORT = +process.env.PORT || 3000;
const ACCESS_TTL = +process.env.ACCESS_TOKEN_TTL || 900;            // seconds
const REFRESH_TTL = +process.env.REFRESH_TOKEN_TTL || 7 * 24 * 3600; // seconds
const AUDIO_DIR = path.join(__dirname, 'public', 'audio');
const UPLOAD_DIR = path.join(__dirname, 'uploads');

const db = seed.create();
let outageUntil = 0; // set by /v1/debug/socket/drop?outageSeconds=N
const audioFiles = ensureAudio(AUDIO_DIR);
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const app = express();
const server = http.createServer(app);
// allowRequest rejects at the transport level during a simulated outage, so clients keep retrying with backoff.
// (A rejection inside io.use() is final: the client will NOT auto-reconnect and must call connect() itself.)
const io = new Server(server, {
  cors: { origin: '*' }, pingInterval: 25000, pingTimeout: 20000,
  allowRequest: (req, cb) => cb(Date.now() < outageUntil ? 'SERVER_UNAVAILABLE' : null, Date.now() >= outageUntil),
});

app.set('trust proxy', true);
app.use(cors());
app.use(express.json({ limit: '5mb' }));
app.use('/media', express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(UPLOAD_DIR));

// ---------------------------------------------------------------- helpers
const MSG = {
  VALIDATION_ERROR: { vi: 'Dữ liệu không hợp lệ', en: 'Invalid data' },
  INVALID_CREDENTIALS: { vi: 'Email hoặc mật khẩu không đúng', en: 'Incorrect email or password' },
  UNAUTHORIZED: { vi: 'Bạn cần đăng nhập', en: 'Authentication required' },
  TOKEN_EXPIRED: { vi: 'Phiên đăng nhập đã hết hạn', en: 'Access token expired' },
  REFRESH_TOKEN_INVALID: { vi: 'Phiên đăng nhập không còn hiệu lực, vui lòng đăng nhập lại', en: 'Refresh token is invalid' },
  FORBIDDEN: { vi: 'Bạn không có quyền thực hiện thao tác này', en: 'Forbidden' },
  NOT_FOUND: { vi: 'Không tìm thấy dữ liệu', en: 'Not found' },
  INTERNAL: { vi: 'Có lỗi xảy ra, vui lòng thử lại', en: 'Something went wrong' },
};
const lang = req => ((req.headers && req.headers['accept-language']) || 'vi').toLowerCase().startsWith('en') ? 'en' : 'vi';
const ok = (res, data, meta) => res.json(meta ? { success: true, data, meta } : { success: true, data });
const fail = (req, res, status, code, details) =>
  res.status(status).json({ success: false, error: { code, message: (MSG[code] || MSG.INTERNAL)[lang(req)], ...(details && { details }) } });

const baseOf = req => `${req.protocol}://${req.get('host')}`;
const absUrl = (base, u) => (u && u.startsWith('/') ? base + u : u);
const nextId = list => list.reduce((m, x) => Math.max(m, x.id), 0) + 1;
const byNewest = (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt);

function paginate(list, q) {
  const page = Math.max(1, parseInt(q.page) || 1);
  const pageSize = Math.min(50, Math.max(1, parseInt(q.pageSize) || 10));
  const total = list.length;
  return { items: list.slice((page - 1) * pageSize, page * pageSize), meta: { page, pageSize, total, hasMore: page * pageSize < total } };
}

function issueTokens(userId) {
  const accessToken = 'at_' + crypto.randomBytes(16).toString('hex');
  const refreshToken = 'rt_' + crypto.randomBytes(24).toString('hex');
  db.accessTokens.set(accessToken, { userId, exp: Date.now() + ACCESS_TTL * 1000 });
  db.refreshTokens.set(refreshToken, { userId, exp: Date.now() + REFRESH_TTL * 1000 });
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: ACCESS_TTL };
}

const findUser = id => db.users.find(u => u.id === +id);
const findPost = id => db.posts.find(p => p.id === +id);
const online = new Map(db.users.filter(u => u.bot).map(u => [u.id, 1])); // userId -> socket count (bots always online)
const lastSeen = new Map();

function authorDto(base, u) {
  return { id: u.id, username: u.username, displayName: u.displayName, avatarUrl: absUrl(base, u.avatar), verified: !!u.verified };
}
function userDto(base, u, viewer) {
  const followers = [...db.follows].filter(f => f.endsWith(':' + u.id)).length;
  const following = [...db.follows].filter(f => f.startsWith(u.id + ':')).length;
  return {
    ...authorDto(base, u), bio: u.bio, location: u.location,
    postCount: db.posts.filter(p => p.authorId === u.id).length, followerCount: followers, followingCount: following,
    isFollowing: viewer ? db.follows.has(`${viewer.id}:${u.id}`) : false, isMe: viewer ? viewer.id === u.id : false,
    online: online.has(u.id), lastSeenAt: lastSeen.get(u.id) || null,
  };
}
function postDto(base, p, viewer) {
  return {
    id: p.id, type: p.type, author: authorDto(base, findUser(p.authorId)), caption: p.caption, tags: p.tags,
    location: p.location, privacy: p.privacy, allowComments: p.allowComments,
    media: p.media.map(m => ({ ...m, url: absUrl(base, m.url), thumbnailUrl: absUrl(base, m.thumbnailUrl) })),
    likeCount: [...db.likes].filter(k => k.endsWith(':' + p.id)).length,
    commentCount: db.comments.filter(c => c.postId === p.id).length,
    isLiked: db.likes.has(`${viewer.id}:${p.id}`), isBookmarked: db.bookmarks.has(`${viewer.id}:${p.id}`),
    createdAt: p.createdAt,
  };
}
function commentDto(base, c, viewer) {
  return {
    id: c.id, postId: c.postId, parentId: c.parentId, author: authorDto(base, findUser(c.authorId)), content: c.content,
    likeCount: [...db.commentLikes].filter(k => k.endsWith(':' + c.id)).length, isLiked: db.commentLikes.has(`${viewer.id}:${c.id}`),
    replyCount: db.comments.filter(x => x.parentId === c.id).length, createdAt: c.createdAt,
  };
}
function messageDto(base, m) {
  return { ...m, mediaUrl: absUrl(base, m.mediaUrl) };
}
function conversationDto(base, c, viewer) {
  const msgs = db.messages.filter(m => m.conversationId === c.id).sort(byNewest);
  const others = c.memberIds.filter(id => id !== viewer.id).map(findUser);
  return {
    id: c.id, type: c.type,
    title: c.title || others[0].displayName,
    avatarUrl: c.type === 'direct' ? absUrl(base, others[0].avatar) : null,
    members: c.memberIds.map(id => ({ ...authorDto(base, findUser(id)), online: online.has(id) })),
    lastMessage: msgs[0] ? messageDto(base, msgs[0]) : null,
    unreadCount: msgs.filter(m => m.senderId !== viewer.id && m.status !== 'seen').length,
    updatedAt: msgs[0] ? msgs[0].createdAt : null,
  };
}
const visiblePosts = viewer => db.posts.filter(p => p.privacy === 'public' || p.authorId === viewer.id || db.follows.has(`${viewer.id}:${p.authorId}`));

// ---------------------------------------------------------------- middlewares
// Request log + debug headers: X-Simulate-Delay: <ms>, X-Simulate-Error: 401 | 500
app.use((req, res, next) => {
  const t0 = Date.now();
  res.on('finish', () => console.log(`${req.method} ${req.originalUrl} -> ${res.statusCode} (${Date.now() - t0}ms)`));
  const delay = Math.min(30000, parseInt(req.get('X-Simulate-Delay')) || 0);
  setTimeout(() => {
    const err = req.get('X-Simulate-Error');
    if (err === '401') return fail(req, res, 401, 'TOKEN_EXPIRED');
    if (err === '500') return fail(req, res, 500, 'INTERNAL');
    next();
  }, delay);
});

function auth(req, res, next) {
  const h = req.get('Authorization') || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : null;
  const session = token && db.accessTokens.get(token);
  if (!session) return fail(req, res, 401, 'UNAUTHORIZED');
  if (session.exp < Date.now()) return fail(req, res, 401, 'TOKEN_EXPIRED');
  req.user = findUser(session.userId);
  req.token = token;
  next();
}

const upload = multer({
  storage: multer.diskStorage({
    destination: UPLOAD_DIR,
    filename: (req, f, cb) => cb(null, `${Date.now()}-${crypto.randomBytes(4).toString('hex')}${path.extname(f.originalname || '')}`),
  }),
  limits: { fileSize: 50 * 1024 * 1024 },
});
const mediaType = mime => (mime.startsWith('video') ? 'video' : mime.startsWith('audio') ? 'audio' : 'image');

// ---------------------------------------------------------------- config & auth
app.get('/v1/config', (req, res) => ok(res, {
  minSupportedVersionCode: 1, latestVersionCode: 12,
  socketUrl: baseOf(req), socketPath: '/socket.io',
  accessTokenTtl: ACCESS_TTL,
  sounds: audioFiles.map(f => ({ name: f, url: `${baseOf(req)}/media/audio/${f}` })),
  serverTime: new Date().toISOString(),
}));

app.post('/v1/auth/login', (req, res) => {
  const { email, password } = req.body || {};
  if (!email || !password) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['email', 'password'].filter(k => !req.body?.[k]) });
  const u = db.users.find(x => x.email.toLowerCase() === String(email).toLowerCase() && x.password === password);
  if (!u) return fail(req, res, 401, 'INVALID_CREDENTIALS');
  ok(res, { ...issueTokens(u.id), user: userDto(baseOf(req), u, u) });
});

app.post('/v1/auth/refresh', (req, res) => {
  const { refreshToken } = req.body || {};
  const s = refreshToken && db.refreshTokens.get(refreshToken);
  if (!s || s.exp < Date.now()) return fail(req, res, 401, 'REFRESH_TOKEN_INVALID');
  db.refreshTokens.delete(refreshToken); // rotation: each refresh token works once
  ok(res, issueTokens(s.userId));
});

app.post('/v1/auth/logout', auth, (req, res) => {
  db.accessTokens.delete(req.token);
  if (req.body?.refreshToken) db.refreshTokens.delete(req.body.refreshToken);
  ok(res, null);
});

// ---------------------------------------------------------------- users
app.get('/v1/me', auth, (req, res) => ok(res, userDto(baseOf(req), req.user, req.user)));

app.patch('/v1/me', auth, (req, res) => {
  const { displayName, bio, location } = req.body || {};
  if (displayName !== undefined && !String(displayName).trim()) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['displayName'] });
  Object.assign(req.user, displayName !== undefined && { displayName }, bio !== undefined && { bio }, location !== undefined && { location });
  ok(res, userDto(baseOf(req), req.user, req.user));
});

app.post('/v1/me/avatar', auth, upload.single('avatar'), (req, res) => {
  if (!req.file) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['avatar'] });
  req.user.avatar = '/uploads/' + req.file.filename;
  ok(res, userDto(baseOf(req), req.user, req.user));
});

app.get('/v1/users/suggestions', auth, (req, res) => {
  const list = db.users.filter(u => u.id !== req.user.id && !db.follows.has(`${req.user.id}:${u.id}`));
  ok(res, list.map(u => ({ ...userDto(baseOf(req), u, req.user), mutualCount: (u.id * 5) % 13 })));
});

app.get('/v1/users/:id', auth, (req, res) => {
  const u = findUser(req.params.id);
  if (!u) return fail(req, res, 404, 'NOT_FOUND');
  ok(res, userDto(baseOf(req), u, req.user));
});

app.get('/v1/users/:id/posts', auth, (req, res) => {
  const u = findUser(req.params.id);
  if (!u) return fail(req, res, 404, 'NOT_FOUND');
  let list = visiblePosts(req.user).filter(p => p.authorId === u.id);
  if (req.query.type) list = list.filter(p => p.type === String(req.query.type).toUpperCase());
  const { items, meta } = paginate(list.sort(byNewest), req.query);
  ok(res, items.map(p => postDto(baseOf(req), p, req.user)), meta);
});

app.get('/v1/me/bookmarks', auth, (req, res) => {
  const list = db.posts.filter(p => db.bookmarks.has(`${req.user.id}:${p.id}`)).sort(byNewest);
  const { items, meta } = paginate(list, req.query);
  ok(res, items.map(p => postDto(baseOf(req), p, req.user)), meta);
});

app.post('/v1/users/:id/follow', auth, (req, res) => {
  const u = findUser(req.params.id);
  if (!u || u.id === req.user.id) return fail(req, res, 400, 'VALIDATION_ERROR');
  db.follows.add(`${req.user.id}:${u.id}`);
  ok(res, userDto(baseOf(req), u, req.user));
});
app.delete('/v1/users/:id/follow', auth, (req, res) => {
  const u = findUser(req.params.id);
  if (!u) return fail(req, res, 404, 'NOT_FOUND');
  db.follows.delete(`${req.user.id}:${u.id}`);
  ok(res, userDto(baseOf(req), u, req.user));
});

// ---------------------------------------------------------------- feed & posts
app.get('/v1/stories', auth, (req, res) => ok(res, db.stories.map(s => ({
  user: authorDto(baseOf(req), findUser(s.userId)), hasUnseen: s.hasUnseen, items: s.items,
}))));

app.get('/v1/feed', auth, (req, res) => {
  const base = baseOf(req);
  const { items, meta } = paginate(visiblePosts(req.user).sort(byNewest), req.query);
  const out = items.map(p => ({ kind: 'post', post: postDto(base, p, req.user) }));
  if (meta.page === 1 && out.length > 3) {
    const users = db.users.filter(u => u.id !== req.user.id && !db.follows.has(`${req.user.id}:${u.id}`)).slice(0, 6);
    out.splice(3, 0, { kind: 'suggestions', users: users.map(u => ({ ...userDto(base, u, req.user), mutualCount: (u.id * 5) % 13 })) });
  }
  ok(res, out, meta);
});

app.get('/v1/posts/:id', auth, (req, res) => {
  const p = findPost(req.params.id);
  if (!p) return fail(req, res, 404, 'NOT_FOUND');
  ok(res, postDto(baseOf(req), p, req.user));
});

app.post('/v1/posts', auth, upload.fields([{ name: 'media', maxCount: 10 }, { name: 'voice', maxCount: 1 }]), (req, res) => {
  const caption = String(req.body.caption || '').trim();
  const files = (req.files && req.files.media) || [];
  const voice = req.files && req.files.voice && req.files.voice[0];
  if (caption.length > 2200) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['caption'], max: 2200 });
  if (!caption && !files.length && !voice) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['caption', 'media', 'voice'] });
  const media = files.map(f => ({ type: mediaType(f.mimetype), url: '/uploads/' + f.filename }));
  if (voice) media.push({ type: 'audio', url: '/uploads/' + voice.filename, durationMs: parseInt(req.body.voiceDurationMs) || null });
  const type = voice ? 'VOICE' : media.some(m => m.type === 'video') ? 'VIDEO' : media.length ? 'PHOTO' : 'TEXT';
  const p = {
    id: nextId(db.posts), authorId: req.user.id, type, caption, media,
    tags: (caption.match(/#[\p{L}\p{N}_]+/gu) || []).map(t => t.slice(1).toLowerCase()),
    location: req.body.location || null, privacy: ['public', 'friends', 'private'].includes(req.body.privacy) ? req.body.privacy : 'public',
    allowComments: req.body.allowComments !== 'false', createdAt: new Date().toISOString(),
  };
  db.posts.push(p);
  io.except('user:' + req.user.id).emit('feed:new', { count: 1, postId: p.id });
  res.status(201);
  ok(res, postDto(baseOf(req), p, req.user));
});

app.delete('/v1/posts/:id', auth, (req, res) => {
  const p = findPost(req.params.id);
  if (!p) return fail(req, res, 404, 'NOT_FOUND');
  if (p.authorId !== req.user.id) return fail(req, res, 403, 'FORBIDDEN');
  db.posts.splice(db.posts.indexOf(p), 1);
  ok(res, null);
});

function toggle(set, key, on) { on ? set.add(key) : set.delete(key); }
for (const [route, set] of [['like', db.likes], ['bookmark', db.bookmarks]]) {
  for (const method of ['post', 'delete']) {
    app[method](`/v1/posts/:id/${route}`, auth, (req, res) => {
      const p = findPost(req.params.id);
      if (!p) return fail(req, res, 404, 'NOT_FOUND');
      toggle(set, `${req.user.id}:${p.id}`, method === 'post');
      const dto = postDto(baseOf(req), p, req.user);
      ok(res, { postId: p.id, isLiked: dto.isLiked, likeCount: dto.likeCount, isBookmarked: dto.isBookmarked });
    });
  }
}

app.get('/v1/posts/:id/comments', auth, (req, res) => {
  const p = findPost(req.params.id);
  if (!p) return fail(req, res, 404, 'NOT_FOUND');
  const parentId = req.query.parentId ? +req.query.parentId : null;
  let list = db.comments.filter(c => c.postId === p.id && c.parentId === parentId);
  list = req.query.sort === 'top'
    ? list.sort((a, b) => commentDto('', b, req.user).likeCount - commentDto('', a, req.user).likeCount)
    : list.sort(byNewest);
  const { items, meta } = paginate(list, req.query);
  ok(res, items.map(c => commentDto(baseOf(req), c, req.user)), meta);
});

app.post('/v1/posts/:id/comments', auth, (req, res) => {
  const p = findPost(req.params.id);
  if (!p) return fail(req, res, 404, 'NOT_FOUND');
  const content = String(req.body?.content || '').trim();
  if (!content || content.length > 500) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['content'], max: 500 });
  if (!p.allowComments) return fail(req, res, 403, 'FORBIDDEN');
  const c = { id: nextId(db.comments), postId: p.id, parentId: req.body.parentId || null, authorId: req.user.id, content, createdAt: new Date().toISOString() };
  db.comments.push(c);
  res.status(201);
  ok(res, commentDto(baseOf(req), c, req.user));
});

app.post('/v1/comments/:id/like', auth, (req, res) => { db.commentLikes.add(`${req.user.id}:${+req.params.id}`); ok(res, null); });
app.delete('/v1/comments/:id/like', auth, (req, res) => { db.commentLikes.delete(`${req.user.id}:${+req.params.id}`); ok(res, null); });

// ---------------------------------------------------------------- chat (REST part)
app.get('/v1/conversations', auth, (req, res) => {
  const list = db.conversations.filter(c => c.memberIds.includes(req.user.id)).map(c => conversationDto(baseOf(req), c, req.user));
  list.sort((a, b) => Date.parse(b.updatedAt || 0) - Date.parse(a.updatedAt || 0));
  ok(res, list);
});

app.post('/v1/conversations', auth, (req, res) => {
  const other = findUser(req.body?.userId);
  if (!other || other.id === req.user.id) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['userId'] });
  let c = db.conversations.find(x => x.type === 'direct' && x.memberIds.includes(other.id) && x.memberIds.includes(req.user.id));
  if (!c) {
    c = { id: nextId(db.conversations), type: 'direct', memberIds: [req.user.id, other.id], title: null };
    db.conversations.push(c);
    io.in(['user:' + req.user.id, 'user:' + other.id]).socketsJoin('conv:' + c.id);
  }
  ok(res, conversationDto(baseOf(req), c, req.user));
});

// Cursor paging: ?before=<messageId>&limit=30  -> newest first
app.get('/v1/conversations/:id/messages', auth, (req, res) => {
  const c = db.conversations.find(x => x.id === +req.params.id);
  if (!c) return fail(req, res, 404, 'NOT_FOUND');
  if (!c.memberIds.includes(req.user.id)) return fail(req, res, 403, 'FORBIDDEN');
  const limit = Math.min(100, parseInt(req.query.limit) || 30);
  let list = db.messages.filter(m => m.conversationId === c.id).sort((a, b) => b.id - a.id);
  if (req.query.before) list = list.filter(m => m.id < +req.query.before);
  const items = list.slice(0, limit);
  ok(res, items.map(m => messageDto(baseOf(req), m)), { limit, hasMore: list.length > limit, nextBefore: items.length ? items[items.length - 1].id : null });
});

app.post('/v1/uploads', auth, upload.single('file'), (req, res) => {
  if (!req.file) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['file'] });
  res.status(201);
  ok(res, { url: `${baseOf(req)}/uploads/${req.file.filename}`, type: mediaType(req.file.mimetype), size: req.file.size });
});

// ---------------------------------------------------------------- journal backup (optional Gson practice)
app.put('/v1/journal/backup', auth, (req, res) => {
  if (!Array.isArray(req.body?.entries)) return fail(req, res, 400, 'VALIDATION_ERROR', { fields: ['entries'] });
  db.journalBackups.set(req.user.id, { entries: req.body.entries, updatedAt: new Date().toISOString() });
  ok(res, { count: req.body.entries.length, updatedAt: db.journalBackups.get(req.user.id).updatedAt });
});
app.get('/v1/journal/backup', auth, (req, res) => ok(res, db.journalBackups.get(req.user.id) || { entries: [], updatedAt: null }));

// ---------------------------------------------------------------- debug helpers (for Developer tools screen)
app.post('/v1/debug/expire-token', auth, (req, res) => {
  db.accessTokens.get(req.token).exp = 0;
  ok(res, { message: 'Access token hiện tại đã bị đánh dấu hết hạn. Request tiếp theo sẽ nhận 401 TOKEN_EXPIRED.' });
});

app.post('/v1/debug/socket/drop', auth, (req, res) => {
  const mode = req.query.mode === 'server' ? 'server' : 'transport';
  const seconds = Math.min(120, parseInt(req.query.outageSeconds) || 0);
  if (seconds) outageUntil = Date.now() + seconds * 1000;
  io.in('user:' + req.user.id).fetchSockets().then(sockets => {
    sockets.forEach(s => (mode === 'server' ? s.disconnect(true) : s.conn.close()));
    ok(res, { dropped: sockets.length, mode, outageSeconds: seconds });
  });
});

app.post('/v1/debug/feed-new', auth, (req, res) => { io.to('user:' + req.user.id).emit('feed:new', { count: parseInt(req.body?.count) || 3 }); ok(res, null); });

app.post('/v1/debug/notify', auth, (req, res) => {
  io.to('user:' + req.user.id).emit('notification', {
    type: req.body?.type || 'system', title: req.body?.title || 'MomentBox', body: req.body?.body || 'Thông báo thử nghiệm', data: req.body?.data || {},
  });
  ok(res, null);
});

// ---------------------------------------------------------------- landing page & errors
app.get('/', (req, res) => {
  const accounts = db.users.map(u => `<tr><td>${u.email}</td><td>${seed.PASSWORD}</td><td>${u.displayName}${u.bot ? ' (bot tự trả lời chat)' : ''}</td></tr>`).join('');
  res.type('html').send(`<!doctype html><meta charset="utf-8"><title>MomentBox mock server</title>
<body style="font-family:system-ui;max-width:760px;margin:40px auto;padding:0 16px;line-height:1.5">
<h1>MomentBox mock server ✅</h1><p>REST base URL: <code>${baseOf(req)}/v1/</code> · Socket.IO: <code>${baseOf(req)}</code> (path <code>/socket.io</code>)</p>
<p>Access token TTL: ${ACCESS_TTL}s · Online sockets: ${io.engine.clientsCount}</p>
<h3>Tài khoản demo</h3><table border="1" cellpadding="6" style="border-collapse:collapse">${accounts}</table>
<p>Xem README.md để biết toàn bộ API.</p></body>`);
});
app.use((req, res) => fail(req, res, 404, 'NOT_FOUND'));
app.use((err, req, res, next) => { console.error(err); fail(req, res, err.code === 'LIMIT_FILE_SIZE' ? 400 : 500, err.code === 'LIMIT_FILE_SIZE' ? 'VALIDATION_ERROR' : 'INTERNAL'); });

// ---------------------------------------------------------------- Socket.IO
const socketBase = s => `${s.handshake.headers['x-forwarded-proto'] || 'http'}://${s.handshake.headers['x-forwarded-host'] || s.handshake.headers.host}`;

io.use((socket, next) => {
  const token = socket.handshake.auth?.token || socket.handshake.query?.token;
  const session = token && db.accessTokens.get(token);
  if (!session) return next(new Error('UNAUTHORIZED'));
  if (session.exp < Date.now()) return next(new Error('TOKEN_EXPIRED'));
  socket.data.userId = session.userId;
  next();
});

function setPresence(userId, delta) {
  const n = (online.get(userId) || 0) + delta;
  if (n > 0) online.set(userId, n); else { online.delete(userId); lastSeen.set(userId, new Date().toISOString()); }
  if ((delta > 0 && n === 1) || n <= 0) io.emit('presence:update', { userId, online: n > 0, lastSeenAt: lastSeen.get(userId) || null });
}

const BOT_REPLIES = ['Haha đúng rồi 😆', 'Ok nha!', 'Để mình xem lịch đã…', 'Tuyệt vời 👍', 'Mai gặp nhé!', 'Nghe hay đó 😍'];
function botReply(conv, senderId, msg, base) {
  const bot = conv.memberIds.map(findUser).find(u => u.bot && u.id !== senderId);
  if (!bot) return;
  const text = msg.type === 'image' ? 'Ảnh đẹp quá 😍' : msg.type === 'voice' ? 'Để tối mình nghe nha 🎧'
    : msg.content.includes('?') ? 'Câu hỏi hay đó 🤔 để mình nghĩ chút' : BOT_REPLIES[msg.id % BOT_REPLIES.length];
  setTimeout(() => {
    msg.status = 'seen';
    io.to('conv:' + conv.id).emit('message:status', { conversationId: conv.id, messageId: msg.id, clientId: msg.clientId, status: 'seen', userId: bot.id });
    io.to('conv:' + conv.id).emit('typing', { conversationId: conv.id, userId: bot.id, isTyping: true });
  }, 700);
  setTimeout(() => {
    io.to('conv:' + conv.id).emit('typing', { conversationId: conv.id, userId: bot.id, isTyping: false });
    const reply = { id: nextId(db.messages), clientId: null, conversationId: conv.id, senderId: bot.id, type: 'text', content: text, mediaUrl: null, durationMs: null, status: 'delivered', createdAt: new Date().toISOString() };
    db.messages.push(reply);
    io.to('conv:' + conv.id).emit('message:new', { message: messageDto(base, reply) });
  }, 2200 + (msg.id % 3) * 600);
}

io.on('connection', socket => {
  const uid = socket.data.userId;
  const base = socketBase(socket);
  socket.join('user:' + uid);
  db.conversations.filter(c => c.memberIds.includes(uid)).forEach(c => socket.join('conv:' + c.id));
  setPresence(uid, +1);
  console.log(`[socket] user ${uid} connected (${socket.id})`);

  const member = id => { const c = db.conversations.find(x => x.id === +id); return c && c.memberIds.includes(uid) ? c : null; };

  socket.on('typing:start', ({ conversationId } = {}) => member(conversationId) && socket.to('conv:' + conversationId).emit('typing', { conversationId: +conversationId, userId: uid, isTyping: true }));
  socket.on('typing:stop', ({ conversationId } = {}) => member(conversationId) && socket.to('conv:' + conversationId).emit('typing', { conversationId: +conversationId, userId: uid, isTyping: false }));

  // payload: { clientId, conversationId, type: text|image|voice, content, mediaUrl, durationMs }
  // Test hooks: content containing "#fail" -> ack error; "#slow" -> ack after 8s (longer than a 5s client timeout)
  socket.on('message:send', (payload = {}, ack) => {
    const reply = typeof ack === 'function' ? ack : () => {};
    const conv = member(payload.conversationId);
    const type = ['text', 'image', 'voice'].includes(payload.type) ? payload.type : 'text';
    const content = String(payload.content || '').trim();
    if (!conv) return reply({ ok: false, error: { code: 'FORBIDDEN' } });
    if (type === 'text' && !content) return reply({ ok: false, error: { code: 'VALIDATION_ERROR' } });
    if (type !== 'text' && !payload.mediaUrl) return reply({ ok: false, error: { code: 'VALIDATION_ERROR' } });
    if (content.includes('#fail')) return reply({ ok: false, error: { code: 'SIMULATED_FAILURE' } });

    const msg = {
      id: nextId(db.messages), clientId: payload.clientId || null, conversationId: conv.id, senderId: uid, type, content,
      mediaUrl: payload.mediaUrl || null, durationMs: payload.durationMs || null, status: 'sent', createdAt: new Date().toISOString(),
    };
    db.messages.push(msg);
    const deliver = () => {
      reply({ ok: true, message: messageDto(base, msg) });
      socket.to('conv:' + conv.id).emit('message:new', { message: messageDto(base, msg) });
      if (conv.memberIds.some(id => id !== uid && online.has(id))) {
        msg.status = 'delivered';
        socket.emit('message:status', { conversationId: conv.id, messageId: msg.id, clientId: msg.clientId, status: 'delivered' });
      }
      botReply(conv, uid, msg, base);
    };
    content.includes('#slow') ? setTimeout(deliver, 8000) : deliver();
  });

  socket.on('message:seen', ({ conversationId, messageId } = {}) => {
    if (!member(conversationId)) return;
    db.messages.filter(m => m.conversationId === +conversationId && m.id <= +messageId && m.senderId !== uid).forEach(m => { m.status = 'seen'; });
    socket.to('conv:' + conversationId).emit('message:status', { conversationId: +conversationId, messageId: +messageId, status: 'seen', userId: uid });
  });

  socket.on('disconnect', reason => {
    setPresence(uid, -1);
    console.log(`[socket] user ${uid} disconnected: ${reason}`);
  });
});

server.listen(PORT, () => {
  const nets = Object.values(require('os').networkInterfaces()).flat().filter(n => n && n.family === 'IPv4' && !n.internal);
  console.log(`MomentBox mock server đang chạy:`);
  console.log(`  • Máy tính      : http://localhost:${PORT}`);
  console.log(`  • Android Emulator: http://10.0.2.2:${PORT}`);
  nets.forEach(n => console.log(`  • Máy thật (LAN): http://${n.address}:${PORT}`));
  console.log(`  Access token TTL = ${ACCESS_TTL}s`);
});
