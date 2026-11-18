// The account button. Marble doesn't handle a login itself: an account says where its own login page is, in a small
// event it signed (Keykin's login-page pointer: kind 30078, d "keykin/login", a "url" tag holding an https origin), and
// this reads that event, checks it, shows the address, and only then offers the way there. The page it leads to is
// another site with another origin, as accounts.google.com is to the sites that send you there.
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { schnorr, sha256 } from "./vendor/noble.js";
import { outbox, resolveNip05 } from "./data.js";
import { decode, isHex64, toHex, fromHex } from "./ids.js";

export const LOGIN_D = "keykin/login";

/** An https origin and nothing else (no path, query, fragment, user or password), as the pointer must be. Or null. */
export function originOnly(url) {
  if (typeof url !== "string" || url.length > 200) return null;
  let u;
  try { u = new URL(url); } catch { return null; }
  // testing on localhost: a login page on this machine (http://localhost:port) counts too, and only then
  const local = typeof location !== "undefined" && /^(localhost|127\.0\.0\.1)$/.test(location.hostname) && u.protocol === "http:" && /^(localhost|127\.0\.0\.1)$/.test(u.hostname);
  if ((u.protocol !== "https:" && !local) || u.username || u.password || u.search || u.hash || (u.pathname !== "/" && u.pathname !== "")) return null;
  // what was written must be the origin itself, not something a parser tidied into one
  return url.replace(/\/$/, "") === u.origin ? u.origin : null;
}

/**
 * The whole check, every time: the id is the hash of exactly these fields, and the signature is the author's over it.
 * (pool.js's verify remembers its answer by id, which is right for showing posts but not for this: a copy with the same
 * id and another address must not pass, since the address is where someone is about to be sent.)
 */
function signedAsIs(e) {
  try {
    if (!isHex64(e.id) || !isHex64(e.pubkey) || typeof e.sig !== "string" || !/^[0-9a-f]{128}$/i.test(e.sig)) return false;
    if (!Number.isInteger(e.created_at) || typeof e.content !== "string") return false;
    const hash = toHex(sha256(new TextEncoder().encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));
    return hash === e.id && schnorr.verify(fromHex(e.sig), fromHex(e.id), fromHex(e.pubkey));
  } catch { return false; }
}

/** The login page a pointer event names, if it's that account's own, signed, and a plain https origin. Or null. */
export function pageFrom(event, pubkey) {
  if (!event || event.kind !== 30078 || event.pubkey !== pubkey || !Array.isArray(event.tags)) return null;
  if (!event.tags.some((t) => t[0] === "d" && t[1] === LOGIN_D)) return null;
  if (!signedAsIs(event)) return null;
  return originOnly(event.tags.find((t) => t[0] === "url")?.[1]);
}

/**
 * Where this account's login page is: { url } (the newest pointer names one), { none: true } (no pointer, or the
 * newest one names nothing: it was taken away), or { unreachable: true } (no relay answered).
 */
export async function loginPage(pubkey, { timeout = 4500 } = {}) {
  const filter = { kinds: [30078], authors: [pubkey], "#d": [LOGIN_D] };
  // the fast relays and the indexers at once, then the account's own write relays if those had nothing
  let found = await req(relays(FAST, INDEXERS), filter, { timeout, settle: 700 });
  if (!found.length) {
    const own = await outbox(pubkey).catch(() => null);
    const more = relays(own?.write || own || []).filter((r) => !relays(FAST, INDEXERS).includes(r));
    if (more.length) found = await req(more, filter, { timeout, settle: 700 });
  }
  // newest wins, and only a signed one of this account's counts: a relay can't make one up
  const mine = found.filter((e) => e.pubkey === pubkey && e.kind === 30078 && Array.isArray(e.tags) && e.tags.some((t) => t[0] === "d" && t[1] === LOGIN_D) && signedAsIs(e))
    .sort((a, b) => b.created_at - a.created_at)[0];
  if (!mine) return { none: true };
  const url = pageFrom(mine, pubkey);
  return url ? { url, event: mine } : { none: true, removed: true }; // (taken away: the newest names nothing)
}

/** Who was typed: an npub or nprofile, a 64-character key, or a name@domain. The account's key, or null. */
export async function whose(typed) {
  const t = String(typed || "").trim().replace(/^nostr:/, "");
  if (!t) return null;
  if (isHex64(t)) return t.toLowerCase();
  if (/^n(pub|profile)1/.test(t)) { const d = decode(t); return d?.type === "profile" ? d.pubkey : null; }
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t) || /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(t)) return (await resolveNip05(t.toLowerCase()).catch(() => null)) || null;
  return null;
}

// ── The dialog ────────────────────────────────────────────────────────────────────────────────

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const spinner = `<span class="spinner" aria-hidden="true"></span>`;
let dialog = null, box = null, attempt = 0;

function ensure() {
  if (dialog) return;
  dialog = document.createElement("dialog");
  dialog.id = "acct";
  dialog.setAttribute("aria-labelledby", "acct-title");
  dialog.innerHTML = `<div class="si"></div>`;
  document.body.append(dialog);
  box = dialog.firstElementChild;
  dialog.addEventListener("click", (ev) => {
    if (ev.target === dialog || ev.target.closest("[data-acct='close']")) return dialog.close();
    if (ev.target.closest("[data-acct='other']")) return ask();
  });
  dialog.addEventListener("submit", (ev) => {
    ev.preventDefault();
    look(null, new FormData(ev.target).get("who"));
  });
  dialog.addEventListener("close", () => { attempt++; });
}
const view = (html) => { box.innerHTML = html; box.querySelector("input, a.si-open, button.si-button")?.focus(); };

function ask(note = "") {
  view(`<h2 id="acct-title">Your account page</h2>
    <p>Where you manage your account: your devices, what's signed in, your backup. Marble finds the page your account names and sends you there.</p>
    ${note ? `<p class="si-note">${esc(note)}</p>` : ""}
    <form><label class="visually-hidden" for="acct-who">Your npub or name</label>
      <input id="acct-who" name="who" class="acct-who" type="text" placeholder="npub1… or you@example.com" autocomplete="username" autocapitalize="off" autocorrect="off" spellcheck="false">
      <div class="si-actions"><button type="button" class="link" data-acct="close">Cancel</button><button type="submit" class="si-button">Find it</button></div></form>`);
}

async function look(pubkey, typed) {
  const mine = ++attempt;
  view(`<h2 id="acct-title">Your account page</h2><p class="si-wait">${spinner}Looking for it…</p>
    <div class="si-actions"><button type="button" class="si-button" data-acct="close">Cancel</button></div>`);
  const pk = pubkey || await whose(typed);
  if (mine !== attempt) return;
  if (!pk) return ask("That isn't an npub or a name Marble could find.");
  const r = await loginPage(pk);
  if (mine !== attempt) return;
  const other = `<button type="button" class="link" data-acct="other">${pubkey ? "Look up another account" : "Try another"}</button>`;
  if (r.url) {
    // the address is shown, in full, before anything goes there; the link is an ordinary one, so the browser shows it too
    const host = new URL(r.url).host;
    return view(`<h2 id="acct-title">Your account page</h2>
      <p>${pubkey ? "Your account" : "That account"} names this page as the place to manage it:</p>
      <p class="acct-addr">${esc(host)}</p>
      <p>It isn't part of Marble. You sign in there with a passkey, a password or your phone.</p>
      <a class="si-button si-open" href="${esc(r.url)}" rel="noopener noreferrer">Go to ${esc(host)}</a>
      <div class="si-actions">${other}<button type="button" class="link" data-acct="close">Cancel</button></div>`);
  }
  view(`<h2 id="acct-title">Your account page</h2>
    <p class="si-note">${pubkey ? "Your account hasn't" : "That account hasn't"} named a login page.</p>
    <p>If you use Marble on Android with a Keykin box: open Your account, then Your account page, and set its address there.</p>
    <div class="si-actions">${other}<button type="button" class="si-button" data-acct="close">OK</button></div>`);
}

/** Open it: for the account that's signed in here ([pubkey]), or, with none, after asking whose. */
export function openAccountPage(pubkey = null) {
  ensure();
  if (!dialog.open) dialog.showModal();
  pubkey ? look(pubkey) : ask();
}
