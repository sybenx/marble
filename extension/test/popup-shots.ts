// Screenshots of the popup, signed out and signed in (a stand-in pairing), light and dark.
//   node --experimental-transform-types test/popup-shots.ts <dist/chrome folder> <out prefix>
import { chromium } from 'playwright-core';
const [ext, out] = process.argv.slice(2);
const ctx = await chromium.launchPersistentContext('', { executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`, headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'] });
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
const id = sw.url().split('/')[2];
const hex = () => [...crypto.getRandomValues(new Uint8Array(32))].map(b => b.toString(16).padStart(2, '0')).join('');
async function shoot(name: string) {
  for (const scheme of ['light', 'dark'] as const) {
    const p = await ctx.newPage();
    await p.emulateMedia({ colorScheme: scheme });
    await p.setViewportSize({ width: 340, height: 520 });
    await p.goto(`chrome-extension://${id}/popup.html`);
    await new Promise(r => setTimeout(r, 1500));
    await p.screenshot({ path: `${out}-${name}-${scheme}.png` });
    await p.close();
  }
}
await shoot('signed-out');
await sw.evaluate(([p, s]) => (globalThis as any).chrome.storage.local.set({ pairing: p, sites: s }), [{ sk: hex(), signer: hex(), relays: ['wss://127.0.0.1:1'], pubkey: hex(), name: 'Brave on Mac', perSite: true, profile: { name: 'Your account' } }, { 'https://syb.lol': { added: 1, lastUsed: Date.now() } }] as any);
await shoot('signed-in');
await ctx.close();
