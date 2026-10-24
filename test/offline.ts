// Create account while no server answers, in Chromium: the key is made here, kept wrapped,
// and signs at once ("Not protected yet"); when the servers come back it's dealt to them
// (this browser 3 shares, each server 1) and the whole key is wiped; it then signs with a
// server, same npub.
//
//   node build.mjs chrome && node --experimental-transform-types test/offline.ts

import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, openSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';

const SERVER = `${process.env.HOME}/cosign/server`;
const DIR = new URL('../.test-offline', import.meta.url).pathname;
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

const kill = () => spawnSync('pkill', ['-f', `server.ts --root ${DIR}/`]);
kill(); rmSync(DIR, { recursive: true, force: true }); mkdirSync(`${DIR}/logs`, { recursive: true });
const start = (h: string, port: number) => { const l = openSync(`${DIR}/logs/${h}.log`, 'a'); return spawn('node', ['server.ts', '--root', `${DIR}/${h}`, '--port', String(port)], { cwd: SERVER, env: { ...process.env, COSIGN_ENROLL: 'open' }, stdio: ['ignore', l, l] }); };
// Start them once for their host keys, then take them down.
start('h1', 8951); start('h2', 8952);
await sleep(4000);
const host = (h: string) => JSON.parse(readFileSync(`${DIR}/${h}/host.json`, 'utf8'));
const seeds = ['h1', 'h2'].map((h, i) => ({ pubkey: host(h).pk, relays: host(h).relays, operator: String(i + 5).repeat(64) }));
kill();
await sleep(1000);

const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8787);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'],
});
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
await sleep(1000);
await sw.evaluate(s => (globalThis as any).chrome.storage.local.set({ devSeeds: s, devNoPublish: true }), seeds);
const stored = async () => (await sw.evaluate(() => (globalThis as any).chrome.storage.local.get(['pending', 'member']))) as any;
async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
const note = (page: Page, c: string) => Promise.race([page.evaluate(`window.nostr.signEvent({ kind: 1, content: ${JSON.stringify(c)}, tags: [] })`).then(r => ({ ok: true as const, r: r as Event }), e => ({ ok: false as const, e: String(e.message ?? e) })), sleep(45_000).then(() => ({ ok: false as const, e: 'no answer in 45 s' }))]);

try {
  const page = await ctx.newPage();
  await page.goto('http://localhost:8787/');
  const pk = page.evaluate('window.nostr.getPublicKey()');
  const c = await card(page, 'Create account');
  await c.click('.create'); await c.fill('.name', 'Offline Person');
  const t = Date.now();
  await c.click('.go');
  const got = await Promise.race([pk, sleep(60_000).then(() => 'timeout')]);
  const s1 = await stored();
  check(!!s1.pending && !s1.member && got === s1.pending.pubkey, `no servers: an account anyway, made here (${Date.now() - t} ms)`);
  check(!!s1.pending && !JSON.stringify(s1.pending).match(/"secret"/) && s1.pending.wrapped.length > 64, 'the key is kept wrapped, not as plain text');
  const ev = await note(page, 'signed while not protected yet');
  check(ev.ok && verifyEvent(ev.r) && ev.r.pubkey === s1.pending.pubkey, `it signs at once${ev.ok ? '' : ': ' + ev.e}`);
  const pop = await ctx.newPage();
  await pop.goto(`chrome-extension://${sw.url().split('/')[2]}/popup.html`);
  await pop.waitForTimeout(1500);
  check(await pop.isVisible('#pending') && (await pop.textContent('#pendingTitle')) === 'Not protected yet', 'the popup says "Not protected yet"');
  check(!(await pop.isVisible('#signOut')), 'no sign-out or export while the whole key is here');

  // The servers come back: the key is dealt to them and wiped here.
  start('h1', 8951); start('h2', 8952);
  await sleep(4000);
  const t2 = Date.now();
  let s2: any = {};
  while (Date.now() - t2 < 120_000) { await pop.reload(); await sleep(3000); s2 = await stored(); if (s2.member && !s2.pending) break; }
  check(!!s2.member && !s2.pending, `servers back: the key is dealt and the whole key is gone (${Date.now() - t2} ms)`);
  check(s2.member?.group.groupX === s1.pending.pubkey && s2.member?.shares.length === 3, 'same npub; this browser now holds 3 shares');
  await sleep(4000);
  const ev2 = await note(page, 'signed with a server now');
  check(ev2.ok && verifyEvent(ev2.r) && ev2.r.pubkey === s1.pending.pubkey, `it signs with a server, same npub${ev2.ok ? '' : ': ' + ev2.e}`);
  await pop.reload(); await pop.waitForTimeout(1500);
  check(!(await pop.isVisible('#pending')), 'the popup no longer says "Not protected yet"');
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  await ctx.close(); http.close(); kill();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
