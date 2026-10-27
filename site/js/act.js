// Doing things as you: signing in (the extension, or your phone's code), liking and reacting, replying.
// What you do shows at once and is taken back if it can't be sent.
import { publish, req, count, relays, foundOn, FAST } from "./pool.js";
import { outbox, keep } from "./data.js";
import { esc } from "./render.js";
import { current, method, hasExtension, signInWithExtension, signedInWithPhone, sign, myWriteRelays, onMe } from "./me.js";

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
  const t = setTimeout(() => toast(method() === "phone" ? "Approve it on your phone…" : "Waiting for Marble… approve in the extension window", { stay: true }), 700);
  try { return await promise; } finally { clearTimeout(t); toast(""); }
}

// ── Signing in ───────────────────────────────────────────────────────────────────────────────────────

const dialog = $("#signin");
const box = $(".si", dialog);
let attempt = 0; // a newer attempt makes an older one's late answer count for nothing
let phoneAbort = null;
const spinner = `<span class="spinner" aria-hidden="true"></span>`;
/** On a phone or tablet (touch first): the sign-in code is opened in Marble on this device, not scanned. */
const onPhone = () => matchMedia("(pointer: coarse)").matches && /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

export function openSignIn() {
  if (!dialog.open) dialog.showModal();
  hasExtension() ? withExtension() : withPhone();
}
function close() {
  attempt++;
  phoneAbort?.abort();
  phoneAbort = null;
  if (dialog.open) dialog.close();
}
function view(html) { box.innerHTML = html; }

async function withExtension() {
  const mine = ++attempt;
  phoneAbort?.abort();
  view(`<h2 id="si-title">Waiting for Marble…</h2>
    <p class="si-wait">${spinner}Approve in the extension window.</p>
    <p class="si-help" hidden>Open the Marble extension to approve.</p>
    <div class="si-actions"><button type="button" class="link" data-si="phone">Sign in with your phone instead</button><button type="button" class="si-button" data-si="close">Cancel</button></div>`);
  const help = setTimeout(() => { const h = $(".si-help", box); if (h && mine === attempt) h.hidden = false; }, 3000);
  const gaveUp = setTimeout(() => {
    if (mine !== attempt) return;
    attempt++;
    view(`<h2 id="si-title">No answer from the extension</h2><p>Open the Marble extension and try again, or sign in with your phone.</p>
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
  phoneAbort?.abort();
  const ctrl = (phoneAbort = new AbortController());
  view(`<h2 id="si-title">Sign in with your phone</h2><p class="si-wait">${spinner}Making a code…</p>`);
  const expire = setTimeout(() => {
    if (mine !== attempt) return;
    ctrl.abort();
    view(`<h2 id="si-title">This code has expired</h2><p>Make a new one and scan it with Marble on your phone.</p>
      <div class="si-actions"><button type="button" class="si-button" data-si="phone">New code</button></div>`);
  }, 180000);
  try {
    const { phoneSignIn } = await import("./connect.js");
    const done = await phoneSignIn({
      signal: ctrl.signal,
      onCode: (uri, svg) => {
        if (mine !== attempt) return;
        // On the phone itself a code to scan can't be scanned: the code is a link that opens Marble instead,
        // and the picture is there for signing in with another phone.
        if (onPhone()) return view(`<h2 id="si-title">Sign in</h2>
          <a class="si-button si-open" href="${esc(uri)}" data-si="open">Sign in with Marble</a>
          <p class="si-wait" hidden>${spinner}Waiting for Marble…</p>
          <div class="qr" hidden>${svg}</div>
          <div class="si-actions"><button type="button" class="link" data-si="qr">Show code</button>
            <button type="button" class="link" data-si="copy" data-code="${esc(uri)}">Copy the code</button><button type="button" class="link" data-si="close">Cancel</button></div>`);
        view(`<h2 id="si-title">Sign in with your phone</h2>
          <div class="qr">${svg}</div>
          <p>Open Marble on your phone, tap Scan, and scan this code.</p>
          <p class="si-wait">${spinner}Waiting for your phone…</p>
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
  if (ev.target === dialog) return close(); // the backdrop
  const b = ev.target.closest("[data-si]");
  if (!b) return;
  const what = b.dataset.si;
  if (what === "open") { $(".si-wait", box).hidden = false; return; } // the link opens Marble; this page waits for it
  if (what === "qr") { $(".qr", box).hidden = false; b.remove(); return; }
  if (what === "close") close();
  else if (what === "phone") withPhone();
  else if (what === "extension") withExtension();
  else if (what === "copy") navigator.clipboard?.writeText(b.dataset.code).then(() => { b.textContent = "Copied"; });
});
dialog.addEventListener("cancel", (ev) => { ev.preventDefault(); close(); });

/** Something only a signed-in account can do: true if it can go ahead, else the sign-in opens. */
function canAct() {
  if (!current()) { openSignIn(); return false; }
  if (!method()) { toast("Signed in to look only: sign in again to do this."); return false; }
  return true;
}

// ── Reactions ────────────────────────────────────────────────────────────────────────────────────────

const HEART = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20.5s-7.5-4.6-9.3-9.5C1.4 7.4 3.7 4 7.2 4c2 0 3.6 1.1 4.8 2.8C13.2 5.1 14.8 4 16.8 4c3.5 0 5.8 3.4 4.5 7-1.8 4.9-9.3 9.5-9.3 9.5z"/></svg>`;
const DEFAULTS = ["❤️", "😂", "😮", "😢", "🔥", "🤙"];
const mine = new Map(); // post id → your reaction's content (your newest)
const mineAt = new Map(); // post id → when you reacted
const checked = new Set(); // post ids already looked up
const tallies = new Map(); // post id → {top: [emoji], likes: n}: everyone else's, beside the heart
let favourites = []; // your most-used reactions, from your own history

/** The heart under a post: filled when you've reacted (your emoji if it wasn't a plain like). */
export function heart(e) {
  return `<button type="button" class="heart" data-react="${e.id}" data-author="${e.pubkey}" data-kind="${e.kind}" aria-label="Like" aria-pressed="false">${HEART}</button>`
    + `<span class="tally" data-tally="${e.id}"></span>`;
}

function paintHearts(root = document) {
  for (const b of root.querySelectorAll("[data-react]")) {
    const r = mine.get(b.dataset.react);
    b.setAttribute("aria-pressed", r ? "true" : "false");
    b.classList.toggle("emoji", !!r && r !== "+" && r !== "❤️");
    b.innerHTML = r && r !== "+" && r !== "❤️" ? `<span>${esc(r.startsWith(":") ? "★" : r)}</span>` : HEART;
    b.setAttribute("aria-label", r ? "You reacted: change it" : "Like");
  }
  // others' top three reactions (not plain likes or custom ones) and the total, as the app shows them: "🔥🤙 45"
  for (const el of root.querySelectorAll("[data-tally]")) {
    const t = tallies.get(el.dataset.tally);
    el.textContent = t?.likes ? `${t.top.join("")} ${t.likes}`.trim() : "";
  }
}

/** After a page draws: which of its posts you've already reacted to (one request for all of them). */
export async function markReactions(root) {
  paintHearts(root);
  const ids = [...root.querySelectorAll("[data-react]")].map((b) => b.dataset.react).filter((id) => !checked.has(id));
  if (!ids.length) return;
  ids.forEach((id) => checked.add(id));
  const me = current();
  const where = me ? await myWriteRelays().catch(() => []) : [];
  // Everyone's reactions to these posts (one request), and the servers' own like counts (NIP-45).
  const found = new Map(ids.map((id) => [id, new Map()])); // post id → reactor → newest reaction
  const counted = Promise.all(ids.map((id) => count(ALSO, { kinds: [7], "#e": [id] }).then((n) => [id, n])));
  await req(relays(ALSO, where, me ? FAST.slice(2, 4) : []), { kinds: [7], "#e": ids, limit: 3000 }, { timeout: 4000, settle: 800, onEvent: (r) => {
    const id = (r.tags.filter((t) => t[0] === "e").pop() || [])[1];
    if (!found.has(id) || r.content === "-") return;
    const old = found.get(id).get(r.pubkey);
    if (!old || old.created_at < r.created_at) found.get(id).set(r.pubkey, r);
  } });
  for (const [id, n] of await counted) {
    const theirs = [...found.get(id).values()].filter((r) => r.pubkey !== me);
    const counts = new Map();
    for (const r of theirs) if (r.content && r.content !== "+" && !/^:.*:$/.test(r.content)) counts.set(r.content, (counts.get(r.content) || 0) + 1);
    const top = [...counts].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([c]) => c);
    tallies.set(id, { top, likes: Math.max(theirs.length, (n || 0) - (found.get(id).has(me) ? 1 : 0)) });
    const own = me && found.get(id).get(me);
    if (own && !(mineAt.get(id) > own.created_at)) { mine.set(id, own.content || "+"); mineAt.set(id, own.created_at); }
  }
  paintHearts(document);
}

async function loadFavourites() {
  const me = current();
  favourites = [];
  if (!me) return;
  const where = await myWriteRelays().catch(() => []);
  const list = await req(relays(where, ALSO), { kinds: [7], authors: [me], limit: 300 }, { timeout: 4000, settle: 800 });
  const counts = new Map();
  for (const r of list) if (r.content && !["+", "-"].includes(r.content) && !r.content.startsWith(":") && [...r.content].length <= 4) counts.set(r.content, (counts.get(r.content) || 0) + 1);
  favourites = [...counts].sort((a, b) => b[1] - a[1]).map(([c]) => c).filter((c) => !DEFAULTS.includes(c)).slice(0, 4);
}
onMe(() => { mine.clear(); checked.clear(); loadFavourites(); markReactions(document); });
loadFavourites();

// Reacting again replaces your reaction: a new one is sent, and the newest is what shows (as in the app).
async function react(b, content) {
  if (!canAct()) return;
  const id = b.dataset.react, author = b.dataset.author;
  const before = mine.get(id), beforeAt = mineAt.get(id);
  if (before === content) return;
  mine.set(id, content);
  mineAt.set(id, Math.floor(Date.now() / 1000));
  paintHearts(document);
  try {
    const ev = await approving(sign({ kind: 7, content, tags: [["e", id, foundOn(id)[0] || "", author], ["p", author], ["k", String(b.dataset.kind || 1)]] }));
    const theirs = await Promise.race([outbox(author).then((o) => o.read.slice(0, 3)), new Promise((r) => setTimeout(() => r([]), 1500))]);
    const sent = await publish(relays(await myWriteRelays(), theirs, ALSO), ev);
    if (!sent.ok.length) throw new Error("nowhere took it");
  } catch (err) {
    if (before) { mine.set(id, before); mineAt.set(id, beforeAt); } else { mine.delete(id); mineAt.delete(id); }
    paintHearts(document);
    toast(/refus|reject|denied|cancel/i.test(String(err?.message)) ? "Not sent: you said no." : "Couldn't send your reaction. Try again.");
  }
}

// The row of reactions: long-press (touch) or hover (mouse) on a heart.
let row = null, hoverTimer = null, leaveTimer = null, pressTimer = null, pressed = false;
function openRow(b) {
  closeRow();
  row = document.createElement("div");
  row.className = "emoji-row";
  row.setAttribute("role", "menu");
  row.innerHTML = [...favourites, ...DEFAULTS].map((c) => `<button type="button" role="menuitem" data-emoji="${esc(c)}">${esc(c)}</button>`).join("")
    + `<button type="button" role="menuitem" class="any" data-any aria-label="Any emoji">+</button>`;
  row.target = b;
  document.body.append(row);
  const r = b.getBoundingClientRect();
  const w = row.offsetWidth;
  row.style.left = `${Math.max(8, Math.min(innerWidth - w - 8, r.left + r.width / 2 - w / 2 + scrollX))}px`;
  row.style.top = `${r.top + scrollY - row.offsetHeight - 8}px`;
  row.addEventListener("mouseenter", () => clearTimeout(leaveTimer));
  row.addEventListener("mouseleave", () => { leaveTimer = setTimeout(closeRow, 300); });
}
function closeRow() { row?.remove(); row = null; }

document.addEventListener("click", (ev) => {
  const pick = ev.target.closest(".emoji-row [data-emoji]");
  if (pick) { const b = row.target; closeRow(); return react(b, pick.dataset.emoji); }
  if (ev.target.closest(".emoji-row [data-any]")) {
    const b = row.target;
    row.innerHTML = `<input type="text" inputmode="text" maxlength="8" placeholder="Any emoji" aria-label="Any emoji"><button type="button" data-any-ok>OK</button>`;
    $("input", row).focus();
    row.target = b;
    return;
  }
  if (ev.target.closest(".emoji-row [data-any-ok]")) {
    const v = $("input", row).value.trim();
    const b = row.target;
    closeRow();
    if (v) react(b, v);
    return;
  }
  const b = ev.target.closest("[data-react]");
  if (b) {
    ev.preventDefault();
    clearTimeout(hoverTimer); // a click is a like: no row popping up under the resting mouse after it
    if (row?.target !== b || !pressed) closeRow();
    if (pressed) { pressed = false; return; } // the long-press opened the row
    if (mine.has(b.dataset.react)) return openRow(b); // already reacted: pick another to change it
    return react(b, "+");
  }
  if (row && !ev.target.closest(".emoji-row")) closeRow();
}, true);
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") closeRow();
  if (ev.key === "Enter" && ev.target.closest?.(".emoji-row input")) $(".emoji-row [data-any-ok]")?.click();
});
document.addEventListener("pointerdown", (ev) => {
  const b = ev.target.closest("[data-react]");
  if (!b || ev.pointerType === "mouse") return;
  pressed = false;
  pressTimer = setTimeout(() => { pressed = true; openRow(b); }, 450);
});
document.addEventListener("pointerup", () => clearTimeout(pressTimer));
document.addEventListener("pointercancel", () => clearTimeout(pressTimer));
document.addEventListener("contextmenu", (ev) => { if (ev.target.closest("[data-react]")) ev.preventDefault(); });
if (matchMedia("(hover: hover)").matches) {
  document.addEventListener("mouseover", (ev) => {
    const b = ev.target.closest?.("[data-react]");
    if (!b) return;
    clearTimeout(leaveTimer);
    if (row?.target === b) return;
    if (row) closeRow();
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => openRow(b), 500);
    b.addEventListener("mouseleave", () => { clearTimeout(hoverTimer); leaveTimer = setTimeout(closeRow, 300); }, { once: true });
  });
}

// ── Replies ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * Signs and sends a reply (the tags already worked out, NIP-10) to your write relays and the author's read
 * relays. Resolves with the event; throws a sentence to show if it couldn't go.
 */
export async function sendReply(to, content, tags) {
  if (!canAct()) throw new Error("");
  let ev;
  try { ev = await approving(sign({ kind: 1, content, tags })); }
  catch (err) { throw new Error(/refus|reject|denied|cancel/i.test(String(err?.message)) ? "Not sent: you said no." : "Couldn't sign your reply. Try again."); }
  const theirs = await Promise.race([outbox(to.pubkey).then((o) => o.read.slice(0, 3)), new Promise((r) => setTimeout(() => r([]), 1500))]);
  const sent = await publish(relays(await myWriteRelays(), theirs, ALSO), ev);
  if (!sent.ok.length) throw new Error("Couldn't send your reply. Try again.");
  keep(ev);
  return { ev, places: sent.ok.length };
}

export { canAct };
