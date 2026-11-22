// Notes and tabs, as the Marble app keeps them. Notes: what you typed and pressed Enter on, or kept. Once your account
// has a notes vault (your phone makes it: kist.md, notes-vault.js), they're read from and written to it, from any
// sign-in that can (your phone's signer). Before that, as before vaults: on your account as kind 30078
// d=marble/later, NIP-44 to yourself, merged item by item (the newer `updated` wins; removed ones stay a month as
// markers so other devices learn it), which only the old browser extension could sync; now kept in this browser.
// Tabs: pages opened from Marble, in this browser. Notes, Searches and Tabs belong to the account signed in (and those
// from while nobody was, to nobody): another account never sees them or gets them merged in, and signing out leaves
// none of them on the page (~/igloo/signin-signout-audit.md, fix 3).
import { req, relays, publish, FAST, testRelay } from "./signin/pool.js";
import { outbox } from "./data.js";
import { current, method, sign, nip44Self, onMe } from "./me.js";
// Notes on vaults (notes-vault.js): once your account has a notes vault (your phone makes it), Notes are read from and
// written to it, from any sign-in that can (your phone's signer), and marble/later isn't written here
import * as vault from "./notes-vault.js";
import { decide, completeRead, fullRead, written, retryAfter, missesAgainst } from "./sync-guard.js";
import { accountRelays, heard } from "./account-relays.js";

export const TAG = "marble/later";
// this browser's copies, by account ("" while nobody's signed in)
const KEY = (pk) => pk ? `marble.later.${pk}` : "marble.later", TABS = (pk) => pk ? `marble.tabs.${pk}` : "marble.tabs";
const DIRTY = (pk) => pk ? `marble.later.dirty.${pk}` : "marble.later.dirty", SYNCED = (pk) => `marble.later.synced.${pk}`;
// the newest account copy this browser has seen (its created_at), and how many complete reads in a row missed it
// (sync-guard.js: an answer without it means the relay holding it may be down, until three in a row say otherwise)
const COPY_AT = (pk) => `marble.later.copy-at.${pk}`, MISSED = (pk) => `marble.later.missed.${pk}`;
const now = () => Math.floor(Date.now() / 1000);
const MONTH = 30 * 86400;
const listeners = new Set();
export const onNotes = (fn) => (listeners.add(fn), () => listeners.delete(fn));

function read(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function write(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} }

// ── Notes ────────────────────────────────────────────────────────────────────────────────────────────

// Once: what was kept before accounts had their own copies goes to whoever is signed in (if nobody, it stays nobody's).
if (typeof localStorage !== "undefined" && !localStorage.getItem("marble.later.scoped")) {
  const pk = current();
  if (pk) for (const [from, to] of [["marble.later", KEY(pk)], ["marble.tabs", TABS(pk)], ["marble.searches", `marble.searches.${pk}`], ["marble.later.dirty", DIRTY(pk)]]) {
    const v = localStorage.getItem(from);
    if (v !== null && localStorage.getItem(to) === null) localStorage.setItem(to, v);
    localStorage.removeItem(from);
  }
  write("marble.later.scoped", 1);
}
const load = (pk) => { const d = read(KEY(pk), null); return d?.v === 1 && Array.isArray(d.items) ? d : { v: 1, items: [] }; };
let who = current() || "";
let doc = load(who); // the account copy's shape, unknown fields and all

const words = (s) => String(s || "").trim().replace(/\s+/g, " ").toLowerCase();
export const same = (a, b) => words(a) === words(b);
const newId = () => [...crypto.getRandomValues(new Uint8Array(8))].map((x) => x.toString(16).padStart(2, "0")).join("");
const next = (item) => Math.max(now(), (item.updated || 0) + 1);

/** The notes you see (not removed), newest first: the vault's when your account has one, else this browser's. */
export function items() {
  const list = vault.active() ? vault.notes().map((i) => (i.long ? { ...i, text: vault.textOf(i) } : i)) : doc.items;
  return list.filter((i) => !i.deleted && String(i.text || "").trim()).sort((a, b) => b.created - a.created);
}
export const open = () => items().filter((i) => !i.done);
export const doneItems = () => items().filter((i) => i.done);
/** These words, typed and kept, not ticked: what the filled bookmark shows. */
export const saved = (text) => items().find((i) => !i.url && !i.done && same(wordsOf(i.text), text)) || null;
/** A note's own words: what it searches for and matches by (a Next step written under them, "\n→ …", isn't). */
export const wordsOf = (text) => String(text || "").split("\n→ ")[0];

function put(item) {
  if (vault.active() && vault.write(item)) { listeners.forEach((fn) => fn()); return; }
  doc.items = doc.items.filter((i) => i.id !== item.id).concat(item);
  doc.at = Math.max(...doc.items.map((i) => i.updated || 0), now());
  write(KEY(who), doc);
  listeners.forEach((fn) => fn());
  changed();
}

/** Enter: the words kept as a note (never twice: the same words come back, unticked). Returns {item, fresh}. */
export function jot(text) {
  const t = String(text || "").trim().slice(0, 500);
  if (!t) return null;
  const have = all().find((i) => !i.url && !i.deleted && same(wordsOf(i.text), t));
  const item = have ? { ...have, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null };
  put(item);
  return { item, fresh: !have };
}
/** A page kept as a note (from Tabs, Reader's star): by its address; kept again, it comes back rather than doubling. A
 *  note written with it (Reader's Share with a note) goes on a line of its own. Returns {item, before} (before: the
 *  note as it was, or null), for Undo; null, kept nothing, when the note won't fit (NOTE_MAX). */
export const NOTE_MAX = 500;
export function keepPage(url, title, note = "") {
  const n = String(note || "").trim();
  const have = all().find((i) => i.url === url && !i.deleted);
  // (your words are never cut: a note that won't fit isn't kept, null; a Note already there keeps its text as it is)
  // (the item's own title, not yours, is shortened to leave room for your note)
  const t = have ? have.text + (n ? `\n${n}` : "") : n ? `${String(title || url).trim().slice(0, Math.max(0, NOTE_MAX - n.length - 1))}\n${n}` : String(title || url).trim().slice(0, NOTE_MAX);
  if (n && t.length > NOTE_MAX) return null;
  const item = have ? { ...have, text: t, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null, url };
  put(item);
  return { item, before: have || null };
}
/**
 * A picture (a screenshot dropped or pasted on the page) kept as a note of its own, in your notes vault: its words are
 * the file's name ("Screenshot 2026-11-20 at 10.42.13"), so it can be filtered and found on any device. Returns the
 * note, or throws with a sentence to show (notes-vault.js uploadPicture).
 */
export async function keepPicture(file, onProgress = null) {
  const pic = await vault.uploadPicture(file, onProgress);
  const name = String(file.name || "").replace(/\.[a-z0-9]{1,5}$/i, "").trim().slice(0, NOTE_MAX);
  const t = now(), item = { id: newId(), text: name || "Picture", created: t, updated: t, done: null, deleted: null, pictures: [pic] };
  if (!vault.active() || !vault.write(item)) throw new Error("Your notes vault isn't open here.");
  vault.flush(); // (at once, not after the 2 s pause typing waits for)
  listeners.forEach((fn) => fn());
  return item;
}
/** Undo of a keepPage: the note as it was before (or removed, if it wasn't there). */
export function unkeep({ item, before }) {
  const i = own(item);
  if (!i) return;
  if (before) put({ ...before, updated: next(i) }); else remove(i);
}
// ── Searches ─────────────────────────────────────────────────────────────────────────────────────────
// What you searched (Enter, or the browser's address bar), in this browser only, for 30 days: as the phone keeps them.
// A search edited within 2 minutes (it shares at least half of the shorter one's words, or one holds the other) takes
// the last one's place. Not notes: the bookmark keeps a search as a note.

const SEARCHES = (pk) => pk ? `marble.searches.${pk}` : "marble.searches";
export const searches = () => read(SEARCHES(who), []).filter((x) => x?.text && now() - x.at < MONTH);
export function searched(text) {
  const t = String(text || "").trim().slice(0, 500);
  if (!t) return;
  const list = searches(), last = list[0];
  const a = words(t), b = last ? words(last.text) : "";
  const aw = a.split(" "), bw = b.split(" ");
  const [short, long] = aw.length <= bw.length ? [aw, bw] : [bw, aw];
  const edit = last && now() - last.at <= 120 && (short.filter((x) => long.includes(x)).length * 2 >= short.length || a.includes(b) || b.includes(a));
  write(SEARCHES(who), [{ text: t, at: now() }, ...list.slice(edit ? 1 : 0).filter((x) => words(x.text) !== a)].slice(0, 200));
}

// ── Keep in view, and the older note (home-rules.md): this browser only, as the notes trial keeps them on the phone ──
const VIEW = (pk) => `marble.later.inview.${pk}`, OFFERED = (pk) => `marble.later.offered.${pk}`;
/** Notes kept in view, in order (ids of open notes). */
export const inView = () => read(VIEW(who), []).filter((id) => items().some((i) => i.id === id && !i.done));
export function keepInView(item, on) {
  const list = read(VIEW(who), []).filter((id) => id !== item.id);
  write(VIEW(who), on ? [...list, item.id] : list);
  listeners.forEach((fn) => fn());
}
/** Move a note within Keep in view (-1: up, 1: down). */
export function moveInView(item, by) {
  const list = read(VIEW(who), []), i = list.indexOf(item.id), j = i + by;
  if (i < 0 || j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  write(VIEW(who), list);
  listeners.forEach((fn) => fn());
}
// when each note was last shown or opened here (home-rules.md: the older note is one not seen in 14 days)
const SEEN_AT = (pk) => `marble.later.seen.${pk}`;
export const seenTimes = () => read(SEEN_AT(who), {});
export function sawNotes(ids) {
  if (!ids.length) return;
  const m = seenTimes(), t = now();
  for (const id of ids) m[id] = t;
  write(SEEN_AT(who), Object.fromEntries(Object.entries(m).sort((a, b) => b[1] - a[1]).slice(0, 2000)));
}
/** The older note offered last ({id, at}), so the same one stays all day. */
export const offered = () => read(OFFERED(who), null);
export const offer = (id) => { const o = offered(); if (o?.id !== id || Math.floor(o.at / 86400) !== Math.floor(now() / 86400)) write(OFFERED(who), { id, at: now() }); };
/** Next step: one step written into the note, on a new line ("→ step"). */
export function nextStep(item, step) {
  const i = all().find((x) => x.id === item.id), t = String(step || "").trim();
  if (i && t) put({ ...i, text: `${i.text}\n→ ${t}`.slice(0, 500), updated: next(i) });
}

// Changes start from the note as Notes shows it: the vault's, or this browser's copy.
const own = (item) => all().find((i) => i.id === item.id);
// the notes changes start from: the vault's when it's open, else this browser's copy
const all = () => (vault.active() ? vault.notes() : doc.items);
export const tick = (item, on) => { const i = own(item); if (i) put({ ...i, done: on ? now() : null, updated: next(i) }); };
/** Removed: only a marker stays (no text), for a month, so other devices learn it. */
export const remove = (item) => { const i = own(item); if (i) put({ ...i, text: "", deleted: now(), updated: next(i) }); };

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
  write(KEY(who), doc);
  listeners.forEach((fn) => fn());
  return JSON.stringify(merged.map((i) => [i.id, i.updated]).sort()) !== before;
}

// ── Syncing ──────────────────────────────────────────────────────

// Without a vault nothing syncs: marble/later is NIP-44 to yourself, and no sign-in here can do that (me.js nip44Self).
let noEncrypt = false;
export const syncs = () => vault.active();
/** marble/later is synced only while there's no vault (it stays as the phone left it once there is). */
const laterSyncs = () => false;
// The last try at syncing failed (not because it can't sync at all): Notes says so, quietly.
let failing = false;
export const syncFailing = () => (vault.active() ? ["offline", "error"].includes(vault.state()) : syncs() && failing);
const failed = (err) => {
  if (/unsupported/i.test(String(err?.message || err))) noEncrypt = true;
  else failing = true;
  listeners.forEach((fn) => fn());
};
let lastPull = 0, sendTimer = null, dirty = read(DIRTY(who), false), failures = 0, stopped = false, found = 0, version = 0;
function changed() {
  dirty = true;
  version++; // (a change made while a send is under way is sent next, never marked sent by it)
  write(DIRTY(who), true);
  if (!laterSyncs()) return;
  failures = 0;
  stopped = false; // (your change: worth one more try at a copy that wouldn't decrypt)
  clearTimeout(sendTimer);
  sendTimer = setTimeout(send, 10000);
}

// your relays: your own list (waited for: a send always goes there), else a few fast ones
// your write relays as your list says (account-relays.js: every one, down or not), and where to read; null: not known yet
async function where() {
  const a = await accountRelays(current()).catch(() => null);
  return a && { write: a.write, both: relays(a.write, a.read, FAST.slice(0, 2)) }; // (every write relay asked, no cap)
}

/**
 * Reads your account's copy and merges it in (at most once a minute unless forced). Returns null when nothing may be
 * sent now (sync-guard.js: a read that missed one of your write relays, or one without the newest copy this browser has
 * seen, until three complete reads in a row have missed it; or a copy that won't decrypt), else true or false.
 */
let pulling = null, pullingFor = null, pullRetry = null, pullFails = 0;
export function pull({ force = false } = {}) {
  // (a copy that won't decrypt isn't asked about again, not even on coming back to the tab, until you change something)
  if (!laterSyncs() || stopped || (!force && Date.now() - lastPull < 60000)) return Promise.resolve(stopped ? null : false);
  // (one read at a time: two asks at once would be two decrypt prompts)
  if (pulling && pullingFor === current()) return pulling;
  pullingFor = current();
  const p = pullNow().finally(() => { if (pulling === p) pulling = null; });
  return (pulling = p);
}
function retryPull() { clearTimeout(pullRetry); pullRetry = setTimeout(() => { pullRetry = null; pull({ force: true }); }, retryAfter(++pullFails) * 1000); }
async function pullNow() {
  lastPull = Date.now();
  const me = current(), w = await where();
  if (!w) { failing = true; listeners.forEach((fn) => fn()); retryPull(); return null; } // (your relay list isn't known yet)
  const answer = await req(w.both, { kinds: [30078], authors: [me], "#d": [TAG], limit: 4 }, { timeout: 6000 });
  heard(me, w.write, answer.answeredBy);
  const got = answer.filter((e) => e.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
  if (me !== who) return null; // (signed out or in as someone else meanwhile)
  if (!answer.answered) { failing = true; listeners.forEach((fn) => fn()); retryPull(); return null; } // (couldn't ask: not "nothing there")
  pullFails = 0;
  found = got?.created_at || 0;
  let theirs = null;
  if (got) {
    try { theirs = JSON.parse(await nip44Self(got.content, false)); }
    catch (err) { stopped = true; failed(err); return null; } // (never sent over; and not asked again and again: next visit)
    if (me !== who) return null;
    // seen, whatever its format: a later read that misses it then can't let this write over it
    if (got.created_at > read(COPY_AT(me), 0)) write(COPY_AT(me), got.created_at);
    // a newer format than this knows (v2 "moved", or later): read nothing from it, and never send over it
    if (theirs?.v !== 1) { stopped = true; failing = true; listeners.forEach((fn) => fn()); return null; }
    stopped = false;
  }
  const seenAt = read(COPY_AT(me), 0);
  const g = decide({ complete: completeRead(w.write, answer.answeredBy), full: fullRead(w.write, answer.answeredBy), found, seenAt, misses: missesAgainst(read(MISSED(me), null), seenAt) });
  write(MISSED(me), { n: g.misses, at: seenAt });
  if (g.do === "rebase") write(COPY_AT(me), found); // (your relays have what they have: from here on, that's the copy)
  const ahead = theirs ? merge(theirs) : false;
  if (theirs) write(SYNCED(me), true);
  if (g.do === "wait") { failing = true; listeners.forEach((fn) => fn()); return null; }
  failing = false;
  if (ahead || (dirty && !theirs)) changed();
  return !!theirs;
}

let sending = false;
/** Sends your notes to your account: read and merged first, so another device's changes are never lost. */
export async function send() {
  if (!laterSyncs() || sending) return;
  sending = true;
  const me = who;
  try {
    lastPull = 0;
    // read first: if it says not now, nothing is sent (it would go over what another device has); tried again,
    // waiting longer each time (a minute, two, up to ten), but not when the copy won't decrypt (next visit)
    if ((await pull({ force: true }).catch(() => null)) === null) {
      if (me === who && !stopped) { clearTimeout(sendTimer); sendTimer = setTimeout(send, retryAfter(++failures) * 1000); }
      return;
    }
    const v = version, w = await where();
    if (!w) throw new Error("relays not known");
    // (the account may switch at any wait: what's sent is this account's notes, as read and merged, or nothing)
    const body = JSON.stringify(doc), after = found;
    if (me !== who || me !== current()) return;
    const content = await nip44Self(body, true);
    if (me !== who || me !== current()) return;
    const ev = await sign({ kind: 30078, created_at: Math.max(Math.floor(Date.now() / 1000), after + 1), tags: [["d", TAG]], content });
    if (me !== who || me !== current() || ev.pubkey !== me) return;
    const sent = await publish(w.write, ev);
    // done only when most of your write relays took it (reader.md §6); a change since stays to be sent
    if (written(w.write, new Set(sent.ok)) && me === who) {
      write(COPY_AT(me), ev.created_at); failures = 0; failing = false; write(SYNCED(me), true);
      if (version === v) { dirty = false; write(DIRTY(me), false); } else { clearTimeout(sendTimer); sendTimer = setTimeout(send, 10000); }
      listeners.forEach((fn) => fn());
    } else throw new Error("not sent");
  } catch (err) {
    if (me !== who) return; // (finished after a switch: nothing to say about the account now here)
    failed(err);
    // tried again, waiting longer each time (a minute, doubling, up to ten)
    if (me === who && !stopped) { clearTimeout(sendTimer); sendTimer = setTimeout(send, retryAfter(++failures) * 1000); }
  } finally { sending = false; }
}

// Another account (or nobody): its own copy. The one signed out of leaves the page; if all of it is on the account
// (synced, nothing unsent), this browser's copy goes too; if not (a phone sign-in, or unsent changes), it stays, out
// of sight, for when that account signs in here again.
function switchTo(pk) {
  pk = pk || "";
  if (pk === who) return;
  const was = who;
  if (was && !pk && read(SYNCED(was), false) && !dirty) for (const k of [KEY(was), DIRTY(was)]) try { localStorage.removeItem(k); } catch {}
  who = pk;
  doc = load(pk);
  dirty = read(DIRTY(pk), false);
  noEncrypt = failing = stopped = false;
  lastPull = 0;
  clearTimeout(sendTimer);
  listeners.forEach((fn) => fn());
}

/**
 * Once the vault is open: this browser's notes the vault has never had (made here and not yet on your phone) go into
 * it, under the same entry your phone would give them (notes-vault.js). A note the vault has, in any version, deleted
 * included, is left to the vault.
 */
let adopted = "";
function adoptIntoVault() {
  // (only once everything the vault names has loaded: a batch still missing may hold these very notes, §7.4)
  if (adopted === who || vault.syncing()) return;
  adopted = who;
  for (const i of doc.items) if (!i.deleted && String(i.text || "").trim() && !vault.has(i.id)) vault.write(i);
}

/** Notes made while nobody was signed in, offered to the account now signed in (never merged in by themselves). */
export const strays = () => (who ? (read(KEY(""), null)?.items || []).filter((i) => !i.deleted && String(i.text || "").trim()) : []);
export function adoptStrays() {
  for (const i of strays()) put({ ...i, updated: next(i) });
  dropStrays();
  changed();
}
export function dropStrays() {
  try { localStorage.removeItem(KEY("")); localStorage.removeItem(DIRTY("")); } catch {}
  listeners.forEach((fn) => fn());
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden" && dirty && laterSyncs() && !stopped) { clearTimeout(sendTimer); send(); }
    if (document.visibilityState === "visible") pull();
  });
  onMe((pk) => { switchTo(pk); pull(); });
  vault.onVault(() => { if (vault.state() === "open") adoptIntoVault(); listeners.forEach((fn) => fn()); });
  // when the page starts: your account's copy in, and yours out if unsent
  setTimeout(async () => {
    if (!laterSyncs()) return;
    if (dirty) send(); else pull({ force: true }).catch(() => {}); // (send reads and merges first)
  }, 500);
}

// ── Tabs: pages opened from Marble, in this browser (30 days, at most 500) ───────────────────────────

export const tabs = () => read(TABS(who), []).filter((t) => now() - t.at < MONTH);
export function opened(url, title, from) {
  const list = [{ url, title: title || hostOf(url), at: now(), from: from || "" }, ...tabs().filter((t) => t.url !== url)].slice(0, 500);
  write(TABS(who), list);
  listeners.forEach((fn) => fn());
}
export const hostOf = (url) => { try { return new URL(url).hostname.replace(/^www\./, ""); } catch { return url; } };

// ── "Go to …": what you typed as an address, or a well-known site's name (android Sites.kt, the same list) ───
// An address is sure, and Enter opens it: a dot and an ending ("example.com/page"), a scheme ("https://…"), or a
// single name followed by a slash ("xumbrel/", "localhost:8080/admin": a machine on your own network, as Tailscale's
// MagicDNS names them; opened over http://, as such names rarely have https certificates). A bare name ("apple") is
// only a likely one: "Go to apple.com" shows, and Enter still keeps a note and searches.

const BY_NAME = new Map();
for (const d of `google.com youtube.com facebook.com instagram.com wikipedia.org amazon.com reddit.com x.com twitter.com whatsapp.com
  tiktok.com linkedin.com yahoo.com bing.com netflix.com microsoft.com apple.com live.com office.com openai.com chatgpt.com
  claude.ai anthropic.com github.com gitlab.com stackoverflow.com twitch.tv discord.com telegram.org signal.org pinterest.com
  quora.com tumblr.com medium.com substack.com wordpress.com blogger.com ebay.com etsy.com walmart.com target.com bestbuy.com
  costco.com homedepot.com lowes.com ikea.com aliexpress.com alibaba.com temu.com shein.com wayfair.com zillow.com redfin.com
  craigslist.org indeed.com glassdoor.com paypal.com venmo.com stripe.com chase.com bankofamerica.com wellsfargo.com
  capitalone.com americanexpress.com coinbase.com kraken.com binance.com strike.me cashapp.com robinhood.com fidelity.com
  vanguard.com schwab.com spotify.com soundcloud.com bandcamp.com pandora.com deezer.com tidal.com vimeo.com dailymotion.com
  rumble.com odysee.com hulu.com disneyplus.com max.com peacocktv.com paramountplus.com primevideo.com crunchyroll.com
  imdb.com rottentomatoes.com letterboxd.com goodreads.com audible.com kindle.com archive.org nytimes.com washingtonpost.com
  wsj.com bloomberg.com reuters.com apnews.com bbc.com bbc.co.uk cnn.com foxnews.com nbcnews.com cbsnews.com abcnews.go.com
  npr.org theguardian.com economist.com ft.com forbes.com businessinsider.com cnbc.com axios.com politico.com theatlantic.com
  newyorker.com vox.com theverge.com wired.com arstechnica.com techcrunch.com engadget.com gizmodo.com cnet.com zdnet.com
  macrumors.com 9to5mac.com 9to5google.com androidpolice.com xda-developers.com tomshardware.com anandtech.com pcmag.com
  ign.com gamespot.com polygon.com kotaku.com pcgamer.com steampowered.com store.steampowered.com epicgames.com gog.com
  itch.io roblox.com minecraft.net xbox.com playstation.com nintendo.com ea.com chess.com lichess.org duolingo.com khanacademy.org
  coursera.org udemy.com edx.org wolframalpha.com weather.com accuweather.com wunderground.com espn.com nfl.com nba.com mlb.com
  nhl.com fifa.com uefa.com strava.com fitbit.com allrecipes.com seriouseats.com bonappetit.com foodnetwork.com yelp.com
  tripadvisor.com booking.com airbnb.com expedia.com kayak.com hotels.com uber.com lyft.com doordash.com ubereats.com
  grubhub.com instacart.com maps.google.com openstreetmap.org mail.google.com gmail.com outlook.com proton.me protonmail.com
  tutanota.com fastmail.com icloud.com dropbox.com box.com drive.google.com docs.google.com notion.so evernote.com obsidian.md
  trello.com asana.com slack.com zoom.us teams.microsoft.com figma.com canva.com adobe.com behance.net dribbble.com
  unsplash.com pexels.com flickr.com imgur.com giphy.com deviantart.com artstation.com 500px.com vsco.co mastodon.social
  bsky.app bluesky.social threads.net lemmy.world kbin.social hackernews.com news.ycombinator.com lobste.rs slashdot.org
  producthunt.com indiehackers.com dev.to hashnode.com npmjs.com pypi.org crates.io docker.com kubernetes.io python.org
  rust-lang.org go.dev kotlinlang.org developer.android.com developer.apple.com mozilla.org firefox.com brave.com
  duckduckgo.com startpage.com kagi.com perplexity.ai gemini.google.com bard.google.com huggingface.co kaggle.com arxiv.org
  nature.com science.org nasa.gov noaa.gov cdc.gov nih.gov who.int un.org irs.gov usps.com ups.com fedex.com dhl.com
  stacker.news primal.net damus.io snort.social iris.to coracle.social nostr.band jumble.social ditto.pub njump.me nostr.com
  zap.stream habla.news yakihonne.com highlighter.com olas.app zapstore.dev bitcoin.org mempool.space blockstream.info
  lightning.network getalby.com wikihow.com fandom.com britannica.com dictionary.com merriam-webster.com thesaurus.com
  translate.google.com deepl.com speedtest.net fast.com downdetector.com pastebin.com archive.ph web.archive.org`.trim().split(/\s+/)) {
  const name = d.replace(/^www\./, "").split(".")[0];
  if (!BY_NAME.has(name)) BY_NAME.set(name, d);
}
const ADDRESS = /^(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})(?:[/:?#]\S*)?$/i;
const LOCAL = /^(?:(https?):\/\/)?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?::\d{1,5})?)(\/\S*)?$/i; // one name, no dot
const NOT_TLDS = new Set(["js", "ts", "kt", "py", "rs", "txt", "md", "json", "png", "jpg", "jpeg", "gif", "pdf", "mp4", "mp3", "exe", "apk", "zip", "html", "css"]);

/** A site to go to for what's typed: {address, url, sure}, or null (it's searched). */
export function site(typed) {
  const q = String(typed || "").trim();
  if (!q || q.includes(" ")) return null;
  const m = ADDRESS.exec(q);
  if (m) {
    const host = m[1].toLowerCase();
    if (NOT_TLDS.has(host.split(".").pop())) return null;
    return { address: host.replace(/^www\./, ""), url: /^https?:\/\//i.test(q) ? q : `https://${q}`, sure: true };
  }
  // one name with a slash after it, or a scheme in front: a machine on your network
  const l = LOCAL.exec(q);
  if (l && (l[1] || l[3])) return { address: q.replace(/^https?:\/\//i, ""), url: l[1] ? q : `http://${q}`, sure: true };
  const name = q.toLowerCase();
  if (BY_NAME.has(name)) return { address: BY_NAME.get(name), url: `https://${BY_NAME.get(name)}`, sure: false };
  if (name.length >= 3 && /^[\p{L}\p{N}-]+$/u.test(name)) {
    for (const [k, v] of BY_NAME) if (k.startsWith(name)) return { address: v, url: `https://${v}`, sure: false };
  }
  return null;
}
