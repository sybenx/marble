// What the sign-in takes from the site around it: where someone posts, a name lookup, profiles, names and pictures.
// A site passes its own with configure({...}), so its caches stay shared (Marble Web passes data.js and render.js).
// With nothing passed, the small defaults below work alone. No DOM here: layer 1 imports this in Node too.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { npub } from "./ids.js";
import { nip05Key } from "./pure.js";

// ── Where someone posts and reads (their kind 10002), as Marble Web's data.js reads it ──
const outboxes = new Map(); // pubkey → Promise<{write, read}>
function readList(list) {
  const newest = list.sort((a, b) => b.created_at - a.created_at)[0];
  const write = [], read = [];
  for (const t of newest?.tags || []) {
    if (t[0] !== "r" || !t[1]) continue;
    if (t[2] !== "read") write.push(t[1]);
    if (t[2] !== "write") read.push(t[1]);
  }
  return { write: relays(write).slice(0, 4), read: relays(read).slice(0, 4) };
}
/** Where someone posts (write) and reads (read), from their kind 10002 list. */
function outbox(pk) {
  if (!outboxes.has(pk)) {
    outboxes.set(pk, req(relays(INDEXERS, FAST), { kinds: [10002], authors: [pk] }, { timeout: 2500, settle: 600 }).then(readList));
  }
  return outboxes.get(pk);
}

/** name@domain → pubkey (and the places it says to look), or null. As Marble Web's data.js. */
async function resolveNip05(address, timeout = 4000) {
  const [name, domain] = address.includes("@") ? address.split("@") : ["_", address];
  try {
    const ctrl = new AbortController();
    setTimeout(() => ctrl.abort(), timeout);
    const r = await fetch(`https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`, { signal: ctrl.signal, redirect: "error" });
    const data = await r.json();
    const pk = nip05Key(data, name); // pure.js: the name as written, else in another case; 64 hex, lower case
    if (!pk) return null;
    const relays = data?.relays && typeof data.relays === "object"
      ? Object.entries(data.relays).find(([k]) => k.toLowerCase() === pk)?.[1] : null;
    return { pubkey: pk, relays: Array.isArray(relays) ? relays.slice(0, 3) : [] };
  } catch { return null; }
}

// ── Profiles: a small store of its own (kind 0, newest wins), for the picture and name the dialog shows ──
const profiles = new Map(); // pubkey → {name, display_name, picture, created_at}
const listeners = new Set();
function keep(e) {
  if (e?.kind !== 0) return;
  let c;
  try { c = JSON.parse(e.content); } catch { return; }
  if (!c || typeof c !== "object") return;
  const old = profiles.get(e.pubkey);
  if (old && old.created_at >= e.created_at) return;
  const p = { name: String(c.name || ""), display_name: String(c.display_name || ""), picture: String(c.picture || ""), created_at: e.created_at };
  profiles.set(e.pubkey, p);
  for (const fn of listeners) fn(e.pubkey, p);
}
/** Someone's profile: what's known, or the first answer (null if nobody has it). A newer one is announced by onProfile. */
function profile(pk, hints = []) {
  if (profiles.has(pk)) return Promise.resolve(profiles.get(pk));
  return new Promise((resolve) => {
    req(relays(hints, FAST, INDEXERS), { kinds: [0], authors: [pk] }, { timeout: 3000, onEvent: (e) => { keep(e); resolve(profiles.get(pk) || null); } })
      .then(() => resolve(profiles.get(pk) || null));
  });
}
const profileOf = (pk) => profiles.get(pk);
/** fn(pubkey, profile) whenever someone's profile arrives or gets newer. Returns a function that stops it. */
const onProfile = (fn) => (listeners.add(fn), () => listeners.delete(fn));

// ── Drawing a person, as Marble Web's render.js does ──
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
function name(pk) {
  const p = hooks.profileOf(pk);
  return String(p?.display_name || p?.name || "").trim() || npub(pk).slice(0, 12) + "…";
}
function avatar(pk, size = 40) {
  const p = hooks.profileOf(pk);
  const n = hooks.name(pk);
  const initial = hooks.esc([...n.replace(/^npub1/, "")][0]?.toUpperCase() || "?");
  const pic = p?.picture && /^https:\/\//.test(p.picture) ? p.picture : "";
  return `<span class="av" data-av="${pk}" style="--s:${size}px" aria-hidden="true">${pic
    ? `<img src="${hooks.esc(pic)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">`
    : ""}<b>${initial}</b></span>`;
}

/**
 * What the sign-in calls. Read at call time, so configure() may come after the modules are imported.
 * outbox(pk) → Promise<{write: [urls], read: [urls]}>; resolveNip05("name@domain" or "domain") → Promise<{pubkey} | null>;
 * profile(pk) → Promise<profile | null>; profileOf(pk) → profile | undefined (what is known now; display_name, name, picture);
 * onProfile(fn) → a function that stops it; esc(text) → HTML-safe text; name(pk) → text; avatar(pk, size) → HTML.
 */
export const hooks = { outbox, resolveNip05, profile, profileOf, onProfile, esc, name, avatar };
export const defaults = Object.freeze({ ...hooks });

// The dialog listens for profiles from the moment it loads; a site's own onProfile, passed later, takes those over.
const watchers = new Map(); // fn → what stops it
/** fn(pubkey, profile) for every profile, through whichever onProfile is in place now or later. */
export function watchProfiles(fn) { watchers.set(fn, hooks.onProfile(fn)); }

/** A site's own hooks, by name (see hooks). Unknown names are an error, so a typo is not silently ignored. */
export function configure(given = {}) {
  for (const [k, v] of Object.entries(given)) {
    if (!(k in hooks)) throw new Error(`signin: no hook named ${k}`);
    if (typeof v !== "function") throw new Error(`signin: hook ${k} must be a function`);
  }
  const moved = "onProfile" in given && given.onProfile !== hooks.onProfile;
  Object.assign(hooks, given);
  if (moved) for (const [fn, stop] of watchers) { try { if (typeof stop === "function") stop(); } catch {} watchers.set(fn, hooks.onProfile(fn)); }
}
