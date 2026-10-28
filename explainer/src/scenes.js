// gekko explainer, 60 s. Seven beats; every beat is a pure function of the global time t.
import { D, T, C, E, seg, clamp01, lerp, rbox, circ, underline, cross, tick, measure, push, pop, translate, rotate, scale, setExit, rng } from './engine.js';
import { evalTrack, drawGekko, miniGekko } from './gekko.js';

export const meta = { w: 1920, h: 1080, fps: 30, duration: 60 };

// ------------------------------------------------------------------ timeline
const B = { intro: [0, 4.6], pain: [4.6, 13], typing: [13, 21], plat: [21, 31], dev: [31, 38], heal: [38, 51], end: [51, 60] };
const EXIT = 0.5;

// ------------------------------------------------------------------ sound cues
export const cues = [];
const cue = (t, name, o) => cues.push([t, name, o]);

// ------------------------------------------------------------------ helpers
const HY = 150;
const HS = 84;
function headline(parts, t, t0, dur, y = HY) {
  const total = parts.reduce((s, p) => s + measure(p[0], HS), 0);
  let x = 960 - total / 2;
  let acc = 0;
  const prog = clamp01((t - t0) / dur);
  for (const [txt, col, start] of parts) {
    const w = measure(txt, HS);
    const own = start !== undefined ? clamp01((t - start) / 0.9) : clamp01((prog * total - acc) / w);
    T(txt, x, y, { size: HS, color: col || C.ink, p: own });
    x += w;
    acc += w;
  }
}

const ground = (x0, x1, y, p, seed = 1) => D(`M${x0},${y} C${x0 + (x1 - x0) * 0.3},${y - 3 + seed} ${x0 + (x1 - x0) * 0.7},${y + 3 - seed} ${x1},${y - 1}`, 'ink', p);

function sheet(x, y, w, h, seed, p, lines = 2) {
  D(rbox(x, y, w, h, 5, seed), 'paper', p);
  const R = rng(seed + 99);
  for (let i = 0; i < lines; i++) {
    const yy = y + 20 + i * 18;
    if (yy < y + h - 8) D(`M${x + 16},${yy} L${x + w * (0.45 + R() * 0.35)},${yy + (R() - 0.5) * 3}`, 'ink', p, { w: 2.6 });
  }
}

function phone(cx, top, w, h, seed, p, ios = false) {
  const x = cx - w / 2;
  D(rbox(x, top, w, h, 34, seed), 'paper', p);
  D(rbox(x + 16, top + 40, w - 32, h - 80, 12, seed + 1), 'paper', clamp01(p * 1.2 - 0.2));
  if (ios) D(rbox(cx - 34, top + 14, 68, 18, 9, seed + 2, 1), 'solid', p);
  else D(circ(cx, top + 24, 6, seed + 2, 0.5), 'solid', p);
  D(`M${cx - 36},${top + h - 20} L${cx + 36},${top + h - 20}`, 'ink', p, { w: 3 });
}

function spark(x, y, ang, p) {
  if (p <= 0 || p >= 1) return;
  const len = 26 + 14 * p;
  for (const da of [-38, 0, 38]) {
    const a = ((ang + da) * Math.PI) / 180;
    D(`M${x + Math.cos(a) * 24},${y + Math.sin(a) * 24} L${x + Math.cos(a) * len},${y + Math.sin(a) * len}`, 'flow', 1, { a: 0, w: 4, alpha: 1 - p });
  }
}

function clockFace(cx, cy, r, t, spin, p, col = 'warn') {
  D(circ(cx, cy, r, 4), col, p);
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2;
    D(`M${cx + Math.sin(a) * (r - 4)},${cy - Math.cos(a) * (r - 4)} L${cx + Math.sin(a) * (r - 13)},${cy - Math.cos(a) * (r - 13)}`, col, p, { w: 3 });
  }
  const a = t * spin;
  D(`M${cx},${cy} L${cx + Math.sin(a) * (r - 18)},${cy - Math.cos(a) * (r - 18)}`, col, p, { w: 5 });
  D(`M${cx},${cy} L${cx + Math.sin(a / 12) * (r - 34)},${cy - Math.cos(a / 12) * (r - 34)}`, col, p, { w: 5 });
}

const ex = (t, end) => seg(t, end - EXIT, end, E.lin);

// ------------------------------------------------------------------ gekko track
const K = (t, x, y, s, pose = 'stand', o = {}) => ({ t, x, y, s, pose, ...o });
const lids = (a, b = a, extra = {}) => ({ base: 'stand', lidL: a, lidR: b, ...extra });

// typing: gekko's right hand hits keys
const LINES = [
  { text: 'say: Open the search', t0: 15.2, cps: 18, y: 324 },
  { text: 'do: tap Search', t0: 16.5, cps: 18, y: 388 },
  { text: 'until: results visible', t0: 17.5, cps: 18, y: 452 },
];
for (const L of LINES) {
  L.t1 = L.t0 + L.text.length / L.cps;
  for (let i = 0; i < L.text.length; i++) cue(L.t0 + i / L.cps, L.text[i] === ' ' ? 'tap' : 'type', { n: i + Math.round(L.t0 * 7), vol: L.text[i] === ' ' ? 0.35 : 1 });
  cue(L.t1 + 0.05, 'bell');
}
const typingActive = (t) => LINES.some((L) => t >= L.t0 && t <= L.t1 + 0.1);
const keyTarget = (t) => {
  const idx = Math.floor(t * 9);
  const R = rng(idx * 17 + 3);
  const row = R() < 0.55 ? 0 : 1;
  const kx = 700 + Math.floor(R() * 11) * 58 + (row ? 29 : 0);
  const phase = (t * 9) % 1;
  const press = phase < 0.45 ? 6 : 0;
  return [kx, (row ? 645 : 585) - 6 + press];
};
const typingPose = (t) => ({ base: 'stand', tR: typingActive(t) ? keyTarget(t) : [640, 640] });

const waveArm = (t) => {
  const dx = Math.sin(t * 8) * 20;
  return [73, -105, 118, -128, 132 + dx * 0.6, -178, 112 + dx, -214];
};
const wavePose = (t) => ({ base: 'wave', armR: waveArm(t) });

const TRACK = [
  K(0, 640, 860, 2.2, 'stand'),
  K(0.4, 640, 860, 2.2, lids(-55)),
  K(0.7, 640, 860, 2.2, lids(-175)),
  K(1.0, 640, 860, 2.2, lids(-125)),
  K(1.7, 640, 860, 2.2, lids(-125, -125, { eyeX: 7 })),
  K(2.3, 640, 860, 2.2, lids(-125, -125, { eyeX: 0 })),
  K(2.5, 640, 860, 2.2, 'closed'),
  K(2.7, 640, 805, 2.2, lids(-150)),
  K(2.95, 640, 860, 2.2, 'stand'),
  K(3.9, 640, 860, 2.2, 'stand', { walk: true }),
  K(5.2, 960, 900, 1.5, 'stand'),
  // pain
  K(6.0, 960, 900, 1.5, lids(-125, -125, { eyeX: -8 })),
  K(7.4, 960, 900, 1.5, lids(-125, -125, { eyeX: 8 })),
  K(8.8, 960, 900, 1.5, 'shrug'),
  K(10.4, 960, 900, 1.5, 'stuck'),
  K(12.4, 960, 900, 1.5, 'stuck'),
  // typing: walk to the typewriter
  K(13.0, 960, 900, 1.5, 'stand', { walk: true }),
  K(14.3, 420, 800, 1.5, 'stand'),
  K(15.0, 420, 800, 1.5, typingPose),
  K(18.8, 420, 800, 1.5, typingPose),
  K(19.2, 420, 800, 1.5, lids(-150)),
  K(20.6, 420, 800, 1.5, 'stand', { walk: true }),
  // platforms
  K(21.9, 960, 900, 1.5, 'stand'),
  K(24.4, 960, 900, 1.5, 'stand'),
  K(24.8, 960, 900, 1.5, { base: 'stand', tL: [520, 702], tR: [1420, 702] }),
  K(30.0, 960, 900, 1.5, { base: 'stand', tL: [520, 702], tR: [1420, 702] }),
  K(30.5, 960, 900, 1.5, 'stand', { walk: true }),
  // devices
  K(31.5, 330, 880, 1.5, 'carry'),
  K(37.4, 330, 880, 1.5, 'carry'),
  K(37.8, 330, 880, 1.5, 'stand', { walk: true }),
  // heal
  K(38.8, 380, 880, 1.5, 'stand'),
  K(39.6, 380, 880, 1.5, { base: 'stand', tR: [790, 540] }),
  K(41.6, 380, 880, 1.5, { base: 'stand', tR: [790, 540] }),
  K(42.0, 380, 880, 1.5, 'shrug'),
  K(45.3, 380, 880, 1.5, 'shrug'),
  K(45.7, 380, 880, 1.5, { base: 'stand', tR: [802, 505] }),
  K(46.0, 380, 880, 1.5, { base: 'stand', tR: [836, 515] }),
  K(46.3, 380, 880, 1.5, { base: 'stand', tR: [802, 505] }),
  K(47.0, 380, 880, 1.5, { base: 'stand', lidL: -140, lidR: -110 }),
  K(50.4, 380, 880, 1.5, 'stand'),
  K(50.6, 380, 880, 1.5, 'stand', { walk: true }),
  // end card
  K(51.9, 520, 900, 1.9, 'stand'),
  K(52.6, 520, 900, 1.9, wavePose),
  K(58.0, 520, 900, 1.9, wavePose),
  K(58.6, 520, 900, 1.9, 'stand'),
  K(60, 520, 900, 1.9, 'stand'),
];

// walking and landing sounds
cue(0.4, 'pop');
cue(0.7, 'pop', { f: 300 });
cue(2.75, 'thud');
for (const [a, b] of [[3.9, 5.2], [13, 14.3], [20.6, 21.9], [30.5, 31.5], [37.8, 38.8], [50.6, 51.9]]) {
  for (let t = a + 0.2; t < b; t += 0.29) cue(t, 'thud', { vol: 0.25 });
}

// ------------------------------------------------------------------ scenes
function intro(t) {
  setExit(ex(t, B.intro[1]));
  ground(260, 1700, 860, 1, 1);
  T('gekko', 1010, 630, { size: 210 });
  D(underline(1015, 1440, 682, 2), 'flow', 1, { w: 6 });
  T('super fast cross-platform UI tests', 1015, 752, { size: 54, color: C.blue });
  T('with LLM healing', 1015, 822, { size: 54, color: C.blue });
}

const PAIN = { L: 400, R: 1520, G: 900, W: 270, H: 40, STEP: 44, N: 7 };
function pain(t) {
  setExit(ex(t, B.pain[1]));
  T('manual testing', 960, 100, { size: 56, anchor: 'mid', p: seg(t, 4.8, 5.4, E.lin) });
  headline([['slow, ', C.ink], ['flaky', C.red], [', twice', C.ink]], t, 5.3, 1.1, 195);
  ground(120, 1800, PAIN.G, seg(t, 5.0, 5.8), 2);
  const stack = (cx, t0, seed, xs, label, lt) => {
    for (let i = 0; i < PAIN.N; i++) {
      const p = seg(t, t0 + i * 0.2, t0 + i * 0.2 + 0.28);
      const jx = ((seed * 13 + i * 7) % 11) - 5;
      sheet(cx - PAIN.W / 2 + jx, PAIN.G - (i + 1) * PAIN.STEP + 2, PAIN.W, PAIN.H, seed * 10 + i, p, 1);
      if (xs.includes(i)) {
        const s0 = 9.0 + xs.indexOf(i) * 0.3 + (cx > 960 ? 0.15 : 0);
        D(cross(cx + 80 + jx, PAIN.G - (i + 1) * PAIN.STEP + 22, 13), 'warn', seg(t, s0, s0 + 0.2, E.out));
      }
    }
    T(label, cx, PAIN.G + 62, { anchor: 'mid', p: seg(t, lt, lt + 0.5, E.lin) });
  };
  stack(PAIN.L, 5.8, 1, [2, 5], 'Android', 7.2);
  stack(PAIN.R, 7.2, 2, [1, 4, 6], 'iOS', 8.6);
  // the slow part: a sleep that never ends
  clockFace(960, 430, 72, Math.max(0, t - 9.8), 5.2, seg(t, 9.6, 10.3));
  T('slow and tedious', 1068, 448, { size: 52, color: C.red, p: seg(t, 10.0, 10.9, E.lin) });
  D(underline(1068, 1068 + measure('slow and tedious', 52), 462, 5), 'warn', seg(t, 10.9, 11.3), { w: 3.5 });
}
cue(4.8, 'pen', { dur: 0.6 });
cue(5.3, 'pen', { dur: 1.0 });
for (let i = 0; i < PAIN.N; i++) {
  cue(5.8 + i * 0.2 + 0.2, 'thud', { vol: 0.55, pan: -0.3 });
  cue(7.2 + i * 0.2 + 0.2, 'thud', { vol: 0.55, pan: 0.3 });
}
[[9.0, -0.3], [9.3, -0.3], [9.15, 0.3], [9.45, 0.3], [9.75, 0.3]].forEach(([t, p]) => cue(t, 'xmark', { pan: p }));
cue(9.1, 'buzz', { dur: 0.3 });
cue(9.6, 'pop', { f: 300 });
for (let t = 9.85; t < 12.2; t += 0.3) cue(t, 'tick', { f: 1700 });

// ---- typing
function typing(t) {
  setExit(ex(t, B.typing[1]));
  headline([['write it once, ', C.ink], ['easy syntax', C.blue]], t, 13.3, 1.7);
  ground(130, 1620, 800, seg(t, 13.6, 14.3), 1);
  // paper first, so the roller overlaps it
  D(rbox(720, 250, 700, 250, 4, 5), 'paper', seg(t, 14.3, 14.9));
  for (const L of LINES) {
    if (t < L.t0) continue;
    const n = Math.min(L.text.length, Math.floor((t - L.t0) * L.cps) + 1);
    T(L.text.slice(0, n), 760, L.y, { size: 50 });
  }
  const tw = seg(t, 13.7, 14.6);
  D(rbox(670, 470, 800, 56, 26, 6), 'paper', tw);
  D(circ(655, 498, 20, 7), 'paper', tw);
  D(circ(1485, 498, 20, 8), 'paper', tw);
  D(rbox(640, 520, 860, 272, 36, 9), 'paper', tw);
  for (let i = 0; i < 13; i++) D(circ(700 + i * 58, 585, 18, 20 + i, 0.8), 'ink', seg(t, 14.2 + i * 0.03, 14.7 + i * 0.03), { w: 3 });
  for (let i = 0; i < 12; i++) D(circ(729 + i * 58, 645, 18, 40 + i, 0.8), 'ink', seg(t, 14.3 + i * 0.03, 14.8 + i * 0.03), { w: 3 });
  D(rbox(830, 705, 520, 40, 14, 11), 'ink', seg(t, 14.5, 15.0));
  // blue annotations
  [['what?', 324, 16.2], ['how?', 388, 17.35], ['done?', 452, 18.85]].forEach(([s, y, t0]) => {
    D(`M1580,${y - 12} C1530,${y - 22} 1480,${y - 14} 1440,${y - 12}`, 'sys', seg(t, t0, t0 + 0.35), { arrow: 'b', w: 3.5 });
    T(s, 1600, y, { size: 52, color: C.blue, p: seg(t, t0 + 0.25, t0 + 0.65, E.lin) });
  });
}
cue(13.3, 'pen', { dur: 1.7 });
cue(14.3, 'slide', { dur: 0.5 });
cue(13.8, 'thud', { vol: 0.6 });
[16.2, 17.35, 18.85].forEach((t, i) => cue(t, 'pop', { f: 500 + i * 60, vol: 0.7 }));

// ---- two platforms
function plat(t) {
  setExit(ex(t, B.plat[1]));
  headline([['one test, two platforms', C.ink]], t, 21.2, 1.4);
  ground(150, 1780, 900, seg(t, 21.4, 22.2), 1);
  phone(450, 400, 230, 500, 3, seg(t, 21.8, 22.8));
  phone(1470, 400, 230, 500, 4, seg(t, 22.4, 23.4), true);
  T('Android', 450, 960, { anchor: 'mid', p: seg(t, 22.7, 23.2, E.lin) });
  T('iOS', 1470, 960, { anchor: 'mid', p: seg(t, 23.3, 23.7, E.lin) });
  // app content
  const content = (cx, k, base) => {
    D(rbox(cx - 85, 458, 170, 44, 10, k + 20), 'ink', seg(t, base, base + 0.4), { w: 3 });
    for (let i = 0; i < 3; i++) D(rbox(cx - 85, 525 + i * 75, 170, 54, 10, k + 30 + i), 'ink', seg(t, base + 0.15 + i * 0.12, base + 0.55 + i * 0.12), { w: 3 });
  };
  content(450, 100, 23.0);
  content(1470, 200, 23.5);
  // the sheet that goes to both
  sheet(830, 230, 260, 160, 8, seg(t, 23.4, 24.2), 3);
  T('say / do / until', 960, 345, { size: 34, anchor: 'mid', p: seg(t, 24.0, 24.5, E.lin) });
  D('M840,300 C760,255 620,270 540,375', 'flow', seg(t, 24.2, 24.9), { arrow: 'o' });
  D('M1080,300 C1160,255 1300,270 1380,375', 'flow', seg(t, 24.4, 25.1), { arrow: 'o' });
  // tiny agents inside the apps
  const mp = seg(t, 25.4, 25.9, E.out);
  if (mp > 0) {
    miniGekko('stand', 450, 846, 0.42, { alpha: mp });
    miniGekko('stand', 1470, 846, 0.42, { alpha: mp });
  }
  D('M592,846 C570,850 548,846 526,838', 'note', seg(t, 26.5, 27.0), { arrow: 'b' });
  T('agent in the app', 586, 878, { size: 38, color: C.blue, p: seg(t, 26.1, 26.9, E.lin) });
  // taps and ticks
  [[25.0, 'L'], [25.0, 'R'], [26.0, 'L'], [26.3, 'R'], [27.0, 'L'], [27.2, 'R']].forEach(([t0, side]) => {
    spark(side === 'L' ? 546 : 1394, 692, side === 'L' ? 200 : -20, clamp01((t - t0 - 0.28) / 0.35));
  });
  [[25.5, 25.7], [26.4, 26.6], [27.3, 27.5]].forEach(([tl, tr], i) => {
    D(tick(450 + 62, 552 + i * 75, 0.95), 'sys', seg(t, tl, tl + 0.2, E.out));
    D(tick(1470 + 62, 552 + i * 75, 0.95), 'sys', seg(t, tr, tr + 0.2, E.out));
  });
  // the stopwatch
  const sw = seg(t, 27.8, 28.5);
  D(circ(960, 500, 48, 12), 'warn', sw);
  D(rbox(950, 440, 20, 12, 3, 13), 'warn', sw, { w: 3.5 });
  const handA = clamp01((t - 28.2) / 0.5) * 6.28;
  D(`M960,500 L${960 + Math.sin(handA) * 32},${500 - Math.cos(handA) * 32}`, 'warn', sw, { w: 4.5 });
  T('super fast', 1030, 520, { size: 68, color: C.red, p: seg(t, 28.6, 29.2, E.lin) });
  D(underline(1030, 1030 + measure('super fast', 68), 538, 6), 'warn', seg(t, 29.2, 29.6), { w: 3.5 });
}
cue(21.2, 'pen', { dur: 1.3 });
cue(21.85, 'pop', { f: 330, pan: -0.4 });
cue(22.45, 'pop', { f: 330, pan: 0.4 });
cue(24.2, 'whoosh', { dur: 0.5, vol: 0.7 });
cue(25.45, 'pop', { f: 450, pan: -0.4 });
cue(25.55, 'pop', { f: 450, pan: 0.4 });
[[25.28, -0.5], [25.28, 0.5], [26.28, -0.5], [26.58, 0.5], [27.28, -0.5], [27.48, 0.5]].forEach(([t, p]) => cue(t, 'tap', { pan: p, vol: 0.7 }));
[[25.5, -0.4, 0], [25.7, 0.4, 0], [26.4, -0.4, 4], [26.6, 0.4, 4], [27.3, -0.4, 7], [27.5, 0.4, 7]].forEach(([t, p, n]) => cue(t, 'check', { pan: p, n, vol: 0.8 }));
cue(27.9, 'plug');
for (let t = 28.2; t < 28.7; t += 0.07) cue(t, 'tick', { f: 2300, vol: 0.5 });
cue(28.7, 'pop', { f: 500 });

// ---- all devices
const DEV_X = [760, 980, 1200, 1420, 1640];
function dev(t) {
  setExit(ex(t, B.dev[1]));
  headline([['every device at once', C.ink]], t, 31.3, 1.4);
  ground(150, 1780, 880, seg(t, 31.5, 32.2), 1);
  DEV_X.forEach((x, i) => phone(x, 580, 170, 300, 20 + i, seg(t, 32.0 + i * 0.25, 32.6 + i * 0.25), i % 2 === 1));
  // stack of test sheets on gekko's hands
  for (let i = 0; i < 3; i++) sheet(165 + ((i * 5) % 7), 568 - (i + 1) * 36, 330, 34, 50 + i, seg(t, 32.4 + i * 0.15, 32.7 + i * 0.15), 1);
  DEV_X.forEach((x, i) => {
    const sx = 390 + i * 26;
    D(`M${sx},462 C${sx + (x - sx) * 0.12},${230 + i * 28} ${x - 70},${300 + i * 14} ${x},560`, 'flow', seg(t, 33.0 + i * 0.22, 33.8 + i * 0.22), { arrow: 'o' });
  });
  DEV_X.forEach((x, i) => {
    D(rbox(x - 55, 650, 110, 30, 8, 60 + i), 'ink', seg(t, 33.9, 34.4), { w: 3 });
    D(tick(x, 740, 1.7), 'sys', seg(t, 34.7 + i * 0.22, 34.95 + i * 0.22, E.out), { w: 6 });
  });
  T('--devices all', 1200, 960, { anchor: 'mid', size: 46, color: C.blue, p: seg(t, 35.9, 36.6, E.lin) });
}
cue(31.3, 'pen', { dur: 1.4 });
DEV_X.forEach((x, i) => cue(32.1 + i * 0.25, 'pop', { f: 300 + i * 40, pan: (i - 2) * 0.35, vol: 0.8 }));
cue(33.0, 'whoosh', { dur: 0.8, vol: 0.6 });
DEV_X.forEach((x, i) => cue(34.75 + i * 0.22, 'check', { n: i * 2, pan: (i - 2) * 0.35 }));

// ---- heal
function heal(t) {
  setExit(ex(t, B.heal[1]));
  headline([['UI changed? ', C.ink, 38.3], ['still green.', C.blue, 46.5]], t, 38.3, 1);
  ground(130, 1780, 880, seg(t, 38.4, 39.1), 1);
  // fix PR sheet slides out from behind the app screen
  const sl = seg(t, 47.4, 48.4, E.out);
  if (sl > 0) {
    push();
    translate(lerp(-120, 0, sl), 0);
    D(rbox(1340, 620, 340, 230, 8, 31), 'paper', 1);
    D('M1340,684 L1680,684', 'ink', 1);
    T('fix PR', 1366, 667, { size: 42 });
    for (let i = 0; i < 3; i++) D(`M1366,${718 + i * 28} L${1520 + (i % 2) * 70},${718 + i * 28}`, 'ink', 1, { w: 2.6 });
    D(tick(1620, 795, 1.5), 'sys', seg(t, 48.7, 49.0, E.out), { w: 6 });
    pop();
  }
  // app screen
  const sc = seg(t, 38.7, 39.7);
  D(rbox(780, 250, 440, 630, 28, 33), 'paper', sc);
  D('M810,290 L960,290', 'ink', sc, { w: 3 });
  const el = seg(t, 39.5, 40.2);
  D(rbox(815, 450, 370, 96, 16, 34), 'paper', el);
  D('M850,488 L1010,488 M850,516 L960,516', 'ink', el, { w: 3 });
  D('M815,600 L1120,600 M815,640 L1050,640 M815,680 L1160,680', 'ink', el, { w: 3 });
  // the missing element
  D(rbox(815, 310, 370, 96, 16, 35), 'warn', seg(t, 39.9, 40.4), { dash: [14, 11], w: 3.5 });
  const fixed = seg(t, 46.0, 46.25, E.out);
  D(cross(1000, 358, 24), 'warn', seg(t, 40.6, 40.9, E.out) * (1 - fixed), { w: 5 });
  // step fails
  T('step fails', 330, 340, { size: 58, color: C.red, p: seg(t, 40.8, 41.5, E.lin) });
  D('M545,324 C620,302 710,318 792,352', 'warn', seg(t, 41.4, 41.9), { arrow: 'r' });
  // the model
  D('M1380,405 C1330,400 1325,440 1365,448 C1360,480 1430,490 1460,466 C1500,490 1580,480 1580,440 C1630,436 1640,386 1590,372 C1590,318 1510,310 1488,346 C1460,310 1390,330 1388,372 C1360,372 1356,400 1380,405 Z', 'paper', seg(t, 43.0, 43.9), { stroke: C.blue, w: 4 });
  T('model', 1490, 425, { size: 56, color: C.blue, anchor: 'mid', p: seg(t, 43.8, 44.3, E.lin) });
  D('M1350,450 C1300,470 1250,490 1200,500', 'note', seg(t, 44.4, 45.2), { arrow: 'b' });
  T('heals', 1290, 548, { size: 48, color: C.blue, anchor: 'mid', p: seg(t, 44.9, 45.5, E.lin) });
  // existing element, picked by the model
  D(rbox(808, 444, 384, 108, 18, 36), 'sys', seg(t, 45.0, 45.4), { w: 3, dash: [10, 8] });
  // bandage thwip on the missing element
  const bp = seg(t, 45.2, 45.45, E.out);
  if (bp > 0) {
    push();
    translate(1000, 358);
    rotate(-14);
    scale(0.8 + 0.2 * bp);
    D(rbox(-110, -30, 220, 60, 28, 70, 1.5), 'paper', 1, { w: 4 });
    D(rbox(-36, -22, 72, 44, 8, 71, 1), 'ink', 1, { w: 3 });
    for (const dx of [-84, -66, 66, 84]) for (const dy of [-10, 10]) D(circ(dx, dy, 2.2, 3, 0.2), 'solid', 1);
    pop();
  }
  D(tick(1000, 358, 2.1), 'sys', seg(t, 46.0, 46.3, E.out), { w: 7 });
  // gekko taps the existing element: spark
  spark(790, 500, 200, clamp01((t - 45.95) / 0.35));
}
cue(38.3, 'pen', { dur: 1.0 });
cue(40.55, 'xmark');
cue(40.6, 'buzz', { dur: 0.3 });
cue(40.8, 'pen', { dur: 0.6, vol: 0.8 });
cue(43.0, 'pop', { f: 360 });
cue(44.4, 'whoosh', { dur: 0.6, vol: 0.5 });
cue(44.9, 'pen', { dur: 0.6, vol: 0.5 });
cue(45.2, 'thwip');
cue(45.95, 'tap');
cue(46.05, 'check', { n: 4 });
cue(46.5, 'pen', { dur: 0.9, vol: 0.9 });
cue(47.4, 'slide', { dur: 0.6 });
cue(48.7, 'check', { n: 7, vol: 0.9 });
cue(48.85, 'chime', { vol: 0.9 });

// ---- end card
const PROMPT = 'ask your AI: set up gekko';
function end(t) {
  ground(150, 1780, 900, seg(t, 51.2, 52.0), 1);
  T('UI tests,', 1240, 390, { size: 112, anchor: 'mid', p: seg(t, 51.9, 52.6, E.lin) });
  T('written once,', 1240, 510, { size: 112, anchor: 'mid', p: seg(t, 52.6, 53.4, E.lin) });
  T('with self-healing.', 1240, 630, { size: 112, anchor: 'mid', p: seg(t, 53.4, 54.4, E.lin) });
  D(underline(886, 1594, 672, 3), 'flow', seg(t, 54.4, 54.8), { w: 6 });
  D(rbox(900, 740, 720, 100, 24, 90), 'paper', seg(t, 54.7, 55.4));
  if (t >= 55.6) {
    const n = Math.floor(clamp01((t - 55.6) / 1.5) * PROMPT.length);
    T(PROMPT.slice(0, n), 934, 805, { size: 52, color: C.blue });
    if (Math.floor(t * 2.5) % 2 === 0 && t < 58.5) {
      const cx = 934 + measure(PROMPT.slice(0, n), 52) + 6;
      D(`M${cx},770 L${cx},815`, 'sys', 1, { w: 3.5 });
    }
  }
}
cue(51.9, 'pen', { dur: 0.7 });
cue(52.6, 'pen', { dur: 0.8 });
cue(53.4, 'pen', { dur: 1.0 });
cue(54.4, 'pen', { dur: 0.4, vol: 0.7 });
cue(55.0, 'pop', { f: 330 });
for (let i = 0; i < PROMPT.length; i++) cue(55.6 + (i / PROMPT.length) * 1.5, PROMPT[i] === ' ' ? 'tap' : 'type', { n: i * 3 + 5, vol: PROMPT[i] === ' ' ? 0.25 : 0.6 });
cue(57.3, 'chime', { vol: 0.8 });

const SCENES = [
  [B.intro, intro],
  [B.pain, pain],
  [B.typing, typing],
  [B.plat, plat],
  [B.dev, dev],
  [B.heal, heal],
  [B.end, end],
];

export function draw(t) {
  const sc = SCENES.find(([[a, b]]) => t >= a && t < b) || SCENES[SCENES.length - 1];
  sc[1](t);
  const g = evalTrack(TRACK, t);
  drawGekko(g.pose, g);
}
