// The pure rules of name-first sign-in: what a typed name is, an account's login-page template and the link made from
// it, the old kind's origin, and the key in a NIP-05 answer. Imports only ids.js (no relay pool, no crypto), so a page
// can load it alone and a server can use it as it is. connect.js and pointer.js re-export these, as before.
//
// `local`: whether a login page on this machine (http://localhost or http://127.0.0.1) counts. Left out, it counts only
// when this page itself runs on localhost (the page's location, as before). A server passes its own answer per request.
import { decode, isHex64 } from "./ids.js";

export const PLACEHOLDER = "<nostrconnect>";
const PLACE = PLACEHOLDER;
const LOCAL = /^(localhost|127\.0\.0\.1)$/;
const hereLocal = () => typeof location !== "undefined" && LOCAL.test(location.hostname);
// A code of the real shape, put in a template to see where it lands
const SAMPLE = "nostrconnect://" + "0".repeat(64) + "?relay=wss%3A%2F%2Fx&secret=1";

/** An https origin and nothing else (no path, query, fragment, user or password), as the old pointer must be. Or null. */
export function originOnly(url, local = hereLocal()) {
  if (typeof url !== "string" || url.length > 200) return null;
  let u;
  try { u = new URL(url); } catch { return null; }
  const isLocal = local && u.protocol === "http:" && LOCAL.test(u.hostname);
  if ((u.protocol !== "https:" && !isLocal) || u.username || u.password || u.search || u.hash || (u.pathname !== "/" && u.pathname !== "")) return null;
  // what was written must be the origin itself, not something a parser tidied into one
  return url.replace(/\/$/, "") === u.origin ? u.origin : null;
}

/**
 * An account's nostrconnect_url template, checked: https (http only on localhost, and only when local pages count), at
 * most 500 characters, no white space or control characters, no user or password, and exactly one <nostrconnect>, at
 * its very end. The code goes in the path, query or fragment, never into the address: filled with a real-shaped code,
 * the template must keep its origin, and the text before the placeholder must start with that origin, so the origin
 * shown is where the link goes. { template, origin }, or null.
 */
export function loginTemplate(t, local = hereLocal()) {
  if (typeof t !== "string" || t.length > 500 || /[\s\x00-\x1f\x7f]/.test(t)) return null;
  if (!t.endsWith(PLACE) || t.indexOf(PLACE) !== t.length - PLACE.length) return null;
  const head = t.slice(0, -PLACE.length);
  if (/[<>]/.test(head)) return null;
  let u, filled;
  try { u = new URL(head); filled = new URL(head + encodeURIComponent(SAMPLE)); } catch { return null; }
  const isLocal = local && u.protocol === "http:" && LOCAL.test(u.hostname);
  if ((u.protocol !== "https:" && !isLocal) || u.username || u.password || !u.hostname) return null;
  if (filled.origin !== u.origin || !head.toLowerCase().startsWith(u.origin.toLowerCase())) return null;
  return { template: t, origin: u.origin };
}

/** The old pointer (kind 30078) names only an origin: its page is <origin>/#connect=<nostrconnect>. */
export const legacyTemplate = (origin) => `${String(origin).replace(/\/$/, "")}/#connect=${PLACE}`;

/**
 * The login page's link: the template with its <nostrconnect> replaced by the code, URL-encoded once. Keykin's template
 * ends in #connect=<nostrconnect>, so connect= is last (§11.6). Open it with no `noreferrer`: the page checks where the
 * request came from by document.referrer.
 */
export const loginLink = (template, uri) => template.slice(0, -PLACE.length) + encodeURIComponent(uri);

/** The key in an npub or nprofile (all lower or all upper case), as lower case hex, or null. Never anything else. */
export function bech32Key(text) {
  if (typeof text !== "string" || text.length > 5000 || (text !== text.toLowerCase() && text !== text.toUpperCase())) return null;
  const d = decode(text);
  return d?.type === "profile" && isHex64(d.pubkey) ? d.pubkey.toLowerCase() : null;
}

const NAME = /^([a-z0-9._-]+)@([a-z0-9-]+(?:\.[a-z0-9-]+)+)$/i;
const DOMAIN = /^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/i;
/**
 * What was typed: { pubkey } for an npub, nprofile or 64 hex; { name, domain } for a name to look up by NIP-05 (a bare
 * domain is _@domain; NIP-05 names are a-z, 0-9, ".", "_", "-"), lower case; or null. Each may start with "nostr:" in
 * any case. At most 400 characters.
 */
export function typedName(typed) {
  const t = String(typed ?? "").trim().replace(/^nostr:/i, "");
  if (!t || t.length > 400) return null;
  if (isHex64(t)) return { pubkey: t.toLowerCase() };
  if (/^n(pub|profile)1/i.test(t)) { const pubkey = bech32Key(t); return pubkey ? { pubkey } : null; }
  const m = NAME.exec(t);
  if (m) return { name: m[1].toLowerCase(), domain: m[2].toLowerCase() };
  return DOMAIN.test(t) ? { name: "_", domain: t.toLowerCase() } : null;
}

/**
 * The key a NIP-05 answer (the body of https://<domain>/.well-known/nostr.json?name=<name>, parsed) gives for `name`:
 * the name as written, else in another case. 64 hex in either case, given back lower case. Or null.
 */
export function nip05Key(body, name) {
  const names = body && typeof body === "object" && body.names && typeof body.names === "object" ? body.names : {};
  const want = String(name ?? "");
  const key = Object.prototype.hasOwnProperty.call(names, want) ? names[want]
    : Object.entries(names).find(([k]) => k.toLowerCase() === want.toLowerCase())?.[1];
  return typeof key === "string" && isHex64(key) ? key.toLowerCase() : null;
}
