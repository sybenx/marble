// Kist (~/kist/kist.md, draft 7.14): the formats. Pure functions over bytes and JSON, no network.
// Section numbers are the spec's. The shared vectors (vectors/vaults.json) run against this file: test/vectors.test.mjs.
import {
  hex, unhex, isHex, cat, u32, utf8, canon, parse, sha256, hkdf, TH, thHex, scalarOK, scalarFrom, pubHex, sign, verify,
  signEvent, eventOK, nip44Conv, nip44Encrypt, nip44Decrypt, randomKey, random, gcmSeal, gcmOpen, unb64,
} from "./crypto.js";

export const KIND = { HEAD: 30810, KEYRING: 30811, CONTRIB: 4813, AUTH: 4810, CERT: 4811, INVITE: 4812 };
const isObj = (o) => !!o && typeof o === "object" && !Array.isArray(o);
const sorted = (a) => [...a].sort();
const isHexList = (a, bytes) => Array.isArray(a) && a.every((x) => isHex(x, bytes));

// ── §3.1 generations, ρ, epochs ──

/** A generation's id: hex(TH("nostr-vault/gen/1", V ‖ P))[0:32], P the parent's 16 raw bytes or empty. */
export const genId = (V, prev) => thHex("nostr-vault/gen/1", 32, V, prev ? unhex(prev) : []);
export const genOK = (g) => isObj(g) && isHex(g.secret, 32) && (g.prev === null || isHex(g.prev, 16)) && isHex(g.id, 16)
  && genId(unhex(g.secret), g.prev) === g.id;
/** A new generation on top of `prev` (null for the first), shutting out `because`. */
export function newGeneration(prev, because = {}) {
  const V = random(32);
  return { id: genId(V, prev), secret: hex(V), prev, because: cleanBecause(because) };
}
export function cleanBecause(b = {}) {
  const out = {};
  for (const k of ["roots", "members", "grants"]) { const l = [...new Set(b[k] || [])].sort(); if (l.length) out[k] = l; }
  if (b.fresh === true) out.fresh = true;
  return out;
}
/** σ for a generation: the one-element array of its id (§2). */
export const sigmaOf = (g) => [g.id];

/** ρ = SHA-256(canonical JSON of {readers, ended}) (§3.1); readers and ended as lowercase hex. */
export const rhoOf = (readers, ended) => sha256(canon({ readers: sorted(readers), ended: sorted(new Set(ended)) }));

/** An epoch's keys from its K (what readers hold) and the vault id. */
export function epochFromK(K, vid) {
  return {
    K: hex(K), kid: thHex("nostr-vault/kid/1", 32, K),
    opsKey: hkdf(K, "nostr-vault/ops/1", vid),
    locator: hex(hkdf(K, "nostr-vault/loc/1", vid)).slice(0, 32),
  };
}
/** An epoch, as an owner device derives it: K(σ, ρ) from the generation's secret V, plus its inbox (§3.1). */
export function epoch(V, vid, rho) {
  const e = epochFromK(hkdf(V, "nostr-vault/epoch/1", vid, rho), vid);
  let c = 0, inbox;
  for (;; c++) { inbox = hkdf(V, "nostr-vault/inbox/1", vid, rho, u32(c)); if (scalarOK(inbox)) break; }
  return { ...e, rho: hex(rho), inboxC: c, inboxSecret: inbox, inbox: pubHex(inbox) };
}
export const authKey = (K0, vid) => hkdf(K0, "nostr-vault/auth/1", vid);
export const dedupKey = (K0, vid) => hkdf(K0, "nostr-vault/ph/1", vid);

// ── §3.3 device keys ──
export const dvkFor = (rootSec, genesisId, n) => scalarFrom(rootSec, "nostr-vault/dvk/1", unhex(genesisId), u32(n));
export const storageFor = (dvkSec) => scalarFrom(dvkSec, "nostr-vault/blossom/1");

// ── §3.4 certificates ──
/** A device cert's template, for the npub to sign. `keykin`: the label (§3.4), on a Keykin v2 account. */
export const deviceCertTemplate = (root, keykin, created_at) =>
  ({ kind: KIND.CERT, tags: [], content: canon({ type: "device", root, ...(keykin ? { keykin } : {}) }), created_at });
/** A sub-cert, signed here by the root. */
export function subCert(rootSec, genesisId, n, dvkSec, name, created_at, aux) {
  const dvk = pubHex(dvkSec), storage = storageFor(dvkSec);
  return signEvent(rootSec, { kind: KIND.CERT, tags: [], created_at, content: canon({
    type: "sub", genesis: genesisId, n, dvk, name, storage: pubHex(storage),
    storage_sig: sign(storage, TH("nostr-vault/storage/1", unhex(dvk)), aux),
  }) }, aux);
}
/** A CERT's content, checked: {type: "device"|"sub"|"revoke", ...}, or null. Signatures are checked here too. */
export function readCert(e) {
  if (!eventOK(e) || e.kind !== KIND.CERT) return null;
  let c; try { c = parse(e.content); } catch { return null; }
  if (!isObj(c)) return null;
  if (c.type === "device") return isHex(c.root, 32) && (c.keykin === undefined || typeof c.keykin === "string") ? { ...c, by: e.pubkey } : null;
  if (c.type === "sub") {
    const ok = isHex(c.genesis, 32) && Number.isSafeInteger(c.n) && c.n >= 0 && isHex(c.dvk, 32) && typeof (c.name ?? "") === "string"
      && isHex(c.storage, 32) && verify(c.storage, TH("nostr-vault/storage/1", unhex(c.dvk)), c.storage_sig);
    return ok ? { ...c, root: e.pubkey } : null;
  }
  if (c.type === "revoke") return isHex(c.root, 32) && (c.keep === undefined || isHexList(c.keep, 32)) ? { ...c, keep: c.keep || [], by: e.pubkey } : null;
  return null;
}

// ── §3.5 wraps, §3.6 the keyring's self seal ──
/** A wrap to `to` (a reader's npub, or your own for the keyring seal): local, with a fresh ephemeral key. */
export function wrapTo(to, text, eph = randomKey(), nonce) {
  return { to, eph: pubHex(eph), ct: nip44Encrypt(nip44Conv(eph, to), text, nonce) };
}
/** An epoch-key wrap for a reader (§3.5): keys and inboxes by kid. */
export const keyWrap = (to, vid, epochs) => wrapTo(to, canon({
  vault: vid, keys: Object.fromEntries(epochs.map((e) => [e.kid, e.K])), inboxes: Object.fromEntries(epochs.map((e) => [e.kid, e.inbox])),
}));
/** A wrap opened by the reader (decrypt(eph, ct) through any signer): {keys, inboxes}, every kid checked, or null. */
export async function openKeyWrap(w, vid, decrypt) {
  try {
    const p = parse(await decrypt(w.eph, w.ct));
    if (!isObj(p) || p.vault !== vid || !isObj(p.keys)) return null;
    for (const [kid, K] of Object.entries(p.keys)) if (!isHex(K, 32) || thHex("nostr-vault/kid/1", 32, unhex(K)) !== kid) return null;
    return { keys: p.keys, inboxes: isObj(p.inboxes) ? p.inboxes : {} };
  } catch { return null; }
}
/** The keyring event's content for a payload: sealed `self` to the owner's npub with a fresh ephemeral key (§3.6). */
export function sealKeyring(payload, owner, eph, nonce) {
  const w = wrapTo(owner, canon(payload), eph, nonce);
  return canon({ self: { eph: w.eph, ct: w.ct } });
}
export const keyringTemplate = (payload, owner, created_at) =>
  ({ kind: KIND.KEYRING, tags: [["d", payload.d]], content: sealKeyring(payload, owner), created_at });
/**
 * A keyring entry opened: decrypt(eph, ct) is the signer's nip44_decrypt as the npub (NIP-07, NIP-46, a local nsec).
 * Returns the payload, checked (§3.6: its d is the event's d; generations whose id fails are dropped), or null.
 */
export async function openKeyring(e, owner, decrypt) {
  if (!eventOK(e) || e.kind !== KIND.KEYRING || e.pubkey !== owner) return null;
  const d = e.tags.find((t) => t[0] === "d")?.[1];
  let p;
  try {
    const { self } = parse(e.content);
    if (!isHex(self?.eph, 32) || typeof self.ct !== "string") return null;
    p = parse(await decrypt(self.eph, self.ct));
  } catch { return null; }
  if (!isObj(p) || p.d !== d || !isHex(p.vault, 32) || !isHex(p.genesis, 32) || p.owner !== owner) return null;
  p.gens = (Array.isArray(p.gens) ? p.gens : []).filter(genOK).map((g) => ({ ...g, because: isObj(g.because) ? g.because : {} }));
  return p;
}
/** Copies of the keyring entry merged (§3.1): generations by id, `because` lists by union. */
export function mergeGens(...lists) {
  const by = new Map();
  for (const g of lists.flat()) {
    const o = by.get(g.id);
    if (!o) { by.set(g.id, { ...g, because: cleanBecause(g.because) }); continue; }
    const b = {};
    for (const k of ["roots", "members", "grants"]) b[k] = [...(o.because[k] || []), ...(g.because[k] || [])];
    b.fresh = o.because.fresh || g.because.fresh;
    o.because = cleanBecause(b);
  }
  return [...by.values()];
}
/** Generation chains: each one's depth from the first (anchored at `firstId`), or absent if it doesn't reach it. */
export function chainDepths(gens, firstId) {
  const by = new Map(gens.map((g) => [g.id, g])), depth = new Map();
  const walk = (g, seen = new Set()) => {
    if (depth.has(g.id)) return depth.get(g.id);
    if (seen.has(g.id)) return -1;
    seen.add(g.id);
    let d = -1;
    if (g.id === firstId && g.prev === null) d = 0;
    else if (g.prev && by.has(g.prev)) { const p = walk(by.get(g.prev), seen); d = p < 0 ? -1 : p + 1; }
    depth.set(g.id, d);
    return d;
  };
  for (const g of gens) walk(g);
  return new Map([...depth].filter(([, d]) => d >= 0));
}
/** The current generation among `vetted` ids: the longest chain back to the first, then the lower id (§3.1). */
export function currentGen(gens, depths, vetted) {
  return gens.filter((g) => depths.has(g.id) && vetted.has(g.id))
    .sort((a, b) => depths.get(b.id) - depths.get(a.id) || (a.id < b.id ? -1 : 1))[0] || null;
}

// ── §4.3 authority events ──
export function readAuth(e, aKey, vid) {
  if (!eventOK(e) || e.kind !== KIND.AUTH) return null;
  let p; try { p = parse(nip44Decrypt(aKey, e.content)); } catch { return null; }
  if (!isObj(p) || p.vault !== vid || !["genesis", "change", "leave"].includes(p.type)) return null;
  const add = p.add ?? [], remove = p.remove ?? [];
  if (!Array.isArray(add) || !Array.isArray(remove)) return null;
  if (!add.every((a) => isObj(a) && isHex(a.reader, 32) && (a.after ?? []).every((x) => x === "self" || isHex(x, 32)))) return null;
  if (!remove.every((r) => isObj(r) && isHex(r.reader, 32) && isHexList(r.prev ?? [], 32))) return null;
  return { id: e.id, by: e.pubkey, created_at: e.created_at, ...p, add, remove, config: isObj(p.config) ? p.config : null, rekey: p.rekey === true };
}
export const authTemplate = (aKey, body, created_at) => ({ kind: KIND.AUTH, tags: [], content: nip44Encrypt(aKey, canon(body)), created_at });

/**
 * Readers and ρ from the authority events held (§3.1, §4.3), for an owner `owner`: {readers, ended, rho}. Events not
 * signed by the owner (or, for a leave, by the reader leaving) count for nothing; a remove naming the owner is invalid.
 */
export function readerSet(auths, owner) {
  const evs = auths.filter((a) => (a.type === "leave" ? a.remove.length === 1 && a.remove[0].reader === a.by && a.by !== owner && !a.add.length : a.by === owner));
  const adds = [], removes = [];
  for (const a of evs) {
    for (const x of a.add) if (x.reader !== owner) adds.push({ reader: x.reader, after: x.after ?? [], auth: a.id });
    for (const x of a.remove) if (x.reader !== owner) removes.push({ reader: x.reader, prev: x.prev ?? [], auth: a.id });
  }
  const removeById = new Map(); // AUTH id → removes in it
  for (const r of removes) removeById.set(r.auth, [...(removeById.get(r.auth) || []), r]);
  // R covers add A of M unless A's after names R (or "self" in R's own event), or names a remove whose prev chain reaches R
  const reaches = (authId, target, reader, seen = new Set()) => {
    if (authId === target) return true;
    if (seen.has(authId)) return false;
    seen.add(authId);
    return (removeById.get(authId) || []).filter((r) => r.reader === reader).some((r) => r.prev.some((p) => reaches(p, target, reader, seen)));
  };
  const covers = (r, a) => r.reader === a.reader && !a.after.some((x) => (x === "self" ? a.auth === r.auth : reaches(x, r.auth, a.reader)));
  const readers = [...new Set(adds.filter((a) => !removes.some((r) => covers(r, a))).map((a) => a.reader))];
  // a remove or leave has effect when it covers some add that no remove or leave coming before it covers; O comes
  // before R when R's prev chain reaches O, else (neither reaches the other) when O has the lower AUTH id (§3.1, as fixed in 7.14 for implementers)
  const before = (o, r) => o.auth !== r.auth && (r.prev.some((p) => reaches(p, o.auth, r.reader))
    || (!o.prev.some((p) => reaches(p, r.auth, o.reader)) && o.auth < r.auth));
  const effective = new Set();
  for (const r of removes) if (adds.some((a) => covers(r, a) && !removes.some((o) => before(o, r) && covers(o, a)))) effective.add(r.auth);
  for (const a of evs) if (a.rekey && a.by === owner && a.type === "change") effective.add(a.id);
  const ended = sorted(effective);
  return { readers: sorted(readers), ended, rho: hex(rhoOf(readers, ended)) };
}
/** The current config (§4.3): the one no other names in `prev`, lowest AUTH id among several; with defaults. */
export function currentConfig(auths, owner) {
  const cs = auths.filter((a) => a.by === owner && a.config && configOK(a.config));
  const named = new Set(cs.flatMap((a) => a.config.prev || []));
  const tops = cs.filter((a) => !named.has(a.id)).sort((a, b) => (a.id < b.id ? -1 : 1));
  if (!tops.length) return null;
  const c = tops[0].config;
  const contrib = { mode: "auto", per_day: 50, max_bytes: 52428800, blocked: [], ...(isObj(c.contrib) ? c.contrib : {}) };
  contrib.blocked = sorted(new Set(tops.flatMap((a) => a.config.contrib?.blocked || [])));
  return { ...c, contrib, history_days: c.history_days ?? (c.profile === "notes" ? 365 : 30), _id: tops[0].id };
}
const configOK = (c) => ["name", "profile"].every((k) => typeof c[k] === "string" && c[k])
  && ["relays", "servers"].every((k) => Array.isArray(c[k]) && c[k].length && c[k].every((x) => typeof x === "string"))
  && (!["photos", "drive"].includes(c.profile) || isHex(c.pinner, 32));

// ── §5.3 versions ──
/** A put with its ver. Every field present; supersedes sorted. */
export function makePut(entry, supersedes, meta, data, deleted = false) {
  const body = { entry, supersedes: sorted(supersedes), meta, data, deleted };
  return { op: "put", ver: verOf(body), ...body };
}
const verOf = (body) => thHex("nostr-vault/ver/1", 32, utf8(canon(body)));
/** A put checked: every member present, well formed, and its ver hashes right (§5.3). */
export function putOK(op) {
  if (!isObj(op) || op.op !== "put" || !isHex(op.entry, 16) || typeof op.ver !== "string") return false;
  if (!Array.isArray(op.supersedes) || !op.supersedes.every((v) => typeof v === "string") || !isObj(op.meta)) return false;
  if (!("data" in op) || typeof op.deleted !== "boolean") return false;
  if (canon(op.supersedes) !== canon(sorted(op.supersedes))) return false;
  const { op: _, ver, ...rest } = op;
  try { return verOf(rest) === ver; } catch { return false; }
}

// ── §5.1 commits ──
export function makeCommit(fields, dvkSec, aux) {
  const c = canon(fields), id = hex(sha256(c));
  return { c, id, sig: sign(dvkSec, TH("nostr-vault/commit/1", unhex(id)), aux) };
}
/** A carried commit {c, sig} checked on its own (§5.1, §10.2): {id, ...fields}, or null. */
export function readCommit(x, genesis) {
  if (!isObj(x) || typeof x.c !== "string" || x.c.length > 12 * 1024 * 4) return null;
  let f; try { f = parse(x.c); } catch { return null; }
  if (!isObj(f) || f.genesis !== genesis || !isHex(f.dvk, 32) || !Number.isSafeInteger(f.seq) || f.seq < 0) return null;
  if (!(f.prev === null || isHex(f.prev, 32)) || (f.seq === 0) !== (f.prev === null) || !isHex(f.kid, 16) || !Array.isArray(f.ops)) return null;
  const id = hex(sha256(x.c));
  if (!verify(f.dvk, TH("nostr-vault/commit/1", unhex(id)), x.sig)) return null;
  return { id, sig: x.sig, c: x.c, ...f };
}

// ── §4.1 heads ──
export function headTemplate(e, body, created_at) {
  return { kind: KIND.HEAD, tags: [["d", e.kid], ["v", e.locator]], content: nip44Encrypt(e.opsKey, canon(body)), created_at };
}
/** A head opened under its epoch (§10.1): the body, or null. epochsByKid: kid → epoch. */
export function openHead(ev, epochsByKid, vid, genesis) {
  if (!eventOK(ev) || ev.kind !== KIND.HEAD) return null;
  const d = ev.tags.find((t) => t[0] === "d")?.[1], v = ev.tags.find((t) => t[0] === "v")?.[1], e = epochsByKid.get(d);
  if (!e || v !== e.locator) return null;
  let b; try { b = parse(nip44Decrypt(e.opsKey, ev.content)); } catch { return null; }
  if (!isObj(b) || b.vault !== vid || b.genesis !== genesis || b.kid !== e.kid || !Array.isArray(b.commits)) return null;
  return { ...b, dvk: ev.pubkey, created_at: ev.created_at, event: ev };
}

// ── §6.1 nv1 blobs ──
export function padme(len) {
  if (len <= 4096) return 4096;
  const e = Math.floor(Math.log2(len)), s = Math.floor(Math.log2(e)) + 1, m = 2 ** (e - s) - 1;
  return Math.floor((len + m) / (m + 1)) * (m + 1);
}
const CHUNK = 65536;
const nonceOf = (i, last) => { const n = new Uint8Array(12); let x = i; for (let j = 10; j >= 0; j--) { n[j] = x % 256; x = Math.floor(x / 256); } n[11] = last ? 1 : 0; return n; };
/** Encrypts a blob: {ref, parts: [Uint8Array]}. key: 32 bytes (random unless given, e.g. a shard's). */
export async function nv1Seal(plain, key = random(32), chunks = 127) {
  const L = padme(plain.length), padded = new Uint8Array(L);
  padded.set(plain);
  const count = Math.ceil(L / CHUNK), out = [];
  for (let i = 0; i < count; i++) out.push(await gcmSeal(key, nonceOf(i, i === count - 1), padded.subarray(i * CHUNK, (i + 1) * CHUNK)));
  const parts = [];
  for (let i = 0; i < count; i += chunks) parts.push(cat(...out.slice(i, i + chunks)));
  return { parts, ref: { alg: "nv1", key: hex(key), size: plain.length, chunks, parts: parts.map((p) => hex(sha256(p))) } };
}
export const refOK = (r) => isObj(r) && r.alg === "nv1" && isHex(r.key, 32) && Number.isSafeInteger(r.size) && r.size >= 0
  && Number.isSafeInteger(r.chunks) && r.chunks >= 1 && r.chunks <= 127 && isHexList(r.parts, 32) && r.parts.length >= 1
  && r.parts.length === Math.ceil(Math.ceil(padme(r.size) / CHUNK) / r.chunks);
/** Opens a blob from its parts (each checked against its SHA-256, every GCM tag and the last-chunk flag). Throws on any failure. */
export async function nv1Open(ref, parts) {
  if (!refOK(ref) || parts.length !== ref.parts.length) throw new Error("bad ref");
  parts.forEach((p, i) => { if (hex(sha256(p)) !== ref.parts[i]) throw new Error("sha256"); });
  const all = cat(...parts), L = padme(ref.size), count = Math.ceil(L / CHUNK);
  if (all.length !== L + 16 * count) throw new Error("size");
  const out = new Uint8Array(L);
  for (let i = 0; i < count; i++) {
    const c = all.subarray(i * (CHUNK + 16), Math.min((i + 1) * (CHUNK + 16), all.length));
    out.set(await gcmOpen(unhex(ref.key), nonceOf(i, i === count - 1), c), i * CHUNK);
  }
  return out.subarray(0, ref.size);
}
/** A checkpoint shard's key: HKDF(dedup key, "nostr-vault/shard/1" ‖ SHA-256(plaintext)) (§9.1). */
export const shardKey = (dKey, plain) => hkdf(dKey, "nostr-vault/shard/1", sha256(plain));

// ── §4.6, §8.7 contributions ──
export const contribEntry = (eventId) => thHex("nostr-vault/contrib/1", 32, unhex(eventId));
/** The put(s) an accepting owner device commits for an item (§8.7), member by member. */
export function acceptedPuts(profile, eventId, plain) {
  const it = plain.item, entry = contribEntry(eventId);
  const meta = { ...it.meta }; delete meta.ph;
  meta.from = plain.from;
  if (profile === "drive") { meta.dev = parse(plain.cert.sub.content).name; meta.parent = it.into ?? "root"; }
  if (profile === "photos") meta.trashed = false;
  const puts = [makePut(entry, [], meta, it.data ?? null, false)];
  if (profile === "photos" && it.into) {
    puts.push(makePut(thHex("nostr-vault/link/1", 32, unhex(it.into), unhex(entry)), [], { kind: "link", album: it.into, photo: entry }, null, false));
  }
  return puts;
}
/** A CONTRIB opened with the inbox secret: its plaintext object, or null. */
export function openContrib(ev, inboxSecret) {
  if (!eventOK(ev) || ev.kind !== KIND.CONTRIB) return null;
  try { const { eph, ct } = parse(ev.content); return parse(nip44Decrypt(nip44Conv(inboxSecret, eph), ct)); } catch { return null; }
}

export { unb64 };
