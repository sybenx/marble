// The login-page pointer (Keykin SPEC §11.1): kind 10046 and its nostrconnect_url template, the old kind 30078 only when
// there's no 10046 at all, removal, and the link built from it.   node tools/login-pointer.test.mjs
import { schnorr, sha256 } from "../site/js/signin/vendor/noble.js";
import { toHex, fromHex } from "../site/js/signin/ids.js";
import { loginTemplate, legacyTemplate, loginLink } from "../site/js/signin/connect.js";
import { pointerOf, POINTER } from "../site/js/signin/pointer.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const sk = schnorr.utils.randomSecretKey(), pk = toHex(schnorr.getPublicKey(sk));
const other = schnorr.utils.randomSecretKey();
function signed(kind, tags, at, key = sk) {
  const t = { kind, pubkey: toHex(schnorr.getPublicKey(key)), created_at: at, tags, content: "" };
  const id = toHex(sha256(new TextEncoder().encode(JSON.stringify([0, t.pubkey, t.created_at, t.kind, t.tags, t.content]))));
  return { ...t, id, sig: toHex(schnorr.sign(fromHex(id), key)) };
}
const T = "https://account.syb.lol/#connect=<nostrconnect>";

// good templates
ok(loginTemplate(T)?.origin === "https://account.syb.lol" && loginTemplate(T).template === T, "Keykin's template");
ok(loginTemplate("https://login.example.org/signin?code=<nostrconnect>")?.origin === "https://login.example.org", "another shape, placeholder at the end");
ok(loginTemplate("https://a.example:8443/#connect=<nostrconnect>")?.origin === "https://a.example:8443", "a port");
// bad templates
for (const [t, why] of [
  ["http://account.syb.lol/#connect=<nostrconnect>", "not https"],
  ["http://localhost:8080/#connect=<nostrconnect>", "http localhost when this isn't localhost"],
  ["https://me:pw@account.syb.lol/#connect=<nostrconnect>", "user and password"],
  ["https://me@account.syb.lol/#connect=<nostrconnect>", "user"],
  ["https://account.syb.lol/#connect=", "no placeholder"],
  ["https://account.syb.lol/#connect=<nostrconnect>&x=1", "placeholder not at the end"],
  ["https://account.syb.lol/<nostrconnect>#connect=<nostrconnect>", "two placeholders"],
  ["javascript:alert(1)//<nostrconnect>", "not a web address"],
  ["<nostrconnect>", "only the placeholder"],
  [42, "not a string"],
  [undefined, "missing"],
]) ok(loginTemplate(t) === null, "refused: " + why);

// the link
const code = "nostrconnect://" + "a".repeat(64) + "?relay=wss%3A%2F%2Fsyb.lol&secret=s1&name=Marble&url=https%3A%2F%2Fmarble.syb.lol";
const link = loginLink(T, code);
ok(link === "https://account.syb.lol/#connect=" + encodeURIComponent(code), "placeholder replaced by the code, encoded once");
ok(!link.includes("<nostrconnect>") && decodeURIComponent(link.split("#connect=")[1]) === code, "decodes back to the code");
ok(loginLink(legacyTemplate("https://old.example"), code) === "https://old.example/#connect=" + encodeURIComponent(code), "old origin as the template");

// the lookup's decision
const p10046 = (tpl, at) => signed(POINTER, tpl === null ? [["relays", "wss://syb.lol"]] : [["nostrconnect_url", tpl]], at);
const p30078 = (url, at) => signed(30078, [["d", "keykin/login"], ...(url ? [["url", url]] : [])], at);
let r = pointerOf([p10046(T, 100)], pk);
ok(r.url === "https://account.syb.lol" && r.template === T && !r.legacy, "10046 found");
r = pointerOf([p10046("https://old.example/#connect=<nostrconnect>", 100), p10046(T, 200)], pk);
ok(r.template === T, "newest 10046 wins");
r = pointerOf([p10046(T, 100), p30078("https://old.example", 500)], pk);
ok(r.template === T, "a 10046 wins over a newer 30078");
r = pointerOf([p30078("https://old.example", 100)], pk);
ok(r.legacy && r.url === "https://old.example" && r.template === "https://old.example/#connect=<nostrconnect>", "no 10046 at all: the old kind, as the template");
r = pointerOf([p10046(null, 300), p30078("https://old.example", 100)], pk);
ok(r.none && r.removed && !r.template, "newest 10046 without nostrconnect_url: removed, no falling back");
r = pointerOf([p10046(T, 100), p10046(null, 300)], pk);
ok(r.none && r.removed, "removed after an older good one");
r = pointerOf([p10046("http://evil.example/#connect=<nostrconnect>", 300), p30078("https://old.example", 100)], pk);
ok(r.none && !r.template, "newest 10046 with a bad template: nothing, no falling back");
r = pointerOf([p30078(null, 100)], pk);
ok(r.none && r.removed, "old kind with no url: removed");
ok(pointerOf([], pk).none && !pointerOf([], pk).removed, "nothing found");
// only the account's own, signed as they are
ok(pointerOf([signed(POINTER, [["nostrconnect_url", T]], 100, other)], pk).none, "someone else's 10046 doesn't count");
const forged = { ...p10046(T, 100) }; forged.tags = [["nostrconnect_url", "https://evil.example/#connect=<nostrconnect>"]];
ok(pointerOf([forged], pk).none, "a changed 10046 doesn't count");
ok(pointerOf([forged, p30078("https://old.example", 50)], pk).legacy, "a forged 10046 is no 10046: the old kind is read");

console.log(`login pointer: ${n} checks passed`);
