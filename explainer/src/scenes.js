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

// a browser window: frame, title bar with three dots and an address pill; the page content is drawn by the caller
function browser(cx, top, w, h, seed, p) {
  const x = cx - w / 2;
  const bar = Math.round(Math.min(46, Math.max(30, h * 0.14)));
  const late = clamp01(p * 1.2 - 0.2);
  D(rbox(x, top, w, h, 16, seed), 'paper', p);
  D(`M${x},${top + bar} L${x + w},${top + bar}`, 'ink', late, { w: 3 });
  for (let i = 0; i < 3; i++) D(circ(x + bar * 0.6 + i * bar * 0.5, top + bar / 2, bar * 0.13, seed + 1 + i, 0.4), 'solid', p);
  D(rbox(x + bar * 2.4, top + bar * 0.24, w - bar * 3.4, bar * 0.52, bar * 0.26, seed + 4, 1), 'ink', late, { w: 3 });
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

// ------------------------------------------------------------------ layout of the "three platforms" beat
// Android phone | gekko | iOS phone | browser. gekko taps the two phones; the browser is clicked by a cursor.
const PA = { cx: 265, top: 440, w: 200, h: 460 };
const PI = { cx: 985, top: 440, w: 200, h: 460 };
const PW = { cx: 1505, top: 520, w: 500, h: 380 };
const PLAT_X = 625;
const PLAT_HAND_L = [PA.cx + PA.w / 2 - 45, 702];
const PLAT_HAND_R = [PI.cx - PI.w / 2 + 65, 702];
// contact times of the three taps per device (Android, iOS, web); ticks follow 0.5 s after the tap starts
const PLAT_TAPS = [[25.0, 26.0, 27.0], [25.12, 26.15, 27.1], [25.25, 26.3, 27.2]];
const PLAT_PAN = [-0.45, 0.05, 0.4];
const phoneField = (d) => ({ x: d.cx - 70, y: d.top + 58, w: 140, h: 40 });
const phoneRow = (d, i) => ({ x: d.cx - 70, y: d.top + 120 + i * 70, w: 140, h: 52 });
const webField = { x: PW.cx - PW.w / 2 + 36, y: PW.top + 66, w: PW.w - 72, h: 40 };
const webRow = (i) => ({ x: PW.cx - PW.w / 2 + 36, y: PW.top + 124 + i * 62, w: PW.w - 72, h: 46 });
const rowRect = [(i) => phoneRow(PA, i), (i) => phoneRow(PI, i), webRow];

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
  K(5.2, 290, 900, 1.5, 'stand'),
  // pain: gekko watches the three stacks to its right
  K(6.0, 290, 900, 1.5, lids(-125, -125, { eyeX: 8 })),
  K(7.4, 290, 900, 1.5, lids(-125, -125, { eyeX: 12 })),
  K(8.8, 290, 900, 1.5, 'shrug'),
  K(10.4, 290, 900, 1.5, 'stuck'),
  K(12.4, 290, 900, 1.5, 'stuck'),
  // typing: walk to the typewriter
  K(13.0, 290, 900, 1.5, 'stand', { walk: true }),
  K(14.3, 420, 800, 1.5, 'stand'),
  K(15.0, 420, 800, 1.5, typingPose),
  K(18.8, 420, 800, 1.5, typingPose),
  K(19.2, 420, 800, 1.5, lids(-150)),
  K(20.6, 420, 800, 1.5, 'stand', { walk: true }),
  // platforms: gekko stands between the two phones and taps both
  K(21.9, PLAT_X, 900, 1.5, 'stand'),
  K(24.4, PLAT_X, 900, 1.5, 'stand'),
  K(24.8, PLAT_X, 900, 1.5, { base: 'stand', tL: PLAT_HAND_L, tR: PLAT_HAND_R }),
  K(30.0, PLAT_X, 900, 1.5, { base: 'stand', tL: PLAT_HAND_L, tR: PLAT_HAND_R }),
  K(30.5, PLAT_X, 900, 1.5, 'stand', { walk: true }),
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
  T('gekko', 1010, 590, { size: 210 });
  D(underline(1015, 1440, 642, 2), 'flow', 1, { w: 6 });
  T('super fast UI tests', 1015, 708, { size: 54, color: C.blue });
  T('on Android, iOS and web', 1015, 772, { size: 54, color: C.blue });
  T('with LLM healing', 1015, 836, { size: 54, color: C.blue });
}

const PAIN = { G: 900, W: 270, H: 40, STEP: 44, N: 7, DT: 0.13 };
// one stack of test sheets per platform; xs are the sheets that get a red cross
const STACKS = [
  { cx: 780, t0: 5.6, seed: 1, xs: [2, 5], label: 'Android', pan: -0.3 },
  { cx: 1160, t0: 6.7, seed: 2, xs: [1, 4, 6], label: 'iOS', pan: 0 },
  { cx: 1540, t0: 7.8, seed: 3, xs: [0, 3, 5], label: 'Web', pan: 0.3 },
];
const crossT = (si, k) => 9.0 + k * 0.3 + si * 0.15;
function pain(t) {
  setExit(ex(t, B.pain[1]));
  T('manual testing', 960, 100, { size: 56, anchor: 'mid', p: seg(t, 4.8, 5.4, E.lin) });
  headline([['slow, ', C.ink], ['flaky', C.red], [', three times', C.ink]], t, 5.3, 1.3, 195);
  ground(120, 1800, PAIN.G, seg(t, 5.0, 5.8), 2);
  STACKS.forEach((S, si) => {
    for (let i = 0; i < PAIN.N; i++) {
      const p = seg(t, S.t0 + i * PAIN.DT, S.t0 + i * PAIN.DT + 0.22);
      const jx = ((S.seed * 13 + i * 7) % 11) - 5;
      sheet(S.cx - PAIN.W / 2 + jx, PAIN.G - (i + 1) * PAIN.STEP + 2, PAIN.W, PAIN.H, S.seed * 10 + i, p, 1);
      const k = S.xs.indexOf(i);
      if (k >= 0) {
        const s0 = crossT(si, k);
        D(cross(S.cx + 80 + jx, PAIN.G - (i + 1) * PAIN.STEP + 22, 13), 'warn', seg(t, s0, s0 + 0.2, E.out));
      }
    }
    const lt = S.t0 + 0.8;
    T(S.label, S.cx, PAIN.G + 62, { anchor: 'mid', p: seg(t, lt, lt + 0.5, E.lin) });
  });
  // the slow part: a sleep that never ends
  clockFace(1160, 430, 72, Math.max(0, t - 10.1), 5.2, seg(t, 9.9, 10.6));
  T('slow and tedious', 1268, 448, { size: 52, color: C.red, p: seg(t, 10.3, 11.1, E.lin) });
  D(underline(1268, 1268 + measure('slow and tedious', 52), 462, 5), 'warn', seg(t, 11.1, 11.5), { w: 3.5 });
}
cue(4.8, 'pen', { dur: 0.6 });
cue(5.3, 'pen', { dur: 1.2 });
STACKS.forEach((S, si) => {
  for (let i = 0; i < PAIN.N; i++) cue(S.t0 + i * PAIN.DT + 0.2, 'thud', { vol: 0.55, pan: S.pan });
  S.xs.forEach((_, k) => cue(crossT(si, k), 'xmark', { pan: S.pan }));
});
cue(9.1, 'buzz', { dur: 0.3 });
cue(9.9, 'pop', { f: 300 });
for (let t = 10.15; t < 12.2; t += 0.3) cue(t, 'tick', { f: 1700 });

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

// ---- three platforms
function plat(t) {
  setExit(ex(t, B.plat[1]));
  headline([['one test, three platforms', C.ink]], t, 21.2, 1.4);
  ground(150, 1780, 900, seg(t, 21.4, 22.2), 1);
  phone(PA.cx, PA.top, PA.w, PA.h, 3, seg(t, 21.7, 22.6));
  phone(PI.cx, PI.top, PI.w, PI.h, 4, seg(t, 22.2, 23.1), true);
  browser(PW.cx, PW.top, PW.w, PW.h, 5, seg(t, 22.7, 23.7));
  [['Android', PA.cx, 22.5], ['iOS', PI.cx, 23.0], ['Web', PW.cx, 23.5]].forEach(([name, x, t0]) => T(name, x, 952, { anchor: 'mid', p: seg(t, t0, t0 + 0.5, E.lin) }));
  // app content: a field and three rows per device
  const content = (field, rows, k, base) => {
    D(rbox(field.x, field.y, field.w, field.h, 10, k + 20), 'ink', seg(t, base, base + 0.4), { w: 3 });
    rows.forEach((r, i) => D(rbox(r.x, r.y, r.w, r.h, 10, k + 30 + i), 'ink', seg(t, base + 0.15 + i * 0.12, base + 0.55 + i * 0.12), { w: 3 }));
  };
  content(phoneField(PA), [0, 1, 2].map((i) => phoneRow(PA, i)), 100, 22.9);
  content(phoneField(PI), [0, 1, 2].map((i) => phoneRow(PI, i)), 200, 23.3);
  content(webField, [0, 1, 2].map(webRow), 300, 23.7);
  // the sheet that goes to all three
  sheet(495, 215, 260, 160, 8, seg(t, 23.6, 24.3), 3);
  T('say / do / until', 625, 330, { size: 34, anchor: 'mid', p: seg(t, 24.1, 24.6, E.lin) });
  D('M497,300 C400,250 285,285 272,412', 'flow', seg(t, 24.3, 25.0), { arrow: 'o' });
  D('M757,305 C850,262 960,280 985,412', 'flow', seg(t, 24.5, 25.2), { arrow: 'o' });
  D('M757,262 C960,215 1300,240 1380,498', 'flow', seg(t, 24.7, 25.5), { arrow: 'o' });
  // tiny agents inside the phones, a script tag inside the page
  const mp = seg(t, 25.4, 25.9, E.out);
  if (mp > 0) {
    miniGekko('stand', PA.cx, 846, 0.42, { alpha: mp });
    miniGekko('stand', PI.cx, 846, 0.42, { alpha: mp });
  }
  const gx = PW.cx;
  const gy = PW.top + 340;
  D(`M${gx - 14},${gy - 14} L${gx - 30},${gy} L${gx - 14},${gy + 14} M${gx + 5},${gy - 18} L${gx - 5},${gy + 18} M${gx + 14},${gy - 14} L${gx + 30},${gy} L${gx + 14},${gy + 14}`, 'sys', seg(t, 25.6, 26.0, E.out), { w: 5 });
  [['agent in the app', PA.cx, 26.1], ['agent in the app', PI.cx, 26.35], ['script in the page', PW.cx, 26.6]].forEach(([txt, x, t0]) =>
    T(txt, x, 1006, { size: 38, color: C.blue, anchor: 'mid', p: seg(t, t0, t0 + 0.5, E.lin) }),
  );
  // gekko taps both phones
  PLAT_TAPS[0].forEach((t0) => spark(PLAT_HAND_L[0] + 26, 692, 200, clamp01((t - t0 - 0.28) / 0.35)));
  PLAT_TAPS[1].forEach((t0) => spark(PLAT_HAND_R[0] - 26, 692, -20, clamp01((t - t0 - 0.28) / 0.35)));
  // a cursor clicks through the page
  const tgt = (i) => {
    const r = webRow(i);
    return [r.x + r.w * 0.55, r.y + r.h * 0.3];
  };
  let cur = [PW.cx + 150, PW.top + 90];
  PLAT_TAPS[2].forEach((t0, i) => {
    const s = seg(t, t0 - 0.1, t0 + 0.2);
    const g = tgt(i);
    cur = [lerp(cur[0], g[0], s), lerp(cur[1], g[1], s)];
  });
  const cp = seg(t, 24.8, 25.1, E.out);
  if (cp > 0) {
    push();
    translate(cur[0], cur[1]);
    D('M0,0 L0,30 L8,23 L14,36 L20,33 L14,21 L25,21 Z', 'paper', cp, { w: 3 });
    pop();
  }
  PLAT_TAPS[2].forEach((t0, i) => spark(tgt(i)[0], tgt(i)[1], 225, clamp01((t - t0 - 0.28) / 0.35)));
  // ticks
  PLAT_TAPS.forEach((taps, d) =>
    taps.forEach((t0, i) => {
      const r = rowRect[d](i);
      D(tick(r.x + r.w - 23, r.y + r.h / 2, 0.95), 'sys', seg(t, t0 + 0.5, t0 + 0.7, E.out));
    }),
  );
  // the stopwatch
  const sw = seg(t, 27.8, 28.5);
  D(circ(1450, 330, 48, 12), 'warn', sw);
  D(rbox(1440, 270, 20, 12, 3, 13), 'warn', sw, { w: 3.5 });
  const handA = clamp01((t - 28.2) / 0.5) * 6.28;
  D(`M1450,330 L${1450 + Math.sin(handA) * 32},${330 - Math.cos(handA) * 32}`, 'warn', sw, { w: 4.5 });
  T('super fast', 1520, 350, { size: 68, color: C.red, p: seg(t, 28.6, 29.2, E.lin) });
  D(underline(1520, 1520 + measure('super fast', 68), 368, 6), 'warn', seg(t, 29.2, 29.6), { w: 3.5 });
}
cue(21.2, 'pen', { dur: 1.3 });
cue(21.75, 'pop', { f: 330, pan: PLAT_PAN[0] });
cue(22.25, 'pop', { f: 330, pan: PLAT_PAN[1] });
cue(22.75, 'pop', { f: 330, pan: PLAT_PAN[2] });
cue(24.3, 'whoosh', { dur: 0.8, vol: 0.7 });
cue(25.45, 'pop', { f: 450, pan: PLAT_PAN[0] });
cue(25.55, 'pop', { f: 450, pan: PLAT_PAN[1] });
cue(25.65, 'pop', { f: 450, pan: PLAT_PAN[2] });
PLAT_TAPS.forEach((taps, d) =>
  taps.forEach((t0, i) => {
    cue(t0 + 0.28, 'tap', { pan: PLAT_PAN[d] * 1.1, vol: 0.7 });
    cue(t0 + 0.5, 'check', { pan: PLAT_PAN[d], n: [0, 4, 7][i], vol: 0.8 });
  }),
);
cue(27.9, 'plug');
for (let t = 28.2; t < 28.7; t += 0.07) cue(t, 'tick', { f: 2300, vol: 0.5 });
cue(28.7, 'pop', { f: 500 });

// ---- all devices: phones and browsers side by side
const DEVS = [
  { cx: 695, kind: 'a' },
  { cx: 915, kind: 'i' },
  { cx: 1175, kind: 'w' },
  { cx: 1435, kind: 'a' },
  { cx: 1695, kind: 'w' },
];
function dev(t) {
  setExit(ex(t, B.dev[1]));
  headline([['every device at once', C.ink]], t, 31.3, 1.4);
  ground(150, 1850, 880, seg(t, 31.5, 32.2), 1);
  DEVS.forEach(({ cx, kind }, i) => {
    const p = seg(t, 32.0 + i * 0.25, 32.6 + i * 0.25);
    if (kind === 'w') browser(cx, 680, 250, 200, 20 + i, p);
    else phone(cx, 580, 170, 300, 20 + i, p, kind === 'i');
  });
  // stack of test sheets on gekko's hands
  for (let i = 0; i < 3; i++) sheet(165 + ((i * 5) % 7), 568 - (i + 1) * 36, 330, 34, 50 + i, seg(t, 32.4 + i * 0.15, 32.7 + i * 0.15), 1);
  DEVS.forEach(({ cx, kind }, i) => {
    const sx = 390 + i * 26;
    const ty = kind === 'w' ? 660 : 560;
    D(`M${sx},462 C${sx + (cx - sx) * 0.12},${230 + i * 28} ${cx - 70},${300 + i * 14} ${cx},${ty}`, 'flow', seg(t, 33.0 + i * 0.22, 33.8 + i * 0.22), { arrow: 'o' });
  });
  DEVS.forEach(({ cx, kind }, i) => {
    if (kind === 'w') D(rbox(cx - 95, 728, 190, 28, 8, 60 + i), 'ink', seg(t, 33.9, 34.4), { w: 3 });
    else D(rbox(cx - 55, 650, 110, 30, 8, 60 + i), 'ink', seg(t, 33.9, 34.4), { w: 3 });
    D(tick(cx, kind === 'w' ? 815 : 740, 1.7), 'sys', seg(t, 34.7 + i * 0.22, 34.95 + i * 0.22, E.out), { w: 6 });
  });
  T('--devices all', 1215, 960, { anchor: 'mid', size: 46, color: C.blue, p: seg(t, 35.9, 36.6, E.lin) });
}
cue(31.3, 'pen', { dur: 1.4 });
DEVS.forEach(({ cx }, i) => cue(32.1 + i * 0.25, 'pop', { f: 300 + i * 40, pan: (cx - 960) / 960 * 0.8, vol: 0.8 }));
cue(33.0, 'whoosh', { dur: 0.8, vol: 0.6 });
DEVS.forEach(({ cx }, i) => cue(34.75 + i * 0.22, 'check', { n: i * 2, pan: (cx - 960) / 960 * 0.8 }));

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
