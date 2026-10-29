// Build mockups.html from src/*.html and render PNGs with headless Chrome.
// Usage: node render.js [screenIds...|boards]   (no args = everything)
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const ROOT = __dirname;
const OUT = path.resolve(ROOT, '..', 'mockups');
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROFILE = process.env.CHROME_PROFILE || path.join(ROOT, '.chrome-profile');

// ---- build ----
const parts = fs.readdirSync(path.join(ROOT, 'src')).filter(f => f.endsWith('.html')).sort();
const html = parts.map(f => fs.readFileSync(path.join(ROOT, 'src', f), 'utf8')).join('\n');
const page = path.join(ROOT, 'mockups.html');
fs.writeFileSync(page, html);

const screens = [...html.matchAll(/<section class="screen([^"]*)" id="(S\w+)"[^>]*?data-t="([^"]+)"[^>]*?data-g="(\w+)"/g)]
  .map(m => ({ id: m[2], land: /\bland\b/.test(m[1]), title: m[3], group: m[4] }));

const slug = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D')
  .replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '');
const url = hash => 'file:///' + page.replace(/\\/g, '/') + '#' + hash;

const jobs = [];
function shot(hash, w, h, file, dsf) { jobs.push({ hash, w, h, file, dsf }); }
function run(job, slot) {
  return new Promise((res, rej) => execFile(CHROME, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${PROFILE}-${slot}`, `--force-device-scale-factor=${job.dsf}`, `--window-size=${job.w},${job.h}`,
    '--virtual-time-budget=2500', `--screenshot=${job.file}`, url(job.hash),
  ], err => { if (err) return rej(err); console.log('✓', path.relative(OUT, job.file)); res(); }));
}
async function runAll(n = 4) {
  let i = 0;
  await Promise.all(Array.from({ length: n }, async (_, slot) => { while (i < jobs.length) await run(jobs[i++], slot); }));
}

// board geometry: must match .board CSS (padding 64, header 150, cell 376, gap 44, label 34)
function boardSize(list, cols) {
  const rows = []; let cur = [], used = 0;
  for (const s of list) {
    const span = s.land ? 2 : 1;
    if (used + span > cols) { rows.push(cur); cur = []; used = 0; }
    cur.push(s); used += span;
  }
  if (cur.length) rows.push(cur);
  const rowH = r => 34 + (r.some(s => !s.land) ? 796 : 376);
  const h = 64 + 150 + rows.reduce((a, r) => a + rowH(r), 0) + 44 * (rows.length - 1) + 64;
  const w = 64 * 2 + cols * 376 + (cols - 1) * 44;
  return { w, h };
}

const args = process.argv.slice(2);
const wantBoards = !args.length || args.includes('boards');
const wantPairs = !args.length || args.includes('pairs');
const ids = args.filter(a => a !== 'boards' && a !== 'pairs');
const todo = ids.length ? screens.filter(s => ids.includes(s.id)) : (args.length ? [] : screens);

fs.mkdirSync(path.join(OUT, 'annotated'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'boards'), { recursive: true });
for (const s of todo) {
  const [w, h] = s.land ? [780, 360] : [360, 780];
  const name = `${s.id}_${slug(s.title)}.png`;
  shot(s.id, w, h, path.join(OUT, name), 2);
  shot(`${s.id},ann`, w, h, path.join(OUT, 'annotated', name), 2);
}
if (wantPairs) {
  fs.mkdirSync(path.join(OUT, 'pairs'), { recursive: true });
  for (const s of (ids.length ? todo : screens)) {
    const [w, h] = s.land ? [812, 824] : [776, 836];
    shot(`pair=${s.id}`, w, h, path.join(OUT, 'pairs', `${s.id}_${slug(s.title)}.png`), 2);
  }
}
if (wantBoards) {
  const groups = [...new Set(screens.map(s => s.group))];
  for (const g of groups) {
    const list = screens.filter(s => s.group === g);
    const cols = Math.min(4, list.reduce((a, s) => a + (s.land ? 2 : 1), 0));
    const { w, h } = boardSize(list, cols);
    shot(`board=${g},cols=${cols}`, w, h, path.join(OUT, 'boards', `Nhom-${g}.png`), 1);
  }
  const cols = 7, { w, h } = boardSize(screens, cols);
  shot(`board=all,cols=${cols}`, w, h, path.join(OUT, 'boards', '00_Tong-quan.png'), 1);
}
fs.writeFileSync(path.join(ROOT, 'screens.json'), JSON.stringify(screens, null, 1));
runAll().catch(e => { console.error(e.message); process.exit(1); });
