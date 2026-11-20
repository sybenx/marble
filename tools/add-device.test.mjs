// Adding Marble on a Mac (Keykin SPEC 11.6, `add=`): reading what the Mac's Sign in sends, and the link on to the
// login page.   node tools/add-device.test.mjs
// The whole way through (the Mac, this page in a browser, the login page, a phone) runs in marble-desktop's test/e2e.ts.
import { readInvite, addFromHash, addLink, returnOk } from "../site/js/add-device.js";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const key = "ab".repeat(32);
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
const challenge = "a".repeat(43), ret = "http://127.0.0.1:53111/done", state = "st4te-1234";
const four = `challenge=${challenge}&return=${encodeURIComponent(ret)}&state=${state}&add=`;
let r = addFromHash("#" + four + encodeURIComponent(invite));
ok(r.invite?.text === invite && r.app?.challenge === challenge && r.app.ret === ret && r.app.state === state && !r.bad && r.rest === "", "challenge, return, state and the invite, add= last");
r = addFromHash("#x=1&add=" + invite);
ok(r.invite?.text === invite && r.app === null && !r.bad && r.rest === "x=1", "raw, after another parameter, which stays; no return: the invite alone");
ok(addFromHash("#x=1") === null && addFromHash("") === null, "no add=: nothing");
ok(addFromHash("#add=" + encodeURIComponent(encodeURIComponent(invite))).bad === "invite", "encoded twice: not an invite");
// a return that is not this computer is refused, whatever else is right
for (const bad of ["https://evil.example/done", "http://192.168.1.5:8080/done", "http://127.0.0.1/done", "http://evil.example:80/done", "http://me:pw@127.0.0.1:5000/done", "http://127.0.0.1:5000/done#x", "javascript:alert(1)"]) {
  const x = addFromHash(`#challenge=${challenge}&return=${encodeURIComponent(bad)}&state=${state}&add=` + encodeURIComponent(invite));
  ok(x.bad === "return" && x.app === null, "refused return: " + bad);
}
ok(returnOk("http://localhost:5000/done") === "http://localhost:5000/done", "localhost is this computer too");
ok(addFromHash(`#return=${encodeURIComponent(ret)}&state=${state}&add=` + encodeURIComponent(invite)).bad === "incomplete", "no challenge: left alone");
ok(addFromHash(`#challenge=short&return=${encodeURIComponent(ret)}&state=${state}&add=` + encodeURIComponent(invite)).bad === "incomplete", "a bad challenge: left alone");
ok(addFromHash(`#challenge=${challenge}&return=${encodeURIComponent(ret)}&add=` + encodeURIComponent(invite)).bad === "incomplete", "no state: left alone");

// the link on
const app = { challenge, ret, state };
const link = addLink("https://account.syb.lol/", invite, app);
ok(link === "https://account.syb.lol/#" + four + encodeURIComponent(invite), "the login page with the same four, add= last");
const back = addFromHash(new URL(link).hash);
ok(back.invite.text === invite && back.app.ret === ret && back.app.challenge === challenge && back.app.state === state, "reads back the same");
ok(addLink("https://account.syb.lol", invite, null) === "https://account.syb.lol/#add=" + encodeURIComponent(invite), "no app return: the invite alone");

console.log(`add-device: ${n} ok`);
