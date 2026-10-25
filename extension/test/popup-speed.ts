// How fast the popup opens, in Chromium: time to first paint, and to its content (the
// account, or the sign-in choices) being drawn. "Warm": the background is awake. "Cold":
// the background has been stopped first, as Chrome does to an idle extension.
//   MEASURE=1 node build.mjs chrome && node --experimental-transform-types test/popup-speed.ts [runs]
// (MEASURE lets the test frame the popup; rebuild without it afterwards.)
import { chromium } from 'playwright-core';
const runs = Number(process.argv[2] ?? 5);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', { executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`, headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'] });
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
const id = sw.url().split('/')[2];
await new Promise(r => setTimeout(r, 1000));
// Signed in (a stand-in pairing with a few sites), the usual case.
const hex = () => [...crypto.getRandomValues(new Uint8Array(32))].map(b => b.toString(16).padStart(2, '0')).join('');
await sw.evaluate(([p, s]) => (globalThis as any).chrome.storage.local.set({ pairing: p, sites: s }), [{ sk: hex(), signer: hex(), relays: ['wss://127.0.0.1:1'], pubkey: hex(), name: 'Chrome on Mac', perSite: true, profile: { name: 'Test' } }, { 'https://a.example': { added: 1, lastUsed: Date.now() }, 'https://b.example': { added: 1, lastUsed: Date.now() } }] as any);
const helper = await ctx.newPage();
const cdp = await ctx.newCDPSession(helper);
async function once(cold: boolean) {
  if (cold) { await cdp.send('ServiceWorker.enable').catch(() => {}); await cdp.send('ServiceWorker.stopAllWorkers').catch(() => {}); await new Promise(r => setTimeout(r, 500)); }
  const p = await ctx.newPage();
  await p.setViewportSize({ width: 340, height: 520 });
  const t0 = Date.now();
  await p.goto(`chrome-extension://${id}/popup.html`);
  await p.waitForFunction(() => performance.getEntriesByName('marble-content').length > 0, null, { timeout: 15_000 }).catch(() => {});
  const r = await p.evaluate(() => ({ fp: performance.getEntriesByName('first-paint')[0]?.startTime ?? -1, fcp: performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? -1, content: performance.getEntriesByName('marble-content')[0]?.startTime ?? -1 }));
  await p.close();
  return r;
}
const med = (xs: number[]) => xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)];
for (const cold of [false, true]) {
  const rs = [] as { fp: number; fcp: number; content: number }[];
  for (let i = 0; i < runs; i++) rs.push(await once(cold));
  console.log(`${cold ? 'cold' : 'warm'}: first paint ${Math.round(med(rs.map(r => r.fp)))} ms, first content ${Math.round(med(rs.map(r => r.fcp)))} ms, account shown ${Math.round(med(rs.map(r => r.content)))} ms (median of ${runs})`);
}
await ctx.close();
