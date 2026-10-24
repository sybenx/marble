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
(sw as any).on?.('console', (m: any) => { if (/error|Error|warn/i.test(m.type() + m.text())) log(`[background] ${m.type()}: ${m.text().slice(0, 300)}`); });
ctx.on('weberror', e => log(`[weberror] ${e.error().message.slice(0, 300)}`));
// Seeds for the two test servers; and sign the profile without posting it publicly.
await sw.evaluate(s => (globalThis as any).chrome.storage.local.set({ devSeeds: s, devNoPublish: true }), seeds);

async function card(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) { const t = await f.textContent('body').catch(() => ''); if (t?.includes(want)) return f; }
    await page.waitForTimeout(250);
  }
  throw new Error(`no card saying "${want}"`);
}
const call = (page: Page, expr: string, ms = 45_000) => Promise.race([
  page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e).split('\n')[0].replace(/^.*Error: /, '') })),
  sleep(ms).then(() => ({ ok: false as const, e: `no answer in ${ms / 1000} s` })),
]);

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
  if (!ev.ok) log(`device status: ${JSON.stringify(await sw.evaluate(() => (globalThis as any).__cosignStatus?.()))}`);
  check(ev.ok && verifyEvent(ev.r as Event), `signs at once (no Allow card: you started from this site)${ev.ok ? '' : ': ' + ev.e}`);

  await sleep(2000);
  const after = (await sw.evaluate(() => (globalThis as any).chrome.storage.local.get('member')) as any).member;
  const pe = after?.profileEvent;
  check(!!pe && verifyEvent(pe) && pe.kind === 0 && JSON.parse(pe.content).name === 'Test Person', 'profile signed (not posted: a test)');

  // The next step: a paper backup, offered on the page and kept up in the popup.
  await card(page, 'One more step', 10_000).then(() => check(true, 'right after: "One more step: a paper backup"'), () => check(false, 'right after: "One more step: a paper backup"'));
  const extId = sw.url().split('/')[2];
  const pop0 = await ctx.newPage();
  await pop0.goto(`chrome-extension://${extId}/popup.html`);
  await pop0.waitForTimeout(1500);
  check(await pop0.isVisible('#safety'), 'the popup keeps a "Keep your account safe" card');
  await pop0.close();
  await sw.evaluate(() => (globalThis as any).chrome.storage.local.set({ dev: true })); // shows the 24 words too
  const safety = await ctx.newPage();
  await safety.goto(`chrome-extension://${extId}/safety.html#paper`);
  const cdp = await ctx.newCDPSession(safety);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true } });
  await safety.click('#makePaper');
  await safety.waitForSelector('#paperDone:not([hidden])', { timeout: 60_000 }).catch(() => {});
  const paper = await safety.textContent('#paperCode').catch(() => '');
  check(!!paper?.startsWith('cosign:backup?k='), `a paper backup: ${paper ? paper.slice(0, 40) + '…' : (await safety.textContent('#paperStatus'))}`);
  await safety.screenshot({ path: 'shots/safety-words.png' });
  // 24 words that are the backup's key (behind the dev flag until Cosign restores from words).
  await safety.waitForSelector('#keepWords:not([hidden])', { timeout: 5000 }).catch(() => {});
  await safety.click('#keepWords');
  const words = await safety.$$eval('#words li', els => els.map(e => e.textContent!.trim()));
  const { mnemonicToEntropy } = await import('@scure/bip39');
  const { wordlist } = await import('@scure/bip39/wordlists/english.js');
  const kOf = (code: string) => new URL(code.replace('cosign:', 'cosign://')).searchParams.get('k');
  check(words.length === 24 && Buffer.from(mnemonicToEntropy(words.join(' '), wordlist)).toString('hex') === kOf(paper ?? ''), '24 words, and they are the backup key');
  await safety.click('#wroteThem');
  for (const el of await safety.$$('#checks input')) await el.fill(words[Number(await el.getAttribute('data-i'))]);
  await safety.click('#checkGo');
  check(((await safety.textContent('#wordsStatus')) ?? '').startsWith('Those match'), 'checking three words: they match');
  await safety.click('#keepQr');
  const dl = safety.waitForEvent('download', { timeout: 10_000 }).catch(() => null);
  await safety.click('#saveImg');
  check(!!(await dl), 'the QR saves as an image');
  await safety.screenshot({ path: 'shots/safety-paper.png' });
  // Read it back the way Cosign does, from what a server keeps for the paper.
  if (paper) {
    await sleep(2000);
    const { restoreShares } = await import(`${SERVER}/backup.ts`);
    const Sig = await import(`${SERVER}/sign.ts`);
    const hostGroup = (h: string) => readdirSync(`${DIR}/${h}/groups`).map(g => JSON.parse(readFileSync(`${DIR}/${h}/groups/${g}/state.json`, 'utf8'))).find(x => x.group.groupX === member.group.groupX);
    const g1 = hostGroup('h1'), g2 = hostGroup('h2');
    const k = new URL(paper.replace('cosign:', 'cosign://')).searchParams.get('k')!;
    let restored: { idx: number; secret: string }[] = [];
    try { restored = restoreShares(k, { ...g1.backup, group: g1.group }); } catch (e) { log(`restore: ${(e as Error).message}`); }
    check(restored.length >= 2, `the paper restores ${restored.length} shares`);
    // Paper + both servers is 4 points: it can sign without this browser (after the wait).
    try {
      const shares = [...restored, ...g1.self.shares, ...g2.self.shares];
      const msg = '11'.repeat(32);
      const round = Sig.commitAll(g1.group, shares);
      const sig = Sig.combine(2, g1.group, msg, round.commits, Sig.partialAll(2, g1.group, shares, round.nonces, msg, round.commits));
      check(!!sig, 'paper + both servers sign (the recovery path)');
    } catch (e) { check(false, `paper + both servers sign: ${(e as Error).message}`); }
    const pop1 = await ctx.newPage();
    await pop1.goto(`chrome-extension://${extId}/popup.html`);
    await pop1.waitForTimeout(2500);
    check(!(await pop1.isVisible('#safety')), 'with a paper, the popup card goes away');
    await pop1.close();
  }

  // Add your phone: it scans this browser's invite and asks to join; you say yes with the lock.
  await safety.click("#tabPhone");
  await safety.waitForFunction(() => !!document.getElementById('inviteQr')?.dataset.code, null, { timeout: 15_000 });
  const invite = (await safety.$eval('#inviteQr', el => (el as HTMLElement).dataset.code))!;
  check(invite.startsWith('cosign:invite?pk='), 'Add your phone shows an invite code');
  const { spawn: spawnP } = await import('node:child_process');
  let phoneOut = '';
  const ph = spawnP('node', ['--experimental-transform-types', '--no-warnings', new URL('./invite-phone.ts', import.meta.url).pathname, invite, 'Pixel 9'], { stdio: ['ignore', 'pipe', 'pipe'] });
  ph.stdout.on('data', d => { phoneOut += d; }); ph.stderr.on('data', d => { phoneOut += d; });
  await safety.waitForSelector('#ask:not([hidden])', { timeout: 30_000 }).catch(() => {});
  check((await safety.textContent('#askName').catch(() => ''))?.includes('Add Pixel 9 to your key?') ?? false, 'the phone asks to join: "Add Pixel 9 to your key?"');
  await safety.click('#askYes').catch(() => {});
  const t3 = Date.now();
  while (!phoneOut.includes('JOINED') && Date.now() - t3 < 60_000) await sleep(500);
  check(phoneOut.includes('JOINED'), `after your lock, the phone gets its shares and joins (${Date.now() - t3} ms)`);
  await sleep(3000);
  check(((await safety.textContent('#phoneStatus')) ?? '').includes('now one of your devices'), `the page says so: "${(await safety.textContent('#phoneStatus'))?.trim()}"`);
  ph.kill();

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
