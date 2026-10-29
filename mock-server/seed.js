// Deterministic demo data. Everything lives in memory and resets when the server restarts.

const HOUR = 3600 * 1000;
const img = (seed, w = 1080, h = 1350) => `https://picsum.photos/seed/momentbox-${seed}/${w}/${h}`;
const avatar = (name, bg) =>
  `https://ui-avatars.com/api/?name=${encodeURIComponent(name)}&size=256&bold=true&color=fff&background=${bg}`;

const VIDEOS = [
  { url: 'https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4', durationMs: 596000 },
  { url: 'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4', durationMs: 10000 },
  { url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8', durationMs: 634000 }, // HLS
];

const USERS = [
  { id: 1, username: 'minhanh', displayName: 'Nguyễn Minh Anh', email: 'minhanh@momentbox.dev', bg: '5B4BDB', bio: 'Thích đi đây đi đó và lưu lại mọi thứ 📸', location: 'Hà Nội', verified: true },
  { id: 2, username: 'lananh', displayName: 'Lan Anh', email: 'lananh@momentbox.dev', bg: 'FF6A5B', bio: 'Cà phê, sách và những buổi sáng sớm ☕', location: 'Đà Lạt', bot: true },
  { id: 3, username: 'minhquan', displayName: 'Minh Quân', email: 'quan@momentbox.dev', bg: '2BA59B', bio: 'Podcast nhỏ mỗi tối 🎧', location: 'TP.HCM', bot: true },
  { id: 4, username: 'travelvn', displayName: 'Travel VN', email: 'travel@momentbox.dev', bg: '3D8BFD', bio: 'Khám phá Việt Nam mỗi ngày 🇻🇳', location: 'Đà Nẵng', verified: true },
  { id: 5, username: 'hatrang', displayName: 'Hà Trang', email: 'trang@momentbox.dev', bg: 'E05592', bio: 'Designer · mèo · trà sữa', location: 'Hà Nội' },
  { id: 6, username: 'duckhang', displayName: 'Đức Khang', email: 'khang@momentbox.dev', bg: '4FA857', bio: 'Chạy bộ 5km mỗi sáng 🏃', location: 'Huế' },
  { id: 7, username: 'phuonglinh', displayName: 'Phương Linh', email: 'linh@momentbox.dev', bg: '7E57C2', bio: 'Nấu ăn là liều thuốc tinh thần 🍜', location: 'Cần Thơ' },
];
const PASSWORD = '123456';

const CAPTIONS = [
  ['Sáng sớm ở đồi chè Cầu Đất, sương phủ kín cả thung lũng ☁️🌿', ['dalat', 'travel'], 'Đà Lạt'],
  ['Cà phê phin và một cuốn sách hay, cuối tuần chỉ cần vậy thôi ☕📖', ['coffee', 'weekend'], 'Hà Nội'],
  ['Hoàng hôn ở biển Mỹ Khê đẹp không thể tả 🌅', ['beach', 'travel'], 'Đà Nẵng'],
  ['Lần đầu tự nấu phở bò, không tệ lắm đâu nha 🍜', ['food', 'homemade'], 'Cần Thơ'],
  ['Một chút tâm sự tối nay 🎧', ['podcast', 'night'], null],
  ['Chạy 10km đầu tiên trong đời! 🏃‍♂️🔥', ['running', 'goal'], 'Huế'],
  ['Hà Nội vào thu, gió heo may và hoa sữa 🍂', ['hanoi', 'autumn'], 'Hà Nội'],
  ['Hôm nay học được cách dùng Coroutines với Retrofit, vui ghê 🤓', ['android', 'kotlin'], null],
  ['Ruộng bậc thang mùa lúa chín vàng óng ả 🌾', ['mucangchai', 'travel'], 'Mù Cang Chải'],
  ['Cả nhà cùng nhau đi picnic cuối tuần 🧺', ['family', 'weekend'], 'Ba Vì'],
];
const TYPES = ['PHOTO', 'PHOTO', 'VIDEO', 'TEXT', 'VOICE', 'PHOTO', 'PHOTO', 'TEXT', 'VIDEO', 'PHOTO'];
const COMMENTS = [
  'Đẹp quá! Cho mình xin địa chỉ với 😍', 'Hẹn tháng sau đi cùng nha!', 'Ảnh chụp bằng máy gì vậy bạn?',
  'Nhìn thèm quá 🤤', 'Chúc mừng nhé, giỏi quá! 👏', 'Mình cũng muốn thử một lần', 'Tuyệt vời ông mặt trời ☀️',
  'Lưu lại để đi dịp Tết', 'Haha dễ thương ghê', 'Cảm ơn đã chia sẻ ❤️',
];

function create() {
  const now = Date.now();
  const users = USERS.map(u => ({ ...u, password: PASSWORD, avatar: avatar(u.displayName, u.bg) }));

  const posts = [];
  for (let i = 0; i < 48; i++) {
    const [caption, tags, location] = CAPTIONS[i % CAPTIONS.length];
    const type = TYPES[i % TYPES.length];
    const authorId = users[(i * 3) % users.length].id;
    const media = [];
    if (type === 'PHOTO') {
      const count = 1 + (i % 3);
      for (let k = 0; k < count; k++) media.push({ type: 'image', url: img(`${i}-${k}`), width: 1080, height: 1350 });
    } else if (type === 'VIDEO') {
      const v = VIDEOS[i % VIDEOS.length];
      media.push({ type: 'video', url: v.url, thumbnailUrl: img(`v${i}`, 1280, 720), width: 1280, height: 720, durationMs: v.durationMs });
    } else if (type === 'VOICE') {
      media.push({ type: 'audio', url: `/media/audio/voice-${1 + (i % 3)}.wav`, durationMs: [12000, 9000, 15000][i % 3] });
    }
    posts.push({
      id: i + 1, authorId, type, caption, tags, location, media,
      privacy: i % 11 === 5 ? 'friends' : 'public', allowComments: true,
      createdAt: new Date(now - (i * 5 + 2) * HOUR).toISOString(),
    });
  }

  const comments = [];
  posts.forEach(p => {
    const n = p.id % 7;
    for (let k = 0; k < n; k++) {
      comments.push({
        id: comments.length + 1, postId: p.id, parentId: null,
        authorId: users[(p.id + k + 1) % users.length].id,
        content: COMMENTS[(p.id + k) % COMMENTS.length],
        createdAt: new Date(Date.parse(p.createdAt) + (k + 1) * 0.3 * HOUR).toISOString(),
      });
    }
  });

  const likes = new Set();      // `${userId}:${postId}`
  const bookmarks = new Set();  // `${userId}:${postId}`
  const commentLikes = new Set();
  posts.forEach(p => users.forEach(u => { if ((p.id * 7 + u.id * 3) % 4 === 0) likes.add(`${u.id}:${p.id}`); }));
  [1, 3, 9].forEach(pid => bookmarks.add(`1:${pid}`));

  const follows = new Set(['1:2', '1:3', '1:4', '2:1', '3:1', '5:1', '6:1', '4:2', '5:2']); // follower:followee

  const conversations = [
    { id: 1, type: 'direct', memberIds: [1, 2], title: null },
    { id: 2, type: 'direct', memberIds: [1, 3], title: null },
    { id: 3, type: 'group', memberIds: [1, 2, 3, 5], title: 'Nhóm Đà Lạt 2026 🌲' },
    { id: 4, type: 'direct', memberIds: [1, 5], title: null },
  ];
  const messages = [];
  const addMsg = (conversationId, senderId, content, minutesAgo, extra = {}) => messages.push({
    id: messages.length + 1, clientId: null, conversationId, senderId, type: 'text', content,
    mediaUrl: null, durationMs: null, status: 'seen',
    createdAt: new Date(now - minutesAgo * 60000).toISOString(), ...extra,
  });
  addMsg(1, 2, 'Hôm qua đi Đà Lạt vui không?', 60 * 26);
  addMsg(1, 1, 'Vui lắm! Lạnh nhưng đẹp cực 😍', 60 * 25);
  addMsg(1, 2, 'Tối nay đi cafe không? ☕', 61, { status: 'delivered' });
  addMsg(2, 3, 'Ông nghe podcast mới của tôi chưa?', 60 * 3);
  addMsg(2, 1, 'Chưa, tối nay nghe nhé', 60 * 2);
  addMsg(3, 5, 'Mọi người gửi ảnh vào nhóm nhé', 60 * 4);
  addMsg(3, 5, '', 60 * 4 - 1, { type: 'image', mediaUrl: img('group-1', 1080, 1080) });
  addMsg(4, 5, '', 60 * 20, { type: 'voice', mediaUrl: '/media/audio/voice-2.wav', durationMs: 9000 });

  const stories = users.filter(u => u.id !== 1).slice(0, 5).map((u, i) => ({
    userId: u.id, hasUnseen: i < 3,
    items: [{ type: 'image', url: img(`story-${u.id}`, 1080, 1920), createdAt: new Date(now - (i + 1) * HOUR).toISOString() }],
  }));

  return { users, posts, comments, likes, bookmarks, commentLikes, follows, conversations, messages, stories,
    accessTokens: new Map(), refreshTokens: new Map(), journalBackups: new Map() };
}

module.exports = { create, PASSWORD };
