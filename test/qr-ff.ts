// The sign-in code (QR) shows on a site's card in desktop Firefox, read from screenshots
// (Firefox hides extension frames from the driver). The popup runs the same code; the
// driver can't open extension pages in Firefox, so it isn't timed here.
//   node build.mjs firefox && FF_PROFILE=<dir> node --experimental-transform-types test/qr-ff.ts
import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import puppeteer, { type Page } from 'puppeteer-core';
import decodeQR from 'qr/decode.js';
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
const LIMIT = Number(process.env.QR_LIMIT_MS ?? 1000);
const SHOT = new URL('../shots/ff-qr.png', import.meta.url).pathname;
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

function pixels(png: string) {
  execFileSync('sips', ['-s', 'format', 'bmp', png, '--out', `${png}.bmp`], { stdio: 'ignore' });
  const b = readFileSync(`${png}.bmp`);
  const off = b.readUInt32LE(10), w = b.readInt32LE(18), h0 = b.readInt32LE(22), bpp = b.readUInt16LE(28) / 8, h = Math.abs(h0), row = Math.ceil((w * bpp) / 4) * 4;
  const rgb = new Uint8Array(w * h * 3);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const s = off + (h0 > 0 ? h - 1 - y : y) * row + x * bpp, d = (y * w + x) * 3; rgb[d] = b[s + 2]; rgb[d + 1] = b[s + 1]; rgb[d + 2] = b[s]; }
  return { w, h, rgb, at: (x: number, y: number) => { const i = (y * w + x) * 3; return [rgb[i], rgb[i + 1], rgb[i + 2]]; } };
}
async function shot(page: Page) { writeFileSync(SHOT, await page.screenshot()); return pixels(SHOT); }
function purpleButton(p: ReturnType<typeof pixels>) {
  const scale = p.w / 1000, rows: { y: number; x0: number; x1: number }[] = [];
  const purple = (x: number, y: number) => { const [r, g, bl] = p.at(x, y); return ((Math.abs(r - 0x6b) < 14 && Math.abs(g - 0x4e) < 14) || (Math.abs(r - 138) < 14 && Math.abs(g - 116) < 14)) && bl > 235; }; // light or dark
  for (let y = 0; y < p.h; y += 2) {
    let n = 0, x0 = 1e9, x1 = -1;
    for (let x = 0; x < p.w; x += 2) if (purple(x, y)) { n++; x0 = Math.min(x0, x); x1 = Math.max(x1, x); }
    if (n * 2 > 100 * scale) rows.push({ y, x0, x1 });
  }
  if (rows.length < 5) return null;
  return { x: (rows[0].x0 + rows[0].x1) / 2 / scale, y: (rows[0].y + rows[rows.length - 1].y) / 2 / scale };
}
const code = (p: ReturnType<typeof pixels>) => { try { return decodeQR({ width: p.w, height: p.h, data: p.rgb }); } catch { return ''; } };

const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<!doctype html><title>t</title><h1>test site</h1>'); }).listen(8798);
const browser = await puppeteer.launch({ browser: 'firefox', executablePath: '/Applications/Firefox.app/Contents/MacOS/firefox', headless: !process.env.HEADED, userDataDir: process.env.FF_PROFILE });
let failed = 0;
try {
  await browser.installExtension(new URL('../dist/firefox', import.meta.url).pathname);
  const page = await browser.newPage();
  await page.setViewport({ width: 1000, height: 700 });
  await page.goto('http://localhost:8798/');
  void page.evaluate('window.nostr.getPublicKey()').catch(() => {});
  let create = null;
  for (let i = 0; i < 20 && !create; i++) { await sleep(250); create = purpleButton(await shot(page)); }
  if (!create) throw new Error('no sign-in card');
  const t = Date.now();
  await page.mouse.click(create.x, create.y + 50); // "Sign in with Marble", just below Create account
  let uri = '';
  while (!uri && Date.now() - t < 20_000) uri = code(await shot(page));
  const ms = Date.now() - t;
  const ok = uri.startsWith('https://cosign.aaro.cc/#') && ms <= LIMIT;
  if (!ok) failed++;
  log(`${ok ? 'PASS' : 'FAIL'} Firefox card: QR ${uri ? `readable in ${ms} ms (screenshots included)` : 'never (20 s); see shots/ff-qr.png'}`);
} catch (e) { failed++; log(`FAIL ${(e as Error).message}`); }
finally { await browser.close(); http.close(); process.exit(failed ? 1 : 0); }
