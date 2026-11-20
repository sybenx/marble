// Reading an account's login-page pointer (Keykin SPEC 11.1): kind 10046 and its nostrconnect_url template, the old
// kind 30078 (d "keykin/login", a "url" tag holding an https origin) only when there is no 10046 at all, and who a typed
// name is. Every event is checked as it is. Moved from Marble Web's account-page.js (its rules; the dialog is in
// account-page.js here). No DOM: this imports in Node.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { schnorr, sha256 } from "./vendor/noble.js";
import { hooks } from "./hooks.js";
import { decode, isHex64, toHex, fromHex } from "./ids.js";
import { loginTemplate, legacyTemplate } from "./connect.js";

export const POINTER = 10046; // the pointer (Keykin SPEC §11.1); kind 30078 "keykin/login" is the old one, read during the move
export const LOGIN_D = "keykin/login";

/** An https origin and nothing else (no path, query, fragment, user or password), as the pointer must be. Or null. */
export function originOnly(url) {
  if (typeof url !== "string" || url.length > 200) return null;
  let u;
  try { u = new URL(url); } catch { return null; }
  // testing on localhost: a login page on this machine (http://localhost:port) counts too, and only then
  const local = typeof location !== "undefined" && /^(localhost|127\.0\.0\.1)$/.test(location.hostname) && u.protocol === "http:" && /^(localhost|127\.0\.0\.1)$/.test(u.hostname);
  if ((u.protocol !== "https:" && !local) || u.username || u.password || u.search || u.hash || (u.pathname !== "/" && u.pathname !== "")) return null;
  // what was written must be the origin itself, not something a parser tidied into one
  return url.replace(/\/$/, "") === u.origin ? u.origin : null;
}

/**
 * The whole check, every time: the id is the hash of exactly these fields, and the signature is the author's over it.
 * (pool.js's verify remembers its answer by id, which is right for showing posts but not for this: a copy with the same
 * id and another address must not pass, since the address is where someone is about to be sent.)
 */
function signedAsIs(e) {
  try {
    if (!isHex64(e.id) || !isHex64(e.pubkey) || typeof e.sig !== "string" || !/^[0-9a-f]{128}$/i.test(e.sig)) return false;
    if (!Number.isInteger(e.created_at) || typeof e.content !== "string") return false;
    const hash = toHex(sha256(new TextEncoder().encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));
    return hash === e.id && schnorr.verify(fromHex(e.sig), fromHex(e.id), fromHex(e.pubkey));
  } catch { return false; }
}

/** The login page an old pointer (kind 30078) names, if it's that account's own, signed, and a plain https origin. Or null. */
export function pageFrom(event, pubkey) {
  if (!event || event.kind !== 30078 || event.pubkey !== pubkey || !Array.isArray(event.tags)) return null;
  if (!event.tags.some((t) => t[0] === "d" && t[1] === LOGIN_D)) return null;
  if (!signedAsIs(event)) return null;
  return originOnly(event.tags.find((t) => t[0] === "url")?.[1]);
}

const mine = (pubkey, kind) => (e) => e?.pubkey === pubkey && e.kind === kind && Array.isArray(e.tags)
  && (kind !== 30078 || e.tags.some((t) => t[0] === "d" && t[1] === LOGIN_D)) && signedAsIs(e);
const newest = (list) => list.sort((a, b) => b.created_at - a.created_at)[0] || null;

/**
 * What the pointers found say (pure: the lookup's decision, tested in tools/). The newest signed 10046 wins: its
 * nostrconnect_url, checked, or none (removed: no falling back to the old kind). Only when there's no 10046 at all,
 * the newest 30078, as the template <url>/#connect=<nostrconnect>.
 * { url (the page's origin, shown before going there), template, event, legacy? } or { none: true, removed? }.
 */
export function pointerOf(found, pubkey) {
  const now = newest(found.filter(mine(pubkey, POINTER)));
  if (now) {
    const t = loginTemplate(now.tags.find((x) => x[0] === "nostrconnect_url")?.[1]);
    return t ? { url: t.origin, template: t.template, event: now } : { none: true, removed: true };
  }
  const old = newest(found.filter(mine(pubkey, 30078)));
  if (!old) return { none: true };
  const origin = pageFrom(old, pubkey);
  return origin ? { url: origin, template: legacyTemplate(origin), event: old, legacy: true } : { none: true, removed: true };
}

/**
 * Where this account's login page is: { url, template } (see pointerOf), { none: true } (no pointer, or the newest one
 * names nothing: it was taken away), or { unreachable: true } (no relay answered). The one lookup: where the address is
 * published may still change.
 */
export async function loginPage(pubkey, { timeout = 4500 } = {}) {
  const filters = [{ kinds: [POINTER], authors: [pubkey] }, { kinds: [30078], authors: [pubkey], "#d": [LOGIN_D] }];
  // the fast relays and the indexers at once, then the account's own write relays if those had no 10046
  let found = await req(relays(FAST, INDEXERS), filters, { timeout, settle: 700 });
  if (!found.some((e) => e.kind === POINTER)) {
    const own = await hooks.outbox(pubkey).catch(() => null);
    const more = relays(own?.write || own || []).filter((r) => !relays(FAST, INDEXERS).includes(r));
    if (more.length) found = found.concat(await req(more, filters, { timeout, settle: 700 }));
  }
  // newest wins, and only a signed one of this account's counts: a relay can't make one up
  return pointerOf(found, pubkey);
}

/** Who was typed: an npub or nprofile, a 64-character key, or a name@domain. The account's key, or null. */
export async function whose(typed) {
  const t = String(typed || "").trim().replace(/^nostr:/, "");
  if (!t) return null;
  if (isHex64(t)) return t.toLowerCase();
  if (/^n(pub|profile)1/.test(t)) { const d = decode(t); return d?.type === "profile" ? d.pubkey : null; }
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t) || /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(t)) { const r = await hooks.resolveNip05(t.toLowerCase()).catch(() => null); return isHex64(r?.pubkey) ? r.pubkey.toLowerCase() : null; }
  return null;
}
