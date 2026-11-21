// Doing things as you: signing in (your name first, then your login page or your phone), liking and reacting, replying.
// What you do shows at once and is taken back if it can't be sent. The sign-in itself, its dialog and the toast are
// Keykin's shared copy in signin/ (never edited here); this adds Marble's own flow in that dialog, adding Marble on a Mac.
import "./signin-hooks.js";
import { publish, req, relays, foundOn, FAST } from "./signin/pool.js";
import { openTray, closePicker, trayOpenFor, trayOnly, pointerInPicker, slideTo, slideEnd, drawEmoji, loadMine, recentsOf, setRecents, used } from "./emoji-picker.js";
import { emojiOf, same, summary, changeOf, recentsFrom, RECENT_FROM } from "./reactions.js";
import { outbox, keep, profile, profiles, onProfile, profilesOf } from "./data.js";
import { esc, avatar, name, personPath } from "./render.js";
import { npub } from "./signin/ids.js";
import { ADD, addFromHash, addLink, readInvite, returnOk } from "./add-device.js";
import { current, method, sign, myWriteRelays, onMe } from "./me.js";
import { toast, approving } from "./signin/toast.js";
import {
  openSignIn, loginLink, lookAgain, dialog, view, pageOf, rememberedPage, forgetPage, EMAIL, SPINNER as spinner,
  nextAttempt, attemptNow, lastTypedName, setLastTyped,
} from "./signin/dialog.js";

export { toast, openSignIn, loginLink, lookAgain };

const $ = (s, el = document) => el.querySelector(s);
const ALSO = ["wss://relay.ditto.pub", "wss://syb.lol"]; // where Discover and search read reactions

// ── Adding Marble on a Mac (Keykin SPEC 11.6, `add=`; the rules in add-device.js) ──
// The Mac's Sign in opens this page with its invite, a PKCE challenge, a return address on this computer and a state.
// Your account (this browser's, or the name you type) names its login page; "Continue at <host>" goes there with the
// four, where you press Add, and the page hands the approval back to the Mac on this computer. With no login page, the
// invite as a code for your phone's Add a device. This page signs nothing and keeps nothing: the invite stays in this
// tab only until it is handed on. A return that is not this computer is left alone.
const addTaken = addFromHash(location.hash); // (read before the page tidies its address: app.js)
if (addTaken) {
  history.replaceState(history.state, "", location.pathname + location.search + (addTaken.rest ? "#" + addTaken.rest : ""));
  try { if (addTaken.invite && !addTaken.bad) sessionStorage.setItem(ADD, JSON.stringify({ invite: addTaken.invite.text, app: addTaken.app })); else sessionStorage.removeItem(ADD); } catch {}
}
function addWaiting() {
  try {
    const o = JSON.parse(sessionStorage.getItem(ADD) || "null"), inv = o && readInvite(o.invite);
    const app = o?.app && returnOk(o.app.ret) ? o.app : null;
    return inv ? { inv, app } : null;
  } catch { return null; }
}
function clearAdd() { try { sessionStorage.removeItem(ADD); } catch {} }
const whoBlock = (pk) => `<div class="si-who">${avatar(pk, 40)}<span class="si-acct"><span class="si-name">${esc(name(pk))}</span><span class="si-fp">${npub(pk).slice(0, 12)}…<b>${npub(pk).slice(-6)}</b></span></span></div>`;

function openAdd() {
  if (!dialog.open) dialog.showModal();
  const a = addWaiting();
  const why = addTaken?.bad === "return" ? "Something sent you here to add a device and send the approval somewhere that is not this device, so it was left alone."
    : addTaken?.bad === "incomplete" ? "Something sent you here to add a device, but what it sent is not complete, so it was left alone. Go back to the app and try again."
    : "Something sent you here to add a device, but what it sent is not a Keykin invite, so it was left alone. Go back to the app and try again.";
  if (!a) return view(`<h2 id="si-title">Add a device</h2><p class="si-note">${why}</p>
    <div class="si-actions"><button type="button" class="si-button" data-si="close">OK</button></div>`);
  current() ? addLook(current(), a, true) : addAskName(a);
}
function addAskName(a, note = "", typed = "") {
  nextAttempt();
  view(`<h2 id="si-title">Add ${esc(a.inv.name)} to your account</h2>
    <p>Type your name. Marble finds your account's sign-in page and sends you there to add it.</p>
    <form data-form="addname"><label for="si-who">Your name</label>
      <input id="si-who" name="who" class="acct-who" type="text" value="${esc(typed)}" placeholder="name@site.com or npub1…" autocomplete="username" autocapitalize="off" autocorrect="off" spellcheck="false" required>
      <div class="si-actions"><button type="button" class="link" data-add="cancel">Not now</button><button type="submit" class="si-button">Continue</button></div></form>
    ${note ? `<div class="si-said" role="status">${note}</div>` : ""}`);
}
async function addLookUp(a, typed) {
  const mine = nextAttempt();
  if (EMAIL.test(typed)) return addAskName(a, `<p>${esc(typed)} looks like an email address. Type your name, like name@site.com.</p>`, typed);
  view(`<h2 id="si-title">Add ${esc(a.inv.name)} to your account</h2><p class="si-wait">${spinner}Looking for ${esc(typed)}…</p>
    <div class="si-actions"><button type="button" class="si-button" data-add="retype">Cancel</button></div>`);
  const { whose } = await import("./signin/pointer.js");
  const pk = await whose(typed);
  if (mine !== attemptNow()) return;
  if (!pk) return addAskName(a, `<p>Marble couldn't find ${esc(typed)}. Check the name.</p>`, typed);
  setLastTyped(typed);
  addLook(pk, a, false);
}
async function addLook(pk, a, signedIn) {
  const mine = nextAttempt();
  const title = `<h2 id="si-title">Add ${esc(a.inv.name)} to your account</h2>`;
  view(`${title}<p class="si-wait">${spinner}Looking for your sign-in page…</p>`);
  const { loginPage } = await import("./signin/pointer.js");
  const [r] = await Promise.all([loginPage(pk), Promise.race([profile(pk).catch(() => null), new Promise((ok) => setTimeout(ok, 1500))])]);
  if (mine !== attemptNow()) return;
  let page = r.template ? pageOf(r.template) : null;
  if (!page) { if (r.removed) forgetPage(pk); page = rememberedPage(pk); }
  const other = `<button type="button" class="link" data-add="${signedIn ? "other" : "retype"}">${signedIn ? "Use another account" : "Not you?"}</button>`;
  if (page) {
    const host = new URL(page.origin).host;
    const link = addLink(page.origin, a.inv.text, a.app);
    // Already signed in here: no question to ask, so straight on to your own sign-in page (it's your account's own
    // address, and Add there is the one click). Shown for a moment so the browser's move isn't a surprise.
    if (signedIn) {
      view(`${title}${whoBlock(pk)}<p class="si-wait">${spinner}Going to ${esc(host)}…</p>`);
      clearAdd();
      return setTimeout(() => { if (mine === attemptNow()) location.assign(link); }, 600);
    }
    // the address is shown, in full, before anything goes there; the link is an ordinary one, so the browser shows it too
    return view(`${title}${whoBlock(pk)}
      <p>Your account names this page to sign in at:</p>
      <p class="acct-addr">${esc(host)}</p>
      <p>Sign in there and press Add. ${esc(a.inv.name)} then signs in by itself.</p>
      <a class="si-button si-open" href="${esc(link)}" data-add="away">Continue at ${esc(host)}</a>
      <div class="si-actions">${other}<button type="button" class="link" data-add="cancel">Not now</button></div>`);
  }
  const { codeSvg } = await import("./signin/connect.js");
  const svg = await codeSvg(a.inv.text);
  if (mine !== attemptNow()) return;
  view(`<h2 id="si-title">Add ${esc(a.inv.name)} with your phone</h2>${whoBlock(pk)}
    <p class="si-note">${r.unreachable ? "Marble couldn't reach the places your sign-in page is listed." : r.removed ? "Your account's sign-in page was removed." : "Your account doesn't name a sign-in page yet."}</p>
    <div class="qr">${svg}</div>
    <p>On your phone, open Marble, tap your picture, then Add a device, and scan this. Then paste the join code your phone shows into ${esc(a.inv.name)}.</p>
    <div class="si-actions">${other}<button type="button" class="link" data-si="copy" data-code="${esc(a.inv.text)}">Copy the invite</button><button type="button" class="si-button" data-add="cancel">Done</button></div>`);
}
dialog.addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-add]");
  if (!b) return;
  const a = addWaiting(), what = b.dataset.add;
  if (what === "away") return clearAdd(); // the link goes on to the login page, with the invite
  if (what === "cancel") { clearAdd(); nextAttempt(); return dialog.close(); }
  if (!a) return;
  if (what === "other") addAskName(a);
  else if (what === "retype") addAskName(a, "", lastTypedName());
});
dialog.addEventListener("submit", (ev) => {
  if (ev.target.dataset.form !== "addname") return;
  ev.preventDefault();
  const a = addWaiting(), t = String(new FormData(ev.target).get("who") || "").trim();
  if (a && t) addLookUp(a, t);
});
if (addTaken || addWaiting()) setTimeout(() => openAdd(), 0);

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
