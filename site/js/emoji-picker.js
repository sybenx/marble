// The reaction picker, as Jumble has it (github.com/CodyTseng/jumble, MIT): a row of quick reactions (a plain like, up
// to three you used lately, then 👍 ❤️ 😂 😢 👀 🧐) and "⋯" for every emoji: in sections under sticky names (Recently used
// first), a search, a skin tone, and your own emoji (NIP-30: your list, kind 10030, and the packs it names, 30030) as
// tabs. On a phone it's a sheet from the bottom: the quick row, then everything. The emoji are emojibase's (MIT),
// trimmed into /emoji.json and fetched the first time "⋯" opens.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { esc } from "./render.js";

export const QUICK = ["👍", "❤️", "😂", "😢", "👀", "🧐"];
export const LIKE = "+";

// An emoji: a character ("😂"), or one of someone's own ({shortcode, url}).
const keyOf = (e) => (typeof e === "string" ? `n:${e}` : `c:${e.shortcode}|${e.url}`);
const RECENT = "marble.recent-emoji", TONE = "marble.skin-tone";
const load = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

/** What you've reacted with lately, most recent first (as many as n). */
export function recent(n = 24) {
  return load(RECENT, []).filter((r) => r && r.e).sort((a, b) => b.at - a.at).slice(0, n).map((r) => r.e);
}
/** Remembers emoji as just used. */
export function used(emoji) {
  if (emoji === LIKE) return;
  const list = load(RECENT, []).filter((r) => r && r.e && keyOf(r.e) !== keyOf(emoji));
  save(RECENT, [{ e: emoji, at: Date.now() }, ...list].slice(0, 30));
}
/** Seeds "recently used" from what you've reacted with before (from your own reactions), when this browser has none. */
export function seedRecent(list) {
  if (load(RECENT, []).length || !list.length) return;
  save(RECENT, list.slice(0, 30).map((e, i) => ({ e, at: Date.now() - i })));
}

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

const HEART_FILLED = `<svg viewBox="0 0 24 24" aria-hidden="true" class="like-heart"><path d="M12 20.5s-7.5-4.6-9.3-9.5C1.4 7.4 3.7 4 7.2 4c2 0 3.6 1.1 4.8 2.8C13.2 5.1 14.8 4 16.8 4c3.5 0 5.8 3.4 4.5 7-1.8 4.9-9.3 9.5-9.3 9.5z"/></svg>`;
const SEARCH = `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>`;
const SMILE = `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><path d="M9 9h.01M15 9h.01"/></svg>`;
const STAR = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z"/></svg>`;
const BACK = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>`;

/** An emoji drawn: a character, the plain like as a heart, or someone's own as its picture. */
export function drawEmoji(e, { big = false } = {}) {
  if (e === LIKE) return HEART_FILLED;
  if (typeof e === "string") return `<span class="em">${esc(e)}</span>`;
  return `<img class="em${big ? " big" : ""}" src="${esc(e.url)}" alt=":${esc(e.shortcode)}:" title=":${esc(e.shortcode)}:" loading="lazy" referrerpolicy="no-referrer" draggable="false">`;
}

// ── The picker ──────────────────────────────────────────────────────────────────────────────────────

let box = null, picks = [], onPick = null, anchor = null;
const phone = () => matchMedia("(max-width: 640px), (pointer: coarse) and (max-width: 900px)").matches;
const cell = (e, cls = "") => { picks.push(e); return `<button type="button" class="${cls}" data-pick="${picks.length - 1}"${typeof e === "string" ? "" : ` title=":${esc(e.shortcode)}:"`}>${drawEmoji(e)}</button>`; };

/** The quick row's emoji: up to three you used lately (and not already there), then the six. */
export function quick() {
  const lately = recent(10).filter((e) => !QUICK.includes(e)).slice(0, 3);
  return [...lately, ...QUICK];
}

/** Opens the picker by a reaction button; pick(emoji) is called with what's chosen (LIKE for the plain like). */
export function openPicker(at, pick) {
  closePicker();
  anchor = at; onPick = pick; picks = [];
  box = document.createElement("div");
  box.className = phone() ? "picker-sheet" : "picker-pop";
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-label", "React");
  if (phone()) {
    box.innerHTML = `<div class="sheet-scrim" data-close></div><div class="sheet"><div class="sheet-grip"></div><h2 class="sheet-title">React</h2>${quickRow(false)}<div class="picker-full"></div></div>`;
    document.body.append(box);
    full($(".picker-full"));
  } else {
    box.innerHTML = quickRow(true);
    document.body.append(box);
    place();
  }
  $("[data-pick]", box)?.focus({ preventScroll: true });
}
export function closePicker() { box?.remove(); box = null; anchor = null; }
export const pickerOpen = () => !!box;

const $ = (s, el = box) => el?.querySelector(s);
const quickRow = (more) => `<div class="quick-row" role="menu">${cell(LIKE, "like")}${quick().map((e) => cell(e)).join("")}`
  + (more ? `<button type="button" class="more-emoji" data-more aria-label="All emoji"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg></button>` : "") + `</div>`;

function place() {
  if (!box || !anchor || !box.classList.contains("picker-pop")) return;
  const r = anchor.getBoundingClientRect(), w = box.offsetWidth, h = box.offsetHeight;
  box.style.left = `${Math.max(8, Math.min(innerWidth - w - 8, r.left + r.width / 2 - w / 2)) + scrollX}px`;
  const above = r.top - h - 8;
  box.style.top = `${(above >= 8 ? above : Math.min(r.bottom + 8, innerHeight - h - 8)) + scrollY}px`;
}

// Everything: tabs (search, emoji, yours, your packs), the sections, the skin tone.
let tab = "system", query = "", searching = false, tone = 0;
async function full(where) {
  tone = load(TONE, 0);
  tab = "system"; query = ""; searching = false;
  where.innerHTML = `<div class="picker"><div class="picker-head"></div><div class="picker-grid"><p class="muted picker-empty">Loading…</p></div>
    <div class="skin-tone" data-tones></div></div>`;
  head(); tones();
  const groups = await allEmoji();
  if (!box) return;
  groups.recent = recent(24);
  where.querySelector(".picker").groups = groups;
  grid();
}
function head() {
  const h = $(".picker-head");
  if (!h) return;
  if (searching) {
    h.innerHTML = `<div class="picker-search"><button type="button" class="tab" data-back aria-label="Back">${BACK}</button>
      <label class="picker-field">${SEARCH}<input type="search" placeholder="Search emojis" aria-label="Search emojis" value="${esc(query)}"></label></div>`;
    $(".picker-search input").focus();
    return;
  }
  h.innerHTML = `<div class="picker-tabs"><button type="button" class="tab" data-search title="Search emojis" aria-label="Search emojis">${SEARCH}</button>
    <button type="button" class="tab" data-tab="system" title="Smileys & Emotion" aria-pressed="${tab === "system"}">${SMILE}</button>
    ${mine.standalone.length ? `<button type="button" class="tab" data-tab="standalone" title="My emojis" aria-pressed="${tab === "standalone"}">${STAR}</button>` : ""}
    ${mine.packs.map((p) => `<button type="button" class="tab pack" data-tab="${esc(p.id)}" title="${esc(p.title)}" aria-pressed="${tab === p.id}">${drawEmoji(p.emojis[0])}</button>`).join("")}</div>`;
}
function tones() {
  const t = $("[data-tones]");
  if (t) { t.hidden = searching || tab !== "system"; t.innerHTML = `<button type="button" data-tone="${tone}" aria-label="Skin tone">${TONES[tone]}</button>`; }
}
function grid() {
  const g = $(".picker-grid"), groups = $(".picker")?.groups;
  if (!g || !groups) return;
  picks = picks.slice(0, $(".quick-row") ? $(".quick-row").querySelectorAll("[data-pick]").length : 0);
  const section = (label, items) => items.length ? `<section>${label ? `<h3>${esc(label)}</h3>` : ""}<div class="emoji-grid">${items.map((e) => cell(e)).join("")}</div></section>` : "";
  let html = "";
  if (searching) {
    const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    if (!words.length) html = section("Recently used", groups.recent);
    else {
      const hits = groups.flatMap((gr) => gr.emoji).map((row) => [row, rank(row, words)]).filter((h) => h[1] < 9)
        .sort((x, y) => x[1] - y[1]).slice(0, 120).map(([row]) => toned(row, tone));
      const yours = [...mine.standalone, ...mine.packs.flatMap((p) => p.emojis)].filter((e) => words.every((q) => e.shortcode.toLowerCase().includes(q)));
      html = section("Emoji", hits) + section("My emojis", yours);
    }
  } else if (tab === "system") {
    html = section("Recently used", groups.recent) + groups.map((gr) => section(gr.label, gr.emoji.map((row) => toned(row, tone)))).join("");
  } else if (tab === "standalone") html = section("My emojis", mine.standalone);
  else { const p = mine.packs.find((x) => x.id === tab); html = p ? section(p.title, p.emojis) : ""; }
  g.innerHTML = html || `<p class="muted picker-empty">No emojis found</p>`;
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
    used(e);
    closePicker();
    return f?.(e);
  }
  if (t.closest("[data-more]")) {
    box.className = "picker-pop wide";
    box.innerHTML = `<div class="picker-full"></div>`;
    picks = [];
    full($(".picker-full"));
    place();
    return;
  }
  if (t.closest("[data-search]")) { searching = true; head(); tones(); grid(); return; }
  if (t.closest("[data-back]")) { searching = false; query = ""; head(); tones(); grid(); return; }
  const tb = t.closest("[data-tab]");
  if (tb) { tab = tb.dataset.tab; head(); tones(); grid(); return; }
  const tn = t.closest("[data-tone]");
  if (tn) {
    const row = tn.parentElement;
    if (!row.classList.contains("open")) { row.classList.add("open"); row.innerHTML = TONES.map((x, i) => `<button type="button" data-tone="${i}" aria-pressed="${i === tone}">${x}</button>`).join(""); return; }
    tone = +tn.dataset.tone; save(TONE, tone); row.classList.remove("open"); tones(); grid(); return;
  }
  if (t.closest("[data-close]") || (!box.contains(t) && !t.closest("[data-react]"))) closePicker();
}, true);
document.addEventListener("input", (ev) => {
  if (box && ev.target.closest?.(".picker-search input")) { query = ev.target.value; grid(); }
});
document.addEventListener("keydown", (ev) => {
  if (!box) return;
  if (ev.key === "Escape") { const a = anchor; closePicker(); a?.focus(); return; }
  // arrows step through the quick row
  const row = ev.target.closest?.(".quick-row");
  if (row && (ev.key === "ArrowRight" || ev.key === "ArrowLeft")) {
    const all = [...row.querySelectorAll("button")], i = all.indexOf(ev.target);
    all[(i + (ev.key === "ArrowRight" ? 1 : all.length - 1)) % all.length]?.focus();
    ev.preventDefault();
  }
});
addEventListener("resize", () => place());
