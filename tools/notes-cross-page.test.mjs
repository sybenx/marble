// Marble Web's Notes in headless Chromium, on the local relay and Blossom servers of tools/notes-sync-smoke.sh, with a
// TEST account Android's engine made. Writes notes the smoke test then reads on Android:
//  1. Android → Web: the page shows the phone's notes.
//  2. Two tabs of one browser (one that kept its device root from an earlier vault) each write a note: both reach your
//     other devices (before 1d2d38e the second tab signed a second sub-cert for the browser's key, and every other
//     device ignored that browser for good).
//  3. A note kept while it can't be sent, then the page reloaded before it went: it shows again after the reload and
//     goes up (before the fix it lived only in page memory and was lost).
//  4. A long note (its text a blob) written while the first Blossom server on your list refuses uploads (429, Retry-After
//     3): it waits here, said so, and goes up by itself once the wait is over (before 5c90f36 the refusal escaped
//     flush() and the note was never sent).
//  5. Web → fresh Web: a new browser shows every note above.
// Prints the texts written, one per line after "WROTE ", for the smoke test to look for on Android.
//   node tools/notes-cross-page.test.mjs <nsec hex>        (VAULTS_RELAY, VAULTS_SERVERS: the local servers; the first
//                                                          server is told to refuse uploads for step 4, then not)
// NOTES_PAGE_EXPECT: a note the phone wrote that the page must show (default Android's edit in the smoke test).
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium in ~/Library/Caches/ms-playwright; skipped
// (exit 0, says so) without them. The browser and the test signer's socket are closed on the way out, failure included.
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import { localSigner, memoryStore } from "./vault-fixtures.mjs";
import { realNet } from "./vault-net.mjs";

const pwPath = path.join(os.homedir(), "keykin/node_modules/playwright-core/index.mjs");
function chromium() {
  const cache = path.join(os.homedir(), "Library/Caches/ms-playwright");
  if (!existsSync(cache)) return null;
  const dirs = readdirSync(cache).sort().reverse();
  for (const [pre, name] of [["chromium_headless_shell-", "chrome-headless-shell"], ["chromium-", "Chromium"]]) {
    for (const d of dirs.filter((n) => n.startsWith(pre) && /\d$/.test(n))) {
      try { const p = execFileSync("find", [path.join(cache, d), "-maxdepth", "6", "-type", "f", "-perm", "+111", "-name", name], { encoding: "utf8" }).split("\n")[0]; if (p) return p; } catch {}
    }
  }
  return null;
}
const exe = chromium();
if (!existsSync(pwPath) || !exe) { console.log(`notes cross page: SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);
const { encrypt, decrypt, conversationKey } = await import(new URL("../site/js/signin/connect.js", import.meta.url));

let n = 0;
// (NOTES_PAGE_SOFT=1: a failed check is said and the rest still run, to see every one that fails; exit 1 at the end)
const failed = [];
const ok = (c, m) => { if (!c) { if (!process.env.NOTES_PAGE_SOFT) throw new Error("FAIL: " + m); failed.push(m); console.log("FAIL: " + m); return; } n++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Math.floor(Date.now() / 1000);
const SITE = new URL("../site/", import.meta.url).pathname;
const BASE = "http://localhost:7799"; // (the site, answered here; localhost so the page takes the test relay)
const RELAY = process.env.VAULTS_RELAY || "ws://127.0.0.1:7447";
const EXPECT = process.env.NOTES_PAGE_EXPECT || "Done one (edited on Android)";
const SERVERS = (process.env.VAULTS_SERVERS || "http://127.0.0.1:7447,http://127.0.0.1:7448").split(",");
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".css": "text/css" };
const sec = C.unhex(process.argv[2]), me = C.pubHex(sec);
const tellServer = (server, body) => fetch(`${server}/_test`, { method: "POST", body: JSON.stringify(body) });

// ── the account's signer (NIP-46, as Marble on the phone answers), on the local relay ──
const signerWs = new WebSocket(RELAY);
await new Promise((ok, no) => { signerWs.onopen = ok; signerWs.onerror = no; });
const relaySend = (m) => signerWs.send(JSON.stringify(m));
signerWs.onmessage = async (raw) => {
  const m = JSON.parse(raw.data);
  if (m[0] !== "EVENT" || m[1] !== "signer") return;
  const e = m[2], ck = await conversationKey(sec, e.pubkey);
  let q; try { q = JSON.parse(await decrypt(e.content, ck)); } catch { return; }
  let result = null, error = null;
  try {
    if (q.method === "sign_event") { const t = JSON.parse(q.params[0]); result = JSON.stringify(C.signEvent(sec, { created_at: now(), tags: [], content: "", ...t })); }
    else if (q.method === "nip44_decrypt") result = C.nip44Decrypt(C.nip44Conv(sec, q.params[0]), q.params[1]);
    else if (q.method === "nip44_encrypt") result = C.nip44Encrypt(C.nip44Conv(sec, q.params[0]), q.params[1]);
    else if (q.method === "get_public_key") result = me;
    else if (q.method === "connect" || q.method === "ping") result = "ack";
    else error = "unsupported";
  } catch (err) { error = String(err?.message || err); }
  relaySend(["EVENT", C.signEvent(sec, { kind: 24133, created_at: now(), tags: [["p", e.pubkey]], content: await encrypt(JSON.stringify({ id: q.id, result, error }), ck) })]);
};
relaySend(["REQ", "signer", { kinds: [24133], "#p": [me], since: now() - 5 }]);

// ── another of your devices reading the relay fresh (the web engine in Node), to see what went up ──
async function seen() {
  const signer = localSigner(sec);
  const entries = notesVault(await readKeyrings({ me, relays: [RELAY], net: realNet, signer }));
  const s = await new Session({ me, entries, net: realNet, signer, store: memoryStore(), deviceName: "Node check" }).open();
  const out = [];
  for (const x of N.notesOf(s.vault.state(), s.vault.roster())) out.push(x.note.text ?? (x.note.blob ? C.unutf8(await s.blob(x.note.blob)) : ""));
  return out;
}
const until = async (fn, tries = 60, ms = 500) => { for (let i = 0; i < tries; i++) { if (await fn()) return true; await sleep(ms); } return false; };

// the account's storage servers (kind 10063): the first refuses uploads in step 4
const list = [SERVERS[0], ...SERVERS.slice(1)];
relaySend(["EVENT", C.signEvent(sec, { kind: 10063, created_at: now(), tags: list.map((s) => ["server", s]), content: "" })]);
await sleep(300);

const wrote = [];
let wsBorn = 0, holdUpTo = -1; // a page's vault heads are dropped on sockets opened at or before holdUpTo
// (the site is answered here, so Chromium counts it as public and would block it from reaching the local servers)
const browser = await pw.launch({ executablePath: exe, args: ["--disable-features=LocalNetworkAccessChecks,BlockInsecurePrivateNetworkRequests"] });
try {
  async function browserWith(seed) {
    const context = await browser.newContext();
    await context.routeWebSocket(/.*/, (ws) => {
      if (ws.url().replace(/\/$/, "") !== RELAY) return ws.close(); // a public relay: not reached
      const born = wsBorn, server = ws.connectToServer();
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return server.send(raw); }
        // (a head not taken: the change can't be sent now, and waits here)
        if (m[0] === "EVENT" && m[1]?.kind === 30810 && born <= holdUpTo) return ws.send(JSON.stringify(["OK", m[1].id, false, "blocked: held by the test"]));
        server.send(raw);
      });
      server.onMessage((raw) => ws.send(raw));
    });
    await context.route(() => true, (route) => {
      const req = route.request(), u = new URL(req.url());
      if (u.hostname === "127.0.0.1") return route.continue(); // the local storage servers
      if (u.origin !== BASE) return route.fulfill({ status: 200, contentType: "text/plain", headers: { "access-control-allow-origin": "*" }, body: "" }); // outside: not reached
      let f = path.join(SITE, decodeURIComponent(u.pathname));
      if (!f.startsWith(SITE) || !existsSync(f) || statSync(f).isDirectory()) f = path.join(SITE, "index.html");
      return route.fulfill({ status: 200, contentType: types[path.extname(f)] || "application/octet-stream", body: readFileSync(f) });
    });
    await context.addInitScript(({ relay, pk, sk, key, seed }) => {
      sessionStorage.setItem("marble.local", relay);
      if (!localStorage.getItem("marble.me")) localStorage.setItem("marble.me", JSON.stringify({ pubkey: pk, method: "phone", phone: { sk, signer: pk, relays: [relay] } }));
      if (seed && !localStorage.getItem("marble.seeded")) { localStorage.setItem(key, JSON.stringify(seed)); localStorage.setItem("marble.seeded", "1"); }
    }, { relay: RELAY, pk: me, sk: C.hex(C.randomKey()), key: `marble.vault.${me}`, seed });
    return context;
  }
  const state = (page) => page.evaluate(async () => (await import("/js/notes-vault.js")).state());
  const logs = [];
  async function opened(page) {
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("console", (m) => { if (/vault/i.test(m.text())) logs.push(m.text().slice(0, 300)); });
    await until(async () => (await state(page)) === "open", 120, 250);
    if ((await state(page)) !== "open") console.log("page:", await state(page), "|", logs.slice(-5).join(" | "), "|", errors.join(" | "));
    return { page, errors };
  }
  async function tab(context) { const page = await context.newPage(); await page.goto(`${BASE}/notes`); return opened(page); }
  const vaultOf = (page, fn, arg) => page.evaluate(async ({ fn, arg }) => { const v = await import("/js/notes-vault.js"); return new Function("v", "arg", `return (${fn})(v, arg)`)(v, arg); }, { fn: fn.toString(), arg });
  // a note typed in Notes and kept (Enter); `send`: then sent at once (as when the tab goes to the background)
  const jot = (page, text, send = true) => page.evaluate(async ({ t, send }) => {
    const later = await import("/js/later.js"), v = await import("/js/notes-vault.js");
    later.jot(t);
    if (send) await v.flush();
    return v.state();
  }, { t: text, send });
  const shown = (page) => vaultOf(page, (v) => v.notes().map((i) => v.textOf(i)));

  // 1, 2. two tabs, opened before this browser first wrote to this vault (it kept its device root from an earlier one)
  const ctx = await browserWith({ root: C.hex(C.randomKey()) });
  const A = await tab(ctx), B = await tab(ctx);
  ok((await state(A.page)) === "open" && (await state(B.page)) === "open", "the vault opens in two tabs");
  ok((await shown(A.page)).includes(EXPECT), `Android → Web: the page shows the phone's "${EXPECT}"`);
  const tabA = "Two tabs: from tab A", tabB = "Two tabs: from tab B";
  ok((await jot(A.page, tabA)) === "open" && (await jot(B.page, tabB)) === "open", "each tab writes a note");
  wrote.push(tabA, tabB);
  ok(await until(async () => { const s = await seen(); return s.includes(tabA) && s.includes(tabB); }, 20), "two tabs → your other devices: both notes there");

  // 3. a note kept while it can't be sent (its head not taken), then the page reloaded before it went
  const kept = "Kept, then the page reloaded";
  holdUpTo = wsBorn++;
  ok((await jot(A.page, kept)) === "offline", "(the note can't be sent now: it waits)");
  await A.page.reload();
  await opened(A.page);
  ok((await shown(A.page)).includes(kept), "after the reload the note shows again");
  ok(await until(async () => (await seen()).includes(kept), 30), "and it goes up: your other devices have it");
  wrote.push(kept);
  ok([A, B].every((t) => !t.errors.length), `no page errors: ${[A, B].flatMap((t) => t.errors).join(" | ")}`);
  holdUpTo = -1;

  // 4. a long note while the first storage server on your list refuses uploads
  await tellServer(SERVERS[0], { refuseUploads: 3 });
  const long = "Written while a server refused " + "with many words in it ".repeat(250);
  try {
    const r = await vaultOf(B.page, async (v, t) => {
      const id = Array.from(crypto.getRandomValues(new Uint8Array(8)), (x) => x.toString(16).padStart(2, "0")).join(""), s = Math.floor(Date.now() / 1000);
      v.write({ id, text: t, created: s, updated: s, done: null, deleted: null });
      let thrown = null; try { await v.flush(); } catch (err) { thrown = String(err?.message || err); }
      return { state: v.state(), held: v.held(), thrown };
    }, long);
    ok(r.held && !r.thrown, `the long note waits here while the server refuses, said so (${JSON.stringify(r)})`);
    await tellServer(SERVERS[0], { refuseUploads: 0 });
    ok(await until(async () => (await seen()).includes(long), 30), "and goes up by itself when the wait is over: your other devices have it");
  } finally { await tellServer(SERVERS[0], { refuseUploads: 0 }); }
  wrote.push(long.slice(0, 40));
  await ctx.close();

  // 5. a fresh browser shows them all
  const ctx2 = await browserWith(null);
  const F = await tab(ctx2);
  const f = await shown(F.page);
  for (const t of [tabA, tabB, kept, long, EXPECT]) ok(f.includes(t), `Web → fresh Web: "${t.slice(0, 40)}" shows (${JSON.stringify(f.map((x) => x.slice(0, 30)))})`);
  ok(!F.errors.length, `no page errors: ${F.errors.join(" | ")}`);
  await ctx2.close();
} finally {
  await browser.close();
  signerWs.close();
}
for (const t of wrote) console.log("WROTE " + t);
if (failed.length) { console.log(`notes cross page: ${failed.length} checks FAILED, ${n} passed`); process.exit(1); }
console.log(`notes cross page: ${n} checks passed`);
process.exit(0);
