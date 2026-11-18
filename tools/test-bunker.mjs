// A NIP-46 signer for local tests: answers a page's nostrconnect:// code as a phone would (connect with the secret,
// then get_public_key, sign_event, nip44_encrypt, nip44_decrypt), with a TEST key, on a local relay only.
//   node tools/test-bunker.mjs <nsec hex> '<nostrconnect://… uri>'
import * as C from "../site/js/vault/crypto.js";
const [secH, uri] = process.argv.slice(2);
const sec = C.unhex(secH), me = C.pubHex(sec);
const u = new URL(uri.replace("nostrconnect://", "http://"));
const client = u.hostname, relay = u.searchParams.get("relay"), secret = u.searchParams.get("secret");
if (!/^ws:\/\/(127\.0\.0\.1|localhost)/.test(relay)) throw new Error("local relays only");
const conv = C.nip44Conv(sec, client);
const ws = new WebSocket(relay);
const send = (obj) => ws.send(JSON.stringify(["EVENT", C.signEvent(sec, { kind: 24133, created_at: Math.floor(Date.now() / 1000), tags: [["p", client]], content: C.nip44Encrypt(conv, JSON.stringify(obj)) })]));
ws.onopen = () => {
  ws.send(JSON.stringify(["REQ", "b", { kinds: [24133], "#p": [me], since: Math.floor(Date.now() / 1000) - 10 }]));
  send({ id: C.hex(C.random(8)), result: secret });
  console.log("answered the code as", me);
};
ws.onmessage = (m) => {
  const x = JSON.parse(m.data);
  if (x[0] !== "EVENT" || x[2].pubkey !== client) return;
  let req; try { req = JSON.parse(C.nip44Decrypt(conv, x[2].content)); } catch { return; }
  let result, error;
  try {
    if (req.method === "get_public_key") result = me;
    else if (req.method === "sign_event") { const t = JSON.parse(req.params[0]); result = JSON.stringify(C.signEvent(sec, t)); }
    else if (req.method === "nip44_decrypt") result = C.nip44Decrypt(C.nip44Conv(sec, req.params[0]), req.params[1]);
    else if (req.method === "nip44_encrypt") result = C.nip44Encrypt(C.nip44Conv(sec, req.params[0]), req.params[1]);
    else if (req.method === "logout") { result = "ok"; }
    else error = "unsupported";
  } catch (e) { error = String(e.message); }
  console.log(req.method, error || "ok");
  send({ id: req.id, ...(error ? { error } : { result }) });
};
