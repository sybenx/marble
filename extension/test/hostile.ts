// A hostile page against the "Allow this site?" decision, in Chromium.
//   Computer: the decision opens in a window of the extension's own; the page only gets a
//   pointer, with no Allow in it.
//   Phone-sized screen (the card stays in the page): the page fades the card, moves it,
//   fades <html>, puts an invisible layer on top, fakes a click; Allow must not work
//   while any of that is going on, and a real click on a settled, visible card must.
//
//   node build.mjs chrome && node --experimental-transform-types test/hostile.ts

import { createServer } from 'node:http';
import { chromium, devices, type BrowserContext, type Frame, type Page } from 'playwright-core';

const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<meta name=viewport content="width=device-width"><h1>Totally normal site</h1><button id=decoy style="position:fixed;bottom:40px;left:20px">Play video</button>'); }).listen(8789);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const exe = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;

async function launch(phone: boolean): Promise<BrowserContext> {
  const ctx = await chromium.launchPersistentContext('', { executablePath: exe, headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'], ...(phone ? { viewport: { width: 412, height: 915 }, screen: { width: 412, height: 915 } } : { viewport: { width: 1200, height: 800 } }) });
  const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
  await sleep(800);
  // Signed in already (a stand-in pairing), so a new site gets "Allow this site?".
  const hex = () => [...crypto.getRandomValues(new Uint8Array(32))].map(b => b.toString(16).padStart(2, '0')).join('');
  await sw.evaluate(p => (globalThis as any).chrome.storage.local.set({ pairing: p }), { sk: hex(), signer: hex(), relays: ['wss://127.0.0.1:1'], pubkey: hex(), name: 'Test' });
  return ctx;
}
async function cardIn(page: Page, want: string, timeout = 15_000): Promise<Frame | undefined> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(200);
  }
}
const disabled = (f: Frame) => f.$eval('#yes', b => (b as HTMLButtonElement).disabled);
/** The page's own script, touching the card's host element (it can: it's in the page). */
const hostile = (page: Page, js: string) => page.evaluate(js);
const HOST = `[...document.documentElement.children].find(e => e.tagName === 'DIV' && e.getAttribute('style')?.includes('2147483647'))`;

try {
  // --- A computer ---------------------------------------------------------------------
  {
    const ctx = await launch(false);
    const page = await ctx.newPage();
    await page.goto('http://localhost:8789/');
    const pk = page.evaluate('window.nostr.getPublicKey()').catch(() => null);
    const pointer = await cardIn(page, 'asking in its own window');
    check(!!pointer, 'computer: the page shows only a pointer ("Cosign is asking in its own window")');
    check(!!pointer && !(await pointer.$('#yes')), 'computer: there is no Allow button in the page');
    await sleep(800);
    const win = ctx.pages().find(p => p.url().includes('card.html') && p.url().includes('&window'));
    check(!!win, 'computer: the decision is in a window of the extension’s own');
    if (win) {
      check((await win.textContent('.site'))?.trim() === 'localhost:8789', `computer: it names the site, large: "${(await win.textContent('.site'))?.trim()}"`);
      await win.click('#no');
      await sleep(800);
      check(!ctx.pages().some(p => p.url().includes('&window')), 'computer: Not now closes the window');
    }
    // Another site: a real click on Allow in the window is taken.
    const page2 = await ctx.newPage();
    await page2.goto('http://127.0.0.1:8789/');
    void page2.evaluate('window.nostr.getPublicKey()').catch(() => null);
    await cardIn(page2, 'asking in its own window');
    await sleep(1200);
    const win2 = ctx.pages().find(p => p.url().includes('card.html') && p.url().includes('&window'));
    if (win2) { await win2.waitForSelector('#yes:not([disabled])', { timeout: 5000 }).catch(() => {}); await win2.click('#yes'); }
    await sleep(800);
    check(!!win2 && !ctx.pages().some(p => p.url().includes('&window')), 'computer: a real click on Allow in the window is taken');
    void pk;
    await ctx.close();
  }

  // --- A phone: the decision in a tab of the extension's own, then back -----------------
  {
    const ctx = await launch(true);
    const page = await ctx.newPage();
    await page.goto('http://localhost:8789/');
    void page.evaluate('window.nostr.getPublicKey()').catch(() => null);
    const pointer = await cardIn(page, 'asking in its own tab', 6000);
    check(!!pointer && !(await pointer.$('#yes')), 'phone: the page shows only a pointer, no Allow');
    await sleep(800);
    const tab = ctx.pages().find(p => p.url().includes('card.html') && p.url().includes('&window'));
    check(!!tab, 'phone: the decision is in a tab of the extension’s own');
    if (tab) {
      check((await tab.textContent('.site'))?.trim() === 'localhost:8789', 'phone: it names the site, large');
      await tab.waitForSelector('#yes:not([disabled])', { timeout: 5000 });
      await tab.click('#yes');
      await sleep(1000);
      check(!ctx.pages().some(p => p.url().includes('&window')), 'phone: Allow in the tab is taken, and the tab goes away');
      check(await page.evaluate('document.visibilityState') === 'visible', 'phone: back on the site');
    }
    await ctx.close();
  }

  // --- The fallback: the card stays in the page, hardened --------------------------------
  {
    const ctx = await launch(true);
    const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js'))!;
    await sw.evaluate(() => (globalThis as any).chrome.storage.local.set({ devInPageCard: true }));
    await sleep(300);
    const page = await ctx.newPage();
    await page.goto('http://localhost:8789/');
    const pk = page.evaluate('window.nostr.getPublicKey()').then(() => 'answered', e => `answered: ${String(e.message ?? e).split('\n')[0]}`);
    const f = await cardIn(page, 'sign in as');
    check(!!f, 'phone: the Allow card is in the page');
    if (!f) throw new Error('no card');
    check((await f.textContent('.site'))?.trim() === 'localhost:8789', 'phone: it names the site, large, from the extension’s record');
    check(await disabled(f), 'phone: Allow waits a moment after the card appears');
    await sleep(1200);
    check(!(await disabled(f)), 'phone: once settled and visible, Allow is live');

    await hostile(page, `${HOST}.style.opacity = '0'`);
    await sleep(100);
    check(await disabled(f), 'attack: fading the card → its look is put back and Allow re-arms');
    check(await hostile(page, `getComputedStyle(${HOST}).opacity`) === '1', 'attack: fading the card → opacity is back to 1');
    await sleep(1200);

    await hostile(page, `${HOST}.style.setProperty('bottom', '300px', 'important')`);
    await sleep(100);
    check(await disabled(f), 'attack: moving the card under the pointer → put back, Allow re-arms');
    await sleep(1200);

    await hostile(page, `document.documentElement.style.opacity = '0.02'`);
    await sleep(1200);
    check(await disabled(f), 'attack: fading the whole page → Allow stays off while it lasts');
    await hostile(page, `document.documentElement.style.opacity = ''`);
    await sleep(1200);

    await hostile(page, `(() => { const d = document.createElement('div'); d.id = 'veil'; d.popover = 'manual'; d.style.cssText = 'pointer-events:none;inset:0;width:100vw;height:100vh;background:transparent;border:0'; d.innerHTML = '<h1 style="margin-top:70vh">Click to claim your prize</h1>'; document.body.append(d); d.showPopover(); })()`);
    await sleep(1200);
    check(await disabled(f), 'attack: an invisible layer on top (popover, clicks pass through) → Allow stays off');
    await hostile(page, `document.getElementById('veil').hidePopover()`);
    await sleep(1200);

    // A click from script, not a person: the card ignores it.
    await f.evaluate(() => (document.getElementById('yes') as HTMLButtonElement).click());
    await sleep(500);
    check(!!(await cardIn(page, 'sign in as', 500)), 'attack: a scripted (untrusted) click on Allow does nothing');

    check(!(await disabled(f)), 'after the attacks stop and a moment passes, Allow is live again');
    // (A person's real tap on a phone isn't something this harness can make inside an
    // extension frame: its clicks there arrive untrusted. The real click is checked in the
    // window, on the computer, through the same handler.)
    void pk;
    await ctx.close();
  }
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  http.close();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
