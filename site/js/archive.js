// Your archive: relays only you use (a Chronicle relay, say, holding every thread you took part in). Marble logs in to
// them as you (relay-auth.js: NIP-42, and only to relays you chose: your 10002 and 10050 lists and these) and search
// asks them too, shown first as "From your archive". The list is kept in this browser and on your account
// (private-relays.js: the same copy Marble Android keeps). Failures are only logged.
import { req, relays, publish, setAuth, dropLogins, FAST, INDEXERS } from "./signin/pool.js";
import { current, method, sign, onMe, nip44DecryptFrom, nip44EncryptTo } from "./me.js";
import { accountRelays } from "./account-relays.js";
import { Session, allowed, loginEvent, norm } from "./relay-auth.js";
import { TAG, parse, label, fromJson, toJson, merge, ahead, nextAt, searchFilter } from "./private-relays.js";

export { label, parse };
const KEY = (pk) => `marble.private-relays.${pk}`;
const log = (...a) => console.log("[archive]", ...a);
const listeners = new Set();
export const onArchive = (fn) => (listeners.add(fn), () => listeners.delete(fn));
const tell = () => listeners.forEach((fn) => { try { fn(); } catch {} });

let who = current() || "";
let all = load(who); // every relay, removed ones too
function load(pk) { if (!pk) return []; try { return fromJson(localStorage.getItem(KEY(pk))) || []; } catch { return []; } }
function save() { if (who) try { localStorage.setItem(KEY(who), toJson(all)); } catch {} }

/** Your archive's addresses (what login and search use). */
export const urls = () => all.filter((r) => !r.gone).map((r) => r.url);
/** For the list on screen. */
export const shown = () => all.filter((r) => !r.gone);

/** Add an address as typed; the stored one, or null if it isn't one. */
export function add(input) {
  const url = parse(input);
  if (!url || !who) return null;
  put({ url, at: nextAt(all, url), gone: false });
  return url;
}
export function remove(url) {
  const u = norm(url);
  if (!all.some((r) => r.url === u && !r.gone)) return;
  put({ url: u, at: nextAt(all, u), gone: true });
}
function put(r) { all = merge(all.filter((x) => x.url !== r.url), [r]); save(); tell(); sendSoon(); }

// ── Relays you chose: where a login may go ─────────────────────────────────────────────────────────────

let listed = new Set(); // your 10002 and 10050 relays, normalised
let listedFor = "";
async function loadListed(pk) {
  if (!pk || listedFor === pk) return;
  listedFor = pk;
  try {
    const got = await req(relays(INDEXERS, FAST), { kinds: [10002, 10050], authors: [pk] }, { timeout: 5000, settle: 800 });
    const set = new Set();
    for (const kind of [10002, 10050]) {
      const e = got.filter((x) => x.kind === kind && x.pubkey === pk).sort((a, b) => b.created_at - a.created_at)[0];
      for (const t of e?.tags || []) if ((kind === 10002 ? t[0] === "r" : t[0] === "relay") && norm(t[1])) set.add(norm(t[1]));
    }
    if (current() === pk) listed = set;
  } catch (err) { log("relay lists not read", err); }
}
export const chosen = () => [...listed, ...urls()];

setAuth({
  session: (url) => new Session(url, () => !!current() && !!method() && allowed(url, chosen())),
  async sign(url, challenge) {
    const me = current();
    if (!me || !allowed(url, chosen())) return null;
    const e = await sign(loginEvent(me, url, challenge));
    return e.pubkey === me ? e : null;
  },
});

// ── Your account's copy ────────────────────────────────────────────────────────────────────────────────

const canSync = () => !!who && method() === "phone";
let pulled = "", sendTimer = null;
async function where() {
  const a = await accountRelays(who).catch(() => null);
  return a && { write: a.write, read: relays(a.write, a.read, FAST.slice(0, 2)) };
}
/** Reads your account's copy and merges it in. Returns the copy's created_at (0: none) or null if it couldn't. */
async function pull() {
  const me = who, w = await where();
  if (!w) return null;
  const got = await req(w.read, { kinds: [30078], authors: [me], "#d": [TAG], limit: 4 }, { timeout: 6000, settle: 1200 });
  if (me !== who) return null;
  if (!got.answered) return null;
  const e = got.filter((x) => x.pubkey === me).sort((a, b) => b.created_at - a.created_at)[0];
  if (!e) return 0;
  const theirs = fromJson(await nip44DecryptFrom(me, e.content));
  if (me !== who) return null;
  if (!theirs) return null; // (a newer format: never written over)
  const mine = all;
  all = merge(theirs, mine); save(); tell();
  return ahead(theirs, mine) ? -e.created_at : e.created_at; // (negative: ours has more, write)
}
function sendSoon() { if (!canSync()) return; clearTimeout(sendTimer); sendTimer = setTimeout(() => send().catch((err) => log("not saved to your account", err)), 2000); }
async function send() {
  const me = who;
  const at = await pull();
  if (at === null || me !== who) return; // (couldn't read: nothing written over what another device has)
  const w = await where();
  const content = await nip44EncryptTo(me, toJson(all));
  if (me !== who) return;
  const ev = await sign({ kind: 30078, created_at: Math.max(Math.floor(Date.now() / 1000), Math.abs(at) + 1), tags: [["d", TAG]], content });
  if (me !== who || ev.pubkey !== me) return;
  const r = await publish(w.write, ev);
  if (!r.ok.length) log("not saved to your account", r.failed);
}
async function start() {
  if (!canSync() || pulled === who) return;
  pulled = who;
  try {
    const at = await pull();
    if (at !== null && (at < 0 || (at === 0 && all.length))) sendSoon();
  } catch (err) { log("not read from your account", err); }
}

function switchTo(pk) {
  pk = pk || "";
  if (pk === who) return;
  who = pk; all = load(pk); pulled = ""; listed = new Set(); listedFor = "";
  clearTimeout(sendTimer);
  dropLogins();
  tell();
  if (pk) { loadListed(pk); start(); }
}
onMe((pk) => switchTo(pk));
if (who) { loadListed(who); setTimeout(start, 1500); }

// ── Search ─────────────────────────────────────────────────────────────────────────────────────────────

/** What your archive has for a search (a #tag: posts with it); [] when you have none, or it couldn't be asked. */
export async function searchArchive(query, { signal = null } = {}) {
  const list = urls();
  if (!list.length || !current()) return [];
  const tag = /^#([\p{L}\p{N}_-]+)$/u.exec(String(query || "").trim());
  try {
    const got = await req(list, searchFilter(query, tag ? tag[1].toLowerCase() : null), { timeout: 7000, settle: 1500, signal });
    return got.sort((a, b) => b.created_at - a.created_at);
  } catch (err) { log("search failed", err); return []; }
}
