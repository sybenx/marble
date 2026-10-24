// Phase 2 end to end: this browser as a device holding 3 of 4 points, in Chromium,
// against a fresh weighted test group (two Cosign servers from ~/cosign/server on port
// 8761, a throwaway key nobody ever holds). The extension joins by the server's code,
// then signs with a server, without any phone.
//
//   node build.mjs chrome && node --experimental-transform-types test/member.ts

import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, openSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { finalizeEvent, verifyEvent, type Event } from 'nostr-tools/pure';
import { Relay } from 'nostr-tools/relay';
import * as nip44 from 'nostr-tools/nip44';
import { hexToBytes } from '@noble/hashes/utils.js';

const SERVER = `${process.env.HOME}/cosign/server`;
const DIR = new URL('../.test-group', import.meta.url).pathname;
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const state = (s: string) => JSON.parse(readFileSync(`${DIR}/${s}/state.json`, 'utf8'));

// --- A fresh weighted group ------------------------------------------------------
const kill = () => spawnSync('pkill', ['-f', `server.ts ${DIR}/`]);
kill();
spawnSync('node', ['setup.ts', '--dir', DIR, '--port', '8761', '--weighted', '--force'], { cwd: SERVER });
mkdirSync(`${DIR}/logs`, { recursive: true });
const procs = new Map<string, ReturnType<typeof spawn>>();
const start = (s: string) => { const l = openSync(`${DIR}/logs/${s}.log`, 'a'); procs.set(s, spawn('node', ['server.ts', `${DIR}/${s}`], { cwd: SERVER, stdio: ['ignore', l, l] })); };
start('a'); start('b');
await sleep(3000);
const html = await (await fetch('http://localhost:8761/')).text();
const code = html.match(/href="(cosign:pair\?[^"]+)"/)![1].replaceAll('&amp;', '&');

// --- Two test sites --------------------------------------------------------------
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8795);
const SITE1 = 'http://localhost:8795/', SITE2 = 'http://127.0.0.1:8795/';

const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: !process.env.HEADED, args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, ...(process.env.HEADED ? [] : ['--headless=new'])],
});
const sw = ctx.serviceWorkers().find(w => w.url().endsWith('/background.js')) ?? await ctx.waitForEvent('serviceworker', w => w.url().endsWith('/background.js'));
await sleep(1000);
const extId = sw.url().split('/')[2];

async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
const call = (page: Page, expr: string) => page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e).split('\n')[0].replace(/^.*Error: /, '') }));
const note = (page: Page, content: string, extra = '') => call(page, `window.nostr.signEvent({ kind: 1, content: ${JSON.stringify(content)}, tags: [] ${extra} })`);

try {
  // 1. Join by the server's code, in the popup.
  await sw.evaluate(() => (globalThis as any).chrome.storage.local.set({ dev: true }));
  const pop = await ctx.newPage();
  await pop.goto(`chrome-extension://${extId}/popup.html`);
  await pop.click('#joinOpen');
  await pop.fill('#joinCode', code);
  let t = Date.now();
  await pop.click('#joinGo');
  await pop.waitForFunction(() => /Done|n’t|isn/.test(document.getElementById('joinStatus')!.textContent ?? ''), null, { timeout: 90_000 });
  const status = await pop.textContent('#joinStatus');
  check(status === 'Done.', `joined: the browser and both servers made a key (${Date.now() - t} ms)${status === 'Done.' ? '' : ': ' + status}`);
  await sleep(5000); // let the servers finish too
  const [a, b] = [state('a'), state('b')];
  const member = (await sw.evaluate(() => chrome.storage.local.get('member')) as any).member;
  check(!!member && a.group.groupX === member.group.groupX && b.group.groupX === member.group.groupX, `same key everywhere: ${member?.group.npub?.slice(0, 16)}…`);
  check(member?.shares.length === 3 && a.self.shares.length === 1 && b.self.shares.length === 1, 'this browser holds 3 shares, each server 1');
  const groupX = member.group.groupX;

  // 2. A site: Allow, then sign with no phone anywhere.
  const page = await ctx.newPage();
  await page.goto(SITE1);
  const pk = call(page, 'window.nostr.getPublicKey()');
  const allow = await card(page, 'Let localhost:8795 sign in as');
  await page.waitForTimeout(700);
  await allow.click('#yes');
  const got = await pk;
  check(got.ok && got.r === groupX, 'getPublicKey → the new key');
  t = Date.now();
  const ev = await note(page, 'signed by this browser and one server');
  check(ev.ok && verifyEvent(ev.r as Event) && (ev.r as Event).pubkey === groupX, `signEvent: browser (3) + a server (1) → valid (${Date.now() - t} ms)${ev.ok ? '' : ': ' + ev.e}`);
  const times: number[] = [];
  for (let i = 0; i < 5; i++) { const s = Date.now(); const r = await note(page, `again ${i}`); if (r.ok) times.push(Date.now() - s); }
  check(times.length === 5, `5 more: ${times.join(', ')} ms`);

  // 3. Encrypt and decrypt to yourself, by threshold ECDH.
  const enc = await call(page, `window.nostr.nip44.encrypt('${groupX}', 'secret')`);
  const dec = enc.ok ? await call(page, `window.nostr.nip44.decrypt('${groupX}', ${JSON.stringify(enc.r)})`) : enc;
  check(dec.ok && dec.r === 'secret', `nip44 round trip${dec.ok ? '' : ': ' + dec.e}`);
  const enc4 = await call(page, `window.nostr.nip04.encrypt('${groupX}', 'old style')`);
  const dec4 = enc4.ok ? await call(page, `window.nostr.nip04.decrypt('${groupX}', ${JSON.stringify(enc4.r)})`) : enc4;
  check(dec4.ok && dec4.r === 'old style', `nip04 round trip${dec4.ok ? '' : ': ' + dec4.e}`);

  // 4. Something destructive: a follow list emptied waits for this device's lock.
  const follows = Array.from({ length: 30 }, (_, i) => `['p', '${(i + 1).toString(16).padStart(64, '0')}']`).join(',');
  const f1 = await call(page, `window.nostr.signEvent({ kind: 3, content: '', tags: [${follows}] })`);
  check(f1.ok, `a follow list of 30 signs at once${f1.ok ? '' : ': ' + f1.e}`);
  await sleep(2000); // the servers archive it from phone_signed
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true } });
  const wipe = call(page, `window.nostr.signEvent({ kind: 3, content: '', tags: [] })`);
  const approve = await card(page, 'wants to make a big change', 30_000);
  log(`approval card: "${(await approve.textContent('.who'))?.trim()}"`);
  await page.waitForTimeout(700);
  await approve.click('#yes');
  const w = await wipe;
  check(w.ok && verifyEvent(w.r as Event), `approved with the device lock → signed${w.ok ? '' : ': ' + w.e}`);

  // 5. A second site.
  const page2 = await ctx.newPage();
  await page2.goto(SITE2);
  const p2 = call(page2, 'window.nostr.getPublicKey()');
  const allow2 = await card(page2, 'Let 127.0.0.1:8795 sign in as');
  await page2.waitForTimeout(700);
  await allow2.click('#yes');
  await p2;
  const ev2 = await note(page2, 'second site');
  check(ev2.ok && verifyEvent(ev2.r as Event), `second site signs${ev2.ok ? '' : ': ' + ev2.e}`);

  // 6. Server A goes down: server B answers instead.
  procs.get('a')!.kill();
  await sleep(1000);
  t = Date.now();
  const ev3 = await note(page, 'with server A down');
  check(ev3.ok && verifyEvent(ev3.r as Event), `server A down: server B signs (${Date.now() - t} ms)${ev3.ok ? '' : ': ' + ev3.e}`);
  start('a');
  await sleep(3000);

  // 7. Remove the second site from a server (as if on the server page): it stops; the first doesn't.
  const sites = (await sw.evaluate(() => chrome.storage.local.get('sites')) as any).sites;
  const { getPublicKey } = await import('nostr-tools/pure');
  const sitePk = getPublicKey(hexToBytes(sites['http://127.0.0.1:8795'].sk));
  const relays = await Promise.all(member.group.relays.map((u: string) => Relay.connect(u).catch(() => null)));
  for (const [from, to] of [[b, a], [a, b]]) {
    const toPk = to.group.members.find((m: any) => m.idx === to.self.idx).pk;
    const ck = nip44.v2.utils.getConversationKey(hexToBytes(from.self.sk), toPk);
    const e = finalizeEvent({ kind: 28001, created_at: Math.floor(Date.now() / 1000), tags: [['p', toPk]], content: nip44.v2.encrypt(JSON.stringify({ type: 'revoke', pk: sitePk }), ck) }, hexToBytes(from.self.sk));
    for (const r of relays) r?.publish(e).catch(() => {});
  }
  await sleep(3000);
  const gone = await note(page2, 'should not sign');
  check(!gone.ok && /removed/.test(gone.e), `removed site refused (${gone.ok ? 'it signed!' : gone.e})`);
  const still = await note(page, 'first site still here');
  check(still.ok, 'the other site still signs');
  for (const r of relays) r?.close();
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  await ctx.close(); http.close(); kill();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
