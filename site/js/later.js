// Notes and tabs, as the Marble app keeps them (android Later.kt; the Mac writes the same). Notes: what you typed
// and pressed Enter on, or kept; on your account as kind 30078 d=marble/later, NIP-44 to yourself, merged item by
// item (the newer `updated` wins; removed ones stay a month as markers so other devices learn it). Here they live
// in this browser, and sync only when you signed in with the Marble extension: signed in by your phone's code,
// they stay here (your servers don't encrypt for sites, on purpose). Tabs: pages opened from Marble, in this browser.
// Notes, Searches and Tabs belong to the account signed in (and those from while nobody was, to nobody): another
// account never sees them or gets them merged in, and signing out leaves none of them on the page
// (~/igloo/signin-signout-audit.md, fix 3).
import { req, relays, publish, FAST } from "./pool.js";
import { outbox } from "./data.js";
import { current, method, sign, nip44Self, onMe } from "./me.js";

export const TAG = "marble/later";
// this browser's copies, by account ("" while nobody's signed in)
const KEY = (pk) => pk ? `marble.later.${pk}` : "marble.later", TABS = (pk) => pk ? `marble.tabs.${pk}` : "marble.tabs";
const DIRTY = (pk) => pk ? `marble.later.dirty.${pk}` : "marble.later.dirty", SYNCED = (pk) => `marble.later.synced.${pk}`;
const now = () => Math.floor(Date.now() / 1000);
const MONTH = 30 * 86400;
const listeners = new Set();
export const onNotes = (fn) => (listeners.add(fn), () => listeners.delete(fn));

function read(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function write(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} }

// ── Notes ────────────────────────────────────────────────────────────────────────────────────────────

// Once: what was kept before accounts had their own copies goes to whoever is signed in (if nobody, it stays nobody's).
if (typeof localStorage !== "undefined" && !localStorage.getItem("marble.later.scoped")) {
  const pk = current();
  if (pk) for (const [from, to] of [["marble.later", KEY(pk)], ["marble.tabs", TABS(pk)], ["marble.searches", `marble.searches.${pk}`], ["marble.later.dirty", DIRTY(pk)]]) {
    const v = localStorage.getItem(from);
    if (v !== null && localStorage.getItem(to) === null) localStorage.setItem(to, v);
    localStorage.removeItem(from);
  }
  write("marble.later.scoped", 1);
}
const load = (pk) => { const d = read(KEY(pk), null); return d?.v === 1 && Array.isArray(d.items) ? d : { v: 1, items: [] }; };
let who = current() || "";
let doc = load(who); // the account copy's shape, unknown fields and all

const words = (s) => String(s || "").trim().replace(/\s+/g, " ").toLowerCase();
export const same = (a, b) => words(a) === words(b);
const newId = () => [...crypto.getRandomValues(new Uint8Array(8))].map((x) => x.toString(16).padStart(2, "0")).join("");
const next = (item) => Math.max(now(), (item.updated || 0) + 1);

/** The notes you see (not removed), newest first. */
export const items = () => doc.items.filter((i) => !i.deleted).sort((a, b) => b.created - a.created);
export const open = () => items().filter((i) => !i.done);
export const doneItems = () => items().filter((i) => i.done);
/** These words, typed and kept, not ticked: what the filled bookmark shows. */
export const saved = (text) => items().find((i) => !i.url && !i.done && same(i.text, text)) || null;

function put(item) {
  doc.items = doc.items.filter((i) => i.id !== item.id).concat(item);
  doc.at = Math.max(...doc.items.map((i) => i.updated || 0), now());
  write(KEY(who), doc);
  listeners.forEach((fn) => fn());
  changed();
}

/** Enter: the words kept as a note (never twice: the same words come back, unticked). Returns {item, fresh}. */
export function jot(text) {
  const t = String(text || "").trim().slice(0, 500);
  if (!t) return null;
  const have = doc.items.find((i) => !i.url && !i.deleted && same(i.text, t));
  const item = have ? { ...have, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null };
  put(item);
  return { item, fresh: !have };
}
/** A page kept as a note (from Tabs): by its address; kept again, it comes back rather than doubling. */
export function keepPage(url, title) {
  const t = String(title || url).trim().slice(0, 500);
  const have = doc.items.find((i) => i.url === url && !i.deleted);
  put(have ? { ...have, done: null, updated: next(have) } : { id: newId(), text: t, created: now(), updated: now(), done: null, deleted: null, url });
}
// ── Searches ─────────────────────────────────────────────────────────────────────────────────────────
// What you searched (Enter, or the browser's address bar), in this browser only, for 30 days: as the phone keeps them.
// A search edited within 2 minutes (it shares at least half of the shorter one's words, or one holds the other) takes
// the last one's place. Not notes: the bookmark keeps a search as a note.

const SEARCHES = (pk) => pk ? `marble.searches.${pk}` : "marble.searches";
export const searches = () => read(SEARCHES(who), []).filter((x) => x?.text && now() - x.at < MONTH);
export function searched(text) {
  const t = String(text || "").trim().slice(0, 500);
  if (!t) return;
  const list = searches(), last = list[0];
  const a = words(t), b = last ? words(last.text) : "";
  const aw = a.split(" "), bw = b.split(" ");
  const [short, long] = aw.length <= bw.length ? [aw, bw] : [bw, aw];
  const edit = last && now() - last.at <= 120 && (short.filter((x) => long.includes(x)).length * 2 >= short.length || a.includes(b) || b.includes(a));
  write(SEARCHES(who), [{ text: t, at: now() }, ...list.slice(edit ? 1 : 0).filter((x) => words(x.text) !== a)].slice(0, 200));
}

export const tick = (item, on) => put({ ...item, done: on ? now() : null, updated: next(item) });
/** Removed: only a marker stays (no text), for a month, so other devices learn it. */
export const remove = (item) => put({ ...item, text: "", deleted: now(), updated: next(item) });

/** The account's copy merged in; true if this browser has something the copy hasn't (so it should be sent). */
function merge(theirs) {
  if (theirs?.v !== 1 || !Array.isArray(theirs.items)) return false;
  const byId = new Map();
  for (const i of [...theirs.items, ...doc.items]) {
    if (!i?.id || (!String(i.text || "").trim() && !i.deleted)) continue;
    const old = byId.get(i.id);
    if (!old || (i.updated || 0) > (old.updated || 0)) byId.set(i.id, i);
  }
  const merged = [...byId.values()].filter((i) => !i.deleted || now() - i.deleted < MONTH);
  const { items: _, ...rest } = theirs;
  const before = JSON.stringify(theirs.items.map((i) => [i.id, i.updated]).sort());
  doc = { ...doc, ...rest, v: 1, items: merged, at: Math.max(...merged.map((i) => i.updated || 0), 0) };
  write(KEY(who), doc);
  listeners.forEach((fn) => fn());
  return JSON.stringify(merged.map((i) => [i.id, i.updated]).sort()) !== before;
}

// ── Syncing (signed in with the extension only) ──────────────────────────────────────────────────────

// An extension that can't encrypt for this site (paired to the phone: your servers don't, on purpose) doesn't sync.
let noEncrypt = false;
export const syncs = () => !!current() && method() === "extension" && !!window.nostr?.nip44 && !noEncrypt;
// The last try at syncing failed (not because it can't sync at all): Notes says so, quietly.
let failing = false;
export const syncFailing = () => syncs() && failing;
const failed = (err) => {
  if (/unsupported/i.test(String(err?.message || err))) noEncrypt = true;
  else failing = true;
  listeners.forEach((fn) => fn());
};
let lastPull = 0, sendTimer = null, dirty = read(DIRTY(who), false);
function changed() {
  dirty = true;
  write(DIRTY(who), true);
  if (!syncs()) return;
  clearTimeout(sendTimer);
  sendTimer = setTimeout(send, 10000);
}

async function where() {
  const o = await Promise.race([outbox(current()), new Promise((r) => setTimeout(() => r({ write: [], read: [] }), 2000))]);
  return { write: o.write.length ? o.write : FAST.slice(0, 3), both: relays(o.write, o.read, FAST.slice(0, 2)).slice(0, 8) };
}

/** Reads your account's copy and merges it in (at most once a minute unless forced). */
export async function pull({ force = false } = {}) {
  if (!syncs() || (!force && Date.now() - lastPull < 60000)) return false;
  lastPull = Date.now();
  const me = current();
  const got = (await req((await where()).both, { kinds: [30078], authors: [me], "#d": [TAG], limit: 4 }, { timeout: 5000, settle: 1000 }))
    .filter((e) => e.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
  if (me !== who) return false; // (signed out or in as someone else meanwhile)
  if (!got) { if (dirty) setTimeout(send, 0); return false; } // (nothing there yet: yours goes now)
  let theirs;
  try { theirs = JSON.parse(await nip44Self(got.content, false)); } catch (err) { failed(err); return false; }
  if (me !== who) return false;
  const ahead = merge(theirs);
  failing = false;
  write(SYNCED(me), true);
  if (ahead || dirty) changed();
  return true;
}

let sending = false;
/** Sends your notes to your account: read and merged first, so another device's changes are never lost. */
export async function send() {
  if (!syncs() || sending) return;
  sending = true;
  const me = who;
  try {
    lastPull = 0;
    await pull({ force: true }).catch(() => {});
    const content = await nip44Self(JSON.stringify(doc), true);
    const ev = await sign({ kind: 30078, tags: [["d", TAG]], content });
    const sent = await publish((await where()).write, ev);
    if (sent.ok.length && me === who) { dirty = false; failing = false; write(DIRTY(me), false); write(SYNCED(me), true); listeners.forEach((fn) => fn()); }
    else if (!sent.ok.length) failed(new Error("not sent"));
  } catch (err) { failed(err); } finally { sending = false; }
}

// Another account (or nobody): its own copy. The one signed out of leaves the page; if all of it is on the account
// (synced, nothing unsent), this browser's copy goes too; if not (a phone sign-in, or unsent changes), it stays, out
// of sight, for when that account signs in here again.
function switchTo(pk) {
  pk = pk || "";
  if (pk === who) return;
  const was = who;
  if (was && !pk && read(SYNCED(was), false) && !dirty) for (const k of [KEY(was), DIRTY(was)]) try { localStorage.removeItem(k); } catch {}
  who = pk;
  doc = load(pk);
  dirty = read(DIRTY(pk), false);
  noEncrypt = failing = false;
  lastPull = 0;
  clearTimeout(sendTimer);
  listeners.forEach((fn) => fn());
}

/** Notes made while nobody was signed in, offered to the account now signed in (never merged in by themselves). */
export const strays = () => (who ? (read(KEY(""), null)?.items || []).filter((i) => !i.deleted && String(i.text || "").trim()) : []);
export function adoptStrays() {
  for (const i of strays()) put({ ...i, updated: next(i) });
  dropStrays();
  changed();
}
export function dropStrays() {
  try { localStorage.removeItem(KEY("")); localStorage.removeItem(DIRTY("")); } catch {}
  listeners.forEach((fn) => fn());
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden" && dirty && syncs()) { clearTimeout(sendTimer); send(); }
    if (document.visibilityState === "visible") pull();
  });
  onMe((pk) => { switchTo(pk); pull(); });
  // when the page starts (the extension may arrive a moment later): your account's copy in, and yours out if unsent
  setTimeout(async () => {
    for (let i = 0; i < 10 && current() && !window.nostr; i++) await new Promise((r) => setTimeout(r, 150));
    if (!syncs()) return;
    if (dirty) send(); else pull({ force: true }).catch(() => {}); // (send reads and merges first)
  }, 500);
}

// ── Tabs: pages opened from Marble, in this browser (30 days, at most 500) ───────────────────────────

export const tabs = () => read(TABS(who), []).filter((t) => now() - t.at < MONTH);
export function opened(url, title, from) {
  const list = [{ url, title: title || hostOf(url), at: now(), from: from || "" }, ...tabs().filter((t) => t.url !== url)].slice(0, 500);
  write(TABS(who), list);
  listeners.forEach((fn) => fn());
}
export const hostOf = (url) => { try { return new URL(url).hostname.replace(/^www\./, ""); } catch { return url; } };

// ── "Go to …": what you typed as an address, or a well-known site's name (android Sites.kt, the same list) ───
// An address is sure, and Enter opens it: a dot and an ending ("example.com/page"), a scheme ("https://…"), or a
// single name followed by a slash ("xumbrel/", "localhost:8080/admin": a machine on your own network, as Tailscale's
// MagicDNS names them; opened over http://, as such names rarely have https certificates). A bare name ("apple") is
// only a likely one: "Go to apple.com" shows, and Enter still keeps a note and searches.

const BY_NAME = new Map();
for (const d of `google.com youtube.com facebook.com instagram.com wikipedia.org amazon.com reddit.com x.com twitter.com whatsapp.com
  tiktok.com linkedin.com yahoo.com bing.com netflix.com microsoft.com apple.com live.com office.com openai.com chatgpt.com
  claude.ai anthropic.com github.com gitlab.com stackoverflow.com twitch.tv discord.com telegram.org signal.org pinterest.com
  quora.com tumblr.com medium.com substack.com wordpress.com blogger.com ebay.com etsy.com walmart.com target.com bestbuy.com
  costco.com homedepot.com lowes.com ikea.com aliexpress.com alibaba.com temu.com shein.com wayfair.com zillow.com redfin.com
  craigslist.org indeed.com glassdoor.com paypal.com venmo.com stripe.com chase.com bankofamerica.com wellsfargo.com
  capitalone.com americanexpress.com coinbase.com kraken.com binance.com strike.me cashapp.com robinhood.com fidelity.com
  vanguard.com schwab.com spotify.com soundcloud.com bandcamp.com pandora.com deezer.com tidal.com vimeo.com dailymotion.com
  rumble.com odysee.com hulu.com disneyplus.com max.com peacocktv.com paramountplus.com primevideo.com crunchyroll.com
  imdb.com rottentomatoes.com letterboxd.com goodreads.com audible.com kindle.com archive.org nytimes.com washingtonpost.com
  wsj.com bloomberg.com reuters.com apnews.com bbc.com bbc.co.uk cnn.com foxnews.com nbcnews.com cbsnews.com abcnews.go.com
  npr.org theguardian.com economist.com ft.com forbes.com businessinsider.com cnbc.com axios.com politico.com theatlantic.com
  newyorker.com vox.com theverge.com wired.com arstechnica.com techcrunch.com engadget.com gizmodo.com cnet.com zdnet.com
  macrumors.com 9to5mac.com 9to5google.com androidpolice.com xda-developers.com tomshardware.com anandtech.com pcmag.com
  ign.com gamespot.com polygon.com kotaku.com pcgamer.com steampowered.com store.steampowered.com epicgames.com gog.com
  itch.io roblox.com minecraft.net xbox.com playstation.com nintendo.com ea.com chess.com lichess.org duolingo.com khanacademy.org
  coursera.org udemy.com edx.org wolframalpha.com weather.com accuweather.com wunderground.com espn.com nfl.com nba.com mlb.com
  nhl.com fifa.com uefa.com strava.com fitbit.com allrecipes.com seriouseats.com bonappetit.com foodnetwork.com yelp.com
  tripadvisor.com booking.com airbnb.com expedia.com kayak.com hotels.com uber.com lyft.com doordash.com ubereats.com
  grubhub.com instacart.com maps.google.com openstreetmap.org mail.google.com gmail.com outlook.com proton.me protonmail.com
  tutanota.com fastmail.com icloud.com dropbox.com box.com drive.google.com docs.google.com notion.so evernote.com obsidian.md
  trello.com asana.com slack.com zoom.us teams.microsoft.com figma.com canva.com adobe.com behance.net dribbble.com
  unsplash.com pexels.com flickr.com imgur.com giphy.com deviantart.com artstation.com 500px.com vsco.co mastodon.social
  bsky.app bluesky.social threads.net lemmy.world kbin.social hackernews.com news.ycombinator.com lobste.rs slashdot.org
  producthunt.com indiehackers.com dev.to hashnode.com npmjs.com pypi.org crates.io docker.com kubernetes.io python.org
  rust-lang.org go.dev kotlinlang.org developer.android.com developer.apple.com mozilla.org firefox.com brave.com
  duckduckgo.com startpage.com kagi.com perplexity.ai gemini.google.com bard.google.com huggingface.co kaggle.com arxiv.org
  nature.com science.org nasa.gov noaa.gov cdc.gov nih.gov who.int un.org irs.gov usps.com ups.com fedex.com dhl.com
  stacker.news primal.net damus.io snort.social iris.to coracle.social nostr.band jumble.social ditto.pub njump.me nostr.com
  zap.stream habla.news yakihonne.com highlighter.com olas.app zapstore.dev bitcoin.org mempool.space blockstream.info
  lightning.network getalby.com wikihow.com fandom.com britannica.com dictionary.com merriam-webster.com thesaurus.com
  translate.google.com deepl.com speedtest.net fast.com downdetector.com pastebin.com archive.ph web.archive.org`.trim().split(/\s+/)) {
  const name = d.replace(/^www\./, "").split(".")[0];
  if (!BY_NAME.has(name)) BY_NAME.set(name, d);
}
const ADDRESS = /^(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})(?:[/:?#]\S*)?$/i;
const LOCAL = /^(?:(https?):\/\/)?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?::\d{1,5})?)(\/\S*)?$/i; // one name, no dot
const NOT_TLDS = new Set(["js", "ts", "kt", "py", "rs", "txt", "md", "json", "png", "jpg", "jpeg", "gif", "pdf", "mp4", "mp3", "exe", "apk", "zip", "html", "css"]);

/** A site to go to for what's typed: {address, url, sure}, or null (it's searched). */
export function site(typed) {
  const q = String(typed || "").trim();
  if (!q || q.includes(" ")) return null;
  const m = ADDRESS.exec(q);
  if (m) {
    const host = m[1].toLowerCase();
    if (NOT_TLDS.has(host.split(".").pop())) return null;
    return { address: host.replace(/^www\./, ""), url: /^https?:\/\//i.test(q) ? q : `https://${q}`, sure: true };
  }
  // one name with a slash after it, or a scheme in front: a machine on your network
  const l = LOCAL.exec(q);
  if (l && (l[1] || l[3])) return { address: q.replace(/^https?:\/\//i, ""), url: l[1] ? q : `http://${q}`, sure: true };
  const name = q.toLowerCase();
  if (BY_NAME.has(name)) return { address: BY_NAME.get(name), url: `https://${BY_NAME.get(name)}`, sure: false };
  if (name.length >= 3 && /^[\p{L}\p{N}-]+$/u.test(name)) {
    for (const [k, v] of BY_NAME) if (k.startsWith(name)) return { address: v, url: `https://${v}`, sure: false };
  }
  return null;
}
