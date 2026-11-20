// A short message at the bottom of the page, and "Approve it on your phone…" while something waits on you. Moved from
// Marble Web's act.js. The page styles #toast (signin.css has Marble Web's rules).
import { current } from "./me.js";

const $ = (s, el = document) => el.querySelector(s);

let toastTimer = null;
/** progress: 0..1 shows a bar under the words (a picture going up), null none. */
export function toast(text, { stay = false, progress = null } = {}) {
  let el = $("#toast");
  if (!el) { el = document.createElement("div"); el.id = "toast"; el.setAttribute("role", "status"); document.body.append(el); }
  if (el.dataset.text !== text || (progress === null) !== !el.querySelector(".toast-bar")) {
    el.textContent = text;
    el.dataset.text = text;
    if (progress !== null) el.insertAdjacentHTML("beforeend", `<span class="toast-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100"><span></span></span>`);
  }
  const bar = el.querySelector(".toast-bar");
  if (bar) { const pc = Math.round(Math.max(0, Math.min(1, progress)) * 100); bar.firstChild.style.width = pc + "%"; bar.setAttribute("aria-valuenow", pc); }
  el.hidden = !text;
  clearTimeout(toastTimer);
  if (text && !stay) toastTimer = setTimeout(() => (el.hidden = true), 3500);
}

/** While something waits on you approving it elsewhere, say where, after a moment. */
export async function approving(promise) {
  let asked = false;
  const t = setTimeout(() => { asked = true; toast("Approve it on your phone…", { stay: true }); }, 700);
  // (only its own message goes: one said meanwhile, like "Signed out", stays)
  try { return await promise; } finally { clearTimeout(t); if (asked && current()) toast(""); }
}
