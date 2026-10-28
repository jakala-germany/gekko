// Synthesized sound effects, rendered offline from a cue list: [[seconds, 'name', options?], ...]
// No samples: every sound is oscillators and filtered noise.
import { rng } from './engine.js';

const SR = 48000;

function noiseBuffer(ctx, seconds, seed) {
  const r = rng(seed);
  const buf = ctx.createBuffer(1, Math.ceil(SR * seconds), SR);
  const d = buf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = r() * 2 - 1;
  return buf;
}

function chain(ctx, dest, pan = 0) {
  const g = ctx.createGain();
  if (pan) {
    const p = ctx.createStereoPanner();
    p.pan.value = pan;
    g.connect(p);
    p.connect(dest);
  } else g.connect(dest);
  return g;
}

// gain envelope: attack a, exponential decay to silence by d
function env(g, t, a, d, peak) {
  g.gain.setValueAtTime(0.0001, t);
  g.gain.linearRampToValueAtTime(peak, t + a);
  g.gain.exponentialRampToValueAtTime(0.0001, t + a + d);
}

function tone(ctx, out, type, f0, f1, t, dur, peak, a = 0.004) {
  const o = ctx.createOscillator();
  o.type = type;
  o.frequency.setValueAtTime(f0, t);
  if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, t + dur);
  const g = ctx.createGain();
  env(g, t, a, dur, peak);
  o.connect(g);
  g.connect(out);
  o.start(t);
  o.stop(t + a + dur + 0.05);
}

function noise(ctx, out, nb, ftype, f0, f1, q, t, dur, peak, a = 0.004, off = 0) {
  const s = ctx.createBufferSource();
  s.buffer = nb;
  const f = ctx.createBiquadFilter();
  f.type = ftype;
  f.Q.value = q;
  f.frequency.setValueAtTime(f0, t);
  if (f1 !== f0) f.frequency.exponentialRampToValueAtTime(f1, t + dur);
  const g = ctx.createGain();
  env(g, t, a, dur, peak);
  s.connect(f);
  f.connect(g);
  g.connect(out);
  s.start(t, off % 1.5);
  s.stop(t + a + dur + 0.05);
}

const R = {
  // soft pen scratch: bandpassed noise with a scratchy amplitude flutter
  pen(ctx, dest, t, o, nb) {
    const dur = o.dur || 0.5;
    const out = chain(ctx, dest, o.pan || 0);
    const s = ctx.createBufferSource();
    s.buffer = nb;
    const f = ctx.createBiquadFilter();
    f.type = 'bandpass';
    f.frequency.value = 3800;
    f.Q.value = 0.9;
    const g = ctx.createGain();
    const r = rng(Math.floor(t * 1000));
    g.gain.setValueAtTime(0, t);
    const steps = Math.ceil(dur * 38);
    for (let i = 0; i < steps; i++) g.gain.linearRampToValueAtTime((0.35 + r() * 0.65) * 0.09 * (o.vol || 1), t + (i / steps) * dur);
    g.gain.linearRampToValueAtTime(0, t + dur + 0.03);
    s.connect(f);
    f.connect(g);
    g.connect(out);
    s.start(t, (t * 7) % 1);
    s.stop(t + dur + 0.05);
  },
  type(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest, o.pan || ((((o.n || 0) * 0.37) % 1) - 0.5) * 0.3);
    const v = o.vol || 1;
    noise(ctx, out, nb, 'bandpass', 2200 + (o.n || 0) * 37 % 500, 1800, 1.4, t, 0.03, 0.22 * v, 0.001, (o.n || 0) * 0.13);
    tone(ctx, out, 'sine', 170, 90, t, 0.05, 0.16 * v, 0.001);
  },
  bell(ctx, dest, t, o) {
    const out = chain(ctx, dest, 0.15);
    tone(ctx, out, 'sine', 2093, 2093, t, 1.1, 0.16);
    tone(ctx, out, 'sine', 3140, 3140, t, 0.7, 0.07);
    tone(ctx, out, 'sine', 4190, 4190, t, 0.4, 0.03);
  },
  tap(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest, o.pan || 0);
    tone(ctx, out, 'sine', 300, 95, t, 0.1, 0.34 * (o.vol || 1), 0.002);
    noise(ctx, out, nb, 'bandpass', 2400, 2400, 1, t, 0.025, 0.12, 0.001);
  },
  tick(ctx, dest, t, o) {
    const out = chain(ctx, dest, o.pan || 0);
    tone(ctx, out, 'square', o.f || 1900, o.f || 1900, t, 0.012, 0.07 * (o.vol || 1), 0.0005);
    tone(ctx, out, 'sine', 700, 500, t, 0.03, 0.08, 0.001);
  },
  // checkmark blip, pitch rises with o.n
  check(ctx, dest, t, o) {
    const out = chain(ctx, dest, o.pan || 0);
    const f = 660 * Math.pow(2, (o.n || 0) / 12);
    tone(ctx, out, 'triangle', f, f, t, 0.16, 0.16 * (o.vol || 1), 0.003);
    tone(ctx, out, 'sine', f * 2, f * 2, t + 0.045, 0.14, 0.07 * (o.vol || 1), 0.003);
  },
  buzz(ctx, dest, t, o) {
    const out = chain(ctx, dest);
    const f = ctx.createBiquadFilter();
    f.type = 'lowpass';
    f.frequency.value = 900;
    f.connect(out);
    const dur = o.dur || 0.32;
    for (const hz of [96, 101]) {
      const os = ctx.createOscillator();
      os.type = 'sawtooth';
      os.frequency.value = hz;
      const g = ctx.createGain();
      g.gain.setValueAtTime(0.0001, t);
      g.gain.linearRampToValueAtTime(0.13, t + 0.01);
      g.gain.setValueAtTime(0.13, t + dur - 0.05);
      g.gain.linearRampToValueAtTime(0.0001, t + dur);
      os.connect(g);
      g.connect(f);
      os.start(t);
      os.stop(t + dur + 0.02);
    }
    const lfo = ctx.createOscillator();
    lfo.frequency.value = 22;
    const lg = ctx.createGain();
    lg.gain.value = 400;
    lfo.connect(lg);
    lg.connect(f.frequency);
    lfo.start(t);
    lfo.stop(t + dur + 0.02);
  },
  xmark(ctx, dest, t, o) {
    const out = chain(ctx, dest, o.pan || 0);
    tone(ctx, out, 'square', 220, 150, t, 0.12, 0.08, 0.002);
    tone(ctx, out, 'sine', 140, 70, t, 0.16, 0.2, 0.002);
  },
  thwip(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest, 0.1);
    noise(ctx, out, nb, 'bandpass', 700, 4200, 3, t, 0.11, 0.3, 0.004);
    tone(ctx, out, 'sine', 500, 200, t + 0.1, 0.08, 0.14, 0.001);
  },
  slide(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest);
    noise(ctx, out, nb, 'lowpass', 600, 2600, 0.7, t, o.dur || 0.4, 0.13, 0.12);
  },
  whoosh(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest);
    const dur = o.dur || 0.5;
    const s = ctx.createBufferSource();
    s.buffer = nb;
    const f = ctx.createBiquadFilter();
    f.type = 'bandpass';
    f.Q.value = 0.8;
    f.frequency.setValueAtTime(350, t);
    f.frequency.exponentialRampToValueAtTime(2400, t + dur * 0.5);
    f.frequency.exponentialRampToValueAtTime(500, t + dur);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(0.16 * (o.vol || 1), t + dur * 0.45);
    g.gain.linearRampToValueAtTime(0.0001, t + dur);
    s.connect(f);
    f.connect(g);
    g.connect(out);
    s.start(t, 0.3);
    s.stop(t + dur + 0.05);
  },
  pop(ctx, dest, t, o) {
    const out = chain(ctx, dest, o.pan || 0);
    tone(ctx, out, 'sine', o.f || 380, (o.f || 380) * 2.3, t, 0.07, 0.3 * (o.vol || 1), 0.002);
  },
  plug(ctx, dest, t, o, nb) {
    const out = chain(ctx, dest, o.pan || 0);
    noise(ctx, out, nb, 'highpass', 2500, 2500, 0.7, t, 0.012, 0.18, 0.0005);
    tone(ctx, out, 'sine', 200, 110, t, 0.09, 0.32, 0.001);
  },
  chime(ctx, dest, t, o) {
    const out = chain(ctx, dest, 0.1);
    const notes = o.notes || [659.25, 783.99, 1046.5];
    notes.forEach((f, i) => {
      tone(ctx, out, 'triangle', f, f, t + i * 0.09, 0.9, 0.13 * (o.vol || 1), 0.004);
      tone(ctx, out, 'sine', f * 2, f * 2, t + i * 0.09, 0.5, 0.04, 0.004);
    });
  },
  // a low soft thud, used for stacking sheets and landing
  thud(ctx, dest, t, o) {
    const out = chain(ctx, dest, o.pan || 0);
    tone(ctx, out, 'sine', 130, 55, t, 0.14, 0.34 * (o.vol || 1), 0.002);
  },
};

export async function renderSfx(cues, duration) {
  const ctx = new OfflineAudioContext(2, Math.ceil(SR * duration), SR);
  const master = ctx.createGain();
  master.gain.value = 0.9;
  const comp = ctx.createDynamicsCompressor();
  comp.threshold.value = -14;
  comp.ratio.value = 3;
  comp.attack.value = 0.004;
  comp.release.value = 0.15;
  master.connect(comp);
  comp.connect(ctx.destination);
  const nb = noiseBuffer(ctx, 1.6, 7);
  for (const [t, name, o] of cues) {
    if (!R[name]) throw new Error('unknown sfx ' + name);
    R[name](ctx, master, t, o || {}, nb);
  }
  const buf = await ctx.startRendering();
  const L = buf.getChannelData(0);
  const Rr = buf.getChannelData(1);
  let peak = 0;
  for (let i = 0; i < L.length; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(Rr[i]));
  const gain = peak > 0 ? 0.89 / peak : 1; // -1 dBFS
  return toWav(L, Rr, gain);
}

function toWav(L, Rr, gain) {
  const n = L.length;
  const bytes = new ArrayBuffer(44 + n * 4);
  const v = new DataView(bytes);
  const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF');
  v.setUint32(4, 36 + n * 4, true);
  w(8, 'WAVEfmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, 2, true);
  v.setUint32(24, SR, true);
  v.setUint32(28, SR * 4, true);
  v.setUint16(32, 4, true);
  v.setUint16(34, 16, true);
  w(36, 'data');
  v.setUint32(40, n * 4, true);
  for (let i = 0; i < n; i++) {
    v.setInt16(44 + i * 4, Math.max(-1, Math.min(1, L[i] * gain)) * 32767, true);
    v.setInt16(46 + i * 4, Math.max(-1, Math.min(1, Rr[i] * gain)) * 32767, true);
  }
  let bin = '';
  const u8 = new Uint8Array(bytes);
  for (let i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(bin);
}
