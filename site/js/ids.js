// The addresses people paste: note1, nevent1, npub1, nprofile1, naddr1 (NIP-19), with or without
// "nostr:", alone or inside a link from another app; a name@domain; or a bare 64-character hex id.

const CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
const GEN = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

function polymod(values) {
  let chk = 1;
  for (const v of values) {
    const top = chk >> 25;
    chk = ((chk & 0x1ffffff) << 5) ^ v;
    for (let i = 0; i < 5; i++) if ((top >> i) & 1) chk ^= GEN[i];
  }
  return chk;
}
const expand = (hrp) => [...hrp].map((c) => c.charCodeAt(0) >> 5).concat(0, [...hrp].map((c) => c.charCodeAt(0) & 31));

function convert(data, from, to, pad) {
  let acc = 0, bits = 0;
  const out = [], max = (1 << to) - 1;
  for (const v of data) {
    acc = (acc << from) | v;
    bits += from;
    while (bits >= to) { bits -= to; out.push((acc >> bits) & max); }
  }
  if (pad && bits > 0) out.push((acc << (to - bits)) & max);
  else if (!pad && (bits >= from || ((acc << (to - bits)) & max))) throw new Error("bad padding");
  return out;
}

function bech32Decode(s) {
  s = s.toLowerCase();
  const sep = s.lastIndexOf("1");
  if (sep < 1 || s.length - sep < 7) throw new Error("bad bech32");
  const hrp = s.slice(0, sep);
  const words = [...s.slice(sep + 1)].map((c) => {
    const i = CHARSET.indexOf(c);
    if (i < 0) throw new Error("bad character");
    return i;
  });
  if (polymod(expand(hrp).concat(words)) !== 1) throw new Error("bad checksum");
  return { hrp, bytes: Uint8Array.from(convert(words.slice(0, -6), 5, 8, false)) };
}

function bech32Encode(hrp, bytes) {
  const words = convert(bytes, 8, 5, true);
  const mod = polymod(expand(hrp).concat(words, [0, 0, 0, 0, 0, 0])) ^ 1;
  const sum = [0, 1, 2, 3, 4, 5].map((i) => (mod >> (5 * (5 - i))) & 31);
  return hrp + "1" + words.concat(sum).map((w) => CHARSET[w]).join("");
}

export const toHex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
export const fromHex = (h) => Uint8Array.from(h.match(/../g) || [], (x) => parseInt(x, 16));
export const isHex64 = (s) => /^[0-9a-f]{64}$/i.test(s || "");
const ascii = new TextDecoder(), utf8 = new TextEncoder();

/** {type: "event"|"profile"|"address", id?, pubkey?, kind?, identifier?, relays: []}, or null. */
export function decode(entity) {
  let d;
  try { d = bech32Decode(entity); } catch { return null; }
  const { hrp, bytes } = d;
  if (hrp === "note" && bytes.length === 32) return { type: "event", id: toHex(bytes), relays: [] };
  if (hrp === "npub" && bytes.length === 32) return { type: "profile", pubkey: toHex(bytes), relays: [] };
  if (!["nevent", "nprofile", "naddr"].includes(hrp)) return null;
  const out = { relays: [] };
  for (let i = 0; i + 2 <= bytes.length;) {
    const t = bytes[i], l = bytes[i + 1], v = bytes.slice(i + 2, i + 2 + l);
    i += 2 + l;
    if (v.length < l) break;
    if (t === 0) {
      if (hrp === "naddr") out.identifier = ascii.decode(v);
      else if (l === 32) out[hrp === "nevent" ? "id" : "pubkey"] = toHex(v);
    } else if (t === 1) out.relays.push(ascii.decode(v));
    else if (t === 2 && l === 32 && hrp !== "nprofile") out.pubkey = toHex(v);
    else if (t === 3 && l === 4) out.kind = ((v[0] << 24) | (v[1] << 16) | (v[2] << 8) | v[3]) >>> 0;
  }
  if (hrp === "nevent") return out.id ? { type: "event", ...out } : null;
  if (hrp === "nprofile") return out.pubkey ? { type: "profile", ...out } : null;
  return out.pubkey && out.kind !== undefined && out.identifier !== undefined ? { type: "address", ...out } : null;
}

function tlv(entries) {
  const parts = [];
  for (const [t, v] of entries) parts.push(t, v.length, ...v);
  return Uint8Array.from(parts);
}
const relayTlv = (relays = []) => relays.slice(0, 2).map((r) => [1, utf8.encode(r)]);

export const npub = (pk) => bech32Encode("npub", fromHex(pk));
export const note = (id) => bech32Encode("note", fromHex(id));
export const nevent = (id, { relays, author } = {}) =>
  bech32Encode("nevent", tlv([[0, fromHex(id)], ...relayTlv(relays), ...(author ? [[2, fromHex(author)]] : [])]));
export const nprofile = (pk, relays) => bech32Encode("nprofile", tlv([[0, fromHex(pk)], ...relayTlv(relays)]));
export const naddr = ({ pubkey, kind, identifier, relays }) =>
  bech32Encode("naddr", tlv([[0, utf8.encode(identifier)], ...relayTlv(relays), [2, fromHex(pubkey)],
    [3, Uint8Array.of((kind >>> 24) & 255, (kind >> 16) & 255, (kind >> 8) & 255, kind & 255)]]));

const ENTITY = /(?:nostr:)?\b((?:note|nevent|npub|nprofile|naddr)1[02-9ac-hj-np-z]{6,})\b/i;
const ADDRESS = /^@?(?:([\w.+-]+)@)?((?:[\w-]+\.)+[a-z]{2,})$/i;

/**
 * What someone typed or pasted into the search box: {kind: "entity", entity, decoded} for an address
 * that opens something at once, {kind: "hex", hex}, {kind: "nip05", address}, or {kind: "text", q}.
 */
export function parseInput(raw) {
  const s = (raw || "").trim();
  const m = ENTITY.exec(s);
  if (m) {
    const decoded = decode(m[1]);
    if (decoded) return { kind: "entity", entity: m[1].toLowerCase(), decoded };
  }
  if (isHex64(s)) return { kind: "hex", hex: s.toLowerCase() };
  const a = ADDRESS.exec(s);
  if (a && a[1] && !/\s/.test(s)) return { kind: "nip05", address: `${a[1]}@${a[2]}`.toLowerCase() };
  return { kind: "text", q: s };
}

/** The page for an address: /e/…, /p/… or /a/…. */
export function pathFor(entity, decoded = decode(entity)) {
  if (!decoded) return null;
  return `/${entity}`;
}
