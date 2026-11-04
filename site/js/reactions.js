// What a reaction is, on the wire and on the page (the Marble reactions design, shared with Android; content-rules.md
// §10, fixtures/reactions/): no page, no network, so the fixtures can run it as it is.
//  - ❤️ goes out as "+" (so other apps count it as a like), and "+" (or nothing) comes in as ❤️; "-" is ignored, and so is
//    anything that isn't one emoji or one :shortcode: with its emoji tag.
//  - The tray: your recent emoji (six at most), then the defaults 👍 ❤️ 😂 😢 👀 🧐 without repeats: six in all.
//  - Recents: from your own last ~200 reactions, the most recently used first (how often breaks a tie).
//  - Changing or removing yours deletes the old one (NIP-09) and, changing, sends the new one.

export const DEFAULTS = ["👍", "❤️", "😂", "😢", "👀", "🧐"];
export const TRAY = 6, RECENT_FROM = 200;

// An emoji: a character ("😂"), or someone's own ({shortcode, url}).
export const keyOf = (e) => (typeof e === "string" ? `n:${e}` : `c:${e.shortcode}|${e.url}`);
export const same = (a, b) => !!a && !!b && keyOf(a) === keyOf(b);

const graphemes = (s) => [...new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(s)].map((g) => g.segment);
const EMOJI = /^(?:\p{Extended_Pictographic}|\p{Regional_Indicator}{2}|[#*0-9]️?⃣)/u;

/** A reaction's emoji (kind 7): "❤️" for "+" or nothing, a character, {shortcode, url}, or null (ignored). */
export function emojiOf(r) {
  const c = (r?.content ?? "").trim();
  if (c === "" || c === "+") return "❤️";
  if (c === "-") return null;
  const custom = /^:([\w-]+):$/.exec(c);
  if (custom) {
    const url = (r.tags || []).find((t) => t[0] === "emoji" && t[1] === custom[1])?.[2];
    return /^https:\/\//.test(url || "") ? { shortcode: custom[1], url } : null;
  }
  const g = graphemes(c);
  return g.length === 1 && EMOJI.test(g[0]) ? g[0] : null;
}

/** What a reaction with this emoji says: {content, tags} (❤️ as "+"; someone's own as :shortcode: and its emoji tag). */
export function contentFor(e) {
  if (typeof e !== "string") return { content: `:${e.shortcode}:`, tags: [["emoji", e.shortcode, e.url]] };
  return { content: e === "❤️" ? "+" : e, tags: [] };
}

/** The reaction to post (an event with id, pubkey, kind), unsigned: kind 7 (NIP-25). */
export function reactionTo(post, e, { relay = "", now = Math.floor(Date.now() / 1000) } = {}) {
  const { content, tags } = contentFor(e);
  return { kind: 7, created_at: now, content, tags: [["e", post.id, relay, post.pubkey], ["p", post.pubkey], ["k", String(post.kind ?? 1)], ...tags] };
}

/** Deleting your old reactions (NIP-09), unsigned: kind 5 naming each, and what kind they were. */
export function deletionOf(olds, { now = Math.floor(Date.now() / 1000) } = {}) {
  return { kind: 5, created_at: now, content: "", tags: [...olds.map((o) => ["e", o.id]), ["k", "7"]] };
}

/** What reacting with to (null: taking it back) sends, in order, given your reactions to post so far: the new reaction,
 *  then the old ones deleted. */
export function changeOf(post, olds, to, opts = {}) {
  return [...(to ? [reactionTo(post, to, opts)] : []), ...(olds.length ? [deletionOf(olds, opts)] : [])];
}

/** Your recent emoji from your own reactions: the last RECENT_FROM, by when each emoji was last used, then how often. */
export function recentsFrom(own) {
  const by = new Map();
  for (const r of [...own].filter((r) => r.kind === 7).sort((a, b) => b.created_at - a.created_at).slice(0, RECENT_FROM)) {
    const e = emojiOf(r);
    if (!e) continue;
    const k = keyOf(e), s = by.get(k) || { e, last: 0, n: 0 };
    s.last = Math.max(s.last, r.created_at); s.n++;
    by.set(k, s);
  }
  return [...by.values()].sort((a, b) => b.last - a.last || b.n - a.n).map((s) => s.e);
}

/** The tray's six: your recent emoji (six at most), then the defaults that aren't there yet. */
export function trayOf(recents) {
  const out = [];
  for (const e of [...recents.slice(0, TRAY), ...DEFAULTS]) if (out.length < TRAY && !out.some((x) => same(x, e))) out.push(e);
  return out;
}

/**
 * An opened post's summary: [{e, n, yours, people: [pubkey…]}…], most given first. byPerson is each person's newest
 * reaction event (pubkey → event); yours (your emoji, or null) stands for yours when it's newer than the relays know.
 */
export function summary(byPerson, me = null, yours = undefined) {
  const out = [];
  const add = (pk, e) => {
    if (!e) return;
    let c = out.find((x) => same(x.e, e));
    if (!c) out.push(c = { e, n: 0, yours: false, people: [] });
    c.n++; c.people.push(pk);
    if (pk === me) c.yours = true;
  };
  for (const [pk, r] of byPerson) if (pk !== me || yours === undefined) add(pk, emojiOf(r));
  if (me && yours !== undefined) add(me, yours);
  return out.sort((a, b) => b.n - a.n);
}
