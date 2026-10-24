// The extension in desktop Firefox (installed as a temporary add-on over WebDriver
// BiDi), against running Cosign 0.4.0 servers. A shorter run than e2e.ts: sign in by
// the card, sign a note, and a second site's Allow.
//
//   node build.mjs firefox && node --experimental-transform-types test/firefox.ts

import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import puppeteer, { type Frame, type Page } from 'puppeteer-core';
import { verifyEvent, type Event } from 'nostr-tools/pure';

const A = JSON.parse(readFileSync(`${process.env.HOME}/cosign/server/data/a/state.json`, 'utf8'));
const t0 = Date.now(), log = (s: string) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)} s  ${s}`);
let failed = 0;
const check = (ok: boolean, what: string) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };

const PAGE = '<!doctype html><title>t</title><h1>test site</h1>';
const http = createServer((_, res) => { res.setHeader('content-type', 'text/html'); res.end(PAGE); }).listen(8797);

const browser = await puppeteer.launch({ browser: 'firefox', executablePath: '/Applications/Firefox.app/Contents/MacOS/firefox', headless: !process.env.HEADED, userDataDir: process.env.FF_PROFILE ?? (await import('node:fs')).mkdtempSync(`${process.env.TMPDIR ?? '/tmp/'}cosign-ff-`) });
const id = await browser.installExtension(new URL('../dist/firefox', import.meta.url).pathname);
log(`installed ${id}`);

async function cardFrame(page: Page, want: string, timeout = 20_000): Promise<Frame> {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    for (const f of page.frames()) if (f.url().includes('/card.html')) {
      const text = await f.evaluate(() => document.body.innerText).catch(() => '');
      if (text.includes(want)) return f;
    }
    await new Promise(r => setTimeout(r, 300));
  }
  throw new Error(`no card saying "${want}"`);
}
const call = (page: Page, expr: string) => page.evaluate(expr).then(r => ({ ok: true as const, r }), e => ({ ok: false as const, e: String(e.message ?? e) }));

try {
  const page = await browser.newPage();
  await page.goto('http://localhost:8797/');
  check(await page.evaluate('typeof window.nostr?.signEvent === "function"') as boolean, 'window.nostr is there');
  const pk = call(page, 'window.nostr.getPublicKey()');
  const card = await cardFrame(page, 'Sign in to localhost:8797');
  await card.waitForSelector('.qr svg', { timeout: 30_000 });
  const uri = await card.$eval('.qr', el => (el as HTMLElement).dataset.uri!);
  log('card shows a code; the stand-in phone answers it');
  execFileSync('node', ['--experimental-transform-types', '--no-warnings', new URL('./fake-phone.ts', import.meta.url).pathname, uri], { stdio: 'ignore', timeout: 70_000 }).toString?.();
  const confirm = await cardFrame(page, 'Sign in as', 30_000);
  await (await confirm.$('.yes'))!.click();
  const got = await pk;
  check(got.ok && got.r === A.group.groupX, `getPublicKey → ${got.ok ? String(got.r).slice(0, 12) : got.e}`);
  const t = Date.now();
  const ev = await call(page, `window.nostr.signEvent({ kind: 1, content: 'hello from Firefox', tags: [] })`);
  check(ev.ok && verifyEvent(ev.r as Event), `signEvent → valid (${Date.now() - t} ms)${ev.ok ? '' : ' ' + ev.e}`);

  const page2 = await browser.newPage();
  await page2.goto('http://127.0.0.1:8797/');
  const p2 = call(page2, 'window.nostr.getPublicKey()');
  const allow = await cardFrame(page2, 'Let 127.0.0.1:8797 sign in as');
  await new Promise(r => setTimeout(r, 700));
  await (await allow.$('#yes'))!.click();
  const got2 = await p2;
  check(got2.ok && got2.r === A.group.groupX, 'second site: Allow → getPublicKey');
} catch (e) {
  failed++; log(`FAIL ${(e as Error).stack}`);
} finally {
  await browser.close(); http.close();
  log(failed ? `${failed} failed` : 'all passed');
  process.exit(failed ? 1 : 0);
}
