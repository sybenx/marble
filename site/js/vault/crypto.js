// Vaults (~/nostr-sync-nip/vaults.md, draft 7.14): the primitives. Bytes are Uint8Array; hex is lowercase.
// secp256k1, BIP-340, SHA-256, HMAC, HKDF and ChaCha20 come from the vendored noble bundle (vendor/sign.js);
// AES-256-GCM from WebCrypto. Everything here is synchronous except AES (nv1, blob.js).
import { secp256k1, schnorr, sha256 as nsha, hmac as nhmac, hkdfExtract, hkdfExpand, chacha20 } from "../vendor/sign.js";

const te = new TextEncoder(), td = new TextDecoder("utf-8", { fatal: true });
export const utf8 = (s) => te.encode(s);
export const unutf8 = (b) => td.decode(b);
const HEXES = Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, "0"));
export const hex = (b) => { let s = ""; for (const x of b) s += HEXES[x]; return s; };
export function unhex(h) {
  if (typeof h !== "string" || h.length % 2 || /[^0-9a-f]/.test(h)) throw new Error("bad hex");
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16);
  return out;
}
export const isHex = (s, bytes) => typeof s === "string" && s.length === bytes * 2 && /^[0-9a-f]+$/.test(s);
export function cat(...parts) {
  const bs = parts.map((p) => (p instanceof Uint8Array ? p : typeof p === "string" ? utf8(p) : Uint8Array.from(p)));
  const out = new Uint8Array(bs.reduce((n, b) => n + b.length, 0));
  let o = 0;
  for (const b of bs) { out.set(b, o); o += b.length; }
  return out;
}
export const u32 = (n) => Uint8Array.of((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
export const b64 = (b) => { let s = ""; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000)); return btoa(s); };
export const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const random = (n) => crypto.getRandomValues(new Uint8Array(n));
export const equal = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

/** RFC 8785 canonical JSON for the I-JSON this spec allows: integers within ±2^53, no floats (§2). Throws otherwise. */
export function canon(v) {
  if (v === null || typeof v === "boolean" || typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number") { if (!Number.isSafeInteger(v)) throw new Error("not an integer"); return String(v); }
  if (Array.isArray(v)) return "[" + v.map(canon).join(",") + "]";
  if (typeof v === "object") return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + canon(v[k])).join(",") + "}";
  throw new Error("not JSON");
}
/** Parses JSON and refuses floats (anything malformed is ignored whole, §2). */
export function parse(text) {
  const v = JSON.parse(text);
  canon(v); // throws on a float or an unsafe integer
  return v;
}

export const sha256 = (...m) => nsha(cat(...m));
export const hmac = (key, ...m) => nhmac(nsha, key, cat(...m));
/** HKDF-SHA256, empty salt, 32 bytes (§2). */
export const hkdf = (ikm, ...info) => hkdfExpand(nsha, hkdfExtract(nsha, ikm, new Uint8Array(32)), cat(...info), 32);
/** Tagged hash TH(tag, m) = SHA-256(utf8(tag) ‖ 0x00 ‖ m) (§2). */
export const TH = (tag, ...m) => nsha(cat(utf8(tag), [0], ...m));
export const thHex = (tag, len, ...m) => hex(TH(tag, ...m)).slice(0, len);

// ── secp256k1 ──
const N = secp256k1.Point.Fn.ORDER;
const big = (b) => BigInt("0x" + (hex(b) || "0"));
export const scalarOK = (b) => b.length === 32 && big(b) > 0n && big(b) < N;
export const xonly = (sec) => schnorr.getPublicKey(sec);
export const pubHex = (sec) => hex(xonly(sec));
/** A key from HKDF output: if zero or not below n, append a counter byte and try again (§3.3). */
export function scalarFrom(ikm, ...info) {
  for (let c = -1; c < 256; c++) {
    const k = hkdf(ikm, ...info, c < 0 ? [] : [c]);
    if (scalarOK(k)) return k;
  }
  throw new Error("no scalar");
}
export const randomKey = () => schnorr.utils.randomSecretKey();
export const sign = (sec, msg, aux = random(32)) => hex(schnorr.sign(msg, sec, aux));
export function verify(pubH, msg, sigH) {
  try { return isHex(pubH, 32) && isHex(sigH, 64) && schnorr.verify(unhex(sigH), msg, unhex(pubH)); } catch { return false; }
}
/** The x coordinate of sec · lift_x(pub). */
export const ecdhX = (sec, pubH) => secp256k1.getSharedSecret(sec, unhex("02" + pubH)).subarray(1, 33);

// ── Nostr events ──
export const eventId = (e) => hex(sha256(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content])));
export function signEvent(sec, { kind, tags = [], content = "", created_at }, aux) {
  const e = { pubkey: pubHex(sec), created_at, kind, tags, content };
  e.id = eventId(e);
  e.sig = sign(sec, unhex(e.id), aux);
  return { id: e.id, pubkey: e.pubkey, created_at, kind, tags, content, sig: e.sig };
}
export function eventOK(e) {
  try {
    return !!e && typeof e === "object" && isHex(e.pubkey, 32) && Number.isSafeInteger(e.created_at) && Number.isSafeInteger(e.kind)
      && Array.isArray(e.tags) && typeof e.content === "string" && eventId(e) === e.id && verify(e.pubkey, unhex(e.id), e.sig);
  } catch { return false; }
}

// ── NIP-44 v2 ──
export const nip44Conv = (sec, pubH) => hkdfExtract(nsha, ecdhX(sec, pubH), utf8("nip44-v2"));
function padLen(l) { if (l <= 32) return 32; const np = 1 << (Math.floor(Math.log2(l - 1)) + 1); const ch = np <= 256 ? 32 : np / 8; return ch * (Math.floor((l - 1) / ch) + 1); }
const keys44 = (conv, nonce) => { const k = hkdfExpand(nsha, conv, nonce, 76); return [k.subarray(0, 32), k.subarray(32, 44), k.subarray(44, 76)]; };
export function nip44Encrypt(conv, text, nonce = random(32)) {
  const pt = utf8(text);
  if (pt.length < 1 || pt.length > 65535) throw new Error("bad length");
  const padded = new Uint8Array(2 + padLen(pt.length));
  padded[0] = pt.length >> 8; padded[1] = pt.length & 255; padded.set(pt, 2);
  const [ck, iv, mk] = keys44(conv, nonce);
  const ct = chacha20(ck, iv, padded);
  return b64(cat([2], nonce, ct, hmac(mk, nonce, ct)));
}
export function nip44Decrypt(conv, payload) {
  const x = unb64(payload);
  if (x[0] !== 2 || x.length < 99) throw new Error("not nip44 v2");
  const nonce = x.subarray(1, 33), ct = x.subarray(33, x.length - 32), mac = x.subarray(x.length - 32);
  const [ck, iv, mk] = keys44(conv, nonce);
  const want = hmac(mk, nonce, ct);
  let d = 0; for (let i = 0; i < 32; i++) d |= want[i] ^ mac[i];
  if (d) throw new Error("bad mac");
  const p = chacha20(ck, iv, ct), len = (p[0] << 8) | p[1];
  if (len < 1 || 2 + len > p.length) throw new Error("bad padding");
  return unutf8(p.subarray(2, 2 + len));
}

// ── AES-256-GCM (WebCrypto) ──
const AAD = utf8("nostr-vault/blob/1");
const aesKey = (key, use) => crypto.subtle.importKey("raw", key, "AES-GCM", false, [use]);
export async function gcmSeal(key, nonce, plain) {
  return new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: AAD }, await aesKey(key, "encrypt"), plain));
}
export async function gcmOpen(key, nonce, sealed) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce, additionalData: AAD }, await aesKey(key, "decrypt"), sealed));
}
