// Nostr apps and nsites, searched here in the page: apps.json (built once a day on Took by apps-index/build.ts and
// served through discover.syb.lol) is downloaded once and matched as you type, so nothing you type is sent anywhere.
// The same rules run on the phone (AppIndex.kt); android/app/src/test/resources/apps/vectors.json checks both.
//
// Everything in the file comes from strangers (names, descriptions, icons): it's drawn as text, links must be https.

// (a local copy when the page is served from this Mac: apps-index/public/apps.json copied to site/)
export const APPS_URL = typeof location !== "undefined" && /^(localhost|127\.0\.0\.1)$/.test(location.hostname) ? "/apps.json" : "https://discover.syb.lol/apps.json";

let loading = null;
/** The index, fetched once a visit (the browser's cache keeps it for an hour). Resolves to [] when it can't load. */
export function loadApps() {
  loading ??= fetch(APPS_URL, { mode: "cors", credentials: "omit", referrerPolicy: "no-referrer" })
    .then((r) => (r.ok ? r.json() : { apps: [] }))
    .then((d) => (d && d.v === 1 && Array.isArray(d.apps) ? d.apps.filter(valid) : []))
    .catch(() => { loading = null; return []; });
  return loading;
}

const https = (s) => typeof s === "string" && /^https:\/\/[^\s/?#@]+\.[^\s/?#@]+(\/|$)/i.test(s);
const valid = (a) => a && typeof a.name === "string" && a.name && https(a.url) && typeof a.pk === "string" && /^[0-9a-f]{64}$/.test(a.pk);

/** Lowercase letters and digits only, accents dropped ("noStrudel" -> "nostrudel", "Zap.Stream" -> "zapstream"). */
export const norm = (s) => String(s || "").toLowerCase().normalize("NFKD").replace(/[^a-z0-9]/g, "");
const words = (s) => String(s || "").toLowerCase().normalize("NFKD").split(/[^a-z0-9]+/).filter(Boolean);

/**
 * How well one app matches what was typed, 0 for not at all.
 * Name exact 100, name prefix 80, domain 75 ("jumble.social", "jumble"), every word starting a name word 60,
 * the name containing it (4 or more letters: "strudel") 50, every word found in the name, description, domain or
 * author 30.
 */
export function textScore(app, query) {
  const q = norm(query);
  if (q.length < 2) return 0;
  const n = norm(app.name);
  if (n === q) return 100;
  if (n.startsWith(q)) return 80;
  const domain = String(app.domain || "").toLowerCase();
  if (domain && (norm(domain) === q || norm(domain.split(".")[0]) === q || (q.length >= 4 && norm(domain).startsWith(q)))) return 75;
  const qw = words(query);
  if (!qw.length) return 0;
  const nw = words(app.name);
  if (qw.every((w) => nw.some((x) => x.startsWith(w)))) return 60;
  if (q.length >= 4 && n.includes(q)) return 50;
  const hay = new Set([...nw, ...words(app.about), ...words(domain), ...words(app.by)]);
  if (qw.every((w) => w.length >= 3 ? [...hay].some((x) => x.startsWith(w)) : hay.has(w))) return 30;
  return 0;
}

/**
 * Apps for a search, best first: text match, then the index's own score (official, recommended, followed by the
 * builder's network), then whether you follow the author. At most 3 from one author.
 * @param {object} o  {follows: Set<pubkey>, limit}
 */
export function searchApps(apps, query, { follows = new Set(), limit = 8 } = {}) {
  const hits = [];
  for (const a of apps) {
    const t = textScore(a, query);
    if (!t) continue;
    hits.push({ a, s: t + 0.3 * (Number(a.score) || 0) + (follows.has(a.pk) ? 20 : 0) });
  }
  hits.sort((x, y) => y.s - x.s || (x.a.name < y.a.name ? -1 : x.a.name > y.a.name ? 1 : 0));
  const per = new Map(), out = [];
  for (const h of hits) {
    const c = per.get(h.a.pk) || 0;
    if (c >= 3) continue;
    per.set(h.a.pk, c + 1);
    out.push(h.a);
    if (out.length >= limit) break;
  }
  return out;
}

/** The one line under an app's name: what it is, then who made it or who uses it. */
export function appLine(a, follows = new Set()) {
  const bits = [];
  if (a.kind === "nsite") bits.push(a.by ? `nsite by ${a.by}` : "nsite");
  else if (a.kind === "android") bits.push("Android app on Zapstore");
  else bits.push(a.domain || "");
  // (the index's own follower count is the builder's network, not yours, so it isn't shown as a number)
  if (follows.has(a.pk)) bits.push("You follow them");
  if (a.kind === "web" && a.zapstore) bits.push("Also on Android");
  return bits.filter(Boolean).join(" · ");
}
