// Reader (marble-2009-spec §4; reader.md in marble-content-fixtures): new items from sources you picked, and you stop
// when you've seen them. Folders: Read first (Special Follow, 10010), Your people (your follows, a toggle), News
// (Marble's stories and the sites you add), Writers, Relays; All items and Starred above them. No counts anywhere: an
// unread item is bold, and a folder or source is bold when something arrived since you last opened it (no dot); read
// items age out (Keep unread doesn't keep them longer). A person's short posts come as one row a day.
// On your account (signed in with the extension, as Notes): your sources and folders (kind 30078 d=marble/reader) and
// what you've read (the seen list, d=discover/seen, with its upto and unread), each NIP-44 to yourself, merged with
// what's there before every write; as Marble Android keeps them (reader.md, agreed 2026-11-04).
import { req, relays, publish, FAST, INDEXERS, normalize, foundOn, testRelay } from "./pool.js";
import { keep, profilesOf, outbox, resolveNip05 } from "./data.js";
import { esc, name, postPath, personPath, content, inApp } from "./render.js";
import { decode, naddr, nevent } from "./ids.js";
import { current, method, myFollows, nip44Self, sign, myWriteRelays, onMe } from "./me.js";
import { loadStories } from "./find.js";
import * as notes from "./later.js";
import { kindOf, isMedia, digestKey, feedItemKey, eventKey, isRead, seenKeys, mergeReader, mergeSeen, discussionOf, fitSeen, readUpTo, CLEARED_FOR } from "./reader-rules.js";
import { decide, completeRead, fullRead, written, retryAfter, missesAgainst } from "./sync-guard.js";
import { accountRelays, heard } from "./account-relays.js";

const now = () => Math.floor(Date.now() / 1000);
const DAY = 86400;
const AGE = { news: DAY, people: 2 * DAY, article: 7 * DAY }, UNREAD_FOR = 30 * DAY;
const OFFSET = -new Date().getTimezoneOffset() * 60;

// ── This browser's copy (per account) ────────────────────────────────────────────────────────────────

const LIST = (pk) => `marble.reader.${pk || ""}`, SEEN = (pk) => `marble.reader.seen.${pk || ""}`;
const read = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };
const fresh = () => ({ v: 1, sources: [], folders: [], people: { on: true, at: 0 }, muted: [] });
let who = null, doc = fresh(), seen = { v: 1, at: 0, days: {}, upto: {}, unread: {} }, keys = new Set();
function load() {
  if (who === (current() || "")) return;
  who = current() || "";
  doc = mergeReader(read(LIST(who), fresh()), fresh());
  seen = { v: 1, at: 0, days: {}, upto: {}, unread: {}, ...read(SEEN(who), {}) };
  keys = seenKeys(seen);
}
function saveList() { write(LIST(who), doc); changed("list"); }
// This browser keeps 30 days of what you've read (so nothing read turns bold while it can still show); the copy on your
// account keeps the last week, as Android's does (trimmed when it's sent: synced()).
function saveSeen() {
  const t = now(), cut = Math.floor((t - UNREAD_FOR) / DAY);
  for (const d of Object.keys(seen.days)) if (+d < cut) delete seen.days[d];
  seen = { ...seen, at: t, upto: Object.fromEntries(Object.entries(seen.upto).filter(([, x]) => x > t - UNREAD_FOR)),
    unread: Object.fromEntries(Object.entries(seen.unread).filter(([, x]) => x > 0 ? x > t - UNREAD_FOR : -x > t - CLEARED_FOR)) };
  keys = seenKeys(seen);
  write(SEEN(who), seen);
  changed("seen");
}
// Keep unread: unread[key] = when (positive); read again after that: unread[key] = -when (so a merge can't bring the
// mark back: the newer of the two times wins, reader-rules.js mergeSeen)
// opened (not only scrolled past): the mark is cleared even if this device never had it, so one that arrives later
// from another device loses (reader.md §2)
function markRead(key, opened = false) {
  const u = seen.unread[key];
  if (keys.has(key) && !(u > 0) && !opened) return;
  if (u > 0 || opened) seen.unread[key] = -now(); // (an open always writes a fresh clearing)
  const d = String(Math.floor(now() / DAY));
  if (!` ${seen.days[d] || ""} `.includes(` ${key} `)) seen.days[d] = [seen.days[d], key].filter(Boolean).join(" ");
  saveSeen();
}
function keepUnread(key) { seen.unread[key] = now(); saveSeen(); }
/**
 * Everything here read, now: a folder of people (Your people, Read first) by the folder's read-up-to time (one entry,
 * not one a person); any other by its sources'; their items' Keep unread marks cleared. `only`: these items one by one
 * instead (Starred: never a whole source read). Returns its Undo: what it changed, put back.
 */
function markAllRead(sources, list = [], folder = "", only = null) {
  const t = now(), these = new Set(sources), was = [];
  const set = (field, k, v) => { was.push([field, k, seen[field][k]]); seen[field][k] = v; };
  const d = String(Math.floor(t / DAY));
  if (only) {
    for (const i of only) for (const k of [i.key, ...(i.keys || [])]) {
      if (seen.unread[k] > 0) set("unread", k, -t);
      if (!keys.has(k)) { was.push(["day", d, k]); seen.days[d] = [seen.days[d], k].filter(Boolean).join(" "); }
    }
  } else {
    if (folder === "people" || folder === "first") set("upto", `folder:${folder}`, t);
    else for (const s of these) set("upto", s, t);
    for (const i of list) if (these.has(i.source) || folder && i.folder === folder) for (const k of [i.key, ...(i.keys || [])]) if (seen.unread[k] > 0) set("unread", k, -t);
  }
  saveSeen();
  return () => {
    for (const [field, k, v] of was.reverse()) {
      if (field === "day") seen.days[k] = String(seen.days[k] || "").split(" ").filter((x) => x && x !== v).join(" ");
      else if (v === undefined) delete seen[field][k]; else seen[field][k] = v;
    }
    saveSeen();
  };
}
const muted = (id) => doc.muted.some((m) => m.source === id && !m.gone);
function setSource(s) { doc.sources = doc.sources.filter((x) => x.id !== s.id).concat({ ...s, at: now() }); saveList(); }
function ensureFolder(id, label) {
  if (!doc.folders.some((f) => f.id === id && !f.gone)) { doc.folders.push({ id, name: label, order: doc.folders.length, view: "list", at: now() }); saveList(); }
}

// ── Your account's copy ──────────────────────────────────────────────────────────────────────────────
// Read when Reader opens; written 20 s after a change (and when you leave the page), merged with what's there first,
// and only when sync-guard.js says so: never over a copy that didn't decrypt, never from a read that missed one of
// your write relays, never under a newer copy this browser has seen (until your relays have answered without it
// three times, or it's past its expiration). The seen list on the account keeps the last week (2,000 keys, under
// 48 KB) and expires two weeks after its last write, as Android writes it.

const D = { list: "marble/reader", seen: "discover/seen" }, OLD_FEEDS = "marble/news-sources";
const EXPIRES = { list: 0, seen: 14 * DAY };
const syncs = () => !!current() && method() === "extension" && !!window.nostr?.nip44;
let broken = new Set(), timer = null, failures = 0, unreached = 0, pullTimer = null, pullFails = 0;
/** Why your account's copy isn't being written, said quietly in Reader (or ""): your relays can't be reached or read. */
export const syncTrouble = () => unreached >= 3 ? "Your relays aren't answering, so what you read here is kept in this browser for now." : broken.size ? "Your Reader copy on the account is in a format this page can't change, so changes stay here." : "";
const dirty = new Set(), version = { list: 0, seen: 0 }; // (a change made while a copy is being sent is sent next)
const onRead = new Set();
const later = (ms) => { clearTimeout(timer); timer = setTimeout(() => { timer = null; pushAccount(); }, ms); };

// your relays: your own list (waited for: a write always goes there), else a few fast ones
// your write relays as your list says (account-relays.js: every one, down or not), and where to read; null: not known yet
async function where() {
  const a = await accountRelays(current()).catch(() => null);
  return a && { write: a.write, both: relays(a.write, a.read, FAST.slice(0, 2)) }; // (every write relay asked, no cap)
}
// the newest account copy this browser has seen, by d, and how many complete reads in a row have missed it
const STATE = (pk) => `marble.reader.copies.${pk}`, MISSED = (pk) => `marble.reader.missed.${pk}`;
const copiesSeen = () => read(STATE(current()), {});
const sawCopy = (d, at) => { const c = copiesSeen(); if ((c[d] || 0) < at) write(STATE(current()), { ...c, [d]: at }); };
/** Your newest copy of each, decrypted ({event, body}; body null: it didn't decrypt), and whether every write relay answered. */
async function fetchMine(ds) {
  const me = current(), w = await where();
  if (!w) return { copies: {}, answered: 0, complete: false, full: false, write: [] };
  const answer = await req(w.both, { kinds: [30078], authors: [me], "#d": ds, limit: 12 }, { timeout: 6000 });
  heard(me, w.write, answer.answeredBy);
  const got = answer.filter((e) => e.pubkey === me), out = {};
  for (const d of ds) {
    const e = got.filter((x) => x.tags.some((t) => t[0] === "d" && t[1] === d)).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0];
    if (!e) continue;
    let body = null;
    try { body = JSON.parse(await nip44Self(e.content, false)); } catch {}
    out[d] = { event: e, body };
    if (body) sawCopy(d, e.created_at);
  }
  return { copies: out, answered: answer.answered, complete: completeRead(w.write, answer.answeredBy), full: fullRead(w.write, answer.answeredBy), write: w.write };
}

/** The account's copies merged in here (and your old news feeds moved over once). */
export async function pullAccount() {
  if (!syncs()) return;
  const me = current();
  const r = await fetchMine([D.list, D.seen, OLD_FEEDS]);
  if (current() !== me) return;
  if (!r.answered) { clearTimeout(pullTimer); pullTimer = setTimeout(() => { pullTimer = null; pullAccount(); }, retryAfter(++pullFails) * 1000); return; }
  pullFails = 0;
  load();
  const list = r.copies[D.list], old = r.copies[OLD_FEEDS], sn = r.copies[D.seen];
  broken = new Set([list && !list.body && "list", sn && !sn.body && "seen"].filter(Boolean));
  if (list?.body?.v === 1) { doc = mergeReader(doc, list.body); write(LIST(who), doc); }
  else if (!list && old?.body && r.complete) {
    // first time: your news feeds (Account › News sources on the phone) as sources in News
    for (const f of old.body.own || []) if (f.url && !f.gone && !doc.sources.some((s) => s.id === `feed:${f.url}`)) doc.sources.push({ id: `feed:${f.url}`, type: "feed", value: f.url, name: f.name || hostOf(f.url), folder: "news", at: f.at || now() });
    write(LIST(who), doc);
    if (doc.sources.length) changed("list");
  }
  if (sn?.body?.v === 1) { seen = mergeSeen(seen, sn.body); keys = seenKeys(seen); write(SEEN(who), seen); }
  onRead.forEach((fn) => fn());
}

function changed(what) {
  if (!syncs()) return;
  dirty.add(what);
  version[what]++;
  failures = 0;
  later(20000);
}
// the seen list as the account keeps it: the last week, the newest 2,000 keys, under 48 KB
function synced(s, t) {
  const cut = Math.floor((t - 7 * DAY) / DAY), all = [];
  for (const [d, ks] of Object.entries(s.days)) if (+d >= cut) for (const key of String(ks).split(" ").filter(Boolean)) all.push([+d, key]);
  const days = {};
  for (const [d, key] of all.sort((a, b) => a[0] - b[0]).slice(-2000)) days[d] = days[d] ? `${days[d]} ${key}` : key;
  return fitSeen({ ...s, at: t, days }, t);
}
let pushing = false;
/** What changed here, merged with what's there now, to your account. */
export async function pushAccount() {
  if (!syncs() || pushing || !dirty.size) return;
  pushing = true;
  const me = current();
  let retry = false;
  try {
    const todo = [...dirty];
    const r = await fetchMine(todo.map((k) => D[k]));
    if (who !== me) return; // (this browser's copy is another account's now)
    for (const k of todo) {
      if (current() !== me || who !== me) return;
      const there = r.copies[D[k]], v = version[k];
      if (there && !there.body) { broken.add(k); dirty.delete(k); continue; } // (never over a copy that didn't decrypt)
      if (there && there.body.v !== 1) { broken.add(k); dirty.delete(k); continue; } // (nor over a newer format this doesn't know)
      const missed = read(MISSED(me), {}), seenAt = copiesSeen()[D[k]] || 0;
      const g = decide({ complete: r.complete, full: r.full, found: there?.event.created_at || 0, seenAt, misses: missesAgainst(missed[k], seenAt), expires: EXPIRES[k], now: now() });
      write(MISSED(me), { ...missed, [k]: { n: g.misses, at: seenAt } });
      unreached = r.complete ? 0 : unreached + 1;
      if (g.do === "wait") { retry = true; onRead.forEach((fn) => fn()); continue; }
      if (g.do === "rebase") write(STATE(me), { ...copiesSeen(), [D[k]]: there?.event.created_at || 0 }); // (your relays have what they have)
      const t = now();
      let body;
      if (k === "list") {
        doc = there?.body?.v === 1 ? mergeReader(doc, there.body) : doc;
        // removals are kept 30 days, so other devices learn them, then dropped
        const keepGone = (l) => l.filter((x) => !x.gone || t - (x.at || 0) < 30 * DAY);
        doc = { ...doc, sources: keepGone(doc.sources), folders: keepGone(doc.folders), muted: keepGone(doc.muted) };
        write(LIST(who), doc);
        body = doc;
      } else {
        seen = there?.body?.v === 1 ? mergeSeen(seen, there.body) : seen;
        keys = seenKeys(seen);
        write(SEEN(who), seen);
        body = synced(seen, t);
      }
      if (there && JSON.stringify(there.body) === JSON.stringify(body)) { if (version[k] === v) dirty.delete(k); continue; }
      const json = JSON.stringify(body);
      // NIP-44 takes at most 65,535 bytes: the seen list is fitted to 48 KB, the Reader list never gets near
      if (new TextEncoder().encode(json).length > 60000) { console.warn("Reader: too big to sync", k); dirty.delete(k); continue; }
      const tags = [["d", D[k]], ...(EXPIRES[k] ? [["expiration", String(t + EXPIRES[k])]] : [])];
      if (current() !== me || who !== me) return; // (switched account meanwhile: nothing of one goes into the other)
      const content = await nip44Self(json, true);
      if (current() !== me || who !== me) return;
      const ev = await sign({ kind: 30078, created_at: Math.max(t, (there?.event.created_at ?? 0) + 1), tags, content });
      if (current() !== me || who !== me || ev.pubkey !== me) return;
      const sent = await publish(r.write, ev);
      // done only when most of your write relays took it; until then pending, sent again, and not "seen"
      if (written(r.write, new Set(sent.ok))) { sawCopy(D[k], ev.created_at); if (version[k] === v) dirty.delete(k); } else retry = true;
    }
  } catch (err) { console.warn("Reader: not synced", err); retry = true; }
  finally {
    pushing = false;
    // tried again, waiting longer each time (a minute, then two, up to ten), until it's sent or nothing's left
    if (dirty.size) later(retry ? retryAfter(++failures) * 1000 : 20000);
    else failures = 0;
  }
}
if (typeof document !== "undefined") document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") pushAccount(); });
// Another account (or nobody): nothing of the last one's goes on. Its unsent changes stay in this browser under its own
// name, for when it's back; the new one starts from its own copy here, and writes nothing until a read has reached it.
onMe(() => {
  clearTimeout(timer); timer = null;
  dirty.clear(); version.list = version.seen = 0; failures = 0; broken = new Set();
  who = null;
  load();
  onRead.forEach((fn) => fn());
});

// ── Sources: what each brings ────────────────────────────────────────────────────────────────────────

const FIXED = [
  { id: "first", name: "Read first" },
  { id: "people", name: "Your people" },
  { id: "news", name: "News" },
];
const LABELS = { feed: ["news", "News"], writer: ["writers", "Writers"], relay: ["relays", "Relays"] };

// a feed's items, through the Worker (browsers can't fetch most feeds), kept for the visit
const feedCache = new Map();
async function feedOf(url) {
  const hit = feedCache.get(url);
  if (hit && Date.now() - hit.at < 15 * 60000) return hit.value;
  const value = fetch(`/api/feed?url=${encodeURIComponent(url)}`).then((r) => r.json()).then((d) => d.xml ? { url: d.url, ...parseFeed(d.xml, d.url) } : null).catch(() => null);
  feedCache.set(url, { at: Date.now(), value });
  return value;
}
// (hnrss repeats its links and points in the description: not a summary)
const clean = (text) => String(text || "").replace(/(Article|Comments) URL:\s*\S+|Points:\s*\d+|# Comments:\s*\d+/gi, "").replace(/\s+/g, " ").trim();
const textOf = (html) => new DOMParser().parseFromString(`<body>${String(html || "").replace(/<\/?(p|br|div|li|h\d)\b[^>]*>/gi, " ")}`, "text/html").body.textContent.replace(/\s+/g, " ").trim();
/** RSS 2.0, RSS 1.0 or Atom: {title, items: [{title, link, guid, at, summary, image}]}. */
export function parseFeed(xml, base) {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.querySelector("parsererror")) return { title: "", items: [] };
  const one = (el, ...names) => { for (const n of names) { const x = el.getElementsByTagName(n)[0]; if (x?.textContent?.trim()) return x.textContent.trim(); } return ""; };
  // a link: an http(s) address, or a Nostr post or article (nostr:nevent1…, nostr:naddr1…) as its path here
  const abs = (u) => {
    const n = /^nostr:((?:note|nevent|naddr|npub|nprofile)1[02-9ac-hj-np-z]{6,})$/i.exec((u || "").trim());
    if (n) return decode(n[1]) ? `/${n[1].toLowerCase()}` : "";
    try { const r = new URL(u, base); return /^https?:$/.test(r.protocol) ? r.href : ""; } catch { return ""; }
  };
  const atom = !!doc.getElementsByTagName("feed")[0] && !doc.getElementsByTagName("channel")[0];
  const entries = [...doc.getElementsByTagName(atom ? "entry" : "item")].slice(0, 40);
  const items = entries.map((el) => {
    const link = (atom ? ([...el.getElementsByTagName("link")].find((l) => (l.getAttribute("rel") || "alternate") === "alternate")?.getAttribute("href") || "") : one(el, "link"))
      || (/^nostr:/i.test(one(el, "guid", "id")) ? one(el, "guid", "id") : "");
    const body = one(el, "description", "summary", "content", "content:encoded");
    const media = [...el.getElementsByTagName("media:content"), ...el.getElementsByTagName("media:thumbnail"), ...el.getElementsByTagName("enclosure")]
      .find((m) => m.getAttribute("url") && (/^image\//.test(m.getAttribute("type") || m.getAttribute("medium") || "") || isMedia(m.getAttribute("url"))))?.getAttribute("url")
      || /<img[^>]+src=["']([^"']+)/i.exec(body)?.[1] || "";
    const when = Date.parse(one(el, "pubDate", "published", "updated", "dc:date"));
    const replies = atom ? [...el.getElementsByTagName("link")].find((l) => l.getAttribute("rel") === "replies")?.getAttribute("href") || "" : "";
    const guid = one(el, "guid", "id");
    const discussion = discussionOf({ link: abs(link), comments: abs(one(el, "comments")), replies: abs(replies), guid, description: body });
    // (the whole summary: an opened item shows all of it; the line cuts it)
    const summary = clean(textOf(body));
    const by = atom ? one(el.getElementsByTagName("author")[0] || el, "name") : one(el, "dc:creator", "author");
    return { title: textOf(one(el, "title")), link: abs(link), guid, at: Number.isFinite(when) ? Math.floor(when / 1000) : now(), summary: summary.slice(0, 6000), image: abs(media), discussion, by: by.slice(0, 80) };
  }).filter((i) => i.title || i.summary);
  return { title: textOf(one(doc, "title")), items };
}

async function newsItems(sources) {
  const out = [];
  const stories = await loadStories().catch(() => []);
  for (const s of stories) {
    const src = `marble:${s.source?.id || "news"}`;
    if (!muted(src) && s.published_at > now() - 2 * DAY) out.push({ key: await feedItemKey(s.url), source: src, sourceName: s.source?.name || hostOf(s.url), kind: "news", at: s.published_at, title: s.title, summary: clean(s.summary), link: s.url, image: s.image, discussion: discussionOf({ link: s.url, comments: s.discussion || s.discussions?.[0]?.url || "" }) });
  }
  for (const src of sources.filter((x) => x.type === "feed")) {
    const f = await feedOf(src.value);
    for (const i of f?.items || []) {
      if (i.at < now() - 30 * DAY) continue;
      out.push({ key: await feedItemKey(i.link, i.guid), source: src.id, sourceName: src.name || f.title || hostOf(src.value), kind: "news", at: i.at, title: i.title, summary: i.summary, link: i.link, image: i.image, discussion: i.discussion, by: i.by });
    }
  }
  return out;
}
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return u; } };

/** A post as one line (content-rules §9): drawn by the shared renderer, then flattened: people as @names, a post or
 *  article it points to as "↪ quoted" / "↪ an article", custom emoji as their :name:, pictures and videos left out
 *  ("A picture" / "A video" when that's all there is), never a raw nostr: or blossom: reference. */
export function plainText(e) {
  // a content warning (NIP-36): its reason, never the words
  const cw = (e.tags || []).find((t) => t[0] === "content-warning");
  if (cw) return `${cw[1] || "Sensitive"}: show`;
  const el = document.createElement("div");
  el.innerHTML = content(e);
  const media = el.querySelector(".media");
  const only = media ? (media.querySelector("video") ? "A video" : "A picture") : "";
  for (const x of el.querySelectorAll(".media, .link-preview")) x.remove();
  for (const q of el.querySelectorAll(".quote-slot")) { const d = decode(q.dataset.quote); q.textContent = ` ↪ ${d?.type === "address" && d.kind === 30023 ? "an article" : "quoted"} `; }
  for (const img of el.querySelectorAll("img.emoji")) img.replaceWith(img.alt);
  // a link to a post on another viewer (njump.me/nevent1…) opens here: "a post" (a person's, their @name)
  for (const a of el.querySelectorAll("a[data-link]")) { const d = decode(a.getAttribute("href").slice(1)); a.textContent = d?.type === "profile" ? `@${name(d.pubkey)}` : "a post"; }
  // a reference written short (↪ …) by the renderer stays as it says; anything raw left is dropped
  for (const a of el.querySelectorAll("a")) if (/\b(npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,}/.test(a.textContent)) a.textContent = "a post";
  return el.textContent.replace(/\s+/g, " ").trim() || only;
}

// people's posts: articles and posts with a link or media as items; short posts as one row per person a day
async function peopleItems(pks, folder) {
  if (!pks.length) return [];
  const got = [];
  for (let i = 0; i < pks.length; i += 150) {
    const authors = pks.slice(i, i + 150);
    got.push(...await req(relays(FAST), [{ kinds: [1], authors, since: now() - 3 * DAY, limit: 400 }, { kinds: [30023], authors, since: now() - 7 * DAY, limit: 60 }], { timeout: 5000, settle: 900, onEvent: keep }));
  }
  // the people they mention too, so their lines say @names
  const named = new Set(pks.slice(0, 400));
  for (const e of got) for (const m of e.content.matchAll(/(?:nostr:)?((?:npub|nprofile)1[02-9ac-hj-np-z]{6,})/g)) { const d = decode(m[1]); if (d?.pubkey) named.add(d.pubkey); }
  await profilesOf([...named], 1800);
  const out = [], days = new Map();
  for (const e of newestOnly(got)) {
    const k = kindOf(e);
    if (k === "reply") continue;
    if (k === "item") {
      const title = e.kind === 30023 ? e.tags.find((t) => t[0] === "title")?.[1] || "Untitled" : plainText(e) || "A post";
      out.push({ key: eventKey(e), source: `people:${e.pubkey}`, author: e.pubkey, kind: e.kind === 30023 ? "article" : "people", at: e.created_at, title, summary: e.kind === 30023 ? (e.tags.find((t) => t[0] === "summary")?.[1] || "").slice(0, 240) : "", event: e, folder });
      continue;
    }
    const dk = digestKey(e.pubkey, e.created_at, OFFSET);
    if (!days.has(dk)) days.set(dk, { key: dk, source: `people:${e.pubkey}`, author: e.pubkey, kind: "digest", posts: [], folder });
    days.get(dk).posts.push(e);
  }
  for (const d of days.values()) {
    d.posts.sort((a, b) => b.created_at - a.created_at);
    d.at = d.posts[0].created_at;
    d.keys = d.posts.map(eventKey);
    out.push(d);
  }
  return out;
}

/** One of each: by id, and an article only its newest version (by kind:pubkey:d). */
function newestOnly(events) {
  const by = new Map();
  for (const e of events) { const k = eventKey(e), o = by.get(k); if (!o || e.created_at > o.created_at) by.set(k, e); }
  return [...by.values()];
}
/** One row per item: the same story from Marble's sources and yours, once (yours kept). */
function once(list) {
  const by = new Map();
  for (const i of list) { const o = by.get(i.key); if (!o || o.source.startsWith("marble:")) by.set(i.key, i); }
  return [...by.values()];
}

async function writerItems(sources) {
  const pks = sources.filter((s) => s.type === "writer" && !muted(s.id)).map((s) => s.value);
  if (!pks.length) return [];
  const got = await req(relays(FAST, INDEXERS), { kinds: [30023], authors: pks, since: now() - 30 * DAY, limit: 100 }, { timeout: 5000, settle: 900, onEvent: keep });
  await profilesOf(pks, 1500);
  return newestOnly(got).map((e) => ({ key: eventKey(e), source: `writer:${e.pubkey}`, author: e.pubkey, kind: "article", at: e.created_at, title: e.tags.find((t) => t[0] === "title")?.[1] || "Untitled", summary: (e.tags.find((t) => t[0] === "summary")?.[1] || "").slice(0, 240), event: e }));
}

async function relayItems(sources) {
  const out = [];
  for (const s of sources.filter((x) => x.type === "relay" && !muted(x.id))) {
    const got = await req([s.value], { kinds: [1], since: now() - 2 * DAY, limit: 60 }, { timeout: 5000, settle: 700, onEvent: keep });
    await profilesOf([...new Set(got.map((e) => e.pubkey))].slice(0, 100), 1200);
    for (const e of got) if (kindOf(e) !== "reply") out.push({ key: eventKey(e), source: s.id, sourceName: s.name || hostOf(s.value.replace(/^wss?/, "https")), author: e.pubkey, kind: "people", at: e.created_at, title: plainText(e) || "A post", event: e });
  }
  return out;
}

/** Special Follow's public part (kind 10010): the people you read first. */
async function firstPeople(me) {
  const own = await Promise.race([outbox(me).then((o) => o.write), new Promise((r) => setTimeout(() => r([]), 1200))]);
  const got = await req(relays(own, FAST, INDEXERS), { kinds: [10010], authors: [me] }, { timeout: 3000, settle: 700 });
  const list = got.filter((e) => e.pubkey === me).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0];
  return (list?.tags || []).filter((t) => t[0] === "p" && /^[0-9a-f]{64}$/.test(t[1])).map((t) => t[1]);
}

// ── Read, new, aged out ──────────────────────────────────────────────────────────────────────────────

const itemRead = (i) => i.kind === "kept" ? true : i.kind === "digest" ? i.keys.every((k) => isRead({ key: k, source: i.source, at: i.at, folder: i.folder }, seen, keys)) && !(seen.unread[i.key] > 0) : isRead(i, seen, keys);
const ageOf = (i) => i.kind === "news" ? AGE.news : i.kind === "article" ? AGE.article : AGE.people;
// a star is a link to the item at marble.syb.lol (the same on every device and host; an article by its address)
const SITE = "https://marble.syb.lol";
const starUrl = (i) => i.link && !i.link.startsWith("/") ? i.link : i.link ? SITE + i.link : i.event ? SITE + pathOf(i.event) : i.posts ? SITE + postPath(i.posts[0]) : "";
const unstarring = new Set(); // (un-starred, the Note going in a few seconds unless you undo)
const starred = (i) => !!starUrl(i) && !unstarring.has(starUrl(i)) && notes.items().some((n) => n.url === starUrl(i));
/** What a folder shows: unread (up to 30 days), then read ones not aged out; starred never age out. */
function shown(items) {
  const t = now();
  const all = items.filter((i) => !muted(i.source)).map((i) => ({ ...i, read: itemRead(i) }));
  // a source whose items are all read up to one that's aged out: read up to there (so they stay read on a device that
  // only has the last week of keys); never past an older one you haven't read
  let raised = false;
  const bySource = new Map();
  for (const i of all) bySource.set(i.source, [...(bySource.get(i.source) || []), i]);
  for (const [src, list] of bySource) {
    // a day's posts count by their oldest unread post; the source's standing Keep unread marks cap it too
    const at = (i) => i.kind === "digest" && !i.read ? Math.min(...i.posts.filter((p) => !isRead({ key: eventKey(p), source: i.source, at: p.created_at, folder: i.folder }, seen, keys)).map((p) => p.created_at), i.at) : i.at;
    // (a mark doesn't say whose it is: every standing one caps it, loaded or not, so none is ever passed)
    const marks = Object.values(seen.unread).filter((x) => x > 0);
    const upto = readUpTo(list.map((i) => ({ at: at(i), read: i.read, aged: t - i.at >= ageOf(i) })), marks);
    if (upto > (seen.upto[src] || 0)) { seen.upto[src] = upto; raised = true; }
  }
  if (raised) saveSeen();
  // Keep unread doesn't keep it longer: a marked item leaves on its kind's clock, as a read one does (reader.md §4)
  const kept = (i) => [i.key, ...(i.keys || [])].some((k) => seen.unread[k] > 0);
  return all.filter((i) => starred(i) || t - i.at < (i.read || kept(i) ? ageOf(i) : UNREAD_FOR)).sort((a, b) => (a.read - b.read) || b.at - a.at);
}
let shownKeys = new Set();


// ── The page ─────────────────────────────────────────────────────────────────────────────────────────
// Google Reader, the best of it (marble-reader-fidelity.md): a row is the star and text; a click opens it in place
// (read then) and again closes it; the opened item's title is the way out; everything else is a word in the bar under
// it, in Reader's order. Expanded view (every item open, read once its top has gone up past the top of the page after
// a second on screen) is a folder's other view. The nav: All items, Starred, the folders with their sources under
// them; a name is bold when something arrived since you last opened it. No counts, no dots. Keys are Reader's (? lists
// them).

const ICON = {
  star: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z"/></svg>',
  plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
};
const VIEW = (f) => `marble.reader.view.${f}`;
// this browser's own: when you last opened each folder and source (for bold), the folders folded in the nav, and what
// you starred here (so Starred still lists it once its folder no longer brings it; the star itself is the Note)
const OPENED = (pk) => `marble.reader.opened.${pk || ""}`, FOLDED = (pk) => `marble.reader.folded.${pk || ""}`, KEPT = (pk) => `marble.reader.kept.${pk || ""}`;
const VIRTUAL = [{ id: "all", name: "All items" }, { id: "starred", name: "Starred" }];
const virtual = (f) => f === "all" || f === "starred";

// dates: the time today, else "Nov 3"; more than a year ago, "Nov 3, 2025"
const clock = (d) => d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
const day = (t) => new Date(t * 1000).toLocaleDateString(undefined, { month: "short", day: "numeric", ...(now() - t > 365 * DAY ? { year: "numeric" } : {}) });
const today = (t) => new Date(t * 1000).toDateString() === new Date().toDateString();
const shortDate = (t) => today(t) ? clock(new Date(t * 1000)) : day(t);
const longDate = (t) => `${day(t)}, ${clock(new Date(t * 1000))}`;
// where an item opens: a Nostr thing in Marble (its post view), a site's story on the site (a viewer link here)
const pathOf = (e) => e.kind >= 30000 && e.kind < 40000 ? `/${naddr({ kind: e.kind, pubkey: e.pubkey, identifier: e.tags.find((t) => t[0] === "d")?.[1] || "", relays: [] })}` : postPath(e);
// a Nostr item's own address (nevent, or naddr for an article), for Open in… and sharing
const entityOf = (i) => {
  const e = i.event || i.posts?.[0];
  // a feed's Nostr link: a path here already, or a viewer's (njump.me/nevent1…) or Marble's
  if (!e) return /^\/((?:note|nevent|naddr)1\w+)$/.exec(hereOf(i.link) || "")?.[1] || "";
  return e.kind >= 30000 && e.kind < 40000 ? naddr({ kind: e.kind, pubkey: e.pubkey, identifier: e.tags.find((t) => t[0] === "d")?.[1] || "", relays: foundOn(e.id).slice(0, 2) })
    : nevent(e.id, { relays: foundOn(e.id).slice(0, 2), author: e.pubkey });
};
// (a Nostr link a feed gave, already a path here, or a viewer or Marble link: here; anything else: the site)
const hereOf = (url) => (/^\/(?:note|nevent|naddr|npub|nprofile)1/.test(url || "") ? url : inApp(url));
/** The opened item's title, the way out (and v): a story's site, an article in the reader, a post in Marble, a day's
 *  posts the person's profile. {text, href, here} */
const titleOf = (i) => {
  if (i.kind === "digest") return { text: `${name(i.author)} · ${today(i.at) ? "today" : day(i.at)}`, href: personPath(i.author), here: true };
  if (i.kind === "people") return { text: `${name(i.author)} · ${shortDate(i.at)}`, href: pathOf(i.event), here: true };
  if (i.event) return { text: i.title, href: pathOf(i.event), here: true };
  const h = hereOf(i.link);
  return { text: i.title, href: h || i.link || "", here: !!h };
};
const linkOut = (o, inner, cls = "") => o.href ? `<a${cls ? ` class="${cls}"` : ""} href="${esc(o.href)}"${o.here ? "" : ' target="_blank" rel="noopener noreferrer"'} data-way-out>${inner}</a>` : inner;
// a post, flat in the column (no card, no picture of the person): its words by the shared renderer, a content warning
// first; only its links, @names, pictures and quote cards are live
const postBody = (e) => {
  keep(e);
  const cw = (e.tags || []).find((t) => t[0] === "content-warning"), body = `<div class="text">${content(e)}</div>`;
  return cw ? `<details class="cw" data-warning="${esc(cw[1] || "")}"><summary>${esc(cw[1] || "Sensitive")}: show</summary>${body}</details>` : body;
};

let active = null; // the Reader on screen (its root, and what the document's click and keys do there)
if (typeof document !== "undefined") {
  document.addEventListener("click", (ev) => { if (active?.root.isConnected) active.click(ev); });
  document.addEventListener("keydown", (ev) => { if (active?.root.isConnected) active.key(ev); });
}

export function showReader(root, signal, { page, fillLater, openTarget }) {
  page("reader", "Reader");
  load();
  const me = current(), q = new URLSearchParams(location.search);
  let folder = q.get("f") || (me ? "people" : "news"), source = q.get("s") || null, items = new Map(), busy = new Set(), pulled = !syncs();
  root.innerHTML = `<div class="reader-page">
    <nav class="r-folders" aria-label="Reader">
      <form class="r-add"><span class="r-add-icon">${ICON.plus}</span><input type="text" placeholder="A site, a person, or a search" aria-label="Add a site, a person, or a search" autocomplete="off" spellcheck="false"></form>
      <p class="r-said" role="status" hidden></p>
      <div class="r-list"></div>
    </nav>
    <section class="r-river"><div class="r-head"><h1></h1>
      <div class="r-tools"><button type="button" class="link r-new" hidden>Show new items</button>
        <span class="r-views" role="group" aria-label="View"><button type="button" data-view="list">List</button> · <button type="button" data-view="river">Expanded</button></span>
        <button type="button" class="link r-all">Mark all as read</button><button type="button" class="link r-settings" hidden>Source settings…</button></div></div>
      <p class="r-msg" role="status" hidden></p>
      <div class="r-rows"></div></section>
    <div class="r-keys" hidden role="dialog" aria-label="Keys"></div>
  </div>`;
  const $ = (s) => root.querySelector(s);
  const said = (text) => { const p = $(".r-said"); p.textContent = text; p.hidden = !text; delete p.dataset.trouble; };

  // ── The message line: one at a time, with Undo for a few seconds ──
  let msgTimer = null;
  function say(text, undo = null, ms = 5000) {
    const p = $(".r-msg");
    clearTimeout(msgTimer);
    p.innerHTML = esc(text) + (undo ? ` · <button type="button" class="link" data-undo>Undo</button>` : "");
    p.hidden = false;
    if (undo) p.querySelector("[data-undo]").onclick = () => { clearTimeout(msgTimer); undo(); };
    msgTimer = setTimeout(() => { p.hidden = true; p.innerHTML = ""; }, ms);
  }

  const folders = () => {
    const mine = doc.folders.filter((f) => !f.gone && !FIXED.some((x) => x.id === f.id)).sort((a, b) => a.order - b.order);
    return [...FIXED.filter((f) => f.id !== "first" || me), ...mine];
  };
  const sourcesIn = (f) => doc.sources.filter((s) => !s.gone && (s.folder || LABELS[s.type]?.[0]) === f);
  // a folder's view: yours on the account for your own folders, this browser's for the rest
  const viewOf = (f) => doc.folders.find((x) => x.id === f && !x.gone)?.view || read(VIEW(f), "list");
  const setView = (f, v) => {
    const own = doc.folders.find((x) => x.id === f && !x.gone);
    if (own) { own.view = v; own.at = now(); saveList(); } else write(VIEW(f), v);
  };
  const sourceName = (id) => id.startsWith("people:") ? name(id.slice(7)) : doc.sources.find((x) => x.id === id)?.name || doc.muted.find((m) => m.source === id)?.name || id.replace(/^\w+:/, "");

  // what you starred here, for Starred once its folder doesn't bring it (Notes is still where it's kept)
  const keptHere = () => read(KEPT(who), {});
  const rememberStar = (i, on) => {
    const k = keptHere(), u = starUrl(i);
    if (on) k[u] = { title: i.kind === "digest" ? `${name(i.author)} · ${day(i.at)}` : i.title, source: i.source, sourceName: i.author ? name(i.author) : i.sourceName, at: i.at };
    else delete k[u];
    write(KEPT(who), Object.fromEntries(Object.entries(k).sort((a, b) => b[1].at - a[1].at).slice(0, 500)));
  };
  // a view's items: a folder's; All items, every folder's; Starred, the starred ones (and what you starred here that's
  // gone from its folder); a source's, its own
  function itemsOf(f) {
    const list = virtual(f) ? once(folders().flatMap((x) => items.get(x.id) || [])) : items.get(f) || [];
    return f === "starred" ? list.filter(starred) : list;
  }
  function viewList() {
    let list = shown(itemsOf(folder));
    if (folder === "starred") {
      const have = new Set(list.map(starUrl)), mine = new Set(notes.items().map((n) => n.url).filter(Boolean));
      list = list.concat(Object.entries(keptHere()).filter(([u]) => !have.has(u) && mine.has(u) && !unstarring.has(u))
        .map(([u, k]) => ({ key: `kept:${u}`, kind: "kept", source: k.source || "", sourceName: k.sourceName || hostOf(u), at: k.at, title: k.title, link: u, read: true })))
        .sort((a, b) => (a.read - b.read) || b.at - a.at);
    }
    return source ? list.filter((i) => i.source === source) : list;
  }

  // ── Bold: something arrived since you last opened it (this browser's times; a name seen for the first time isn't) ──
  const openedAt = () => read(OPENED(who), {});
  const lastOpened = (id) => { const o = openedAt(); if (!(id in o)) { o[id] = now(); write(OPENED(who), o); } return o[id]; };
  const touch = (id) => write(OPENED(who), { ...openedAt(), [id]: now() });
  const arrived = (list, id) => { const t = lastOpened(id); return list.some((i) => i.at > t && !muted(i.source) && !itemRead(i)); };
  const folded = () => new Set(read(FOLDED(who), []));

  function drawFolders() {
    const trouble = syncTrouble(), p = $(".r-said");
    if (trouble && (p.hidden || p.dataset.trouble)) { p.textContent = trouble; p.hidden = false; p.dataset.trouble = "1"; }
    else if (!trouble && p.dataset.trouble) { p.hidden = true; delete p.dataset.trouble; }
    const shut = folded(), cur = root.querySelector(".r-nav.navcur")?.dataset.nav;
    const entry = (id, label, { src = null, bold = false, more = "", tri = "" } = {}) => {
      const on = id === folder && src === source;
      const href = `/reader?f=${encodeURIComponent(id)}${src ? `&s=${encodeURIComponent(src)}` : ""}`;
      return `<div class="r-nav${src ? " r-src-nav" : ""}${on ? " on" : ""}${bold && !on ? " new" : ""}" data-nav="${esc(src ? `s:${src}` : `f:${id}`)}">${tri}<a class="r-name" href="${esc(href)}" data-folder="${esc(id)}"${src ? ` data-source="${esc(src)}"` : ""}${on ? ' aria-current="page"' : ""}>${esc(label)}${bold && !on ? '<span class="visually-hidden">, new</span>' : ""}</a>${more}</div>`;
    };
    const more = (kind, id, label) => `<button type="button" class="r-more" data-more-${kind}="${esc(id)}" aria-label="More for ${esc(label)}" aria-haspopup="menu">⋯</button>`;
    const top = VIRTUAL.map((f) => entry(f.id, f.name)).join("");
    const list = folders().map((f) => {
      const srcs = sourcesIn(f.id), closed = shut.has(f.id), got = items.get(f.id) || [];
      const tri = srcs.length ? `<button type="button" class="r-tri" data-fold="${esc(f.id)}" aria-expanded="${!closed}" aria-label="${closed ? "Show" : "Hide"} the sources in ${esc(f.name)}">${closed ? "▸" : "▾"}</button>` : '<span class="r-tri"></span>';
      const under = closed ? "" : srcs.map((s) => entry(f.id, s.name || s.value, { src: s.id, bold: arrived(got.filter((i) => i.source === s.id), `src:${s.id}`), more: more("source", s.id, s.name || s.value) })).join("");
      return `<div class="r-group">${entry(f.id, f.name, { bold: arrived(got, f.id), tri, more: more("folder", f.id, f.name) })}${under}</div>`;
    }).join("");
    const others = doc.muted.filter((m) => !m.gone && !doc.sources.some((x) => x.id === m.source)).map((m) => `<div class="r-nav r-src-nav muted"><span class="r-tri"></span><span class="r-name">${esc(sourceName(m.source))}</span><button type="button" class="link" data-unmute="${esc(m.source)}">Unmute</button></div>`).join("");
    $(".r-list").innerHTML = `${top}<div class="r-sep"></div>${list}${others ? `<div class="r-sep"></div>${others}` : ""}<button type="button" class="r-nav r-add-chip" data-add>Add</button>`;
    if (cur) root.querySelector(`.r-nav[data-nav="${CSS.escape(cur)}"]`)?.classList.add("navcur");
  }

  // ── The row: the star, then text (source · title — snippet · date) ──
  function row(i) {
    const src = i.author ? name(i.author) : i.sourceName;
    const oldest = i.posts?.[i.posts.length - 1];
    const title = i.kind === "digest" ? (today(i.at) ? "Today's posts" : `Posts from ${day(i.at)}`) : i.title;
    const snip = i.kind === "digest" ? plainText(oldest) : i.kind === "people" || i.kind === "kept" ? "" : i.summary || "";
    const on = starred(i);
    return `<article class="r-row${i.read ? " read" : ""}${i.kind === "digest" ? " digest" : ""}" data-key="${esc(i.key)}">
      <div class="r-line">
        <button type="button" class="r-star${on ? " on" : ""}" aria-label="Star" aria-pressed="${on}" data-star>${ICON.star}</button>
        <button type="button" class="r-open" data-toggle aria-expanded="false">
          <span class="r-src">${esc(src)}</span><span class="r-text"><span class="r-title">${esc(title)}</span>${snip ? `<span class="r-snip"> — ${esc(snip)}</span>` : ""}</span><time class="r-date">${esc(shortDate(i.at))}</time>
        </button>
      </div>
      <div class="r-item" hidden></div>
    </article>`;
  }
  // the item, opened: its title (the way out), a by-line, the body flat in the column, and the bar
  function opened(i, expanded) {
    const t = titleOf(i), on = starred(i);
    const src = i.kind === "kept" ? i.sourceName : i.author && i.kind !== "news" ? name(i.author) : i.sourceName;
    const by = i.by && i.by !== src ? ` · by ${esc(i.by)}` : "";
    const from = i.source ? `from <button type="button" class="link r-from" data-source-link="${esc(i.source)}">${esc(src)}</button>` : esc(src);
    const head = `<h2 class="r-headline">${expanded ? `<button type="button" class="r-star${on ? " on" : ""}" aria-label="Star" aria-pressed="${on}" data-star>${ICON.star}</button>` : ""}${linkOut(t, esc(t.text))}</h2>
      <p class="r-by">${from}${by} · ${esc(longDate(i.at))}</p>`;
    let body = "";
    if (i.kind === "news") {
      const long = (i.summary || "").length > 1500 && i.link && !hereOf(i.link);
      body = `${i.image ? `<img class="r-pic" src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ""}
        ${i.summary ? `<p class="r-summary">${esc(i.summary)}</p>` : ""}${long ? `<p><a href="${esc(i.link)}" target="_blank" rel="noopener noreferrer">Read on ${esc(hostOf(i.link))}</a></p>` : ""}`;
    } else if (i.kind === "article") body = i.summary ? `<p class="r-summary">${esc(i.summary)}</p>` : "";
    else if (i.kind === "digest") body = [...i.posts].reverse().map((p) => `<div class="r-dp"><a class="r-dpt" href="${esc(postPath(p))}">${esc(`${name(p.pubkey)} · ${shortDate(p.created_at)}`)}</a>${postBody(p)}</div>`).join("");
    else if (i.event) body = postBody(i.event);
    return `${head}<div class="r-body">${body}</div>${bar(i, on)}<div class="r-note" hidden></div>`;
  }
  // the bar, Reader's order: Star · Share with a note · Copy link · Keep unread · Open in… (Nostr) · Discussion
  function bar(i, on) {
    const kept = [i.key, ...(i.keys || [])].some((k) => seen.unread[k] > 0);
    return `<div class="r-bar">${[
      `<button type="button" class="link" data-star aria-pressed="${on}">${on ? "Starred" : "Star"}</button>`,
      i.kind === "kept" ? "" : `<button type="button" class="link" data-share aria-expanded="false">Share with a note</button>`,
      copyOf(i) ? `<button type="button" class="link" data-copy>Copy link</button>` : "",
      i.kind === "kept" ? "" : `<label class="r-chk"><input type="checkbox" data-unread${kept ? " checked" : ""}> Keep unread</label>`,
      entityOf(i) ? `<button type="button" class="link" data-openin aria-haspopup="menu">Open in…</button>` : "",
      i.discussion ? `<a href="${esc(i.discussion)}" target="_blank" rel="noopener noreferrer">Discussion</a>` : "",
    ].filter(Boolean).join('<span class="r-dot-sep" aria-hidden="true"> · </span>')}</div>`;
  }
  // Copy link: a story's own link; a Nostr item's at marble.syb.lol
  const copyOf = (i) => i.link && /^https?:/.test(i.link) && !hereOf(i.link) ? i.link : entityOf(i) ? `${SITE}/${entityOf(i)}` : i.link?.startsWith("/") ? SITE + i.link : i.link || "";

  // ── The stream ──
  // Rows never move while you read: the order, and where "Earlier" falls, are fixed when the view loads; reading, Keep
  // unread and Mark all as read change only how a row looks. New items wait behind "Show new items".
  let byKey = new Map(), cur = -1, layout = null;
  const viewId = () => `${folder}|${source || ""}`;
  const rowsEl = () => [...root.querySelectorAll(".r-row")];
  function drawRiver(reset = false) {
    // a folder of yours this browser hasn't heard of yet (your account's list is still coming): kept, and shown when it comes
    const f = [...VIRTUAL, ...folders()].find((x) => x.id === folder) || (!pulled ? { id: folder, name: "Reader" } : folders()[0]);
    folder = f.id;
    if (source && !doc.sources.some((s) => s.id === source && !s.gone) && !source.startsWith("people:") && !source.startsWith("marble:")) source = null;
    $(".r-head h1").textContent = source ? sourceName(source) : f.name;
    const v = viewOf(folder);
    for (const b of root.querySelectorAll("[data-view]")) b.setAttribute("aria-pressed", b.dataset.view === v);
    $(".r-settings").hidden = !(source || folder === "people" && me);
    let list = viewList();
    if (reset || !layout || layout.view !== viewId()) layout = { view: viewId(), keys: list.map((i) => i.key), earlier: list.find((i, n) => i.read && n > 0)?.key || null };
    else { const pos = new Map(layout.keys.map((k, n) => [k, n])); list = list.filter((i) => pos.has(i.key)).sort((a, b) => pos.get(a.key) - pos.get(b.key)); }
    byKey = new Map(list.map((i) => [i.key, i]));
    shownKeys = new Set(list.flatMap((i) => [i.key, ...(i.keys || [])]));
    const html = list.map((i) => (i.key === layout.earlier ? '<div class="r-earlier">Earlier</div>' : "") + row(i)).join("");
    const waiting = virtual(folder) ? folders().some((x) => busy.has(x.id)) : busy.has(folder);
    const empty = folder === "people" && !doc.people.on ? "Your people is off. Turn it on in Source settings… to see your people's posts here."
      : waiting ? "" : folder === "starred" ? "Nothing starred yet. Star an item to keep it here (and in Notes)."
      : folder === "first" ? "Nobody in Read first yet. Special Follow someone to see them here first."
      : !me && folder === "people" ? "Sign in to read your people." : "Nothing new here.";
    const rows = $(".r-rows");
    rows.className = `r-rows ${v}`;
    rows.innerHTML = html || (waiting ? '<p class="r-wait">Getting the newest…</p>' : `<p class="r-empty">${esc(empty)}</p>`);
    $(".r-new").hidden = true;
    cur = -1;
    if (v === "river") { for (const el of rowsEl()) openRow(el, true, false); watch(); } else stopWatch();
  }
  // how each row looks now (read, Keep unread ticked, starred), nothing moved
  function restyle() {
    for (const el of rowsEl()) {
      const i = byKey.get(el.dataset.key);
      if (!i) continue;
      const r = itemRead(i), on = starred(i);
      el.classList.toggle("read", r);
      const box = el.querySelector("[data-unread]");
      if (box) box.checked = [i.key, ...(i.keys || [])].some((k) => seen.unread[k] > 0);
      for (const s of el.querySelectorAll("[data-star]")) { s.classList.toggle("on", on); s.setAttribute("aria-pressed", on); if (s.classList.contains("link")) s.textContent = on ? "Starred" : "Star"; }
    }
  }

  // opening one in place (and reading it); in the list view, the one open before closes
  function openRow(el, on, mark = true) {
    const i = byKey.get(el.dataset.key), box = el.querySelector(".r-item"), b = el.querySelector("[data-toggle]");
    if (!i) return;
    const river = viewOf(folder) === "river";
    if (on && !river) for (const other of root.querySelectorAll(".r-row.open")) if (other !== el) openRow(other, false);
    if (on && !box.dataset.drawn) { box.innerHTML = opened(i, river); box.dataset.drawn = "1"; fillLater(box); }
    box.hidden = !on;
    el.classList.toggle("open", on);
    b?.setAttribute("aria-expanded", on);
    if (on && mark) { readItem(i); el.classList.add("read"); restyle1(el); }
  }
  const restyle1 = (el) => { const box = el.querySelector("[data-unread]"); if (box) box.checked = false; };
  const headerH = () => document.querySelector("header.top")?.getBoundingClientRect().bottom || 0;
  function focusRow(n, open = true) {
    const all = rowsEl();
    if (!all.length) return;
    cur = Math.max(0, Math.min(all.length - 1, n));
    for (const x of all) x.classList.toggle("current", x === all[cur]);
    const river = viewOf(folder) === "river";
    if (!river && open) openRow(all[cur], true);
    // (Expanded: the item's top to the top of the page, under the header; List: just into view)
    if (river) scrollTo({ top: all[cur].getBoundingClientRect().top + scrollY - headerH() - 6 });
    else all[cur].scrollIntoView({ block: "nearest" });
  }

  // Expanded view: read once its top has gone up past the top of the page and it's been on screen for a second (a
  // fling doesn't count)
  let io = null, onScroll = null;
  function watch() {
    stopWatch();
    const since = new Map();
    const check = () => {
      const top = headerH(), t = Date.now();
      for (const el of root.querySelectorAll(".r-row:not(.read)")) {
        const s = since.get(el);
        if (s && t - s >= 1000 && el.getBoundingClientRect().top < top) { readItem(byKey.get(el.dataset.key), false); el.classList.add("read"); restyle1(el); }
      }
    };
    io = new IntersectionObserver((entries) => {
      for (const en of entries) {
        if (en.isIntersecting) { if (!since.has(en.target)) { since.set(en.target, Date.now()); setTimeout(check, 1050); } }
        else since.delete(en.target);
      }
    });
    for (const el of root.querySelectorAll(".r-row:not(.read)")) io.observe(el);
    let raf = 0;
    onScroll = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; if (root.isConnected) check(); }); };
    addEventListener("scroll", onScroll, { passive: true });
  }
  function stopWatch() { io?.disconnect(); io = null; if (onScroll) removeEventListener("scroll", onScroll); onScroll = null; }

  // (left for a post and brought back by Back, this page goes on as it was: only a page no longer shown stops)
  const gone = () => signal.aborted && !root.isConnected;
  const readItem = (i, opened = true) => { if (!i || i.kind === "kept") return; for (const k of i.keys || [i.key]) markRead(k, opened); if (i.kind === "digest") markRead(i.key, opened); drawFolders(); };
  // Keep unread (the box, m, a long press): it doesn't keep it any longer than reading would (reader.md §4)
  const unread = (el) => { const i = byKey.get(el.dataset.key); if (!i || i.kind === "kept") return; for (const k of [i.key, ...(i.keys || [])]) keepUnread(k); el.classList.remove("read"); const b = el.querySelector("[data-unread]"); if (b) b.checked = true; drawFolders(); };
  const readAgain = (el) => { readItem(byKey.get(el.dataset.key)); el.classList.add("read"); restyle1(el); };
  // Star and Note are one thing: starring keeps it in Notes; un-starring takes the Note away (a few seconds to undo, as
  // it can hold words you wrote); a Note deleted un-stars it
  const star = (el) => {
    const i = byKey.get(el.dataset.key), u = i && starUrl(i);
    if (!u) return;
    const have = notes.items().find((n) => n.url === u);
    if (!have) { notes.keepPage(u, i.kind === "digest" ? `${name(i.author)} · ${day(i.at)}` : i.title); rememberStar(i, true); restyle(); drawFolders(); return; }
    unstarring.add(u);
    restyle();
    let undone = false;
    const t = setTimeout(() => { if (undone) return; unstarring.delete(u); const n = notes.items().find((x) => x.url === u); if (n) notes.remove(n); rememberStar(i, false); restyle(); }, 5000);
    say("Removed from Notes", () => { undone = true; clearTimeout(t); unstarring.delete(u); restyle(); say("Kept in Notes", null, 2500); });
  };
  // v: exactly what the title does
  const wayOut = (el) => {
    const i = byKey.get(el.dataset.key), o = i && titleOf(i);
    if (!o?.href) return;
    readItem(i);
    if (o.here) { const a = document.createElement("a"); a.href = o.href; el.append(a); a.click(); a.remove(); } // (Marble's own page, as a link click: Back comes here)
    else open(o.href, "_blank", "noopener");
  };
  // Mark all as read: one click, no question, then a few seconds of Undo; rows stay where they are
  // (from a ⋯ menu, a folder or source that isn't on screen: marked where it is, nothing opened)
  const allRead = (f = folder, src = source) => {
    let undo;
    if (f === "starred") undo = markAllRead([], [], "", shown(itemsOf(f)).filter((i) => !i.read && (!src || i.source === src)));
    else if (src) undo = markAllRead([src], itemsOf(f));
    else if (f === "all") { const all = folders().map((f) => markAllRead([...new Set((items.get(f.id) || []).map((i) => i.source))], items.get(f.id) || [], f.id)); undo = () => all.reverse().forEach((u) => u()); }
    else undo = markAllRead([...new Set(itemsOf(f).map((i) => i.source))], itemsOf(f), f);
    restyle(); drawFolders();
    say("Marked all as read", () => { undo(); restyle(); drawFolders(); });
  };

  const again = new Set(); // folders asked for again while their first fill was running (your account's list came in)
  async function fill(f, { quiet = false } = {}) {
    if (virtual(f)) { for (const x of folders()) if (!items.has(x.id)) fill(x.id); return; }
    if (busy.has(f)) { again.add(f); return; }
    if (gone()) return;
    busy.add(f);
    if (f === folder && !(quiet && byKey.size)) drawRiver();
    try {
      const srcs = sourcesIn(f);
      let got = [];
      if (f === "news") got = await newsItems(srcs);
      else if (f === "people") got = me && doc.people.on ? await peopleItems([...await myFollows()], "people") : [];
      else if (f === "first") got = me ? await peopleItems(await firstPeople(me), "first") : [];
      else if (f === "writers") got = await writerItems(srcs);
      else if (f === "relays") got = await relayItems(srcs);
      else got = [...await newsItems(srcs.filter((s) => s.type === "feed")).then((l) => l.filter((i) => !i.source.startsWith("marble:"))), ...await writerItems(srcs), ...await relayItems(srcs)];
      items.set(f, once(got).map((i) => ({ ...i, folder: i.folder || f })));
    } catch { items.set(f, items.get(f) || []); }
    busy.delete(f);
    if (gone()) return; // (a refill asked for meanwhile stays in `again`: Back brings this page back, and runs it)
    drawFolders();
    if (f === folder || virtual(folder)) refresh();
    if (again.delete(f)) fill(f, { quiet: true });
  }
  // new items for the view on screen: drawn at once only when it's empty; otherwise nothing moves under you, and a
  // quiet "Show new items" (or r) brings them in
  function refresh() {
    if (!byKey.size) return drawRiver(true);
    const had = new Set(layout?.keys || []);
    $(".r-new").hidden = !viewList().some((i) => !had.has(i.key));
  }
  function go(f, s = null) {
    folder = f; source = s;
    touch(s ? `src:${s}` : f);
    history.replaceState(history.state, "", `/reader?f=${encodeURIComponent(f)}${s ? `&s=${encodeURIComponent(s)}` : ""}`);
    drawFolders(); drawRiver(true);
    if (virtual(f) || !items.has(f)) fill(f);
    scrollTo({ top: 0 });
  }

  // ── Adding a source: a site (its feed), a person (their articles), a relay ──
  $(".r-add").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const input = $(".r-add input"), t = input.value.trim();
    if (!t) return;
    said("Looking…");
    let src = null;
    const d = decode(t.replace(/^nostr:/, ""));
    if (/^wss?:\/\//i.test(t)) { const u = normalize(t); if (u) src = { type: "relay", value: u, name: hostOf(u.replace(/^ws/, "http")) }; }
    else if (d?.type === "profile" || /^[0-9a-f]{64}$/i.test(t)) { const pk = d?.pubkey || t.toLowerCase(); await profilesOf([pk], 2000); src = { type: "writer", value: pk, name: name(pk) }; }
    else if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) { const r = await resolveNip05(t); if (r) { await profilesOf([r.pubkey], 2000); src = { type: "writer", value: r.pubkey, name: name(r.pubkey) }; } }
    else {
      const f = await fetch(`/api/feed?url=${encodeURIComponent(t)}`).then((r) => r.json()).catch(() => ({}));
      if (f.xml) { const p = parseFeed(f.xml, f.url); src = { type: "feed", value: f.url, name: p.title || hostOf(f.url) }; feedCache.set(f.url, { at: Date.now(), value: Promise.resolve({ url: f.url, ...p }) }); }
    }
    if (!src) return said("Couldn't find anything to follow there.");
    const id = `${src.type}:${src.value}`;
    const [fid, label] = FIXED.some((x) => x.id === folder) || virtual(folder) || folder === LABELS[src.type][0] ? LABELS[src.type] : [folder, null];
    if (label) ensureFolder(fid, label);
    setSource({ id, ...src, folder: fid, gone: false });
    input.value = "";
    said(`Added ${src.name}.`);
    items.delete(fid);
    go(fid);
  });

  // ── Menus: a folder's or source's ⋯ (Mark all as read, Mute, Unsubscribe; Your people on or off), a long press ──
  let menu = null;
  const closeMenu = () => { menu?.remove(); menu = null; };
  function openMenu(anchor, entries) {
    closeMenu();
    menu = document.createElement("div");
    menu.className = "r-menu";
    menu.setAttribute("role", "menu");
    menu.innerHTML = entries.map(([label], n) => `<button type="button" role="menuitem" data-n="${n}">${esc(label)}</button>`).join("");
    document.body.append(menu);
    const r = anchor.getBoundingClientRect(), w = Math.min(220, innerWidth - 32);
    Object.assign(menu.style, { top: `${scrollY + r.bottom + 4}px`, left: `${Math.max(16, Math.min(r.left, innerWidth - w - 16))}px`, width: `${w}px` });
    menu.onclick = (ev) => { const b = ev.target.closest("[data-n]"); if (b) { closeMenu(); entries[+b.dataset.n][1](); } };
    menu.querySelector("button")?.focus();
  }
  const mute = (id, on, label) => { doc.muted = doc.muted.filter((m) => m.source !== id).concat({ source: id, at: now(), gone: !on, ...(label ? { name: label } : {}) }); saveList(); if (on && source === id) go(folder); else { drawFolders(); drawRiver(true); } };
  const peopleOnOff = () => { doc.people = { on: !doc.people.on, at: now() }; saveList(); items.delete("people"); drawFolders(); if (folder === "people") { drawRiver(true); fill("people"); } };
  const folderMenu = (anchor, id) => openMenu(anchor, [
    ["Mark all as read", () => allRead(id, null)],
    ...(id === "people" && me ? [[doc.people.on ? "Turn off Your people" : "Turn on Your people", peopleOnOff]] : []),
  ]);
  const sourceMenu = (anchor, id) => {
    const s = doc.sources.find((x) => x.id === id && !x.gone), label = sourceName(id);
    openMenu(anchor, [
      ["Mark all as read", () => allRead(s ? s.folder || LABELS[s.type][0] : folder, id)],
      [muted(id) ? `Unmute ${label}` : `Mute ${label}`, () => mute(id, !muted(id), label)],
      ...(s ? [["Unsubscribe", () => { setSource({ ...s, gone: true }); items.delete(s.folder || LABELS[s.type][0]); if (source === id) go(folder); else { drawFolders(); fill(folder); } }]] : []),
    ]);
  };
  const rowMenu = (el) => {
    const i = byKey.get(el.dataset.key);
    if (!i || i.kind === "kept") return;
    const label = i.source.startsWith("people:") ? name(i.author) : i.sourceName || sourceName(i.source);
    openMenu(el.querySelector(".r-open") || el, [[el.classList.contains("read") ? "Keep unread" : "Mark as read", () => el.classList.contains("read") ? unread(el) : readAgain(el)], ["Share with a note", () => share(el)], [`Mute ${label}`, () => mute(i.source, true, label)]]);
  };
  // Open in… for a Nostr item (spec §10): the app your follows recommend for it (NIP-89), your Nostr app (a nostr:
  // link), Marble, and njump.me always last
  const openIn = async (el, anchor) => {
    const i = byKey.get(el.dataset.key), ent = i && entityOf(i), e = i && (i.event || i.posts?.[0]);
    if (!ent) return;
    readItem(i);
    const rest = [
      ["Your Nostr app", () => {
        location.href = `nostr:${ent}`;
        // nothing opened it (no app for nostr: links here): said, not silent
        setTimeout(() => { if (document.visibilityState === "visible" && document.hasFocus()) say("No app opened it. Try njump.me, or Marble."); }, 1500);
      }],
      ["Marble", () => { const a = document.createElement("a"); a.href = `/${ent}`; el.append(a); a.click(); a.remove(); }],
      ["njump.me", () => open(`https://njump.me/${ent}`, "_blank", "noopener")],
    ];
    openMenu(anchor, rest);
    // the app your follows recommend, at the top once it's known (the list doesn't wait for it)
    // (a story pointing at a Nostr item: its kind and author from its address, for the app your follows recommend)
    const d = !e && decode(ent), qq = e || (d && { kind: d.kind ?? 1, pubkey: d.pubkey || d.author || "", tags: [] });
    const shownNow = menu, rec = qq && openTarget ? await openTarget(qq, ent).catch(() => null) : null;
    if (rec && /^https:/.test(rec.url) && menu === shownNow && menu) openMenu(anchor, [[rec.label.replace(/ ›$/, ""), () => open(rec.url, "_blank", "noopener")], ...rest]);
  };

  // ── Share with a note, inline under the item: never prefilled; private unless you turn on Post publicly (off every
  // time): Keep puts it in Notes with your note (and stars it); Post publicly gives a few seconds to undo before anything
  // is signed ──
  function share(el) {
    const i = byKey.get(el.dataset.key);
    if (!i || i.kind === "kept") return;
    if (!el.classList.contains("open")) openRow(el, true);
    const box = el.querySelector(".r-note"), b = el.querySelector("[data-share]");
    if (!box.hidden) { box.querySelector("textarea").focus(); return; }
    box.innerHTML = `<textarea rows="3" maxlength="600" placeholder="Add a note (optional)" aria-label="Your note"></textarea>
      <div class="r-note-go"><label class="r-pub"><input type="checkbox" role="switch" data-pub> Post publicly</label><span class="r-sp"></span>
        <button type="button" class="link" data-cancel>Cancel</button><button type="button" class="r-btn" data-go>Keep</button></div>`;
    box.hidden = false; b?.setAttribute("aria-expanded", "true");
    const ta = box.querySelector("textarea"), pub = box.querySelector("[data-pub]"), goBtn = box.querySelector("[data-go]");
    ta.focus();
    const close = () => { box.hidden = true; box.innerHTML = ""; b?.setAttribute("aria-expanded", "false"); };
    pub.onchange = () => { goBtn.textContent = pub.checked ? "Post publicly" : "Keep"; };
    box.querySelector("[data-cancel]").onclick = close;
    ta.onkeydown = (ev) => { if (ev.key === "Escape") { ev.stopPropagation(); close(); b?.focus(); } };
    goBtn.onclick = () => {
      const note = ta.value.trim();
      if (!pub.checked) {
        const kept = notes.keepPage(starUrl(i), i.kind === "digest" ? `${name(i.author)} · ${day(i.at)}` : i.title, note);
        rememberStar(i, true);
        close(); restyle();
        return say("Kept in Notes", () => { notes.unkeep(kept); if (!kept.before) rememberStar(i, false); restyle(); });
      }
      if (!me) return say("Sign in to post publicly.");
      const ent = entityOf(i), ref = ent ? `nostr:${ent}` : i.link && !i.link.startsWith("/") ? i.link : "";
      if (!ref) return say("This one can't be posted.");
      close();
      const e = i.event || i.posts?.[0];
      const tags = e ? [e.kind >= 30000 && e.kind < 40000 ? ["q", `${e.kind}:${e.pubkey}:${e.tags.find((t) => t[0] === "d")?.[1] || ""}`, foundOn(e.id)[0] || "", e.pubkey] : ["q", e.id, foundOn(e.id)[0] || "", e.pubkey], ["p", e.pubkey]] : [];
      // a few seconds to change your mind: nothing is signed until then
      let undone = false;
      say("Posting publicly", () => { undone = true; say("Not posted.", null, 2500); }, 5000);
      setTimeout(async () => {
        if (undone) return;
        try {
          const ev = await sign({ kind: 1, tags, content: note ? `${note}\n\n${ref}` : ref });
          const sent = await publish(await myWriteRelays(), ev);
          say(sent.ok.length ? "Posted." : "Couldn't reach your relays. Not posted.", null, 3000);
        } catch { say("Not posted: it wasn't signed.", null, 3000); }
      }, 5000);
    };
  }

  // ── Keys (Reader's; ? lists them) ──
  const KEYS = [["j / k", "Next / previous item"], ["n / p", "Move down / up without opening"], ["o, Enter", "Open or close"], ["s", "Star"], ["v", "Go where the title goes"],
    ["m", "Read or unread"], ["shift-d, shift-s", "Share with a note"], ["shift-a", "Mark all as read"], ["r", "Show new items"], ["shift-n / shift-p", "Next / previous in the list on the left"],
    ["shift-o", "Open that one"], ["shift-x", "Fold or unfold a folder"], ["1 / 2", "Expanded / List"], ["u", "Hide or show the list on the left"], ["a", "Add a site, a person or a search"],
    ["/", "Search"], ["g a / g s", "All items / Starred"], ["?", "These keys"]];
  const keysBox = () => {
    const k = $(".r-keys");
    if (!k.hidden) { k.hidden = true; return; }
    k.innerHTML = `<h2>Keys</h2><dl>${KEYS.map(([a, b]) => `<dt>${esc(a)}</dt><dd>${esc(b)}</dd>`).join("")}</dl><button type="button" class="link" data-keys-close>Close</button>`;
    k.hidden = false;
    k.querySelector("[data-keys-close]").focus();
  };
  const navMove = (by) => {
    const all = [...root.querySelectorAll(".r-nav[data-nav]")];
    if (!all.length) return;
    let n = all.findIndex((x) => x.classList.contains("navcur"));
    if (n < 0) n = all.findIndex((x) => x.classList.contains("on"));
    n = Math.max(0, Math.min(all.length - 1, n + by));
    for (const x of all) x.classList.toggle("navcur", x === all[n]);
    all[n].scrollIntoView({ block: "nearest" });
  };
  const navOpen = () => { const a = root.querySelector(".r-nav.navcur [data-folder]"); if (a) go(a.dataset.folder, a.dataset.source || null); };
  const fold = (id) => { const s = folded(); s.has(id) ? s.delete(id) : s.add(id); write(FOLDED(who), [...s]); drawFolders(); };
  let g = 0;

  // (the document's listeners are added once, for whichever Reader is on screen: this one, also after Back)
  // brought back by Back (app.js route): this one is on screen again: its keys, and what came in while away
  root.addEventListener("marble:restored", () => {
    active = me_;
    if (!onRead.has(redraw)) onRead.add(redraw);
    for (const f of [...again]) { again.delete(f); fill(f, { quiet: true }); }
    drawFolders(); if (!root.querySelector(".r-row")) drawRiver(true); else refresh();
  });
  const me_ = active = { root, click: (ev) => { if (menu && !menu.contains(ev.target) && !ev.target.closest(".r-more, .r-settings, [data-openin]")) closeMenu(); }, key: (ev) => {
    if (ev.key === "Escape") { if (!$(".r-keys").hidden) $(".r-keys").hidden = true; return closeMenu(); }
    if (ev.metaKey || ev.ctrlKey || ev.altKey || ev.target.closest?.("input, textarea, select, [contenteditable]") || document.querySelector("dialog[open]")) return;
    const all = rowsEl(), el = all[cur], k = ev.key;
    if (g && Date.now() - g < 1500 && (k === "a" || k === "s")) { g = 0; ev.preventDefault(); return go(k === "a" ? "all" : "starred"); }
    g = 0;
    if (k === "j") focusRow(cur + 1);
    else if (k === "k") focusRow(cur < 0 ? 0 : cur - 1);
    else if (k === "n") focusRow(cur + 1, false);
    else if (k === "p") focusRow(cur < 0 ? 0 : cur - 1, false);
    else if ((k === "o" || k === "Enter") && el && !ev.target.closest("a, button:not([data-toggle])")) openRow(el, !el.classList.contains("open"));
    else if (k === "s" && el) star(el);
    else if (k === "v" && el) wayOut(el);
    else if (k === "m" && el) { if (el.classList.contains("read")) unread(el); else readAgain(el); }
    else if ((k === "D" || k === "S") && el) share(el);
    else if (k === "A") allRead();
    else if (k === "r") { if (!$(".r-new").hidden) drawRiver(true); }
    else if (k === "N") navMove(1);
    else if (k === "P") navMove(-1);
    else if (k === "O") navOpen();
    else if (k === "X") { const n = root.querySelector(".r-nav.navcur") || root.querySelector(".r-nav.on"); const f = n?.querySelector("[data-folder]")?.dataset.folder; if (f && !virtual(f)) fold(f); }
    else if (k === "1" || k === "2") { setView(folder, k === "1" ? "river" : "list"); drawRiver(); }
    else if (k === "u") root.querySelector(".reader-page").classList.toggle("no-nav");
    else if (k === "a") $(".r-add input").focus();
    else if (k === "g") g = Date.now();
    else if (k === "?") keysBox();
    else return;
    ev.preventDefault();
  } };

  root.addEventListener("change", (ev) => {
    const box = ev.target.closest("[data-unread]");
    if (!box) return;
    const el = box.closest(".r-row");
    if (box.checked) unread(el); else readAgain(el);
  });
  root.addEventListener("click", (ev) => {
    const t = ev.target;
    if (t.closest("[data-keys-close]")) return keysBox();
    if (t.closest("[data-add]")) { root.querySelector(".reader-page").classList.add("adding"); return $(".r-add input").focus(); }
    const fo = t.closest("[data-fold]");
    if (fo) return fold(fo.dataset.fold);
    const mf = t.closest("[data-more-folder]");
    if (mf) return folderMenu(mf, mf.dataset.moreFolder);
    const ms = t.closest("[data-more-source]");
    if (ms) return sourceMenu(ms, ms.dataset.moreSource);
    const fl = t.closest("[data-folder]");
    if (fl) { ev.preventDefault(); return go(fl.dataset.folder, fl.dataset.source || null); }
    const v = t.closest("[data-view]");
    if (v) { setView(folder, v.dataset.view); return drawRiver(); }
    const um = t.closest("[data-unmute]");
    if (um) return mute(um.dataset.unmute, false);
    if (t.closest(".r-settings")) return source ? sourceMenu(t.closest(".r-settings"), source) : folderMenu(t.closest(".r-settings"), folder);
    if (t.closest(".r-all")) return allRead();
    if (t.closest(".r-new")) return drawRiver(true);
    const sl = t.closest("[data-source-link]");
    if (sl) return go(folder, sl.dataset.sourceLink);
    const el = t.closest(".r-row");
    if (!el || !byKey.has(el.dataset.key)) return;
    cur = rowsEl().indexOf(el);
    for (const x of rowsEl()) x.classList.toggle("current", x === el);
    if (t.closest("[data-star]")) return star(el);
    if (t.closest("[data-share]")) return share(el);
    if (t.closest("[data-copy]")) { const u = copyOf(byKey.get(el.dataset.key)); return navigator.clipboard?.writeText(u).then(() => say("Link copied.", null, 2500), () => say(u, null, 8000)); }
    if (t.closest("[data-openin]")) return openIn(el, t.closest("[data-openin]"));
    if (t.closest("[data-toggle]")) return openRow(el, !el.classList.contains("open"));
    if (t.closest("[data-way-out]")) readItem(byKey.get(el.dataset.key)); // (the link itself is followed: Marble's own page, or the site)
  });
  // a long press on a phone: the row's menu (a desktop right-click is the browser's own)
  let press;
  root.addEventListener("touchstart", (ev) => { const el = ev.target.closest(".r-row"); if (el && ev.target.closest(".r-line")) press = setTimeout(() => { press = "fired"; rowMenu(el); }, 500); }, { passive: true });
  root.addEventListener("touchmove", () => { if (press !== "fired") clearTimeout(press); }, { passive: true });
  root.addEventListener("touchend", (ev) => { if (press === "fired") ev.preventDefault(); else clearTimeout(press); press = null; });
  root.addEventListener("contextmenu", (ev) => { if (press === "fired" || press) ev.preventDefault(); });

  touch(source ? `src:${source}` : folder);
  drawFolders();
  drawRiver(true);
  fill(folder);
  // (while this page is away it waits; Back brings it back, marble:restored above)
  function redraw() { if (root.isConnected) { drawFolders(); refresh(); } }
  onRead.add(redraw);
  signal.addEventListener("abort", () => { pushAccount(); });
  // your account's list in: its sources fetched in the background (what's on screen stays; new items wait behind
  // "Show new items")
  pullAccount().finally(() => { pulled = true; }).then(() => {
    for (const f of new Set(doc.sources.filter((s) => !s.gone).map((s) => s.folder))) if (f) fill(f, { quiet: true });
    if (!byKey.size) drawRiver(true);
    drawFolders();
  }).catch(() => {});
  // the other folders, quietly, for their bold
  setTimeout(() => { for (const f of folders()) if (f.id !== folder) fill(f.id); }, 1500);
}
