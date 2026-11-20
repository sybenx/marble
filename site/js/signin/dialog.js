// The Sign in dialog: your name first, then the login page your account names, or your phone, or another signer app (a
// nostrconnect code or a bunker link). Moved from Marble Web's act.js as it is, Marble's words and all. It uses the
// page's <dialog id="signin" aria-labelledby="si-title"><div class="si"></div></dialog>, made here when the page has none.
import { npub } from "./ids.js";
import { loginLink, loginTemplate, legacyTemplate } from "./connect.js";
import { hooks, watchProfiles } from "./hooks.js";
import { toast } from "./toast.js";
import { current, extensionSignedOut, signedInWithPhone, onAsk, choose, neither, onSignedOut } from "./me.js";

const $ = (s, el = document) => el.querySelector(s);
// (the site's own, through configure: see hooks.js)
const esc = (s) => hooks.esc(s), avatar = (pk, size) => hooks.avatar(pk, size), name = (pk) => hooks.name(pk);
const profile = (pk) => hooks.profile(pk), profiles = { get: (pk) => hooks.profileOf(pk) }, onProfile = watchProfiles;
function made() {
  const d = document.createElement("dialog");
  d.id = "signin";
  d.setAttribute("aria-labelledby", "si-title");
  d.innerHTML = `<div class="si"></div>`;
  document.body.append(d);
  return d;
}

// ── Signing in ───────────────────────────────────────────────────────────────────────────────────────

const dialog = $("#signin") || made();
const box = $(".si", dialog);
let attempt = 0; // a newer attempt makes an older one's late answer count for nothing
let phoneAbort = null;
const spinner = `<span class="spinner" aria-hidden="true"></span>`;
/** On a phone or tablet (touch first): the sign-in code is opened in the sign-in app on this device, not scanned. */
const onPhone = () => matchMedia("(pointer: coarse)").matches && /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

export function openSignIn() {
  if (!dialog.open) dialog.showModal();
  const p = pendingCode();
  p ? atLoginPage(p.page, { pending: p }) : askName();
}

// Signed out somewhere else (the phone, the login page): said once, quietly.
onSignedOut(() => toast("Signed out"));
// Signed in with the browser extension, which Marble no longer uses: signed out (me.js), and told so once.
if (extensionSignedOut()) setTimeout(() => { toast("Marble doesn't use the browser extension any more, so you're signed out. Sign in again.", { stay: true }); setTimeout(() => toast(""), 9000); }, 300);

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
  if (!dialog.open) dialog.showModal();
  withMarble();
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
  clearPending();
  if (dialog.open) dialog.close();
}
function view(html) { box.innerHTML = html; box.querySelector("input, a.si-open")?.focus(); }

// ── A code waiting on the login page ──
// "Sign in at <host>" opens it in a new tab, with the code in the link, and this tab keeps listening: the answer
// (kind 24133) is ephemeral, so relays don't keep it for a page that wasn't there. Coming back to this tab, it listens
// again from when the code was made (connect.js listen). The code's key is also kept for this tab (sessionStorage), so
// a reload, or the login page sending you back here in its own tab's place, still picks it up for an answer after that.
const PENDING = "marble.signin", PENDING_LIFE = 15 * 60; // seconds
function pendingCode() {
  try {
    const p = JSON.parse(sessionStorage.getItem(PENDING) || "null");
    const page = p && pageOf(p.page || p.url); // (url: an origin, kept before the pointer moved to kind 10046)
    if (p?.sk && p.made && Date.now() / 1000 - p.made < PENDING_LIFE && page) return { ...p, page };
  } catch {}
  clearPending();
  return null;
}
function clearPending() { try { sessionStorage.removeItem(PENDING); } catch {} }
// The login page's link (connect.js): the account's template with the code in it.
export { loginLink };
// A login page as kept here: its template ({template, origin}), or an origin kept before kind 10046, as the old template.
const pageOf = (s) => typeof s !== "string" ? null : loginTemplate(s) || (/<nostrconnect>/.test(s) ? null : loginTemplate(legacyTemplate(s)));

// ── Your name first; your phone a button away ──
// The field takes a Nostr address (name@site.com, or just site.com) or an npub. Most people will try an email: that's
// caught (EMAIL below) and answered with the phone way in, right there.
let lastTyped = "";
const PHONE_BUTTON = `<button type="button" class="si-button si-alt" data-si="code">Sign in with your phone</button>`;
const ACCOUNT_PAGE = `<p class="si-get">Here to manage your account? <button type="button" class="link" data-account-visitor>Your account page</button></p>`;
function askName(note = "", typed = "") {
  attempt++;
  stopWatching();
  phoneAbort?.abort();
  phoneAbort = null;
  missingFor = null;
  view(`<h2 id="si-title">Sign in</h2>
    <form data-form="name"><label for="si-who">Your Nostr address or npub</label>
      <input id="si-who" name="who" class="acct-who" type="text" value="${esc(typed)}" placeholder="name@site.com or npub1…" autocomplete="username" autocapitalize="off" autocorrect="off" spellcheck="false" required>
      <div class="si-actions"><button type="submit" class="si-button">Continue</button></div></form>
    ${note ? `<div class="si-said" role="status">${note}</div>` : `<p class="si-or"><span>or</span></p>${PHONE_BUTTON}`}
    <div class="si-actions"><button type="button" class="link si-small" data-si="signer">Use a signer app</button></div>
    ${ACCOUNT_PAGE}`);
}
// An email, not a Nostr address: a mail provider's domain. (Any other name@domain is looked up, and one that finds no
// account is answered the same way.)
const EMAIL = /@(gmail|googlemail|yahoo|ymail|outlook|hotmail|live|msn|icloud|me|mac|aol|proton|protonmail|pm|gmx|web|yandex|mail|zoho|fastmail|hey|duck|tutanota|tuta|comcast|att|verizon)\.[a-z.]+$/i;
const notEmail = (typed) => `<p>${esc(typed)} looks like an email address. Marble doesn't use email yet. Try your Nostr address, or sign in with your phone.</p>${PHONE_BUTTON}`;

async function lookUp(typed) {
  const mine = ++attempt;
  lastTyped = typed;
  stopWatching();
  phoneAbort?.abort();
  phoneAbort = null;
  if (EMAIL.test(typed)) return askName(notEmail(typed), typed);
  view(`<h2 id="si-title">Sign in</h2><p class="si-wait">${spinner}Looking for ${esc(typed)}…</p>
    <div class="si-actions"><button type="button" class="si-button" data-si="retype">Cancel</button></div>`);
  const { whose, loginPage } = await import("./pointer.js");
  const pk = await whose(typed);
  if (mine !== attempt) return;
  if (!pk) return askName(typed.includes("@") ? notEmail(typed) : `<p>Marble couldn't find ${esc(typed)}. Check the name, or sign in with your phone.</p>${PHONE_BUTTON}`, typed);
  const r = await loginPage(pk);
  if (mine !== attempt) return;
  if (r.template) return atLoginPage(pageOf(r.template), { who: pk });
  if (r.removed) forgetPage(pk);
  const kept = rememberedPage(pk); // a pointer seen after an earlier sign-in, should the relays miss it now
  kept ? atLoginPage(kept, { who: pk }) : withMarble({ missing: pk });
}

// ── Your account names a login page ──
function atLoginPage(page, { who = null, pending = null } = {}) {
  const host = new URL(page.origin).host;
  who ??= pending?.who || null;
  run((uri) => view(`<h2 id="si-title">Sign in</h2>
      ${who ? `<div class="si-who">${avatar(who, 40)}<span class="si-acct"><span class="si-name">${esc(name(who))}</span><span class="si-fp">${npub(who).slice(0, 12)}…<b>${npub(who).slice(-6)}</b></span></span></div>` : ""}
      <p>Your account names this page to sign in at:</p>
      <p class="acct-addr">${esc(host)}</p>
      <p>It isn't part of Marble. You sign in there with a passkey, a password or your phone, and come back here signed in.</p>
      <a class="si-button si-open" href="${esc(loginLink(page.template, uri))}" target="_blank" rel="noopener" data-si="away">Sign in at ${esc(host)}</a>
      <p class="si-wait"${pending ? "" : " hidden"}>${spinner}Waiting for ${esc(host)}…</p>
      <div class="si-actions"><button type="button" class="link" data-si="retype">Not you?</button><button type="button" class="link" data-si="code">Use your phone instead</button></div>`),
    { pending, keep: { page: page.template, who } });
}

// ── Marble on your phone: the button on the first screen (no lookup ran), or a name with no login page (missing) ──
let signerFrom = "phone"; // where the signer app's Back goes
let missingFor = null; // the account a name lookup found no pointer for (its codes carry kk_login=missing)
const missingCode = () => ({ extra: missingFor ? "&kk_login=missing" : "", missing: missingFor });
function withMarble({ missing = null } = {}) {
  missingFor = missing;
  run((uri, svg) => {
    if (onPhone()) view(`<h2 id="si-title">Sign in with Marble</h2>
      <a class="si-button si-open" href="${esc(phoneLink(uri))}" data-si="open">Open Marble</a>
      <p class="si-wait" hidden>${spinner}Waiting for Marble…</p>
      <p class="si-note" data-none hidden>Marble didn't open.</p>
      ${onIphone() ? `<p data-none hidden>Marble isn't on iPhone yet. nsec.app works in Safari: copy the code and paste it there.</p>` : ""}
      <p class="si-get">No Marble yet? <a class="link" href="${onIphone() ? NSEC_APP : GET_MARBLE}" target="_blank" rel="noopener">${onIphone() ? "Open nsec.app" : "Get Marble for Android"}</a></p>
      <div class="si-actions"><button type="button" class="link" data-si="signer">Use a signer app</button><button type="button" class="link" data-si="retype">Back</button></div>`);
    else view(`<h2 id="si-title">${missing ? "Sign in with Marble on your phone" : "Sign in with your phone"}</h2>
      <div class="qr">${svg}</div>
      <p>Scan this with your phone. With Marble on it, Marble opens and asks you to sign in.</p>
      <p class="si-wait">${spinner}Waiting for your phone…</p>
      <p class="si-get">No Marble yet? <a class="link" href="${GET_MARBLE}" target="_blank" rel="noopener">Get Marble for Android</a></p>
      <div class="si-actions"><button type="button" class="link si-small" data-si="signer">Use a signer app</button><button type="button" class="link" data-si="retype">Back</button></div>`);
    if (noAppSoon) noApp();
  }, missingCode());
}

// ── Another signer app (for nostr nerds): the plain code, or a bunker link ──
function withSigner() {
  run((uri, svg) => view(`<h2 id="si-title">Use a signer app</h2>
      <div class="qr">${svg}</div>
      <p>Scan this nostrconnect code with your signer app, or paste its bunker link.</p>
      <form data-form="bunker"><label class="visually-hidden" for="si-bunker">Bunker link</label>
        <input id="si-bunker" name="bunker" class="acct-who" type="text" placeholder="bunker://…" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false">
        <p class="si-note" data-bunker-note hidden></p>
        <div class="si-actions"><button type="button" class="link" data-si="copy" data-code="${esc(uri)}">Copy the code</button><button type="button" class="link" data-si="unsigner">Back</button><button type="submit" class="si-button">Connect</button></div></form>`), missingCode());
}

// ── A pointer the phone put back ──
// Signed in from a name with no pointer: the phone may publish it as it answers (kk_login=missing). Looked up once more a
// little after, and kept for next time in this browser. Nothing changes mid-sign-in.
const PAGE = (pk) => `marble.loginpage.${pk}`;
function rememberedPage(pk) { try { return pageOf(localStorage.getItem(PAGE(pk))); } catch { return null; } } // the template
function forgetPage(pk) { try { localStorage.removeItem(PAGE(pk)); } catch {} }
export async function lookAgain(pk, wait = 8000) { // (the phone's pointer reaches your write relays within a few seconds)
  await new Promise((r) => setTimeout(r, wait));
  const { loginPage } = await import("./pointer.js");
  const r = await loginPage(pk).catch(() => null);
  try { if (r?.template) localStorage.setItem(PAGE(pk), r.template); } catch {}
  return r?.template || null;
}

let again = null;
function finish(done) {
  clearPending();
  signedInWithPhone(done);
  close();
  toast("Signed in");
}
/** Makes a code (or picks up a pending one), draws it with render(uri, svg), and signs in once it's answered. */
async function run(render, { pending = null, keep = null, extra = "", missing = null } = {}) {
  const mine = ++attempt;
  stopWatching();
  phoneAbort?.abort();
  const ctrl = (phoneAbort = new AbortController());
  again = () => run(render, { keep, extra, missing });
  view(`<h2 id="si-title">Sign in</h2><p class="si-wait">${spinner}Making a code…</p>`);
  const life = keep ? PENDING_LIFE * 1000 - (pending ? Date.now() - pending.made * 1000 : 0) : 180000;
  const expire = setTimeout(() => {
    if (mine !== attempt) return;
    ctrl.abort();
    clearPending();
    view(`<h2 id="si-title">This code has expired</h2><p>Make a new one to sign in.</p>
      <div class="si-actions"><button type="button" class="link" data-si="name">Back</button><button type="button" class="si-button" data-si="again">New code</button></div>`);
  }, Math.max(0, life));
  try {
    const { phoneSignIn } = await import("./connect.js");
    const done = await phoneSignIn({
      signal: ctrl.signal,
      pending,
      extra,
      onPending: keep ? (p) => { try { sessionStorage.setItem(PENDING, JSON.stringify({ ...p, ...keep, who: keep.who || pending?.who || null })); } catch {} } : null,
      // the site's name the sign-in app shows: the page's own <meta name="application-name">
      name: document.querySelector('meta[name="application-name"]')?.content || location.hostname,
      onCode: (uri, svg) => { if (mine === attempt) render(uri, svg); },
    });
    if (mine !== attempt) return done.remote.stop();
    finish(done);
    if (missing && done.pubkey === missing) lookAgain(missing);
  } catch (err) {
    if (mine !== attempt || ctrl.signal.aborted) return;
    clearPending();
    view(`<h2 id="si-title">Sign-in didn't finish</h2><p>${esc(String(err?.message || "").replace(/^[a-z-]+: /, "") || "Try again with a new code.")}</p>
      <div class="si-actions"><button type="button" class="link" data-si="name">Back</button><button type="button" class="si-button" data-si="again">Try again</button></div>`);
  } finally { clearTimeout(expire); }
}

async function withBunker(link, form) {
  const mine = attempt;
  const note = $("[data-bunker-note]", form), button = $("button[type=submit]", form);
  note.hidden = false;
  note.innerHTML = `${spinner}Connecting… approve it in your signer app.`;
  button.disabled = true;
  try {
    const { bunkerSignIn } = await import("./connect.js");
    const done = await bunkerSignIn(link);
    if (mine !== attempt) return done.remote.stop();
    finish(done);
  } catch (err) {
    if (mine !== attempt) return;
    note.textContent = String(err?.message || "That didn't connect.").replace(/^timeout$/, "Your signer app didn't answer.");
    button.disabled = false;
  }
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
  if (what === "away") { const w = $(".si-wait", box); if (w) w.hidden = false; return; }
  if (what === "close") close();
  else if (what === "name") { clearPending(); askName(); }
  else if (what === "retype") { clearPending(); askName("", lastTyped); }
  else if (what === "code") { clearPending(); withMarble(); }
  else if (what === "unsigner") signerFrom === "name" ? askName("", lastTyped) : withMarble({ missing: missingFor });
  else if (what === "signer") { signerFrom = $("#si-who", box) ? "name" : "phone"; withSigner(); }
  else if (what === "again") again?.();
  else if (what === "copy") navigator.clipboard?.writeText(b.dataset.code).then(() => { b.textContent = "Copied"; });
});
dialog.addEventListener("submit", (ev) => {
  ev.preventDefault();
  const f = ev.target, data = new FormData(f);
  if (f.dataset.form === "name") { const t = String(data.get("who") || "").trim(); if (t) lookUp(t); }
  else if (f.dataset.form === "bunker") { const l = String(data.get("bunker") || "").trim(); if (l) withBunker(l, f); }
});
// Back in this tab from the login page with a code still waiting: the sign-in picks up where it was.
if (!current() && pendingCode()) setTimeout(() => openSignIn(), 0);
dialog.addEventListener("cancel", (ev) => { ev.preventDefault(); if (!picking) close(); });

// ── For a site's own flows in this dialog (Marble Web's "Adding Marble on a Mac") ──
// They draw into the same dialog, count attempts with it (a newer attempt makes an older one's late answer count for
// nothing), and share the name last typed.
export { dialog, view, pageOf, rememberedPage, forgetPage, EMAIL };
export const SPINNER = spinner;
/** Starts a new attempt: ++attempt. */
export const nextAttempt = () => ++attempt;
/** The attempt now: an answer for an older one is late. */
export const attemptNow = () => attempt;
export const lastTypedName = () => lastTyped;
export const setLastTyped = (t) => { lastTyped = t; };
