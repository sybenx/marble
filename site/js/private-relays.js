// Relays only you use (your own archive), as Marble Android keeps them (PrivateRelays.kt): on your account as kind
// 30078 d=marble/private-relays, NIP-44 to yourself, {"v":1,"relays":[{"url","at","gone"}]}, merged per relay by the
// newest change; removed ones stay as markers so other devices learn it. No page, no network here.
import { norm } from "./relay-auth.js";

export const TAG = "marble/private-relays";

/** A typed address as stored: wss:// added if missing; null if it isn't a relay address. */
export function parse(input) {
  const t = String(input ?? "").trim();
  if (!t) return null;
  const n = norm(t.includes("://") ? t : `wss://${t}`);
  if (!n) return null;
  const host = n.slice(n.indexOf("://") + 3).split("/")[0];
  // (a host with a dot, or localhost: "hello" isn't an address)
  if (!host || /\s/.test(host) || !/^([a-z0-9-]+\.)+[a-z0-9-]{2,}(:\d+)?$|^localhost(:\d+)?$/.test(host)) return null;
  return n;
}

/** How to show it: the host and path, without the scheme ("archive.example.com"). */
export const label = (url) => (norm(url) || String(url)).replace(/^wss?:\/\//, "");

/** Null if it isn't a copy this version can read. */
export function fromJson(s) {
  let o;
  try { o = typeof s === "string" ? JSON.parse(s) : s; } catch { return null; }
  if (!o || typeof o !== "object" || Array.isArray(o)) return null;
  if ((o.v ?? 1) !== 1) return null;
  if (!Array.isArray(o.relays)) return [];
  const out = [];
  for (const x of o.relays) {
    const url = x && typeof x === "object" ? parse(x.url) : null;
    if (url) out.push({ url, at: Number(x.at) || 0, gone: x.gone === true });
  }
  return out;
}

export const toJson = (list) => JSON.stringify({ v: 1, relays: list.map((r) => ({ url: r.url, at: r.at, gone: !!r.gone })) });

/** Per relay, the newest change wins; sorted by address. */
export function merge(a, b) {
  const by = new Map();
  for (const r of [...(a || []), ...(b || [])]) { const had = by.get(r.url); if (!had || r.at > had.at) by.set(r.url, r); }
  return [...by.values()].sort((x, y) => (x.url < y.url ? -1 : x.url > y.url ? 1 : 0));
}

const key = (l) => l.map((r) => `${r.url} ${r.at} ${!!r.gone}`).sort().join("\n");
/** Whether `mine` holds something `theirs` hasn't (so the account's copy should be written). */
export const ahead = (theirs, mine) => key(merge(theirs, mine)) !== key(merge(theirs, []));

/** When the next change to `url` happens: now, or after its last change. */
export const nextAt = (list, url, now = Math.floor(Date.now() / 1000)) => Math.max(now, ((list || []).find((r) => r.url === url)?.at || 0) + 1);

/** What's asked of your archive: search over posts, articles and profiles; a #tag: posts with it. */
export const searchFilter = (text, tag = null) =>
  tag ? { kinds: [1, 30023], "#t": [tag], limit: 40 } : { kinds: [1, 30023, 0], search: text, limit: 40 };
