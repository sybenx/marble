// The extension end to end, in Chromium, against running Cosign 0.4.0 servers.
//
// A stand-in phone answers the extension's sign-in code: it tells server A about the
// ticket (as server B, which A also accepts tickets from) and hands the ticket over by
// QRST, exactly as the Cosign app does after you tap Connect.
//
//   (Cosign servers running from ~/cosign/server: node server.ts data/a, data/b)
//   node build.mjs chrome && node --experimental-transform-types test/e2e.ts

import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { finalizeEvent, verifyEvent, type Event } from 'nostr-tools/pure';
import { Relay } from 'nostr-tools/relay';
import * as nip44 from 'nostr-tools/nip44';
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils.js';
import { Store, Transfer, browserEnv } from '../vendor/qrst-web/index.ts';
import { parseUri } from '../vendor/qrst-core/index.ts';
import { signerTicket, type Ticket } from '../src/ticket.ts';
import { decide } from './approve.ts';

const SERVERS = process.env.COSIGN_SERVERS ?? `${process.env.HOME}/cosign/server/data`;
const A = JSON.parse(readFileSync(`${SERVERS}/a/state.json`, 'utf8'));
const B = JSON.parse(readFileSync(`${SERVERS}/b/state.json`, 'utf8'));
const group = A.group;
const serverA = group.members.find((m: any) => m.idx === A.self.idx).pk;
const signerPk = A.signer.pk;
const t0 = Date.now(), ms = () => `${((Date.now() - t0) / 1000).toFixed(1)} s`;
const log = (s: string) => console.log(`${ms().padStart(7)}  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };

// --- Talking to server A as server B -------------------------------------------
const relays = await Promise.all(group.relays.map((u: string) => Relay.connect(u).catch(() => null)));
function asB(msg: object) {
  const ck = nip44.v2.utils.getConversationKey(hexToBytes(B.self.sk), serverA);
  const e = finalizeEvent({ kind: 28001, created_at: Math.floor(Date.now() / 1000), tags: [['p', serverA]], content: nip44.v2.encrypt(JSON.stringify(msg), ck) }, hexToBytes(B.self.sk));
  for (const r of relays) r?.publish(e).catch(() => {});
}

// --- Two test sites (two origins, one server) -----------------------------------
const PAGE = `<!doctype html><title>test site</title><h1>test site</h1>`;
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end(PAGE); }).listen(8799);
const SITE1 = 'http://localhost:8799/', SITE2 = 'http://127.0.0.1:8799/';

// --- The browser ---------------------------------------------------------------
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const ctx = await chromium.launchPersistentContext('', {
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  headless: !process.env.HEADED,
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, ...(process.env.HEADED ? [] : ['--headless=new'])],
});

async function cardFrame(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) {
      const text = await f.textContent('body').catch(() => '');
      if (text?.includes(want)) return f;
    }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}

const call = (page: Page, expr: string) => page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e) }));

try {
  const page = await ctx.newPage();
  await page.goto(SITE1);
  check(await page.evaluate('typeof window.nostr?.signEvent === "function"'), 'window.nostr is there');

  // 1. Not signed in: the site asks, a sign-in card appears with a code.
  const pk = call(page, 'window.nostr.getPublicKey()');
  const card = await cardFrame(page, 'Sign in to localhost:8799');
  log('sign-in card is showing');
  await card.click('.phone');
  await card.waitForSelector('.qr svg', { timeout: 30_000 });
  const uri = await card.$eval('.qr', el => (el as HTMLElement).dataset.uri!);
  const params = parseUri(uri);
  check(params.profile === 'signer-ticket' && params.check === 'none', `code is a signer-ticket (origin claim ${params.origin})`);

  // 2. The stand-in phone scans it and taps Connect.
  const ticket: Ticket = { v: 1, pubkey: group.groupX, signer: signerPk, relays: group.relays, t: bytesToHex(randomBytes(16)), expires: Math.floor(Date.now() / 1000) + 900 };
  asB({ type: 'ticket', t: ticket.t, origin: params.origin, expires: ticket.expires });
  const mem = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) }; };
  const phone = Transfer.join({ profile: signerTicket, store: new Store(mem(), () => Math.floor(Date.now() / 1000)), env: browserEnv(), baseUrl: '', origin: '', check: 'none' }, params, { viaCamera: true }, ticket);
  let released = false;
  phone.onChange = () => { const v = phone.view(); if (v.session.phase === 'release' && !released) { released = true; log('phone: Connect tapped'); phone.release(); } };

  // 3. The card asks "Sign in as …?"; tap Sign in.
  const confirm = await cardFrame(page, 'Sign in as', 40_000);
  await confirm.click('.yes');
  log('card: Sign in tapped');
  // The spent code must not come back while signing in finishes.
  let flashed = false;
  const watch = (async () => { for (let i = 0; i < 40; i++) { if (await confirm.$eval('.qr', el => !(el as HTMLElement).hidden).catch(() => false)) flashed = true; await page.waitForTimeout(50); } })();
  const got = await pk;
  await watch.catch(() => {});
  check(!flashed, 'the code stays hidden after Sign in');
  check(got.ok && got.r === group.groupX, `getPublicKey → ${got.ok ? String(got.r).slice(0, 12) : got.e}`);
  phone.dispose();

  // 4. Sign something.
  let t = Date.now();
  const ev = await call(page, `window.nostr.signEvent({ kind: 1, content: 'hello from the Cosign extension', tags: [], created_at: Math.floor(Date.now()/1000) })`);
  check(ev.ok && verifyEvent(ev.r as Event) && (ev.r as Event).pubkey === group.groupX, `signEvent → valid signature (${Date.now() - t} ms)${ev.ok ? '' : ' ' + ev.e}`);

  // 5. Encrypt and decrypt to yourself.
  const enc = await call(page, `window.nostr.nip44.encrypt('${group.groupX}', 'secret')`);
  const dec = enc.ok ? await call(page, `window.nostr.nip44.decrypt('${group.groupX}', ${JSON.stringify(enc.r)})`) : enc;
  check(dec.ok && dec.r === 'secret', `nip44 round trip${dec.ok ? '' : ' ' + dec.e}`);

  // 6. A second site asks "Let … sign in as you?"; Not now refuses, Allow works.
  const page2 = await ctx.newPage();
  await page2.goto(SITE2);
  const p2 = call(page2, 'window.nostr.getPublicKey()');
  await cardFrame(page2, 'asking in its own window');
  await page2.waitForTimeout(700); // buttons wait a moment
  await page2.screenshot({ path: 'shots/desktop-allow-card.png' });
  await decide(ctx);
  const got2 = await p2;
  check(got2.ok && got2.r === group.groupX, 'second site: Allow → getPublicKey');
  t = Date.now();
  const ev2 = await call(page2, `window.nostr.signEvent({ kind: 1, content: 'from the second site', tags: [] })`);
  check(ev2.ok && verifyEvent(ev2.r as Event), `second site signs (${Date.now() - t} ms)${ev2.ok ? '' : ' ' + ev2.e}`);

  const id = ctx.serviceWorkers()[0].url().split('/')[2];
  const pop = await ctx.newPage();
  await pop.setViewportSize({ width: 340, height: 520 });
  await pop.goto(`chrome-extension://${id}/popup.html`);
  await pop.waitForTimeout(2500);
  await pop.screenshot({ path: 'shots/desktop-popup-signed-in.png' });

  // 7. Remove one site "on the phone": only that site stops.
  const store = await (await ctx.serviceWorkers()[0]).evaluate(() => chrome.storage.local.get(['pairing', 'sites'])) as any;
  const { getPublicKey } = await import('nostr-tools/pure');
  const pkOf = (k: string) => getPublicKey(hexToBytes(k));
  check(!!store.pairing.perSite, `a grant per site (${Object.keys(store.sites).length} sites)`);
  asB({ type: 'revoke', pk: pkOf(store.sites[SITE2.slice(0, -1)].sk) });
  await page.waitForTimeout(3000);
  const gone = await call(page2, `window.nostr.signEvent({ kind: 1, content: 'should not sign', tags: [] })`);
  check(!gone.ok && /was removed on your phone/.test(gone.e), `removed site refused (${gone.ok ? 'it signed!' : gone.e.split('\n')[0].replace(/^.*Error: /, '')})`);
  const still = await call(page, `window.nostr.signEvent({ kind: 1, content: 'still here', tags: [] })`);
  check(still.ok && verifyEvent(still.r as Event), 'the other site still signs');
  const p4 = call(page2, 'window.nostr.getPublicKey()');
  await cardFrame(page2, '127.0.0.1:8799 wants to sign in');
  check(true, 'removed site asks Allow again');
  void p4;

  // 8. Remove the whole browser "on the phone": every site stops, and it's signed out.
  asB({ type: 'revoke', pk: pkOf(store.pairing.sk) });
  await page.waitForTimeout(3000);
  const after = await call(page, `window.nostr.signEvent({ kind: 1, content: 'should not sign', tags: [] })`);
  check(!after.ok && /browser was removed/i.test(after.e), `browser removed: refused (${after.ok ? 'it signed!' : after.e.split('\n')[0].replace(/^.*Error: /, '')})`);
  const p3 = call(page, 'window.nostr.getPublicKey()');
  await cardFrame(page, 'Sign in to localhost:8799');
  check(true, 'browser removed: the site gets the sign-in card again');
  void p3;
} catch (e) {
  failed++;
  log(`FAIL ${(e as Error).stack}`);
} finally {
  await ctx.close();
  http.close();
  for (const r of relays) r?.close();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
