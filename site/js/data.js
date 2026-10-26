// What the pages read: posts by id, people's profiles (asked for in batches), where each person posts
// (their NIP-65 list), replies. Everything found is kept for the visit, so going back is instant.
import { req, relays, FAST, INDEXERS, MORE } from "./pool.js";
import { parseProfile, verifyNip05 } from "./search-rank.js";

export const events = new Map(); // id → event
export const profiles = new Map(); // pubkey → parsed profile (newest)
const outboxes = new Map(); // pubkey → Promise<{write, read}>
const listeners = new Set();

export function keep(e) {
  events.set(e.id, e);
  if (e.kind === 0) {
    const p = parseProfile(e);
    // parseProfile (Astral's) keeps what search needs; a profile page also shows the banner and website.
    if (p) try { const c = JSON.parse(e.content); p.banner = c.banner || ""; p.website = c.website || ""; p.bot = c.bot === true; } catch {}
    const old = profiles.get(e.pubkey);
    // (a copy from search has no banner: the full one replaces it even when it's the same version)
    if (p && (!old || old.created_at < p.created_at || (old.banner === undefined && old.created_at <= p.created_at))) {
      profiles.set(e.pubkey, p);
      for (const fn of listeners) fn(e.pubkey, p);
    }
  }
  return e;
}

/** fn(pubkey, profile) whenever someone's profile arrives or gets newer. */
export const onProfile = (fn) => (listeners.add(fn), () => listeners.delete(fn));

// Profiles are asked for together: every name on a page in one request a moment later.
const wanted = new Set(), asked = new Set();
let flushTimer = null;
export function wantProfile(pk, hints = []) {
  if (!pk || asked.has(pk)) return;
  wanted.add(pk);
  for (const h of hints) extraHints.add(h);
  if (!flushTimer) flushTimer = setTimeout(flushProfiles, 30);
}
const extraHints = new Set();
function flushProfiles() {
  flushTimer = null;
  const pks = [...wanted].slice(0, 150);
  pks.forEach((pk) => { wanted.delete(pk); asked.add(pk); });
  const urls = relays(FAST, INDEXERS, [...extraHints].slice(0, 3));
  extraHints.clear();
  if (pks.length) req(urls, { kinds: [0], authors: pks }, { timeout: 4000, settle: 1500, onEvent: keep });
  if (wanted.size) flushTimer = setTimeout(flushProfiles, 30);
}

/**
 * Someone's profile: what's known, or the first answer (null if nobody has it). The others keep coming
 * for a moment, and a newer one is announced through onProfile.
 */
export function profile(pk, hints = []) {
  if (profiles.has(pk)) return Promise.resolve(profiles.get(pk));
  asked.add(pk);
  return new Promise((resolve) => {
    req(relays(hints, FAST, INDEXERS), { kinds: [0], authors: [pk] }, { timeout: 3000, onEvent: (e) => { keep(e); resolve(profiles.get(pk) || null); } })
      .then(() => resolve(profiles.get(pk) || null));
  });
}

/** Where someone posts (write) and reads (read), from their kind 10002 list. */
export function outbox(pk) {
  if (!outboxes.has(pk)) {
    outboxes.set(pk, req(relays(INDEXERS, FAST), { kinds: [10002], authors: [pk] }, { timeout: 2500, settle: 600 }).then((list) => {
      const newest = list.sort((a, b) => b.created_at - a.created_at)[0];
      const write = [], read = [];
      for (const t of newest?.tags || []) {
        if (t[0] !== "r" || !t[1]) continue;
        if (t[2] !== "read") write.push(t[1]);
        if (t[2] !== "write") read.push(t[1]);
      }
      return { write: relays(write).slice(0, 4), read: relays(read).slice(0, 4) };
    }));
  }
  return outboxes.get(pk);
}

/**
 * A post by id, as fast as it can be had: the places named in the link and the fast defaults all at once,
 * the first good answer wins. If none of them has it after a moment, the author's own places and a wider
 * set are asked too. Resolves with the event, or null.
 */
export async function findEvent(id, { relays: hints = [], author = null, timeout = 7000 } = {}) {
  if (events.has(id)) return events.get(id);
  const ctrl = new AbortController();
  const first = (urls, wait) => req(urls, { ids: [id] }, { timeout: wait, stopOnFirst: true, signal: ctrl.signal, onEvent: keep }).then((l) => l[0] || null);
  const quick = first(relays(hints, FAST), timeout);
  const wider = new Promise((r) => setTimeout(r, 1200)).then(async () => {
    if (events.has(id)) return events.get(id);
    const own = author ? (await outbox(author)).write : [];
    return first(relays(own, MORE, INDEXERS), timeout - 1200);
  });
  const found = await Promise.race([
    quick.then((e) => e || wider),
    wider.then((e) => e || quick),
  ]);
  ctrl.abort();
  return found;
}

/**
 * A long-form piece or other replaceable thing by its naddr parts: the places in the link and the fast
 * ones at once, the author's own as soon as they're known. The newest of what arrives in the first
 * moment after the first answer wins.
 */
export function findAddress({ pubkey, kind, identifier, relays: hints = [] }) {
  const filter = { kinds: [kind], authors: [pubkey], "#d": [identifier] };
  const ctrl = new AbortController();
  const got = [];
  return new Promise((resolve) => {
    let timer = null;
    const done = () => { ctrl.abort(); resolve(got.sort((a, b) => b.created_at - a.created_at)[0] || null); };
    const onEvent = (e) => { keep(e); got.push(e); if (!timer) timer = setTimeout(done, 150); };
    const quick = req(relays(hints, FAST), filter, { timeout: 5000, onEvent, signal: ctrl.signal });
    const own = outbox(pubkey).then((o) => req(o.write, filter, { timeout: 4000, onEvent, signal: ctrl.signal }));
    Promise.all([quick, own]).then(() => { if (!timer) done(); });
  });
}

/** Replies to a post, from the places it was found, the author's inbox and the fast defaults. onEvent streams them. */
export async function replies(e, { onEvent, hints = [] } = {}) {
  const inbox = await Promise.race([outbox(e.pubkey).then((o) => o.read), new Promise((r) => setTimeout(() => r([]), 900))]);
  const filter = e.kind >= 30000 && e.kind < 40000
    ? { kinds: [1, 1111], "#a": [`${e.kind}:${e.pubkey}:${(e.tags.find((t) => t[0] === "d") || [])[1] || ""}`], limit: 300 }
    : { kinds: [1, 1111], "#e": [e.id], limit: 300 };
  return req(relays(hints, FAST, inbox), filter, { timeout: 6000, settle: 1500, onEvent: (r) => { keep(r); onEvent?.(r); } });
}

/** Someone's posts before `until`, newest first: from the fast places and their own, streamed to onEvent. */
export async function postsBy(pk, { until, limit = 30, hints = [], onEvent = null, signal = null } = {}) {
  const filter = { kinds: [1, 6], authors: [pk], limit };
  if (until) filter.until = until;
  const seen = new Set();
  const each = (e) => { if (seen.has(e.id)) return; seen.add(e.id); keep(e); onEvent?.(e); };
  const lists = await Promise.all([
    req(relays(hints, FAST), filter, { timeout: 4000, settle: 800, onEvent: each, signal }),
    outbox(pk).then((o) => req(o.write.filter((u) => !FAST.includes(u)), filter, { timeout: 4000, settle: 800, onEvent: each, signal })),
  ]);
  const byId = new Map(lists.flat().map((e) => [e.id, e]));
  return [...byId.values()].sort((a, b) => b.created_at - a.created_at);
}

/** name@domain → pubkey (and the places it says to look), or null. */
export async function resolveNip05(address) {
  const [name, domain] = address.includes("@") ? address.split("@") : ["_", address];
  try {
    const ctrl = new AbortController();
    setTimeout(() => ctrl.abort(), 4000);
    const r = await fetch(`https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`, { signal: ctrl.signal, redirect: "error" });
    const data = await r.json();
    const names = data?.names || {};
    const pk = names[name] ?? Object.entries(names).find(([k]) => k.toLowerCase() === name.toLowerCase())?.[1];
    if (!/^[0-9a-f]{64}$/.test(pk || "")) return null;
    return { pubkey: pk, relays: (data.relays?.[pk] || []).slice(0, 3) };
  } catch { return null; }
}

export { verifyNip05 };
