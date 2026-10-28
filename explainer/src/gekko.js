// The gekko mascot rig, ported from the android-illustrations scene template.
// Origin = bottom centre between the feet. Poses are numbers, so they interpolate.
import { D, push, pop, translate, rotate, scale, setAlpha, getAlpha, lerp, E, clamp01 } from './engine.js';

const BODY = 'M-72,-152 C-30,-156 30,-150 73,-153 C77,-120 71,-85 74,-52 C30,-48 -30,-55 -70,-49 C-76,-85 -69,-120 -72,-152 Z';
const SEAM = 'M-58,-146 L58,-147';
const LID = 'M-2,-8 L29,-7 L32,7 L-2,8 Z';
const EYE_L = 'M-35,-124 L-18,-124 L-18,-98 L-38,-98 L-38,-121 Q-38,-124 -35,-124 Z';
const EYE_R = 'M17,-123 L30,-123 Q33,-123 33,-120 L33,-98 L14,-98 L14,-120 Q14,-123 17,-123 Z';

const mir = (a) => a.map((v, i) => (i % 2 === 0 ? -v : v));

const BASE = {
  armL: [-73, -105, -92, -94, -98, -74, -90, -58],
  armR: [73, -105, 92, -94, 98, -74, 90, -58],
  legL: [-30, -50, -31, -6, -43, -5],
  legR: [30, -50, 31, -6, 43, -5],
  lidL: -125,
  lidR: -125,
  rot: 0,
  sq: 1,
  eyeX: 0,
  eyeY: 0,
};

const OVERHEAD_L = [-73, -95, -130, -100, -134, -160, -112, -208];
const LIMP_L = [-73, -105, -95, -90, -100, -62, -96, -40];

export const POSES = {
  stand: BASE,
  walk: {
    ...BASE,
    armL: [-73, -105, -102, -102, -110, -82, -104, -66],
    armR: [73, -105, 88, -92, 90, -76, 82, -64],
    legL: [-30, -50, -52, -6, -64, -5],
    legR: [30, -50, 38, -6, 50, -5],
    lidL: -132,
    lidR: -118,
  },
  carry: { ...BASE, armL: OVERHEAD_L, armR: mir(OVERHEAD_L), lidL: -100, lidR: -100 },
  strain: {
    ...BASE,
    armL: OVERHEAD_L,
    armR: mir(OVERHEAD_L),
    legL: [-30, -50, -54, -6, -66, -5],
    legR: [30, -50, 54, -6, 66, -5],
    lidL: -215,
    lidR: -215,
  },
  closed: { ...BASE, lidL: 0, lidR: 0 },
  stuck: {
    ...BASE,
    armL: LIMP_L,
    armR: mir(LIMP_L),
    legL: [-30, -50, -46, -6, -58, -5],
    legR: [30, -50, 46, -6, 58, -5],
    lidL: -215,
    lidR: -215,
  },
  shrug: { ...BASE, lidL: -105, lidR: -205 },
  swallow: { ...BASE, lidL: -150, lidR: -150, rot: 7 },
  reach: { ...BASE, armR: [73, -105, 112, -110, 152, -112, 192, -108] },
  wave: { ...BASE, armR: [73, -105, 118, -128, 132, -178, 112, -214], lidL: -140, lidR: -110 },
  cheer: { ...BASE, armL: [-73, -105, -118, -130, -128, -180, -108, -216], armR: [73, -105, 118, -130, 128, -180, 108, -216], lidL: -150, lidR: -150 },
};

const clone = (p) => ({
  ...p,
  armL: p.armL.slice(),
  armR: p.armR.slice(),
  legL: p.legL.slice(),
  legR: p.legR.slice(),
});

function mixArr(a, b, t) {
  return a.map((v, i) => lerp(v, b[i], t));
}

export function mixPose(a, b, t) {
  return {
    armL: mixArr(a.armL, b.armL, t),
    armR: mixArr(a.armR, b.armR, t),
    legL: mixArr(a.legL, b.legL, t),
    legR: mixArr(a.legR, b.legR, t),
    lidL: lerp(a.lidL, b.lidL, t),
    lidR: lerp(a.lidR, b.lidR, t),
    rot: lerp(a.rot, b.rot, t),
    sq: lerp(a.sq, b.sq, t),
    eyeX: lerp(a.eyeX, b.eyeX, t),
    eyeY: lerp(a.eyeY, b.eyeY, t),
  };
}

// a thin arm from the shoulder to a point in gekko space
function armTo(side, ex, ey) {
  const sx = side * 73;
  const sy = -105;
  const dx = ex - sx;
  const dy = ey - sy;
  const len = Math.hypot(dx, dy) || 1;
  const nx = -dy / len;
  const ny = dx / len;
  const bend = len * 0.06 * -side;
  return [sx, sy, sx + dx * 0.35 + nx * bend, sy + dy * 0.35 + ny * bend, sx + dx * 0.7 + nx * bend, sy + dy * 0.7 + ny * bend, ex, ey];
}

// spec: 'stand' | { base, lidL, lidR, armL, armR, eyeX, eyeY, tR:[wx,wy], tL:[wx,wy] }
function resolve(spec, st) {
  const s = typeof spec === 'string' ? { base: spec } : spec;
  const p = clone(POSES[s.base || 'stand']);
  for (const k of ['lidL', 'lidR', 'rot', 'sq', 'eyeX', 'eyeY', 'armL', 'armR', 'legL', 'legR']) {
    if (s[k] !== undefined) p[k] = Array.isArray(s[k]) ? s[k].slice() : s[k];
  }
  const sx = st.s * (st.flip ? -1 : 1);
  if (s.tR) p.armR = armTo(1, (s.tR[0] - st.x) / sx, (s.tR[1] - st.y) / (st.s * p.sq));
  if (s.tL) p.armL = armTo(-1, (s.tL[0] - st.x) / sx, (s.tL[1] - st.y) / (st.s * p.sq));
  return p;
}

// Keyframes: { t, x, y, s, pose, flip, walk, rot, alpha }
export function evalTrack(keys, t, fnArg = t) {
  let i = 0;
  while (i < keys.length - 1 && keys[i + 1].t <= t) i++;
  const k0 = keys[i];
  const k1 = keys[Math.min(i + 1, keys.length - 1)];
  const span = k1.t - k0.t;
  const u = span > 0 && t > k0.t ? clamp01((t - k0.t) / span) : 0;
  const e = E.io(u);
  const specOf = (k) => (typeof k.pose === 'function' ? k.pose(fnArg) : k.pose || 'stand');
  const st = {
    x: lerp(k0.x, k1.x, e),
    y: lerp(k0.y, k1.y, e),
    s: lerp(k0.s ?? 1.5, k1.s ?? 1.5, e),
    rot: lerp(k0.rot ?? 0, k1.rot ?? 0, e),
    alpha: lerp(k0.alpha ?? 1, k1.alpha ?? 1, e),
    flip: !!k0.flip,
  };
  let pose;
  if (k0.walk && u > 0 && u < 1) {
    st.flip = k1.x < k0.x ? true : k1.x > k0.x ? false : st.flip;
    const ph = (t - k0.t) * 11;
    const w = (Math.sin(ph) + 1) / 2;
    const wA = POSES.walk;
    const wB = { ...POSES.walk, legL: POSES.walk.legR.map((v, j) => (j % 2 === 0 ? -v : v)), legR: POSES.walk.legL.map((v, j) => (j % 2 === 0 ? -v : v)) };
    let pw = mixPose(wA, wB, w);
    st.y -= Math.abs(Math.sin(ph)) * 7;
    const p1 = resolve(specOf(k1), { ...st, flip: st.flip });
    if (u > 0.82) pw = mixPose(pw, p1, (u - 0.82) / 0.18);
    pose = pw;
  } else {
    const p0 = resolve(specOf(k0), st);
    const p1 = resolve(specOf(k1), st);
    pose = u <= 0 ? p0 : mixPose(p0, p1, e);
    if (k0.flip !== undefined && k1.flip !== undefined && k0.flip !== k1.flip && u > 0.5) st.flip = !!k1.flip;
    if (u >= 1) st.flip = !!(k1.flip ?? k0.flip);
  }
  if (!k0.walk) st.y += Math.sin(t * 3.1) * 1.6;
  return { pose, ...st };
}

// Draw gekko from a resolved pose. o: { x, y, s, flip, rot, alpha, sq }
export function drawGekko(pose, o) {
  const { x, y, s = 1.5, flip = false, alpha = 1 } = o;
  if (alpha <= 0) return;
  push();
  setAlpha(getAlpha() * Math.min(1, alpha));
  translate(x, y);
  const r = (o.rot || 0) + (pose.rot || 0) * (flip ? -1 : 1);
  if (r) rotate(r);
  const pop_ = alpha < 1 ? 0.9 + 0.1 * alpha : 1;
  scale((flip ? -s : s) * pop_, s * pop_ * (pose.sq ?? 1));
  const arm = (a) => `M${a[0]},${a[1]} C${a[2]},${a[3]} ${a[4]},${a[5]} ${a[6]},${a[7]}`;
  const leg = (l) => `M${l[0]},${l[1]} L${l[2]},${l[3]} L${l[4]},${l[5]}`;
  D(arm(pose.armL), 'pen', 1, { a: 0 });
  D(arm(pose.armR), 'pen', 1, { a: 0 });
  D(leg(pose.legL), 'pen', 1, { a: 0, w: 7 });
  D(leg(pose.legR), 'pen', 1, { a: 0, w: 7 });
  push();
  translate(-72, -152);
  rotate(pose.lidL);
  D(LID, 'solid', 1, { a: 0 });
  pop();
  push();
  translate(72, -152);
  scale(-1, 1);
  rotate(pose.lidR);
  D(LID, 'solid', 1, { a: 0 });
  pop();
  D(BODY, 'solid', 1, { a: 0 });
  D(SEAM, 'ink', 1, { a: 0, stroke: '#fff', w: 3 });
  push();
  translate(pose.eyeX, pose.eyeY);
  D(EYE_L, 'white', 1, { a: 0 });
  D(EYE_R, 'white', 1, { a: 0 });
  pop();
  pop();
}

export function gekkoAt(keys, t) {
  const g = evalTrack(keys, t);
  drawGekko(g.pose, g);
  return g;
}

// a static gekko, e.g. a tiny one inside a phone
export function miniGekko(spec, x, y, s, extra = {}) {
  const st = { x, y, s, flip: !!extra.flip };
  drawGekko(resolve(spec, st), { ...st, ...extra });
}
