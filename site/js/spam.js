// Likely spam, as Wisp finds it (github.com/barrydeen/wisp, MIT), shared with Marble Android and discover
// (spam-signals.md; marble-content-fixtures, spam/):
//  - engagement that was manufactured, not earned (Wisp's InflatedEngagement): one account giving more than half of 10+
//    reactions, or 15+ reactors of whom half also repost within two minutes;
//  - a casino advert, by its words.
// (Wisp's third, its NSpam author classifier, is Android's only: its model is 9 MB.) Never for people you follow (they're
// only ever cued) or yourself. Likely spam is left out of search; shown anyway, it says so, with the reason a tap away.

export const MIN_REACTIONS = 10, COORDINATED_MIN_REACTORS = 15, PAIR_WINDOW_SECS = 120;

/** {short, long}: manufactured engagement on post among got (its reactions, kind 7, and reposts, 6 and 16), or null. */
export function inflated(post, got) {
  const target = (e) => e.tags.filter((t) => t[0] === "e" && t[1]).at(-1)?.[1];
  const reactions = got.filter((e) => e.kind === 7 && target(e) === post);
  const reposts = got.filter((e) => (e.kind === 6 || e.kind === 16) && target(e) === post);
  const counts = new Map();
  for (const r of reactions) counts.set(r.pubkey, (counts.get(r.pubkey) || 0) + 1);
  const total = reactions.length, most = Math.max(0, ...counts.values());
  if (total >= MIN_REACTIONS && most * 2 > total)
    return { short: "Its likes come from one account", long: `${most} of its ${total} likes come from a single account: that's how a post is pushed up the trending lists, not people liking it.` };
  const first = (list) => { const m = new Map(); for (const e of list) if (!m.has(e.pubkey) || m.get(e.pubkey) > e.created_at) m.set(e.pubkey, e.created_at); return m; };
  const reactedAt = first(reactions);
  if (reactedAt.size < COORDINATED_MIN_REACTORS) return null;
  const repostedAt = first(reposts);
  let paired = 0;
  for (const [pk, at] of reactedAt) if (repostedAt.has(pk) && Math.abs(repostedAt.get(pk) - at) <= PAIR_WINDOW_SECS) paired++;
  if (paired * 2 >= reactedAt.size)
    return { short: "Its likes look arranged", long: `${paired} of the ${reactedAt.size} accounts that liked it also reposted it within two minutes: a ring pushing it up, not people finding it.` };
  return null;
}

// The casino-review farm (found on diamonds, 2026-10-27): one templated "A note about <brand>" post per fresh account,
// in many languages, praising a casino's layout and welcome bonus. (Word edges for any script: casinò, казино.)
const OPENER = /^\s*(a note about|una nota su|une note sur|uma nota sobre|una nota sobre|eine notiz (über|zu)|een notitie over)(?![\p{L}\p{N}])/iu;
const GAMBLING = /(?<![\p{L}\p{N}])(casino|casinò|cassino|kasino|kasyno|казино|betting|bookmaker|sportsbook|slots?|free spins|freispiele|giri gratis|tiradas gratis|welcome bonus|bonus di benvenuto|bono de bienvenida|bônus de boas-vindas|jackpot|roulette|blackjack|scratchcards?|wagering)(?![\p{L}\p{N}])/giu;

/** {short, long}: a casino advert, by its words (the farm's opener and a gambling word, or three of them and a link). */
export function advert(e) {
  const head = (e.content || "").slice(0, 1500);
  const terms = [...new Set([...head.matchAll(GAMBLING)].map((m) => m[1].toLowerCase()))];
  if (!terms.length) return null;
  if (OPENER.test(head) || (terms.length >= 3 && head.includes("https://")))
    return { short: "Reads like a casino advert", long: `It's written like the casino adverts posted from throwaway accounts ("A note about…", ${terms.slice(0, 3).join(", ")}).` };
  return null;
}

// What's been found this visit (post id → why), and whose posts are never marked: you and the people you follow.
const found = new Map();
let trusted = null;
/** Who's exempt (a Set of pubkeys: you and your follows); until it's known, nothing is marked. */
export function setTrusted(set) { trusted = set; }

/** Why post e looks like spam (what was found, else its words), or null; never for you or the people you follow. */
export function spamOf(e) {
  if (!trusted || trusted.has(e.pubkey)) return null;
  return found.get(e.id) || advert(e);
}
/** Marks e (when it's a stranger's) with why; returns whether it's marked. */
export function mark(e, why) {
  if (!why || !trusted || trusted.has(e.pubkey)) return false;
  found.set(e.id, why);
  return true;
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
/** The quiet amber cue on a post that looks like spam, the reason on a tap ("" when it doesn't). */
export function cue(e) {
  const why = spamOf(e);
  return why ? `<details class="spam-cue"><summary>Looks like spam · ${esc(why.short)}</summary><p>${esc(why.long)} Marble leaves posts like this out of search. People you follow are never hidden, only marked.</p></details>` : "";
}
