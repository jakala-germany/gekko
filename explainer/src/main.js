import { init, begin, W, H } from './engine.js';
import { meta, draw, cues } from './scenes.js';
import { renderSfx } from './audio.js';

const canvas = document.getElementById('c');
canvas.width = meta.w ?? W;
canvas.height = meta.h ?? H;
const ctx = canvas.getContext('2d');
init(ctx);

function renderFrame(t) {
  ctx.globalAlpha = 1;
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  begin(t);
  draw(t);
}

window.__video = {
  meta,
  renderFrame,
  frame(i) {
    renderFrame(i / meta.fps);
    return canvas.toDataURL('image/png').slice(22);
  },
  renderAudio: () => renderSfx(cues, meta.duration),
};

await document.fonts.load('46px "Patrick Hand"');
await document.fonts.ready;
window.__ready = true;

if (!location.search.includes('render')) {
  document.body.classList.add('preview');
  const scrub = document.getElementById('scrub');
  const tt = document.getElementById('tt');
  scrub.max = meta.duration;
  let playing = false;
  let t0 = 0;
  const show = (t) => {
    renderFrame(t);
    scrub.value = t;
    tt.textContent = t.toFixed(1);
  };
  document.getElementById('play').onclick = () => {
    playing = !playing;
    t0 = performance.now() - scrub.value * 1000;
  };
  scrub.oninput = () => show(+scrub.value);
  const loop = () => {
    if (playing) {
      const t = ((performance.now() - t0) / 1000) % meta.duration;
      show(t);
    }
    requestAnimationFrame(loop);
  };
  show(0);
  loop();
}
