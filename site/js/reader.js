// Reader (marble-2009-spec §4; reader.md in marble-content-fixtures): new items from sources you picked, and you stop
// when you've seen them. Folders: Read first (Special Follow, 10010), Your people (your follows, a toggle), News
// (Marble's stories and the sites you add), Writers, Relays. No counts anywhere; a new item is bold and a folder with
// new ones has a dot; scrolled past means read; read items age out. A person's short posts come as one row a day.
// On your account (signed in with the extension, as Notes): your sources and folders (kind 30078 d=marble/reader) and
// what you've read (the seen list, d=discover/seen, with its upto and unread), each NIP-44 to yourself, merged with
// what's there before every write; as Marble Android keeps them (reader.md, agreed 2026-11-04).
import { req, relays, publish, FAST, INDEXERS, normalize } from "./pool.js";
import { keep, profilesOf, outbox, resolveNip05 } from "./data.js";
import { esc, name, avatar, ago, postPath } from "./render.js";
import { decode } from "./ids.js";
import { current, method, myFollows, nip44Self, sign } from "./me.js";
import { loadStories } from "./find.js";
import * as notes from "./later.js";
import { kindOf, isMedia, digestKey, feedItemKey, postKey, isRead, seenKeys, mergeReader, mergeSeen } from "./reader-rules.js";

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
function saveSeen() {
  // the last week of keys, as the seen list keeps them; unread marks for 30 days
  const cut = Math.floor((now() - 7 * DAY) / DAY);
  for (const d of Object.keys(seen.days)) if (+d < cut) delete seen.days[d];
  for (const [k, t] of Object.entries(seen.unread)) if (t < now() - UNREAD_FOR) delete seen.unread[k];
  seen.at = now();
  write(SEEN(who), seen);
  changed("seen");
}
function markRead(key) {
  if (keys.has(key) && seen.unread[key] == null) return;
  delete seen.unread[key];
  const d = String(Math.floor(now() / DAY));
  seen.days[d] = [seen.days[d], key].filter(Boolean).join(" ");
  keys.add(key);
  saveSeen();
}
function keepUnread(key) { seen.unread[key] = now(); saveSeen(); }
function markAllRead(sources) { for (const s of sources) seen.upto[s] = now(); seen.unread = Object.fromEntries(Object.entries(seen.unread).filter(([k]) => !shownKeys.has(k))); saveSeen(); }
const muted = (id) => doc.muted.some((m) => m.source === id && !m.gone);
function setSource(s) { doc.sources = doc.sources.filter((x) => x.id !== s.id).concat({ ...s, at: now() }); saveList(); }
function ensureFolder(id, label) {
  if (!doc.folders.some((f) => f.id === id && !f.gone)) { doc.folders.push({ id, name: label, order: doc.folders.length, view: "river", at: now() }); saveList(); }
}

// ── Your account's copy ──────────────────────────────────────────────────────────────────────────────
// Read when Reader opens; written 20 s after a change (and when you leave the page), from what's there merged with
// this browser's: never over a copy that didn't decrypt. The seen list keeps the last week (2,000 keys at most) and
// expires on relays two weeks after its last write, as Android writes it.

const D = { list: "marble/reader", seen: "discover/seen" }, OLD_FEEDS = "marble/news-sources";
const syncs = () => !!current() && method() === "extension" && !!window.nostr?.nip44;
let account = { list: undefined, seen: undefined }, broken = new Set(), timer = null, dirty = new Set();
const onRead = new Set();

async function where() {
  const o = await Promise.race([outbox(current()), new Promise((r) => setTimeout(() => r({ write: [], read: [] }), 2000))]);
  return { write: o.write.length ? o.write : FAST.slice(0, 3), both: relays(o.write, o.read, FAST.slice(0, 2)).slice(0, 8) };
}
/** Your newest copy of each, decrypted: {event, body} (body null: it didn't decrypt). */
async function fetchMine(ds) {
  const me = current();
  const got = (await req((await where()).both, { kinds: [30078], authors: [me], "#d": ds, limit: 12 }, { timeout: 5000, settle: 1000 })).filter((e) => e.pubkey === me);
  const out = {};
  for (const d of ds) {
    const e = got.filter((x) => x.tags.some((t) => t[0] === "d" && t[1] === d)).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0];
    if (!e) continue;
    let body = null;
    try { body = JSON.parse(await nip44Self(e.content, false)); } catch {}
    out[d] = { event: e, body };
  }
  return out;
}

/** The account's copies merged in here (and your old news feeds moved over once). */
export async function pullAccount() {
  if (!syncs()) return;
  const me = current();
  const got = await fetchMine([D.list, D.seen, OLD_FEEDS]);
  if (current() !== me) return;
  load();
  const list = got[D.list], old = got[OLD_FEEDS], sn = got[D.seen];
  broken = new Set([list && !list.body && "list", sn && !sn.body && "seen"].filter(Boolean));
  if (list?.body?.v === 1) { account.list = list; doc = mergeReader(doc, list.body); write(LIST(who), doc); }
  else if (!list && old?.body) {
    // first time: your news feeds (Account › News sources on the phone) as sources in News
    for (const f of old.body.own || []) if (f.url && !f.gone && !doc.sources.some((s) => s.id === `feed:${f.url}`)) doc.sources.push({ id: `feed:${f.url}`, type: "feed", value: f.url, name: f.name || hostOf(f.url), folder: "news", at: f.at || now() });
    write(LIST(who), doc);
    if (doc.sources.length) changed("list");
  }
  if (sn?.body?.v === 1) { account.seen = sn; seen = mergeSeen(seen, sn.body); keys = seenKeys(seen); write(SEEN(who), seen); }
  onRead.forEach((fn) => fn());
}

function changed(what) {
  if (!syncs()) return;
  dirty.add(what);
  clearTimeout(timer);
  timer = setTimeout(pushAccount, 20000);
}
let pushing = false;
/** What changed here, merged with what's there now, to your account. */
export async function pushAccount() {
  if (!syncs() || pushing || !dirty.size) return;
  pushing = true;
  const me = current();
  try {
    const todo = [...dirty];
    dirty.clear();
    const got = await fetchMine(todo.map((k) => D[k]));
    for (const k of todo) {
      const there = got[D[k]];
      if (there && !there.body) { broken.add(k); continue; } // (never over a copy that didn't decrypt)
      if (current() !== me) return;
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
        // the last week, the newest 2,000 keys
        const cut = Math.floor((t - 7 * DAY) / DAY), all = [];
        for (const [d, ks] of Object.entries(seen.days)) if (+d >= cut) for (const key of String(ks).split(" ").filter(Boolean)) all.push([+d, key]);
        const kept = all.sort((a, b) => a[0] - b[0]).slice(-2000), days = {};
        for (const [d, key] of kept) days[d] = days[d] ? `${days[d]} ${key}` : key;
        seen = { ...seen, at: t, days };
        keys = seenKeys(seen);
        write(SEEN(who), seen);
        body = seen;
      }
      const same = there && JSON.stringify(there.body) === JSON.stringify(body);
      if (same) continue;
      const tags = [["d", D[k]], ...(k === "seen" ? [["expiration", String(t + 14 * DAY)]] : [])];
      const ev = await sign({ kind: 30078, created_at: Math.max(t, (there?.event.created_at ?? 0) + 1), tags, content: await nip44Self(JSON.stringify(body), true) });
      const sent = await publish((await where()).write, ev);
      if (!sent.ok.length) dirty.add(k);
    }
  } catch { } finally { pushing = false; }
}
if (typeof document !== "undefined") document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") { clearTimeout(timer); pushAccount(); } });

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
const textOf = (html) => new DOMParser().parseFromString(`<body>${html || ""}`, "text/html").body.textContent.replace(/\s+/g, " ").trim();
/** RSS 2.0, RSS 1.0 or Atom: {title, items: [{title, link, guid, at, summary, image}]}. */
export function parseFeed(xml, base) {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.querySelector("parsererror")) return { title: "", items: [] };
  const one = (el, ...names) => { for (const n of names) { const x = el.getElementsByTagName(n)[0]; if (x?.textContent?.trim()) return x.textContent.trim(); } return ""; };
  const abs = (u) => { try { const r = new URL(u, base); return /^https?:$/.test(r.protocol) ? r.href : ""; } catch { return ""; } };
  const atom = !!doc.getElementsByTagName("feed")[0] && !doc.getElementsByTagName("channel")[0];
  const entries = [...doc.getElementsByTagName(atom ? "entry" : "item")].slice(0, 40);
  const items = entries.map((el) => {
    const link = atom ? ([...el.getElementsByTagName("link")].find((l) => (l.getAttribute("rel") || "alternate") === "alternate")?.getAttribute("href") || "") : one(el, "link");
    const body = one(el, "description", "summary", "content", "content:encoded");
    const media = [...el.getElementsByTagName("media:content"), ...el.getElementsByTagName("media:thumbnail"), ...el.getElementsByTagName("enclosure")]
      .find((m) => m.getAttribute("url") && (/^image\//.test(m.getAttribute("type") || m.getAttribute("medium") || "") || isMedia(m.getAttribute("url"))))?.getAttribute("url")
      || /<img[^>]+src=["']([^"']+)/i.exec(body)?.[1] || "";
    const when = Date.parse(one(el, "pubDate", "published", "updated", "dc:date"));
    return { title: textOf(one(el, "title")), link: abs(link), guid: one(el, "guid", "id"), at: Number.isFinite(when) ? Math.floor(when / 1000) : now(), summary: textOf(body).slice(0, 240), image: abs(media) };
  }).filter((i) => i.title || i.summary);
  return { title: textOf(one(doc, "title")), items };
}

async function newsItems(sources) {
  const out = [];
  const stories = await loadStories().catch(() => []);
  for (const s of stories) {
    const src = `marble:${s.source?.id || "news"}`;
    if (!muted(src) && s.published_at > now() - 2 * DAY) out.push({ key: await feedItemKey(s.url), source: src, sourceName: s.source?.name || hostOf(s.url), kind: "news", at: s.published_at, title: s.title, summary: s.summary, link: s.url, image: s.image });
  }
  for (const src of sources.filter((x) => x.type === "feed")) {
    const f = await feedOf(src.value);
    for (const i of f?.items || []) {
      if (i.at < now() - 30 * DAY) continue;
      out.push({ key: await feedItemKey(i.link, i.guid), source: src.id, sourceName: src.name || f.title || hostOf(src.value), kind: "news", at: i.at, title: i.title, summary: i.summary, link: i.link, image: i.image });
    }
  }
  return out;
}
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return u; } };

// people's posts: articles and posts with a link or media as items; short posts as one row per person a day
async function peopleItems(pks, folder) {
  if (!pks.length) return [];
  const got = [];
  for (let i = 0; i < pks.length; i += 150) {
    const authors = pks.slice(i, i + 150);
    got.push(...await req(relays(FAST), [{ kinds: [1], authors, since: now() - 3 * DAY, limit: 400 }, { kinds: [30023], authors, since: now() - 7 * DAY, limit: 60 }], { timeout: 5000, settle: 900, onEvent: keep }));
  }
  await profilesOf(pks.slice(0, 400), 1500);
  const out = [], days = new Map();
  for (const e of new Map(got.map((e) => [e.id, e])).values()) {
    const k = kindOf(e);
    if (k === "reply") continue;
    if (k === "item") {
      const title = e.kind === 30023 ? e.tags.find((t) => t[0] === "title")?.[1] || "Untitled" : e.content.replace(/https?:\/\/\S+/g, "").trim().slice(0, 200) || hostOf((e.content.match(/https?:\/\/\S+/) || [""])[0]);
      const link = e.kind === 1 ? (e.content.match(/https?:\/\/[^\s<>"']+/g) || []).find((u) => !isMedia(u)) : "";
      out.push({ key: postKey(e.id), source: `people:${e.pubkey}`, author: e.pubkey, kind: e.kind === 30023 ? "article" : "people", at: e.created_at, title, summary: e.kind === 30023 ? (e.tags.find((t) => t[0] === "summary")?.[1] || "").slice(0, 240) : link ? hostOf(link) : "", link, event: e, folder });
      continue;
    }
    const dk = digestKey(e.pubkey, e.created_at, OFFSET);
    if (!days.has(dk)) days.set(dk, { key: dk, source: `people:${e.pubkey}`, author: e.pubkey, kind: "digest", posts: [], folder });
    days.get(dk).posts.push(e);
  }
  for (const d of days.values()) {
    d.posts.sort((a, b) => b.created_at - a.created_at);
    d.at = d.posts[0].created_at;
    d.keys = d.posts.map((p) => postKey(p.id));
    out.push(d);
  }
  return out;
}

async function writerItems(sources) {
  const pks = sources.filter((s) => s.type === "writer" && !muted(s.id)).map((s) => s.value);
  if (!pks.length) return [];
  const got = await req(relays(FAST, INDEXERS), { kinds: [30023], authors: pks, since: now() - 30 * DAY, limit: 100 }, { timeout: 5000, settle: 900, onEvent: keep });
  await profilesOf(pks, 1500);
  return got.map((e) => ({ key: postKey(e.id), source: `writer:${e.pubkey}`, author: e.pubkey, kind: "article", at: e.created_at, title: e.tags.find((t) => t[0] === "title")?.[1] || "Untitled", summary: (e.tags.find((t) => t[0] === "summary")?.[1] || "").slice(0, 240), event: e }));
}

async function relayItems(sources) {
  const out = [];
  for (const s of sources.filter((x) => x.type === "relay" && !muted(x.id))) {
    const got = await req([s.value], { kinds: [1], since: now() - 2 * DAY, limit: 60 }, { timeout: 5000, settle: 700, onEvent: keep });
    await profilesOf([...new Set(got.map((e) => e.pubkey))].slice(0, 100), 1200);
    for (const e of got) if (kindOf(e) !== "reply") out.push({ key: postKey(e.id), source: s.id, sourceName: s.name || hostOf(s.value.replace(/^wss?/, "https")), author: e.pubkey, kind: "people", at: e.created_at, title: e.content.slice(0, 200), event: e });
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

const itemRead = (i) => i.kind === "digest" ? i.keys.every((k) => isRead({ key: k, source: i.source, at: i.at }, seen, keys)) && seen.unread[i.key] == null : isRead(i, seen, keys);
const ageOf = (i) => i.kind === "news" ? AGE.news : i.kind === "article" ? AGE.article : AGE.people;
const starred = (i) => !!i.link && notes.items().some((n) => n.url === i.link) || !!i.event && notes.items().some((n) => n.url === location.origin + postPath(i.event));
/** What a folder shows: unread (up to 30 days), then read ones not aged out; starred never age out. */
function shown(items) {
  const t = now();
  return items.filter((i) => !muted(i.source)).map((i) => ({ ...i, read: itemRead(i) }))
    .filter((i) => i.read ? t - i.at < ageOf(i) || starred(i) : t - i.at < UNREAD_FOR)
    .sort((a, b) => (a.read - b.read) || b.at - a.at);
}
let shownKeys = new Set();

// ── The page ─────────────────────────────────────────────────────────────────────────────────────────

const ICON = {
  star: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z"/></svg>',
  out: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
  more: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>',
  plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
};

export function showReader(root, signal, { page }) {
  page("reader", "Reader");
  load();
  const me = current();
  let folder = new URLSearchParams(location.search).get("f") || (me ? "people" : "news"), items = new Map(), busy = new Set();
  root.innerHTML = `<div class="reader-page">
    <nav class="r-folders" aria-label="Reader">
      <form class="r-add"><span class="r-add-icon">${ICON.plus}</span><input type="text" placeholder="A site, a person, or a search" aria-label="Add a site, a person, or a search" autocomplete="off" spellcheck="false"></form>
      <p class="r-said" role="status" hidden></p>
      <div class="r-list"></div>
    </nav>
    <section class="r-river" aria-live="polite"><div class="r-head"><h1></h1><button type="button" class="link r-all">Mark all as read</button></div><div class="r-rows"></div></section>
  </div>`;
  const $ = (s) => root.querySelector(s);
  const said = (text) => { const p = $(".r-said"); p.textContent = text; p.hidden = !text; };

  const folders = () => {
    const mine = doc.folders.filter((f) => !f.gone && !FIXED.some((x) => x.id === f.id)).sort((a, b) => a.order - b.order);
    return [...FIXED.filter((f) => f.id !== "first" || me), ...mine];
  };
  const sourcesIn = (f) => doc.sources.filter((s) => !s.gone && (s.folder || LABELS[s.type]?.[0]) === f);
  const fresh = (f) => (items.get(f) || []).some((i) => !itemRead(i) && !muted(i.source));

  function drawFolders() {
    const list = folders().map((f) => {
      const toggle = f.id === "people" && me ? `<button type="button" class="r-toggle" role="switch" aria-checked="${doc.people.on}" aria-label="Your people: ${doc.people.on ? "on" : "off"}" data-people></button>` : "";
      return `<a class="r-folder${f.id === folder ? " on" : ""}" href="/reader?f=${esc(f.id)}" data-folder="${esc(f.id)}">${esc(f.name)}${fresh(f.id) ? '<span class="r-dot" aria-label="new"></span>' : ""}${toggle}</a>`;
    }).join("");
    const srcs = doc.sources.filter((s) => !s.gone).map((s) => `<div class="r-source${muted(s.id) ? " muted" : ""}" data-source="${esc(s.id)}"><span>${esc(s.name || s.value)}</span><button type="button" class="ic r-src-more" aria-label="More for ${esc(s.name || s.value)}">${ICON.more}</button></div>`).join("");
    $(".r-list").innerHTML = list + (srcs ? `<div class="r-sep"></div>${srcs}` : "");
  }

  function row(i) {
    const who = i.author ? name(i.author) : i.sourceName;
    const meta = [i.kind === "digest" ? "" : who, i.kind === "digest" ? dayWords(i.at) : ago(i.at), i.kind === "article" ? "article" : i.link && i.kind === "people" ? "link" : ""].filter(Boolean).join(" · ");
    const open = i.link || (i.event ? postPath(i.event) : "#");
    const ext = i.link ? ' target="_blank" rel="noopener noreferrer"' : "";
    const title = i.kind === "digest" ? `${esc(name(i.author))} · ${i.posts.length} ${i.posts.length === 1 ? "post" : "posts"}` : esc(i.title);
    const snip = i.kind === "digest" ? i.posts.map((p) => esc(p.content.slice(0, 80))).join(" · ") : esc(i.summary || "");
    const body = `<div class="r-body"><div class="r-meta">${esc(meta)}</div><div class="r-title">${title}</div>${snip ? `<div class="r-snip">${snip}</div>` : ""}</div>`;
    return `<article class="r-row${i.read ? " read" : ""}${i.kind === "digest" ? " digest" : ""}" data-key="${esc(i.key)}" tabindex="-1">
      ${i.kind === "digest" ? `<button type="button" class="r-open" data-expand>${body}</button>` : `<a class="r-open" href="${esc(open)}"${ext} data-read>${body}</a>`}
      ${i.image ? `<img class="r-thumb" src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ""}
      <div class="r-acts">
        <button type="button" class="ic r-star${starred(i) ? " on" : ""}" aria-label="${starred(i) ? "Starred" : "Star"}" aria-pressed="${starred(i)}" data-star>${ICON.star}</button>
        <a class="ic" href="${esc(open)}"${ext} aria-label="Open in…" data-read>${ICON.out}</a>
        <button type="button" class="ic r-row-more" aria-label="More" data-menu>${ICON.more}</button>
      </div>
      ${i.kind === "digest" ? `<div class="r-posts" hidden>${i.posts.map((p) => `<a class="r-post" href="${esc(postPath(p))}">${avatar(p.pubkey, 24)}<span>${esc(p.content.slice(0, 280))}</span><time>${esc(ago(p.created_at))}</time></a>`).join("")}</div>` : ""}
    </article>`;
  }
  const dayWords = (t) => { const d = Math.floor((now() + OFFSET) / DAY) - Math.floor((t + OFFSET) / DAY); return d === 0 ? "Today" : d === 1 ? "Yesterday" : new Date(t * 1000).toLocaleDateString(undefined, { weekday: "long" }); };

  let byKey = new Map();
  function drawRiver() {
    const f = folders().find((x) => x.id === folder) || folders()[0];
    folder = f.id;
    $(".r-head h1").textContent = f.name;
    const list = shown(items.get(folder) || []);
    byKey = new Map(list.map((i) => [i.key, i]));
    shownKeys = new Set(list.flatMap((i) => [i.key, ...(i.keys || [])]));
    const firstRead = list.findIndex((i) => i.read);
    const html = list.map((i, n) => (n === firstRead && n > 0 ? '<div class="r-earlier">Earlier</div>' : "") + row(i)).join("");
    const empty = folder === "people" && !doc.people.on ? "Your people is off. Turn it on to see your people's posts here."
      : busy.has(folder) ? "" : folder === "first" ? "Nobody in Read first yet. Special Follow someone to see them here first."
      : !me && folder === "people" ? "Sign in to read your people." : "Nothing new here.";
    $(".r-rows").innerHTML = html || (busy.has(folder) ? '<p class="r-wait">Getting the newest…</p>' : `<p class="r-empty">${esc(empty)}</p>`);
    $(".r-all").hidden = !list.some((i) => !i.read);
    watch();
  }

  // read as you scroll past: on screen for a second (a fling doesn't count)
  let io;
  function watch() {
    io?.disconnect();
    const timers = new Map();
    io = new IntersectionObserver((entries) => {
      for (const en of entries) {
        const key = en.target.dataset.key;
        if (en.isIntersecting) timers.set(key, setTimeout(() => { readItem(byKey.get(key)); en.target.classList.add("read"); }, 1000));
        else clearTimeout(timers.get(key));
      }
    }, { threshold: 0.6 });
    for (const el of root.querySelectorAll(".r-row:not(.read)")) io.observe(el);
  }
  signal.addEventListener("abort", () => io?.disconnect());
  const readItem = (i) => { if (!i) return; for (const k of i.keys || [i.key]) markRead(k); if (i.kind === "digest") markRead(i.key); drawFolders(); };

  async function fill(f) {
    if (busy.has(f) || signal.aborted) return;
    busy.add(f);
    if (f === folder) drawRiver();
    try {
      const srcs = sourcesIn(f);
      let got = [];
      if (f === "news") got = await newsItems(srcs);
      else if (f === "people") got = me && doc.people.on ? await peopleItems([...await myFollows()], "people") : [];
      else if (f === "first") got = me ? await peopleItems(await firstPeople(me), "first") : [];
      else if (f === "writers") got = await writerItems(srcs);
      else if (f === "relays") got = await relayItems(srcs);
      else got = [...await newsItems(srcs.filter((s) => s.type === "feed")).then((l) => l.filter((i) => !i.source.startsWith("marble:"))), ...await writerItems(srcs), ...await relayItems(srcs)];
      items.set(f, got);
    } catch { items.set(f, items.get(f) || []); }
    busy.delete(f);
    if (signal.aborted) return;
    drawFolders();
    if (f === folder) drawRiver();
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
    Object.assign(menu.style, { top: `${scrollY + r.bottom + 4}px`, left: `${Math.max(16, Math.min(r.right - w, innerWidth - w - 16))}px`, width: `${w}px` });
    menu.onclick = (ev) => { const b = ev.target.closest("[data-n]"); if (b) { entries[+b.dataset.n][1](); closeMenu(); } };
    menu.querySelector("button")?.focus();
  }
  document.addEventListener("click", (ev) => { if (menu && !menu.contains(ev.target) && !ev.target.closest("[data-menu], .r-src-more")) closeMenu(); }, { signal });
  document.addEventListener("keydown", (ev) => { if (ev.key === "Escape") closeMenu(); }, { signal });
  const rowMenu = (el) => {
    const i = byKey.get(el.dataset.key);
    const more = el.querySelector("[data-menu]");
    if (i) openMenu(more?.offsetParent ? more : el.querySelector(".r-open") || el, [["Keep unread", () => { for (const k of [i.key, ...(i.keys || [])]) keepUnread(k); el.classList.remove("read"); drawFolders(); }]]);
  };

  root.addEventListener("click", (ev) => {
    const t = ev.target;
    if (t.closest("[data-people]")) { ev.preventDefault(); doc.people = { on: !doc.people.on, at: now() }; saveList(); items.delete("people"); drawFolders(); if (folder === "people") { drawRiver(); fill("people"); } return; }
    const fl = t.closest("[data-folder]");
    if (fl) { ev.preventDefault(); folder = fl.dataset.folder; history.replaceState(history.state, "", `/reader?f=${encodeURIComponent(folder)}`); drawFolders(); drawRiver(); if (!items.has(folder)) fill(folder); return; }
    const more = t.closest(".r-src-more");
    if (more) {
      const id = more.closest("[data-source]").dataset.source, s = doc.sources.find((x) => x.id === id);
      return openMenu(more, [
        ["Mark all as read", () => { markAllRead([id]); drawFolders(); drawRiver(); }],
        [muted(id) ? "Unmute" : "Mute", () => { doc.muted = doc.muted.filter((m) => m.source !== id).concat({ source: id, at: now(), gone: muted(id) }); saveList(); drawFolders(); drawRiver(); }],
        ["Unsubscribe", () => { setSource({ ...s, gone: true }); drawFolders(); items.delete(s.folder || LABELS[s.type][0]); fill(folder); }],
      ]);
    }
    if (t.closest(".r-all")) { const list = items.get(folder) || []; markAllRead([...new Set(list.map((i) => i.source))]); return drawRiver(); }
    const el = t.closest(".r-row"), i = el && byKey.get(el.dataset.key);
    if (!i) return;
    if (t.closest("[data-menu]")) return rowMenu(el);
    if (t.closest("[data-star]")) {
      const url = i.link || location.origin + postPath(i.event), title = i.kind === "digest" ? `${name(i.author)} · ${i.posts.length} posts` : i.title;
      const have = notes.items().find((n) => n.url === url);
      if (have) notes.remove(have); else notes.keepPage(url, title);
      const on = !have, b = t.closest("[data-star]");
      b.classList.toggle("on", on); b.setAttribute("aria-pressed", on); b.setAttribute("aria-label", on ? "Starred" : "Star");
      return;
    }
    if (t.closest("[data-expand]")) { const p = el.querySelector(".r-posts"); p.hidden = !p.hidden; if (!p.hidden) readItem(i); return; }
    if (t.closest("[data-read]")) readItem(i);
  });
  // right-click, and a long press on a phone: the row's menu
  root.addEventListener("contextmenu", (ev) => { const el = ev.target.closest(".r-row"); if (el) { ev.preventDefault(); rowMenu(el); } });
  let press;
  root.addEventListener("touchstart", (ev) => { const el = ev.target.closest(".r-row"); if (el) press = setTimeout(() => { press = "fired"; rowMenu(el); }, 500); }, { passive: true });
  root.addEventListener("touchmove", () => { if (press !== "fired") clearTimeout(press); }, { passive: true });
  root.addEventListener("touchend", (ev) => { if (press === "fired") ev.preventDefault(); else clearTimeout(press); press = null; });

  drawFolders();
  drawRiver();
  fill(folder);
  const redraw = () => { if (!signal.aborted) { drawFolders(); drawRiver(); } };
  onRead.add(redraw);
  signal.addEventListener("abort", () => { onRead.delete(redraw); pushAccount(); });
  pullAccount().then(() => { for (const f of new Set(doc.sources.filter((s) => !s.gone).map((s) => s.folder))) if (f && f !== folder) items.delete(f); if (doc.sources.some((s) => s.folder === folder)) fill(folder); }).catch(() => {});
  // the other folders, quietly, for their dots
  setTimeout(() => { for (const f of folders()) if (f.id !== folder) fill(f.id); }, 1500);
}
