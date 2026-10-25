// The sign-in code (QR) shows: in the popup and on a site's card, in Chromium, within a
// second or two of tapping "Sign in with Marble". Also: what it says while there's no code.
//   node build.mjs chrome && node --experimental-transform-types test/qr.ts
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const LIMIT = Number(process.env.QR_LIMIT_MS ?? 1000);
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8784);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const LAUNCH = { executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`, headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new', ...(process.env.CHROME_ARGS ? process.env.CHROME_ARGS.split('|') : [])] };
const ctx = await chromium.launchPersistentContext('', LAUNCH);
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
await new Promise(r => setTimeout(r, 1000));
const id = sw.url().split('/')[2];
// Timed from opening (the popup, or the site asking), tapping as soon as the button is there.
async function timeQr(scope: Page | Frame, label: string, t: number) {
  await scope.click('button.phone');
  const ok = await scope.waitForSelector('.qr svg', { timeout: 20_000 }).then(() => true, () => false);
  const ms = Date.now() - t;
  const status = await scope.textContent('.by-phone .status').catch(() => '');
  check(ok && ms <= LIMIT, `${label}: QR shows ${ok ? `${ms} ms after opening` : 'never (20 s)'}${ok ? '' : `; it says "${status}"`}`);
}
async function offline() {
  const off = await chromium.launchPersistentContext('', { ...LAUNCH, offline: true });
  try {
    const w = off.serviceWorkers()[0] ?? await off.waitForEvent('serviceworker');
    await new Promise(r => setTimeout(r, 500));
    const p = await off.newPage();
    await p.goto(`chrome-extension://${w.url().split('/')[2]}/popup.html`);
    await p.click('button.phone');
    const said = await p.waitForSelector('.by-phone .retry:not([hidden])', { timeout: 8000 }).then(() => p.textContent('.by-phone .status'), () => '');
    const box = await p.isVisible('.by-phone .qr');
    check(/network/.test(said ?? '') && !box, `offline: "${said}" with Retry, no empty box`);
    await off.setOffline(false);
    const t = Date.now();
    // It keeps trying by itself; Retry only starts over sooner.
    const tapped = await p.click('.by-phone .retry', { timeout: 1000 }).then(() => true, () => false);
    const ok = await p.waitForSelector('.by-phone .qr svg', { timeout: 10_000 }).then(() => true, () => false);
    check(ok, `back online: QR ${ok ? `in ${Date.now() - t} ms` : 'never'} (${tapped ? 'after Retry' : 'by itself'})`);
  } finally { await off.close(); }
}
try {
  const pop = await ctx.newPage();
  await pop.setViewportSize({ width: 340, height: 520 });
  const opened = Date.now();
  await pop.goto(`chrome-extension://${id}/popup.html`);
  await pop.waitForSelector('#out:not([hidden]) button.phone', { timeout: 5000 });
  await timeQr(pop, 'popup', opened);
  await pop.close();
  const page = await ctx.newPage();
  await page.goto('http://localhost:8784/');
  const asked = Date.now();
  void page.evaluate('window.nostr.getPublicKey()').catch(() => {});
  let card: Frame | undefined;
  for (let i = 0; i < 40 && !card; i++) { card = page.frames().find(f => f.url().includes('/card.html')); if (!card) await page.waitForTimeout(250); }
  await card!.waitForSelector('button.phone', { timeout: 5000 });
  await timeQr(card!, 'site card', asked);
  if (!process.env.CHROME_ARGS) await offline();
} catch (e) { failed++; log(`FAIL ${(e as Error).stack}`); }
finally { await ctx.close(); http.close(); log(failed ? `${failed} failed` : 'all passed'); process.exit(failed ? 1 : 0); }
