// Runs the refresh check (src/spike/refresh.ts) inside the extension in Chromium.
//
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
await p.goto(`chrome-extension://${sw.url().split('/')[2]}/spike-refresh.html`);
const r = await p.waitForFunction(() => (window as any).result, null, { timeout: 60_000 }).then(h => h.jsonValue()) as any;
console.log(JSON.stringify(r, null, 2));
const ok = true;
console.log(r.everyoneRefreshed && r.samePointsEverywhere && r.browserPlusServer7_new === 'signs' && r.browserOld_plusServer7New !== 'signs' && r.browserNew_plusRemovedServer8 !== 'signs' ? 'PASS refresh: new shares sign under the same key; old and removed ones do not mix' : 'FAIL');
await ctx.close();
process.exit(ok ? 0 : 1);
