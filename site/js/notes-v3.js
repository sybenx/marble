// Reading Marble Android's notes v3 packs (~/igloo/marble-notes-sync-v3.md, option A; android notes/Packs.kt), read
// only: a stopgap so a heavy user's notes show here, until Marble notes move to vaults (then this file goes, and Web
// reads the vault instead). Its rules, from the sync spec session:
//  - nothing is ever written from here: no packs, keys, checkpoints, base files or markers;
//  - a note keeps its v3 id;
//  - what's decrypted stays in this browser (IndexedDB, per account, cleared on sign-out);
//  - v3 §4 when taking a pack: it decrypts, its prefix names its d, it's under that key, every note in it belongs
//    there; never backwards; absence is never removal; more than 10 notes (or 5%) gone or invalid in one pull: none of
//    it is taken.
// Shared tests: marble-content-fixtures/fixtures/notes-packs (harness/marble-web-notes-packs.mjs).

const enc = new TextEncoder();
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
const unhex = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));
const sha256 = async (bytes) => new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
/** A short hash of a text: what an edit says it started from. */
export const hashOf = async (text) => hex(await sha256(enc.encode(text))).slice(0, 12);

/** Where a key's notes go (v3 §1): slots, pack names, the paging tag. */
export async function layout(kid, k) {
  const key = await crypto.subtle.importKey("raw", unhex(k), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = async (msg) => new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(msg)));
  const bits = (b) => [...b].map((x) => x.toString(2).padStart(8, "0")).join("");
  return {
    kid,
    slot: async (id) => bits(await mac(id)),
    d: async (prefix) => hex(await mac(`pack:${prefix}`)).slice(0, 32),
    w: hex(await mac("notes")).slice(0, 16),
    checkpointD: hex(await mac("checkpoint")).slice(0, 32),
    baseD: hex(await mac("base")).slice(0, 32),
  };
}

const KNOWN = ["id", "text", "created", "updated", "f", "done", "deleted", "url", "quote", "from", "base", "body"];
const FIELDS = ["text", "done", "deleted", "url", "quote"];
const at = (n, field) => n.f?.[field] ?? n.created;
const set = (o, k, v) => { if (v == null) delete o[k]; else o[k] = v; };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** The merge (v3 §5) of two copies of one note: per field the newest wins; two text edits from different starts keep both. */
export async function mergeNotes(mine, theirs, device = "another device") {
  if (same(mine, theirs)) return [mine];
  const newer = theirs.updated > mine.updated ? theirs : mine, older = newer === theirs ? mine : theirs;
  const f = {};
  const pick = (field, a, b) => { const ta = at(mine, field), tb = at(theirs, field); f[field] = Math.max(ta, tb); return tb > ta ? b : a; };
  const conflict = mine.text !== theirs.text && !mine.deleted && !theirs.deleted && mine.base != null && theirs.base != null
    && theirs.base !== await hashOf(mine.text) && mine.base !== await hashOf(theirs.text);
  const text = pick("text", mine.text, theirs.text), done = pick("done", mine.done, theirs.done);
  const removed = [mine.deleted, theirs.deleted].filter(Boolean);
  f.deleted = Math.max(at(mine, "deleted"), at(theirs, "deleted"));
  const url = pick("url", mine.url, theirs.url), quote = pick("quote", mine.quote, theirs.quote);
  const merged = { ...Object.fromEntries(Object.entries(older).filter(([k]) => !KNOWN.includes(k))), ...newer };
  Object.assign(merged, { text, f, created: Math.min(mine.created, theirs.created), updated: Math.max(mine.updated, theirs.updated) });
  set(merged, "done", done); set(merged, "deleted", removed.length ? Math.min(...removed) : null);
  set(merged, "url", url); set(merged, "quote", quote); set(merged, "base", text === mine.text ? mine.base : theirs.base);
  if (!conflict) return [merged];
  const other = text === mine.text ? theirs : mine;
  const copy = Object.fromEntries(Object.entries(other).filter(([k]) => KNOWN.includes(k) && k !== "base"));
  Object.assign(copy, { id: hex(await sha256(enc.encode(`${mine.id}|${other.text}`))).slice(0, 32), from: device, f: { text: at(other, "text") } });
  return [merged, copy];
}

/** A pack's content as read: {prefix, kid, items, markers, retired}, or null. */
export function parsePack(json) {
  try {
    const o = JSON.parse(json);
    if (o?.v !== 3 || !/^[01]+$/.test(o.prefix || "")) return null;
    const ok = (x) => x && typeof x.id === "string" && x.id;
    return { prefix: o.prefix, kid: String(o.kid ?? ""), retired: o.retired === true,
      items: (Array.isArray(o.items) ? o.items : []).filter(ok), markers: (Array.isArray(o.markers) ? o.markers : []).filter((m) => ok(m) && m.deleted > 0) };
  } catch { return null; }
}

/** v3 §4: whether a pack read under d is taken. */
export async function accepts(l, d, pack) {
  if (!pack || pack.kid !== l.kid || (await l.d(pack.prefix)) !== d) return false;
  for (const x of [...pack.items, ...pack.markers]) if (!(await l.slot(x.id)).startsWith(pack.prefix)) return false;
  return true;
}

/** A base file (option I, §7): 12-byte nonce, then AES-256-GCM with AAD = id then `updated` (8 bytes big-endian). */
export async function openSealed(file, id, updated, keyHex) {
  try {
    const aad = new Uint8Array([...enc.encode(id), ...new Uint8Array(new BigUint64Array([BigInt(updated)]).buffer).reverse()]);
    const key = await crypto.subtle.importKey("raw", unhex(keyHex), "AES-GCM", false, ["decrypt"]);
    const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: file.slice(0, 12), additionalData: aad }, key, file.slice(12)));
    const n = new DataView(plain.buffer).getUint32(0);
    return n <= plain.length - 4 ? plain.slice(4, 4 + n) : null;
  } catch { return null; }
}

/**
 * Notes as this browser holds them from packs: {notes: {id: note}, markers: {id: deleted}, keys: {kid: {seen: {d: at},
 * retired: [prefix]}}, base: sha256}. take() applies one key's pull by §4; under() lays a base file beneath.
 */
export const empty = () => ({ notes: {}, markers: {}, keys: {}, base: null });

async function takeNote(s, n, device) {
  if (s.markers[n.id]) return;
  const have = s.notes[n.id];
  for (const o of have ? await mergeNotes(have, n, device) : [n]) {
    if (o.deleted) { s.markers[o.id] = o.deleted; delete s.notes[o.id]; } else if (!s.markers[o.id]) s.notes[o.id] = o;
  }
}

/** One key's packs (each {d, created_at, content: decrypted or null}): true if taken, false if the pull was stopped. */
export async function take(s, l, incoming, device = "another device") {
  const k = (s.keys[l.kid] ||= { seen: {}, retired: [] });
  const valid = [];
  let invalid = 0;
  for (const inc of [...incoming].sort((a, b) => a.created_at - b.created_at)) {
    const pack = inc.content == null ? null : parsePack(inc.content);
    if (!(await accepts(l, inc.d, pack))) { invalid++; continue; }
    if ((k.seen[inc.d] || 0) > inc.created_at) continue; // never backwards
    valid.push([inc, pack]);
  }
  const gone = new Set(valid.flatMap(([, p]) => [...p.markers.map((m) => m.id), ...p.items.filter((n) => n.deleted).map((n) => n.id)]));
  const removing = [...gone].filter((id) => s.notes[id]).length;
  if (invalid + removing > Math.max(10, Math.floor(Math.max(1, Object.keys(s.notes).length) * 5 / 100))) return false;
  for (const [inc, pack] of valid) {
    k.seen[inc.d] = Math.max(k.seen[inc.d] || 0, inc.created_at);
    if (pack.retired) { if (!k.retired.includes(pack.prefix)) k.retired.push(pack.prefix); continue; }
    for (const m of pack.markers) if (!s.markers[m.id]) { s.markers[m.id] = m.deleted; delete s.notes[m.id]; }
    for (const n of pack.items) await takeNote(s, n, device);
  }
  return true;
}

/** A base file's notes beneath what's held (the packs overlay it). */
export async function under(s, body, kid) {
  let o;
  try { o = JSON.parse(new TextDecoder().decode(body)); } catch { return false; }
  if (o?.v !== 3 || o.kid !== kid) return false;
  for (const m of o.markers || []) if (m?.id && m.deleted > 0 && !s.markers[m.id]) { s.markers[m.id] = m.deleted; delete s.notes[m.id]; }
  for (const n of o.items || []) if (n?.id) await takeNote(s, n);
  return true;
}

// ── Reading them from your relays (the page only) ────────────────────────────────────────────────────

const KEYS_D = "marble/notes/keys";
let state = empty(), who = "", status = { reading: false, partial: false, halted: false };
const listeners = new Set();
export const onPacks = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const tell = () => listeners.forEach((fn) => fn());
/** The notes from packs (not removed), as Notes shows them; and which were removed there. */
export const packNotes = () => Object.values(state.notes);
export const removedInPacks = (id) => !!state.markers[id];
export const packStatus = () => status;

// IndexedDB, one record per account: this browser's copy (decrypted, never sent anywhere)
const db = () => new Promise((ok, no) => {
  const r = indexedDB.open("marble-notes-v3", 1);
  r.onupgradeneeded = () => r.result.createObjectStore("held");
  r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
});
async function store(op, pk, value) {
  try {
    const d = await db();
    return await new Promise((ok, no) => {
      const t = d.transaction("held", op === "get" ? "readonly" : "readwrite").objectStore("held");
      const r = op === "get" ? t.get(pk) : op === "put" ? t.put(value, pk) : t.delete(pk);
      r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
    });
  } catch { return undefined; }
}

/** This account's copy, from this browser (at once, before the relays answer). */
export async function show(pk) {
  who = pk || "";
  state = (pk && (await store("get", pk))) || empty();
  if (who === (pk || "")) tell();
}
/** Signed out: this browser's copy goes. */
export async function forget(pk) {
  if (pk) await store("delete", pk);
  if (who === pk) { state = empty(); who = ""; tell(); }
}

/**
 * Reads your packs and takes what's new. req(relays, filter) and decrypt(text) are passed in, so this file reads only
 * through them; relays: your write and read relays. A pack already taken (same d and time) isn't decrypted again.
 */
export async function readPacks(me, { req, relays, decrypt, fetchBlob, device = "your phone" }) {
  if (!me || status.reading) return;
  status = { ...status, reading: true };
  try {
    if (who !== me) await show(me);
    const keysEvents = (await req(relays, { kinds: [30078], authors: [me], "#d": [KEYS_D], limit: 10 })).filter((e) => e.pubkey === me);
    const keys = new Map();
    for (const e of keysEvents) {
      try { for (const x of JSON.parse(await decrypt(e.content)).keys || []) if (/^[0-9a-f]{64}$/.test(x.k)) keys.set(x.kid || x.k.slice(0, 8), x); } catch {}
    }
    if (!keys.size) return;
    const s = structuredClone(state);
    let partial = false, halted = false;
    for (const [kid, key] of keys) {
      const l = await layout(kid, key.k);
      // every page (§3): until the oldest seen, no −1; stop when a page adds nothing, at most 20 pages
      const all = new Map();
      let until;
      for (let page = 0; ; page++) {
        if (page === 20) { partial = true; break; }
        const got = (await req(relays, { kinds: [30078], authors: [me], "#w": [l.w], limit: 500, ...(until ? { until } : {}) })).filter((e) => e.pubkey === me);
        if (!got.filter((e) => !all.has(e.id) && all.set(e.id, e)).length) break;
        until = Math.min(...got.map((e) => e.created_at));
      }
      // per pack its newest copy (a tie: the lower id)
      const newest = new Map();
      for (const e of all.values()) {
        const d = e.tags.find((t) => t[0] === "d")?.[1];
        const o = d && newest.get(d);
        if (d && (!o || e.created_at > o.created_at || (e.created_at === o.created_at && e.id < o.id))) newest.set(d, e);
      }
      const seen = s.keys[kid]?.seen || {};
      const fresh = [...newest.entries()].filter(([d, e]) => (seen[d] || 0) < e.created_at);
      const incoming = [];
      for (const [d, e] of fresh) { let content = null; try { content = await decrypt(e.content); } catch {} incoming.push({ d, created_at: e.created_at, content }); }
      if (!(await take(s, l, incoming, device))) { halted = true; continue; }
      // the base file under the packs (option I), when its pointer names one not taken yet
      const ptr = (await req(relays, { kinds: [30078], authors: [me], "#d": [l.baseD], limit: 4 })).filter((e) => e.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
      let p = null;
      try { p = ptr && JSON.parse(await decrypt(ptr.content)); } catch {}
      const b = p?.base;
      if (p?.kid === kid && b?.alg === "aes-256-gcm/v1" && b.sha256 !== s.base) {
        const file = await fetchBlob(b.sha256, b.servers || []);
        const body = file && file.length === b.size && hex(await sha256(file)) === b.sha256 && (await openSealed(file, "base", b.updated, b.key));
        if (body && (await under(s, body, kid))) s.base = b.sha256;
      }
    }
    if (who !== me) return; // (signed out or in as someone else meanwhile)
    state = s;
    status = { reading: false, partial, halted };
    await store("put", me, s);
    tell();
  } finally {
    status = { ...status, reading: false };
  }
}
