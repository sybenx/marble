// What a handle typed or pasted in the box might be, for rows under it (Enter still searches the web, as any search
// box does: "@elonmusk" is a search, not a mode). Only what can be confirmed cheaply gets a row:
//   name@domain.tld, @name@domain.tld  someone's Nostr address (NIP-05) and a fediverse account (WebFinger), asked at once
//   @name.bsky.social, @domain.tld     a Bluesky handle (its public profile)
//   @name                              someone you follow with that name. Nobody else: open search ranks lookalikes,
//                                      and a checked address only proves someone holds a domain ("@elonmusk" finds a
//                                      verified "Elonmusk · elonmusk85836@divine.video" with his photo)
// An email is neither of the first two, so it gets no row. Asking reveals the address to that site, so only an address
// that looks complete (a dot and an ending after the @) is asked about, once (kept for the visit), after typing stops.
import { resolveNip05, profile, profiles } from "./data.js";
import { searchPeople } from "./search.js";
import { normalizeName } from "./search-rank.js";
import { current, myFollows } from "./me.js";

const WAIT = 2500;
const DOMAIN = "((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})";
const ADDRESS = new RegExp(`^@?([a-z0-9._~+-]+)@${DOMAIN}$`, "i");
const HANDLE = new RegExp(`^@${DOMAIN}$`, "i");
const NAME = /^@([\p{L}\p{N}_]{2,30})$/u;
const LOCAL = /\.(local|localhost|internal|lan|home|arpa)$/; // a machine on your own network: never asked

/** {kind: "address", name, domain} | {kind: "bluesky", handle} | {kind: "name", name} | null */
export function handleOf(typed) {
  const t = String(typed || "").trim();
  let m = ADDRESS.exec(t);
  if (m) return LOCAL.test(m[2].toLowerCase()) ? null : { kind: "address", name: m[1].toLowerCase(), domain: m[2].toLowerCase() };
  if ((m = HANDLE.exec(t))) return LOCAL.test(m[1].toLowerCase()) ? null : { kind: "bluesky", handle: m[1].toLowerCase() };
  if ((m = NAME.exec(t))) return { kind: "name", name: m[1] };
  return null;
}
// (a name's answer depends on who's signed in: whom they follow)
export const keyOf = (h) => (h.kind === "address" ? `${h.name}@${h.domain}` : h.kind === "bluesky" ? `@${h.handle}` : `@${h.name} ${current() || ""}`.toLowerCase());

const asked = new Map(); // key → Promise<rows>
/**
 * The rows for a handle: [{kind: "person", pubkey, sub}] for someone on Nostr (opens here), [{kind: "web", label,
 * url, name, picture, sub}] for a profile elsewhere. Asked once a visit.
 */
export function lookUp(h) {
  const key = keyOf(h);
  if (!asked.has(key)) asked.set(key, find(h).then((rows) => rows.filter(Boolean), () => []));
  return asked.get(key);
}

async function find(h) {
  if (h.kind === "address") return Promise.all([nostrAddress(h), fediverse(h)]);
  if (h.kind === "bluesky") return [await bluesky(h.handle)];
  return [await nostrName(h.name)];
}

async function nostrAddress({ name, domain }) {
  const address = `${name}@${domain}`;
  const r = await resolveNip05(address, WAIT);
  if (!r) return null;
  // their name and picture with the row, not after it
  await Promise.race([profile(r.pubkey, r.relays), new Promise((ok) => setTimeout(ok, WAIT))]);
  return { kind: "person", pubkey: r.pubkey, sub: name === "_" ? domain : address };
}

// Someone you follow whose name, display name or address (before the @) is exactly this.
async function nostrName(name) {
  const follows = await myFollows();
  if (!follows.size) return null;
  const n = normalizeName(name), text = (s) => (typeof s === "string" ? s : "");
  const found = await searchPeople(name, { limit: 8, follows: [...follows], cached: profiles, signal: AbortSignal.timeout(4000) }).catch(() => []);
  const p = found.find((p) => follows.has(p.pubkey) && [p.display_name, p.name, text(p.nip05).split("@")[0]].some((x) => normalizeName(text(x)) === n));
  if (!p) return null;
  if (!profiles.has(p.pubkey)) profiles.set(p.pubkey, p); // the row shows their name and picture at once
  return { kind: "person", pubkey: p.pubkey, sub: text(p.nip05).replace(/^_@/, "") || `@${name}` };
}

// A fediverse account (Mastodon and the rest): WebFinger says where its page is; the server's NodeInfo says what it
// runs, and the account itself its name and picture, when they answer.
async function fediverse({ name, domain }) {
  const wf = await json(`https://${domain}/.well-known/webfinger?resource=${encodeURIComponent(`acct:${name}@${domain}`)}`);
  const links = Array.isArray(wf?.links) ? wf.links : [];
  const page = links.find((l) => l?.rel === "http://webfinger.net/rel/profile-page" && https(l.href))?.href;
  const self = links.find((l) => l?.rel === "self" && /activity\+json|ld\+json/.test(l.type || "") && https(l.href))?.href;
  if (!page && !self) return null;
  const [runs, actor] = await Promise.all([software(domain), self ? json(self, "application/activity+json") : null]);
  // many servers answer only signed requests for the account itself (mastodon.social): Mastodon's public lookup then
  // (also on GoToSocial and Akkoma); elsewhere the row has no name or picture
  const shown = actor || (await json(`https://${domain}/api/v1/accounts/lookup?acct=${encodeURIComponent(name)}`));
  const url = page || (https(shown?.url) ? shown.url : self);
  const picture = [shown?.icon].flat().map((i) => i?.url).concat(shown?.avatar).find(https) || null;
  return { kind: "web", label: `Open on ${runs || domain}`, url, name: plain(shown?.name || shown?.display_name) || null, picture, sub: `@${name}@${domain}` };
}
const SOFTWARE = { mastodon: "Mastodon", pleroma: "Pleroma", akkoma: "Akkoma", misskey: "Misskey", sharkey: "Sharkey", gotosocial: "GoToSocial",
  pixelfed: "Pixelfed", friendica: "Friendica", lemmy: "Lemmy", firefish: "Firefish", iceshrimp: "Iceshrimp", hometown: "Hometown", peertube: "PeerTube", threads: "Threads" };
async function software(domain) {
  const ni = await json(`https://${domain}/.well-known/nodeinfo`);
  const href = (Array.isArray(ni?.links) ? ni.links : []).map((l) => l?.href).filter(https).pop();
  const s = href ? String((await json(href))?.software?.name || "").toLowerCase() : "";
  return SOFTWARE[s] || (s ? s[0].toUpperCase() + s.slice(1) : null);
}

async function bluesky(handle) {
  const p = await json(`https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${encodeURIComponent(handle)}`);
  if (!p?.did || !p.handle || p.handle === "handle.invalid") return null;
  return { kind: "web", label: "Open on Bluesky", url: `https://bsky.app/profile/${encodeURIComponent(p.handle)}`, name: plain(p.displayName) || null,
    picture: https(p.avatar) ? p.avatar : null, sub: `@${p.handle}` };
}

const https = (u) => typeof u === "string" && /^https:\/\//.test(u);
const plain = (s) => (typeof s === "string" ? s.replace(/[\u0000-\u001f‎‏‪-‮⁦-⁩]/g, "").trim().slice(0, 80) : "");
async function json(url, accept = "application/json") {
  try {
    const r = await fetch(url, { headers: { accept }, credentials: "omit", referrerPolicy: "no-referrer", signal: AbortSignal.timeout(WAIT) });
    return r.ok ? await r.json() : null;
  } catch { return null; }
}
