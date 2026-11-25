// Notes on vaults (~/kist/kist.md 7.14, notes profile §11.1): this page as one of your owner devices
// (§12.3). Your phone (Marble Android) makes the vault; here it's opened through your
// phone's signer, one decrypt per keyring entry each visit. The owner secrets live in this page's memory only; this
// browser keeps its device root and certs (localStorage) and a copy of your notes for speed (IndexedDB), and the copy
// goes on sign-out. Until your account has a notes vault, Notes stay as they were (later.js).
//
// later.js asks: state(), notes(), write(changes), resolve(...). Everything is versioned (§5.3): an edit or a removal
// is a new version, so none asks for confirmation. Hard-to-undo things (§12.4) aren't offered on a page.
import { req, publish, FAST, relays as join } from "./signin/pool.js";
import { accountRelays } from "./account-relays.js";
import { current, sign, nip44DecryptFrom, canVault, onMe } from "./me.js";

let lib = null; // the vault code (and the noble bundle under it), loaded only for an account that may have a vault
const load = () => (lib ??= Promise.all([import("./vault/sync.js"), import("./vault/notes.js"), import("./vault/core.js"), import("./vault/refusals.js")])
  .then(([sync, notes, core, refusals]) => ({ sync, notes, core, refusals })));

let early = []; // changes made while the vault was opening
let libNow = null; // the vault code, once loaded
let who = "", session = null, status = "none", why = "", cached = null, queue = new Map(), timer = null, writing = null, opening = null;
// status: "none" (no vault: Notes as before), "opening", "open", "offline" (opened, can't reach relays now), "error",
// "refused" (your signer wouldn't decrypt your keyring for this site: Notes show this browser's copy, and say why), "removed"
const listeners = new Set();
export const onVault = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const tell = () => listeners.forEach((fn) => fn());
export const state = () => status;
export const reason = () => why;
/** Something the vault names hasn't loaded yet (§7.4): Notes says "syncing…" and shows what it has. */
export const syncing = () => !!session && (session.gaps.size > 0 || blobFailed.size > 0);
/**
 * Storage servers that said no and are being left alone for now (good-guest §3.5), one plain line each, newest first:
 * "blossom.x says too many requests, trying again at 3 pm", "blossom.x refused storage: 402 payment required".
 */
export function refusals() {
  if (!session || !libNow) return [];
  return session.refusals.active(Math.floor(Date.now() / 1000)).map((r) => libNow.refusals.refusalLine(r, when));
}
/** Whether changes made here are waiting because storage servers refused them (not lost, not a relay problem). */
export const held = () => heldUntil > Date.now() / 1000;
let heldUntil = 0;
/** A time to come back, as Notes says it: "in 5 minutes", "at 3 pm", "tomorrow at 9:30 am", "on Fri at 3 pm". */
export function when(t) {
  const left = t - Date.now() / 1000;
  if (left < 90) return "in a minute";
  if (left < 3600) return `in ${Math.round(left / 60)} minutes`;
  const d = new Date(t * 1000), today = new Date();
  const time = d.toLocaleTimeString("en-US", { hour: "numeric", minute: d.getMinutes() ? "2-digit" : undefined }).toLowerCase().replace(/\s+/g, " ");
  const days = Math.round((new Date(d).setHours(0, 0, 0, 0) - new Date(today).setHours(0, 0, 0, 0)) / 86400000);
  return days <= 0 ? `at ${time}` : days === 1 ? `tomorrow at ${time}` : `on ${d.toLocaleDateString("en-US", { weekday: "short" })} at ${time}`;
}
// When the first refusal is over: Notes redraws, and what waited on it is tried again (once, not on a loop).
let wakeTimer = null;
function wake() {
  clearTimeout(wakeTimer);
  const t = Math.floor(Date.now() / 1000), next = session?.refusals.active(t).map((r) => r.until).sort((a, b) => a - b)[0];
  if (!next) return;
  wakeTimer = setTimeout(() => { tell(); if (session) { if (blobHeld.size) remember(); if (queue.size) flush(); } wake(); }, Math.max(1000, (next - t) * 1000 + 500));
}
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
  // (the engine asks one server at a time; a refusal comes back as {status, reason, retryAfter} so it can leave that
  // server alone for a while. X-Reason and Retry-After show only when the server lets pages read them.)
  async get(servers, sha) {
    let last = null;
    for (const s of servers) {
      try {
        const r = await fetch(`${s.replace(/\/+$/, "")}/${sha}`, { signal: AbortSignal.timeout(15000) });
        if (r.ok) return new Uint8Array(await r.arrayBuffer());
        last = { status: r.status, reason: r.headers.get("X-Reason"), retryAfter: r.headers.get("Retry-After") };
      } catch {}
    }
    return last && libNow?.refusals.isRefusal(last.status, last.retryAfter) ? last : null;
  },
  /** BUD-02 upload: {ok, why, sha256 (as the server describes the blob)}. */
  // (XMLHttpRequest, not fetch: it says how much has gone up, for the picture's progress bar)
  put(server, bytes, auth, onSent) {
    return new Promise((done) => {
      const x = new XMLHttpRequest();
      x.open("PUT", `${server.replace(/\/+$/, "")}/upload`);
      x.timeout = Math.max(30000, bytes.length / 50); // (30 s, or about 50 KB/s for a big picture)
      x.setRequestHeader("Content-Type", "application/octet-stream");
      x.setRequestHeader("Authorization", "Nostr " + btoa(JSON.stringify(auth)));
      if (onSent) x.upload.onprogress = (e) => onSent(e.loaded);
      x.onload = () => {
        if (x.status < 200 || x.status >= 300) {
          const reason = x.getResponseHeader("X-Reason"), retryAfter = x.getResponseHeader("Retry-After");
          return done({ ok: false, why: `${x.status} ${reason || ""}`.trim(), status: x.status, reason, retryAfter });
        }
        let d = null; try { d = JSON.parse(x.responseText); } catch {}
        done({ ok: true, sha256: typeof d?.sha256 === "string" ? d.sha256 : null });
      };
      x.onerror = () => done({ ok: false, why: "network error" });
      x.ontimeout = () => done({ ok: false, why: "timed out" });
      x.send(bytes);
    });
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
  if (who !== pk) { session = null; cached = null; queue = new Map(); early = []; blobTexts.clear(); blobFailed.clear(); blobHeld.clear(); heldUntil = 0; clearTimeout(wakeTimer); }
  who = pk; status = "opening"; lastTry = Date.now(); tell();
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
      const all = await sync.readKeyrings({ me: pk, relays: mine, net, signer: signer(pk) });
      entries = sync.notesVault(all);
      // keyring entries there, but the signer wouldn't open any of them: not "no vault"
      if (!entries.length && all.refused.length) return fail(pk, all.refused[0]);
      // no relay answered: not "no vault" (that would hide your notes and drop this browser's copy), tried again soon
      if (!entries.length && !all.answered) { setTimeout(() => { if (who === pk && !session) { lastTry = 0; retry(); } }, 15000); return fail(pk, "couldn't reach your relays"); }
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
  // a signer that won't decrypt for this site (Marble's own signer says "not_allowed: …"): Notes show this browser's
  // copy, and say why; anything else is an error, tried again later
  status = /unsupported|not[_ ]allowed|denied|rejected|refused/i.test(m) ? "refused" : "error";
  why = m;
  tell();
  return null;
}

/**
 * Not open (no vault yet, the signer refused, an error): looked for again when you come back to the page, at most every
 * 5 minutes, so a vault your phone makes meanwhile, or a site switch you turn on, is picked up without a reload.
 */
let lastTry = 0;
function retry() {
  if (session || opening || !current() || !["none", "refused", "error"].includes(status) || Date.now() - lastTry < 300000) return;
  start(current());
}

/** Signed out, or another account: the session and this browser's copy of the notes go (§12.3). */
export async function stop(pk) {
  clearTimeout(timer);
  if (pk) await idb("delete", pk);
  session = null; cached = null; queue = new Map(); early = []; blobTexts.clear(); blobFailed.clear(); blobHeld.clear(); heldUntil = 0; clearTimeout(wakeTimer); status = "none"; why = "";
  for (const u of pictureUrls.values()) if (u) URL.revokeObjectURL(u);
  pictureUrls.clear();
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
    deletedOn: n.deletedOn, long: !!x.blob, pictures: x.pictures };
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
      const note = { id, text: q.note.text, created: q.note.created, updated: q.note.updated, done: q.note.done, url: q.note.url, quote: q.note.quote, app: q.note.from,
        // (a new note's pictures; a note's pictures already in the vault stay as its meta has them)
        pictures: q.meta.pictures === undefined && q.note.pictures?.length ? q.note.pictures : undefined };
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
      status = s.removed() ? "removed" : "open"; why = ""; heldUntil = 0;
      await remember();
    } catch (err) {
      why = String(err?.message || err);
      if (/removed/.test(why)) status = "removed";
      // storage servers said no: the changes wait here, and nothing is sent until the first of them may be asked again
      else if (err instanceof libNow.refusals.RefusedError) { heldUntil = err.retryAt || 0; clearTimeout(timer); wake(); }
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
    session.blob(x.blob).then((b) => { blobTexts.set(x.ver, new TextDecoder().decode(b)); blobFailed.delete(x.ver); blobHeld.delete(x.ver); tell(); })
      // (held back by a server's refusal: not "syncing…"; Notes names the server, and it's asked again when it said)
      .catch((err) => { if (err instanceof libNow.refusals.RefusedError) { blobHeld.add(x.ver); blobFailed.delete(x.ver); wake(); } else blobFailed.add(x.ver); tell(); });
  }
  if (session.held.size) wake();
}
const blobTexts = new Map(), blobFailed = new Set(), blobHeld = new Set();
/** A note's text; one kept in a blob shows a line saying so until it has loaded. */
export const textOf = (item) => (item.long && !item.text ? blobTexts.get(item.ver) || (blobFailed.has(item.ver) || blobHeld.has(item.ver) ? "(This note hasn't loaded yet.)" : "(Loading this note…)") : item.text);

// ── pictures (a screenshot dropped or pasted on the page): each an encrypted blob of the vault, named in the note ──

/** Whether a picture can be kept now: the vault is open here (or opening, and will be). */
export const canKeepPictures = () => (!!session && status !== "removed") || status === "opening";
const MAX_PICTURE = 25 * 1024 * 1024;
/**
 * Uploads a picture (a File or Blob) as a blob of your notes vault: {ref, mime, w, h}. Throws with a sentence to show
 * when it can't (not an image, too big, no vault, your storage servers refused it).
 */
export async function uploadPicture(file, onProgress = null) {
  if (opening) await opening;
  if (!session || status === "removed") throw new Error(status === "removed" ? "This browser was removed from your account, so it can't add to your notes." : "Your notes vault isn't open here.");
  const { notes: N } = libNow;
  if (!N.PICTURE_TYPES.includes(file.type)) throw new Error("Only PNG, JPEG, GIF and WebP pictures can go in your notes.");
  if (file.size > MAX_PICTURE) throw new Error("That picture is over 25 MB.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  let w = 0, h = 0;
  try { const b = await createImageBitmap(new Blob([bytes], { type: file.type })); w = b.width; h = b.height; b.close(); }
  catch { throw new Error("That file isn't a picture this browser can open."); }
  const ref = await session.upload(await session.device(), bytes, 2, undefined, onProgress).catch((err) => {
    if (err instanceof libNow.refusals.RefusedError) { wake(); tell(); throw new Error(refusals()[0] ? refusals()[0] + "." : "Your storage servers wouldn't take the picture."); }
    throw new Error("Your storage servers wouldn't take the picture. Try again in a moment.");
  });
  const pic = { ref, mime: file.type, w, h };
  pictureUrls.set(ref.parts[0], URL.createObjectURL(new Blob([bytes], { type: file.type })));
  return pic;
}
// pictures loaded on this page: first part's sha → object URL (null while loading, false when it wouldn't load)
const pictureUrls = new Map();
/** A picture's address on this page, or null while it loads (Notes is told when it has). */
export function pictureUrl(pic) {
  const k = pic.ref.parts[0];
  if (pictureUrls.has(k)) return pictureUrls.get(k) || null;
  if (!session) return null;
  pictureUrls.set(k, null);
  session.blob(pic.ref).then((b) => { pictureUrls.set(k, URL.createObjectURL(new Blob([b], { type: pic.mime }))); tell(); })
    .catch(() => { pictureUrls.delete(k); });
  return null;
}

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
    else if (session) refresh();
    else retry();
  });
  addEventListener("pagehide", () => flush());
  onMe((pk) => { if (who && who !== pk) stop(who); if (pk) start(pk); });
  // already signed in when the page loads: opened now too (onMe only fires on a change)
  if (current()) setTimeout(() => { if (!who && current()) start(); }, 0);
}
