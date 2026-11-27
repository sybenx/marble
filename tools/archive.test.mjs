// Your archive (site/js/relay-auth.js, private-relays.js, and the login in signin/pool.js): who may be logged in to, the
// login's shape, one retry only, and the account copy's merge, as Marble Android does them.   node tools/archive.test.mjs
import { allowed, loginEvent, needsAuth, Session, norm, KIND } from "../site/js/relay-auth.js";
import { parse, label, fromJson, toJson, merge, ahead, nextAt, searchFilter, relevant, TAG } from "../site/js/private-relays.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const same = (a, b, m) => ok(JSON.stringify(a) === JSON.stringify(b), `${m}: ${JSON.stringify(a)} vs ${JSON.stringify(b)}`);

// ── the allow check ──
const chosen = ["wss://Relay.Example.com/", "wss://archive.example.com/inbox"];
ok(allowed("wss://relay.example.com", chosen), "same relay, other case and slash");
ok(allowed("WSS://RELAY.EXAMPLE.COM//", chosen), "scheme and host in capitals, slashes");
ok(allowed("wss://archive.example.com/inbox/", chosen), "a path");
ok(!allowed("wss://archive.example.com", chosen), "the path matters");
ok(!allowed("wss://other.example.com", chosen), "not chosen");
ok(!allowed("https://relay.example.com", chosen), "not a relay address");
ok(!allowed("wss://relay.example.com", []), "nothing chosen");
ok(!allowed("garbage", chosen), "garbage");
ok(norm("wss://A.b/") === "wss://a.b" && norm("ws://x.y/p/") === "ws://x.y/p", "norm");

// ── the login ──
same(loginEvent("ab".repeat(32), "wss://a.example", "ch1", 100),
  { kind: 22242, pubkey: "ab".repeat(32), created_at: 100, tags: [["relay", "wss://a.example"], ["challenge", "ch1"]], content: "" }, "kind 22242");
ok(KIND === 22242 && needsAuth("auth-required: please") && !needsAuth("blocked: no") && !needsAuth(undefined), "needsAuth");

// ── one retry only (the state machine) ──
{
  const s = new Session("wss://a", true);
  same(s.onAuthRequired("sub1"), [], "refused before a challenge: wait for one");
  same(s.onChallenge("c"), [{ send: "c" }], "challenge: log in");
  same(s.onChallenge("c"), [], "same challenge: not again");
  same(s.authSent("ev1"), [], "login sent");
  same(s.onOk("other", true), { ours: false, actions: [] }, "someone else's OK");
  same(s.onOk("ev1", true), { ours: true, actions: [{ resend: "sub1" }] }, "logged in: sent again");
  same(s.onAuthRequired("sub1"), [{ giveUp: "sub1" }], "refused again: never a second retry");
  same(s.onAuthRequired("sub2"), [{ resend: "sub2" }], "another request, logged in: once");
  same(s.onAuthRequired("sub2"), [{ giveUp: "sub2" }], "and only once");
}
{
  const s = new Session("wss://a", () => false);
  same(s.onChallenge("c"), [], "not chosen: no login");
  same(s.onAuthRequired("x"), [{ giveUp: "x" }], "not chosen: stays refused");
}
{
  const s = new Session("wss://a", true);
  s.onChallenge("c"); s.onAuthRequired("x");
  same(s.authSent(null), [{ giveUp: "x" }], "couldn't sign: gives up");
  same(s.onAuthRequired("y"), [{ giveUp: "y" }], "and doesn't try again");
}

// ── the pool: a relay that asks for a login, end to end with a fake socket ──
{
  globalThis.location = { hostname: "example.com" };
  const sockets = [];
  globalThis.WebSocket = class { constructor(url) { this.url = url; this.readyState = 1; this.out = []; sockets.push(this); } send(m) { this.out.push(JSON.parse(m)); } close() {} };
  const pool = await import("../site/js/signin/pool.js");
  const signed = [];
  pool.setAuth({
    session: (url) => new Session(url, () => allowed(url, ["wss://archive.example.com"])),
    sign: async (url, ch) => { signed.push([url, ch]); return { id: "login" + signed.length, ...loginEvent("ab".repeat(32), url, ch, 1) }; },
  });
  const tick = () => new Promise((r) => setTimeout(r, 0));
  const p = pool.req(["wss://archive.example.com"], { kinds: [1], search: "x" }, { timeout: 2000 });
  await tick(); await tick();
  const ws = sockets.find((s) => s.url === "wss://archive.example.com");
  const reqs = () => ws.out.filter((m) => m[0] === "REQ");
  const subId = reqs()[0][1];
  const say = (m) => ws.onmessage({ data: JSON.stringify(m) });
  say(["AUTH", "chal"]);
  say(["CLOSED", subId, "auth-required: log in"]);
  await tick();
  ok(signed.length === 1 && signed[0][1] === "chal", "one login signed for the challenge");
  const auth = ws.out.find((m) => m[0] === "AUTH");
  ok(auth && auth[1].kind === 22242 && auth[1].tags[1][1] === "chal", "AUTH sent with the 22242");
  say(["OK", auth[1].id, true, ""]);
  ok(reqs().length === 2, "the request sent once more after the login");
  say(["CLOSED", subId, "auth-required: still no"]);
  ok(reqs().length === 2, "refused again: not sent a third time");
  const got = await p;
  ok(got.length === 0 && got.answered === 0, "ends as not answered");

  // a relay nobody chose: asks, gets nothing
  const q = pool.req(["wss://stranger.example.com"], { kinds: [1] }, { timeout: 2000 });
  await tick(); await tick();
  const ws2 = sockets.find((s) => s.url === "wss://stranger.example.com");
  ws2.onmessage({ data: JSON.stringify(["AUTH", "c2"]) });
  ws2.onmessage({ data: JSON.stringify(["CLOSED", ws2.out[0][1], "auth-required: x"]) });
  await tick();
  ok(signed.length === 1 && !ws2.out.some((m) => m[0] === "AUTH") && ws2.out.filter((m) => m[0] === "REQ").length === 1, "not chosen: no login, no retry");
  await q;
}

// ── private relays ──
ok(TAG === "marble/private-relays", "d tag");
ok(parse("archive.example.com") === "wss://archive.example.com", "wss:// added");
ok(parse(" WSS://Archive.Example.com/ ") === "wss://archive.example.com", "a full address, normalised");
ok(parse("wss://archive.example.com/me/") === "wss://archive.example.com/me", "a path");
for (const bad of ["", "hello", "not an address", "https://archive.example.com", "wss://", "a b.com"]) ok(parse(bad) === null, "refused: " + bad);
ok(label("wss://archive.example.com/me") === "archive.example.com/me", "label");

// Android's copy, exactly as it writes it
const android = '{"v":1,"relays":[{"url":"wss://a.example.com","at":100,"gone":false},{"url":"wss://b.example.com","at":200,"gone":true}]}';
same(fromJson(android), [{ url: "wss://a.example.com", at: 100, gone: false }, { url: "wss://b.example.com", at: 200, gone: true }], "reads Android's copy");
ok(toJson(fromJson(android)) === android, "writes it the same way");
ok(fromJson('{"v":2,"relays":[]}') === null && fromJson("nope") === null, "a newer format or garbage: not read");
same(fromJson('{"v":1}'), [], "no relays");

const mine = [{ url: "wss://a.example.com", at: 150, gone: true }, { url: "wss://c.example.com", at: 10, gone: false }];
const theirs = fromJson(android);
same(merge(theirs, mine), [
  { url: "wss://a.example.com", at: 150, gone: true },
  { url: "wss://b.example.com", at: 200, gone: true },
  { url: "wss://c.example.com", at: 10, gone: false },
], "per relay, newest change wins; removed ones kept");
ok(ahead(theirs, mine), "this one has something the account copy hasn't");
ok(!ahead(theirs, [{ url: "wss://a.example.com", at: 50, gone: false }]), "an older change: nothing to write");
ok(nextAt([{ url: "wss://a.example.com", at: 5000 }], "wss://a.example.com", 100) === 5001 && nextAt([], "wss://x.y", 100) === 100, "next change is newer");

same(searchFilter("cats"), { kinds: [1, 30023, 0], search: "cats", limit: 100 }, "search filter (more than shown: relevant() picks)");
same(searchFilter("#cats", "cats"), { kinds: [1, 30023], "#t": ["cats"], limit: 40 }, "tag filter");

// ── which archive posts show (as Android's PrivateRelays.relevant): every word, the whole phrase first, then newest ──
{
  const p = (id, content, at, title) => ({ id, content, created_at: at, kind: 1, tags: title ? [["title", title]] : [] });
  const got = relevant([p("a", "new things", 5), p("b", "an app", 6), p("c", "the app is new", 7), p("d", "a new app!", 3), p("e", "nothing", 9), p("f", "notes", 8, "New App")], "new app");
  same(got.map((e) => e.id), ["f", "d", "c"], "every word; the phrase first, then newest; the title counts");
  same(relevant([p("a", "x", 1), p("b", "y", 2)], "#x", "x").map((e) => e.id), ["b", "a"], "a #tag: as it is, newest first");
  same(relevant([p("a", "x", 1)], "  "), [], "no words: nothing");
}

console.log(`archive: ${n} checks passed`);
process.exit(0);
