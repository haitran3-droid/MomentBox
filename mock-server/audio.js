// Generates the sample sounds served under /media/audio (no copyrighted assets needed).
// - voice-*.wav   : speech-like tones for VOICE posts / chat voice messages (MediaPlayer, ExoPlayer)
// - ambient-*.wav : loopable 16-bit PCM for AudioTrack MODE_STREAM (background sound in the journal editor)
// - sfx-*.wav     : short 16-bit PCM clips for AudioTrack MODE_STATIC (shutter, message sent, record start)
const fs = require('fs');
const path = require('path');

const RATE = 22050;

function wav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((s, i) => data.writeInt16LE(Math.max(-1, Math.min(1, s)) * 32767 | 0, i * 2));
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(RATE, 24); h.writeUInt32LE(RATE * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

function rng(seed) { let a = seed; return () => { a = (a * 1664525 + 1013904223) >>> 0; return a / 4294967296; }; }

function voice(seconds, seed) {
  const r = rng(seed), n = seconds * RATE, out = new Float32Array(n);
  let f = 180, target = 200, syll = 0;
  for (let i = 0; i < n; i++) {
    if (i % 2200 === 0) { target = 150 + r() * 120; syll = r() < 0.18 ? 0 : 1; }
    f += (target - f) * 0.0008;
    const t = i / RATE, env = syll * (0.55 + 0.45 * Math.sin(t * 2 * Math.PI * 3.2) ** 2);
    out[i] = env * 0.35 * (Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(4 * Math.PI * f * t) + 0.25 * Math.sin(6 * Math.PI * f * t));
  }
  return out;
}

function ambient(seconds, kind) {
  const r = rng(kind === 'rain' ? 7 : 11), n = seconds * RATE, out = new Float32Array(n);
  let b0 = 0, b1 = 0, b2 = 0;
  for (let i = 0; i < n; i++) {
    const w = r() * 2 - 1;
    b0 = 0.99765 * b0 + w * 0.099; b1 = 0.963 * b1 + w * 0.2965; b2 = 0.57 * b2 + w * 1.0526; // pink noise
    let s = (b0 + b1 + b2 + w * 0.1848) * 0.11;
    if (kind === 'ocean') s *= 0.35 + 0.65 * Math.sin(Math.PI * (i / RATE) / 4) ** 2;
    if (kind === 'rain' && r() < 0.0009) s += (r() - 0.5) * 0.8;
    out[i] = s;
  }
  const fade = RATE / 2; // crossfade tail into head so the loop is seamless
  for (let i = 0; i < fade; i++) { const k = i / fade; out[i] = out[i] * k + out[n - fade + i] * (1 - k); }
  return out.subarray(0, n - fade);
}

function sfx(kind) {
  const len = { shutter: 0.18, pop: 0.09, tick: 0.05 }[kind], n = Math.floor(len * RATE), out = new Float32Array(n), r = rng(3);
  for (let i = 0; i < n; i++) {
    const t = i / RATE, env = Math.exp(-t * (kind === 'shutter' ? 28 : 45));
    out[i] = kind === 'shutter' ? env * (r() * 2 - 1) * 0.8
      : kind === 'pop' ? env * Math.sin(2 * Math.PI * (500 + 1800 * t / len) * t) * 0.8
      : env * Math.sin(2 * Math.PI * 1500 * t) * 0.7;
  }
  return out;
}

function ensureAudio(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const files = {
    'voice-1.wav': () => voice(12, 1), 'voice-2.wav': () => voice(9, 2), 'voice-3.wav': () => voice(15, 3),
    'ambient-rain.wav': () => ambient(20, 'rain'), 'ambient-ocean.wav': () => ambient(20, 'ocean'),
    'sfx-shutter.wav': () => sfx('shutter'), 'sfx-pop.wav': () => sfx('pop'), 'sfx-tick.wav': () => sfx('tick'),
  };
  for (const [name, make] of Object.entries(files)) {
    const p = path.join(dir, name);
    if (!fs.existsSync(p)) fs.writeFileSync(p, wav(make()));
  }
  return Object.keys(files);
}

module.exports = { ensureAudio, RATE };
