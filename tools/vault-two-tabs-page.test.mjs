// Marble Web's Notes open in two tabs of one browser: a note written in either tab reaches your other devices.
//
// Before the fix, each tab kept its own snapshot of this browser's vault storage (notes-vault.js storeFor), read once
// when the tab opened the vault and written back whole on every change. A tab opened before this browser first wrote to
// the vault (while it already had its device root, from an earlier vault or while the first tab waited for your signer)
// didn't see the first tab's sub-cert, so it signed a second one for the same device key. A device key with two
// different sub-certs is never live (kist.md §2), so every other device ignored everything this browser wrote, for good.
// Now storage is read through and written one key at a time, and a browser whose key already went dead moves to a new
// one and carries what it wrote that still stands, as written, so those notes show everywhere.
//
// Headless Chromium; nothing outside is reached: the site, the relay, a NIP-46 signer for a TEST key and the Blossom
// server are answered here (page.route / routeWebSocket), with no ports opened. Another device (a fresh one, as the
// phone or the Mac would) reads the same relay and server with the engine in Node.
//   node tools/vault-two-tabs-page.test.mjs
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium in ~/Library/Caches/ms-playwright; skipped
// without them.
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import * as C from "../site/js/vault/crypto.js";
import * as N from "../site/js/vault/notes.js";
import { Session, readKeyrings, notesVault } from "../site/js/vault/sync.js";
import { makeVault, localSigner, memoryStore } from "./vault-fixtures.mjs";

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
if (!existsSync(pwPath) || !exe) { console.log(`vault two tabs page: SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);
const { encrypt, decrypt, conversationKey } = await import(new URL("../site/js/signin/connect.js", import.meta.url));

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Math.floor(Date.now() / 1000);
const SITE = new URL("../site/", import.meta.url).pathname;
const BASE = "http://localhost:7799"; // (localhost, so the page takes a test relay; never opened: answered here)
const LOCAL = "ws://localhost:7798";
const STUB = `${BASE}/test-blossom`;
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".css": "text/css" };

// ── a test account, its relay (in memory), its signer (NIP-46, as Marble on the phone answers) and storage server ──
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
async function answer(e) {
  const ck = await conversationKey(sec, e.pubkey);
  let m; try { m = JSON.parse(await decrypt(e.content, ck)); } catch { return; }
  let result = null, error = null;
  try {
    if (m.method === "sign_event") { const t = JSON.parse(m.params[0]); result = JSON.stringify(C.signEvent(sec, { created_at: now(), tags: [], content: "", ...t })); }
    else if (m.method === "nip44_decrypt") result = C.nip44Decrypt(C.nip44Conv(sec, m.params[0]), m.params[1]);
    else if (m.method === "nip44_encrypt") result = C.nip44Encrypt(C.nip44Conv(sec, m.params[0]), m.params[1]);
    else if (m.method === "get_public_key") result = me;
    else if (m.method === "connect" || m.method === "ping") result = "ack";
    else error = "unsupported";
  } catch (err) { error = String(err?.message || err); }
  take(C.signEvent(sec, { kind: 24133, created_at: now(), tags: [["p", e.pubkey]], content: await encrypt(JSON.stringify({ id: m.id, result, error }), ck) }));
}
const blobs = new Map();
function blossomAnswer(req) {
  const p = new URL(req.url()).pathname.slice("/test-blossom".length), method = req.method();
  if (method === "PUT" && p === "/upload") {
    const b = req.postDataBuffer(), sha = C.hex(C.sha256(new Uint8Array(b)));
    blobs.set(sha, new Uint8Array(b));
    return { status: 200, contentType: "application/json", body: JSON.stringify({ sha256: sha, size: b.length }) };
  }
  const b = blobs.get(p.slice(1));
  return method === "GET" && b ? { status: 200, contentType: "application/octet-stream", body: Buffer.from(b) } : { status: 404, body: "" };
}

// ── another of your devices (the phone, the Mac), opening the vault fresh in Node on the same relay and server ──
const net = {
  async req(_urls, filter) { const fs = Array.isArray(filter) ? filter : [filter]; return Object.assign(events.filter((e) => fs.some((f) => matches(e, f))), { answered: 1 }); },
  async publish(urls, e) { take(e); return { ok: urls.slice(), failed: [] }; },
  async get(_servers, sha) { return blobs.get(sha) || null; },
  async put(_server, bytes) { const sha = C.hex(C.sha256(bytes)); blobs.set(sha, bytes); return { ok: true, sha256: sha }; },
};
const signer = localSigner(sec);
async function otherDevice(store = memoryStore(), name = "Pixel 8") {
  const entries = notesVault(await readKeyrings({ me, relays: [LOCAL], net, signer }));
  return new Session({ me, entries, net, signer, store, deviceName: name }).open();
}
const seen = async () => { const s = await otherDevice(); return N.notesOf(s.vault.state(), s.vault.roster()).map((x) => x.note.text).sort(); };

const made = await makeVault(net, sec, { relays: [LOCAL], servers: [STUB], puts: [N.notePut({ id: "0123456789abcdef", text: "from the phone", created: now() - 50, updated: now() - 50 }, [])] });
const KEY = `marble.vault.${me}`;

const browser = await pw.launch({ executablePath: exe });
try {
  // a browser (one profile: its tabs share localStorage), seeded with what this browser's vault storage already held
  async function browserWith(seed) {
    const context = await browser.newContext();
    await context.routeWebSocket(/.*/, (ws) => {
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
    await context.route(() => true, (route) => {
      const req = route.request(), u = new URL(req.url());
      if (u.origin !== BASE) return route.fulfill({ status: 200, contentType: "text/plain", headers: { "access-control-allow-origin": "*" }, body: "" }); // outside: not reached
      if (u.pathname.startsWith("/test-blossom")) return route.fulfill(blossomAnswer(req));
      let f = path.join(SITE, decodeURIComponent(u.pathname));
      if (!f.startsWith(SITE) || !existsSync(f) || statSync(f).isDirectory()) f = path.join(SITE, "index.html");
      return route.fulfill({ status: 200, contentType: types[path.extname(f)] || "application/octet-stream", body: readFileSync(f) });
    });
    await context.addInitScript(({ relay, pk, sk, key, seed }) => {
      sessionStorage.setItem("marble.local", relay);
      if (!localStorage.getItem("marble.me")) localStorage.setItem("marble.me", JSON.stringify({ pubkey: pk, method: "phone", phone: { sk, signer: pk, relays: [relay] } }));
      if (seed && !localStorage.getItem("marble.seeded")) { localStorage.setItem(key, JSON.stringify(seed)); localStorage.setItem("marble.seeded", "1"); }
    }, { relay: LOCAL, pk: me, sk: C.hex(C.randomKey()), key: KEY, seed });
    return context;
  }
  async function tab(context) {
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    await page.goto(`${BASE}/notes`);
    for (let i = 0; i < 80 && (await page.evaluate(async () => (await import("/js/notes-vault.js")).state())) !== "open"; i++) await sleep(250);
    return { page, errors };
  }
  // a note typed in Notes and kept (Enter), then sent (as when the tab goes to the background)
  const jot = (page, text) => page.evaluate(async (t) => {
    const later = await import("/js/later.js"), v = await import("/js/notes-vault.js");
    later.jot(t);
    await v.flush();
    return v.state();
  }, text);
  const shown = (page) => page.evaluate(async () => (await import("/js/later.js")).items().map((i) => i.text).sort());

  // 1. two tabs, opened before this browser first wrote to this vault (it kept its device root from an earlier vault)
  const ctx = await browserWith({ root: C.hex(C.randomKey()) });
  const A = await tab(ctx), B = await tab(ctx);
  ok((await A.page.evaluate(async () => (await import("/js/notes-vault.js")).state())) === "open", "the vault opens in tab A");
  ok((await B.page.evaluate(async () => (await import("/js/notes-vault.js")).state())) === "open", "the vault opens in tab B");
  ok((await jot(A.page, "from tab A")) === "open", "a note is written in tab A");
  ok((await jot(B.page, "from tab B")) === "open", "a note is written in tab B");
  const subs = (() => {
    const s = new Set();
    for (const e of events) if (e.kind === 30810) s.add(e.pubkey);
    return s.size;
  })();
  ok(subs === 2, `one key for the phone, one for this browser (${subs} device keys wrote)`);
  let other = await seen();
  ok(JSON.stringify(other) === JSON.stringify(["from tab A", "from tab B", "from the phone"]), `your other device sees both tabs' notes: ${JSON.stringify(other)}`);
  // and this browser, opened again, still shows them (its own key counts for it too)
  const A2 = await tab(ctx);
  ok(JSON.stringify(await shown(A2.page)) === JSON.stringify(["from tab A", "from tab B", "from the phone"]), `a reloaded tab shows them: ${JSON.stringify(await shown(A2.page))}`);
  ok([A, B, A2].every((t) => !t.errors.length), `no page errors: ${[A, B, A2].flatMap((t) => t.errors).join(" | ")}`);
  await ctx.close();

  // 2. a browser whose key already went dead that way (two tabs, before the fix): it moves to a new key by itself and
  //    carries what it wrote that still stands, so the notes show on your other devices
  const root = C.hex(C.randomKey());
  const tab1 = memoryStore(), tab2 = memoryStore();
  tab1.set("root", root); tab2.set("root", root);
  const t1 = await otherDevice(tab1, "Marble Web (Chrome on Mac)"), t2 = await otherDevice(tab2, "Marble Web (Chrome on Mac)");
  const put = (id, text) => N.notePut({ id, text, created: now(), updated: now() }, []);
  await t1.commit([put("aaaaaaaaaaaaaaaa", "stranded one")]);
  await t2.commit([put("bbbbbbbbbbbbbbbb", "stranded two")]);
  other = await seen();
  ok(!other.includes("stranded one") && !other.includes("stranded two"), `(the dead key's notes don't show elsewhere: ${JSON.stringify(other)})`);
  const ctx2 = await browserWith(Object.fromEntries(tab2.m));
  const H = await tab(ctx2);
  for (let i = 0; i < 40 && !(await seen()).includes("stranded two"); i++) await sleep(250);
  other = await seen();
  ok(other.includes("stranded one") && other.includes("stranded two"), `after this browser opens Notes, your other device sees what it wrote: ${JSON.stringify(other)}`);
  ok(JSON.stringify(await shown(H.page)) === JSON.stringify(other), `…and so does this browser: ${JSON.stringify(await shown(H.page))}`);
  ok((await jot(H.page, "after the move")) === "open" && (await seen()).includes("after the move"), "a note written after the move shows elsewhere");
  ok(!H.errors.length, `no page errors: ${H.errors.join(" | ")}`);
  await ctx2.close();
} finally {
  await browser.close();
}
console.log(`vault two tabs page: ${n} checks passed`);
