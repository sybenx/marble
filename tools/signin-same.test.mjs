// The sign-in, before and after it moved to signin/ (Keykin's shared copy): the same screens, word for word, and a full
// sign-in that ends the same way. Marble Web as it was (main, given as a checkout, or else its last commit before the
// move from git) and this checkout are each served with tools/serve-site.mjs, one at a time on the same port, and opened
// in headless Chromium. Nothing outside is reached: every WebSocket is answered here (a small relay, and a signer app
// that answers NIP-46), the public relays are closed at once, and every other site gets an empty answer.
//   node tools/signin-same.test.mjs [path to a main checkout]
// Needs ~/keykin/node_modules/playwright-core and a Playwright Chromium in ~/Library/Caches/ms-playwright; skipped
// without them.
import { spawn, execSync, execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { deepStrictEqual as same } from "node:assert";

const here = new URL("..", import.meta.url).pathname;
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
if (!existsSync(pwPath) || !exe) { console.log(`signin same: SKIPPED (needs ${pwPath} and a Playwright Chromium)`); process.exit(0); }
const { chromium: pw } = await import(pwPath);

// Node side: keys, signing and NIP-44 from the package (the same code main had, byte for byte)
const { schnorr, sha256 } = await import(new URL("../site/js/signin/vendor/sign.js", import.meta.url));
const { toHex, fromHex, npub } = await import(new URL("../site/js/signin/ids.js", import.meta.url));
const { encrypt, decrypt, conversationKey } = await import(new URL("../site/js/signin/connect.js", import.meta.url));
const utf8 = new TextEncoder();
const now = () => Math.floor(Date.now() / 1000);
function signed(t, sk) {
  const pubkey = toHex(schnorr.getPublicKey(sk));
  const id = toHex(sha256(utf8.encode(JSON.stringify([0, pubkey, t.created_at, t.kind, t.tags, t.content]))));
  return { ...t, pubkey, id, sig: toHex(schnorr.sign(fromHex(id), sk)) };
}
const key = () => { const sk = schnorr.utils.randomSecretKey(); return { sk, pk: toHex(schnorr.getPublicKey(sk)) }; };

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };

// ── The world both sides see: the same accounts, pointer, invite and answers ──
const LOCAL = "ws://127.0.0.1:7777"; // never opened: answered here
const alice = key(), carol = key(), user = key(), signer = key();
const pointer = signed({ kind: 10046, created_at: now() - 60, tags: [["nostrconnect_url", "https://login.example/#connect=<nostrconnect>"]], content: "" }, alice.sk);
const invite = JSON.stringify({ keykin: 2, invite: { key: "ab".repeat(32), name: "Marble on Mac" } });
const challenge = "A".repeat(43), state = "abcdefgh12", ret = "http://127.0.0.1:5555/done";
const ADD_OK = `#challenge=${challenge}&return=${encodeURIComponent(ret)}&state=${state}&add=${encodeURIComponent(invite)}`;
const ADD_INCOMPLETE = `#return=${encodeURIComponent(ret)}&state=${state}&add=${encodeURIComponent(invite)}`;
const NIP05 = { "https://example.test/.well-known/nostr.json?name=alice": { names: { alice: alice.pk } }, "https://nobody.example.test/.well-known/nostr.json?name=_": { names: {} } };

const matches = (e, f) => (!f.ids || f.ids.includes(e.id)) && (!f.kinds || f.kinds.includes(e.kind)) && (!f.authors || f.authors.includes(e.pubkey))
  && (f.since == null || e.created_at >= f.since) && (f.until == null || e.created_at <= f.until)
  && Object.entries(f).every(([k, v]) => !k.startsWith("#") || e.tags.some((t) => t[0] === k.slice(1) && v.includes(t[1])));

/** A relay and a signer app for one page: the relay keeps what it's sent; the signer answers NIP-46 for `user`. */
function world() {
  const events = [pointer], subs = new Map(), log = { sockets: [], methods: [] };
  let nextSub = 0;
  const deliver = (e) => {
    for (const [, s] of subs) for (const [id, filters] of s.subs) if (filters.some((f) => matches(e, f))) s.ws.send(JSON.stringify(["EVENT", id, e]));
  };
  const take = async (e) => {
    events.push(e);
    deliver(e);
    if (e.kind !== 24133 || !e.tags.some((t) => t[0] === "p" && t[1] === signer.pk)) return;
    const ck = await conversationKey(signer.sk, e.pubkey);
    let msg;
    try { msg = JSON.parse(await decrypt(e.content, ck)); } catch { return; }
    log.methods.push(msg.method);
    let result = null, error = null;
    if (msg.method === "connect") result = "ack";
    else if (msg.method === "get_public_key") result = user.pk;
    else if (msg.method === "sign_event") result = JSON.stringify(signed(JSON.parse(msg.params[0]), user.sk));
    else if (msg.method === "logout") result = "ack";
    else error = "unsupported";
    await answer(e.pubkey, { id: msg.id, ...(error ? { error } : { result }) });
  };
  const answer = async (to, body) => take(signed({ kind: 24133, created_at: now(), tags: [["p", to]], content: await encrypt(JSON.stringify(body), await conversationKey(signer.sk, to)) }, signer.sk));
  return {
    log,
    /** The signer app scanned a nostrconnect code: it answers with the code's secret. */
    async scanned(uri) {
      const client = uri.slice("nostrconnect://".length, "nostrconnect://".length + 64);
      const secret = new URLSearchParams(uri.slice(uri.indexOf("?") + 1)).get("secret");
      await answer(client, { id: toHex(crypto.getRandomValues(new Uint8Array(8))), result: secret });
    },
    handler(ws) {
      const url = ws.url().replace(/\/$/, "");
      log.sockets.push(url);
      if (url !== LOCAL) return ws.close(); // a public relay: not reached
      const me = { ws, subs: new Map() }, sid = nextSub++;
      subs.set(sid, me);
      ws.onClose(() => subs.delete(sid));
      ws.onMessage(async (m) => {
        let msg;
        try { msg = JSON.parse(m); } catch { return; }
        if (msg[0] === "REQ") {
          const filters = msg.slice(2);
          me.subs.set(msg[1], filters);
          for (const e of events) if (filters.some((f) => matches(e, f))) ws.send(JSON.stringify(["EVENT", msg[1], e]));
          ws.send(JSON.stringify(["EOSE", msg[1]]));
        } else if (msg[0] === "CLOSE") me.subs.delete(msg[1]);
        else if (msg[0] === "EVENT") { ws.send(JSON.stringify(["OK", msg[1].id, true, ""])); await take(msg[1]); }
      });
    },
  };
}

// ── What a page did: its errors, its module requests, what it wrote to storage, what it asked outside ──
async function opened(browser, base, side, report) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const w = world();
  const seen = { errors: [], modules: new Map(), badModules: [], outside: [], keys: new Set(), cutOff: [], reloading: false };
  await page.routeWebSocket(/.*/, (ws) => w.handler(ws));
  await page.route((u) => u.origin !== base, (route) => {
    const url = route.request().url();
    seen.outside.push(url);
    if (NIP05[url]) return route.fulfill({ status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify(NIP05[url]) });
    return route.fulfill({ status: 200, contentType: "text/plain", headers: { "access-control-allow-origin": "*" }, body: "" });
  });
  await page.exposeBinding("__storageKey", (_, where, k) => { seen.keys.add(`${where}:${String(k).replace(/[0-9a-f]{64}/g, "<hex>")}`); });
  await page.addInitScript((r) => {
    const set = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k, v) {
      try { if (k !== "marble.local") window.__storageKey(this === window.localStorage ? "local" : "session", k); } catch {}
      return set.call(this, k, v);
    };
    set.call(sessionStorage, "marble.local", r);
  }, LOCAL);
  page.on("console", (m) => { if (m.type() === "error") seen.errors.push(m.text()); });
  page.on("pageerror", (e) => seen.errors.push(String(e.message || e)));
  page.on("request", (q) => { const u = new URL(q.url()); if (u.origin === base && u.pathname.startsWith("/js/")) seen.modules.set(u.pathname, (seen.modules.get(u.pathname) || 0) + 1); });
  // (a module still loading when the test closes or reloads the page is cut off, not failed)
  let closing = false;
  page.on("requestfailed", (q) => {
    if (!q.url().startsWith(base + "/js/")) return;
    if ((closing || seen.reloading) && q.failure()?.errorText === "net::ERR_ABORTED") seen.cutOff.push(q.url());
    else seen.badModules.push(`${q.url()} ${q.failure()?.errorText}`);
  });
  page.on("response", (r) => { const u = r.url(); if (u.startsWith(base + "/js/") && (r.status() !== 200 || !/javascript/.test(r.headers()["content-type"] || ""))) seen.badModules.push(u); });
  const done = async () => {
    closing = true;
    for (const k of seen.keys) report.keys.add(k);
    report.errors.push(...seen.errors.map((e) => `${side}: ${e}`));
    report.badModules.push(...seen.badModules);
    report.cutOff.push(...seen.cutOff.map((u) => new URL(u).pathname));
    for (const u of seen.outside) report.outside.add(u);
    await context.close();
  };
  return { page, w, seen, done };
}

/** The sign-in dialog (and the account page's) as drawn, with only the random parts made plain. */
const plain = (html) => html == null ? null : html
  .replace(/(<div class="qr">)[\s\S]*?(<\/div>)/g, "$1QR$2")
  .replace(/(secret(?:=|%3D))[0-9a-f]{16}/g, "$1<secret>")
  .replace(/[0-9a-f]{64}/g, "<hex>");
async function snap(page) {
  const s = await page.evaluate(() => ({
    signin: document.querySelector("#signin")?.innerHTML ?? null, signinOpen: !!document.querySelector("#signin")?.open,
    acct: document.querySelector("#acct")?.innerHTML ?? null, acctOpen: !!document.querySelector("#acct")?.open,
    focus: document.activeElement?.id || document.activeElement?.getAttribute("data-si") || document.activeElement?.tagName || null,
    toast: document.querySelector("#toast")?.hidden === false ? document.querySelector("#toast").textContent : null,
  }));
  return { ...s, signin: plain(s.signin), acct: plain(s.acct) };
}
const settle = (page) => page.waitForTimeout(400);

// ── The screens ──
const W = { timeout: 15000 };
async function firstScreen(page, base, at = "/") {
  await page.goto(base + at);
  await page.click("#account .sign-in", W);
  await page.waitForSelector("#signin[open] #si-who", W);
}
async function typed(page, name) {
  await page.fill("#si-who", name);
  await page.click("#signin form[data-form] button[type=submit]");
}
const scenarios = {
  "first screen": async (page, base) => { await firstScreen(page, base); },
  "sign in with your phone": async (page, base) => { await firstScreen(page, base); await page.click("[data-si=code]"); await page.waitForSelector("#signin .qr svg", W); },
  "use a signer app": async (page, base) => { await firstScreen(page, base); await page.click("[data-si=signer]"); await page.waitForSelector("#signin .qr svg", W); },
  "signer app, back": async (page, base) => { await firstScreen(page, base); await page.click("[data-si=signer]"); await page.waitForSelector("#signin .qr svg", W); await page.click("[data-si=unsigner]"); await page.waitForSelector("#si-who", W); },
  "a name with a login page": async (page, base) => { await firstScreen(page, base); await typed(page, "alice@example.test"); await page.waitForSelector("a.si-open[data-si=away]", W); },
  "a name with a login page, not you": async (page, base) => { await firstScreen(page, base); await typed(page, "alice@example.test"); await page.waitForSelector("a.si-open[data-si=away]", W); await page.click("[data-si=retype]"); await page.waitForSelector("#si-who", W); },
  "an account with no login page": async (page, base) => { await firstScreen(page, base); await typed(page, npub(carol.pk)); await page.waitForSelector("#signin .qr svg", W); },
  "a name that finds none": async (page, base) => { await firstScreen(page, base); await typed(page, "nobody.example.test"); await page.waitForSelector("#signin .si-said", W); },
  "an email": async (page, base) => { await firstScreen(page, base); await typed(page, "someone@gmail.com"); await page.waitForSelector("#signin .si-said", W); },
  "your account page": async (page, base) => { await firstScreen(page, base); await page.click("[data-account-visitor]"); await page.waitForSelector("#acct[open] #acct-who", W); },
  "your account page, a name": async (page, base) => { await firstScreen(page, base); await page.click("[data-account-visitor]"); await page.fill("#acct-who", "alice@example.test"); await page.click("#acct button[type=submit]"); await page.waitForSelector("#acct a.si-open", W); },
  "add a Mac, signed out": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("#signin[open] form[data-form=addname]", W); },
  "add a Mac, a name with a login page": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("form[data-form=addname]", W); await typed(page, "alice@example.test"); await page.waitForSelector("a.si-open[data-add=away]", W); },
  "add a Mac, not you": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("form[data-form=addname]", W); await typed(page, "alice@example.test"); await page.waitForSelector("a.si-open[data-add=away]", W); await page.click("[data-add=retype]"); await page.waitForSelector("form[data-form=addname]", W); },
  "add a Mac, no login page": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("form[data-form=addname]", W); await typed(page, npub(carol.pk)); await page.waitForSelector("#signin .qr svg", W); },
  "add a Mac, an email": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("form[data-form=addname]", W); await typed(page, "someone@gmail.com"); await page.waitForSelector("#signin .si-said", W); },
  "add a Mac, not now": async (page, base) => { await page.goto(base + "/" + ADD_OK); await page.waitForSelector("form[data-form=addname]", W); await page.click("[data-add=cancel]"); await page.waitForFunction(() => !document.querySelector("#signin").open, null, W); },
  "add a Mac, not complete": async (page, base) => { await page.goto(base + "/" + ADD_INCOMPLETE); await page.waitForSelector("#signin[open] .si-note", W); },
};

/** A full sign-in: the signer app answers, the page is signed in, stays so on reload, and Sign out clears it. */
async function fullSignIn(page, base, w, seen, how) {
  await firstScreen(page, base);
  await page.click("[data-si=signer]");
  await page.waitForSelector("#signin [data-si=copy]", W);
  if (how === "nostrconnect") await w.scanned(await page.getAttribute("[data-si=copy]", "data-code"));
  else {
    await page.fill("#si-bunker", `bunker://${signer.pk}?relay=${encodeURIComponent(LOCAL)}&secret=0123abcd`);
    await page.click("form[data-form=bunker] button[type=submit]");
  }
  await page.waitForFunction(() => document.body.dataset.signed === "1", null, W);
  const after = await snap(page);
  const me = await page.evaluate(() => localStorage.getItem("marble.me"));
  seen.reloading = true;
  await page.reload();
  seen.reloading = false;
  await page.waitForSelector("#account .me", W);
  const stays = await page.evaluate(() => ({ signed: document.body.dataset.signed, me: localStorage.getItem("marble.me") }));
  await page.click("#account .me");
  await page.click("[data-sign-out]");
  await page.waitForSelector("#account .sign-in", W);
  await settle(page);
  const out = await page.evaluate(() => ({ signed: document.body.dataset.signed ?? null, me: localStorage.getItem("marble.me") }));
  return { after, me: JSON.parse(me), stays, out, methods: [...w.log.methods] };
}
const shape = (v) => Array.isArray(v) ? v.map(shape) : v && typeof v === "object" ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, shape(v[k])])) : typeof v;

// ── Run both sides, one server at a time ──
async function freePort() {
  return new Promise((ok, no) => { const s = net.createServer(); s.unref(); s.on("error", no); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => ok(p)); }); });
}
async function serve(checkout, port) {
  const child = spawn(process.execPath, [path.join(checkout, "tools/serve-site.mjs"), String(port)], { stdio: ["ignore", "pipe", "pipe"] });
  await new Promise((ok, no) => {
    const t = setTimeout(() => no(new Error("server did not start")), 10000);
    child.stdout.on("data", (d) => { if (/serving site/.test(d)) { clearTimeout(t); ok(); } });
    child.on("exit", (c) => { clearTimeout(t); no(new Error("server exited " + c)); });
  });
  return child;
}
const stop = (child) => new Promise((ok) => { if (!child || child.exitCode !== null) return ok(); child.once("exit", ok); child.kill(); });

let mainDir = process.argv[2] ? path.resolve(process.argv[2]) : null, tmp = null;
if (!mainDir) { // main as it was: the commit before the move
  tmp = mkdtempSync(path.join(os.tmpdir(), "signin-same-"));
  execSync(`git -C "${here}" archive e48bafe site tools/serve-site.mjs | tar -x -C "${tmp}"`);
  mainDir = tmp;
}

const port = await freePort(), base = `http://127.0.0.1:${port}`;
const results = {};
let browser = null, server = null;
try {
  browser = await pw.launch({ executablePath: exe, headless: true });
  for (const [side, dir] of [["main", mainDir], ["branch", here]]) {
    const report = (results[side] = { screens: {}, keys: new Set(), errors: [], loadErrors: [], badModules: [], outside: new Set(), modules: {}, full: {}, cutOff: [] });
    server = await serve(dir, port);
    // loading the front page and a profile: no errors, every module once
    for (const at of ["/", "/" + npub(alice.pk)]) {
      const p = await opened(browser, base, side, report);
      await p.page.goto(base + at);
      await p.page.waitForSelector("#account .sign-in", W);
      await p.page.waitForTimeout(1500);
      report.loadErrors.push(...p.seen.errors.map((e) => `${at}: ${e}`));
      report.modules[at] = Object.fromEntries(p.seen.modules);
      await p.done();
    }
    for (const [name, go] of Object.entries(scenarios)) {
      const p = await opened(browser, base, side, report);
      try { await go(p.page, base); await settle(p.page); report.screens[name] = await snap(p.page); }
      catch (err) { report.screens[name] = { failed: String(err.message || err).split("\n")[0] }; }
      await p.done();
    }
    for (const how of ["nostrconnect", "bunker"]) {
      const p = await opened(browser, base, side, report);
      try { report.full[how] = await fullSignIn(p.page, base, p.w, p.seen, how); }
      catch (err) { report.full[how] = { failed: String(err.message || err).split("\n")[0] }; }
      await p.done();
    }
    await stop(server);
    server = null;
  }
} finally {
  await browser?.close().catch(() => {});
  await stop(server);
  if (tmp) rmSync(tmp, { recursive: true, force: true });
}

// SIGNIN_SAME_DUMP=<file>: what both sides drew, to look at
if (process.env.SIGNIN_SAME_DUMP) (await import("node:fs")).writeFileSync(process.env.SIGNIN_SAME_DUMP, JSON.stringify(results, (k, v) => v instanceof Set ? [...v] : v, 1));

// ── The same on both ──
const M = results.main, B = results.branch;
for (const name of Object.keys(scenarios)) {
  ok(!M.screens[name].failed, `main: ${name}: ${M.screens[name].failed}`);
  ok(!B.screens[name].failed, `branch: ${name}: ${B.screens[name].failed}`);
  try { same(B.screens[name], M.screens[name]); n++; } catch { throw new Error(`FAIL: ${name}: not the same\nmain:   ${JSON.stringify(M.screens[name])}\nbranch: ${JSON.stringify(B.screens[name])}`); }
}
ok(B.screens["first screen"].signin.includes("Your Nostr address or npub") && B.screens["add a Mac, not complete"].signin.includes("is not complete"), "the screens are the ones meant");
ok(B.screens["add a Mac, signed out"].signin.includes("Add Marble on Mac to your account"), "the add flow opened from #add=");
for (const at of ["/", "/" + npub(alice.pk)]) {
  ok(!B.loadErrors.some((e) => e.startsWith(at + ":")), `branch: no console errors loading ${at}: ${B.loadErrors.join(" | ")}`);
  const mods = B.modules[at];
  ok(mods["/js/signin/pool.js"] === 1 && mods["/js/signin/me.js"] === 1 && mods["/js/me.js"] === 1, `branch ${at}: pool.js and me.js once each: ${JSON.stringify(mods)}`);
  ok(!["/js/pool.js", "/js/ids.js", "/js/connect.js", "/js/account-page.js"].some((m) => mods[m]), `branch ${at}: nothing from the old paths`);
  ok(Object.values(mods).every((c) => c === 1), `branch ${at}: every module once: ${JSON.stringify(mods)}`);
}
ok(!B.badModules.length, `branch: no failed module requests: ${B.badModules.join(", ")}`);
ok(!M.badModules.length, `main: no failed module requests: ${M.badModules.join(", ")}`);
same([...B.keys].sort(), [...M.keys].sort()); n++;
ok(B.errors.length === M.errors.length, `console errors: main ${M.errors.length}, branch ${B.errors.length}\n${[...M.errors, ...B.errors].join("\n")}`);
ok([...B.outside].every((u) => !/^wss?:/.test(u)) && [...B.outside].sort().join() === [...M.outside].sort().join(), `the same outside requests: ${[...B.outside].join(", ")}`);

for (const how of ["nostrconnect", "bunker"]) {
  const m = M.full[how], b = B.full[how];
  ok(!m.failed, `main: full sign-in (${how}): ${m.failed}`);
  ok(!b.failed, `branch: full sign-in (${how}): ${b.failed}`);
  ok(b.me.pubkey === user.pk && b.me.method === "phone" && b.me.phone.signer === signer.pk && JSON.stringify(b.me.phone.relays) === JSON.stringify([LOCAL]), `branch (${how}): signed in as the signer's account`);
  same(shape(b.me), shape(m.me)); n++;
  ok(b.after.toast === "Signed in" && !b.after.signinOpen, `branch (${how}): "Signed in", dialog closed`);
  same(b.after, m.after); n++;
  ok(b.stays.signed === "1" && JSON.parse(b.stays.me).pubkey === user.pk, `branch (${how}): a reload stays signed in`);
  ok(b.out.signed === null && b.out.me === null, `branch (${how}): sign out clears it`);
  ok(b.methods.includes("get_public_key") && b.methods.includes("logout") && (how === "nostrconnect" || b.methods[0] === "connect"), `branch (${how}): the signer was asked ${b.methods}`);
  same(b.methods, m.methods); n++;
}

console.log(`modules cut off by the test's reload or close: main ${JSON.stringify(M.cutOff)}, branch ${JSON.stringify(B.cutOff)}`);
console.log(`signin same: ${n} checks passed (${Object.keys(scenarios).length} screens and 2 full sign-ins, main and branch; storage keys: ${[...B.keys].sort().join(", ")}; console errors on each side: ${B.errors.length})`);
