// @ts-nocheck (a test script, typed loosely)
// A site you once said Not now to (or whose window you closed): a click on its Sign in asks again; a request
// with no click is still refused quietly; the toolbar badge counts what waits. Brave (or any Chromium), a
// throwaway account made here that publishes nothing, and marble.syb.lol's Sign in.
//
//   node build.mjs chrome && [BROWSER=<path>] node --experimental-transform-types test/again.ts
import { chromium } from 'playwright-core';
const ext = new URL('../dist/chrome', import.meta.url).pathname;
const exe = process.env.BROWSER ?? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser';
const t0 = Date.now(), log = s => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s  ${s}`);
setTimeout(() => { log("gave up after 6 min"); process.exit(2); }, 360000).unref?.();
log("launching");
const ctx = await chromium.launchPersistentContext('', { executablePath: exe, headless: false,
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--headless=new', '--no-first-run'] });
let sw = ctx.serviceWorkers()[0] ?? await ctx.waitForEvent('serviceworker', { timeout: 15000 }).catch(() => null);
if (!sw) { log('no service worker: extension not loaded'); await ctx.close(); process.exit(1); }
const id = new URL(sw.url()).host; log(`extension ${id}`);
const pop = await ctx.newPage(); await pop.goto(`chrome-extension://${id}/popup.html`);
await pop.evaluate(() => chrome.storage.local.set({ devNoPublish: true }));
const made = await pop.evaluate(() => new Promise(res => { const p = chrome.runtime.connect({ name: 'create' }); p.onMessage.addListener(m => { if (m.done || m.error) res(m.text); }); p.postMessage({ type: 'create', name: 'Throwaway test' }); setTimeout(() => res('timeout'), 90000); }));
log(`create: ${made}`);
await pop.close();
log("opening the site"); const site = await ctx.newPage(); await site.goto(process.env.SITE ?? 'https://marble.syb.lol/');
const win = () => ctx.pages().find(p => p.url().includes('/card.html') && p.url().includes('&window'));
async function signIn(label, click = true) {
  await site.waitForSelector('button.sign-in', { timeout: 15000 });
  const r = click ? site.evaluate(() => window.nostr.getPublicKey().then(pk => 'pk ' + pk.slice(0, 8), e => 'error: ' + e.message))
    : site.evaluate(() => new Promise(res => setTimeout(() => window.nostr.getPublicKey().then(pk => res('pk ' + pk.slice(0, 8)), e => res('error: ' + e.message)), 6000)));
  if (!click) await new Promise(x => setTimeout(x, 6500));
  if (click) await site.click('button.sign-in').catch(() => {});
  const end = Date.now() + 8000; while (!win() && Date.now() < end) await new Promise(r => setTimeout(r, 200));
  log(`${label}: approval window ${win() ? 'OPENED ' + win().url().split('#')[1] : 'did NOT open'}; badge ${await badge()}`);
  return [r];
}
const badge = async () => { const s = ctx.serviceWorkers()[0]; return s ? s.evaluate(() => chrome.action.getBadgeText({})).catch(e => '?' + e.message) : '?'; };
const swEval = f => ctx.serviceWorkers()[0].evaluate(f);
const ans = r => Promise.race([r, new Promise(x => setTimeout(() => x('still waiting after 5 s'), 5000))]);
// 1. Close the window the way a person does (the window goes), no button.
let r; [r] = await signIn('1 Sign in');
if (win()) await win().evaluate(() => window.close()).catch(() => {});
await new Promise(x => setTimeout(x, 1500));
log(`window still there: ${!!win()}`);
log(`1 closed by the user: page got ${await ans(r)}`);
await site.reload();
// 2. Sign in again, now a real click.
[r] = await signIn('2 Sign in again (click)');
log(`2 page got ${await ans(r)}`);
if (win()) { await win().waitForSelector('#no:not([disabled])', { timeout: 10000 }).catch(() => {}); await win().click('#no').catch(e => log('no click: ' + e.message)); log(`2 said Not now: page got ${await ans(r)}`); }
await site.reload();
[r] = await signIn('2b a request with no click, after Not now', false);
log(`2b page got ${await ans(r)}`);
[r] = await signIn('3 Sign in after Not now (click)');
log(`3 page got ${await ans(r)}`);
if (win()) { await win().waitForSelector('#yes:not([disabled])', { timeout: 10000 }).catch(() => {}); await win().click('#yes').catch(e => log('yes click: ' + e.message)); log(`3 said Allow: page got ${await ans(r)}`); }
log(`badge after: "${await badge()}"`);
await ctx.close(); process.exit(0);
