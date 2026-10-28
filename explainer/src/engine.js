// Hand-drawn canvas engine: path sampling, wobble + boil, pen draw-on, text write-on.
// Everything is a pure function of time, so any frame can be rendered on its own.

export const W = 1920;
export const H = 1080;
export const C = { ink: '#111', red: '#E5322D', orange: '#F28C1E', blue: '#2F6FE4', white: '#fff' };

export const clamp01 = (x) => Math.max(0, Math.min(1, x));
export const lerp = (a, b, t) => a + (b - a) * t;

export const E = {
  lin: (t) => t,
  in: (t) => t * t * t,
  out: (t) => 1 - Math.pow(1 - t, 3),
  io: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  back: (t) => {
    const c1 = 1.70158;
    const c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  },
  pop: (t) => {
    // overshoot then settle, for stamps and ticks
    const u = clamp01(t);
    return u < 0.6 ? (u / 0.6) * 1.18 : 1.18 - 0.18 * ((u - 0.6) / 0.4);
  },
};

// progress 0..1 of time t inside [a, b]
export function seg(t, a, b, e = E.io) {
  return e(clamp01((t - a) / (b - a)));
}

export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------- state
let ctx;
let M = [1, 0, 0, 1, 0, 0];
const stack = [];
let BOIL = 0;
let EX = 0; // exit progress of the current scene (0..1): strokes erase from the start
let GA = 1; // global alpha multiplier
let AMP = 2.4; // wobble amplitude in px

export function init(c) {
  ctx = c;
}

export function begin(t, o = {}) {
  M = [1, 0, 0, 1, 0, 0];
  stack.length = 0;
  BOIL = Math.floor(t * 8) % 4;
  EX = 0;
  GA = 1;
  AMP = o.amp ?? 2.4;
}

export function setExit(v) {
  EX = clamp01(v);
}
export function getExit() {
  return EX;
}
export function setAlpha(v) {
  GA = v;
}
export function getAlpha() {
  return GA;
}

// ---------------------------------------------------------------- transforms
export function push() {
  stack.push([M.slice(), GA]);
}
export function pop() {
  const s = stack.pop();
  M = s[0];
  GA = s[1];
}
export function translate(x, y) {
  M[4] += M[0] * x + M[2] * y;
  M[5] += M[1] * x + M[3] * y;
}
export function rotate(deg) {
  const r = (deg * Math.PI) / 180;
  const cs = Math.cos(r);
  const sn = Math.sin(r);
  const [a, b, c, d] = M;
  M[0] = a * cs + c * sn;
  M[1] = b * cs + d * sn;
  M[2] = -a * sn + c * cs;
  M[3] = -b * sn + d * cs;
}
export function scale(sx, sy = sx) {
  M[0] *= sx;
  M[1] *= sx;
  M[2] *= sy;
  M[3] *= sy;
}
const tx = (x, y) => M[0] * x + M[2] * y + M[4];
const ty = (x, y) => M[1] * x + M[3] * y + M[5];
const mscale = () => Math.sqrt(Math.abs(M[0] * M[3] - M[1] * M[2]));

// ---------------------------------------------------------------- noise
function hash(ix, iy, s) {
  let h = (Math.imul(ix, 374761393) + Math.imul(iy, 668265263) + Math.imul(s, 1274126177)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}
function vn(x, y, s) {
  const ix = Math.floor(x);
  const iy = Math.floor(y);
  const fx = x - ix;
  const fy = y - iy;
  const u = fx * fx * (3 - 2 * fx);
  const v = fy * fy * (3 - 2 * fy);
  const a = hash(ix, iy, s);
  const b = hash(ix + 1, iy, s);
  const c = hash(ix, iy + 1, s);
  const d = hash(ix + 1, iy + 1, s);
  return (a + (b - a) * u + (c + (d - c) * u - (a + (b - a) * u)) * v) * 2 - 1;
}
const FQ = 0.018;
function wob(x, y, out) {
  const s = BOIL;
  const nx = vn(x * FQ, y * FQ, s) + 0.5 * vn(x * FQ * 2.1, y * FQ * 2.1, s + 11);
  const ny = vn(x * FQ + 31.7, y * FQ + 17.3, s + 5) + 0.5 * vn(x * FQ * 2.1 + 9, y * FQ * 2.1 + 4, s + 19);
  out[0] = x + nx * AMP * 0.7;
  out[1] = y + ny * AMP * 0.7;
}

// ---------------------------------------------------------------- paths
const cache = new Map();

function parse(d) {
  let c = cache.get(d);
  if (c) return c;
  const tok = d.match(/[MLHVCQZ]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?/g) || [];
  let i = 0;
  let cmd = '';
  let x = 0;
  let y = 0;
  const subs = [];
  let cur = null;
  const n = () => +tok[i++];
  const begin_ = (px, py) => {
    cur = { pts: [[px, py]], closed: false };
    subs.push(cur);
    x = px;
    y = py;
  };
  const lineTo = (px, py) => {
    cur.pts.push([px, py]);
    x = px;
    y = py;
  };
  const cubic = (x1, y1, x2, y2, px, py) => {
    const L = Math.hypot(x1 - x, y1 - y) + Math.hypot(x2 - x1, y2 - y1) + Math.hypot(px - x2, py - y2);
    const k = Math.max(2, Math.ceil(L / 5));
    for (let j = 1; j <= k; j++) {
      const u = j / k;
      const v = 1 - u;
      cur.pts.push([
        v * v * v * x + 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u * px,
        v * v * v * y + 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u * py,
      ]);
    }
    x = px;
    y = py;
  };
  const quad = (x1, y1, px, py) => {
    const L = Math.hypot(x1 - x, y1 - y) + Math.hypot(px - x1, py - y1);
    const k = Math.max(2, Math.ceil(L / 5));
    for (let j = 1; j <= k; j++) {
      const u = j / k;
      const v = 1 - u;
      cur.pts.push([v * v * x + 2 * v * u * x1 + u * u * px, v * v * y + 2 * v * u * y1 + u * u * py]);
    }
    x = px;
    y = py;
  };
  while (i < tok.length) {
    if (/[A-Za-z]/.test(tok[i])) cmd = tok[i++];
    switch (cmd) {
      case 'M':
        begin_(n(), n());
        cmd = 'L';
        break;
      case 'L':
        lineTo(n(), n());
        break;
      case 'H':
        lineTo(n(), y);
        break;
      case 'V':
        lineTo(x, n());
        break;
      case 'C':
        cubic(n(), n(), n(), n(), n(), n());
        break;
      case 'Q':
        quad(n(), n(), n(), n());
        break;
      case 'Z': {
        const s = cur.pts[0];
        if (Math.hypot(x - s[0], y - s[1]) > 0.1) lineTo(s[0], s[1]);
        cur.closed = true;
        x = s[0];
        y = s[1];
        break;
      }
      default:
        i++;
    }
  }
  let total = 0;
  for (const s of subs) {
    s.cl = [0];
    for (let j = 1; j < s.pts.length; j++) {
      s.cl.push(s.cl[j - 1] + Math.hypot(s.pts[j][0] - s.pts[j - 1][0], s.pts[j][1] - s.pts[j - 1][1]));
    }
    s.len = s.cl[s.cl.length - 1];
    total += s.len;
  }
  subs.total = total;
  cache.set(d, subs);
  return subs;
}

// ---------------------------------------------------------------- styles
const STY = {
  ink: { stroke: C.ink, w: 3.5 },
  paper: { stroke: C.ink, w: 3.5, fill: '#fff' },
  solid: { stroke: C.ink, w: 3, fill: C.ink },
  white: { fill: '#fff', w: 0, stroke: null },
  flow: { stroke: C.orange, w: 4.5 },
  warn: { stroke: C.red, w: 4 },
  note: { stroke: C.blue, w: 3.5, dash: [14, 11] },
  sys: { stroke: C.blue, w: 4.5 },
  dotr: { stroke: C.red, w: 2, fill: C.red },
  doto: { stroke: C.orange, w: 2, fill: C.orange },
  dotb: { stroke: C.blue, w: 2, fill: C.blue },
  pen: { stroke: C.ink, w: 6 },
  fillw: { stroke: null, w: 0, fill: '#fff' },
};

// Draw a path with pen progress p (0..1). o: { a: erase-from, arrow: 'o'|'k'|'r'|'b', w, stroke, fill, alpha }
export function D(d, cls = 'ink', p = 1, o = {}) {
  const st = Object.assign({}, STY[cls], o);
  const a = o.a ?? EX;
  const b = Math.min(1, p);
  if (b <= a) return;
  const subs = parse(d);
  const total = subs.total;
  const k = mscale();
  const tmp = [0, 0];
  const alpha = (st.alpha ?? 1) * GA;
  let off = 0;
  let last = null;
  for (const sub of subs) {
    const A = a * total - off;
    const B = b * total - off;
    off += sub.len;
    if (B <= 0 || A >= sub.len) continue;
    const s0 = Math.max(0, A);
    const s1 = Math.min(sub.len, B);
    const tp = new Array(sub.pts.length);
    for (let j = 0; j < sub.pts.length; j++) {
      const px = sub.pts[j][0];
      const py = sub.pts[j][1];
      wob(tx(px, py), ty(px, py), tmp);
      tp[j] = [tmp[0], tmp[1]];
    }
    if (st.fill && sub.closed) {
      const fa = clamp01((p - 0.5) / 0.4) * (1 - clamp01(a * 1.3));
      if (fa > 0) {
        ctx.globalAlpha = alpha * fa;
        ctx.fillStyle = st.fill;
        ctx.beginPath();
        ctx.moveTo(tp[0][0], tp[0][1]);
        for (let j = 1; j < tp.length; j++) ctx.lineTo(tp[j][0], tp[j][1]);
        ctx.closePath();
        ctx.fill();
      }
    }
    if (st.stroke && st.w > 0) {
      ctx.globalAlpha = alpha;
      ctx.strokeStyle = st.stroke;
      ctx.lineWidth = st.w * k;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.setLineDash(st.dash ? st.dash.map((v) => v * k) : []);
      ctx.beginPath();
      const full = s0 <= 0 && s1 >= sub.len;
      let started = false;
      for (let j = 0; j < tp.length; j++) {
        const c0 = sub.cl[j];
        if (c0 < s0) {
          const c1 = sub.cl[j + 1];
          if (c1 > s0) {
            const u = (s0 - c0) / (c1 - c0);
            ctx.moveTo(tp[j][0] + (tp[j + 1][0] - tp[j][0]) * u, tp[j][1] + (tp[j + 1][1] - tp[j][1]) * u);
            started = true;
          }
          continue;
        }
        if (c0 > s1) {
          const c_ = sub.cl[j - 1];
          const u = (s1 - c_) / (c0 - c_);
          if (!started) {
            ctx.moveTo(tp[j - 1][0], tp[j - 1][1]);
            started = true;
          }
          ctx.lineTo(tp[j - 1][0] + (tp[j][0] - tp[j - 1][0]) * u, tp[j - 1][1] + (tp[j][1] - tp[j - 1][1]) * u);
          break;
        }
        if (!started) {
          ctx.moveTo(tp[j][0], tp[j][1]);
          started = true;
        } else ctx.lineTo(tp[j][0], tp[j][1]);
      }
      if (full && sub.closed) ctx.closePath();
      ctx.stroke();
      ctx.setLineDash([]);
    }
    last = tp;
  }
  if (o.arrow && b >= 0.97 && last && last.length > 2) {
    const e = last[last.length - 1];
    let q = last[last.length - 2];
    for (let j = last.length - 3; j >= 0 && Math.hypot(e[0] - q[0], e[1] - q[1]) < 8; j--) q = last[j];
    const ang = Math.atan2(e[1] - q[1], e[0] - q[0]);
    const col = { o: C.orange, k: C.ink, r: C.red, b: C.blue }[o.arrow];
    const len = 19 * (k > 0 ? Math.max(0.7, k) : 1);
    ctx.globalAlpha = alpha;
    ctx.strokeStyle = col;
    ctx.lineWidth = (st.w || 4) * k * 0.9;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(e[0] - Math.cos(ang - 0.5) * len, e[1] - Math.sin(ang - 0.5) * len);
    ctx.lineTo(e[0], e[1]);
    ctx.lineTo(e[0] - Math.cos(ang + 0.5) * len, e[1] - Math.sin(ang + 0.5) * len);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
}

// ---------------------------------------------------------------- text
const FONT = '"Patrick Hand","Bradley Hand","Marker Felt",cursive';
const wcache = new Map();
export function measure(str, size = 46) {
  const key = size + str;
  let w = wcache.get(key);
  if (w === undefined) {
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.font = `${size}px ${FONT}`;
    w = ctx.measureText(str).width;
    ctx.restore();
    wcache.set(key, w);
  }
  return w;
}

// Handwritten text with left-to-right write-on. o: { size, color, anchor: 'left'|'mid'|'right', p, alpha }
export function T(str, x, y, o = {}) {
  const size = o.size || 46;
  const p = o.p ?? 1;
  const al = (o.alpha ?? 1) * (1 - EX) * GA;
  if (p <= 0 || al <= 0) return;
  const w = measure(str, size);
  const x0 = o.anchor === 'mid' ? x - w / 2 : o.anchor === 'right' ? x - w : x;
  ctx.save();
  ctx.setTransform(M[0], M[1], M[2], M[3], M[4], M[5]);
  ctx.font = `${size}px ${FONT}`;
  ctx.globalAlpha = al;
  ctx.fillStyle = o.color || C.ink;
  ctx.textBaseline = 'alphabetic';
  if (p < 1) {
    ctx.beginPath();
    ctx.rect(x0 - 6, y - size * 1.1, w * p + 8, size * 1.6);
    ctx.clip();
  }
  ctx.fillText(str, x0, y);
  ctx.restore();
}

// ---------------------------------------------------------------- shape helpers (hand-jittered)
export function rbox(x, y, w, h, r = 0, seed = 1, j = 2.5) {
  const R = rng(seed * 7919 + 13);
  const q = () => (R() - 0.5) * 2 * j;
  const f = (v) => v.toFixed(1);
  const side = (x0, y0, x1, y1) => `Q${f((x0 + x1) / 2 + q())},${f((y0 + y1) / 2 + q())} ${f(x1 + q() * 0.4)},${f(y1 + q() * 0.4)}`;
  const rr = Math.min(r, w / 2, h / 2);
  const p = [
    [x + rr, y],
    [x + w - rr, y],
    [x + w, y + rr],
    [x + w, y + h - rr],
    [x + w - rr, y + h],
    [x + rr, y + h],
    [x, y + h - rr],
    [x, y + rr],
  ];
  let d = `M${f(p[0][0] + q())},${f(p[0][1] + q())}`;
  d += side(p[0][0], p[0][1], p[1][0], p[1][1]);
  d += rr > 0 ? `Q${f(x + w)},${f(y)} ${f(p[2][0])},${f(p[2][1])}` : '';
  d += side(p[2][0], p[2][1], p[3][0], p[3][1]);
  d += rr > 0 ? `Q${f(x + w)},${f(y + h)} ${f(p[4][0])},${f(p[4][1])}` : '';
  d += side(p[4][0], p[4][1], p[5][0], p[5][1]);
  d += rr > 0 ? `Q${f(x)},${f(y + h)} ${f(p[6][0])},${f(p[6][1])}` : '';
  d += side(p[6][0], p[6][1], p[7][0], p[7][1]);
  d += rr > 0 ? `Q${f(x)},${f(y)} ${f(p[0][0])},${f(p[0][1])}` : '';
  return d + ' Z';
}

export function circ(cx, cy, r, seed = 1, j = 1.5) {
  const R = rng(seed * 104729 + 5);
  const q = () => (R() - 0.5) * 2 * j;
  const k = 0.5523;
  const f = (v) => v.toFixed(1);
  const a = r + q();
  const b = r + q();
  const c = r + q();
  const e = r + q();
  return (
    `M${f(cx + a)},${f(cy)} ` +
    `C${f(cx + a)},${f(cy + b * k)} ${f(cx + a * k)},${f(cy + b)} ${f(cx)},${f(cy + b)} ` +
    `C${f(cx - c * k)},${f(cy + b)} ${f(cx - c)},${f(cy + e * k)} ${f(cx - c)},${f(cy)} ` +
    `C${f(cx - c)},${f(cy - e * k)} ${f(cx - c * k)},${f(cy - e)} ${f(cx)},${f(cy - e)} ` +
    `C${f(cx + a * k)},${f(cy - e)} ${f(cx + a)},${f(cy - b * k)} ${f(cx + a)},${f(cy)} Z`
  );
}

// a wavy underline stroke
export function underline(x0, x1, y, seed = 1) {
  const R = rng(seed * 31 + 7);
  const f = (v) => v.toFixed(1);
  const m = (x0 + x1) / 2;
  return `M${f(x0)},${f(y + R() * 4)} C${f(x0 + (m - x0) * 0.6)},${f(y - 5 + R() * 4)} ${f(m)},${f(y + 5 + R() * 4)} ${f(x1)},${f(y - 2 + R() * 4)}`;
}

export const cross = (cx, cy, r = 14) => `M${cx - r},${cy - r} L${cx + r},${cy + r} M${cx + r},${cy - r} L${cx - r},${cy + r}`;
export const tick = (cx, cy, s = 1) => `M${cx - 14 * s},${cy + 1 * s} L${cx - 4 * s},${cy + 12 * s} L${cx + 17 * s},${cy - 14 * s}`;
