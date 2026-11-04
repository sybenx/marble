// Reader's shared rules (marble-content-fixtures/reader.md, fixtures/reader/): what a person's post is in Reader, its
// digest key, whether an item is read, and how two seen lists or two Reader lists merge. The same as Marble Android.
// No page, no network, so the fixtures can run it as it is.
import { sha256 } from "./vendor/noble.js";

const MEDIA = /\.(jpe?g|png|gif|webp|avif|mp4|webm|mov|m4v)(\?\S*)?$/i;
const URLS = /https?:\/\/[^\s<>"']+/gi;
// a Blossom picture or video (BUD-10), and a post or article pointed to (a quote): media and a link, as http ones
const BLOSSOM_MEDIA = /blossom:[0-9a-f]{64}\.(jpe?g|png|gif|webp|avif|mp4|webm|mov|m4v)\b/i;
const QUOTE = /(?:^|[^\w])(?:nostr:)?(nevent|note|naddr)1[02-9ac-hj-np-z]{6,}/i;

/** A person's post in Reader: "item" (an article, or a post with a link, media or a quote), "short", or "reply" (not shown). */
export function kindOf(e) {
  if (e.kind === 30023) return "item";
  const tags = e.tags || [];
  if (tags.some((t) => t[0] === "e" && (t[3] === "reply" || t[3] === "root" || t[3] === undefined || t[3] === ""))) return "reply";
  if (tags.some((t) => t[0] === "imeta" || t[0] === "q")) return "item";
  const text = String(e.content || "");
  return (text.match(URLS) || []).length || BLOSSOM_MEDIA.test(text) || QUOTE.test(text) ? "item" : "short";
}
export const isMedia = (url) => MEDIA.test(url);

/** The day a post falls on (local time, offset in seconds east of UTC). */
export const dayOf = (createdAt, offset = 0) => Math.floor((createdAt + offset) / 86400);
/** A digest row's key: one person's short posts for one day. */
export const digestKey = (pubkey, createdAt, offset = 0) => `p${pubkey.slice(0, 8)}:${dayOf(createdAt, offset)}`;

const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
/** A feed item's key: r + the first 16 hex of SHA-256 of its link (or its guid). */
export const feedItemKey = async (link, guid = "") => "r" + hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(link || guid))).slice(0, 16);
/** A Nostr post's key: its id's first 16 hex. */
export const postKey = (id) => id.slice(0, 16);
/** An article's (or any addressable event's) key: "a" + 16 hex of SHA-256 of kind:pubkey:d, so an edit is the same item. */
export const addressKey = (kind, pubkey, d) => "a" + hex(sha256(new TextEncoder().encode(`${kind}:${pubkey}:${d}`))).slice(0, 16);
/** An event's key: by its address when it has one (kinds 30000–39999), else by its id. */
export const eventKey = (e) => e.kind >= 30000 && e.kind < 40000 ? addressKey(e.kind, e.pubkey, e.tags.find((t) => t[0] === "d")?.[1] || "") : postKey(e.id);

const seenKeys = (seen) => new Set(Object.values(seen?.days || {}).flatMap((s) => String(s).split(" ").filter(Boolean)));
/** Whether an item ({key, source, at}) is read, by the seen list. */
export function isRead(item, seen, keys = seenKeys(seen)) {
  if (seen?.unread?.[item.key] > 0) return false; // (kept unread; a negative time: read again after that)
  return keys.has(item.key) || item.at <= (seen?.upto?.[item.source] ?? -1);
}
export { seenKeys };

/** Two seen lists as one: days joined, read up to the larger, unread the newer; unknown fields kept. */
export function mergeSeen(a, b) {
  const out = { ...b, ...a, v: 1, at: Math.max(a?.at || 0, b?.at || 0) };
  const days = {};
  for (const s of [a, b]) for (const [d, keys] of Object.entries(s?.days || {})) {
    const have = new Set((days[d] || "").split(" ").filter(Boolean));
    for (const k of String(keys).split(" ")) if (k) have.add(k);
    days[d] = [...have].join(" ");
  }
  const upto = { ...(b?.upto || {}) };
  for (const [k, t] of Object.entries(a?.upto || {})) upto[k] = Math.max(t, upto[k] ?? 0);
  // a Keep unread mark (t) or its clearing (-t): the later of the two wins
  const unread = { ...(b?.unread || {}) };
  for (const [k, t] of Object.entries(a?.unread || {})) if (unread[k] == null || Math.abs(t) > Math.abs(unread[k])) unread[k] = t;
  return { ...out, days, upto, unread };
}

/** Two Reader lists as one: per source, folder and mute, the newest `at` wins; unknown fields kept. */
export function mergeReader(a, b) {
  const by = (key, x, y) => {
    const m = new Map();
    for (const e of [...(y || []), ...(x || [])]) { const o = m.get(e[key]); if (!o || (e.at || 0) > (o.at || 0)) m.set(e[key], e); }
    return [...m.values()];
  };
  const people = (a?.people?.at || 0) >= (b?.people?.at || 0) ? a?.people : b?.people;
  return { ...b, ...a, v: 1, sources: by("id", a?.sources, b?.sources), folders: by("id", a?.folders, b?.folders),
    people: people || { on: true, at: 0 }, muted: by("source", a?.muted, b?.muted) };
}

// A discussion page: Hacker News, Lobsters, Reddit
const DISCUSSION = /^https?:\/\/(news\.ycombinator\.com\/item\?id=\d+|(www\.)?lobste\.rs\/s\/\w+|(www\.|old\.)?reddit\.com\/r\/[^/]+\/comments\/\w+)/i;
export const isDiscussion = (url) => DISCUSSION.test(url || "");
/**
 * Where a feed item's discussion is, or "" (none, or it's the item's own link): RSS 2.0 <comments>, Atom
 * <link rel="replies">, the guid or link when it's a discussion page, or a "Comments URL" in the description (hnrss).
 */
export function discussionOf({ link = "", comments = "", replies = "", guid = "", description = "" }) {
  const http = (u) => (/^https?:\/\//i.test(u || "") ? u.trim() : "");
  const said = /Comments URL:?\s*(?:<a[^>]+href=["']([^"']+)["']|(https?:\/\/\S+?))(?=["'<\s]|$)/i.exec(description || "");
  const found = http(comments) || http(replies) || [guid, link].map(http).find(isDiscussion) || http(said?.[1] || said?.[2]) || "";
  return found && found !== link ? found : "";
}

/**
 * The seen list kept small (NIP-44 holds at most 65,535 bytes): read-up-to times and Keep unread marks older than the
 * window (30 days) go, nothing that old shows; then while it's over maxBytes, the oldest day of keys goes.
 */
export function fitSeen(s, now, { window = 30 * 86400, maxBytes = 48000 } = {}) {
  const old = now - window;
  const out = { ...s, days: { ...(s.days || {}) }, upto: Object.fromEntries(Object.entries(s.upto || {}).filter(([, x]) => x > old)),
    unread: Object.fromEntries(Object.entries(s.unread || {}).filter(([, x]) => Math.abs(x) > old)) };
  const size = () => new TextEncoder().encode(JSON.stringify(out)).length;
  const days = Object.keys(out.days).sort((a, b) => a - b);
  while (size() > maxBytes && days.length > 1) delete out.days[days.shift()];
  return out;
}
