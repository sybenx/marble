// Marble on the web: the front page's search, and the pages it opens (/e/ a post, /p/ a person,
// /a/ an article, /?q= a search). One page; links change the view without reloading, and going back
// shows the page as it was, at the same place.
import { decode, parseInput, pathFor, npub, note, isHex64 } from "./ids.js";
import { req, warm, foundOn, FAST, connectTimes } from "./pool.js";
import { keep, events, profiles, wantProfile, onProfile, profile, findEvent, findAddress, replies, postsBy, resolveNip05, verifyNip05 } from "./data.js";
import { searchPeople, followerCounts, SEARCH_RELAYS } from "./search.js";
import { goodPosts, stories, yours, loadStories, prepareMine } from "./find.js";
import { current as currentMe, onMe, canSignIn, signIn, signOut, myFollows } from "./me.js";
import { parseProfile } from "./search-rank.js";
import { esc, name, avatar, content, markdown, ago, fullDate, postPath, personPath } from "./render.js";

const $ = (s, el = document) => el.querySelector(s);
const view = $("#view");
const input = $("#q");
const form = $("#search");

// Timings, for checking that pasted links open fast: window.marbleTimings in the console.
const timings = (window.marbleTimings = []);
let navStart = 0; // when the current page was asked for (0: the browser's own navigation start)
function mark(what, extra = {}) {
  const ms = Math.round(performance.now() - navStart); // on the page (painted at the next frame)
  timings.push({ what, ms, ...extra });
  document.documentElement.dataset[what] = ms;
}

// ── Pages ────────────────────────────────────────────────────────────────────────────────────────────

const saved = new Map(); // history key → {node, y, title, page, q}: the page itself, kept as it was left
let current = null; // {key, ctrl}
let pageEl = null; // the current page, inside #view
history.scrollRestoration = "manual";

function go(path, { replace = false } = {}) {
  save();
  navStart = performance.now();
  const state = { key: Math.random().toString(36).slice(2) };
  if (replace) history.replaceState(state, "", path);
  else history.pushState(state, "", path);
  route(state);
}

function save() {
  if (!current || !history.state) return;
  saved.set(current.key, { node: pageEl, y: scrollY, title: document.title, page: document.body.dataset.page, q: input.value });
}

addEventListener("popstate", (ev) => {
  save();
  navStart = performance.now();
  route(ev.state || { key: "start" }, true);
});

function route(state, back = false) {
  current?.ctrl.abort();
  current = { key: state.key, ctrl: new AbortController() };
  const signal = current.ctrl.signal;
  for (const k of ["post", "profile", "posts", "people"]) delete document.documentElement.dataset[k];
  const prior = back && saved.get(state.key);
  const path = decodeURIComponent(location.pathname);
  const q = new URLSearchParams(location.search).get("q");

  if (prior) {
    document.body.dataset.page = prior.page;
    view.replaceChildren((pageEl = prior.node));
    document.title = prior.title;
    input.value = prior.q || "";
    requestAnimationFrame(() => scrollTo(0, prior.y));
    paintAll();
    return;
  }
  scrollTo(0, 0);
  view.replaceChildren((pageEl = document.createElement("div")));
  let m;
  if ((m = /^\/(e|p|a)\/([^/]+)\/?$/.exec(path)) || (m = /^\/()((?:note|nevent|npub|nprofile|naddr)1[^/]+)\/?$/.exec(path))) {
    const entity = m[2].replace(/^nostr:/, "");
    input.value = "";
    if (isHex64(entity)) return showEvent({ type: "event", id: entity.toLowerCase(), relays: [] }, signal, { hex: true });
    const d = decode(entity);
    if (!d) return showMissing("That link doesn't look right.");
    if (!m[1]) return go(pathFor(entity, d), { replace: true });
    if (d.type === "event") return showEvent(d, signal);
    if (d.type === "profile") return showProfile(d, signal);
    return showAddress(d, signal);
  }
  if (path === "/" || path === "/index.html") {
    if (q) { input.value = q; return showSearch(q, signal); }
    document.body.dataset.page = "home";
    document.title = "Marble";
    return;
  }
  showMissing("There's nothing here.");
}

function page(kind, title) {
  document.body.dataset.page = kind;
  document.title = title ? `${title} · Marble` : "Marble";
}

function showMissing(text, more = "") {
  page("view", "Not found");
  pageEl.innerHTML = `<div class="empty"><p>${esc(text)}</p>${more}<p><a href="/">Search Marble</a></p></div>`;
}

// ── Cards ────────────────────────────────────────────────────────────────────────────────────────────

const replyTo = (e) => {
  const es = (e.tags || []).filter((t) => t[0] === "e" && isHex64(t[1]));
  return (es.find((t) => t[3] === "reply") || es.find((t) => t[3] === "root") || (es.some((t) => t[3]) ? null : es[es.length - 1])) || null;
};
const rootOf = (e) => (e.tags || []).find((t) => t[0] === "e" && t[3] === "root") || null;
const warning = (e) => (e.tags || []).find((t) => t[0] === "content-warning");

function who(pk, e, { big = false } = {}) {
  wantProfile(pk);
  const p = profiles.get(pk);
  return `<div class="who">
    <a href="${personPath(pk)}" class="who-av">${avatar(pk, big ? 48 : 40)}</a>
    <div class="who-text">
      <a href="${personPath(pk)}" class="who-name" data-name="${pk}">${esc(name(pk))}</a>
      <span class="who-sub"><span data-nip05="${pk}">${nip05Label(pk, p)}</span>${e ? `<a href="${postPath(e, foundOn(e.id).slice(0, 2))}" class="when" title="${esc(fullDate(e.created_at))}">${ago(e.created_at)}</a>` : ""}</span>
    </div>
  </div>`;
}

const nip05Ok = new Map();
function nip05Label(pk, p) {
  const a = (p?.nip05 || "").replace(/^_@/, "");
  if (!a) return "";
  if (!nip05Ok.has(pk)) {
    nip05Ok.set(pk, null);
    verifyNip05(p.nip05, pk).then((ok) => { nip05Ok.set(pk, ok); paint(pk); });
  }
  const ok = nip05Ok.get(pk);
  if (ok === false) return "";
  return `<span class="addr${ok ? " ok" : ""}" title="${ok ? "Checked with " + esc(a.split("@").pop()) : ""}">${esc(a)}</span>`;
}

/** A post in a list: who, when, what; tapping it opens it. */
function card(e, { compact = false, context = true, why = "", clamp = false } = {}) {
  keep(e);
  if (e.kind === 6) return repostCard(e);
  const parent = context ? replyTo(e) : null;
  const cw = warning(e);
  const body = e.kind === 30023 ? articleTeaser(e) : `<div class="text${clamp ? " clamp" : ""}">${content(e)}</div>`;
  return `<article class="card${compact ? " compact" : ""}" data-href="${postPath(e, foundOn(e.id).slice(0, 2))}">
    ${why ? `<p class="why">${esc(why)}</p>` : ""}
    ${who(e.pubkey, e)}
    ${parent ? `<p class="context">Replying to <span class="reply-name" data-parent="${parent[1]}">a post</span></p>` : ""}
    ${cw ? `<details class="cw"><summary>${esc(cw[1] || "Sensitive")}: show</summary>${body}</details>` : body}
  </article>`;
}

function repostCard(e) {
  let inner = null;
  try { inner = JSON.parse(e.content); } catch {}
  const target = (e.tags.find((t) => t[0] === "e") || [])[1];
  wantProfile(e.pubkey);
  const head = `<p class="reposted"><span data-name="${e.pubkey}">${esc(name(e.pubkey))}</span> shared · ${ago(e.created_at)}</p>`;
  if (inner && inner.id && inner.sig) return head.replace("<p", '<div class="repost"><p') + card(inner, { context: false }) + "</div>";
  if (target) return `<div class="repost">${head}<span class="quote-slot" data-quote="${note(target)}"></span></div>`;
  return "";
}

function articleTeaser(e) {
  const tag = (k) => (e.tags.find((t) => t[0] === k) || [])[1] || "";
  const img = tag("image");
  return `<div class="teaser">${/^https:\/\//.test(img) ? `<img src="${esc(img)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : ""}
    <h3>${esc(tag("title") || "Untitled")}</h3>${tag("summary") ? `<p>${esc(tag("summary").slice(0, 240))}</p>` : ""}</div>`;
}

// Profiles that arrive later fill in every name, picture and address already on the page.
function paint(pk) {
  const p = profiles.get(pk);
  for (const el of document.querySelectorAll(`[data-name="${pk}"]`)) el.textContent = (el.classList.contains("mention") ? "@" : "") + name(pk);
  for (const el of document.querySelectorAll(`[data-av="${pk}"]`)) {
    const img = el.querySelector("img");
    if (img && img.getAttribute("src") === p?.picture) continue; // the same picture: leave it be (no reload, no flicker)
    el.outerHTML = avatar(pk, parseInt(el.style.getPropertyValue("--s")) || 40);
  }
  for (const el of document.querySelectorAll(`[data-nip05="${pk}"]`)) el.innerHTML = nip05Label(pk, p);
}
const paintAll = () => new Set([...document.querySelectorAll("[data-name],[data-av]")].map((el) => el.dataset.name || el.dataset.av)).forEach((pk) => profiles.has(pk) && paint(pk));
let paintQueue = new Set(), paintTimer = null;
onProfile((pk) => {
  paintQueue.add(pk);
  if (!paintTimer) paintTimer = requestAnimationFrame(() => { paintTimer = null; const q = paintQueue; paintQueue = new Set(); q.forEach(paint); });
});

// Quoted posts and "Replying to" names, filled in after the page is up.
function fillLater(root = pageEl) {
  for (const slot of root.querySelectorAll(".quote-slot:not([data-filled])")) {
    slot.dataset.filled = "1";
    const d = decode(slot.dataset.quote);
    if (!d) continue;
    slot.innerHTML = `<span class="quote loading"></span>`;
    (d.type === "address" ? findAddress(d) : findEvent(d.id, { relays: d.relays, author: d.pubkey, timeout: 5000 })).then((q) => {
      slot.innerHTML = q ? card(q, { compact: true, context: false }) : `<a class="quote missing" href="${pathFor(slot.dataset.quote, d)}">A post that couldn't be loaded</a>`;
      fillLater(slot);
    });
  }
  for (const el of root.querySelectorAll(".reply-name[data-parent]:not([data-filled])")) {
    el.dataset.filled = "1";
    const id = el.dataset.parent;
    findEvent(id, { timeout: 4000 }).then((p) => {
      if (!p) return;
      el.outerHTML = `<a href="${postPath(p)}" data-name="${p.pubkey}">${esc(name(p.pubkey))}</a>`;
      wantProfile(p.pubkey);
    });
  }
}

// ── A post ───────────────────────────────────────────────────────────────────────────────────────────

async function showEvent(d, signal, { hex = false } = {}) {
  page("view", "Post");
  const cached = events.get(d.id);
  if (!cached) pageEl.innerHTML = `<div class="thread"><article class="card main skeleton"><div class="who"><span class="av" style="--s:48px"></span><div class="who-text"><span class="bar w40"></span><span class="bar w20"></span></div></div><span class="bar"></span><span class="bar"></span><span class="bar w60"></span></article></div>`;
  // A bare 64-character id can be a post or a person: both are asked for, and whichever turns up first opens.
  const asPost = cached ? Promise.resolve(cached) : findEvent(d.id, { relays: d.relays, author: d.pubkey });
  const asPerson = hex ? profile(d.id) : Promise.resolve(null);
  const e = await Promise.race([asPost, asPerson.then((p) => p || asPost)]).then((x) => (x && x.kind === undefined ? null : x));
  if (signal.aborted) return;
  if (!e && hex && await asPerson && !signal.aborted) return go(personPath(d.id), { replace: true });
  if (!e) {
    return showMissing("Couldn't find this post.", "<p class=\"muted\">It may have been deleted, or the places that keep it aren't answering right now.</p>");
  }
  if (e.kind === 0) return go(personPath(e.pubkey), { replace: true });
  if (e.kind === 30023) return showArticle(e, signal);
  const found = foundOn(e.id);
  if (!/^\/e\/nevent1/.test(location.pathname)) history.replaceState(history.state, "", postPath(e, found.slice(0, 2)));

  const parent = replyTo(e), root = rootOf(e);
  pageEl.innerHTML = `<div class="thread">
    ${root && root[1] !== parent?.[1] ? `<div class="up" data-up="${root[1]}" data-hint="${esc(root[2] || "")}"></div><a class="gap" href="/e/${note(root[1])}">…</a>` : ""}
    ${parent ? `<div class="up" data-up="${parent[1]}" data-hint="${esc(parent[2] || "")}"></div>` : ""}
    ${mainPost(e)}
    <section class="replies" aria-label="Replies"><h2>Replies</h2><div class="list" id="replies"><p class="muted loading-text">Looking for replies…</p></div></section>
  </div>`;
  mark("post", { relay: found[0] || "kept", id: e.id.slice(0, 8), connect: connectTimes() });
  document.title = `${name(e.pubkey)}: “${(e.content || "").replace(/\s+/g, " ").slice(0, 60)}” · Marble`;
  fillLater();
  if (parent || root) for (const el of pageEl.querySelectorAll(".up[data-up]")) {
    findEvent(el.dataset.up, { relays: el.dataset.hint ? [el.dataset.hint] : [], timeout: 5000 }).then((p) => {
      if (signal.aborted) return;
      el.innerHTML = p ? card(p, { compact: true, context: false }) : "";
      fillLater(el);
    });
  }
  signal.addEventListener("abort", onProfile((pk) => {
    if (pk === e.pubkey) document.title = `${name(pk)}: “${(e.content || "").replace(/\s+/g, " ").slice(0, 60)}” · Marble`;
  }));

  // Replies stream in; each one goes under the post it answers, the author's own first, then oldest first.
  const list = $("#replies");
  const all = new Map();
  let timer = null;
  const draw = () => {
    timer = null;
    if (signal.aborted) return;
    const kids = new Map();
    for (const r of all.values()) {
      const to = replyTo(r)?.[1] || e.id;
      const at = all.has(to) || to === e.id ? to : e.id;
      if (!kids.has(at)) kids.set(at, []);
      kids.get(at).push(r);
    }
    const order = (a, b) => (b.pubkey === e.pubkey) - (a.pubkey === e.pubkey) || a.created_at - b.created_at;
    const tree = (id, depth) => (kids.get(id) || []).sort(order).map((r) =>
      `<div class="reply${depth ? " nested" : ""}">${card(r, { context: false })}${depth < 3 ? tree(r.id, depth + 1) : ""}</div>${depth >= 3 ? tree(r.id, depth) : ""}`).join("");
    list.innerHTML = tree(e.id, 0);
    fillLater(list);
  };
  replies(e, { hints: found, onEvent: (r) => {
    if (!(r.content || "").trim() || warning(r)?.[1]?.match(/spam/i)) return;
    all.set(r.id, r);
    if (!timer) timer = setTimeout(draw, all.size < 4 ? 0 : 120);
  } }).then(() => {
    if (signal.aborted) return;
    if (!all.size) list.innerHTML = `<p class="muted">No replies yet.</p>`;
    else draw();
  });
}

function mainPost(e) {
  const cw = warning(e);
  const body = `<div class="text big">${content(e)}</div>`;
  return `<article class="card main" data-id="${e.id}">
    ${who(e.pubkey, null, { big: true })}
    ${cw ? `<details class="cw"><summary>${esc(cw[1] || "Sensitive")}: show</summary>${body}</details>` : body}
    <div class="meta"><time datetime="${new Date(e.created_at * 1000).toISOString()}">${esc(fullDate(e.created_at))}</time>
    <button type="button" class="link" data-copy="${esc(location.origin + postPath(e, foundOn(e.id).slice(0, 2)))}">Copy link</button></div>
  </article>`;
}

// ── An article ───────────────────────────────────────────────────────────────────────────────────────

async function showAddress(d, signal) {
  page("view", "Article");
  pageEl.innerHTML = `<div class="thread"><article class="card main skeleton"><span class="bar w60"></span><span class="bar"></span><span class="bar"></span></article></div>`;
  const e = await findAddress(d);
  if (signal.aborted) return;
  if (!e) return showMissing("Couldn't find this.", "<p class=\"muted\">It may have been deleted, or the places that keep it aren't answering right now.</p>");
  if (e.kind === 30023 || e.kind === 30024) return showArticle(e, signal);
  pageEl.innerHTML = `<div class="thread">${mainPost(e)}</div>`;
  mark("post", { relay: foundOn(e.id)[0] });
  fillLater();
}

function showArticle(e, signal) {
  const tag = (k) => (e.tags.find((t) => t[0] === k) || [])[1] || "";
  const img = tag("image");
  const at = Number(tag("published_at")) || e.created_at;
  page("view", tag("title") || "Article");
  pageEl.innerHTML = `<article class="article">
    ${/^https:\/\//.test(img) ? `<img class="cover" src="${esc(img)}" alt="" referrerpolicy="no-referrer">` : ""}
    <h1>${esc(tag("title") || "Untitled")}</h1>
    ${who(e.pubkey, null, { big: true })}
    <p class="muted dated">${esc(fullDate(at))}</p>
    <div class="prose">${markdown(e.content)}</div>
    <div class="meta"><button type="button" class="link" data-copy="${esc(location.href)}">Copy link</button></div>
  </article>
  <section class="replies thread" aria-label="Replies"><h2>Replies</h2><div class="list" id="replies"><p class="muted loading-text">Looking for replies…</p></div></section>`;
  mark("post", { relay: foundOn(e.id)[0] });
  fillLater();
  const list = $("#replies"), got = [];
  replies(e, { onEvent: (r) => { got.push(r); } }).then(() => {
    if (signal.aborted) return;
    list.innerHTML = got.length ? got.sort((a, b) => a.created_at - b.created_at).map((r) => `<div class="reply">${card(r, { context: false })}</div>`).join("") : `<p class="muted">No replies yet.</p>`;
    fillLater(list);
  });
}

// ── A person ─────────────────────────────────────────────────────────────────────────────────────────

async function showProfile(d, signal) {
  const pk = d.pubkey;
  page("view", profiles.has(pk) ? name(pk) : "Profile");
  if (!/^\/p\/npub1/.test(location.pathname)) history.replaceState(history.state, "", personPath(pk));
  const draw = () => {
    const p = profiles.get(pk) || {};
    const banner = /^https:\/\//.test(p.banner || "") ? p.banner : "";
    const site = /^https?:\/\/\S+$/.test(p.website || "") ? p.website : "";
    return `<div class="person-head">
      <div class="banner">${banner ? `<img src="${esc(banner)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ""}</div>
      <div class="person-id">${avatar(pk, 88)}
        <div><h1 data-name="${pk}">${esc(name(pk))}</h1><p class="who-sub"><span data-nip05="${pk}">${nip05Label(pk, p)}</span></p></div>
      </div>
      ${p.about ? `<div class="about">${content({ content: p.about, tags: [] }, { quotes: false })}</div>` : ""}
      <p class="links">${site ? `<a href="${esc(site)}" target="_blank" rel="noopener noreferrer nofollow">${esc(site.replace(/^https?:\/\/(www\.)?/, "").replace(/\/$/, ""))}</a>` : ""}
      <button type="button" class="link" data-copy="${esc(location.origin + personPath(pk))}">Copy link</button></p>
    </div>`;
  };
  const root = pageEl;
  root.innerHTML = `<div class="person"><div id="person-head">${draw()}</div>
    <div class="tabs" role="tablist"><button type="button" role="tab" aria-selected="true" data-tab="posts">Posts</button><button type="button" role="tab" aria-selected="false" data-tab="all">Posts and replies</button></div>
    <div class="list" id="posts"><p class="muted loading-text">Loading posts…</p></div></div>`;
  const head = $("#person-head", root);
  if (!profiles.has(pk)) {
    profile(pk, d.relays).then((p) => {
      if (signal.aborted) return;
      if (!p) return;
      head.innerHTML = draw();
      page("view", name(pk));
      mark("profile");
    });
  } else mark("profile");

  signal.addEventListener("abort", onProfile((who2) => {
    if (who2 === pk) head.innerHTML = draw();
  }));

  const box = $("#posts", root);
  let shown = [], until = null, all = false, done = false;
  const render = () => {
    const list = shown.filter((e) => all || e.kind === 6 || !replyTo(e));
    box.innerHTML = (list.length ? list.map((e) => card(e)).join("") : `<p class="muted">${done ? "No posts yet." : "Loading posts…"}</p>`)
      + (done ? "" : `<button type="button" class="more" data-more>More</button>`);
    fillLater(box);
  };
  // Posts are drawn as they arrive (a few times a second at most), then once more when all are in.
  let drawTimer = null;
  const load = async () => {
    const ids = new Set(shown.map((e) => e.id));
    let fresh = 0;
    await postsBy(pk, { until, hints: d.relays, signal, onEvent: (e) => {
      if (ids.has(e.id)) return;
      ids.add(e.id);
      fresh++;
      shown.push(e);
      shown.sort((a, b) => b.created_at - a.created_at);
      if (!drawTimer) drawTimer = setTimeout(() => {
        drawTimer = null;
        if (signal.aborted) return;
        render();
        if (!document.documentElement.dataset.posts) mark("posts");
      }, 80);
    } });
    if (signal.aborted) return;
    clearTimeout(drawTimer);
    drawTimer = null;
    if (!fresh) done = true;
    until = shown.length ? shown[shown.length - 1].created_at - 1 : null;
    render();
    if (!all && !done && shown.filter((e) => e.kind === 6 || !replyTo(e)).length < 8 && shown.length < 200) load();
  };
  root.onclick = (ev) => {
    const tab = ev.target.closest("[data-tab]");
    if (tab) {
      all = tab.dataset.tab === "all";
      for (const b of root.querySelectorAll("[data-tab]")) b.setAttribute("aria-selected", b === tab);
      render();
    }
    if (ev.target.closest("[data-more]")) { ev.target.closest("[data-more]").textContent = "Loading…"; load(); }
  };
  load();
}

// ── Search ───────────────────────────────────────────────────────────────────────────────────────────

// A search: people, posts, #tag posts, stories, your things and the web, each only when it has results.
// Phones get the search mock's single column (the top three of each, then "See all"); desktops the
// desktop mock: posts and stories in one ranked list, your things after the third, people on the right.
async function showSearch(q, signal) {
  page("search", q);
  const parsed = parseInput(q);
  const tagOnly = /^#[\p{L}\p{N}_-]+$/u.test(q) ? q.slice(1) : null;
  const oneWord = !tagOnly && /^[\p{L}\p{N}_-]{3,40}$/u.test(q) ? q : null;
  const root = pageEl;
  const got = { people: [], extra: {}, peopleDone: !!tagOnly, posts: null, tagged: null, stories: tagOnly ? [] : null, yours: null, follows: new Set() };
  const opened = new Set(); // lists "See all" has opened, kept across redraws
  const wideQuery = matchMedia("(min-width: 1000px)");
  root.innerHTML = `<div class="results">
    <aside class="side" aria-label="People">${parsed.kind === "nip05" ? `<div class="list" id="addr"><p class="muted">Looking up ${esc(parsed.address)}…</p></div>` : ""}<div id="side"></div></aside>
    <div class="main" id="main"></div>
  </div>`;
  const side = $("#side", root), main = $("#main", root);
  const timers = {};
  const soon = (fn) => { if (!timers[fn.name]) timers[fn.name] = setTimeout(() => { timers[fn.name] = null; if (!signal.aborted && root.isConnected) fn(); }, 30); };
  const redraw = () => { soon(drawSide); soon(drawMain); };
  wideQuery.addEventListener("change", redraw);
  signal.addEventListener("abort", () => wideQuery.removeEventListener("change", redraw));
  root.onclick = (ev) => {
    const open = ev.target.closest("[data-open]");
    if (open) { opened.add(open.dataset.open); redraw(); }
  };

  // One section of the phone layout: its first three, "See all N" for the rest.
  const section = (id, title, items, draw) => !items?.length ? "" : `<section><h2>${title}</h2><div class="list">
    ${(opened.has(id) ? items : items.slice(0, 3)).map(draw).join("")}
    ${!opened.has(id) && items.length > 3 ? `<button type="button" class="more" data-open="${id}">See all ${items.length}</button>` : ""}</div></section>`;
  const personFor = (p) => personRow(p, { verified: got.extra.verified?.get(p.pubkey), followers: got.extra.followers?.get(p.pubkey), follow: got.follows.has(p.pubkey) });
  const postItem = (x) => card(x.e, { why: x.why, clamp: true });
  const web = `<a class="web" href="https://duckduckgo.com/?q=${encodeURIComponent(q)}" target="_blank" rel="noopener noreferrer">
    <span class="web-icon" aria-hidden="true">↗</span>
    <span><span class="web-title">Search the web for “${esc(q)}”</span><span class="web-sub">with DuckDuckGo</span></span></a>`;
  const waiting = () => got.posts === null ? `<p class="muted loading-text">Searching…</p>` : "";

  function drawSide() {
    const people = got.people;
    if (!people.length) { side.innerHTML = got.peopleDone ? "" : `<section><h2>People</h2><p class="muted loading-text">Searching…</p></section>`; return; }
    if (!wideQuery.matches) { side.innerHTML = section("people", "People", people, personFor); fillLater(side); return; }
    const [top, ...rest] = people;
    const more = opened.has("people") ? rest : rest.slice(0, 7);
    side.innerHTML = `<section><h2>People</h2>${topPerson(top, got)}</section>
      ${more.length ? `<section class="more-people"><h2>More people</h2>${more.map((p) => personLine(p, got)).join("")}
      ${!opened.has("people") && rest.length > more.length ? `<button type="button" class="see-all" data-open="people">See all ${people.length} people</button>` : ""}</section>` : ""}`;
  }

  function drawMain() {
    const posts = got.posts || [], tagged = got.tagged || [], stories = got.stories || [], yours = got.yours || [];
    if (!wideQuery.matches) {
      const shown = new Set(posts.slice(0, 3).map((x) => x.e.id));
      main.innerHTML = waiting()
        + section("posts", tagOnly ? esc(q) : "Posts", posts, postItem)
        + section("tag", `#${esc(oneWord || "")}`, tagged.filter((x) => !shown.has(x.e.id)), postItem)
        + section("stories", "Stories", stories, storyRow)
        + section("yours", "Your things", yours, postItem)
        + (got.posts === null ? "" : web);
      fillLater(main);
      return;
    }
    // Posts and stories in one list by relevance: a post's score against the best post's, a story's title
    // match (a summary-only match counts for less) times freshness; never more than two of a kind in a row.
    const pool = new Map();
    for (const x of [...posts, ...tagged]) if (![...pool.values()].some((y) => y.e.pubkey === x.e.pubkey)) pool.set(x.e.id, x);
    const best = Math.max(...[...pool.values()].map((x) => x.score), 1e-9);
    const left = [...[...pool.values()].map((x) => ({ type: "post", x, rel: x.score / best })),
      ...stories.map((i) => ({ type: "story", i, rel: (i.inTitle ? 0.95 : 0.5) * Math.pow(0.5, (Date.now() / 1000 - i.published_at) / 86400 / 30) }))]
      .sort((a, b) => b.rel - a.rel);
    const limit = opened.has("main") ? 40 : 12, list = [];
    while (left.length && list.length < limit) {
      const run = list.length >= 2 && list.at(-1).type === list.at(-2).type ? list.at(-1).type : null;
      const i = run ? left.findIndex((r) => r.type !== run) : 0;
      list.push(left.splice(i < 0 ? 0 : i, 1)[0]);
    }
    const n = (k, one, many) => `${k} ${k === 1 ? one : many}`;
    const parts = [n(pool.size, "post", "posts"), stories.length && n(stories.length, "story", "stories"), got.people.length && n(got.people.length, "person", "people")].filter(Boolean);
    const found = parts.length > 1 ? `${parts.slice(0, -1).join(", ")} and ${parts.at(-1)}` : parts[0];
    const count = got.posts === null || !list.length ? "" : `<p class="count">${found} for “${esc(q)}”. Showing the best ${list.length}.</p>`;
    const mine = yours.length ? `<div class="yours-box"><h3>From your things</h3>${yours.slice(0, 3).map(yourLine).join("")}</div>` : "";
    main.innerHTML = count + waiting()
      + list.map((r, i) => (i === 3 ? mine : "") + (r.type === "post" ? postResult(r.x) : storyResult(r.i))).join("")
      + (list.length <= 3 ? mine : "")
      + (left.length && !opened.has("main") ? `<button type="button" class="more" data-open="main">More results</button>` : "")
      + (got.posts === null ? "" : web);
    fillLater(main);
  }
  redraw();
  signal.addEventListener("abort", onProfile((pk) => { if (pk === got.people[0]?.pubkey) soon(drawSide); }));

  if (parsed.kind === "nip05") resolveNip05(parsed.address).then(async (r) => {
    const box = $("#addr", root);
    if (signal.aborted || !box) return;
    if (r) {
      wantProfile(r.pubkey, r.relays);
      box.innerHTML = personRow({ pubkey: r.pubkey, ...(profiles.get(r.pubkey) || {}) }, { verified: true });
      return;
    }
    // Some sites (primal.net among them) can't be asked from a browser. Then: everyone who says they're
    // that address, the most followed first, unchecked.
    const said = (await req(SEARCH_RELAYS, { kinds: [0], search: parsed.address, limit: 30 }, { timeout: 3000, settle: 600 }))
      .map(parseProfile).filter((p) => p && (p.nip05 || "").toLowerCase() === parsed.address);
    const counts = said.length ? await followerCounts(said.map((p) => p.pubkey)) : new Map();
    if (signal.aborted) return;
    said.sort((a, b) => (counts.get(b.pubkey) || 0) - (counts.get(a.pubkey) || 0));
    for (const p of said) if (!profiles.has(p.pubkey)) profiles.set(p.pubkey, p);
    const site = esc(parsed.address.split("@")[1]);
    box.innerHTML = said.length
      ? `<p class="muted note">${site} didn't answer, so these are the accounts that say they're ${esc(parsed.address)}, the most followed first.</p>`
        + said.slice(0, 3).map((p) => personRow(p, { followers: counts.get(p.pubkey) })).join("")
      : `<p class="muted">Nobody found at ${esc(parsed.address)}.</p>`;
  });

  const me = currentMe();
  // Your follows (read once a visit, usually in by now): at most a short wait before people are ranked.
  const followsSoon = Promise.race([myFollows(), new Promise((r) => setTimeout(() => r(new Set()), 1500))]);
  followsSoon.then((f) => { got.follows = f; });

  if (!tagOnly) followsSoon.then((follows) => {
    if (signal.aborted) return;
    searchPeople(q, { limit: 10, follows: [...follows], cached: profiles, signal, onResults: (list, x) => {
      for (const p of list) if (!profiles.has(p.pubkey)) profiles.set(p.pubkey, p);
      got.people = list; got.extra = x;
      soon(drawSide); soon(drawMain);
      if (list.length && !document.documentElement.dataset.people) mark("people");
    } }).then(() => { if (!signal.aborted) { got.peopleDone = true; got.extra.ranked = true; soon(drawSide); } });
  });

  goodPosts(q, { follows: followsSoon, me, tag: tagOnly, signal }).then((list) => {
    got.posts = list;
    soon(drawMain);
    if (list.length) mark("posts", goodPosts.last);
  });
  if (oneWord) goodPosts(q, { follows: followsSoon, me, tag: oneWord, signal }).then((list) => { got.tagged = list; soon(drawMain); });
  if (!tagOnly) stories(q).then((list) => {
    got.stories = list;
    soon(drawMain);
    if (list.length) mark("stories");
  });
  if (me) yours(q, me, { signal }).then((list) => { got.yours = list; soon(drawMain); });
}

const agoLong = (ts) => {
  const h = (Date.now() / 1000 - ts) / 3600;
  return h < 1 ? `${Math.max(1, Math.round(h * 60))} min ago` : h < 48 ? `${Math.round(h)} h ago` : h < 24 * 60 ? `${Math.round(h / 24)} days ago` : `${Math.round(h / 24 / 30)} months ago`;
};

// Desktop results, as the desktop mock lays them out: plain, no cards.
function postResult(x) {
  const e = x.e;
  wantProfile(e.pubkey);
  const title = e.kind === 30023 ? (e.tags.find((t) => t[0] === "title") || [])[1] : "";
  return `<article class="res" data-href="${postPath(e, foundOn(e.id).slice(0, 2))}">
    <p class="res-why"><b>Post</b>${x.why ? ` · ${esc(x.why)}` : ""}</p>
    <div class="res-who">${avatar(e.pubkey, 28)}<a href="${personPath(e.pubkey)}" data-name="${e.pubkey}">${esc(name(e.pubkey))}</a><span>${agoLong(e.created_at)}</span></div>
    ${title ? `<p class="res-title">${esc(title)}</p>` : ""}
    <div class="text clamp4">${e.kind === 30023 ? esc((e.tags.find((t) => t[0] === "summary") || [])[1] || "") : content(e, { quotes: false })}</div>
  </article>`;
}

function storyResult(i) {
  const img = /^https:\/\//.test(i.image || "") ? `<img src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : "";
  const src = i.source?.name || "";
  return `<div class="res story-res"><div class="res-body">
    <p class="res-why"><b>Story</b> · ${esc(i.topic)}</p>
    <div class="res-src"><span class="dot" aria-hidden="true">${esc(src.replace(/^r\//, "").slice(0, 1).toUpperCase())}</span><span>${esc(src)}<small>${agoLong(i.published_at)}</small></span></div>
    <a class="res-title" href="${esc(i.url)}" target="_blank" rel="noopener noreferrer">${esc(i.title)}</a>
    ${i.summary ? `<p class="snip">${esc(i.summary)}</p>` : ""}
  </div>${img}</div>`;
}

function yourLine(x) {
  const e = x.e;
  const text = (e.kind === 30023 ? (e.tags.find((t) => t[0] === "title") || [])[1] : e.content || "").replace(/nostr:\S+|https?:\/\/\S+/g, "").replace(/\s+/g, " ").trim();
  return `<a class="it" href="${postPath(e)}"><span class="k">${esc(x.why)} · ${agoLong(e.created_at)}${x.why !== "You wrote" ? ` · <span data-name="${e.pubkey}">${esc(name(e.pubkey))}</span>` : ""}</span><span class="t">${esc(text.slice(0, 200))}</span></a>`;
}

function whyPerson(p, got) {
  if (got.follows.has(p.pubkey)) return "You follow them";
  const n = got.extra.followers?.get(p.pubkey);
  return n ? `${n >= 10000 ? Math.round(n / 1000) + "k" : n >= 1000 ? (n / 1000).toFixed(1) + "k" : n} followers` : "";
}

function topPerson(p, got) {
  wantProfile(p.pubkey);
  const full = profiles.get(p.pubkey) || p;
  const banner = /^https:\/\//.test(full.banner || "") ? `<img src="${esc(full.banner)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : "";
  const ok = got.extra.verified?.get(p.pubkey);
  const addr = (p.nip05 || "").replace(/^_@/, "");
  const why = whyPerson(p, got);
  return `<a class="pcard" href="${personPath(p.pubkey)}">
    <span class="pcard-banner">${banner}</span>
    <span class="pcard-in">${avatar(p.pubkey, 72)}
      <span class="pcard-name" data-name="${p.pubkey}">${esc(name(p.pubkey))}</span>
      ${addr && ok !== false ? `<span class="addr${ok ? " ok" : ""}">${esc(addr)}</span>` : ""}
      ${why ? `<span class="pcard-why">${esc(why)}</span>` : ""}
      ${p.about ? `<span class="pcard-about">${esc(p.about.replace(/\s+/g, " ").slice(0, 220))}</span>` : ""}
    </span></a>`;
}

function personLine(p, got) {
  const ok = got.extra.verified?.get(p.pubkey);
  const sub = whyPerson(p, got) || (ok ? (p.nip05 || "").replace(/^_@/, "") : "") || (p.about || "").replace(/\s+/g, " ").slice(0, 80);
  return `<a class="pline" href="${personPath(p.pubkey)}">${avatar(p.pubkey, 38)}<span><b data-name="${p.pubkey}">${esc(name(p.pubkey))}</b><small>${esc(sub)}</small></span></a>`;
}

function storyRow(i) {
  const img = /^https:\/\//.test(i.image || "") ? `<img src="${esc(i.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : "";
  return `<a class="story" href="${esc(i.url)}" target="_blank" rel="noopener noreferrer">
    <span class="story-text"><span class="story-src"><b>${esc(i.topic)}</b> · ${esc(i.source?.name || "")} · ${ago(i.published_at)}</span>
    <span class="story-title">${esc(i.title)}</span></span>${img}
  </a>`;
}

function personRow(p, { verified, followers, follow = false } = {}) {
  const pk = p.pubkey;
  const addr = (p.nip05 || "").replace(/^_@/, "");
  const count = follow ? "You follow them" : followers ? `${followers >= 10000 ? Math.round(followers / 1000) + "k" : followers >= 1000 ? (followers / 1000).toFixed(1) + "k" : followers} followers` : "";
  return `<a class="person-row" href="${personPath(pk)}">
    ${avatar(pk, 44)}
    <span class="pr-text">
      <span class="pr-name" data-name="${pk}">${esc(name(pk))}</span>
      <span class="pr-sub">${addr && verified !== false ? `<span class="addr${verified ? " ok" : ""}">${esc(addr)}</span>` : ""}${count ? `<span>${count}</span>` : ""}</span>
      ${p.about ? `<span class="pr-about">${esc(p.about.replace(/\s+/g, " ").slice(0, 140))}</span>` : ""}
    </span>
  </a>`;
}

// ── The search box ───────────────────────────────────────────────────────────────────────────────────

let typing = null;
let warmed = false;
const warmUp = () => { if (!warmed) { warmed = true; warm(FAST); warm(SEARCH_RELAYS); warm(["wss://cache2.primal.net/v1"]); loadStories(); myFollows(); } };
for (const ev of ["focus", "pointerdown", "keydown"]) input.addEventListener(ev, warmUp, { passive: true });

input.addEventListener("paste", () => { navStart = performance.now(); });
input.addEventListener("input", () => {
  const text = input.value;
  const parsed = parseInput(text);
  clearTimeout(typing);
  // An address opens what it names at once, without waiting for Enter.
  if (parsed.kind === "entity") {
    const t0 = navStart && performance.now() - navStart < 50 ? navStart : performance.now();
    input.value = "";
    input.blur();
    go(pathFor(parsed.entity, parsed.decoded));
    navStart = t0;
    return;
  }
  if (parsed.kind === "hex") { input.blur(); return go(`/e/${parsed.hex}`); }
  if (!text.trim()) {
    if (document.body.dataset.page === "search") go("/", { replace: true });
    return;
  }
  typing = setTimeout(() => runSearch(text), parsed.kind === "nip05" ? 600 : 160);
});

function runSearch(text) {
  const path = `/?q=${encodeURIComponent(text.trim())}`;
  const already = document.body.dataset.page === "search";
  if (location.pathname + location.search === path) return;
  go(path, { replace: already });
}

form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  clearTimeout(typing);
  const text = input.value.trim();
  if (!text) return;
  const parsed = parseInput(text);
  if (parsed.kind === "nip05") {
    const r = await resolveNip05(parsed.address);
    if (r) { input.blur(); return go(`/p/${npub(r.pubkey)}`); }
  }
  runSearch(text);
  input.blur();
});

addEventListener("keydown", (ev) => {
  if (ev.key === "/" && document.activeElement !== input && !/INPUT|TEXTAREA/.test(document.activeElement?.tagName)) {
    ev.preventDefault();
    input.focus();
  }
});

// ── Links, copy, pictures ────────────────────────────────────────────────────────────────────────────

document.addEventListener("click", (ev) => {
  if (ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
  const copy = ev.target.closest("[data-copy]");
  if (copy) {
    navigator.clipboard?.writeText(copy.dataset.copy).then(() => {
      const was = copy.textContent;
      copy.textContent = "Copied";
      setTimeout(() => (copy.textContent = was), 1500);
    });
    return;
  }
  const img = ev.target.closest(".media img, .prose img, .cover");
  if (img) { ev.preventDefault(); return zoom(img.src); }
  const a = ev.target.closest("a[href]");
  if (a) {
    const url = new URL(a.href, location.href);
    if (url.origin !== location.origin || a.target === "_blank" || a.hasAttribute("download") || /\.(png|jpg|svg|apk)$/.test(url.pathname) || url.hash) return;
    ev.preventDefault();
    if (url.pathname === "/" && !url.search) input.value = "";
    return go(url.pathname + url.search);
  }
  const c = ev.target.closest("article[data-href]");
  if (c && !ev.target.closest("button, video, details summary, .who") && !getSelection().toString()) go(c.dataset.href);
});

const dialog = $("#zoom");
function zoom(src) {
  dialog.querySelector("img").src = src;
  dialog.showModal();
}
dialog.addEventListener("click", () => dialog.close());

// ── Signed in ────────────────────────────────────────────────────────────────────────────────────────

const account = $("#account");
function drawAccount() {
  const me = currentMe();
  if (me) {
    wantProfile(me);
    account.innerHTML = `<button type="button" class="me" aria-label="Your account" aria-expanded="false">${avatar(me, 36)}</button>
      <div class="menu" hidden><a href="${personPath(me)}">Your profile</a><button type="button" data-sign-out>Sign out</button></div>`;
  } else account.innerHTML = canSignIn() ? `<button type="button" class="sign-in">Sign in</button>` : "";
}
account.addEventListener("click", async (ev) => {
  const menu = $(".menu", account);
  if (ev.target.closest(".sign-in")) {
    try { await signIn(); } catch { return; }
  } else if (ev.target.closest(".me")) {
    menu.hidden = !menu.hidden;
    $(".me", account).setAttribute("aria-expanded", !menu.hidden);
  } else if (ev.target.closest("[data-sign-out]")) signOut();
  else if (ev.target.closest("a") && menu) menu.hidden = true;
});
document.addEventListener("click", (ev) => { if (!account.contains(ev.target)) { const m = $(".menu", account); if (m) m.hidden = true; } });
onMe((me) => { drawAccount(); prepareMine(me); });
drawAccount();
prepareMine(currentMe());
setTimeout(() => { if (!currentMe()) drawAccount(); }, 800); // extensions can arrive a moment after the page

// ── Start ────────────────────────────────────────────────────────────────────────────────────────────

const first = { key: "start" };
history.replaceState(first, "", location.pathname + location.search);
// GitHub Pages serves 404.html for /e/… and /p/…; the page is the same, so it just starts there.
route(first);
if (matchMedia("(pointer: fine)").matches && document.body.dataset.page === "home") input.focus({ preventScroll: true });
