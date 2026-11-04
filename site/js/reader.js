// Reader (marble-2009-spec §4; reader.md in marble-content-fixtures): new items from sources you picked, and you stop
// when you've seen them. Folders: Read first (Special Follow, 10010), Your people (your follows, a toggle), News
// (Marble's stories and the sites you add), Writers, Relays. No counts anywhere; a new item is bold and a folder with
// new ones has a dot; scrolled past means read; read items age out. A person's short posts come as one row a day.
// On your account (signed in with the extension, as Notes): your sources and folders (kind 30078 d=marble/reader) and
// what you've read (the seen list, d=discover/seen, with its upto and unread), each NIP-44 to yourself, merged with
// what's there before every write; as Marble Android keeps them (reader.md, agreed 2026-11-04).
import { req, relays, publish, FAST, INDEXERS, normalize, foundOn, testRelay } from "./pool.js";
import { keep, profilesOf, outbox, resolveNip05 } from "./data.js";
import { esc, name, postPath, content, inApp } from "./render.js";
import { decode, naddr, nevent } from "./ids.js";
import { current, method, myFollows, nip44Self, sign, myWriteRelays } from "./me.js";
import { loadStories } from "./find.js";
import * as notes from "./later.js";
import { kindOf, isMedia, digestKey, feedItemKey, eventKey, isRead, seenKeys, mergeReader, mergeSeen, discussionOf, fitSeen } from "./reader-rules.js";
import { decide, completeRead } from "./sync-guard.js";

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
    unread: Object.fromEntries(Object.entries(seen.unread).filter(([, x]) => Math.abs(x) > t - UNREAD_FOR)) };
  keys = seenKeys(seen);
  write(SEEN(who), seen);
  changed("seen");
}
// Keep unread: unread[key] = when (positive); read again after that: unread[key] = -when (so a merge can't bring the
// mark back: the newer of the two times wins, reader-rules.js mergeSeen)
function markRead(key) {
  const u = seen.unread[key];
  if (keys.has(key) && !(u > 0)) return;
  if (u > 0) seen.unread[key] = -now();
  const d = String(Math.floor(now() / DAY));
  seen.days[d] = [seen.days[d], key].filter(Boolean).join(" ");
  saveSeen();
}
function keepUnread(key) { seen.unread[key] = now(); saveSeen(); }
/**
 * Everything here read, now: a folder of people (Your people, Read first) by the folder's read-up-to time (one entry,
 * not one a person); any other by its sources'; their items' Keep unread marks cleared.
 */
function markAllRead(sources, list = [], folder = "") {
  const t = now(), these = new Set(sources);
  if (folder === "people" || folder === "first") seen.upto[`folder:${folder}`] = t;
  else for (const s of these) seen.upto[s] = t;
  for (const i of list) if (these.has(i.source) || folder && i.folder === folder) for (const k of [i.key, ...(i.keys || [])]) if (seen.unread[k] > 0) seen.unread[k] = -t;
  saveSeen();
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
let broken = new Set(), timer = null, wait = 60000;
const dirty = new Set(), version = { list: 0, seen: 0 }; // (a change made while a copy is being sent is sent next)
const onRead = new Set();
const later = (ms) => { clearTimeout(timer); timer = setTimeout(() => { timer = null; pushAccount(); }, ms); };

// your relays: your own list (waited for: a write always goes there), else a few fast ones
async function where() {
  const o = await outbox(current()).catch(() => ({ write: [], read: [] }));
  // (testing on localhost: the local relay is where writes go, so it's the one that has to answer)
  const write = testRelay() ? relays([testRelay()]) : relays(o.write.length ? o.write : FAST.slice(0, 3));
  return { write, both: relays(write, o.read, FAST.slice(0, 2)).slice(0, 10) };
}
// the newest account copy this browser has seen, by d, and how many complete reads in a row have missed it
const STATE = (pk) => `marble.reader.copies.${pk}`, MISSED = (pk) => `marble.reader.missed.${pk}`;
const copiesSeen = () => read(STATE(current()), {});
const sawCopy = (d, at) => { const c = copiesSeen(); if ((c[d] || 0) < at) write(STATE(current()), { ...c, [d]: at }); };
/** Your newest copy of each, decrypted ({event, body}; body null: it didn't decrypt), and whether every write relay answered. */
async function fetchMine(ds) {
  const me = current(), w = await where();
  const answer = await req(w.both, { kinds: [30078], authors: [me], "#d": ds, limit: 12 }, { timeout: 6000 });
  const got = answer.filter((e) => e.pubkey === me), out = {};
  for (const d of ds) {
    const e = got.filter((x) => x.tags.some((t) => t[0] === "d" && t[1] === d)).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0];
    if (!e) continue;
    let body = null;
    try { body = JSON.parse(await nip44Self(e.content, false)); } catch {}
    out[d] = { event: e, body };
    if (body) sawCopy(d, e.created_at);
  }
  return { copies: out, answered: answer.answered, complete: completeRead(w.write, answer.answeredBy), write: w.write };
}

/** The account's copies merged in here (and your old news feeds moved over once). */
export async function pullAccount() {
  if (!syncs()) return;
  const me = current();
  const r = await fetchMine([D.list, D.seen, OLD_FEEDS]);
  if (!r.answered || current() !== me) return;
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
  wait = 60000;
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
    for (const k of todo) {
      if (current() !== me) return;
      const there = r.copies[D[k]], v = version[k];
      if (there && !there.body) { broken.add(k); dirty.delete(k); continue; } // (never over a copy that didn't decrypt)
      const missed = read(MISSED(me), {});
      const g = decide({ complete: r.complete, found: there?.event.created_at || 0, seenAt: copiesSeen()[D[k]] || 0, misses: missed[k] || 0, expires: EXPIRES[k], now: now() });
      write(MISSED(me), { ...missed, [k]: g.misses });
      if (g.do === "wait") { retry = true; continue; }
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
      const ev = await sign({ kind: 30078, created_at: Math.max(t, (there?.event.created_at ?? 0) + 1), tags, content: await nip44Self(json, true) });
      const sent = await publish(r.write, ev);
      if (sent.ok.length) { sawCopy(D[k], ev.created_at); if (version[k] === v) dirty.delete(k); } else retry = true;
    }
  } catch (err) { console.warn("Reader: not synced", err); retry = true; }
  finally {
    pushing = false;
    // tried again, waiting longer each time (a minute, then two, up to ten), until it's sent or nothing's left
    if (dirty.size) { later(retry ? wait : 20000); if (retry) wait = Math.min(wait * 2, 600000); }
  }
}
if (typeof document !== "undefined") document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") pushAccount(); });

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
    const summary = clean(textOf(body));
    return { title: textOf(one(el, "title")), link: abs(link), guid, at: Number.isFinite(when) ? Math.floor(when / 1000) : now(), summary: summary.slice(0, 240), image: abs(media), discussion };
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
      out.push({ key: await feedItemKey(i.link, i.guid), source: src.id, sourceName: src.name || f.title || hostOf(src.value), kind: "news", at: i.at, title: i.title, summary: i.summary, link: i.link, image: i.image, discussion: i.discussion });
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

/** A post's own link out (the first link to another site, as the renderer drew it), for v ("the original"), or "". */
function originalOf(e) {
  if (e.kind !== 1) return "";
  const el = document.createElement("div");
  el.innerHTML = content(e, { quotes: false });
  return el.querySelector('a[target="_blank"]:not(.mention)')?.getAttribute("href") || "";
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
      out.push({ key: eventKey(e), source: `people:${e.pubkey}`, author: e.pubkey, kind: e.kind === 30023 ? "article" : "people", at: e.created_at, title, summary: e.kind === 30023 ? (e.tags.find((t) => t[0] === "summary")?.[1] || "").slice(0, 240) : "", event: e, folder, original: originalOf(e) });
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
    for (const e of got) if (kindOf(e) !== "reply") out.push({ key: eventKey(e), source: s.id, sourceName: s.name || hostOf(s.value.replace(/^wss?/, "https")), author: e.pubkey, kind: "people", at: e.created_at, title: plainText(e) || "A post", event: e, original: originalOf(e) });
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

const itemRead = (i) => i.kind === "digest" ? i.keys.every((k) => isRead({ key: k, source: i.source, at: i.at, folder: i.folder }, seen, keys)) && !(seen.unread[i.key] > 0) : isRead(i, seen, keys);
const ageOf = (i) => i.kind === "news" ? AGE.news : i.kind === "article" ? AGE.article : AGE.people;
// a star is a link to the item at marble.syb.lol (the same on every device and host; an article by its address)
const SITE = "https://marble.syb.lol";
const starUrl = (i) => i.link && !i.link.startsWith("/") ? i.link : i.link ? SITE + i.link : i.event ? SITE + pathOf(i.event) : i.posts ? SITE + postPath(i.posts[0]) : "";
const starred = (i) => !!starUrl(i) && notes.items().some((n) => n.url === starUrl(i));
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
    let upto = 0;
    for (const i of list.sort((a, b) => a.at - b.at)) { if (!i.read) break; if (t - i.at >= ageOf(i)) upto = i.at; }
    if (upto > (seen.upto[src] || 0)) { seen.upto[src] = upto; raised = true; }
  }
  if (raised) saveSeen();
  return all.filter((i) => !i.read ? t - i.at < UNREAD_FOR : t - i.at < ageOf(i) || starred(i)).sort((a, b) => (a.read - b.read) || b.at - a.at);
}
let shownKeys = new Set();


// ── The page ─────────────────────────────────────────────────────────────────────────────────────────
// Google Reader's list view by default: one line an item (☆, the source, the title in bold while unread, a grey
// snippet, a short date); a click opens it in place (read then) and again closes it. Expanded view (every item
// open, read as you scroll past) is a folder's other view. Keys: j/k next and previous (opening it), o or Enter open
// or close, s star, v the original, m read or not, shift-A all read.

const ICON = {
  star: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z"/></svg>',
  out: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
  talk: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a7 7 0 0 1-10.4 6.1L5 19.5l1.4-4.3A7 7 0 1 1 20 12z"/></svg>',
  more: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>',
  plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
};
const VIEW = (f) => `marble.reader.view.${f}`;
// a short date: the time today, else "Nov 4"
const shortDate = (t) => {
  const d = new Date(t * 1000), today = new Date();
  return d.toDateString() === today.toDateString() ? d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })
    : d.toLocaleDateString(undefined, { month: "short", day: "numeric", ...(d.getFullYear() !== today.getFullYear() ? { year: "numeric" } : {}) });
};
// where an item opens: a Nostr thing in Marble (its post view), a site's story on the site (a viewer link here)
const pathOf = (e) => e.kind >= 30000 && e.kind < 40000 ? `/${naddr({ kind: e.kind, pubkey: e.pubkey, identifier: e.tags.find((t) => t[0] === "d")?.[1] || "", relays: [] })}` : postPath(e);
// a Nostr item's own address (nevent, or naddr for an article), for Open in… and sharing
const entityOf = (i) => {
  const e = i.event || i.posts?.[0];
  if (!e) return /^\/((?:note|nevent|naddr)1\w+)$/.exec(i.link || "")?.[1] || "";
  return e.kind >= 30000 && e.kind < 40000 ? naddr({ kind: e.kind, pubkey: e.pubkey, identifier: e.tags.find((t) => t[0] === "d")?.[1] || "", relays: foundOn(e.id).slice(0, 2) })
    : nevent(e.id, { relays: foundOn(e.id).slice(0, 2), author: e.pubkey });
};
// (a Nostr link a feed gave, already a path here, or a viewer or Marble link: here; anything else: the site)
const hereOf = (url) => (/^\/(?:note|nevent|naddr|npub|nprofile)1/.test(url || "") ? url : inApp(url));
const openOf = (i) => i.event ? { href: pathOf(i.event), here: true } : i.posts ? { href: postPath(i.posts[0]), here: true }
  : hereOf(i.link) ? { href: hereOf(i.link), here: true } : { href: i.link || "", here: false };

let active = null; // the Reader on screen (its root, and what the document's click and keys do there)
if (typeof document !== "undefined") {
  document.addEventListener("click", (ev) => { if (active?.root.isConnected) active.click(ev); });
  document.addEventListener("keydown", (ev) => { if (active?.root.isConnected) active.key(ev); });
}

export function showReader(root, signal, { page, card, fillLater, openTarget }) {
  page("reader", "Reader");
  load();
  const me = current();
  let folder = new URLSearchParams(location.search).get("f") || (me ? "people" : "news"), items = new Map(), busy = new Set(), pulled = !syncs();
  root.innerHTML = `<div class="reader-page">
    <nav class="r-folders" aria-label="Reader">
      <form class="r-add"><span class="r-add-icon">${ICON.plus}</span><input type="text" placeholder="A site, a person, or a search" aria-label="Add a site, a person, or a search" autocomplete="off" spellcheck="false"></form>
      <p class="r-said" role="status" hidden></p>
      <div class="r-list"></div>
    </nav>
    <section class="r-river"><div class="r-head"><h1></h1>
      <div class="r-views" role="group" aria-label="View"><button type="button" data-view="list">List</button><button type="button" data-view="river">Expanded</button></div>
      <button type="button" class="link r-new" hidden>Show new items</button><button type="button" class="link r-all">Mark all as read</button></div><div class="r-rows"></div></section>
  </div>`;
  const $ = (s) => root.querySelector(s);
  const said = (text) => { const p = $(".r-said"); p.textContent = text; p.hidden = !text; };

  const folders = () => {
    const mine = doc.folders.filter((f) => !f.gone && !FIXED.some((x) => x.id === f.id)).sort((a, b) => a.order - b.order);
    return [...FIXED.filter((f) => f.id !== "first" || me), ...mine];
  };
  const sourcesIn = (f) => doc.sources.filter((s) => !s.gone && (s.folder || LABELS[s.type]?.[0]) === f);
  const fresh = (f) => (items.get(f) || []).some((i) => !itemRead(i) && !muted(i.source));
  // a folder's view: yours on the account for your own folders, this browser's for Read first, Your people, News
  const viewOf = (f) => doc.folders.find((x) => x.id === f && !x.gone)?.view || read(VIEW(f), "list");
  const setView = (f, v) => {
    const own = doc.folders.find((x) => x.id === f && !x.gone);
    if (own) { own.view = v; own.at = now(); saveList(); } else write(VIEW(f), v);
  };

  function drawFolders() {
    const list = folders().map((f) => {
      const toggle = f.id === "people" && me ? `<button type="button" class="r-toggle" role="switch" aria-checked="${doc.people.on}" aria-label="Your people: ${doc.people.on ? "on" : "off"}" data-people></button>` : "";
      return `<a class="r-folder${f.id === folder ? " on" : ""}" href="/reader?f=${esc(f.id)}" data-folder="${esc(f.id)}">${esc(f.name)}${fresh(f.id) ? '<span class="r-dot" aria-label="new"></span>' : ""}${toggle}</a>`;
    }).join("");
    const srcs = doc.sources.filter((s) => !s.gone).map((s) => `<div class="r-source${muted(s.id) ? " muted" : ""}" data-source="${esc(s.id)}"><span>${esc(s.name || s.value)}</span><button type="button" class="ic r-src-more" aria-label="More for ${esc(s.name || s.value)}">${ICON.more}</button></div>`).join("");
    const others = doc.muted.filter((m) => !m.gone && !doc.sources.some((x) => x.id === m.source)).map((m) => `<div class="r-source muted"><span>${esc(sourceName(m.source))}</span><button type="button" class="link" data-unmute="${esc(m.source)}">Unmute</button></div>`).join("");
    $(".r-list").innerHTML = list + (srcs || others ? `<div class="r-sep"></div>${srcs}${others}` : "");
  }

  // the line
  function row(i) {
    const source = i.author ? name(i.author) : i.sourceName;
    const title = i.kind === "digest" ? `${i.posts.length} ${i.posts.length === 1 ? "post" : "posts"}` : i.title;
    const snip = i.kind === "digest" ? i.posts.map(plainText).filter(Boolean).join(" · ") : i.kind === "people" ? "" : i.summary || "";
    const on = starred(i);
    return `<article class="r-row${i.read ? " read" : ""}${i.kind === "digest" ? " digest" : ""}" data-key="${esc(i.key)}">
      <div class="r-line">
        <button type="button" class="ic r-star${on ? " on" : ""}" aria-label="${on ? "Starred" : "Star"}" aria-pressed="${on}" data-star>${ICON.star}</button>
        <button type="button" class="r-open" data-toggle aria-expanded="false">
          <span class="r-src">${esc(source)}</span><span class="r-text"><span class="r-title">${esc(title)}</span>${snip ? `<span class="r-snip"> — ${esc(snip)}</span>` : ""}</span><time class="r-date">${esc(shortDate(i.at))}</time>
        </button>
        ${lineOpen(i)}
      </div>
      <div class="r-item" hidden></div>
    </article>`;
  }
  // Open in… on the line too (reader.md §5): a Nostr item's list, or a story's site
  const lineOpen = (i) => {
    if (entityOf(i)) return `<button type="button" class="ic r-line-open" aria-label="Open in…" data-openin>${ICON.out}</button>`;
    const o = openOf(i);
    return o.href ? `<a class="ic r-line-open" href="${esc(o.href)}"${o.here ? "" : ' target="_blank" rel="noopener noreferrer"'} aria-label="Open in…" data-open>${ICON.out}</a>` : "";
  };
  // the item, opened: the article or post (the shared renderer: references, media, quotes), and what you can do
  function opened(i) {
    const o = openOf(i), ext = o.here ? "" : ' target="_blank" rel="noopener noreferrer"';
    let body;
    if (i.kind === "news") body = `<h2 class="r-headline">${o.href ? `<a href="${esc(o.href)}"${ext}>${esc(i.title)}</a>` : esc(i.title)}</h2>
      ${i.image ? `<img class="r-pic" src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ""}
      ${i.summary ? `<p class="r-summary">${esc(i.summary)}</p>` : ""}`;
    else if (i.kind === "digest") body = i.posts.map((p) => card(p, { compact: true })).join("");
    else body = card(i.event, { compact: true });
    return `<div class="r-body">${body}</div>
      <div class="r-acts">
        ${entityOf(i) ? `<button type="button" class="ic" aria-label="Open in…" data-openin>${ICON.out}</button>`
          : o.href ? `<a class="ic" href="${esc(o.href)}"${ext} aria-label="Open in…" data-open>${ICON.out}</a>` : ""}
        ${i.discussion ? `<a class="ic" href="${esc(i.discussion)}" target="_blank" rel="noopener noreferrer" aria-label="Discussion">${ICON.talk}</a>` : ""}
        <button type="button" class="link" data-unread>Keep unread</button>
        <button type="button" class="link" data-share>Share with a note</button>
      </div>`;
  }

  let byKey = new Map(), cur = -1;
  const rowsEl = () => [...root.querySelectorAll(".r-row")];
  function drawRiver() {
    // a folder of yours this browser hasn't heard of yet (your account's list is still coming): kept, and shown when it comes
    const f = folders().find((x) => x.id === folder) || (!pulled ? { id: folder, name: "Reader" } : folders()[0]);
    folder = f.id;
    $(".r-head h1").textContent = f.name;
    for (const b of root.querySelectorAll("[data-view]")) b.setAttribute("aria-pressed", b.dataset.view === viewOf(folder));
    const list = shown(items.get(folder) || []);
    byKey = new Map(list.map((i) => [i.key, i]));
    shownKeys = new Set(list.flatMap((i) => [i.key, ...(i.keys || [])]));
    const firstRead = list.findIndex((i) => i.read);
    const html = list.map((i, n) => (n === firstRead && n > 0 ? '<div class="r-earlier">Earlier</div>' : "") + row(i)).join("");
    const empty = folder === "people" && !doc.people.on ? "Your people is off. Turn it on to see your people's posts here."
      : busy.has(folder) ? "" : folder === "first" ? "Nobody in Read first yet. Special Follow someone to see them here first."
      : !me && folder === "people" ? "Sign in to read your people." : "Nothing new here.";
    const rows = $(".r-rows");
    rows.className = `r-rows ${viewOf(folder)}`;
    rows.innerHTML = html || (busy.has(folder) ? '<p class="r-wait">Getting the newest…</p>' : `<p class="r-empty">${esc(empty)}</p>`);
    $(".r-all").hidden = !list.some((i) => !i.read);
    $(".r-new").hidden = true;
    cur = -1;
    if (viewOf(folder) === "river") { for (const el of rowsEl()) openRow(el, true, false); watch(); } else io?.disconnect();
  }

  // opening one in place (and reading it); in the list view, the one open before closes
  function openRow(el, on, mark = true) {
    const i = byKey.get(el.dataset.key), box = el.querySelector(".r-item"), b = el.querySelector("[data-toggle]");
    if (!i) return;
    if (on && viewOf(folder) === "list") for (const other of root.querySelectorAll(".r-row.open")) if (other !== el) openRow(other, false);
    if (on && !box.dataset.drawn) { box.innerHTML = opened(i); box.dataset.drawn = "1"; fillLater(box); }
    box.hidden = !on;
    el.classList.toggle("open", on);
    b.setAttribute("aria-expanded", on);
    if (on && mark) { readItem(i); el.classList.add("read"); }
  }
  function focusRow(n) {
    const all = rowsEl();
    if (!all.length) return;
    cur = Math.max(0, Math.min(all.length - 1, n));
    for (const x of all) x.classList.toggle("current", x === all[cur]);
    if (viewOf(folder) === "list") openRow(all[cur], true);
    all[cur].scrollIntoView({ block: "nearest" });
  }

  // expanded view: read once scrolled past (it was on screen for a second, then went off the top; a fling doesn't count)
  let io;
  function watch() {
    io?.disconnect();
    const timers = new Map(), looked = new Set();
    io = new IntersectionObserver((entries) => {
      for (const en of entries) {
        const key = en.target.dataset.key;
        if (en.isIntersecting) timers.set(key, setTimeout(() => looked.add(key), 1000));
        else {
          clearTimeout(timers.get(key));
          if (!en.target.isConnected || !en.rootBounds) continue; // (the page left, not scrolled past)
          if (looked.has(key) && en.boundingClientRect.bottom <= (en.rootBounds?.top ?? 0) + 1) { readItem(byKey.get(key)); en.target.classList.add("read"); io.unobserve(en.target); }
        }
      }
    }, { threshold: 0.2 });
    for (const el of root.querySelectorAll(".r-row:not(.read)")) io.observe(el);
  }
  // (left for a post and brought back by Back, this page goes on as it was: only a page no longer shown stops)
  const gone = () => signal.aborted && !root.isConnected;
  const readItem = (i) => { if (!i) return; for (const k of i.keys || [i.key]) markRead(k); if (i.kind === "digest") markRead(i.key); drawFolders(); };
  const unread = (el) => { const i = byKey.get(el.dataset.key); if (!i) return; for (const k of [i.key, ...(i.keys || [])]) keepUnread(k); el.classList.remove("read"); drawFolders(); };
  const star = (el) => {
    const i = byKey.get(el.dataset.key), b = el.querySelector("[data-star]");
    if (!i || !starUrl(i)) return;
    const have = notes.items().find((n) => n.url === starUrl(i));
    if (have) notes.remove(have); else notes.keepPage(starUrl(i), i.kind === "digest" ? `${name(i.author)} · ${i.posts.length} posts` : i.title);
    b.classList.toggle("on", !have); b.setAttribute("aria-pressed", !have); b.setAttribute("aria-label", have ? "Star" : "Starred");
  };
  // v: the original: a post's own link out when it has one (its article, say), else the item itself
  const original = (el) => {
    const i = byKey.get(el.dataset.key), o = i && (i.original ? (hereOf(i.original) ? { href: hereOf(i.original), here: true } : { href: i.original, here: false }) : openOf(i));
    if (!o?.href) return;
    readItem(i);
    if (o.here) { const a = document.createElement("a"); a.href = o.href; el.append(a); a.click(); a.remove(); } // (Marble's own page, as a link click: Back comes here)
    else open(o.href, "_blank", "noopener");
  };
  const allRead = () => { const list = items.get(folder) || [...byKey.values()]; markAllRead([...new Set(list.map((i) => i.source))], list, folder); drawRiver(); };

  async function fill(f, { quiet = false } = {}) {
    if (busy.has(f) || gone()) return;
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
      items.set(f, once(got));
    } catch { items.set(f, items.get(f) || []); }
    busy.delete(f);
    if (gone()) return;
    drawFolders();
    if (f === folder) refresh();
  }
  // new items for the folder on screen: drawn at once only when it's empty; otherwise nothing moves under you, and a
  // quiet "Show new items" brings them in
  function refresh() {
    if (!byKey.size) return drawRiver();
    const latest = shown(items.get(folder) || []), had = new Set(byKey.keys());
    $(".r-new").hidden = !latest.some((i) => !had.has(i.key));
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
    const [fid, label] = FIXED.some((x) => x.id === folder) || folder === LABELS[src.type][0] ? LABELS[src.type] : [folder, null];
    if (label) ensureFolder(fid, label);
    setSource({ id, ...src, folder: fid, gone: false });
    input.value = "";
    said(`Added ${src.name}.`);
    folder = fid;
    history.replaceState(history.state, "", `/reader?f=${encodeURIComponent(fid)}`);
    drawFolders();
    items.delete(fid);
    fill(fid);
  });

  // ── Menus: a row's (Keep unread), a source's (Mark all as read, Mute, Unsubscribe) ──
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
    menu.onclick = (ev) => { const b = ev.target.closest("[data-n]"); if (b) { entries[+b.dataset.n][1](); closeMenu(); } };
    menu.querySelector("button")?.focus();
  }
  const sourceName = (id) => id.startsWith("people:") ? name(id.slice(7)) : doc.sources.find((x) => x.id === id)?.name || doc.muted.find((m) => m.source === id)?.name || id.replace(/^\w+:/, "");
  const mute = (id, on, label) => { doc.muted = doc.muted.filter((m) => m.source !== id).concat({ source: id, at: now(), gone: !on, ...(label ? { name: label } : {}) }); saveList(); drawFolders(); drawRiver(); };
  const rowMenu = (el) => {
    const i = byKey.get(el.dataset.key);
    if (!i) return;
    const label = i.source.startsWith("people:") ? name(i.author) : i.sourceName || sourceName(i.source);
    openMenu(el.querySelector(".r-open") || el, [["Keep unread", () => unread(el)], ["Share with a note", () => share(i)], [`Mute ${label}`, () => mute(i.source, true, label)]]);
  };
  // Open in… for a Nostr item: Marble, your Nostr app (a nostr: link), or a web viewer, njump.me always last
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
        setTimeout(() => { if (document.visibilityState === "visible" && document.hasFocus()) said("No app opened it. Try njump.me, or Marble."); }, 1500);
      }],
      ["Marble", () => { const a = document.createElement("a"); a.href = `/${ent}`; el.append(a); a.click(); a.remove(); }],
      ["njump.me", () => open(`https://njump.me/${ent}`, "_blank", "noopener")],
    ];
    openMenu(anchor, rest);
    // the app your follows recommend, at the top once it's known (the list doesn't wait for it)
    const shownNow = menu, rec = e && openTarget ? await openTarget(e, ent).catch(() => null) : null;
    if (rec && /^https:/.test(rec.url) && menu === shownNow && menu) openMenu(anchor, [[rec.label.replace(/ ›$/, ""), () => open(rec.url, "_blank", "noopener")], ...rest]);
  };

  // ── Share with a note (spec §5's guards: never prefilled, the button says it posts publicly, a few seconds to undo
  // before anything is signed) ──
  function share(i) {
    if (!me) return said("Sign in to share.");
    const ent = entityOf(i), ref = ent ? `nostr:${ent}` : i.link && !i.link.startsWith("/") ? i.link : "";
    if (!ref) return;
    const box = document.createElement("dialog");
    box.className = "r-share";
    box.innerHTML = `<form method="dialog"><h2>Share with a note</h2>
      <p class="r-share-what">${esc(i.kind === "digest" ? `${name(i.author)}'s post` : i.title)}</p>
      <textarea rows="3" maxlength="600" placeholder="Add a note (optional)" aria-label="Your note"></textarea>
      <p class="muted r-share-who">This posts publicly, as you, with a link to it.</p>
      <div class="r-share-go"><button type="button" class="link" value="cancel" data-cancel>Cancel</button><button type="button" class="follow-btn" data-post>Post publicly</button></div></form>`;
    document.body.append(box);
    box.showModal();
    box.querySelector("textarea").focus();
    const close = () => { box.close(); box.remove(); };
    box.querySelector("[data-cancel]").onclick = close;
    box.addEventListener("cancel", close);
    box.querySelector("[data-post]").onclick = () => {
      const note = box.querySelector("textarea").value.trim();
      close();
      const e = i.event || i.posts?.[0];
      const tags = e ? [e.kind >= 30000 && e.kind < 40000 ? ["q", `${e.kind}:${e.pubkey}:${e.tags.find((t) => t[0] === "d")?.[1] || ""}`, foundOn(e.id)[0] || "", e.pubkey] : ["q", e.id, foundOn(e.id)[0] || "", e.pubkey], ["p", e.pubkey]] : [];
      // a few seconds to change your mind: nothing is signed until then
      let undone = false;
      const toast = document.createElement("div");
      toast.className = "r-toast";
      toast.innerHTML = `Posting… <button type="button" class="link">Undo</button>`;
      document.body.append(toast);
      toast.querySelector("button").onclick = () => { undone = true; toast.textContent = "Not posted."; setTimeout(() => toast.remove(), 2000); };
      setTimeout(async () => {
        if (undone) return;
        try {
          const ev = await sign({ kind: 1, tags, content: note ? `${note}\n\n${ref}` : ref });
          const sent = await publish(await myWriteRelays(), ev);
          toast.textContent = sent.ok.length ? "Shared." : "Couldn't reach your relays. Not shared.";
        } catch { toast.textContent = "Not shared: it wasn't signed."; }
        setTimeout(() => toast.remove(), 2500);
      }, 5000);
    };
  }

  // (the document's listeners are added once, for whichever Reader is on screen: this one, also after Back)
  active = { root, click: (ev) => { if (menu && !menu.contains(ev.target) && !ev.target.closest(".r-src-more, [data-openin]")) closeMenu(); }, key: (ev) => {
    if (ev.key === "Escape") return closeMenu();
    if (ev.metaKey || ev.ctrlKey || ev.altKey || ev.target.closest?.("input, textarea, [contenteditable]") || document.querySelector("dialog[open]")) return;
    const all = rowsEl(), el = all[cur];
    const k = ev.key;
    if (k === "j") focusRow(cur + 1);
    else if (k === "k") focusRow(cur < 0 ? 0 : cur - 1);
    else if ((k === "o" || k === "Enter") && el && !ev.target.closest("a, button:not([data-toggle])")) { ev.preventDefault(); openRow(el, !el.classList.contains("open")); }
    else if (k === "s" && el) star(el);
    else if (k === "v" && el) original(el);
    else if (k === "m" && el) { if (el.classList.contains("read")) unread(el); else { readItem(byKey.get(el.dataset.key)); el.classList.add("read"); } }
    else if (k === "A" && ev.shiftKey) allRead();
    else return;
    ev.preventDefault();
  } };

  root.addEventListener("click", (ev) => {
    const t = ev.target;
    if (t.closest("[data-people]")) { ev.preventDefault(); doc.people = { on: !doc.people.on, at: now() }; saveList(); items.delete("people"); drawFolders(); if (folder === "people") { drawRiver(); fill("people"); } return; }
    const fl = t.closest("[data-folder]");
    if (fl) { ev.preventDefault(); folder = fl.dataset.folder; history.replaceState(history.state, "", `/reader?f=${encodeURIComponent(folder)}`); drawFolders(); drawRiver(); if (!items.has(folder)) fill(folder); return; }
    const v = t.closest("[data-view]");
    if (v) { setView(folder, v.dataset.view); return drawRiver(); }
    const um = t.closest("[data-unmute]");
    if (um) return mute(um.dataset.unmute, false);
    const more = t.closest(".r-src-more");
    if (more) {
      const id = more.closest("[data-source]").dataset.source, s = doc.sources.find((x) => x.id === id);
      return openMenu(more, [
        ["Mark all as read", () => { markAllRead([id], items.get(folder) || []); drawFolders(); drawRiver(); }],
        [muted(id) ? "Unmute" : "Mute", () => { doc.muted = doc.muted.filter((m) => m.source !== id).concat({ source: id, at: now(), gone: muted(id) }); saveList(); drawFolders(); drawRiver(); }],
        ["Unsubscribe", () => { setSource({ ...s, gone: true }); drawFolders(); items.delete(s.folder || LABELS[s.type][0]); fill(folder); }],
      ]);
    }
    if (t.closest(".r-all")) return allRead();
    if (t.closest(".r-new")) { $(".r-new").hidden = true; return drawRiver(); }
    const el = t.closest(".r-row");
    if (!el || !byKey.has(el.dataset.key)) return;
    cur = rowsEl().indexOf(el);
    for (const x of rowsEl()) x.classList.toggle("current", x === el);
    if (t.closest("[data-star]")) return star(el);
    if (t.closest("[data-unread]")) return unread(el);
    if (t.closest("[data-share]")) return share(byKey.get(el.dataset.key));
    if (t.closest("[data-openin]")) return openIn(el, t.closest("[data-openin]"));
    if (t.closest("[data-toggle]")) return openRow(el, !el.classList.contains("open"));
    if (t.closest("[data-open]")) readItem(byKey.get(el.dataset.key)); // (the link itself is followed: Marble's own page, or the site)
  });
  // right-click, and a long press on a phone: the row's menu
  root.addEventListener("contextmenu", (ev) => { const el = ev.target.closest(".r-row"); if (el && ev.target.closest(".r-line")) { ev.preventDefault(); rowMenu(el); } });
  let press;
  root.addEventListener("touchstart", (ev) => { const el = ev.target.closest(".r-row"); if (el && ev.target.closest(".r-line")) press = setTimeout(() => { press = "fired"; rowMenu(el); }, 500); }, { passive: true });
  root.addEventListener("touchmove", () => { if (press !== "fired") clearTimeout(press); }, { passive: true });
  root.addEventListener("touchend", (ev) => { if (press === "fired") ev.preventDefault(); else clearTimeout(press); press = null; });

  drawFolders();
  drawRiver();
  fill(folder);
  const redraw = () => { if (gone()) return onRead.delete(redraw); if (root.isConnected) { drawFolders(); refresh(); } };
  onRead.add(redraw);
  signal.addEventListener("abort", () => pushAccount());
  // your account's list in: its sources fetched in the background (what's on screen stays; new items wait behind
  // "Show new items")
  pullAccount().finally(() => { pulled = true; }).then(() => {
    for (const f of new Set(doc.sources.filter((s) => !s.gone).map((s) => s.folder))) if (f) fill(f, { quiet: true });
    if (!byKey.size) drawRiver();
    drawFolders();
  }).catch(() => {});
  // the other folders, quietly, for their dots
  setTimeout(() => { for (const f of folders()) if (f.id !== folder) fill(f.id); }, 1500);
}
