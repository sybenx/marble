// Notes on vaults (~/nostr-sync-nip/vaults.md 7.14, notes profile §11.1): this page as one of your owner devices
// (§12.3). Your phone (Marble Android) makes the vault; here it's opened however you signed in (the extension, or your
// phone's signer), one decrypt per keyring entry each visit. The owner secrets live in this page's memory only; this
// browser keeps its device root and certs (localStorage) and a copy of your notes for speed (IndexedDB), and the copy
// goes on sign-out. Until your account has a notes vault, Notes stay as they were (later.js).
//
// later.js asks: state(), notes(), write(changes), resolve(...). Everything is versioned (§5.3): an edit or a removal
// is a new version, so none asks for confirmation. Hard-to-undo things (§12.4) aren't offered on a page.
import { req, publish, FAST, relays as join } from "./pool.js";
import { accountRelays } from "./account-relays.js";
import { current, sign, nip44DecryptFrom, canVault, onMe } from "./me.js";

let lib = null; // the vault code (and the noble bundle under it), loaded only for an account that may have a vault
const load = () => (lib ??= Promise.all([import("./vault/sync.js"), import("./vault/notes.js"), import("./vault/core.js")]).then(([sync, notes, core]) => ({ sync, notes, core })));

let early = []; // changes made while the vault was opening
let libNow = null; // the vault code, once loaded
let who = "", session = null, status = "none", why = "", cached = null, queue = new Map(), timer = null, writing = null, opening = null;
// status: "none" (no vault: Notes as before), "opening", "open", "offline" (opened, can't reach relays now), "error", "removed"
const listeners = new Set();
export const onVault = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const tell = () => listeners.forEach((fn) => fn());
export const state = () => status;
export const reason = () => why;
/** Something the vault names hasn't loaded yet (§7.4): Notes says "syncing…" and shows what it has. */
export const syncing = () => !!session && (session.gaps.size > 0 || blobFailed.size > 0);
/** Whether Notes come from the vault (open, or shown from this browser's copy while it opens). */
export const active = () => status === "open" || status === "offline" || status === "removed" || (status === "opening" && !!cached);

// ── this browser's storage ──
const STORE = (pk) => `marble.vault.${pk}`;
function storeFor(pk) {
  let o = {};
  try { o = JSON.parse(localStorage.getItem(STORE(pk))) || {}; } catch {}
  return { get: (k) => o[k], set: (k, v) => { o[k] = v; try { localStorage.setItem(STORE(pk), JSON.stringify(o)); } catch {} } };
}
const db = () => new Promise((ok, no) => {
  const r = indexedDB.open("marble-vault", 1);
  r.onupgradeneeded = () => r.result.createObjectStore("notes");
  r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
});
async function idb(op, pk, value) {
  try {
    const d = await db();
    return await new Promise((ok, no) => {
      const t = d.transaction("notes", op === "get" ? "readonly" : "readwrite").objectStore("notes");
      const r = op === "get" ? t.get(pk) : op === "put" ? t.put(value, pk) : t.delete(pk);
      r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
    });
  } catch { return undefined; }
}

// ── the network, as the vault code wants it ──
const net = {
  req: (urls, filter) => req(urls, filter, { timeout: 7000, settle: 1500 }),
  publish: (urls, e) => publish(urls, e),
  async get(servers, sha) {
    for (const s of servers) {
      try {
        const r = await fetch(`${s.replace(/\/+$/, "")}/${sha}`, { signal: AbortSignal.timeout(15000) });
        if (r.ok) return new Uint8Array(await r.arrayBuffer());
      } catch {}
    }
    return null;
  },
  /** BUD-02 upload: {ok, why, sha256 (as the server describes the blob)}. */
  async put(server, bytes, auth) {
    try {
      const r = await fetch(`${server.replace(/\/+$/, "")}/upload`, { method: "PUT", body: bytes, signal: AbortSignal.timeout(30000),
        headers: { "Content-Type": "application/octet-stream", Authorization: "Nostr " + btoa(JSON.stringify(auth)) } });
      if (!r.ok) return { ok: false, why: `${r.status} ${r.headers.get("X-Reason") || ""}`.trim() };
      const d = await r.json().catch(() => null);
      return { ok: true, sha256: typeof d?.sha256 === "string" ? d.sha256 : null };
    } catch (err) { return { ok: false, why: String(err?.message || err) }; }
  },
};
const signer = (pk) => ({ pubkey: pk, sign: (t) => sign(t), decrypt: (eph, ct) => nip44DecryptFrom(eph, ct) });
const deviceName = () => {
  const ua = navigator.userAgent;
  const b = /Firefox\//.test(ua) ? "Firefox" : /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "a browser";
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iPhone" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "";
  return `Marble Web (${b}${os ? " on " + os : ""})`;
};

/** Opens your notes vault, if you have one. One decrypt per keyring entry (your signer may ask). */
export function start(pk = current()) {
  if (!pk || !canVault()) return Promise.resolve(null);
  if (opening && who === pk) return opening;
  if (who !== pk) { session = null; cached = null; queue = new Map(); early = []; blobTexts.clear(); blobFailed.clear(); }
  who = pk; status = "opening"; tell();
  const p = (async () => {
    cached = (await idb("get", pk)) || null;
    status = "opening"; why = ""; tell();
    libNow = await load();
    const { sync } = libNow;
    const a = await accountRelays(pk).catch(() => null);
    if (who !== pk) return null;
    const mine = join(a?.write || [], a?.read || [], FAST.slice(0, 3));
    let entries;
    try {
      entries = sync.notesVault(await sync.readKeyrings({ me: pk, relays: mine, net, signer: signer(pk) }));
    } catch (err) { return fail(pk, err); }
    if (who !== pk) return null;
    if (!entries.length) { status = "none"; cached = null; await idb("delete", pk); tell(); return null; }
    try {
      // your Blossom servers (kind 10063): where this page's blobs go, in your order (the vault's own when you have none)
      const lists = await req(mine, { kinds: [10063], authors: [pk] }, { timeout: 5000, settle: 1000 }).catch(() => []);
      const s = new sync.Session({ me: pk, entries, net, signer: signer(pk), store: storeFor(pk), deviceName: deviceName(), accountRelays: a?.write || [],
        blossom: sync.serverList(lists, pk) });
      await s.open();
      if (who !== pk) return null;
      session = s;
      status = s.removed() ? "removed" : "open";
      const waiting = early; early = [];
      for (const i of waiting) write(i);
      await remember();
      tell();
      return s;
    } catch (err) { return fail(pk, err); }
  })();
  opening = p;
  p.finally(() => { if (opening === p) opening = null; });
  return p;
}
function fail(pk, err) {
  if (who !== pk) return null;
  const m = String(err?.message || err);
  // a signer that won't decrypt for this site: Notes as before
  status = /unsupported|not allowed|denied|rejected/i.test(m) ? "none" : "error";
  why = m;
  tell();
  return null;
}

/** Signed out, or another account: the session and this browser's copy of the notes go (§12.3). */
export async function stop(pk) {
  clearTimeout(timer);
  if (pk) await idb("delete", pk);
  session = null; cached = null; queue = new Map(); early = []; blobTexts.clear(); blobFailed.clear(); status = "none"; why = "";
  if (who === pk || !pk) who = "";
  tell();
}

// ── notes ──

/** Your notes from the vault, as later.js shows them: {id, text, created, updated, done, url, quote, from, conflict…}. */
export function notes() {
  if (!session) {
    const list = (cached?.notes || []).filter((n) => !early.some((x) => x.id === n.id));
    return [...list, ...early.filter((x) => !x.deleted)];
  }
  const { notes: N } = libSync();
  const list = N.notesOf(session.vault.state(), session.vault.roster()).map(asItem);
  // what's queued here shows at once, before it's written
  for (const [id, q] of queue) {
    const i = list.findIndex((x) => x.id === id);
    if (q.note.deleted) { if (i >= 0) list.splice(i, 1); continue; }
    const item = { ...(i >= 0 ? list[i] : {}), ...q.note, conflict: false, versions: null };
    if (i >= 0) list[i] = item; else list.push(item);
  }
  return list;
}
const libSync = () => libNow;
function asItem(n) {
  const x = n.note;
  return { id: x.id, text: x.text ?? "", created: x.created, updated: x.updated, done: x.done, deleted: null, url: x.url, quote: x.quote, from: x.app,
    entry: n.entry, ver: x.ver, meta: x.meta, base: n.current, conflict: n.conflict, versions: n.conflict ? n.versions.map((v) => ({ ver: v.ver, text: v.text ?? blobTexts.get(v.ver) ?? "", device: v.device, done: v.done, meta: v.meta })) : null,
    deletedOn: n.deletedOn, long: !!x.blob };
}
/** Whether an entry for this note id exists in the vault at all (any version, deleted included). */
export function has(id) {
  if (!session) return false;
  return session.vault.state().entries.has(libNow.notes.entryOf(id));
}

/**
 * Queues a change to a note (an item as later.js keeps it; `deleted` set to remove it). Written as one version per
 * pause (§11.1): after 2 s, or at once when the page goes to the background.
 */
export function write(item) {
  // (while it opens, with your notes showing from this browser's copy: the change waits for it)
  if (!session) { if (status !== "opening" || !cached) return false; early = [...early.filter((x) => x.id !== item.id), item]; tell(); return true; }
  const was = queue.get(item.id);
  // the versions it was based on: the first queued change's, so the queue makes one version superseding them
  const cur = notes().find((n) => n.id === item.id);
  const base = was ? was.base : session.vault.state().entries.get(libNow.notes.entryOf(item.id))?.current.map((p) => p.ver) || [];
  const meta = was ? was.meta : cur?.meta || {};
  queue.set(item.id, { note: item, base: item.supersedeAll ? item.supersedeAll : (cur?.conflict && !item.supersedeAll ? [cur.ver] : base), meta });
  tell();
  clearTimeout(timer);
  timer = setTimeout(flush, 2000);
  return true;
}
/** Writes what's queued as one commit (after catching up). */
export async function flush() {
  clearTimeout(timer);
  if (!session || !queue.size) return;
  if (writing) return writing.then(flush);
  const s = session, { notes: N } = libNow;
  const taking = new Map(queue);
  const p = (async () => {
    const ops = [];
    for (const [id, q] of taking) {
      const entry = N.entryOf(id);
      if (q.note.deleted) { if (q.base.length) ops.push(N.deletePut(entry, q.base)); continue; }
      const note = { id, text: q.note.text, created: q.note.created, updated: q.note.updated, done: q.note.done, url: q.note.url, quote: q.note.quote, app: q.note.from };
      let data = null;
      if (N.needsBlob(note.text)) {
        const dev = await s.device();
        data = { blob: await s.upload(dev, new TextEncoder().encode(note.text), 2) };
      }
      ops.push(N.notePut(note, q.base, q.meta, data));
    }
    try {
      if (ops.length) await s.commit(ops);
      for (const [id, q] of taking) if (queue.get(id) === q) queue.delete(id);
      status = s.removed() ? "removed" : "open"; why = "";
      await remember();
    } catch (err) {
      why = String(err?.message || err);
      if (/removed/.test(why)) status = "removed";
      else { status = "offline"; clearTimeout(timer); timer = setTimeout(flush, 30000); }
    }
    tell();
  })();
  writing = p;
  await p.finally(() => { if (writing === p) writing = null; });
}
/** Catches up with your other devices (on coming back to the tab). */
export async function refresh() {
  if (!session || writing) return;
  try { await session.catchUp(); status = session.removed() ? "removed" : "open"; await remember(); } catch { status = "offline"; }
  tell();
}
async function remember() {
  if (!session || session.me !== who) return;
  cached = { notes: notes() };
  await idb("put", who, cached);
  // a note whose text is in a blob: fetched, then shown
  for (const n of libNow.notes.notesOf(session.vault.state(), session.vault.roster())) for (const x of n.versions) {
    if (x.text !== null || !x.blob || blobTexts.has(x.ver)) continue;
    session.blob(x.blob).then((b) => { blobTexts.set(x.ver, new TextDecoder().decode(b)); blobFailed.delete(x.ver); tell(); })
      .catch(() => { blobFailed.add(x.ver); tell(); });
  }
}
const blobTexts = new Map(), blobFailed = new Set();
/** A note's text; one kept in a blob shows a line saying so until it has loaded. */
export const textOf = (item) => (item.long && !item.text ? blobTexts.get(item.ver) || (blobFailed.has(item.ver) ? "(This note hasn't loaded yet.)" : "(Loading this note…)") : item.text);

// ── conflicts (§5.4, §11.1): keep this, keep both, or edit ──

/** Keep one version: a version with its text, superseding every current one. */
export function keepVersion(item, ver) {
  const v = item.versions.find((x) => x.ver === ver);
  if (!v) return;
  write({ ...item, text: v.text, done: v.done, updated: Math.max(Math.floor(Date.now() / 1000), item.updated + 1), supersedeAll: item.base });
}
/** Keep both: this note keeps the first version; each other becomes a note of its own. */
export function keepBoth(item, newId) {
  const [first, ...rest] = item.versions;
  const t = Math.max(Math.floor(Date.now() / 1000), item.updated + 1);
  write({ ...item, text: first.text, done: first.done, updated: t, supersedeAll: item.base });
  for (const v of rest) write({ id: newId(), text: v.text, created: item.created, updated: t, done: v.done, url: item.url, quote: item.quote, from: item.from });
}
/** Edit: your text, superseding every version. */
export function combine(item, text) {
  write({ ...item, text, updated: Math.max(Math.floor(Date.now() / 1000), item.updated + 1), supersedeAll: item.base });
}


if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flush();
    else refresh();
  });
  addEventListener("pagehide", () => flush());
  onMe((pk) => { if (who && who !== pk) stop(who); if (pk) start(pk); });
}
