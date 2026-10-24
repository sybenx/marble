// Sign in to a real site (default tube.syb.lol) with the extension, in Chromium: pair on
// the site's first window.nostr call, then screenshot what the site shows.
//   node --experimental-transform-types test/tube.ts [url]
import { execFileSync } from 'node:child_process';
import { chromium, type Frame, type Page } from 'playwright-core';

const URL_ = process.argv[2] ?? 'https://tube.syb.lol/';
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: !process.env.HEADED, viewport: { width: 1200, height: 800 },
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, ...(process.env.HEADED ? [] : ['--headless=new'])],
});
const log = (s: string) => console.log(s);
async function cardFrame(page: Page, want: string, timeout = 20_000): Promise<Frame | undefined> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(300);
  }
}
const page = await ctx.newPage();
page.on('console', m => { if (/nostr|sign|error/i.test(m.text())) log(`[page] ${m.text().slice(0, 160)}`); });
await page.goto(URL_, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3000);
log(`window.nostr: ${await page.evaluate('typeof window.nostr')}`);
await page.screenshot({ path: 'shots/tube-1-loaded.png' });
// The site's own sign-in button, if it has one.
const btn = page.getByRole('button', { name: process.env.BUTTON ? new RegExp(process.env.BUTTON, 'i') : /browser extension|sign in|log in/i }).first();
if (await btn.count()) { await btn.click(); log('clicked the site\'s sign-in'); } else { log('no sign-in button; calling getPublicKey'); void page.evaluate('window.nostr.getPublicKey()').catch(() => {}); }
await page.waitForTimeout(2000);
await page.screenshot({ path: 'shots/tube-2-after-click.png' });
const card = await cardFrame(page, 'Sign in to', 15_000);
if (!card) { log('no Cosign card appeared'); await ctx.close(); process.exit(1); }
await card.waitForSelector('.qr svg', { timeout: 30_000 });
const uri = await card.$eval('.qr', el => (el as HTMLElement).dataset.uri!);
await page.screenshot({ path: 'shots/tube-3-card.png' });
execFileSync('node', ['--experimental-transform-types', '--no-warnings', new URL('./fake-phone.ts', import.meta.url).pathname, uri], { stdio: 'inherit', timeout: 70_000 });
const confirm = await cardFrame(page, 'Sign in as', 30_000);
await confirm!.click('.yes');
await page.waitForTimeout(8000);
await page.screenshot({ path: 'shots/tube-4-signed-in.png' });
log('done; see shots/tube-*.png');
await ctx.close();
