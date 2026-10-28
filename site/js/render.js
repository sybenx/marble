// Turning a post's text into the page: everything escaped first, then links, pictures, videos, mentions
// of people (their names filled in when known), quoted posts (loaded after), #tags and custom emoji.
import { decode, npub, nevent, pathFor } from "./ids.js";
import { profiles, wantProfile } from "./data.js";

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const IMAGE = /\.(jpe?g|png|gif|webp|avif|svg)(\?.*)?$/i;
const VIDEO = /\.(mp4|webm|mov|m4v)(\?.*)?$/i;
const TOKEN = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]}])|(?:nostr:|@)?\b((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,})\b|(^|[\s(])#([\p{L}\p{N}_]{1,60})|:([\w-]{1,40}):/giu;

export function name(pk) {
  const p = profiles.get(pk);
  return String(p?.display_name || p?.name || "").trim() || npub(pk).slice(0, 12) + "…";
}

export function avatar(pk, size = 40) {
  const p = profiles.get(pk);
  const n = name(pk);
  const initial = esc([...n.replace(/^npub1/, "")][0]?.toUpperCase() || "?");
  const pic = p?.picture && /^https:\/\//.test(p.picture) ? p.picture : "";
  return `<span class="av" data-av="${pk}" style="--s:${size}px" aria-hidden="true">${pic
    ? `<img src="${esc(pic)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">`
    : ""}<b>${initial}</b></span>`;
}

const imeta = (e) => {
  const m = new Map();
  for (const t of e.tags || []) if (t[0] === "imeta") {
    const f = Object.fromEntries(t.slice(1).map((x) => [x.split(" ")[0], x.slice(x.indexOf(" ") + 1)]));
    if (f.url) m.set(f.url, f);
  }
  return m;
};

/** The post's text as HTML; media gathered under it, quoted posts as placeholders filled in later. */
export function content(e, { quotes = true } = {}) {
  const media = imeta(e);
  const emoji = new Map((e.tags || []).filter((t) => t[0] === "emoji" && /^https:\/\//.test(t[2] || "")).map((t) => [t[1], t[2]]));
  const text = String(e.content || "");
  const gallery = [];
  const quoted = [];
  let out = "", last = 0;
  for (const m of text.matchAll(TOKEN)) {
    out += esc(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1]) {
      const url = m[1];
      const type = media.get(url)?.m || "";
      if (IMAGE.test(url) || type.startsWith("image/")) { gallery.push(`<img src="${esc(url)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`); continue; }
      if (VIDEO.test(url) || type.startsWith("video/")) { gallery.push(`<video src="${esc(url)}" controls preload="none" playsinline></video>`); continue; }
      out += `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer nofollow">${esc(url.replace(/^https?:\/\/(www\.)?/, "").slice(0, 60))}${url.length > 70 ? "…" : ""}</a>`;
    } else if (m[2]) {
      const d = decode(m[2]);
      if (!d) { out += esc(m[0]); continue; }
      if (d.type === "profile") {
        wantProfile(d.pubkey, d.relays);
        out += `<a class="mention" href="${pathFor(m[2].toLowerCase(), d)}" data-name="${d.pubkey}">@${esc(name(d.pubkey))}</a>`;
      } else if (quotes && (d.type === "event" || d.type === "address") && !quoted.length) {
        // the first post or thing it points to: a card (content-rules.md §3); any later one, a short link
        quoted.push(m[2].toLowerCase());
        out += `<span class="quote-slot" data-quote="${esc(m[2].toLowerCase())}"></span>`;
      } else {
        out += shortLink(m[2].toLowerCase(), d);
      }
    } else if (m[4] && !/\p{L}/u.test(m[4])) {
      out += esc(m[0]); // (a tag has a letter: "#1" is just text)
    } else if (m[4]) {
      out += `${esc(m[3])}<a class="tag" href="/?q=${encodeURIComponent("#" + m[4])}">#${esc(m[4])}</a>`;
    } else if (m[5]) {
      const src = emoji.get(m[5]);
      out += src ? `<img class="emoji" src="${esc(src)}" alt=":${esc(m[5])}:" title=":${esc(m[5])}:" loading="lazy" referrerpolicy="no-referrer">` : esc(m[0]);
    }
  }
  out += esc(text.slice(last));
  out = out.trim().replace(/\n{3,}/g, "\n\n").replace(/\n/g, "<br>")
    .replace(/(?:<br>)+(<span class="quote-slot")/g, "$1").replace(/(<\/span>)(?:<br>)+(?=<span class="quote-slot"|$)/g, "$1")
    .replace(/(?:<br>\s*)+$/, "");
  if (gallery.length) out += `<div class="media${gallery.length > 1 ? " grid" : ""}">${gallery.join("")}</div>`;
  return out;
}

/** A post or thing a post points to, beyond the first (which is a card): "↪ quoted", or "↪ an article". */
export const shortLink = (entity, d) =>
  `<a class="ref" data-shortlink="${esc(entity)}" href="/${esc(entity)}">↪ ${d.type === "address" && d.kind === 30023 ? "an article" : "quoted"}</a>`;

// Long-form pieces (kind 30023) are Markdown: a small, careful subset, everything escaped first.
export function markdown(src) {
  const inline = (s) => esc(s)
    .replace(/!\[([^\]]*)\]\((https:\/\/[^)\s]+)\)/g, '<img src="$2" alt="$1" loading="lazy" referrerpolicy="no-referrer">')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer nofollow">$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<]+[^\s<.,;:!?)])/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer nofollow">$2</a>')
    .replace(/(?:nostr:)((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,})/g, (_, ent) => {
      const d = decode(ent);
      if (!d) return ent;
      if (d.type === "profile") { wantProfile(d.pubkey); return `<a class="mention" href="${pathFor(ent, d)}" data-name="${d.pubkey}">@${esc(name(d.pubkey))}</a>`; }
      return shortLink(ent, d);
    })
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*\w])\*([^*\s][^*]*)\*/g, "$1<em>$2</em>")
    .replace(/(^|\W)_([^_\s][^_]*)_(?=\W|$)/g, "$1<em>$2</em>");
  const lines = String(src || "").replace(/\r/g, "").split("\n");
  const out = [];
  let para = [], list = null, quote = [];
  const flush = () => {
    if (para.length) out.push(`<p>${inline(para.join(" "))}</p>`);
    if (list) out.push(`<${list.tag}>${list.items.map((i) => `<li>${inline(i)}</li>`).join("")}</${list.tag}>`);
    if (quote.length) out.push(`<blockquote>${inline(quote.join(" "))}</blockquote>`);
    para = []; list = null; quote = [];
  };
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (/^```/.test(l)) {
      flush();
      const code = [];
      while (++i < lines.length && !/^```/.test(lines[i])) code.push(lines[i]);
      out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`);
    } else if (/^#{1,6}\s/.test(l)) {
      flush();
      const level = Math.min(6, Math.max(2, l.match(/^#+/)[0].length + 1));
      out.push(`<h${level}>${inline(l.replace(/^#+\s*/, ""))}</h${level}>`);
    } else if (/^\s*([-*+]|\d+[.)])\s+/.test(l)) {
      if (para.length || quote.length) { const keep = list; list = null; flush(); list = keep; }
      const tag = /^\s*\d/.test(l) ? "ol" : "ul";
      if (!list || list.tag !== tag) { flush(); list = { tag, items: [] }; }
      list.items.push(l.replace(/^\s*([-*+]|\d+[.)])\s+/, ""));
    } else if (/^>\s?/.test(l)) {
      if (!quote.length) flush();
      quote.push(l.replace(/^>\s?/, ""));
    } else if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(l)) {
      flush(); out.push("<hr>");
    } else if (/^\s*nostr:(nevent|note|naddr)1[02-9ac-hj-np-z]{6,}\s*$/.test(l)) {
      // a post or article on a line of its own shows as a card, filled in after
      flush();
      out.push(`<span class="quote-slot" data-quote="${esc(l.trim().slice(6).toLowerCase())}"></span>`);
    } else if (!l.trim()) {
      flush();
    } else {
      if (list || quote.length) flush();
      para.push(l.trim());
    }
  }
  flush();
  return out.join("\n");
}

export function ago(ts) {
  const s = Math.max(0, Date.now() / 1000 - ts);
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  if (s < 7 * 86400) return `${Math.floor(s / 86400)}d`;
  const d = new Date(ts * 1000);
  return d.toLocaleDateString(undefined, { day: "numeric", month: "short", ...(d.getFullYear() !== new Date().getFullYear() ? { year: "numeric" } : {}) });
}
export const fullDate = (ts) => new Date(ts * 1000).toLocaleString(undefined, { dateStyle: "long", timeStyle: "short" });

/** The link to a post on this site, with where it was found and who wrote it, so it opens fast elsewhere too; it's
 *  also the link to share (marble.syb.lol/nevent1…, and /npub1… for a person). */
export const postPath = (e, found = []) => `/${nevent(e.id, { relays: found, author: e.pubkey })}`;
export const personPath = (pk) => `/${npub(pk)}`;
