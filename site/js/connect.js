// Signing in with your phone: this page shows a nostrconnect:// code (NIP-46), Marble on your phone scans it,
// and your servers answer as the signer, over NIP-44 v2. The pieces for it (vendor/sign.js) load only now.
import { subscribe, publish, testRelay } from "./pool.js";
import { toHex, fromHex, isHex64 } from "./ids.js";

let lib = null;
const load = () => (lib ??= import("./vendor/sign.js"));
const utf8 = new TextEncoder(), text = new TextDecoder();
const b64 = (bytes) => btoa(String.fromCharCode(...bytes));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const now = () => Math.floor(Date.now() / 1000);
const ACK_WINDOW = 600; // seconds after a code is made in which a bare "ack" from a second answerer still counts

/** Where the code tells your servers to answer (on localhost, the test server only). */
export function connectRelays() {
  const local = testRelay();
  return local ? [local] : ["wss://syb.lol", "wss://relay.damus.io", "wss://relay.primal.net"];
}

// ── NIP-44 v2 ────────────────────────────────────────────────────────────────────────────────────────

async function conversationKey(sk, pubkey) {
  const { secp256k1, hkdfExtract, sha256 } = await load();
  const shared = secp256k1.getSharedSecret(sk, fromHex("02" + pubkey)).subarray(1, 33);
  return hkdfExtract(sha256, shared, utf8.encode("nip44-v2"));
}
function paddedLength(n) {
  if (n <= 32) return 32;
  const next = 1 << (Math.floor(Math.log2(n - 1)) + 1);
  const chunk = next <= 256 ? 32 : next / 8;
  return chunk * (Math.floor((n - 1) / chunk) + 1);
}
async function messageKeys(key, nonce) {
  const { hkdfExpand, sha256 } = await load();
  const k = hkdfExpand(sha256, key, nonce, 76);
  return { chacha: k.subarray(0, 32), iv: k.subarray(32, 44), mac: k.subarray(44, 76) };
}
export async function encrypt(plaintext, key) {
  const { chacha20, hmac, sha256 } = await load();
  const body = utf8.encode(plaintext);
  if (body.length < 1 || body.length > 65535) throw new Error("bad length");
  const padded = new Uint8Array(2 + paddedLength(body.length));
  padded[0] = body.length >> 8; padded[1] = body.length & 255;
  padded.set(body, 2);
  const nonce = crypto.getRandomValues(new Uint8Array(32));
  const k = await messageKeys(key, nonce);
  const cipher = chacha20(k.chacha, k.iv, padded);
  const mac = hmac(sha256, k.mac, new Uint8Array([...nonce, ...cipher]));
  return b64(new Uint8Array([2, ...nonce, ...cipher, ...mac]));
}
export async function decrypt(payload, key) {
  const { chacha20, hmac, sha256 } = await load();
  const d = unb64(payload);
  if (d[0] !== 2 || d.length < 99) throw new Error("not nip44 v2");
  const nonce = d.subarray(1, 33), cipher = d.subarray(33, d.length - 32), mac = d.subarray(d.length - 32);
  const k = await messageKeys(key, nonce);
  const want = hmac(sha256, k.mac, new Uint8Array([...nonce, ...cipher]));
  if (toHex(want) !== toHex(mac)) throw new Error("bad mac");
  const padded = chacha20(k.chacha, k.iv, cipher);
  const len = (padded[0] << 8) | padded[1];
  return text.decode(padded.subarray(2, 2 + len));
}
export { conversationKey };

// ── NIP-46 ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * Waits for answers (kind 24133) to pk, from `since()`. A phone drops a page's connections while it's in the
 * background (you went to Marble to approve), so when the page is back, it asks again from the same time.
 */
function listen(relays, pk, since, onEvent) {
  let close = subscribe(relays, { kinds: [24133], "#p": [pk], since: since() }, onEvent);
  const again = () => {
    if (document.visibilityState !== "visible") return;
    close();
    close = subscribe(relays, { kinds: [24133], "#p": [pk], since: since() }, onEvent);
  };
  document.addEventListener("visibilitychange", again);
  addEventListener("pageshow", again);
  addEventListener("online", again);
  return () => {
    close();
    document.removeEventListener("visibilitychange", again);
    removeEventListener("pageshow", again);
    removeEventListener("online", again);
  };
}

/**
 * A connection to the signer your phone set up: asks it things and waits for the answers. One made from a code keeps
 * the code's secret: a code shown on a shared screen can be scanned by two phones, and whoever answers first is
 * signed in, so if a second answers it as well we can't tell which was yours. onIntruder() is then told, and the
 * session should be given up.
 */
export class Remote {
  constructor({ sk, signer, relays, secret = null, made = 0 }) {
    this.sk = typeof sk === "string" ? fromHex(sk) : sk;
    this.signer = signer;
    this.relays = relays;
    this.secret = secret;
    this.made = made;
    this.intruded = false;
    this.onIntruder = null;
    this.waiting = new Map(); // request id → {resolve, reject}
    this.close = null;
  }
  async start() {
    if (this.close) return;
    const { schnorr } = await load();
    this.pk = toHex(schnorr.getPublicKey(this.sk));
    this.key = await conversationKey(this.sk, this.signer);
    // From the oldest question still waiting (asked again when the page comes back), else from now.
    const since = () => Math.min(now(), ...[...this.waiting.values()].map((w) => w.at)) - 30;
    this.close = listen(this.relays, this.pk, since, async (e) => {
      if (e.pubkey !== this.signer) return this.second(e);
      let msg;
      try { msg = JSON.parse(await decrypt(e.content, this.key)); } catch { return; }
      const w = this.waiting.get(msg.id);
      if (!w) return;
      this.waiting.delete(msg.id);
      if (msg.error) w.reject(new Error(msg.error)); else w.resolve(msg.result);
    });
  }
  async second(e) {
    if (!this.secret || this.intruded || !isHex64(e.pubkey)) return;
    let msg;
    try { msg = JSON.parse(await decrypt(e.content, await conversationKey(this.sk, e.pubkey))); } catch { return; }
    if (this.intruded || !(msg?.result === this.secret || (msg?.result === "ack" && now() - this.made < ACK_WINDOW))) return;
    this.intruded = true;
    this.onIntruder?.();
  }
  async request(method, params = [], timeout = 90000) {
    await this.start();
    const { schnorr, sha256 } = await load();
    const id = toHex(crypto.getRandomValues(new Uint8Array(8)));
    const content = await encrypt(JSON.stringify({ id, method, params }), this.key);
    const ev = signWith(this.sk, { kind: 24133, created_at: now(), tags: [["p", this.signer]], content }, { schnorr, sha256 });
    const answer = new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject, at: now() });
      setTimeout(() => { if (this.waiting.delete(id)) reject(new Error("timeout")); }, timeout);
    });
    publish(this.relays, ev);
    return answer;
  }
  async signEvent(template) {
    return JSON.parse(await this.request("sign_event", [JSON.stringify(template)]));
  }
  stop() { this.close?.(); this.close = null; for (const w of this.waiting.values()) w.reject(new Error("closed")); this.waiting.clear(); }
}

function signWith(sk, t, { schnorr, sha256 }) {
  const pubkey = toHex(schnorr.getPublicKey(sk));
  const id = toHex(sha256(utf8.encode(JSON.stringify([0, pubkey, t.created_at, t.kind, t.tags, t.content]))));
  return { ...t, pubkey, id, sig: toHex(schnorr.sign(fromHex(id), sk)) };
}

/**
 * Starts a phone sign-in: onCode(uri) gets the code to show; resolves with {pubkey, remote, saved} once your
 * phone has connected and said which account it is. Rejects on signal abort. perms: what the site will ask
 * to sign (NIP-46), shown for what it is; Marble and your servers don't refuse other kinds because of it. name:
 * the site's name the sign-in app shows.
 */
export async function phoneSignIn({ onCode, signal, perms = "sign_event:1,sign_event:7,get_public_key", name = "Marble" }) {
  const { schnorr, encodeQR } = await load();
  const sk = schnorr.utils.randomSecretKey();
  const pk = toHex(schnorr.getPublicKey(sk));
  const secret = toHex(crypto.getRandomValues(new Uint8Array(8)));
  const relays = connectRelays();
  const uri = `nostrconnect://${pk}?${relays.map((r) => "relay=" + encodeURIComponent(r)).join("&")}&secret=${secret}`
    + `&name=${encodeURIComponent(name)}&url=${encodeURIComponent(location.origin)}&perms=${encodeURIComponent(perms)}`;
  onCode(uri, qrSvg(encodeQR, uri));
  // Your servers answer the code with its secret, from the signer they'll use for this site.
  const made = now(), asked = made - 30;
  let close;
  const signer = await new Promise((resolve, reject) => {
    close = listen(relays, pk, () => asked, async (e) => {
      try {
        const msg = JSON.parse(await decrypt(e.content, await conversationKey(sk, e.pubkey)));
        if (msg.result === secret || msg.result === "ack") resolve(e.pubkey);
      } catch {}
    });
    signal?.addEventListener("abort", () => { close(); reject(new Error("cancelled")); });
  });
  // The session listens from here on (for a second answer too) before the code's own listening stops, so none is missed.
  const remote = new Remote({ sk, signer, relays, secret, made });
  await remote.start();
  close();
  const pubkey = await remote.request("get_public_key", [], 30000);
  if (remote.intruded) { remote.stop(); throw new Error("intruder"); }
  if (!isHex64(pubkey)) throw new Error("no account");
  return { pubkey: pubkey.toLowerCase(), remote, saved: { sk: toHex(sk), signer, relays, secret, made } };
}

function qrSvg(encodeQR, data) {
  const m = encodeQR(data, "raw", { ecc: "medium", border: 3 });
  let d = "";
  for (let y = 0; y < m.length; y++) for (let x = 0; x < m.length; x++) {
    if (!m[y][x]) continue;
    let run = 1;
    while (x + run < m.length && m[y][x + run]) run++;
    d += `M${x} ${y}h${run}v1h-${run}z`;
    x += run - 1;
  }
  return `<svg viewBox="0 0 ${m.length} ${m.length}" shape-rendering="crispEdges" role="img" aria-label="Sign-in code"><rect width="${m.length}" height="${m.length}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
