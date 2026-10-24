// The browser's half of a joint test with a real Cosign phone (another person or session
// drives the phone): shows the extension's cosign:join code, waits to be added, signs,
// then waits for a refresh (a device reported lost on the phone) and signs again.
// Codes and progress go to .test-group/joint/.
//
//   node build.mjs chrome && node --experimental-transform-types test/joint.ts

import { writeFileSync, appendFileSync, mkdirSync } from 'node:fs';
import { chromium, type Frame, type Page } from 'playwright-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';

const OUT = new URL('../.test-group/joint', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const t0 = Date.now();
const log = (s: string) => { const line = `${((Date.now() - t0) / 1000).toFixed(1).padStart(7)} s  ${s}`; console.log(line); appendFileSync(`${OUT}/browser.log`, line + '\n'); };
writeFileSync(`${OUT}/browser.log`, '');
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// The test site: test/serve.mjs on port 8799 (already running).
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'],
});
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
await sleep(1000);
const member = async () => ((await sw.evaluate(() => (globalThis as any).chrome.storage.local.get('member'))) as any).member;
async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
const note = (page: Page, content: string) => page.evaluate(`window.nostr.signEvent({ kind: 1, content: ${JSON.stringify(content)}, tags: [] })`).then(r => ({ ok: true as const, r: r as Event }), e => ({ ok: false as const, e: String(e.message ?? e) }));

try {
  await sw.evaluate(() => (globalThis as any).chrome.storage.local.set({ dev: true }));
  const pop = await ctx.newPage();
  await pop.goto(`chrome-extension://${sw.url().split('/')[2]}/popup.html`);
  await pop.click('#joinDevice');
  await pop.waitForFunction(() => (document.getElementById('joinDeviceCode')?.textContent ?? '').startsWith('cosign:join'), null, { timeout: 30_000 });
  const code = (await pop.textContent('#joinDeviceCode'))!;
  writeFileSync(`${OUT}/ext-join.txt`, code);
  log(`JOIN CODE ${code}`);

  while (!(await member())) await sleep(1000);
  const m1 = await member();
  log(`ADDED: shares ${m1.shares.map((s: any) => s.idx).join(', ')} of ${m1.group.npub ?? m1.group.groupX}, epoch ${m1.epoch ?? 0}`);
  await sleep(4000);

  const page = await ctx.newPage();
  await page.goto('http://localhost:8799/');
  const pk = page.evaluate('window.nostr.getPublicKey()');
  const allow = await card(page, 'sign in as');
  await page.waitForTimeout(700);
  await allow.click('#yes');
  await pk;
  const before = await note(page, 'joint test: before the refresh');
  log(before.ok && verifyEvent(before.r) && before.r.pubkey === m1.group.groupX ? `SIGNED before the refresh (${before.r.id.slice(0, 12)}…)` : `FAILED to sign before: ${before.ok ? 'bad signature' : before.e}`);

  log('waiting for a refresh (report a device lost on the phone)…');
  while (((await member())?.epoch ?? 0) < 1) await sleep(1000);
  const m2 = await member();
  log(`REFRESHED to epoch ${m2.epoch}; shares changed: ${m2.shares.every((s: any, i: number) => s.secret !== m1.shares[i].secret)}`);
  await sleep(4000);
  const after = await note(page, 'joint test: after the refresh');
  log(after.ok && verifyEvent(after.r) && after.r.pubkey === m1.group.groupX ? `SIGNED after the refresh, same npub (${after.r.id.slice(0, 12)}…). PASS` : `FAILED to sign after: ${after.ok ? 'bad signature' : after.e}`);
} catch (e) {
  log(`ERROR ${(e as Error).stack}`);
} finally {
  await ctx.close();
  process.exit(0);
}
