// The extension added to an existing key as a device, then following a refresh, in
// Chromium against a fresh weighted test group on this Mac (throwaway key):
//   Cosign's stand-in phone makes the key → vouches for the extension (its code) →
//   vouches for a stand-in tablet → the extension signs → the phone reports the tablet
//   lost → the extension follows the refresh to the new epoch → it still signs.
//
//   node build.mjs chrome && node --experimental-transform-types test/joined.ts

import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium, type Frame, type Page } from 'playwright-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';
import { decide } from './approve.ts';

const SERVER = `${process.env.HOME}/cosign/server`;
const { Pool, sealTo, KIND_MEMBER } = await import(`${SERVER}/net.ts`);
const DIR = new URL('../.test-group', import.meta.url).pathname;
const PHONE = `${DIR}/phone.json`, TABLET = `${DIR}/tablet.json`;
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const until = async (f: () => boolean | Promise<boolean>, ms: number) => { const end = Date.now() + ms; while (Date.now() < end) { if (await f()) return true; await sleep(300); } return false; };

// --- A fresh weighted group, and the stand-in phone making its key ------------------
const procs: ReturnType<typeof spawn>[] = [];
const killAll = () => { for (const p of procs) p.kill(); spawnSync('pkill', ['-f', `server.ts ${DIR}/`]); };
spawnSync('pkill', ['-f', `server.ts ${DIR}/`]);
spawnSync('node', ['setup.ts', '--dir', DIR, '--port', '8761', '--weighted', '--force'], { cwd: SERVER });
mkdirSync(`${DIR}/logs`, { recursive: true });
for (const s of ['a', 'b']) procs.push(spawn('node', ['server.ts', `${DIR}/${s}`], { cwd: SERVER, stdio: 'ignore' }));
await sleep(3000);
const code = (await (await fetch('http://localhost:8761/')).text()).match(/href="(cosign:pair\?[^"]+)"/)![1].replaceAll('&amp;', '&');
let phoneOut = '';
const phoneProc = spawn('node', ['test/fake-phone.ts', code], { cwd: SERVER, env: { ...process.env, PHONE_STATE: PHONE } });
procs.push(phoneProc);
phoneProc.stdout.on('data', d => { phoneOut += d; }); phoneProc.stderr.on('data', d => { phoneOut += d; });
check(await until(() => phoneOut.includes('made npub'), 60_000), 'the stand-in phone and both servers make a key');
await sleep(4000);
const phone = JSON.parse(readFileSync(PHONE, 'utf8'));
const serverA = phone.group.members.find((m: any) => m.role === 'server').pk;
const ctl = new Pool(phone.group.relays, () => {});
ctl.start([{ kinds: [KIND_MEMBER], '#p': ['0'.repeat(64)] }]);
await sleep(1500);
/** The phone vouches for a new device by its code, with its lock. */
const vouch = (joinUri: string) => {
  const u = new URL(joinUri.replace('cosign:', 'cosign://'));
  ctl.publish(sealTo(phone.sk, serverA, { type: 'add_device', id: Date.now().toString(16), pk: u.searchParams.get('pk'), name: u.searchParams.get('name'), relays: u.searchParams.getAll('r'), lock: true }));
};

// --- The browser ---------------------------------------------------------------------
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end('<h1>site</h1>'); }).listen(8791);
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
  await pop.waitForFunction(() => (document.getElementById('joinDeviceCode')?.textContent ?? '').startsWith('cosign:join'), null, { timeout: 20_000 });
  const joinUri = (await pop.textContent('#joinDeviceCode'))!;
  log('the extension shows its code; the phone vouches for it');
  vouch(joinUri);
  check(await until(async () => !!(await member()), 60_000), 'the extension is added: it holds shares of the key');
  const m1 = await member();
  check(m1?.shares.length === 3 && m1.group.groupX === phone.group.groupX, `3 shares of the same key (${m1?.shares.map((s: any) => s.idx).join(', ')})`);

  // A stand-in tablet joins too: that's the device we'll report lost.
  let tabOut = '';
  const tab = spawn('node', ['test/fake-device.ts', 'Test tablet'], { cwd: SERVER, env: { ...process.env, DEVICE_STATE: TABLET } });
  procs.push(tab);
  tab.stdout.on('data', d => { tabOut += d; });
  await until(() => tabOut.includes('JOIN '), 20_000);
  await sleep(2000);
  vouch(tabOut.split('JOIN ')[1].split('\n')[0]);
  check(await until(() => tabOut.includes('JOINED'), 60_000), 'a stand-in tablet is added too');
  await sleep(4000);

  const page = await ctx.newPage();
  await page.goto('http://localhost:8791/');
  const pk = page.evaluate('window.nostr.getPublicKey()');
  await card(page, 'asking in its own window');
  await decide(ctx);
  await pk;
  const before = await note(page, 'before the refresh');
  check(before.ok && verifyEvent(before.r) && before.r.pubkey === phone.group.groupX, `the extension signs (browser + a server)${before.ok ? '' : ': ' + before.e}`);

  // The phone reports the tablet lost: everyone left refreshes to epoch 1.
  const tabletIdx = JSON.parse(readFileSync(TABLET, 'utf8')).idx;
  const me = (await import('nostr-tools/pure')).getPublicKey(Buffer.from(phone.sk, 'hex'));
  ctl.publish(sealTo(phone.sk, me, { type: 'test_report', idx: tabletIdx }));
  log(`the phone reports the tablet (member ${tabletIdx}) lost`);
  check(await until(async () => ((await member())?.epoch ?? 0) >= 1, 90_000), 'the extension follows the refresh to epoch 1');
  const m2 = await member();
  check(m2.shares.every((s: any, i: number) => s.secret !== m1.shares[i].secret), 'its shares changed');
  await sleep(4000);
  const after = await note(page, 'after the refresh');
  check(after.ok && verifyEvent(after.r) && after.r.pubkey === phone.group.groupX, `it still signs, same npub${after.ok ? '' : ': ' + after.e}`);
  check(await until(() => tabOut.includes('REMOVED'), 10_000), 'the tablet was told it was removed');
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  await ctx.close(); http.close(); ctl.close?.(); killAll();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
