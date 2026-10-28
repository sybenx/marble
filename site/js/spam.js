// Likely spam, as Wisp finds it (github.com/barrydeen/wisp, MIT), shared with Marble Android and discover
// (spam-signals.md; marble-content-fixtures, spam/):
//  - engagement that was manufactured, not earned (Wisp's InflatedEngagement): one account giving more than half of 10+
//    reactions, or 15+ reactors of whom half also repost within two minutes;
//  - a gambling advert, by its words, or links to a betting site that pay whoever posted them.
// (Wisp's third, its NSpam author classifier, is Android's only: its model is 9 MB.) Never for people you follow or
// yourself; the guesses from what someone writes only for people nobody you follow follows. Likely spam is left out of
// search; shown anyway, it says so in a broad, plain category: never a number or the rule itself (that would teach
// spammers the way around it). "Not spam" on one keeps its author unmarked in this browser.

// What people see: a category (on the post) and one plain sentence (on a tap).
const BOUGHT = { short: "Its likes look bought", long: "Its likes look bought or arranged, not given by people who found it." };
const ADVERT = { short: "A gambling advert", long: "It reads like an advert for a betting or casino site, posted from an account made for it." };
const BETTING = { short: "Links to a betting site", long: "It sends people to a betting site, with a link that pays whoever posted it." };

export const MIN_REACTIONS = 10, COORDINATED_MIN_REACTORS = 15, PAIR_WINDOW_SECS = 120;

/** {short, long}: manufactured engagement on post among got (its reactions, kind 7, and reposts, 6 and 16), or null. */
export function inflated(post, got) {
  const target = (e) => e.tags.filter((t) => t[0] === "e" && t[1]).at(-1)?.[1];
  const reactions = got.filter((e) => e.kind === 7 && target(e) === post);
  const reposts = got.filter((e) => (e.kind === 6 || e.kind === 16) && target(e) === post);
  const counts = new Map();
  for (const r of reactions) counts.set(r.pubkey, (counts.get(r.pubkey) || 0) + 1);
  const total = reactions.length, most = Math.max(0, ...counts.values());
  if (total >= MIN_REACTIONS && most * 2 > total) return BOUGHT;
  const first = (list) => { const m = new Map(); for (const e of list) if (!m.has(e.pubkey) || m.get(e.pubkey) > e.created_at) m.set(e.pubkey, e.created_at); return m; };
  const reactedAt = first(reactions);
  if (reactedAt.size < COORDINATED_MIN_REACTORS) return null;
  const repostedAt = first(reposts);
  let paired = 0;
  for (const [pk, at] of reactedAt) if (repostedAt.has(pk) && Math.abs(repostedAt.get(pk) - at) <= PAIR_WINDOW_SECS) paired++;
  if (paired * 2 >= reactedAt.size) return BOUGHT;
  return null;
}

// The casino-review farm (found on diamonds, 2026-10-27): one templated "A note about <brand>" post per fresh account,
// in many languages, praising a casino's layout and welcome bonus. (Word edges for any script: casinò, казино.)
const OPENER = /^\s*(a note about|una nota su|une note sur|uma nota sobre|una nota sobre|eine notiz (über|zu)|een notitie over)(?![\p{L}\p{N}])/iu;
const GAMBLING = /(?<![\p{L}\p{N}])(casino|casinò|cassino|kasino|kasyno|казино|betting|bookmaker|sportsbook|slots?|free spins|freispiele|giri gratis|tiradas gratis|welcome bonus|bonus di benvenuto|bono de bienvenida|bônus de boas-vindas|jackpot|roulette|blackjack|scratchcards?|wagering)(?![\p{L}\p{N}])/giu;

// Betting and casino sites (a known list holds up even when it's known), and the marks of a link that pays its poster.
const BETTING_SITES = /(^|\.)(predyx\.com|polymarket\.com|kalshi\.com|stake\.com|stake\.us|bc\.game|roobet\.com|rollbit\.com|bet365\.com|draftkings\.com|fanduel\.com|1xbet\.com|betway\.com|sportsbet\.io|cloudbet\.com|nitrogensports\.eu|duelbits\.com|shuffle\.com|gamdom\.com|bitstarz\.com|7bitcasino\.com|mystake\.com|thunderpick\.io)$/;
const REFERRAL = /[?&](ref|r|aff|affiliate|referral|refcode|invite|code|btag|promo)=/i;
const LINK = /https?:\/\/([^/\s?#]+)\S*/gi;

/** Links to betting sites that pay the poster (a referral mark), or many of them. */
export function bettingLinks(e) {
  const bets = [...(e.content || "").matchAll(LINK)].filter((m) => BETTING_SITES.test(m[1].toLowerCase().replace(/^www\./, ""))).map((m) => m[0]);
  return bets.some((b) => REFERRAL.test(b)) || bets.length >= 3 ? BETTING : null;
}

/** A gambling advert: links to betting sites that pay the poster; the farm's opener and a gambling word; or three
 *  gambling words and a link. */
export function advert(e) {
  const bet = bettingLinks(e);
  if (bet) return bet;
  const head = (e.content || "").slice(0, 1500);
  const terms = [...new Set([...head.matchAll(GAMBLING)].map((m) => m[1].toLowerCase()))];
  if (!terms.length) return null;
  return OPENER.test(head) || (terms.length >= 3 && head.includes("https://")) ? ADVERT : null;
}

// What's been found this visit (post id → why); whose posts are never marked (you and the people you follow); who's
// vouched for (followed by someone you follow: no guesses from their words); and who you've said isn't spam.
const found = new Map();
let trusted = null, vouched = null;
/** Who's exempt (a Set of pubkeys: you and your follows); until it's known, nothing is marked. */
export function setTrusted(set) { trusted = set; }
/** Who the people you follow follow (a Set); until it's known, nothing is guessed from words. */
export function setVouched(set) { vouched = set; }

const NOT_SPAM = "marble.notspam";
let notSpam = new Set();
try { notSpam = new Set(JSON.parse(localStorage.getItem(NOT_SPAM) || "[]")); } catch {}
/** "Not spam": pk's posts aren't marked again, in this browser. */
export function markNotSpam(pk) {
  notSpam.add(pk);
  try { localStorage.setItem(NOT_SPAM, JSON.stringify([...notSpam])); } catch {}
  dispatchEvent(new Event("marble:spam"));
}

/** Why post e looks like spam (bought likes found, else its words), or null. Never for you, the people you follow, or
 *  someone you said isn't; guesses from words only for people nobody you follow follows. */
export function spamOf(e) {
  if (!trusted || trusted.has(e.pubkey) || notSpam.has(e.pubkey)) return null;
  return found.get(e.id) || (vouched && !vouched.has(e.pubkey) ? advert(e) : null);
}
/** Marks e (when it's a stranger's) with why; returns whether it's marked. */
export function mark(e, why) {
  if (!why || !trusted || trusted.has(e.pubkey) || notSpam.has(e.pubkey)) return false;
  found.set(e.id, why);
  return true;
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
/** The quiet amber cue on a post that looks like spam, the reason on a tap ("" when it doesn't). */
export function cue(e) {
  const why = spamOf(e);
  return why ? `<details class="spam-cue"><summary>Looks like spam · ${esc(why.short)}</summary><p>${esc(why.long)} Marble leaves posts like this out of search. People you follow are never marked.</p>
    <button type="button" class="link" data-not-spam="${esc(e.pubkey)}">Not spam</button></details>` : "";
}

// "Not spam", on a cue
if (typeof document !== "undefined") document.addEventListener("click", (ev) => {
  const b = ev.target.closest?.("[data-not-spam]");
  if (b) markNotSpam(b.dataset.notSpam);
});
