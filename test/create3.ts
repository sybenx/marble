// Create account against three servers on this Mac (like the three public ones), in
// Chromium: with one down, the account starts with the other two; with only one up,
// there's no one-server account: it falls back to "Not protected yet" (option b).
//
//   node build.mjs chrome && node --experimental-transform-types test/create3.ts

import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { mkdirSync, openSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';

const SERVER = `${process.env.HOME}/cosign/server`;
const DIR = new URL('../.test-three', import.meta.url).pathname;
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const kill = () => spawnSync('pkill', ['-f', `server.ts --root ${DIR}/`]);
kill(); rmSync(DIR, { recursive: true, force: true }); mkdirSync(`${DIR}/logs`, { recursive: true });
const procs = new Map<string, ChildProcess>();
const start = (h: string, port: number) => { const l = openSync(`${DIR}/logs/${h}.log`, 'a'); procs.set(h, spawn('node', ['server.ts', '--root', `${DIR}/${h}`, '--port', String(port)], { cwd: SERVER, env: { ...process.env, COSIGN_ENROLL: 'open' }, stdio: ['ignore', l, l] })); };
const hosts = [['h1', 8961], ['h2', 8962], ['h3', 8963]] as const;
for (const [h, p] of hosts) start(h, p);
await sleep(4000);
const host = (h: string) => JSON.parse(readFileSync(`${DIR}/${h}/host.json`, 'utf8'));
const seeds = hosts.map(([h], i) => ({ pubkey: host(h).pk, relays: host(h).relays, operator: String(i + 1).repeat(64) }));
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8786);
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const exe = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;

async function createWith(label: string): Promise<{ member?: any; pending?: any; note?: { ok: boolean; r?: Event; e?: string } }> {
  const ctx = await chromium.launchPersistentContext('', { executablePath: exe, headless: true, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new'] });
  try {
    const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
    await sleep(1000);
    await sw.evaluate(s => (globalThis as any).chrome.storage.local.set({ devSeeds: s, devNoPublish: true }), seeds);
    const page = await ctx.newPage();
    await page.goto('http://localhost:8786/');
    const pk = page.evaluate('window.nostr.getPublicKey()').catch(() => null);
    let c: Frame | undefined;
    for (let i = 0; i < 60 && !c; i++) { for (const f of page.frames()) if (f.url().includes('/card.html') && (await f.textContent('body').catch(() => ''))?.includes('Create account')) c = f; if (!c) await sleep(250); }
    await c!.click('.create'); await c!.fill('.name', label); await c!.click('.go');
    await Promise.race([pk, sleep(90_000)]);
    await sleep(3000);
    const s = (await sw.evaluate(() => (globalThis as any).chrome.storage.local.get(['member', 'pending']))) as any;
    const note = await Promise.race([page.evaluate(`window.nostr.signEvent({ kind: 1, content: 'hi', tags: [] })`).then(r => ({ ok: true, r: r as Event }), e => ({ ok: false, e: String(e.message ?? e) })), sleep(45_000).then(() => ({ ok: false, e: 'no answer in 45 s' }))]);
    return { ...s, note };
  } finally { await ctx.close(); }
}

try {
  procs.get('h3')!.kill();
  await sleep(1500);
  const a = await createWith('Two of three');
  const servers = a.member?.group.members.filter((m: any) => m.role === 'server') ?? [];
  check(!!a.member && !a.pending && servers.length === 2, `one server down: the account starts with the other two (${servers.length} servers)`);
  check(!!a.note?.ok && verifyEvent(a.note.r!) && a.note.r!.pubkey === a.member?.group.groupX, `it signs with them${a.note?.ok ? '' : ': ' + a.note?.e}`);

  procs.get('h2')!.kill();
  await sleep(1500);
  const b = await createWith('Only one up');
  check(!b.member && !!b.pending, 'only one server up: no one-server account; "Not protected yet" instead');
  check(!!b.note?.ok && verifyEvent(b.note.r!), `and it still signs, on its own key${b.note?.ok ? '' : ': ' + b.note?.e}`);
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  http.close(); kill();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
