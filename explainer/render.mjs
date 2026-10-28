// Usage:
//   node render.mjs                      full render -> out/gekko-explainer.mp4
//   node render.mjs --stills 2,10.5,30   PNG stills -> out/stills/
//   node render.mjs --from 10 --to 20    partial render -> out/partial.mp4 (no audio)
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { extname, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const arg = (n) => {
  const i = args.indexOf('--' + n);
  return i >= 0 ? args[i + 1] : undefined;
};
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.ttf': 'font/ttf', '.json': 'application/json' };

const server = createServer(async (req, res) => {
  try {
    const p = join(root, decodeURIComponent(req.url.split('?')[0]).replace(/^\/$/, '/index.html'));
    const buf = await readFile(p);
    res.writeHead(200, { 'content-type': MIME[extname(p)] || 'application/octet-stream' });
    res.end(buf);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(0, r));
const url = `http://localhost:${server.address().port}/index.html?render`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('pageerror', (e) => console.error('page error:', e.message));
page.on('console', (m) => m.type() === 'error' && console.error('console:', m.text()));
await page.goto(url);
await page.waitForFunction('window.__ready === true', null, { timeout: 30000 });
const meta = await page.evaluate('window.__video.meta');
await mkdir(join(root, 'out/stills'), { recursive: true });

if (arg('stills')) {
  for (const t of arg('stills').split(',').map(Number)) {
    const b64 = await page.evaluate((i) => window.__video.frame(i), Math.round(t * meta.fps));
    const f = join(root, `out/stills/t${t.toFixed(2).padStart(5, '0')}.png`);
    await writeFile(f, Buffer.from(b64, 'base64'));
    console.log(f);
  }
} else {
  const from = arg('from') !== undefined ? Number(arg('from')) : 0;
  const to = arg('to') !== undefined ? Number(arg('to')) : meta.duration;
  const partial = arg('from') !== undefined || arg('to') !== undefined;
  const f0 = Math.round(from * meta.fps);
  const f1 = Math.round(to * meta.fps);
  const outFile = join(root, partial ? 'out/partial.mp4' : 'out/gekko-explainer.mp4');
  const ff = ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(meta.fps), '-c:v', 'png', '-i', 'pipe:0'];
  if (!partial) {
    const wav = await page.evaluate('window.__video.renderAudio()');
    await writeFile(join(root, 'out/sfx.wav'), Buffer.from(wav, 'base64'));
    ff.push('-i', join(root, 'out/sfx.wav'), '-map', '0:v', '-map', '1:a', '-c:a', 'aac', '-b:a', '160k', '-t', String(meta.duration));
  }
  ff.push('-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-tune', 'animation', '-pix_fmt', 'yuv420p', '-r', String(meta.fps), '-movflags', '+faststart', outFile);
  const enc = spawn('ffmpeg', ff, { stdio: ['pipe', 'inherit', 'inherit'] });
  const t0 = Date.now();
  for (let i = f0; i < f1; i++) {
    const b64 = await page.evaluate((n) => window.__video.frame(n), i);
    if (!enc.stdin.write(Buffer.from(b64, 'base64'))) await new Promise((r) => enc.stdin.once('drain', r));
    if (i % 90 === 0) console.log(`frame ${i}/${f1}  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
  enc.stdin.end();
  await new Promise((r) => enc.on('close', r));
  console.log('wrote', outFile);
}
await browser.close();
server.close();
