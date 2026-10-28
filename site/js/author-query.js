// Someone you follow, inside a search ("thailand anarko", "thailand arko", "from:anarko thailand", "@anarko thailand"):
// their posts with the other words, as well as (never instead of) the usual results. Only people you follow (anyone can
// call themselves anything: the handle rule), by their name or their NIP-05's name part: exactly, then a start of it
// (3+ letters), a part of it (4+), one typo off (4+). The phone's AuthorQuery (Search.kt), ported as it is.
import { normalizeName } from "./search-rank.js";

const EXPLICIT = /^(?:from:|@|＠)(.+)$/i;

// How well a typed name names someone; at least STRONG: it's their name, not a part of it.
export const EXACT = 7, TIGHT = 6, FIRST = 5, PREFIX = 4, PART = 3, TYPO = 2, STRONG = FIRST;

/**
 * {author, rest, others, loose} for a query, or null: whose posts to look in, for which words; others are other people
 * you follow it could mean (chips); loose, a part of a name (it could be wrong): after the first of the usual results.
 * @param follows Set of pubkeys you follow (none when signed out), known Map pubkey → profile.
 */
export function split(q, follows, known) {
  const words = (q || "").trim().split(/\s+/).filter(Boolean);
  if (words.length < 2 || !follows?.size) return null;
  const mine = [...follows].map((pk) => known.get(pk) && { ...known.get(pk), pubkey: pk }).filter(Boolean);
  const make = (hits, rest) => ({ author: hits[0][0], rest, others: hits.slice(1, 5).map((h) => h[0]), loose: hits[0][1] < STRONG });
  // from:name, @name: that word only (a name that isn't someone you follow: no author, the words as they are)
  const i = words.findIndex((w) => EXPLICIT.test(w));
  if (i >= 0) {
    const hits = ranked(mine, EXPLICIT.exec(words[i])[1]);
    return hits.length ? make(hits, words.filter((_, j) => j !== i).join(" ")) : null;
  }
  // otherwise the run of words (three at most, never all of them) that best names someone you follow: the closest
  // match, then the longest run, then the leftmost
  let best = null;
  for (let len = Math.min(3, words.length - 1); len >= 1; len--) for (let start = 0; start + len <= words.length; start++) {
    const hits = ranked(mine, words.slice(start, start + len).join(" "));
    if (!hits.length) continue;
    if (!best || hits[0][1] > best.score || (hits[0][1] === best.score && len > best.len))
      best = { score: hits[0][1], len, split: make(hits, [...words.slice(0, start), ...words.slice(start + len)].join(" ")) };
  }
  return best?.split || null;
}

/** Who typed could name among mine, with how well, best first: [[pubkey, score]…]. */
export function ranked(mine, typed) {
  const t = normalizeName(typed);
  if (t.length < 2) return [];
  const tight = t.replace(/ /g, "");
  return mine.map((p) => [p.pubkey, score(p, t, tight)]).filter((h) => h[1] > 0).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1));
}

function score(p, t, tight) {
  const n = normalizeName(p.display_name || p.name);
  const local = normalizeName((p.nip05 || "").split("@")[0].replace(/^_$/, ""));
  const names = [n.replace(/ /g, ""), local.replace(/ /g, "")].filter(Boolean);
  const words = [...n.split(" "), ...local.split(" ")].filter(Boolean);
  if (n === t) return EXACT;
  if (names.includes(tight)) return TIGHT;
  if (tight.length >= 4 && words[0] === t) return FIRST;
  if (tight.length >= 3 && [...names, ...words].some((w) => w.startsWith(tight))) return PREFIX;
  if (tight.length >= 4 && names.some((w) => w.includes(tight))) return PART;
  if (tight.length >= 4 && [...names, ...words].some((w) => oneOff(w, tight))) return TYPO;
  return 0;
}

/** One letter added, dropped, changed, or two side by side swapped. */
export function oneOff(a, b) {
  if (a === b || Math.abs(a.length - b.length) > 1) return false;
  if (a.length === b.length) {
    const d = [...a].map((_, i) => i).filter((i) => a[i] !== b[i]);
    return d.length === 1 || (d.length === 2 && d[1] === d[0] + 1 && a[d[0]] === b[d[1]] && a[d[1]] === b[d[0]]);
  }
  const [long, short] = a.length > b.length ? [a, b] : [b, a];
  let i = 0;
  while (i < short.length && long[i] === short[i]) i++;
  return long.slice(i + 1) === short.slice(i);
}
