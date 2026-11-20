// A site's own sign-in code and the login page's link (Keykin SPEC §11.6): no kk=account on a site's code, `connect=`
// last in the fragment and URL-encoded once, and the link opened with no noreferrer.   node tools/site-codes.test.mjs
import { readFileSync } from "node:fs";
import { siteCode, loginLink, deviceLabel, shortKey } from "../site/js/signin/connect.js";

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

const link = loginLink("https://account.syb.lol/#connect=<nostrconnect>", code);
ok(link.startsWith("https://account.syb.lol/#connect=nostrconnect%3A%2F%2F"), "connect= in the fragment, encoded once");
const frag = link.slice(link.indexOf("#") + 1);
ok(frag.indexOf("connect=") === 0 && !frag.slice(8).includes("&") && !frag.slice(8).includes("#"), "connect= last, nothing after it");
ok(decodeURIComponent(frag.slice(8)) === code, "decodes back to the code");

// the link to the login page sends the referrer (the page checks the sender by it)
const act = ["act.js", "signin/dialog.js"].map((f) => readFileSync(new URL("../site/js/" + f, import.meta.url), "utf8")).join("\n");
const away = act.split("\n").filter((l) => l.includes("loginLink(") && l.includes("<a "));
ok(away.length >= 1 && away.every((l) => !/noreferrer/.test(l)), "login link has no noreferrer");
ok(!/kk=account/.test(readFileSync(new URL("../site/js/signin/connect.js", import.meta.url), "utf8").replace(/^\s*(\*|\/\/).*$/gm, "")), "connect.js code adds no kk=account");

// device= (Keykin SPEC 11.5): a short label for this browser, so the account page can tell sign-ins apart
const labelled = new URLSearchParams(siteCode({ ...base, device: "Chrome on Windows", extra: "&kk_login=missing" }).split("?")[1]);
ok(labelled.get("device") === "Chrome on Windows" && labelled.get("kk_login") === "missing", "device= on the code, extra after it");
ok(!siteCode(base).includes("device="), "no device= when there is no label");
ok(new URLSearchParams(siteCode({ ...base, device: "x".repeat(90) }).split("?")[1]).get("device").length === 60, "device= at most 60");
const win = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
ok(deviceLabel({ userAgent: win }) === "Chrome on Windows", "Chrome on Windows from userAgent");
ok(deviceLabel({ userAgent: win, userAgentData: { brands: [{ brand: "Microsoft Edge" }, { brand: "Chromium" }], platform: "Windows" } }) === "Edge on Windows", "userAgentData first");
ok(deviceLabel({ userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1" }) === "Safari on iPhone", "Safari on iPhone");
ok(deviceLabel({ userAgent: "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0" }) === "Firefox on Linux", "Firefox on Linux");
ok(deviceLabel({}) === "", "nothing known: no label");
const app = readFileSync(new URL("../site/js/signin/connect.js", import.meta.url), "utf8");
ok(/device: deviceLabel\(\)/.test(app), "the phone sign-in code carries this browser's label");
// "This browser: key ab12" is the first 4 hex of the client key, as the account page shows it
ok(await shortKey("nothex") === null, "no key, no short key");
const sk = "1".repeat(64);
const { schnorr } = await import("../site/js/signin/vendor/sign.js");
const hex = [...schnorr.getPublicKey(Uint8Array.from(sk.match(/../g), (h) => parseInt(h, 16)))].map((b) => b.toString(16).padStart(2, "0")).join("");
ok(await shortKey(sk) === hex.slice(0, 4), "short key is the client key's first 4 hex");
ok(/This browser: key \$\{k\}/.test(readFileSync(new URL("../site/js/app.js", import.meta.url), "utf8")), "the account menu shows it");

console.log(`site codes: ${n} checks passed`);
