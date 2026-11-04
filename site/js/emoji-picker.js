// Reacting (the Marble reactions design, ~/igloo/marble-reactions.md; reactions.js has its rules): the tray, a small
// row that floats above the button: six emoji (your recent ones, then the defaults) and + for everything. Everything is
// Jumble's picker (github.com/CodyTseng/jumble, MIT): a search at the top, then Recent, your own emoji (NIP-30: your
// list, kind 10030, and the sets it names, 30030), and the usual sections, with a skin tone; on a phone, a sheet from
// the bottom. The emoji are emojibase's (MIT), trimmed into /emoji.json and fetched the first time + opens.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { esc } from "./render.js";
import { trayOf, keyOf, same } from "./reactions.js";

const TONE = "marble.skin-tone";
const load = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

// Recents: worked out from your own reactions (reactions.js), kept here for the next visit; what you pick goes first at
// once (before the relays have it).
let recents = [], recentsFor = null;
const unique = (list) => { const seen = new Set(); return list.filter((e) => { const k = keyOf(e); return !seen.has(k) && seen.add(k); }); };
/** Whose recents these are (a pubkey, or null): the ones kept from last time, until the relays say. */
export function recentsOf(pk) { recentsFor = pk; recents = pk ? load(`marble.recents.${pk}`, []) : []; }
/** Your recent emoji, most recent first, from your own reactions. */
export function setRecents(pk, list) { if (pk !== recentsFor) return; recents = unique(list).slice(0, 48); if (pk) save(`marble.recents.${pk}`, recents); }
/** An emoji you've just reacted with. */
export function used(e) { setRecents(recentsFor, [e, ...recents]); }
export const recent = (n = 24) => recents.slice(0, n);

// ── Your own emoji (NIP-30) ─────────────────────────────────────────────────────────────────────────

let mine = { standalone: [], packs: [] }, mineFor = null;
const emojiTags = (tags) => (tags || []).filter((t) => t[0] === "emoji" && /^[\w-]+$/.test(t[1] || "") && /^https:\/\//.test(t[2] || ""))
  .map((t) => ({ shortcode: t[1], url: t[2] }));
/** Looks up your emoji list and its packs (once per account). */
export async function loadMine(pk) {
  if (!pk) { mine = { standalone: [], packs: [] }; mineFor = null; return; }
  if (mineFor === pk) return;
  mineFor = pk;
  const lists = await req(relays(FAST, INDEXERS), { kinds: [10030], authors: [pk] }, { timeout: 3500, settle: 500 });
  const list = lists.sort((a, b) => b.created_at - a.created_at)[0];
  if (!list || mineFor !== pk) return;
  const refs = list.tags.filter((t) => t[0] === "a" && /^30030:[0-9a-f]{64}:/.test(t[1] || "")).map((t) => t[1].split(":")).slice(0, 20);
  const packs = refs.length ? await req(relays(FAST, INDEXERS), refs.map(([, author, d]) => ({ kinds: [30030], authors: [author], "#d": [d] })), { timeout: 3500, settle: 500 }) : [];
  if (mineFor !== pk) return;
  const newest = new Map();
  for (const p of packs) { const id = `${p.pubkey}:${p.tags.find((t) => t[0] === "d")?.[1] || ""}`; if (!newest.has(id) || newest.get(id).created_at < p.created_at) newest.set(id, p); }
  mine = {
    standalone: emojiTags(list.tags),
    packs: refs.map(([, a, d]) => newest.get(`${a}:${d}`)).filter(Boolean).map((p) => ({
      id: `${p.pubkey}:${p.tags.find((t) => t[0] === "d")[1]}`,
      title: p.tags.find((t) => t[0] === "title" || t[0] === "name")?.[1] || p.tags.find((t) => t[0] === "d")[1],
      emojis: emojiTags(p.tags),
    })).filter((p) => p.emojis.length),
  };
}

// ── Every emoji ─────────────────────────────────────────────────────────────────────────────────────

let data = null; // Promise<[{id, label, emoji: [[char, name, tags, skins?]…]}…]>
const allEmoji = () => (data ||= fetch("/emoji.json").then((r) => r.json()).then((j) => j.groups).catch(() => (data = null, [])));
const toned = (row, tone) => (tone && row[3] ? row[3].split(" ")[tone - 1] || row[0] : row[0]);
const TONES = ["✋", "✋🏻", "✋🏼", "✋🏽", "✋🏾", "✋🏿"];

const SEARCH = `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>`;
const PLUS = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>`;

/** An emoji drawn: a character, or someone's own as its picture. */
export function drawEmoji(e) {
  if (typeof e === "string") return `<span class="em">${esc(e)}</span>`;
  return `<img class="em" src="${esc(e.url)}" alt=":${esc(e.shortcode)}:" title=":${esc(e.shortcode)}:" loading="lazy" referrerpolicy="no-referrer" draggable="false">`;
}

// ── The tray and the picker ─────────────────────────────────────────────────────────────────────────

let box = null, picks = [], onPick = null, anchor = null, current = null;
const phone = () => matchMedia("(max-width: 640px), (pointer: coarse) and (max-width: 900px)").matches;
const cell = (e) => { picks.push(e); return `<button type="button" data-pick="${picks.length - 1}" aria-pressed="${same(e, current)}"${typeof e === "string" ? ` aria-label="${esc(e)}"` : ` title=":${esc(e.shortcode)}:" aria-label=":${esc(e.shortcode)}:"`}>${drawEmoji(e)}</button>`; };
const $ = (s, el = box) => el?.querySelector(s);

/** The tray by a reaction button: pick(emoji) gets what's chosen; yours (if any) is marked. */
export function openTray(at, pick, { yours = null, focus = true } = {}) {
  closePicker();
  anchor = at; onPick = pick; picks = []; current = yours;
  box = document.createElement("div");
  box.className = "picker-pop tray-pop";
  box.setAttribute("role", "menu");
  box.setAttribute("aria-label", "React");
  box.innerHTML = `<div class="tray">${trayOf(recents).map(cell).join("")}<button type="button" class="more-emoji" data-more aria-label="Every emoji">${PLUS}</button></div>`;
  document.body.append(box);
  place();
  if (focus) $("[data-pick]")?.focus({ preventScroll: true });
}
/** Everything: by the button on a computer, a sheet on a phone. */
function openFull() {
  const at = anchor, pick = onPick, yours = current;
  closePicker();
  anchor = at; onPick = pick; current = yours; picks = [];
  box = document.createElement("div");
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-label", "React");
  if (phone()) {
    box.className = "picker-sheet";
    box.innerHTML = `<div class="sheet-scrim" data-close></div><div class="sheet"><div class="sheet-grip"></div><div class="picker-full"></div></div>`;
  } else {
    box.className = "picker-pop wide";
    box.innerHTML = `<div class="picker-full"></div>`;
  }
  document.body.append(box);
  full($(".picker-full"));
  place();
}
export function closePicker() { box?.remove(); box = null; anchor = null; slid = null; }
export const pickerOpen = () => !!box;
export const trayOpenFor = (b) => !!box && anchor === b;
export const trayOnly = () => !!box?.classList.contains("tray-pop");
export const pointerInPicker = (el) => !!box && box.contains(el);

// Sliding (a press that opened the tray): the emoji under the finger lights up; letting go there picks it.
let slid = null;
/** The emoji at (x, y) in the tray, lit up (or none). */
export function slideTo(x, y) {
  if (!box) return;
  const b = document.elementFromPoint(x, y)?.closest?.(".tray [data-pick], .tray [data-more]");
  if (b === slid) return;
  slid?.classList.remove("lit");
  slid = b && box.contains(b) ? b : null;
  slid?.classList.add("lit");
}
/** Letting go: picks what's lit (true), or leaves the tray open (false). */
export function slideEnd() {
  const b = slid;
  slid?.classList.remove("lit"); slid = null;
  if (!b) return false;
  if (b.matches("[data-more]")) { openFull(); return true; }
  const e = picks[+b.dataset.pick], f = onPick;
  closePicker();
  f?.(e);
  return true;
}

function place() {
  if (!box || !anchor || !box.classList.contains("picker-pop")) return;
  const r = anchor.getBoundingClientRect(), w = box.offsetWidth, h = box.offsetHeight;
  box.style.left = `${Math.max(8, Math.min(innerWidth - w - 8, r.left + r.width / 2 - w / 2)) + scrollX}px`;
  const above = r.top - h - 8;
  box.style.top = `${(above >= 8 ? above : Math.min(r.bottom + 8, innerHeight - h - 8)) + scrollY}px`;
}

// Everything: the search, then Recent, your own emoji, and the sections; the skin tone.
let query = "", tone = 0;
async function full(where) {
  tone = load(TONE, 0);
  query = "";
  where.innerHTML = `<div class="picker"><div class="picker-head"><label class="picker-field">${SEARCH}<input type="search" placeholder="Search emoji" aria-label="Search emoji"></label></div>
    <div class="picker-grid"><p class="muted picker-empty">Loading…</p></div><div class="skin-tone" data-tones></div></div>`;
  tones();
  if (!phone()) $(".picker-field input")?.focus({ preventScroll: true });
  const groups = await allEmoji();
  if (!box) return;
  where.querySelector(".picker").groups = groups;
  grid();
}
function tones() {
  const t = $("[data-tones]");
  if (t) { t.hidden = !!query.trim(); t.innerHTML = `<button type="button" data-tone="${tone}" aria-label="Skin tone">${TONES[tone]}</button>`; }
}
function grid() {
  const g = $(".picker-grid"), groups = $(".picker")?.groups;
  if (!g || !groups) return;
  picks = [];
  const section = (label, items) => items.length ? `<section>${label ? `<h3>${esc(label)}</h3>` : ""}<div class="emoji-grid">${items.map(cell).join("")}</div></section>` : "";
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  let html;
  if (words.length) {
    const hits = groups.flatMap((gr) => gr.emoji).map((row) => [row, rank(row, words)]).filter((h) => h[1] < 9)
      .sort((x, y) => x[1] - y[1]).slice(0, 120).map(([row]) => toned(row, tone));
    const yours = unique([...mine.standalone, ...mine.packs.flatMap((p) => p.emojis)]).filter((e) => words.every((q) => e.shortcode.toLowerCase().includes(q)));
    html = section("Emoji", hits) + section("Your emoji", yours);
  } else {
    html = section("Recent", recent(24)) + section("Your emoji", mine.standalone) + mine.packs.map((p) => section(p.title, p.emojis)).join("")
      + groups.map((gr) => section(gr.label, gr.emoji.map((row) => toned(row, tone)))).join("");
  }
  g.innerHTML = html || `<p class="muted picker-empty">No emoji found</p>`;
  g.scrollTop = 0;
}

// How well a search names an emoji (lower is better; 9: not at all): its name exactly, its name's first word, a word of
// its name or a tag exactly, then a start of one. Every word typed has to start one of its words.
function rank([, name, tags], words) {
  const nameWords = name.split(/[\s:,]+/), tagWords = tags.split(" "), all = [...nameWords, ...tagWords];
  if (!words.every((q) => all.some((x) => x.startsWith(q)))) return 9;
  const q = words.join(" ");
  if (name === q) return 0;
  if (nameWords[0] === q) return 1;
  if (words.every((w) => nameWords.includes(w) || tagWords.includes(w))) return 2;
  return words.every((w) => nameWords.some((x) => x.startsWith(w))) ? 3 : 4;
}

// A press counts only if it stays put (the sheet moving or the grid scrolling under a finger isn't a pick), as Jumble's.
let press = null;
document.addEventListener("pointerdown", (ev) => {
  const b = ev.target.closest?.("[data-pick]");
  press = b && box?.contains(b) ? { b, x: ev.clientX, y: ev.clientY, top: b.getBoundingClientRect().top } : null;
}, true);
document.addEventListener("click", (ev) => {
  if (!box) return;
  const t = ev.target;
  const b = t.closest("[data-pick]");
  if (b && box.contains(b)) {
    if (ev.detail && press && (press.b !== b || Math.hypot(ev.clientX - press.x, ev.clientY - press.y) > 8 || Math.abs(b.getBoundingClientRect().top - press.top) > 2)) return;
    const e = picks[+b.dataset.pick], f = onPick;
    closePicker();
    return f?.(e);
  }
  if (t.closest("[data-more]") && box.contains(t)) return openFull();
  const tn = t.closest("[data-tone]");
  if (tn && box.contains(tn)) {
    const row = tn.parentElement;
    if (!row.classList.contains("open")) { row.classList.add("open"); row.innerHTML = TONES.map((x, i) => `<button type="button" data-tone="${i}" aria-pressed="${i === tone}">${x}</button>`).join(""); return; }
    tone = +tn.dataset.tone; save(TONE, tone); row.classList.remove("open"); tones(); grid(); return;
  }
  if (t.closest("[data-close]") || (!box.contains(t) && !t.closest("[data-react]"))) closePicker();
}, true);
document.addEventListener("input", (ev) => {
  if (box && ev.target.closest?.(".picker-field input")) { query = ev.target.value; tones(); grid(); }
});
document.addEventListener("keydown", (ev) => {
  if (!box) return;
  if (ev.key === "Escape") { const a = anchor; closePicker(); a?.focus(); return; }
  // arrows step through the tray
  const row = ev.target.closest?.(".tray");
  if (row && (ev.key === "ArrowRight" || ev.key === "ArrowLeft")) {
    const all = [...row.querySelectorAll("button")], i = all.indexOf(ev.target);
    all[(i + (ev.key === "ArrowRight" ? 1 : all.length - 1)) % all.length]?.focus();
    ev.preventDefault();
  }
});
addEventListener("resize", () => place());
