// Runs the weighted-design groundwork (src/spike/weighted.ts) inside the extension in
// Chromium: DKG for 4-of-6 with this browser holding 3 shares, then who can sign.
//   node build.mjs chrome && node --experimental-transform-types test/weighted.ts
import { chromium } from 'playwright-core';
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'],
});
const sw = ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker');
const p = await ctx.newPage();
p.on('console', m => m.type() === 'error' && console.log('[page]', m.text()));
await p.goto(`chrome-extension://${sw.url().split('/')[2]}/spike-weighted.html`);
const r = await p.waitForFunction('window.result', null, { timeout: 60_000 }).then(h => h.jsonValue()) as any;
console.log(JSON.stringify(r, null, 2));
const ok = r.sameKeyEverywhere && r.browserPlusServer4 === 'signs' && r.browserPlusServer6 === 'signs' && /refused|invalid/.test(r.allThreeServers) && /refused|invalid/.test(r.browserAlone);
console.log(ok ? 'PASS weighted 4-of-6: browser + any server signs; servers alone and browser alone cannot' : 'FAIL');
await ctx.close();
process.exit(ok ? 0 : 1);
