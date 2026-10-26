// The one search box's sections other than People, as in the approved search mock (igloo/feed-mock/search.mjs):
// posts that match and are worth reading, posts with a #tag, today's stories that match, and your own
// saved, liked and written posts. Everything is worked out here in the browser.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { keep, profiles, outbox } from "./data.js";
import { followerCounts, SEARCH_RELAYS } from "./search.js";

const FEEDS = "https://sybenx.github.io/topic-feeds/index.json";
const now = () => Math.floor(Date.now() / 1000);

// "self-hosting" also finds "self-hosted", "selfhost" and "self hosting"
export function queryRe(q) {
  const parts = q.toLowerCase().replace(/^#/, "").split(/[\s-]+/).filter(Boolean)
    .map((w) => w.replace(/(ing|ed|s)$/, "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return new RegExp("(^|[^\\p{L}\\p{N}])" + parts.join("[\\s-]?") + "(?:ing|ed|es|s|ers|er)?(?=$|[^\\p{L}\\p{N}])", "iu");
}

// ── Filters, as in the feed mock ─────────────────────────────────────────────────────────────────────

const tag = (e, k) => (e.tags.find((t) => t[0] === k) || [])[1] || "";
const plain = (e) => (e.kind === 30023 ? [tag(e, "title"), tag(e, "summary"), e.content.slice(0, 3000)].join("\n") : e.content)
  .replace(/nostr:\S+/g, " ").replace(/https?:\/\/\S+/g, " ");
const realText = (e) => plain(e).replace(/#\w+/g, "").replace(/\s+/g, " ").trim();
const isReply = (e) => e.kind === 1 && e.tags.some((t) => t[0] === "e" && t[3] !== "mention");
const BOTNAME = /\bbot\b|bot$|🤖/i;
const FEEBOT = /^Block \d{6,}|mempool|sat\/vB/i;
const HEAVY = /\b(died|dies|passed away|funeral|r\.?i\.?p\b|mourn\w*|griev\w*|grief|cancer|chemo\w*|hospital\w*|hospice|diagnos\w*|suicid\w*|want to die|kill myself|miscarriage|condolences)\b/i;
const NSFW = (e) => e.tags.some((t) => t[0] === "content-warning" || (t[0] === "t" && /nsfw|porn|nude/i.test(t[1] || "")));
const EN = new Set("the a an and or but of to in on for with is are was were it this that i you we they my your not be have has at from by so if just what about".split(" "));
const english = (s) => { const w = s.toLowerCase().match(/[a-z']+/g) || []; return w.length >= 4 && w.filter((x) => EN.has(x)).length / w.length >= 0.12; };
// The mock reads English; here only when the browser's first language is English (otherwise no language filter).
const wantEnglish = () => /^en\b/i.test(navigator.languages?.[0] || navigator.language || "en");

function followersLabel(n) {
  return `${n >= 10000 ? Math.round(n / 1000) + "k" : n >= 1000 ? (n / 1000).toFixed(1) + "k" : n} followers`;
}

/**
 * Posts worth showing for a search: the search servers' best ("sort:top") and newest matches, the mock's
 * filters, then trust (people you follow, or 300+ followers), freshness (a month's half-life) and the
 * servers' own order. One post per person. Each: {e, why}.
 * With tag set, posts with that #tag from the fast servers instead.
 */
export async function goodPosts(q, { follows: followsP = new Set(), me = null, tag: tagName = null, signal = null } = {}) {
  const filters = tagName
    ? [{ kinds: [1], "#t": [tagName.toLowerCase()], limit: 100 }]
    : [{ kinds: [1, 30023], search: q + " sort:top", limit: 60 }, { kinds: [1, 30023], search: q, limit: 60 }];
  const t0 = performance.now();
  const found = await req(tagName ? FAST : SEARCH_RELAYS, filters, { timeout: 4000, settle: 700, signal, onEvent: keep });
  const follows = await followsP; // the account's follows (a Set, or a promise of one), needed only from here
  if (signal?.aborted) return [];
  const t1 = performance.now();
  const re = tagName ? null : queryRe(q);
  const en = wantEnglish();
  const cand = found.filter((e) => !isReply(e) && !NSFW(e) && !FEEBOT.test(e.content) && (!re || re.test(plain(e))))
    .filter((e) => realText(e).length >= 40 && (!en || english(realText(e))))
    .filter((e) => follows.has(e.pubkey) || e.pubkey === me || !HEAVY.test(realText(e)));
  const authors = [...new Set(cand.map((e) => e.pubkey))];
  const strangers = authors.filter((a) => !follows.has(a) && a !== me);
  const need = authors.filter((a) => !profiles.has(a));
  const [counts] = await Promise.all([
    strangers.length ? followerCounts(strangers) : new Map(),
    need.length ? req(relays(FAST, INDEXERS), { kinds: [0], authors: need.slice(0, 150) }, { timeout: 2500, settle: 500, onEvent: keep }) : null,
  ]);
  if (signal?.aborted) return [];
  goodPosts.last = { search: Math.round(t1 - t0), rank: Math.round(performance.now() - t1), found: found.length, kept: cand.length };
  const scored = cand.map((e, order) => {
    const p = profiles.get(e.pubkey) || {};
    if (p.bot === true || BOTNAME.test(p.display_name || p.name || "")) return null;
    const fc = counts.get(e.pubkey) || 0;
    let trust, why = "";
    if (e.pubkey === me) { trust = 1; why = "You"; }
    else if (follows.has(e.pubkey)) { trust = 0.9; why = "You follow them"; }
    else if (fc >= 300) { trust = 0.35 + 0.1 * Math.min(1, Math.log10(fc / 300) / 2); why = followersLabel(fc); }
    else return null; // nobody you know follows them, and few others do
    const fresh = Math.pow(0.5, (now() - e.created_at) / 86400 / 30);
    const rank = 1 / (1 + order / 20);
    return { e, why, score: trust * (0.1 + 0.9 * fresh) * rank };
  }).filter(Boolean).sort((a, b) => b.score - a.score);
  const seen = new Set();
  return scored.filter((s) => !seen.has(s.e.pubkey) && seen.add(s.e.pubkey));
}

// ── Stories ──────────────────────────────────────────────────────────────────────────────────────────

let feeds = null;
/** Today's stories from topic-feeds, fetched once a visit (started early, when the search box gets focus). */
export function loadStories() {
  if (!feeds) {
    feeds = fetch(FEEDS).then((r) => r.json()).then((idx) => Promise.all(idx.topics.map((t) =>
      fetch(t.url).then((r) => r.json()).then((d) => d.items.map((i) => ({ ...i, topic: t.name }))).catch(() => []))))
      .then((lists) => lists.flat())
      .catch(() => { feeds = null; return []; });
  }
  return feeds;
}

/** Stories whose title or summary matches, title matches first, then newest. */
export async function stories(q) {
  const re = queryRe(q);
  const items = await loadStories();
  const seen = new Set();
  return items.filter((i) => re.test(i.title) || re.test(i.summary || ""))
    .sort((a, b) => (re.test(b.title) - re.test(a.title)) || b.published_at - a.published_at)
    .filter((i) => !seen.has(i.url) && seen.add(i.url));
}

// ── Your things ──────────────────────────────────────────────────────────────────────────────────────

let mine = null; // {saved: Set, liked: Set, events: Map} for the signed-in account, read once a visit
function myThings(me) {
  if (mine?.me === me) return mine.ready;
  const ready = (async () => {
    const own = await Promise.race([outbox(me).then((o) => o.write), new Promise((r) => setTimeout(() => r([]), 1500))]);
    const where = relays(own, FAST);
    const [lists, likes] = await Promise.all([
      req(where, { kinds: [10003], authors: [me] }, { timeout: 3000, settle: 800 }),
      req(where, { kinds: [7], authors: [me], limit: 500 }, { timeout: 4000, settle: 800 }),
    ]);
    const bookmarks = lists.sort((a, b) => b.created_at - a.created_at)[0];
    const saved = new Set((bookmarks?.tags || []).filter((t) => t[0] === "e").map((t) => t[1]));
    const liked = new Set(likes.filter((e) => e.content !== "-").map((e) => (e.tags.filter((t) => t[0] === "e").pop() || [])[1]).filter(Boolean));
    const ids = [...new Set([...saved, ...liked])].slice(0, 600);
    const events = new Map();
    for (let i = 0; i < ids.length; i += 200) {
      const got = await req(relays(FAST, own), { ids: ids.slice(i, i + 200) }, { timeout: 4000, settle: 800, onEvent: keep });
      for (const e of got) events.set(e.id, e);
    }
    return { saved, liked, events };
  })();
  mine = { me, ready };
  return ready;
}
/** Start reading your saved and liked posts as soon as you're signed in. */
export const prepareMine = (me) => { if (me) myThings(me); };

/** Your saved, written and liked posts that match, in that order, newest first within each. Each: {e, why}. */
export async function yours(q, me, { signal } = {}) {
  if (!me) return [];
  const re = queryRe(q);
  const [things, written] = await Promise.all([
    myThings(me),
    req(SEARCH_RELAYS, { kinds: [1, 30023], authors: [me], search: q, limit: 30 }, { timeout: 3000, settle: 600, signal, onEvent: keep }),
  ]);
  if (signal?.aborted) return [];
  const all = new Map([...things.events, ...written.map((e) => [e.id, e])]);
  const items = [];
  for (const e of all.values()) {
    if ((e.kind !== 1 && e.kind !== 30023) || !re.test(plain(e))) continue;
    const why = things.saved.has(e.id) ? "Saved" : e.pubkey === me ? "You wrote" : things.liked.has(e.id) ? "You liked" : null;
    if (why) items.push({ e, why, rank: { Saved: 0, "You wrote": 1, "You liked": 2 }[why] });
  }
  return items.sort((a, b) => a.rank - b.rank || b.e.created_at - a.e.created_at);
}
