// Doing things as you: signing in (the extension, or your phone's code), liking and reacting, replying.
// What you do shows at once and is taken back if it can't be sent.
import { publish, req, relays, foundOn, FAST } from "./pool.js";
import { openTray, closePicker, trayOpenFor, trayOnly, pointerInPicker, slideTo, slideEnd, drawEmoji, loadMine, recentsOf, setRecents, used } from "./emoji-picker.js";
import { emojiOf, same, summary, changeOf, recentsFrom, RECENT_FROM } from "./reactions.js";
import { outbox, keep, profile, profiles, onProfile, profilesOf } from "./data.js";
import { esc, avatar, name, personPath } from "./render.js";
import { npub } from "./ids.js";
import { current, method, hasExtension, signInWithExtension, signedInWithPhone, sign, myWriteRelays, onMe, onAsk, choose, neither, onSignedOut } from "./me.js";

const $ = (s, el = document) => el.querySelector(s);
const ALSO = ["wss://relay.ditto.pub", "wss://syb.lol"]; // where Discover and search read reactions

// ── A short message at the bottom ────────────────────────────────────────────────────────────────────

let toastTimer = null;
export function toast(text, { stay = false } = {}) {
  let el = $("#toast");
  if (!el) { el = document.createElement("div"); el.id = "toast"; el.setAttribute("role", "status"); document.body.append(el); }
  el.textContent = text;
  el.hidden = !text;
  clearTimeout(toastTimer);
  if (text && !stay) toastTimer = setTimeout(() => (el.hidden = true), 3500);
}

/** While something waits on you approving it elsewhere, say where, after a moment. */
async function approving(promise) {
  let asked = false;
  const t = setTimeout(() => { asked = true; toast(method() === "phone" ? "Approve it on your phone…" : "Approve it in your extension…", { stay: true }); }, 700);
  // (only its own message goes: one said meanwhile, like "Signed out", stays)
  try { return await promise; } finally { clearTimeout(t); if (asked && current()) toast(""); }
}

// ── Signing in ───────────────────────────────────────────────────────────────────────────────────────

const dialog = $("#signin");
const box = $(".si", dialog);
let attempt = 0; // a newer attempt makes an older one's late answer count for nothing
let phoneAbort = null;
const spinner = `<span class="spinner" aria-hidden="true"></span>`;
/** On a phone or tablet (touch first): the sign-in code is opened in the sign-in app on this device, not scanned. */
const onPhone = () => matchMedia("(pointer: coarse)").matches && /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

export function openSignIn() {
  if (!dialog.open) dialog.showModal();
  hasExtension() ? withExtension() : withPhone();
}

// Signed out somewhere else (the extension, the phone): said once, quietly.
onSignedOut(() => toast("Signed out"));

// More than one account answered the code you signed in with (me.js): which one is you? Until you say, nothing is
// signed, and this doesn't close. Each account shows its picture, name and the ends of its npub, as sign-in apps show
// them (a lookalike can copy a name and a picture, not these).
let picking = null;
onAsk((choices) => {
  attempt++;
  phoneAbort?.abort();
  phoneAbort = null;
  stopWatching();
  picking = choices;
  toast("");
  if (!dialog.open) dialog.showModal();
  drawPick();
  for (const c of choices) profile(c.pubkey).then(drawPick);
});
onProfile((pk) => { if (picking?.some((c) => c.pubkey === pk)) drawPick(); });
function drawPick() {
  if (!picking) return;
  view(`<h2 id="si-title">${picking.length === 2 ? "Both of these accounts" : "These accounts"} tried to sign in. Which one is you?</h2>
    <div class="si-choices">${picking.map((c, i) => {
      const n = npub(c.pubkey), p = profiles.get(c.pubkey), nm = String(p?.display_name || p?.name || "").trim();
      return `<button type="button" class="si-choice" data-si="chosen" data-i="${i}">${avatar(c.pubkey, 40)}
        <span class="si-acct"><span class="si-name">${esc(nm || "No name")}</span><span class="si-fp">${n.slice(0, 12)}…<b>${n.slice(-6)}</b></span></span></button>`;
    }).join("")}</div>
    <div class="si-actions"><button type="button" class="link" data-si="neither">Neither is me</button></div>`);
}

// ── When tapping the code opens no app ──
// Opening one hides this page. If it's still on screen, and has focus, a moment after the tap, nothing did. A question
// the browser asks first ("Open in …?") takes the focus: then we wait for it to come back. Once the page has been
// hidden, it's never "nothing", so a slow app can't set this off.
let stopWatching = () => {};
function whenNothingOpens(then, wait = 1500) {
  let t = 0;
  const check = () => { if (document.visibilityState !== "visible") return stop(); if (!document.hasFocus()) return; stop(); then(); };
  const hidden = () => { if (document.visibilityState === "hidden") stop(); };
  const back = () => { clearTimeout(t); t = setTimeout(check, 700); };
  const stop = () => { clearTimeout(t); document.removeEventListener("visibilitychange", hidden); removeEventListener("pagehide", stop); removeEventListener("focus", back); };
  document.addEventListener("visibilitychange", hidden);
  addEventListener("pagehide", stop);
  addEventListener("focus", back);
  t = setTimeout(check, wait);
  return stop;
}
// On Android, Chrome and the browsers built on it take an intent:// link, which also says where to go when no app takes
// the code: back to this page, which then says so. Elsewhere (or on an address that already has a #), the plain code.
const NO_APP = "#signin-no-app";
function phoneLink(uri) {
  const ua = navigator.userAgent;
  if (!/Android/.test(ua) || !/Chrome\//.test(ua) || /Firefox/.test(ua) || location.hash || !uri.startsWith("nostrconnect://")) return uri;
  return `intent://${uri.slice(15)}#Intent;scheme=nostrconnect;S.browser_fallback_url=${encodeURIComponent(location.href.split("#")[0] + NO_APP)};end`;
}
let noAppSoon = false;
function noApp() {
  noAppSoon = false;
  if (!$("[data-none]", box)) return;
  $(".si-wait", box).hidden = true;
  for (const el of box.querySelectorAll("[data-none]")) el.hidden = false;
}
// Back from an intent:// link no app took: the address is put back, and the sign-in says so (with a new code, if the
// browser loaded the page afresh for it).
function backWithNoApp(loaded = false) {
  if ((!loaded && location.hash !== NO_APP) || current()) return;
  if (location.hash === NO_APP) history.replaceState(history.state, "", location.href.split("#")[0]);
  noAppSoon = true;
  if (dialog.open) noApp(); else openSignIn();
}
addEventListener("hashchange", () => backWithNoApp());
// (looked at now: the page may tidy its address before then)
if (location.hash === NO_APP) setTimeout(() => backWithNoApp(true), 0);
// When tapping the code opened no app, or there's none on your phone: get Marble (Android); on iPhone, where there's no
// Marble yet, nsec.app in Safari.
const GET_MARBLE = "https://marble.syb.lol/about#get", NSEC_APP = "https://nsec.app";
const onIphone = () => /iPhone|iPad|iPod/.test(navigator.userAgent);

function close() {
  attempt++;
  stopWatching();
  phoneAbort?.abort();
  phoneAbort = null;
  if (dialog.open) dialog.close();
}
function view(html) { box.innerHTML = html; }

async function withExtension() {
  const mine = ++attempt;
  phoneAbort?.abort();
  view(`<h2 id="si-title">Waiting for your extension…</h2>
    <p class="si-wait">${spinner}Approve in the extension window.</p>
    <p class="si-help" hidden>Open your extension to approve.</p>
    <div class="si-actions"><button type="button" class="link" data-si="phone">Sign in with your phone instead</button><button type="button" class="si-button" data-si="close">Cancel</button></div>`);
  const help = setTimeout(() => { const h = $(".si-help", box); if (h && mine === attempt) h.hidden = false; }, 3000);
  const gaveUp = setTimeout(() => {
    if (mine !== attempt) return;
    attempt++;
    view(`<h2 id="si-title">No answer from the extension</h2><p>Open your extension and try again, or sign in with your phone.</p>
      <div class="si-actions"><button type="button" class="link" data-si="phone">Sign in with your phone</button><button type="button" class="si-button" data-si="extension">Try again</button></div>`);
  }, 60000);
  try {
    await signInWithExtension();
    if (mine !== attempt) return;
    close();
    toast("Signed in");
  } catch {
    if (mine !== attempt) return;
    view(`<h2 id="si-title">Sign-in cancelled</h2><p>The extension didn't let this site in. You can try again.</p>
      <div class="si-actions"><button type="button" class="link" data-si="phone">Sign in with your phone</button><button type="button" class="si-button" data-si="extension">Try again</button></div>`);
  } finally { clearTimeout(help); clearTimeout(gaveUp); }
}

async function withPhone() {
  const mine = ++attempt;
  stopWatching();
  phoneAbort?.abort();
  const ctrl = (phoneAbort = new AbortController());
  view(`<h2 id="si-title">Sign in with your phone</h2><p class="si-wait">${spinner}Making a code…</p>`);
  const expire = setTimeout(() => {
    if (mine !== attempt) return;
    ctrl.abort();
    view(`<h2 id="si-title">This code has expired</h2><p>Make a new one and scan it with your phone.</p>
      <div class="si-actions"><button type="button" class="si-button" data-si="phone">New code</button></div>`);
  }, 180000);
  try {
    const { phoneSignIn } = await import("./connect.js");
    const done = await phoneSignIn({
      signal: ctrl.signal,
      // the site's name the sign-in app shows: the page's own <meta name="application-name">
      name: document.querySelector('meta[name="application-name"]')?.content || location.hostname,
      onCode: (uri, svg) => {
        if (mine !== attempt) return;
        // On the phone itself a code to scan can't be scanned: the code is a link that opens its sign-in app instead,
        // and the picture is there for signing in with another phone.
        if (onPhone()) {
          view(`<h2 id="si-title">Sign in</h2>
            <a class="si-button si-open" href="${esc(phoneLink(uri))}" data-si="open">Sign in</a>
            <p class="si-wait" hidden>${spinner}Waiting for your sign-in app…</p>
            <p class="si-note" data-none hidden>No sign-in app opened.</p>
            ${onIphone() ? `<p data-none hidden>Marble isn't on iPhone yet. nsec.app works in Safari: copy the code and paste it there.</p>` : ""}
            <div class="qr" hidden>${svg}</div>
            <div class="si-actions">${onIphone() ? `<a class="link" href="${NSEC_APP}" target="_blank" rel="noopener" data-none hidden>Open nsec.app</a>`
              : `<a class="link" href="${GET_MARBLE}" target="_blank" rel="noopener" data-none hidden>Get Marble</a>`}<button type="button" class="link" data-si="qr">Show code</button>
              <button type="button" class="link" data-si="copy" data-code="${esc(uri)}">Copy the code</button><button type="button" class="link" data-si="close">Cancel</button></div>`);
          if (noAppSoon) noApp();
          return;
        }
        view(`<h2 id="si-title">Sign in with your phone</h2>
          <div class="qr">${svg}</div>
          <p>Scan this code with the sign-in app on your phone.</p>
          <p class="si-wait">${spinner}Waiting for your phone…</p>
          <p class="si-get">No sign-in app on your phone? <a class="link" href="${GET_MARBLE}" target="_blank" rel="noopener">Get Marble</a></p>
          <div class="si-actions">${hasExtension() ? `<button type="button" class="link" data-si="extension">Use the extension</button>` : ""}
            <button type="button" class="link" data-si="copy" data-code="${esc(uri)}">Copy the code</button><button type="button" class="si-button" data-si="close">Cancel</button></div>`);
      },
    });
    if (mine !== attempt) return done.remote.stop();
    signedInWithPhone(done);
    close();
    toast("Signed in");
  } catch (err) {
    if (mine !== attempt || ctrl.signal.aborted) return;
    view(`<h2 id="si-title">Your phone didn't finish signing in</h2><p>${esc(String(err?.message || "").replace(/^[a-z-]+: /, "") || "Try again with a new code.")}</p>
      <div class="si-actions"><button type="button" class="si-button" data-si="phone">Try again</button></div>`);
  } finally { clearTimeout(expire); }
}

dialog.addEventListener("click", (ev) => {
  if (ev.target === dialog) return picking ? null : close(); // the backdrop
  const b = ev.target.closest("[data-si]");
  if (!b) return;
  const what = b.dataset.si;
  if (what === "open") { // the link opens the sign-in app on this phone (any: Marble, Amber…); this page waits for it, unless none opens
    $(".si-wait", box).hidden = false;
    for (const el of box.querySelectorAll("[data-none]")) el.hidden = true;
    stopWatching();
    const mine = attempt;
    stopWatching = whenNothingOpens(() => { if (mine === attempt) noApp(); });
    return;
  }
  if (what === "chosen") { const c = picking?.[+b.dataset.i]; if (!c) return; picking = null; choose(c); close(); return; }
  if (what === "neither") { picking = null; neither(); close(); return; }
  if (what === "qr") { $(".qr", box).hidden = false; b.remove(); return; }
  if (what === "close") close();
  else if (what === "phone") withPhone();
  else if (what === "extension") withExtension();
  else if (what === "copy") navigator.clipboard?.writeText(b.dataset.code).then(() => { b.textContent = "Copied"; });
});
dialog.addEventListener("cancel", (ev) => { ev.preventDefault(); if (!picking) close(); });

/** Something only a signed-in account can do: true if it can go ahead, else the sign-in opens. */
function canAct() {
  if (!current()) { openSignIn(); return false; }
  if (!method()) { toast("Signed in to look only: sign in again to do this."); return false; }
  return true;
}

// ── Reactions ────────────────────────────────────────────────────────────────────────────────────────
// The Marble reactions design (~/igloo/marble-reactions.md, as Android does it; rules in reactions.js). In a list of
// posts only the button: ☺+, or your emoji once you've reacted (no counts of anything). Clicking it opens the tray, or,
// once you've reacted, takes your reaction back; hovering opens the tray too (to change it), and on a touch screen a
// press opens it under the finger: slide to an emoji and let go. On an opened post, a row of each emoji and how many gave
// it (the top five, then "+N"); clicking someone else's gives it too; its empty end (or a long press) shows who reacted.

const SMILE_PLUS = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M22 11v1a10 10 0 1 1-9-10"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><path d="M9 9h.01M15 9h.01M16 5h6M19 2v6"/></svg>`;
const mine = new Map(); // post id → your reaction (newest): "❤️", "😂" or {shortcode, url}
const mineAt = new Map(); // post id → when you reacted (or took it back)
const mineEvents = new Map(); // post id → your reaction events to it (what taking it back deletes)
const checked = new Set(); // post ids already looked up
const everyone = new Map(); // opened post id → reactor → their newest reaction event
const SHOWN = 5; // emoji on an opened post before "+N"

const lastE = (r) => (r.tags.filter((t) => t[0] === "e").pop() || [])[1];
function mineSeen(id, r) {
  const list = mineEvents.get(id) || [];
  if (!list.some((x) => x.id === r.id)) mineEvents.set(id, [...list, r]);
  if (mineAt.get(id) >= r.created_at) return;
  const e = emojiOf(r);
  if (e) { mine.set(id, e); mineAt.set(id, r.created_at); }
}

/** The reaction button under a post: ☺+, or your reaction once you've given one. */
export function heart(e) {
  return `<button type="button" class="heart" data-react="${e.id}" data-author="${e.pubkey}" data-kind="${e.kind}" aria-label="React" aria-pressed="false" aria-haspopup="menu">${SMILE_PLUS}</button>`;
}
/** On an opened post: everyone's reactions, each with how many gave it. */
export const reactionsRow = (e) => `<div class="reactions" data-reactions="${e.id}" data-author="${e.pubkey}" data-kind="${e.kind}"></div>`;

function paint(root = document) {
  for (const b of root.querySelectorAll("[data-react]")) {
    const r = mine.get(b.dataset.react);
    b.setAttribute("aria-pressed", r ? "true" : "false");
    b.classList.toggle("reacted", !!r);
    b.innerHTML = r ? drawEmoji(r) : SMILE_PLUS;
    b.setAttribute("aria-label", r ? `You reacted ${typeof r === "string" ? r : `:${r.shortcode}:`}: take it back` : "React");
  }
  for (const el of root.querySelectorAll("[data-reactions]")) {
    const id = el.dataset.reactions, me = current();
    const all = summary(everyone.get(id) || new Map(), me, me && mineAt.has(id) ? mine.get(id) ?? null : undefined);
    el.summary = all;
    const shown = all.slice(0, SHOWN), more = all.length - shown.length;
    el.innerHTML = !all.length ? "" : shown.map((c, i) => `<button type="button" class="pill${c.yours ? " yours" : ""}" data-pill="${i}" aria-pressed="${c.yours}"
      aria-label="${esc(typeof c.e === "string" ? c.e : `:${c.e.shortcode}:`)} ${c.n}${c.yours ? ", yours" : ""}">${drawEmoji(c.e)}<span>${c.n}</span></button>`).join("")
      + (more ? `<button type="button" class="pill more" data-who aria-label="${more} more: see who reacted">+${more}</button>` : "")
      + `<button type="button" class="who-end" data-who aria-label="See who reacted" title="See who reacted"></button>`;
  }
}

/** On an opened post: a reaction to it came in. */
export function addReaction(post, r) {
  if (r.kind !== 7 || lastE(r) !== post || !emojiOf(r)) return;
  if (!everyone.has(post)) everyone.set(post, new Map());
  const old = everyone.get(post).get(r.pubkey);
  if (old && old.created_at >= r.created_at) return;
  everyone.get(post).set(r.pubkey, r);
  if (r.pubkey === current()) mineSeen(post, r);
  clearTimeout(addReaction.t);
  addReaction.t = setTimeout(() => paint(document), 120);
}

/** After a page draws: which of its posts you've already reacted to (one request for all of them). */
export async function markReactions(root) {
  paint(root);
  const me = current();
  if (!me) return;
  const ids = [...root.querySelectorAll("[data-react]")].map((b) => b.dataset.react).filter((id) => !checked.has(id));
  if (!ids.length) return;
  ids.forEach((id) => checked.add(id));
  const where = await myWriteRelays().catch(() => []);
  const got = await req(relays(where, ALSO, FAST.slice(2, 4)), [{ kinds: [7], authors: [me], "#e": ids, limit: 500 }, { kinds: [5], authors: [me], "#k": ["7"], limit: 200 }], { timeout: 4000, settle: 600 });
  // (what you've deleted stays taken back, wherever the relay still has it)
  const gone = new Set(got.filter((x) => x.kind === 5).flatMap((x) => x.tags.filter((t) => t[0] === "e").map((t) => t[1])));
  for (const r of got) if (r.kind === 7 && ids.includes(lastE(r)) && !gone.has(r.id)) mineSeen(lastE(r), r);
  paint(document);
}

// your recent emoji, from your own reactions (the same on every device), and your own emoji (NIP-30)
async function loadRecents() {
  const me = current();
  recentsOf(me);
  if (!me) return;
  loadMine(me);
  const where = await myWriteRelays().catch(() => []);
  const list = await req(relays(where, ALSO), { kinds: [7], authors: [me], limit: RECENT_FROM }, { timeout: 4000, settle: 800 });
  if (list.length) setRecents(me, recentsFrom(list)); // (none found: what was kept from last time stands)
}
onMe(() => { mine.clear(); mineAt.clear(); mineEvents.clear(); checked.clear(); closePicker(); loadRecents(); markReactions(document); });
loadRecents();

async function send(template, author) {
  const ev = await approving(sign(template));
  const theirs = await Promise.race([outbox(author).then((o) => o.read.slice(0, 3)), new Promise((r) => setTimeout(() => r([]), 1500))]);
  const sent = await publish(relays(await myWriteRelays(), theirs, ALSO), ev);
  if (!sent.ok.length) throw new Error("nowhere took it");
  return ev;
}

// Reacting, or changing it: the new one is sent, then the old ones deleted (NIP-09). emoji null: taking it back.
async function react(on, emoji) {
  if (!canAct()) return;
  const { id, author, kind } = on;
  const before = mine.get(id), beforeAt = mineAt.get(id), olds = mineEvents.get(id) || [];
  if (emoji && before && same(before, emoji)) return;
  if (!emoji && !before) return;
  if (emoji) mine.set(id, emoji); else mine.delete(id);
  mineAt.set(id, Math.floor(Date.now() / 1000));
  if (emoji) used(emoji);
  paint(document);
  try {
    const [first, second] = changeOf({ id, pubkey: author, kind: +kind || 1 }, olds, emoji, { relay: foundOn(id)[0] || "" });
    if (!first) return paint(document); // (yours wasn't found anywhere: nothing to take back)
    if (emoji) {
      const ev = await send(first, author);
      mineEvents.set(id, [ev]);
      if (everyone.has(id)) everyone.get(id).set(ev.pubkey, ev);
      // (a deletion that doesn't go out leaves the old one up; the new one still stands)
      if (second) await send(second, author).catch(() => {});
    } else {
      await send(first, author);
      mineEvents.delete(id);
      everyone.get(id)?.delete(current());
    }
    paint(document);
  } catch (err) {
    if (before) mine.set(id, before); else mine.delete(id);
    if (beforeAt) mineAt.set(id, beforeAt); else mineAt.delete(id);
    mineEvents.set(id, olds);
    paint(document);
    // (signed out meanwhile, somewhere else: that's what to say, and it was said)
    if (!current()) return toast("Signed out");
    toast(/refus|reject|denied|cancel/i.test(String(err?.message)) ? "Not sent: you said no." : emoji ? "Couldn't send your reaction. Try again." : "Couldn't take your reaction back. Try again.");
  }
}

const target = (b) => ({ id: b.dataset.react, author: b.dataset.author, kind: b.dataset.kind });
function tray(b, { focus = true } = {}) {
  if (trayOpenFor(b)) return;
  openTray(b, (emoji) => react(target(b), emoji), { yours: mine.get(b.dataset.react) || null, focus });
}

// Clicking (or tapping) the button: the tray, or taking yours back. A press that opened the tray isn't a click too.
let pressTimer = null, pressed = null, pressAt = null, hoverTimer = null, leaveTimer = null, clicked = null;
document.addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-react]");
  if (b) {
    ev.preventDefault();
    ev.stopPropagation(); // (not opening the post it's on)
    clearTimeout(hoverTimer);
    clicked = b; // (no tray from hovering it again until the mouse leaves: what's under the mouse was just redrawn)
    if (pressed) { pressed = null; return; }
    if (!canAct()) return;
    if (mine.has(b.dataset.react)) { closePicker(); return react(target(b), null); }
    return trayOpenFor(b) ? closePicker() : tray(b);
  }
  const pill = ev.target.closest("[data-pill]");
  if (pill) {
    ev.stopPropagation();
    if (pressed) { pressed = null; return; }
    const row = pill.closest("[data-reactions]"), c = row.summary?.[+pill.dataset.pill];
    if (c && !c.yours) react({ id: row.dataset.reactions, author: row.dataset.author, kind: row.dataset.kind }, c.e);
    return;
  }
  const who = ev.target.closest("[data-who]");
  if (who) { ev.stopPropagation(); whoReacted(who.closest("[data-reactions]")); }
}, true);
// the keyboard: the menu key or ↑ on a button opens the tray (to change yours)
document.addEventListener("keydown", (ev) => {
  const b = ev.target.closest?.("[data-react]");
  if (b && (ev.key === "ArrowUp" || ev.key === "ContextMenu")) { ev.preventDefault(); if (canAct()) tray(b); }
});
document.addEventListener("contextmenu", (ev) => {
  const b = ev.target.closest("[data-react]");
  if (b) { ev.preventDefault(); if (canAct()) tray(b); }
  else if (ev.target.closest("[data-reactions]") && matchMedia("(pointer: coarse)").matches) ev.preventDefault();
});

// Touch: a press opens the tray under the finger; sliding lights up an emoji, letting go there reacts with it (letting
// go elsewhere leaves the tray open). On an opened post's row, a press shows who reacted.
document.addEventListener("touchstart", (ev) => {
  const b = ev.target.closest?.("[data-react], [data-reactions]");
  if (!b || ev.touches.length > 1) return;
  const t = ev.touches[0];
  pressAt = { x: t.clientX, y: t.clientY };
  pressed = null;
  clearTimeout(pressTimer);
  pressTimer = setTimeout(() => {
    if (b.matches("[data-reactions]")) { pressed = b; whoReacted(b); return; }
    if (!current() || !method()) return; // (the tap will say why)
    pressed = b;
    tray(b, { focus: false });
    navigator.vibrate?.(8);
  }, 380);
}, { passive: true });
document.addEventListener("touchmove", (ev) => {
  const t = ev.touches[0];
  if (!pressed) { if (pressAt && Math.hypot(t.clientX - pressAt.x, t.clientY - pressAt.y) > 8) { clearTimeout(pressTimer); pressAt = null; } return; }
  if (pressed.matches("[data-react]")) { ev.preventDefault(); slideTo(t.clientX, t.clientY); }
}, { passive: false });
document.addEventListener("touchend", (ev) => {
  clearTimeout(pressTimer);
  if (pressed) ev.preventDefault(); // (no click after a press: it would land on what the press opened)
  if (pressed?.matches("[data-react]")) slideEnd();
  // (the click that follows a press is swallowed above; none comes if the finger slid, so forget it soon)
  const p = pressed;
  setTimeout(() => { if (pressed === p) pressed = null; }, 400);
});
document.addEventListener("touchcancel", () => { clearTimeout(pressTimer); pressed = null; });

// A mouse: hovering opens the tray (to react, or change yours); moving away closes it (not the full picker).
if (matchMedia("(hover: hover)").matches) {
  document.addEventListener("mouseover", (ev) => {
    const b = ev.target.closest?.("[data-react]");
    if (b || pointerInPicker(ev.target)) clearTimeout(leaveTimer);
    if (!b || b === clicked || trayOpenFor(b) || !current() || !method()) return;
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => tray(b, { focus: false }), 450);
  });
  document.addEventListener("mouseout", (ev) => {
    const from = ev.target.closest?.("[data-react]") || (pointerInPicker(ev.target) ? ev.target : null);
    if (!from || from.contains(ev.relatedTarget)) return;
    if (from === clicked) clicked = null;
    if (pointerInPicker(ev.relatedTarget)) return;
    clearTimeout(hoverTimer);
    clearTimeout(leaveTimer);
    leaveTimer = setTimeout(() => { if (trayOnly()) closePicker(); }, 450);
  });
}

// Who reacted: a tab for each emoji (and All), each person opening their profile.
function whoReacted(row) {
  const all = row?.summary;
  if (!all?.length) return;
  document.querySelector(".who-sheet")?.remove();
  const total = all.reduce((n, c) => n + c.n, 0);
  const el = document.createElement("div");
  el.className = "who-sheet";
  el.setAttribute("role", "dialog");
  el.setAttribute("aria-label", "Reactions");
  const tabs = [{ label: `All ${total}`, people: all.flatMap((c) => c.people.map((pk) => [pk, c.e])) },
    ...all.map((c) => ({ label: `${typeof c.e === "string" ? esc(c.e) : drawEmoji(c.e)} ${c.n}`, people: c.people.map((pk) => [pk, c.e]), html: true }))];
  const list = (i) => tabs[i].people.map(([pk, e]) => `<a class="who-row" href="${personPath(pk)}">${avatar(pk, 36)}<span class="who-name" data-name="${pk}">${esc(name(pk))}</span>${i ? "" : `<span class="who-emoji">${drawEmoji(e)}</span>`}</a>`).join("");
  el.innerHTML = `<div class="sheet-scrim" data-who-close></div><div class="who-box"><div class="who-head"><h2>Reactions</h2><button type="button" class="link" data-who-close aria-label="Close">Close</button></div>
    <div class="who-tabs" role="tablist">${tabs.map((t, i) => `<button type="button" role="tab" data-who-tab="${i}" aria-selected="${!i}">${t.html ? t.label : esc(t.label)}</button>`).join("")}</div>
    <div class="who-list">${list(0)}</div></div>`;
  document.body.append(el);
  profilesOf(all.flatMap((c) => c.people));
  el.addEventListener("click", (ev) => {
    const t = ev.target.closest("[data-who-tab]");
    if (t) { el.querySelectorAll("[data-who-tab]").forEach((x) => x.setAttribute("aria-selected", x === t)); el.querySelector(".who-list").innerHTML = list(+t.dataset.whoTab); return; }
    if (ev.target.closest("[data-who-close]") || ev.target.closest(".who-row")) el.remove();
  });
  el.querySelector("[data-who-tab]").focus();
}
document.addEventListener("keydown", (ev) => { if (ev.key === "Escape") document.querySelector(".who-sheet")?.remove(); });

// ── Replies ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * Signs and sends a reply (the tags already worked out, NIP-10) to your write relays and the author's read
 * relays. Resolves with the event; throws a sentence to show if it couldn't go.
 */
export async function sendReply(to, content, tags) {
  if (!canAct()) throw new Error("");
  let ev;
  try { ev = await approving(sign({ kind: 1, content, tags })); }
  catch (err) { throw new Error(!current() ? "Signed out" : /refus|reject|denied|cancel/i.test(String(err?.message)) ? "Not sent: you said no." : "Couldn't sign your reply. Try again."); }
  const theirs = await Promise.race([outbox(to.pubkey).then((o) => o.read.slice(0, 3)), new Promise((r) => setTimeout(() => r([]), 1500))]);
  const sent = await publish(relays(await myWriteRelays(), theirs, ALSO), ev);
  if (!sent.ok.length) throw new Error("Couldn't send your reply. Try again.");
  keep(ev);
  return { ev, places: sent.ok.length };
}

export { canAct };
