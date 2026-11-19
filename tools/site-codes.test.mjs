// A site's own sign-in code and the login page's link (Keykin SPEC §11.6): no kk=account on a site's code, `connect=`
// last in the fragment and URL-encoded once, and the link opened with no noreferrer.   node tools/site-codes.test.mjs
import { readFileSync } from "node:fs";
import { siteCode, loginLink } from "../site/js/connect.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const pk = "a".repeat(64), perms = "sign_event:1,nip44_decrypt,get_public_key";
const base = { pk, relays: ["wss://syb.lol", "wss://relay.damus.io"], secret: "0123456789abcdef", name: "Marble", url: "https://marble.syb.lol", perms };

const code = siteCode(base);
ok(code.startsWith(`nostrconnect://${pk}?relay=wss%3A%2F%2Fsyb.lol&relay=wss%3A%2F%2Frelay.damus.io&secret=0123456789abcdef`), "key, relays, secret");
const q = new URLSearchParams(code.slice(code.indexOf("?") + 1));
ok(q.get("name") === "Marble" && q.get("url") === "https://marble.syb.lol" && q.get("perms") === perms, "name, url, perms");
ok(!q.has("kk") && !/kk=account/.test(code), "no kk=account on a site's code");
const missing = siteCode({ ...base, extra: "&kk_login=missing" });
ok(new URLSearchParams(missing.split("?")[1]).get("kk_login") === "missing" && !/kk=account/.test(missing), "kk_login=missing alone");

const link = loginLink("https://account.syb.lol", code);
ok(link.startsWith("https://account.syb.lol/#connect=nostrconnect%3A%2F%2F"), "connect= in the fragment, encoded once");
const frag = link.slice(link.indexOf("#") + 1);
ok(frag.indexOf("connect=") === 0 && !frag.slice(8).includes("&") && !frag.slice(8).includes("#"), "connect= last, nothing after it");
ok(decodeURIComponent(frag.slice(8)) === code, "decodes back to the code");

// the link to the login page sends the referrer (the page checks the sender by it)
const act = readFileSync(new URL("../site/js/act.js", import.meta.url), "utf8");
const away = act.split("\n").filter((l) => l.includes("loginLink(") && l.includes("<a "));
ok(away.length >= 1 && away.every((l) => !/noreferrer/.test(l)), "login link has no noreferrer");
ok(!/kk=account/.test(readFileSync(new URL("../site/js/connect.js", import.meta.url), "utf8").replace(/^\s*(\*|\/\/).*$/gm, "")), "connect.js code adds no kk=account");

console.log(`site codes: ${n} checks passed`);
