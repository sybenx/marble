// The extension in desktop Firefox (a temporary add-on over WebDriver BiDi), against
// running Cosign servers (phase 1). Firefox hides extension frames from the driver, so
// the card is read from screenshots: the code is decoded from the image and the purple
// button found by its colour.
//
//   firefox -CreateProfile "cosigntest <dir>"   (once; Firefox here won't start without it)
//   node build.mjs firefox && FF_PROFILE=<dir> [SCAN_DELAY=40] node --experimental-transform-types test/firefox.ts

import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import puppeteer, { type Page } from 'puppeteer-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';

const A = JSON.parse(readFileSync(`${process.env.HOME}/cosign/server/data/a/state.json`, 'utf8'));
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const SHOT = new URL('../shots/ff-now.png', import.meta.url).pathname;

const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<!doctype html><title>t</title><h1>test site</h1>'); }).listen(8797);
const browser = await puppeteer.launch({ browser: 'firefox', executablePath: '/Applications/Firefox.app/Contents/MacOS/firefox', headless: !process.env.HEADED, userDataDir: process.env.FF_PROFILE });
log(`installed ${await browser.installExtension(new URL('../dist/firefox', import.meta.url).pathname)}`);

/** The screenshot as RGB, by way of an uncompressed BMP that sips makes. */
function pixels(png: string) {
  execFileSync('sips', ['-s', 'format', 'bmp', png, '--out', `${png}.bmp`], { stdio: 'ignore' });
  const b = readFileSync(`${png}.bmp`);
  const off = b.readUInt32LE(10), w = b.readInt32LE(18), h0 = b.readInt32LE(22), bpp = b.readUInt16LE(28) / 8, h = Math.abs(h0), row = Math.ceil((w * bpp) / 4) * 4;
  return { w, h, at: (x: number, y: number) => { const i = off + (h0 > 0 ? h - 1 - y : y) * row + x * bpp; return [b[i + 2], b[i + 1], b[i]]; } };
}
/** The centre of the card's primary (purple) button, if one is showing, in page pixels. */
async function purpleButton(page: Page): Promise<{ x: number; y: number } | null> {
  writeFileSync(SHOT, await page.screenshot());
  const p = pixels(SHOT), scale = p.w / 1000;
  // Rows that are mostly purple across a button's width; the logo and the purple
  // "Not now" link are far narrower.
  const purple = (x: number, y: number) => { const [r, g, bl] = p.at(x, y); return ((Math.abs(r - 0x6b) < 14 && Math.abs(g - 0x4e) < 14) || (Math.abs(r - 138) < 14 && Math.abs(g - 116) < 14)) && bl > 235; }; // light or dark
  const rows: { y: number; x0: number; x1: number }[] = [];
  for (let y = 0; y < p.h; y += 2) {
    let n = 0, x0 = 1e9, x1 = -1;
    for (let x = 0; x < p.w; x += 2) if (purple(x, y)) { n++; x0 = Math.min(x0, x); x1 = Math.max(x1, x); }
    if (n * 2 > 100 * scale) rows.push({ y, x0, x1 });
  }
  if (rows.length < 5) return null;
  const y = (rows[0].y + rows[rows.length - 1].y) / 2, x = (rows[0].x0 + rows[0].x1) / 2;
  return { x: x / scale, y: y / scale };
}
const call = (page: Page, expr: string) => page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e) }));

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 1000, height: 700 });
  await page.goto('http://localhost:8797/');
  check(await page.evaluate('typeof window.nostr?.signEvent === "function"') as boolean, 'window.nostr is there');
  const pk = call(page, 'window.nostr.getPublicKey()');
  await sleep(3000);
  // The card leads with Create account (purple); Sign in with your phone sits just below.
  const create = await purpleButton(page);
  if (!create) throw new Error('no sign-in card');
  await page.mouse.click(create.x, create.y + 50);
  await sleep(4000);
  // A person takes a while to find their phone and scan; Firefox may put the
  // extension's background to sleep meanwhile.
  const delay = Number(process.env.SCAN_DELAY ?? 0);
  if (delay) { log(`waiting ${delay} s, as if scanning`); await sleep(delay * 1000); }
  writeFileSync(SHOT, await page.screenshot());
  log('the stand-in phone scans the card');
  execFileSync('node', ['--experimental-transform-types', '--no-warnings', new URL('./fake-phone.ts', import.meta.url).pathname, SHOT], { stdio: 'inherit', timeout: 70_000 });
  let btn = null;
  for (let i = 0; i < 30 && !btn; i++) { await sleep(1000); btn = await purpleButton(page); }
  check(!!btn, 'the card asks "Sign in as …?"');
  if (!btn) throw new Error('no Sign in button');
  log(`clicking the button at ${Math.round(btn.x)}, ${Math.round(btn.y)}`);
  await page.mouse.click(btn.x, btn.y);
  await sleep(1500); writeFileSync(SHOT.replace('now', 'after-click'), await page.screenshot());
  const got = await Promise.race([pk, sleep(30_000).then(() => ({ ok: false as const, e: 'no answer in 30 s' }))]);
  writeFileSync(SHOT.replace('now', 'after-wait'), await page.screenshot());
  check(got.ok && got.r === A.group.groupX, `getPublicKey → ${got.ok ? String(got.r).slice(0, 12) : got.e}`);
  const t = Date.now();
  const ev = await Promise.race([call(page, `window.nostr.signEvent({ kind: 1, content: 'hello from Firefox', tags: [] })`), sleep(30_000).then(() => ({ ok: false as const, e: 'no answer in 30 s', r: null }))]);
  check(ev.ok && verifyEvent(ev.r as Event), `signEvent → valid (${Date.now() - t} ms)${ev.ok ? '' : ' ' + ev.e}`);
} catch (e) {
  failed++; log(`FAIL ${(e as Error).message}`);
} finally {
  await browser.close(); http.close();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
