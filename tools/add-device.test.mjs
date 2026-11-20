// Adding Marble on a Mac (Keykin SPEC 11.6, `add=`): reading what the Mac's Sign in sends, and the link on to the
// login page.   node tools/add-device.test.mjs
// The whole way through (the Mac, this page in a browser, the login page, a phone) runs in marble-desktop's test/e2e.ts.
import { readInvite, addFromHash, addLink } from "../site/js/add-device.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const key = "ab".repeat(32), relay = "wss://syb.lol", secret = "c0ffee".repeat(4);
const invite = JSON.stringify({ keykin: 2, invite: { key, name: "Marble on Mac" } });

// the invite
ok(readInvite(invite)?.key === key && readInvite(invite).name === "Marble on Mac" && readInvite(invite).text === invite, "a Keykin invite");
ok(readInvite(JSON.stringify({ keykin: 2, invite: { key: key.toUpperCase() } }))?.name === "A new device", "no name: a new device, key in lower case");
for (const [t, why] of [
  ["not an invite", "not JSON"], [JSON.stringify({ keykin: 1, invite: { key } }), "another version"],
  [JSON.stringify({ keykin: 2, invite: { key: "ab12" } }), "a short key"], [JSON.stringify({ keykin: 2, join: { npub: key } }), "a join code"],
  ["{" + " ".repeat(4001) + "}", "too long"], [undefined, "nothing"],
]) ok(readInvite(t) === null, "refused: " + why);

// the fragment
let r = addFromHash("#relay=" + encodeURIComponent(relay) + "&secret=" + secret + "&add=" + encodeURIComponent(invite));
ok(r.invite?.text === invite && r.relay === relay && r.secret === secret && r.rest === "", "relay, secret and the invite, add= last");
r = addFromHash("#x=1&add=" + invite);
ok(r.invite?.text === invite && r.relay === null && r.rest === "x=1", "raw, after another parameter, which stays; no relay");
ok(addFromHash("#x=1") === null && addFromHash("") === null, "no add=: nothing");
ok(addFromHash("#add=" + encodeURIComponent(encodeURIComponent(invite))).invite === null, "encoded twice: not an invite");
ok(addFromHash("#relay=" + encodeURIComponent(relay) + "&add=" + encodeURIComponent(invite)).relay === null, "a relay with no secret is dropped");
ok(addFromHash("#relay=javascript%3Ax&secret=" + secret + "&add=" + encodeURIComponent(invite)).relay === null, "not a relay");
ok(addFromHash("#relay=" + encodeURIComponent(relay) + "&secret=nothex&add=" + encodeURIComponent(invite)).secret === null, "not a secret");

// the link on
const link = addLink("https://account.syb.lol/", { invite, relay, secret });
ok(link === "https://account.syb.lol/#relay=" + encodeURIComponent(relay) + "&secret=" + secret + "&add=" + encodeURIComponent(invite), "the login page with the same three, add= last");
const back = addFromHash(new URL(link).hash);
ok(back.invite.text === invite && back.relay === relay && back.secret === secret, "reads back the same");
ok(addLink("https://account.syb.lol", { invite, relay: null, secret: null }) === "https://account.syb.lol/#add=" + encodeURIComponent(invite), "no relay: the invite alone");

console.log(`add-device: ${n} ok`);
