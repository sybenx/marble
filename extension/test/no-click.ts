// @ts-nocheck (a test script, typed loosely)
// Nothing opens without a click, and open tabs hear when they're signed out (~/igloo/signin-signout-audit.md, fixes
// 1 and 5). Brave (or any Chromium), a throwaway account made here that publishes nothing, and Marble Web's site/
// served on :8787 (`node serve.mjs <marble-web>/site 8787`).
//
//   node build.mjs chrome && [BROWSER=<path>] [SITE=<url>] node --experimental-transform-types test/no-click.ts
import { chromium } from 'playwright-core';
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const exe = process.env.BROWSER ?? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser';
const SITE = process.env.SITE ?? 'http://localhost:8787/';
const origin = new URL(SITE).origin;
const t0 = Date.now(), log = s => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s  ${s}`);
setTimeout(() => { log('gave up after 6 min'); process.exit(2); }, 360000).unref?.();
let failed = 0;
const check = (ok, what) => { if (!ok) failed++; log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); };
const wait = ms => new Promise(r => setTimeout(r, ms));

log('launching');
const ctx = await chromium.launchPersistentContext('', { executablePath: exe, headless: false,
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new', '--no-first-run'] });
let sw = ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker', { timeout: 15000 }).catch(() => null);
if (!sw) { log('no service worker: extension not loaded'); await ctx.close(); process.exit(1); }
const id = new URL(sw.url()).host; log(`extension ${id}`);
const pop = await ctx.newPage(); await pop.goto(`chrome-extension://${id}/popup.html`);
await pop.evaluate(() => chrome.storage.local.set({ devNoPublish: true }));

const site = await ctx.newPage();
const win = () => ctx.pages().find(p => p.url().includes('/card.html'));
// The in-page card: a host on <html> at the top z-index (its shadow root is closed).
const card = () => site.evaluate(() => [...document.documentElement.children].some(e => (e.getAttribute('style') ?? '').includes('2147483647')));
const badge = () => ctx.serviceWorkers()[0].evaluate(() => chrome.action.getBadgeText({})).catch(e => '?' + e.message);
// A request with no click: a site asking on load or in the background. Playwright's evaluate counts as a click for
// 5 s (the browser's user activation), so the request goes once that has passed, and says if it hadn't.
const quiet = () => site.evaluate(() => new Promise(res => setTimeout(() => {
  if (navigator.userActivation?.isActive) return res('error: test bug, the page still counts as clicked');
  Promise.race([
    window.nostr.getPublicKey().then(pk => 'pk ' + pk, e => 'error: ' + e.message),
    new Promise(r => setTimeout(() => r('still waiting after 5 s'), 5000)),
  ]).then(res);
}, 5500)));
const me = () => site.evaluate(() => { try { return JSON.parse(localStorage.getItem('marble.me'))?.pubkey ?? null; } catch { return null; } });
async function nothingOpened(label) {
  await wait(2000);
  check(!win(), `${label}: no window`);
  check(!(await card()), `${label}: no card on the page`);
  check(!(await badge()), `${label}: nothing waits on the toolbar (badge "${await badge()}")`);
}

// 1. Signed out of Marble, no click.
await site.goto(SITE);
await site.waitForFunction(() => !!window.nostr, null, { timeout: 10000 });
check(await site.evaluate(() => window.nostr.__marble === true), 'window.nostr.__marble is true');
let r = await quiet();
log(`1 signed out of Marble, no click: ${r}`);
check(/^error: not-connected:/.test(r), '1 not-connected at once');
await nothingOpened('1');

// 2. Signed in to Marble, the site not let in, no click.
const made = await pop.evaluate(() => new Promise(res => { const p = chrome.runtime.connect({ name: 'create' }); p.onMessage.addListener(m => { if (m.done || m.error) res(m.text); }); p.postMessage({ type: 'create', name: 'Throwaway test' }); setTimeout(() => res('timeout'), 90000); }));
log(`create: ${made}`);
for (const p of ctx.pages()) if (p.url().includes('/safety.html')) await p.close(); // the backup step it opens
const pubkey = await ctx.serviceWorkers()[0].evaluate(async () => { const { member, pending } = await chrome.storage.local.get(['member', 'pending']); return member?.group.groupX ?? pending?.pubkey; });
log(`account ${pubkey?.slice(0, 8)}`);
await site.reload(); await site.waitForFunction(() => !!window.nostr, null, { timeout: 10000 });
r = await quiet();
log(`2 site not let in, no click: ${r}`);
check(/^error: not-connected:/.test(r), '2 not-connected at once');
await nothingOpened('2');

// 3. A click on the site's Sign in: the window opens, and Allow lets it in.
await site.click('button.sign-in');
await site.click('[data-si="extension"]', { timeout: 3000 }).catch(() => log('(the sign-in went straight to the extension)'));
for (const end = Date.now() + 8000; !win() && Date.now() < end;) await wait(200);
check(!!win(), `3 a click: the window opened${win() ? ' (' + win().url().split('#')[1] + ')' : ''}`);
if (win()) { await win().waitForSelector('#yes:not([disabled])', { timeout: 10000 }).catch(() => {}); await win().click('#yes').catch(e => log('yes click: ' + e.message)); }
for (const end = Date.now() + 15000; !(await me()) && Date.now() < end;) await wait(300);
check((await me()) === pubkey, `3 Allow: the site is signed in as ${(await me())?.slice(0, 8)}`);
await site.reload(); await site.waitForFunction(() => !!window.nostr, null, { timeout: 10000 });
r = await quiet();
check(r === 'pk ' + pubkey, `3 let in: a request with no click gets the pubkey (${r.slice(0, 11)})`);
await wait(1500);
check((await me()) === pubkey, '3 still signed in after a reload (its quiet check on load)');

// 4. Remove in the popup: the open page hears it at once.
await site.evaluate(() => { window.__heard = 0; window.addEventListener('marble:signedout', () => window.__heard++); });
await pop.evaluate(o => chrome.runtime.sendMessage({ type: 'remove-site', origin: o }), origin);
for (const end = Date.now() + 5000; !(await site.evaluate(() => window.__heard)) && Date.now() < end;) await wait(100);
check((await site.evaluate(() => window.__heard)) === 1, '4 Remove: the page got marble:signedout');
check((await me()) === null, '4 the site signed itself out');
check(await site.isVisible('button.sign-in'), '4 the site shows Sign in');

// 5. Afterwards, no click.
await site.reload(); await site.waitForFunction(() => !!window.nostr, null, { timeout: 10000 });
r = await quiet();
log(`5 removed, no click: ${r}`);
check(/^error: not-connected:/.test(r), '5 not-connected at once');
await nothingOpened('5');

// 6. Another site's tab doesn't hear this site's Remove; every tab hears Marble signing out.
const other = await ctx.newPage(); await other.goto(`http://127.0.0.1:${new URL(SITE).port || 80}/`);
await other.waitForFunction(() => !!window.nostr, null, { timeout: 10000 });
for (const p of [site, other]) await p.evaluate(() => { window.__heard = 0; window.addEventListener('marble:signedout', () => window.__heard++); });
await pop.evaluate(o => chrome.runtime.sendMessage({ type: 'remove-site', origin: o }), origin); // (already gone: says nothing)
await ctx.serviceWorkers()[0].evaluate(o => chrome.storage.local.get('sites').then(({ sites }) => chrome.storage.local.set({ sites: { ...sites, [o]: { added: Date.now(), lastUsed: Date.now() } } })), origin);
await pop.evaluate(o => chrome.runtime.sendMessage({ type: 'remove-site', origin: o }), origin);
await wait(1500);
const heard = async () => `site ${await site.evaluate(() => window.__heard)}, other ${await other.evaluate(() => window.__heard)}`;
log(`6 heard: ${await heard()}`);
check((await site.evaluate(() => window.__heard)) === 1 && (await other.evaluate(() => window.__heard)) === 0, '6 a Remove is heard by that site only (and only when there was something to remove)');
await pop.evaluate(() => chrome.runtime.sendMessage({ type: 'sign-out' }));
await wait(3000);
log(`6 heard: ${await heard()}`);
check((await site.evaluate(() => window.__heard)) === 2 && (await other.evaluate(() => window.__heard)) === 1, '6 Sign out: every tab heard it');

log(failed ? `${failed} FAILED` : 'all passed');
await ctx.close(); process.exit(failed ? 1 : 0);
