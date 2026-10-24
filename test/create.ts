// "Create account" end to end, in Chromium: two shared Cosign servers (from
// ~/cosign/server, open enrollment, counted as two operators) and a site that asks for
// Nostr. The card leads with Create account; a name, and this browser and both servers
// make a key nobody holds (browser 3 shares, each server 1, 4 to sign). Then it signs
// with either server, and the profile is published.
//
//   node build.mjs chrome && node --experimental-transform-types test/create.ts

import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, openSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';
import { SimplePool } from 'nostr-tools/pool';

const SERVER = `${process.env.HOME}/cosign/server`;
const DIR = new URL('../.test-shared', import.meta.url).pathname;
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// --- Two shared servers -----------------------------------------------------------
const kill = () => spawnSync('pkill', ['-f', `server.ts --root ${DIR}/`]);
kill();
rmSync(DIR, { recursive: true, force: true });
mkdirSync(`${DIR}/logs`, { recursive: true });
const hosts = ['h1', 'h2'];
const procs = new Map<string, ReturnType<typeof spawn>>();
const start = (h: string, port: number) => {
  const l = openSync(`${DIR}/logs/${h}.log`, 'a');
  procs.set(h, spawn('node', ['server.ts', '--root', `${DIR}/${h}`, '--port', String(port)], { cwd: SERVER, env: { ...process.env, COSIGN_ENROLL: 'open' }, stdio: ['ignore', l, l] }));
};
start('h1', 8991); start('h2', 8992);
await sleep(4000);
const host = (h: string) => JSON.parse(readFileSync(`${DIR}/${h}/host.json`, 'utf8'));
// Two made-up operators, so the extension takes both (never two from one operator).
const seeds = hosts.map((h, i) => ({ pubkey: host(h).pk, relays: host(h).relays, operator: String(i + 1).repeat(64) }));

const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8794);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: !process.env.HEADED, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, ...(process.env.HEADED ? [] : ['--headless=new'])],
});
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
await sleep(1000);
await sw.evaluate(s => (globalThis as any).chrome.storage.local.set({ devSeeds: s }), seeds);

async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
const call = (page: Page, expr: string) => page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e).split('\n')[0].replace(/^.*Error: /, '') }));

try {
  const page = await ctx.newPage();
  await page.goto('http://localhost:8794/');
  const pk = call(page, 'window.nostr.getPublicKey()');
  const c = await card(page, 'Create account');
  await page.screenshot({ path: 'shots/create-1-card.png' });
  await c.click('.create');
  await c.fill('.name', 'Test Person');
  await page.screenshot({ path: 'shots/create-2-name.png' });
  const t = Date.now();
  await c.click('.go');
  const got = await Promise.race([pk, sleep(90_000).then(() => ({ ok: false as const, e: 'no answer in 90 s' }))]);
  const member = (await sw.evaluate(() => chrome.storage.local.get('member')) as any).member;
  check(got.ok && got.r === member?.group.groupX, `created: the site gets the new key (${Date.now() - t} ms)${got.ok ? '' : ': ' + got.e}`);
  check(member?.shares.length === 3 && member.group.members.filter((m: any) => m.role === 'server').length === 2, 'this browser holds 3 shares; two servers hold 1 each');
  await sleep(3000);
  const groups = hosts.map(h => readdirSync(`${DIR}/${h}/groups`).map(g => JSON.parse(readFileSync(`${DIR}/${h}/groups/${g}/state.json`, 'utf8'))).find(s => s.group.groupX === member.group.groupX));
  check(groups.every(g => g && g.self.shares.length === 1 && !g.signer), 'each server has the key, one share, and no signer key (it serves no sites itself)');

  const ev = await call(page, `window.nostr.signEvent({ kind: 1, content: 'my first note', tags: [] })`);
  check(ev.ok && verifyEvent(ev.r as Event), `signs at once (no Allow card: you started from this site)${ev.ok ? '' : ': ' + ev.e}`);

  const pool = new SimplePool();
  const profile = await pool.get(member.group.relays, { kinds: [0], authors: [member.group.groupX] }, { maxWait: 5000 });
  pool.destroy();
  check(!!profile && JSON.parse(profile.content).name === 'Test Person', `profile published${profile ? '' : ' (not found on the relays)'}`);

  procs.get('h1')!.kill();
  await sleep(1000);
  const t2 = Date.now();
  const ev2 = await call(page, `window.nostr.signEvent({ kind: 1, content: 'one server down', tags: [] })`);
  check(ev2.ok && verifyEvent(ev2.r as Event), `a server down: the other signs (${Date.now() - t2} ms)${ev2.ok ? '' : ': ' + ev2.e}`);

  const pop = await ctx.newPage();
  await pop.setViewportSize({ width: 340, height: 520 });
  await pop.goto(`chrome-extension://${sw.url().split('/')[2]}/popup.html`);
  await pop.waitForTimeout(1500);
  await pop.screenshot({ path: 'shots/create-3-popup.png' });
  check((await pop.textContent('#name'))?.includes('Test Person') ?? false, 'the popup shows your name');
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  await ctx.close(); http.close(); kill();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
