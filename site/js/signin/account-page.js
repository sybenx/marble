// The account button's dialog ("Your account page"): asks whose account, reads its login-page pointer (pointer.js),
// shows the address in full, and only then offers the way there. Moved from Marble Web's account-page.js.
import { whose, loginPage } from "./pointer.js";

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
