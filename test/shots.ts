// Screenshots of the card and popup, desktop and phone-sized, into $OUT (default ./shots).
import { mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, devices } from 'playwright-core';

const OUT = process.env.OUT ?? 'shots';
mkdirSync(OUT, { recursive: true });
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end(`<!doctype html><meta name=viewport content="width=device-width"><title>tube</title><body style="font:16px system-ui;margin:0;background:#111;color:#eee"><div style="padding:20px"><h1>tube.syb.lol (stand-in)</h1><p>${'Videos '.repeat(80)}</p></div>`); }).listen(8798);
const ext = new URL('../dist/chrome', import.meta.url).pathname;

for (const mode of ['desktop', 'phone'] as const) {
  const ctx = await chromium.launchPersistentContext('', {
    executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
    headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'],
    ...(mode === 'phone' ? { ...devices['Pixel 7'], userAgent: devices['Pixel 7'].userAgent } : { viewport: { width: 1100, height: 700 } }),
  });
  const page = await ctx.newPage();
  await page.goto('http://localhost:8798/');
  void page.evaluate('window.nostr.getPublicKey()').catch(() => {});
  await page.waitForTimeout(mode === 'phone' ? 3000 : 6000);
  await page.screenshot({ path: `${OUT}/${mode}-pair-card.png` });
  const id = (ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker')).url().split('/')[2];
  const pop = await ctx.newPage();
  await pop.setViewportSize(mode === 'phone' ? { width: 412, height: 800 } : { width: 340, height: 520 });
  await pop.goto(`chrome-extension://${id}/popup.html`);
  await pop.waitForTimeout(4000);
  await pop.screenshot({ path: `${OUT}/${mode}-popup-signed-out.png` });
  await ctx.close();
}
http.close();
console.log('done');
