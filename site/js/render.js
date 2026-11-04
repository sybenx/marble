// Turning a post's text into the page: everything escaped first, then links, pictures, videos, mentions
// of people (their names filled in when known), quoted posts (loaded after), #tags and custom emoji.
import { decode, npub, nevent, naddr, pathFor } from "./ids.js";
import { profiles, wantProfile } from "./data.js";
import { parseBlossom } from "./blossom.js";

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const IMAGE = /\.(jpe?g|png|gif|webp|avif|svg)(\?.*)?$/i;
const VIDEO = /\.(mp4|webm|mov|m4v)(\?.*)?$/i;
const TOKEN = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]}])|(?:nostr:|@)?\b((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,})\b|(^|[\s(])#([\p{L}\p{N}_]{1,60})|:([\w-]{1,40}):|(blossom:[0-9a-f]{64}\.[a-z0-9]{1,8}(?:\?[^\s<>"']*[^\s<>"'.,;:!?)\]}])?)/giu;

// Links to another Nostr viewer (njump.me, primal.net/e/…, nostr.at…) open the post or person here instead.
const VIEWER = /^https?:\/\/(?:www\.)?(?:njump\.me|nostr\.at|nostr\.com|snort\.social\/[ep]|primal\.net\/[epa]|iris\.to|coracle\.social)\/((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,})\/?$/i;
/** This site's path for a link to a post or person on another viewer ("https://njump.me/nevent1…" → "/nevent1…"), or null. */
export function inApp(url) {
  const m = VIEWER.exec(url || "");
  return m && decode(m[1]) ? `/${m[1].toLowerCase()}` : null;
}
const linkTo = (url, text) => {
  const here = inApp(url);
  return here ? `<a href="${esc(here)}" data-link="${esc(url)}">${text}</a>` : `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer nofollow">${text}</a>`;
};

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
  const links = []; // (the post's plain links, in order: the first, alone on its line or at the end, gets a preview)
  let out = "", last = 0;
  for (const m of text.matchAll(TOKEN)) {
    out += esc(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1]) {
      const url = m[1];
      const type = media.get(url)?.m || "";
      if (IMAGE.test(url) || type.startsWith("image/")) { gallery.push(`<img src="${esc(url)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`); continue; }
      if (VIDEO.test(url) || type.startsWith("video/")) { gallery.push(`<video src="${esc(url)}" controls preload="none" playsinline></video>`); continue; }
      out += linkTo(url, `${esc(url.replace(/^https?:\/\/(www\.)?/, "").slice(0, 60))}${url.length > 70 ? "…" : ""}`);
      links.push(url);
    } else if (m[6]) {
      // a Blossom link (BUD-10): a picture or video among the media, any other file a short link; found later (blossom.js)
      const b = parseBlossom(m[6]);
      if (!b) { out += esc(m[0]); continue; }
      if (b.kind === "image") { gallery.push(`<img data-blossom="${esc(m[6])}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`); continue; }
      if (b.kind === "video") { gallery.push(`<video data-blossom="${esc(m[6])}" controls preload="none" playsinline></video>`); continue; }
      out += `<a class="blossom-file finding" data-blossom="${esc(m[6])}">a .${esc(b.ext)} file</a>`;
    } else if (m[2]) {
      const d = decode(m[2]);
      if (!d) { out += esc(m[0]); continue; }
      if (d.type === "profile") {
        wantProfile(d.pubkey, d.relays);
        out += `<a class="mention" href="${pathFor(m[2].toLowerCase(), d)}" data-name="${d.pubkey}">@${esc(name(d.pubkey))}</a>`;
      } else if (quotes && (d.type === "event" || d.type === "address") && (!quoted.length || (d.type === "address" && d.kind === 30023))) {
        // the first post or thing it points to: a card (content-rules.md §3), and any article; any later one, a short link
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
  // quoted only by a q tag (NIP-18), nothing in the text: its card, after the text
  const q = quotes && !quoted.length ? (e.tags || []).find((t) => t[0] === "q" && t[1]) : null;
  const qRef = q && (/^[0-9a-f]{64}$/.test(q[1]) ? nevent(q[1], { relays: q[2] ? [q[2]] : [] })
    : /^\d+:[0-9a-f]{64}:/.test(q[1]) ? naddr({ kind: +q[1].split(":")[0], pubkey: q[1].split(":")[1], identifier: q[1].split(":").slice(2).join(":"), relays: q[2] ? [q[2]] : [] }) : null);
  if (qRef) out += `\n<span class="quote-slot" data-quote="${esc(qRef)}"></span>`;
  out = out.trim().replace(/\n{3,}/g, "\n\n").replace(/\n/g, "<br>")
    .replace(/(?:<br>)+(<span class="quote-slot")/g, "$1").replace(/(<\/span>)(?:<br>)+(?=<span class="quote-slot"|$)/g, "$1")
    .replace(/(?:<br>\s*)+$/, "");
  // three or more mentions together at the end (a list of people tagged): one line of small chips, the rest behind "+N"
  out = out.replace(/((?:(?:\s|<br>)*<a class="mention"[^>]*>[^<]*<\/a>){3,})(?:\s|<br>)*$/, (run) => {
    const chips = run.match(/<a class="mention"[^>]*>[^<]*<\/a>/g), shown = 4;
    return `<span class="mention-run">${chips.map((c, i) => i < shown ? c : c.replace("<a ", "<a hidden ")).join(" ")}${chips.length > shown ? ` <button type="button" class="more-people-chip" data-more-mentions>+${chips.length - shown}</button>` : ""}</span>`;
  });
  // a link alone on its line, or at the end: one preview card for the post's first such link, filled in after
  if (quotes && links.length && !gallery.length) {
    const first = links.find((u) => new RegExp(`(^|\\n)\\s*${u.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*(\\n|$)`).test(text) || text.trimEnd().endsWith(u));
    if (first && !inApp(first)) out += `<span class="link-preview" data-preview="${esc(first)}"></span>`;
  }
  if (gallery.length) out += `<div class="media${gallery.length > 1 ? " grid" : ""}">${gallery.join("")}</div>`;
  return out;
}

/** A post or thing a post points to, beyond the first (which is a card; articles always are): "↪ quoted", or
 *  "↪ an article" (in long-form, where references stay short). */
export const shortLink = (entity, d) =>
  `<a class="ref" data-shortlink="${esc(entity)}" href="/${esc(entity)}">↪ ${d.type === "address" && d.kind === 30023 ? "an article" : "quoted"}</a>`;

// Long-form pieces (kind 30023) are Markdown: a small, careful subset, everything escaped first.
export function markdown(src, { title = "" } = {}) {
  const unesc = (u) => u.replace(/&(amp|lt|gt|quot|#39);/g, (_, c) => ({ amp: "&", lt: "<", gt: ">", quot: '"', "#39": "'" })[c]);
  const inline = (s) => esc(s)
    .replace(/!\[([^\]]*)\]\((https:\/\/[^)\s]+)\)/g, '<img src="$2" alt="$1" loading="lazy" referrerpolicy="no-referrer">')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, (_, t, u) => linkTo(unesc(u), t))
    .replace(/\[([^\]]+)\]\(nostr:((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{6,})\)/g, (all, t, ent) => decode(ent) ? `<a href="/${ent.toLowerCase()}">${t}</a>` : all)
    .replace(/(^|[\s(])(https?:\/\/[^\s<]+[^\s<.,;:!?)])/g, (_, pre, u) => pre + linkTo(unesc(u), u))
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
  // a leading "# Title" that repeats the title above it: once is enough
  const same = (a, b) => a.replace(/[^\p{L}\p{N}]+/gu, " ").trim().toLowerCase() === b.replace(/[^\p{L}\p{N}]+/gu, " ").trim().toLowerCase();
  const firstLine = lines.findIndex((l) => l.trim());
  if (title && firstLine >= 0 && /^#{1,2}\s/.test(lines[firstLine]) && same(lines[firstLine].replace(/^#+\s*/, ""), title)) lines.splice(firstLine, 1);
  // GFM table cells: split on pipes that aren't escaped, without the outer ones
  const cells = (l) => l.trim().replace(/^\|/, "").replace(/(?<!\\)\|$/, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
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
    if (/^\s*\|.*\|/.test(l) && /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(lines[i + 1] || "")) {
      // a table (GFM): its own box that scrolls sideways when it's wider than the page, the first column staying put
      flush();
      const head = cells(l), rows = [];
      i++;
      while (i + 1 < lines.length && /^\s*\|/.test(lines[i + 1])) rows.push(cells(lines[++i]));
      out.push(`<div class="table-wrap"><table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead><tbody>${
        rows.map((r) => `<tr>${head.map((_, k) => `<td>${inline(r[k] || "")}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`);
    } else if (/^```/.test(l)) {
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
