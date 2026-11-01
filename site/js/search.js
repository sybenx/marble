// People search, the same way Astral and discover.syb.lol do it (astral-nostr src/nostr/profileSearch.js;
// discover-syb-lol search.js is the same port), on this page's shared sockets instead of NDK: name search
// on relay.ditto.pub and syb.lol, Primal's order and follower counts as hints, NIP-05s checked against
// their .well-known, people you follow first. Ranking is search-rank.js, unchanged. One difference: the
// first people found show at once, not 150 ms later (later updates are still batched).
import { rankProfiles, matchesQuery, verifyNip05, parseProfile, isBridged } from "./search-rank.js";
import { req, relays, FAST } from "./pool.js";

// Ditto's (relay.ditto.pub, syb.lol) and two more search relays, as the phone asks (results show as they arrive)
export const SEARCH_RELAYS = ["wss://relay.ditto.pub", "wss://syb.lol", "wss://search.nos.today", "wss://search.nostrarchives.com"];
const PRIMAL = ["wss://cache2.primal.net/v1", "wss://cache1.primal.net/v1"];
const RELAY_WAIT = 2500, ENRICH_WAIT = 2500, LATE_WAIT = 6000;
const FOLLOWER_COUNTS = 10000133; // Primal's kind for {pubkey: followers}

// Primal's cache answers {cache: [...]} REQs; it errors on multi-word names, so it's only a hint.
const primal = async (cache, timeout) => {
  for (const url of PRIMAL) {
    const ev = await req([url], { cache }, { timeout, unsigned: true });
    if (ev.length) return ev;
  }
  return [];
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Search people by name. Resolves with the ranked list; onResults gets each better list as it comes in
 * (people you follow first, then relay results as they arrive, then the ranked list, then late arrivals).
 * @param {object} o  {limit, follows: string[], cached: Map<pk, profile>, onResults, signal}
 */
export async function searchPeople(query, { limit = 8, follows = [], cached = new Map(), onResults = null, signal = null } = {}) {
  const q = (query || "").trim();
  if (q.length < 2) return [];
  const followSet = new Set(follows);
  const profiles = new Map(), verified = new Map(), followers = new Map(), primalRank = new Map(), late = [];
  let ranked = false, emitTimer = null;

  const add = (p) => {
    if (!p || isBridged(p) || !matchesQuery(p, q)) return false;
    const old = profiles.get(p.pubkey);
    if (old && old.created_at >= p.created_at) return false;
    profiles.set(p.pubkey, p);
    if (!old && ranked) late.push(p.pubkey);
    return true;
  };
  const list = () => {
    const all = [...profiles.values()], signals = { follows: followSet, verified, followers, primalRank };
    if (!ranked) return rankProfiles(all, q, signals).slice(0, limit + 5);
    const lateSet = new Set(late);
    return rankProfiles(all.filter((p) => !lateSet.has(p.pubkey)), q, signals).slice(0, limit + 5).concat(late.map((pk) => profiles.get(pk)));
  };
  const emit = () => { clearTimeout(emitTimer); emitTimer = null; if (!signal?.aborted) onResults?.(list(), { followers, verified, ranked }); };
  let emitted = false;
  const emitSoon = () => { if (!emitted) { emitted = true; emit(); } else if (!emitTimer) emitTimer = setTimeout(emit, 150); };

  // 1. people you follow, from profiles already loaded
  for (const pk of follows) {
    const p = cached.get(pk);
    if (p && matchesQuery(p, q)) add({ ...p, pubkey: pk, created_at: p.created_at || 0 });
  }
  if (profiles.size) emit();

  // 2. relays, streaming (they stay open a while longer for slow results)
  const relays = req(SEARCH_RELAYS, { kinds: [0], search: q, limit: 20 },
    { timeout: LATE_WAIT, signal, onEvent: (e) => { if (add(parseProfile(e))) emitSoon(); } });
  const primalDone = primal(["user_search", { query: q, limit }], RELAY_WAIT).then((events) => {
    events.filter((e) => e.kind === 0).forEach((e, i) => { if (!primalRank.has(e.pubkey)) primalRank.set(e.pubkey, i); add(parseProfile(e)); });
  }).catch(() => {});
  await Promise.race([Promise.all([relays, primalDone]), sleep(RELAY_WAIT)]);
  if (signal?.aborted) return [];
  emit();

  // 3. follower counts for every candidate and NIP-05 checks, then the ranked list
  const all = [...profiles.values()];
  const candidates = list().filter((p) => p.nip05).slice(0, 20);
  await Promise.race([
    Promise.all([
      primal(["user_infos", { pubkeys: all.map((p) => p.pubkey).slice(0, 100) }], ENRICH_WAIT).then((events) => {
        for (const e of events) if (e.kind === FOLLOWER_COUNTS) try { for (const [pk, n] of Object.entries(JSON.parse(e.content))) followers.set(pk, Number(n) || 0); } catch {}
      }).catch(() => {}),
      ...candidates.map((p) => verifyNip05(p.nip05, p.pubkey, ENRICH_WAIT).then((ok) => verified.set(p.pubkey, ok))),
    ]),
    sleep(ENRICH_WAIT),
  ]);
  if (signal?.aborted) return [];
  const result = list();
  ranked = true;
  emit();
  return result;
}

/** pubkey → follower count, from Primal (as the ranking above uses them). */
export async function followerCounts(pubkeys) {
  const counts = new Map();
  for (const e of await primal(["user_infos", { pubkeys: pubkeys.slice(0, 100) }], ENRICH_WAIT))
    if (e.kind === FOLLOWER_COUNTS) try { for (const [pk, n] of Object.entries(JSON.parse(e.content))) counts.set(pk, Number(n) || 0); } catch {}
  return counts;
}

/** Posts matching words, from both search relays, newest first. A #tag asks the fast relays for that tag instead. */
export async function searchPosts(query, { limit = 40, authors = null, kinds = [1], signal = null } = {}) {
  const tag = /^#([\p{L}\p{N}_-]+)$/u.exec(query.trim());
  const filter = tag ? { kinds, "#t": [tag[1].toLowerCase()], limit } : { kinds, search: query, limit };
  if (authors) filter.authors = authors;
  const got = await req(tag ? FAST : SEARCH_RELAYS, filter, { timeout: 5000, settle: 800, signal });
  return got.sort((a, b) => b.created_at - a.created_at);
}

// Ditto's servers take "sort:new" (newest first); the others don't, and get the words as they are.
const NEWEST_FIRST = ["wss://relay.ditto.pub", "wss://syb.lol"];

/**
 * One person's posts about some words, newest first, as the phone finds them: Ditto's servers newest first (by
 * relevance, a post from this morning can be past the first hundred), the other search servers as they are, and the
 * person's latest 200 posts that have every word (what no search server has yet). hints: where they post.
 */
export async function postsAbout(pk, words, { hints = [], signal = null } = {}) {
  const ws = words.toLowerCase().split(/\s+/).filter(Boolean);
  const text = (e) => `${e.content || ""} ${(e.tags.find((t) => t[0] === "title") || [])[1] || ""}`.toLowerCase();
  const base = { kinds: [1, 30023], authors: [pk] }, o = { timeout: 5000, settle: 800, signal };
  // (a word at least, from the search servers: one that ignores the search would send everything)
  const some = (e) => ws.some((w) => text(e).includes(w)), every = (e) => ws.every((w) => text(e).includes(w));
  const lists = await Promise.all([
    req(NEWEST_FIRST, { ...base, search: `${words} sort:new`, limit: 40 }, o).then((l) => l.filter(some)),
    req(SEARCH_RELAYS.filter((u) => !NEWEST_FIRST.includes(u)), { ...base, search: words, limit: 40 }, o).then((l) => l.filter(some)),
    req(relays(hints, FAST), { ...base, limit: 200 }, o).then((l) => l.filter(every)),
  ]);
  const seen = new Set();
  return lists.flat().filter((e) => !seen.has(e.id) && seen.add(e.id)).sort((a, b) => b.created_at - a.created_at);
}
