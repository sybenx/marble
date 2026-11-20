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
 * the code's secret: a code shown on a shared screen can be scanned by more than one phone, and whoever answers first
 * is signed in, so if another answers it as well we can't tell which was yours. onSecond(signer) is then told (me.js
 * asks you which account is yours). others: those not yet decided on; ignored: those decided against.
 */
export class Remote {
  constructor({ sk, signer, relays, secret = null, made = 0, others = [], ignored = [] }) {
    this.sk = typeof sk === "string" ? fromHex(sk) : sk;
    this.signer = signer;
    this.relays = relays;
    this.secret = secret;
    this.made = made;
    this.others = others;
    this.ignored = new Set(ignored);
    this.onSecond = null;
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
    const known = () => this.ignored.has(e.pubkey) || this.others.includes(e.pubkey);
    if (!this.secret || !isHex64(e.pubkey) || known()) return;
    let msg;
    try { msg = JSON.parse(await decrypt(e.content, await conversationKey(this.sk, e.pubkey))); } catch { return; }
    if (known() || !(msg?.result === this.secret || (msg?.result === "ack" && now() - this.made < ACK_WINDOW))) return;
    this.others.push(e.pubkey);
    this.onSecond?.(e.pubkey);
  }
  /** What a saved session holds. */
  saved() {
    return { sk: toHex(this.sk), signer: this.signer, relays: this.relays, secret: this.secret, made: this.made, others: this.others, ignored: [...this.ignored] };
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

/** A site's own nostrconnect:// code (NIP-46): its key, relays, secret, name, url (its origin) and perms, then extra. */
export function siteCode({ pk, relays, secret, name, url, perms, device = "", extra = "" }) {
  return `nostrconnect://${pk}?${relays.map((r) => "relay=" + encodeURIComponent(r)).join("&")}&secret=${secret}`
    + `&name=${encodeURIComponent(name)}&url=${encodeURIComponent(url)}&perms=${encodeURIComponent(perms)}`
    + (device ? `&device=${encodeURIComponent(device.slice(0, 60))}` : "") + extra;
}

/**
 * This browser as a short label, like "Chrome on Windows" (Keykin SPEC 11.5: `device=` on the code, so the account
 * page can tell this sign-in from another). From navigator.userAgentData when there is one, else the userAgent.
 */
export function deviceLabel(nav = globalThis.navigator) {
  const a = nav?.userAgent || "", d = nav?.userAgentData;
  const brands = (d?.brands || []).map((b) => b.brand);
  const fromBrands = brands.includes("Microsoft Edge") ? "Edge" : brands.includes("Opera") ? "Opera" : brands.includes("Brave") ? "Brave"
    : brands.includes("Google Chrome") ? "Chrome" : brands.includes("Chromium") ? "Chromium" : null;
  const b = fromBrands || (/Edg(e|A|iOS)?\//.test(a) ? "Edge" : /OPR\/|Opera/.test(a) ? "Opera" : /SamsungBrowser/.test(a) ? "Samsung Internet"
    : /Firefox\/|FxiOS/.test(a) ? "Firefox" : /Chrome\/|CriOS|Chromium/.test(a) ? "Chrome" : /Safari\//.test(a) ? "Safari" : null);
  const p = d?.platform || "";
  const os = /Windows/.test(p) ? "Windows" : /Android/.test(p) ? "Android" : /macOS/.test(p) ? "macOS" : /Chrome OS|ChromeOS/.test(p) ? "ChromeOS" : /Linux/.test(p) ? "Linux"
    : /Windows/.test(a) ? "Windows" : /Android/.test(a) ? "Android" : /iPhone/.test(a) ? "iPhone" : /iPad/.test(a) ? "iPad" : /CrOS/.test(a) ? "ChromeOS"
    : /Macintosh|Mac OS X/.test(a) ? "macOS" : /Linux/.test(a) ? "Linux" : null;
  return b && os ? `${b} on ${os}` : b || (os ? `A browser on ${os}` : "");
}

/** The first 4 hex of this browser's own sign-in key (its NIP-46 client key), as the account page shows it, or null. */
export async function shortKey(skHex) {
  if (!/^[0-9a-f]{64}$/i.test(skHex || "")) return null;
  const { schnorr } = await load();
  return toHex(schnorr.getPublicKey(fromHex(skHex))).slice(0, 4);
}

// ── The login page an account names (Keykin SPEC §11.1) ──
// Its template, the old kind as a template, and the link: pure.js, re-exported here as before.
export { loginTemplate, legacyTemplate, loginLink } from "./pure.js";

/**
 * Starts a phone sign-in: onCode(uri, svg) gets the code to show; resolves with {pubkey, remote, saved} once your
 * phone (or the login page your account names, handing the code to a device) has connected and said which account it
 * is. Rejects on signal abort. perms: what the site will ask to sign (NIP-46), shown for what it is; Marble and your
 * servers don't refuse other kinds because of it. name: the site's name the sign-in app shows. The code carries no
 * kk=account (Keykin SPEC §11.6): that mark is only for an account page's own code. Which key a site gets is the
 * person's choice, made on their device or the login page.
 * pending: a code made earlier ({sk, secret, relays, made}, see onPending), listened for again from when it was made,
 * as after coming back from the login page in this tab. onPending(p) gets that, to keep until the sign-in ends.
 * extra: more parameters for the code, as "&k=v" (kk_login=missing: Marble found no login-page pointer, so the phone
 * that answers can publish it again; agreed with Marble Android).
 */
export async function phoneSignIn({ onCode, onPending, pending = null, signal, extra = "", perms = "sign_event:1,sign_event:7,sign_event:4811,sign_event:30811,sign_event:24242,nip44_decrypt,get_public_key", name = "Marble" }) {
  const { schnorr, encodeQR } = await load();
  const sk = pending ? fromHex(pending.sk) : schnorr.utils.randomSecretKey();
  const pk = toHex(schnorr.getPublicKey(sk));
  const secret = pending?.secret || toHex(crypto.getRandomValues(new Uint8Array(8)));
  const relays = pending?.relays || connectRelays();
  const made = pending?.made || now(), asked = made - 30;
  const uri = siteCode({ pk, relays, secret, name, url: location.origin, perms, device: deviceLabel(), extra });
  onPending?.({ sk: toHex(sk), secret, relays, made });
  onCode(uri, qrSvg(encodeQR, uri));
  // Your servers answer the code with its secret, from the signer they'll use for this site.
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
  if (!isHex64(pubkey)) throw new Error("no account");
  return { pubkey: pubkey.toLowerCase(), remote, saved: remote.saved() };
}

/** A bunker:// link (NIP-46, from a signer app) as {signer, relays, secret}, or null. */
export function parseBunker(link) {
  let u;
  try { u = new URL(String(link || "").trim().replace(/^bunker:\/\//i, "http://")); } catch { return null; }
  if (!/^bunker:\/\//i.test(String(link).trim()) || !isHex64(u.hostname)) return null;
  const relays = u.searchParams.getAll("relay").filter((r) => /^wss?:\/\//i.test(r));
  if (!relays.length) return null;
  return { signer: u.hostname.toLowerCase(), relays, secret: u.searchParams.get("secret") || "" };
}

/** Signs in with a bunker link: connects to that signer, then asks which account it is. Resolves as phoneSignIn does. */
export async function bunkerSignIn(link, { perms = "sign_event:1,sign_event:7,sign_event:4811,sign_event:30811,sign_event:24242,nip44_decrypt,get_public_key" } = {}) {
  const b = parseBunker(link);
  if (!b) throw new Error("That isn't a bunker link.");
  const { schnorr } = await load();
  const remote = new Remote({ sk: schnorr.utils.randomSecretKey(), signer: b.signer, relays: b.relays });
  try {
    const ok = await remote.request("connect", [b.signer, b.secret, perms], 90000);
    if (ok !== "ack" && ok !== b.secret) throw new Error("The signer didn't connect.");
    const pubkey = await remote.request("get_public_key", [], 30000);
    if (!isHex64(pubkey)) throw new Error("no account");
    return { pubkey: pubkey.toLowerCase(), remote, saved: remote.saved() };
  } catch (err) { remote.stop(); throw err; }
}

/** Any text as a code to scan (the sign-in's own drawing), for the Mac's invite (add-device.js). */
export async function codeSvg(data) { const { encodeQR } = await load(); return qrSvg(encodeQR, data); }

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
