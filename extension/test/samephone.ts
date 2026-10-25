// The same-phone flow, in Chromium dressed as an Android phone: the card offers Open
// Cosign; the stand-in phone answers that link; the extension signs in with no
// "Sign in as …?" and then says who you are, with "Not you? Sign out".
//   node build.mjs chrome && node --experimental-transform-types test/samephone.ts
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, devices, type Frame, type Page } from 'playwright-core';

const A = JSON.parse(readFileSync(`${process.env.HOME}/cosign/server/data/a/state.json`, 'utf8'));
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<meta name=viewport content="width=device-width"><h1>site</h1>'); }).listen(8793);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'], ...devices['Pixel 7'],
});
async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
try {
  const page = await ctx.newPage();
  await page.goto('http://localhost:8793/');
  const pk = page.evaluate('window.nostr.getPublicKey()').then(r => ({ ok: true, r }), e => ({ ok: false, r: String(e) }));
  const c = await card(page, 'Create account');
  page.on('console', m => log(`[console] ${m.text()}`));
  log(`phone button: ${await c.$eval('.phone', el => `${(el as HTMLElement).offsetWidth}x${(el as HTMLElement).offsetHeight}, onclick ${typeof (el as HTMLButtonElement).onclick}`)}`);
  // Emulated taps don't reach into the card's frame here; press the button directly.
  await c.$eval('.phone', el => (el as HTMLButtonElement).click());
  await page.waitForTimeout(1000);
  log(`after tap, by-phone hidden: ${await c.$eval('.by-phone', el => (el as HTMLElement).hidden)}`);
  await page.waitForTimeout(6000); await page.screenshot({ path: 'shots/samephone-card.png' });
  await c.waitForSelector('.open:not([hidden])', { timeout: 30_000 });
  const uri = await c.$eval('.open', el => (el as HTMLAnchorElement).href);
  check(uri.startsWith('https://cosign.aaro.cc/'), 'the card offers Open Cosign, a cosign.aaro.cc link');
  await c.$eval('.open', el => (el as HTMLAnchorElement).click()); // a tab with the link; on a phone, Cosign
  execFileSync('node', ['--experimental-transform-types', '--no-warnings', new URL('./fake-phone.ts', import.meta.url).pathname, uri], { stdio: 'ignore', timeout: 70_000 });
  const got = await Promise.race([pk, new Promise<{ ok: boolean; r: unknown }>(r => setTimeout(() => r({ ok: false, r: 'no answer in 30 s' }), 30_000))]);
  check(got.ok && got.r === A.group.groupX, `signed in with no "Sign in as …?" (${got.ok ? 'ok' : got.r})`);
  const notice = await card(page, 'Signed in as', 10_000);
  check(!!notice && (await notice.textContent('body'))!.includes('Not you? Sign out'), `then: "${(await notice.textContent('#title'))?.trim()}", with Not you? Sign out`);
  await page.screenshot({ path: 'shots/samephone-notice.png' });
} catch (e) { failed++; log(`FAIL ${(e as Error).message}`); }
finally { await ctx.close(); http.close(); log(failed ? `${failed} failed` : 'all passed'); process.exit(failed ? 1 : 0); }
