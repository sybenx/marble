// Marble Web's Notes when a storage server says no (good-guest §3.5), in the page: a long note (its text a blob) that a
// Blossom server refuses with 429 + Retry-After waits here ("…, changes wait here"), nothing is sent to that server until
// it said, then the page tries again by itself, once, and the note goes up. A fresh browser whose server refuses
// downloads shows the note as not loaded yet, never gone, and names the server instead of "syncing…".
// Before the fix, the long note's upload ran outside flush()'s try: the refusal escaped flush(), so Notes never said the
// change was waiting and nothing tried it again.
//
// Headless Chromium; nothing outside is reached: the site, the relay, a NIP-46 signer for a TEST key and the Blossom
// server are all answered here (page.route / routeWebSocket), with no ports opened.
//   node tools/vault-refusals-page.test.mjs
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium in ~/Library/Caches/ms-playwright; skipped
// without them.
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { makeVault } from "./vault-fixtures.mjs";

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
if (!existsSync(pwPath) || !exe) { console.log(`vault refusals page: SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);
const { encrypt, decrypt, conversationKey } = await import(new URL("../site/js/signin/connect.js", import.meta.url));

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Math.floor(Date.now() / 1000);
const SITE = new URL("../site/", import.meta.url).pathname;
const BASE = "http://localhost:7799"; // (localhost, so the page takes a test relay; never opened: answered here)
const LOCAL = "ws://localhost:7798";
const STUB = `${BASE}/test-blossom`; // the account's storage server (same origin: this tests refusals, not CORS)
const HOST = "localhost:7799";
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".css": "text/css" };

// ── a test account, its relay (in memory) and its signer (answers NIP-46 as Marble on the phone would) ──
const sec = C.randomKey(), me = C.pubHex(sec);
const events = [];
const matches = (e, f) => (!f.ids || f.ids.includes(e.id)) && (!f.kinds || f.kinds.includes(e.kind)) && (!f.authors || f.authors.includes(e.pubkey))
  && (f.since == null || e.created_at >= f.since) && (f.until == null || e.created_at <= f.until)
  && Object.entries(f).every(([k, v]) => !k.startsWith("#") || e.tags.some((t) => t[0] === k.slice(1) && v.includes(t[1])));
const sockets = new Set();
function take(e) {
  const k = e.kind >= 30000 && e.kind < 40000 ? `${e.kind}:${e.pubkey}:${e.tags.find((t) => t[0] === "d")?.[1] ?? ""}` : (e.kind >= 10000 && e.kind < 20000 ? `${e.kind}:${e.pubkey}` : null);
  if (k) { const i = events.findIndex((x) => `${x.kind}:${x.pubkey}${x.kind >= 30000 ? ":" + (x.tags.find((t) => t[0] === "d")?.[1] ?? "") : ""}` === k); if (i >= 0) { if (events[i].created_at > e.created_at) return; events.splice(i, 1); } }
  events.push(e);
  for (const s of sockets) for (const [id, fs] of s.subs) if (fs.some((f) => matches(e, f))) s.ws.send(JSON.stringify(["EVENT", id, e]));
  if (e.kind === 24133 && e.tags.some((t) => t[0] === "p" && t[1] === me)) answer(e);
}
let signs = 0;
async function answer(e) {
  const ck = await conversationKey(sec, e.pubkey);
  let m; try { m = JSON.parse(await decrypt(e.content, ck)); } catch { return; }
  let result = null, error = null;
  try {
    if (m.method === "sign_event") { signs++; const t = JSON.parse(m.params[0]); result = JSON.stringify(C.signEvent(sec, { created_at: now(), tags: [], content: "", ...t })); }
    else if (m.method === "nip44_decrypt") result = C.nip44Decrypt(C.nip44Conv(sec, m.params[0]), m.params[1]);
    else if (m.method === "nip44_encrypt") result = C.nip44Encrypt(C.nip44Conv(sec, m.params[0]), m.params[1]);
    else if (m.method === "get_public_key") result = me;
    else if (m.method === "connect" || m.method === "ping") result = "ack";
    else error = "unsupported";
  } catch (err) { error = String(err?.message || err); }
  take(C.signEvent(sec, { kind: 24133, created_at: now(), tags: [["p", e.pubkey]], content: await encrypt(JSON.stringify({ id: m.id, result, error }), ck) }));
}

// ── the storage server: works ("store"), or refuses everything with 429 and Retry-After ──
const blossom = { mode: "store", retryAfter: "3", blobs: new Map(), log: [] };
function blossomAnswer(req) {
  const p = new URL(req.url()).pathname.slice("/test-blossom".length), method = req.method();
  blossom.log.push({ t: Date.now(), method, p });
  if (blossom.mode === "429") return { status: 429, headers: { "Retry-After": blossom.retryAfter, "X-Reason": "slow down" }, body: "too many requests" };
  if (method === "PUT" && p === "/upload") {
    const b = req.postDataBuffer(), sha = C.hex(C.sha256(new Uint8Array(b)));
    blossom.blobs.set(sha, b);
    return { status: 200, contentType: "application/json", body: JSON.stringify({ sha256: sha, size: b.length }) };
  }
  const b = blossom.blobs.get(p.slice(1));
  return method === "GET" && b ? { status: 200, contentType: "application/octet-stream", body: b } : { status: 404, body: "" };
}
const asked = (since, method) => blossom.log.filter((x) => x.t >= since && x.method === method).length;

// ── the vault, made as the phone makes one; its relay is the test's, its server the one above ──
await makeVault({ async publish(urls, e) { take(e); return { ok: urls.slice(), failed: [] }; } }, sec,
  { relays: [LOCAL], servers: [STUB], puts: [N.notePut({ id: "0123456789abcdef", text: "a short note", created: now() - 50, updated: now() - 50 }, [])] });

const browser = await pw.launch({ executablePath: exe });
try {
  async function device() {
    const context = await browser.newContext();
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    await page.routeWebSocket(/.*/, (ws) => {
      if (ws.url().replace(/\/$/, "") !== LOCAL) return ws.close(); // a public relay: not reached
      const s = { ws, subs: new Map() };
      sockets.add(s);
      ws.onClose(() => sockets.delete(s));
      ws.onMessage((raw) => {
        let msg; try { msg = JSON.parse(raw); } catch { return; }
        if (msg[0] === "REQ") { const fs = msg.slice(2); s.subs.set(msg[1], fs); for (const e of events) if (fs.some((f) => matches(e, f))) ws.send(JSON.stringify(["EVENT", msg[1], e])); ws.send(JSON.stringify(["EOSE", msg[1]])); }
        else if (msg[0] === "CLOSE") s.subs.delete(msg[1]);
        else if (msg[0] === "EVENT") { ws.send(JSON.stringify(["OK", msg[1].id, true, ""])); take(msg[1]); }
      });
    });
    await page.route(() => true, (route) => {
      const req = route.request(), u = new URL(req.url());
      if (u.origin !== BASE) return route.fulfill({ status: 200, contentType: "text/plain", headers: { "access-control-allow-origin": "*" }, body: "" }); // outside: not reached
      if (u.pathname.startsWith("/test-blossom")) return route.fulfill(blossomAnswer(req));
      let f = path.join(SITE, decodeURIComponent(u.pathname));
      if (!f.startsWith(SITE) || !existsSync(f) || statSync(f).isDirectory()) f = path.join(SITE, "index.html");
      return route.fulfill({ status: 200, contentType: types[path.extname(f)] || "application/octet-stream", body: readFileSync(f) });
    });
    await context.addInitScript(({ relay, pk, sk }) => {
      sessionStorage.setItem("marble.local", relay);
      if (!localStorage.getItem("marble.me")) localStorage.setItem("marble.me", JSON.stringify({ pubkey: pk, method: "phone", phone: { sk, signer: pk, relays: [relay] } }));
    }, { relay: LOCAL, pk: me, sk: C.hex(C.randomKey()) });
    await page.goto(`${BASE}/notes`);
    for (let i = 0; i < 60 && (await page.evaluate(async () => (await import("/js/notes-vault.js")).state())) !== "open"; i++) await sleep(250);
    return { context, page, errors };
  }
  const vaultOf = (page, fn, arg) => page.evaluate(async ({ fn, arg }) => { const v = await import("/js/notes-vault.js"); return new Function("v", "arg", `return (${fn})(v, arg)`)(v, arg); }, { fn: fn.toString(), arg });
  const line = (page) => page.evaluate(() => document.querySelector(".only-you")?.textContent || "");
  // (the Notes box caps a note at 500 characters; a note over 4 KiB is one written on the phone and edited here)
  const writeLong = (page, text) => vaultOf(page, async (v, t) => {
    const id = Array.from(crypto.getRandomValues(new Uint8Array(8)), (x) => x.toString(16).padStart(2, "0")).join(""), s = Math.floor(Date.now() / 1000);
    v.write({ id, text: t, created: s, updated: s, done: null, deleted: null });
    try { await v.flush(); return null; } catch (err) { return String(err?.message || err); }
  }, text);
  const texts = (page) => vaultOf(page, (v) => v.notes().map((i) => v.textOf(i)));
  const LONG = (tag) => `${tag} ` + "a long note's words ".repeat(300);

  // 1. a working server: a long note's text goes up as a blob
  const A = await device();
  ok((await vaultOf(A.page, (v) => v.state())) === "open", "the vault opens in the page (signed in with a phone)");
  let t = Date.now();
  ok((await writeLong(A.page, LONG("first"))) === null, "a long note is written");
  ok(asked(t, "PUT") === 1 && blossom.blobs.size === 1, `its blob went up (${asked(t, "PUT")} upload)`);
  await A.context.close();

  // 2. the server refuses (429, Retry-After 3): the note waits here, said so; nothing asked inside the wait; then once
  const B = await device();
  await sleep(1000); // (the first note's blob read while the server still answers)
  blossom.mode = "429";
  t = Date.now();
  const thrown = await writeLong(B.page, LONG("second"));
  ok(thrown === null, `a refused upload doesn't escape flush() (${thrown})`);
  ok(asked(t, "PUT") === 1, `one upload asked (${asked(t, "PUT")})`);
  const l1 = await line(B.page);
  ok(l1.includes(`${HOST} says too many requests, trying again in a minute, changes wait here`), `Notes says so: "${l1}"`);
  ok((await texts(B.page)).some((x) => x.startsWith("second")), "the note shows here meanwhile");
  t = Date.now();
  await vaultOf(B.page, (v) => v.flush());
  await sleep(1500);
  ok(asked(t, "PUT") === 0 && asked(t, "GET") === 0, `inside the wait nothing is asked (${asked(t, "PUT")} PUT, ${asked(t, "GET")} GET)`);
  blossom.mode = "store";
  t = Date.now();
  // (until this browser's head with the note's commit is on the relay: the line clears as the wait ends, a moment
  // before the retried send has finished)
  const headAfter = () => events.some((e) => e.kind === 30810 && e.created_at * 1000 >= t - 1000);
  for (let i = 0; i < 60 && !(/^Only you$/.test((await line(B.page)).trim()) && headAfter()); i++) await sleep(250);
  ok(headAfter(), "the note's commit reached the relay");
  ok(asked(t, "PUT") === 1, `when the wait is over the page tries again by itself, once (${asked(t, "PUT")} upload)`);
  ok((await line(B.page)).trim() === "Only you" && blossom.blobs.size === 2, `the note went up and the line cleared ("${await line(B.page)}")`);
  ok(B.errors.length === 0, `no page errors: ${B.errors.join(" | ")}`);
  await B.context.close();

  // 3. a fresh browser, downloads refused: nothing shows as gone or broken, Notes names the server (not "syncing…"),
  //    a long note shows "hasn't loaded yet"; when the server answers again, every note loads whole
  blossom.mode = "429"; blossom.retryAfter = "6";
  t = Date.now();
  const D = await device();
  for (let i = 0; i < 40 && !(await line(D.page)).includes("says too many"); i++) await sleep(250);
  const tx = await texts(D.page), l3 = await line(D.page), gets = asked(t, "GET");
  ok(tx.includes("a short note") && tx.some((x) => x === "(This note hasn't loaded yet.)") && tx.every((x) => x === "a short note" || x === "(This note hasn't loaded yet.)"),
    `held, not gone: ${JSON.stringify(tx.map((x) => x.slice(0, 30)))}`);
  ok(l3.includes(`${HOST} says too many requests`) && !l3.includes("syncing"), `Notes names the server: "${l3}"`);
  ok(gets >= 1 && gets <= 2, `one download asked per blob, not a storm (${gets})`);
  blossom.mode = "store";
  let whole = [];
  for (let i = 0; i < 120; i++) { whole = await texts(D.page); if (whole.length === 3 && whole.every((x) => !x.startsWith("("))) break; await sleep(250); }
  ok(whole.length === 3 && whole.some((x) => x.startsWith("first")) && whole.some((x) => x.startsWith("second")), `the server answers again: every note loads whole (${JSON.stringify(whole.map((x) => x.slice(0, 20)))})`);
  ok(!(await line(D.page)).includes("says too many"), `…and the line clears ("${await line(D.page)}")`);
  await D.context.close();
} finally {
  await browser.close();
}
console.log(`vault refusals page: ${n} checks passed (${signs} signatures by the test signer)`);
