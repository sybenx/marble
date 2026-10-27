// Notes and tabs, as the Marble app keeps them (android Later.kt; the Mac writes the same). Notes: what you typed
// and pressed Enter on, or kept; on your account as kind 30078 d=marble/later, NIP-44 to yourself, merged item by
// item (the newer `updated` wins; removed ones stay a month as markers so other devices learn it). Here they live
// in this browser, and sync only when you signed in with the Marble extension: signed in by your phone's code,
// they stay here (your servers don't encrypt for sites, on purpose). Tabs: pages opened from Marble, in this browser.
import { req, relays, publish, FAST } from "./pool.js";
import { outbox } from "./data.js";
import { current, method, sign, nip44Self, onMe } from "./me.js";

export const TAG = "marble/later";
const KEY = "marble.later", TABS = "marble.tabs", ENGINE = "marble.engine";
const now = () => Math.floor(Date.now() / 1000);
const MONTH = 30 * 86400;
const listeners = new Set();
export const onNotes = (fn) => (listeners.add(fn), () => listeners.delete(fn));

function read(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function write(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} }

// ── Notes ────────────────────────────────────────────────────────────────────────────────────────────

let doc = read(KEY, { v: 1, items: [] }); // the account copy's shape, unknown fields and all
if (doc.v !== 1 || !Array.isArray(doc.items)) doc = { v: 1, items: [] };

const words = (s) => String(s || "").trim().replace(/\s+/g, " ").toLowerCase();
export const same = (a, b) => words(a) === words(b);
const newId = () => [...crypto.getRandomValues(new Uint8Array(8))].map((x) => x.toString(16).padStart(2, "0")).join("");
const next = (item) => Math.max(now(), (item.updated || 0) + 1);

/** The notes you see (not removed), newest first. */
export const items = () => doc.items.filter((i) => !i.deleted).sort((a, b) => b.created - a.created);
export const open = () => items().filter((i) => !i.done);
export const doneItems = () => items().filter((i) => i.done);
/** These words, typed and kept, not ticked: what the filled bookmark shows. */
export const saved = (text) => items().find((i) => !i.url && !i.done && same(i.text, text)) || null;

function put(item) {
  doc.items = doc.items.filter((i) => i.id !== item.id).concat(item);
  doc.at = Math.max(...doc.items.map((i) => i.updated || 0), now());
  write(KEY, doc);
  listeners.forEach((fn) => fn());
  changed();
}

/** Enter: the words kept as a note (never twice: the same words come back, unticked). Returns {item, fresh}. */
export function jot(text) {
  const t = String(text || "").trim().slice(0, 500);
  if (!t) return null;
  const have = doc.items.find((i) => !i.url && !i.deleted && same(i.text, t));
  const item = have ? { ...have, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null };
  put(item);
  return { item, fresh: !have };
}
/** A page kept as a note (from Tabs): by its address; kept again, it comes back rather than doubling. */
export function keepPage(url, title) {
  const t = String(title || url).trim().slice(0, 500);
  const have = doc.items.find((i) => i.url === url && !i.deleted);
  put(have ? { ...have, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null, url });
}
export const tick = (item, on) => put({ ...item, done: on ? now() : null, updated: next(item) });
/** Removed: only a marker stays (no text), for a month, so other devices learn it. */
export const remove = (item) => put({ ...item, text: "", deleted: now(), updated: next(item) });

/** The account's copy merged in; true if this browser has something the copy hasn't (so it should be sent). */
function merge(theirs) {
  if (theirs?.v !== 1 || !Array.isArray(theirs.items)) return false;
  const byId = new Map();
  for (const i of [...theirs.items, ...doc.items]) {
    if (!i?.id || (!String(i.text || "").trim() && !i.deleted)) continue;
    const old = byId.get(i.id);
    if (!old || (i.updated || 0) > (old.updated || 0)) byId.set(i.id, i);
  }
  const merged = [...byId.values()].filter((i) => !i.deleted || now() - i.deleted < MONTH);
  const { items: _, ...rest } = theirs;
  const before = JSON.stringify(theirs.items.map((i) => [i.id, i.updated]).sort());
  doc = { ...doc, ...rest, v: 1, items: merged, at: Math.max(...merged.map((i) => i.updated || 0), 0) };
  write(KEY, doc);
  listeners.forEach((fn) => fn());
  return JSON.stringify(merged.map((i) => [i.id, i.updated]).sort()) !== before;
}

// ── Syncing (signed in with the extension only) ──────────────────────────────────────────────────────

export const syncs = () => !!current() && method() === "extension" && !!window.nostr?.nip44;
let lastPull = 0, sendTimer = null, dirty = read("marble.later.dirty", false);
function changed() {
  dirty = true;
  write("marble.later.dirty", true);
  if (!syncs()) return;
  clearTimeout(sendTimer);
  sendTimer = setTimeout(send, 10000);
}

async function where() {
  const o = await Promise.race([outbox(current()), new Promise((r) => setTimeout(() => r({ write: [], read: [] }), 2000))]);
  return { write: o.write.length ? o.write : FAST.slice(0, 3), both: relays(o.write, o.read, FAST.slice(0, 2)).slice(0, 8) };
}

/** Reads your account's copy and merges it in (at most once a minute unless forced). */
export async function pull({ force = false } = {}) {
  if (!syncs() || (!force && Date.now() - lastPull < 60000)) return false;
  lastPull = Date.now();
  const me = current();
  const got = (await req((await where()).both, { kinds: [30078], authors: [me], "#d": [TAG], limit: 4 }, { timeout: 5000, settle: 1000 }))
    .filter((e) => e.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
  if (!got) { if (dirty) changed(); return false; }
  let theirs;
  try { theirs = JSON.parse(await nip44Self(got.content, false)); } catch { return false; }
  const ahead = merge(theirs);
  if (ahead || dirty) changed();
  return true;
}

let sending = false;
/** Sends your notes to your account: read and merged first, so another device's changes are never lost. */
export async function send() {
  if (!syncs() || sending) return;
  sending = true;
  try {
    lastPull = 0;
    await pull({ force: true }).catch(() => {});
    const content = await nip44Self(JSON.stringify(doc), true);
    const ev = await sign({ kind: 30078, tags: [["d", TAG]], content });
    const sent = await publish((await where()).write, ev);
    if (sent.ok.length) { dirty = false; write("marble.later.dirty", false); }
  } catch {} finally { sending = false; }
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden" && dirty && syncs()) { clearTimeout(sendTimer); send(); }
    if (document.visibilityState === "visible") pull();
  });
  onMe(() => { lastPull = 0; pull(); });
}

// ── Tabs: pages opened from Marble, in this browser (30 days, at most 500) ───────────────────────────

export const tabs = () => read(TABS, []).filter((t) => now() - t.at < MONTH);
export function opened(url, title, from) {
  const list = [{ url, title: title || hostOf(url), at: now(), from: from || "" }, ...tabs().filter((t) => t.url !== url)].slice(0, 500);
  write(TABS, list);
  listeners.forEach((fn) => fn());
}
export const hostOf = (url) => { try { return new URL(url).hostname.replace(/^www\./, ""); } catch { return url; } };

// ── Your engine ──────────────────────────────────────────────────────────────────────────────────────

export const ENGINES = {
  DuckDuckGo: (q, dark) => `https://duckduckgo.com/?q=${encodeURIComponent(q)}&kae=${dark ? "d" : "-1"}`,
  Google: (q) => `https://www.google.com/search?q=${encodeURIComponent(q)}`,
  Bing: (q) => `https://www.bing.com/search?q=${encodeURIComponent(q)}`,
  Brave: (q) => `https://search.brave.com/search?q=${encodeURIComponent(q)}`,
  Ecosia: (q) => `https://www.ecosia.org/search?q=${encodeURIComponent(q)}`,
};
export const engine = () => { const e = read(ENGINE, "DuckDuckGo"); return ENGINES[e] ? e : "DuckDuckGo"; };
export const setEngine = (name) => { if (ENGINES[name]) write(ENGINE, name); };
export const results = (q) => ENGINES[engine()](q, matchMedia("(prefers-color-scheme: dark)").matches);

// ── "Go to …": only for an address you typed (android Sites.kt's rule) ───────────────────────────────
// A dot and an ending, or a scheme: "example.com", "lemmy.world/c/x". A bare word ("apple") is never a site:
// .com has to be typed on purpose, so it searches.

const ADDRESS = /^(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})(?:[/:?#]\S*)?$/i;
const NOT_TLDS = new Set(["js", "ts", "kt", "py", "rs", "txt", "md", "json", "png", "jpg", "jpeg", "gif", "pdf", "mp4", "mp3", "exe", "apk", "zip", "html", "css"]);

/** The site an address names: {address, url}, or null for anything that isn't one (it's searched). */
export function site(typed) {
  const q = String(typed || "").trim();
  if (!q || q.includes(" ")) return null;
  const m = ADDRESS.exec(q);
  if (!m) return null;
  const host = m[1].toLowerCase();
  if (NOT_TLDS.has(host.split(".").pop())) return null;
  return { address: host.replace(/^www\./, ""), url: /^https?:\/\//i.test(q) ? q : `https://${q}` };
}
