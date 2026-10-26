// One WebSocket per relay, opened once and reused by every request on the page. Each request goes to
// all its relays at once; events stream in as they arrive, each checked (its id is the hash of what it
// says, and the author's signature is good) before anyone sees it.
import { schnorr, sha256 } from "./vendor/noble.js";
import { toHex, fromHex } from "./ids.js";

/** Asked first for anything: they answer fast and hold most of what people read. */
export const FAST = ["wss://syb.lol", "wss://relay.ditto.pub", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"];
/** Where people's profiles and lists of where they post are kept. */
export const INDEXERS = ["wss://purplepag.es", "wss://user.kindpag.es"];
/** Asked when the fast ones don't have it. */
export const MORE = ["wss://nostr.wine", "wss://relay.nostr.band", "wss://nostr.mom", "wss://offchain.pub", "wss://relay.snort.social"];

const CONNECT_WAIT = 3000;
const utf8 = new TextEncoder();
const checked = new Map(); // id → true/false

export function verify(e) {
  if (!e || typeof e.id !== "string" || typeof e.sig !== "string") return false;
  if (checked.has(e.id)) return checked.get(e.id);
  let ok = false;
  try {
    const hash = toHex(sha256(utf8.encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));
    ok = hash === e.id && schnorr.verify(fromHex(e.sig), fromHex(e.id), fromHex(e.pubkey));
  } catch { ok = false; }
  checked.set(e.id, ok);
  return ok;
}

export function normalize(url) {
  try {
    let u = (url || "").trim();
    if (!/^wss?:\/\//i.test(u)) return null;
    const p = new URL(u);
    if (p.hostname === "localhost" || /^(127\.|10\.|192\.168\.)/.test(p.hostname) || p.hostname.endsWith(".onion")) return null;
    return `${p.protocol}//${p.host.toLowerCase()}${p.pathname.replace(/\/+$/, "")}`;
  } catch { return null; }
}

/** The relays in order, each once, the broken ones left out. */
export function relays(...lists) {
  const out = [];
  for (const list of lists) for (const r of list || []) {
    const n = normalize(r);
    if (n && !out.includes(n) && !down.has(n)) out.push(n);
  }
  return out;
}

const sockets = new Map(); // url → Relay
const down = new Set(); // relays that failed to connect this visit
let nextId = 0;

class Relay {
  constructor(url) {
    this.url = url;
    this.subs = new Map();
    this.queue = [];
    this.open = false;
    this.dead = false;
    this.openedAt = 0;
    const t0 = performance.now();
    // index.html starts connecting to the fast ones before this script has loaded, for links opened cold.
    const early = (globalThis.marbleWarm || []).find((w) => normalize(w.url) === url && w.readyState < 2);
    try { this.ws = early || new WebSocket(url); } catch { this.fail(); return; }
    this.timer = setTimeout(() => this.fail(), CONNECT_WAIT);
    const opened = () => {
      clearTimeout(this.timer);
      this.open = true;
      this.openedAt = early ? -1 : performance.now() - t0;
      for (const m of this.queue) this.ws.send(m);
      this.queue = [];
    };
    if (this.ws.readyState === 1) queueMicrotask(opened);
    else this.ws.onopen = opened;
    this.ws.onmessage = (m) => this.receive(m.data);
    this.ws.onerror = () => { if (!this.open) this.fail(); };
    this.ws.onclose = () => this.fail();
  }
  fail() {
    if (this.dead) return;
    this.dead = true;
    clearTimeout(this.timer);
    if (!this.open) down.add(this.url);
    if (sockets.get(this.url) === this) sockets.delete(this.url);
    try { this.ws?.close(); } catch {}
    for (const sub of this.subs.values()) sub.done(this.url);
    this.subs.clear();
  }
  send(msg) {
    const m = JSON.stringify(msg);
    if (this.open) this.ws.send(m);
    else this.queue.push(m);
  }
  receive(data) {
    let m;
    try { m = JSON.parse(data); } catch { return; }
    if (!Array.isArray(m)) return;
    const sub = this.subs.get(m[1]);
    if (!sub) return;
    if (m[0] === "EVENT") sub.event(m[2], this.url);
    else if (m[0] === "COUNT") { this.subs.delete(m[1]); sub.count?.(m[2]?.count, this.url); sub.done(this.url); }
    else if (m[0] === "EOSE" || m[0] === "CLOSED") { this.subs.delete(m[1]); sub.done(this.url); }
  }
}

function relay(url) {
  let r = sockets.get(url);
  if (!r || r.dead) { r = new Relay(url); if (!r.dead) sockets.set(url, r); }
  return r;
}

/** Opens the sockets ahead of time, so the first request doesn't wait for them. */
export function warm(urls) {
  for (const u of relays(urls)) relay(u);
}

/**
 * Asks the relays for events matching the filters. onEvent(event, relayUrl) gets each new, checked event
 * as it arrives; the promise resolves with all of them once every relay has answered, or at the timeout.
 * Options: {timeout, onEvent, signal, stopOnFirst, settle, unsigned}. settle: once one relay has answered,
 * wait at most this long for the rest (one that can't connect would otherwise hold everything up).
 * unsigned is for Primal's counts, which nobody signs.
 */
export function req(urls, filters, { timeout = 5000, onEvent = null, signal = null, stopOnFirst = false, settle = 0, unsigned = false } = {}) {
  filters = Array.isArray(filters) ? filters : [filters];
  const list = relays(urls);
  return new Promise((resolve) => {
    const id = `m${(nextId++).toString(36)}`;
    const events = new Map();
    const waiting = new Set(list);
    let finished = false, settleTimer = null;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearTimeout(settleTimer);
      for (const u of list) {
        const r = sockets.get(u);
        if (r?.subs.has(id)) { r.subs.delete(id); if (r.open) r.send(["CLOSE", id]); }
      }
      resolve([...events.values()]);
    };
    const sub = {
      event(e, url) {
        if (finished || !e || (e.id && events.has(e.id)) || !(verify(e) || (unsigned && e.kind !== 0))) return;
        events.set(e.id || `unsigned${events.size}`, e); // Primal's counts have no id
        if (e.id) seenOn(e.id, url);
        onEvent?.(e, url);
        if (stopOnFirst) finish();
      },
      done(url) {
        waiting.delete(url);
        if (!waiting.size) finish();
        else if (settle && !settleTimer) settleTimer = setTimeout(finish, settle);
      },
    };
    const timer = setTimeout(finish, timeout);
    signal?.addEventListener("abort", finish);
    if (!list.length) return finish();
    for (const u of list) {
      const r = relay(u);
      if (r.dead) { waiting.delete(u); continue; }
      r.subs.set(id, sub);
      r.send(["REQ", id, ...filters]);
    }
    if (!waiting.size) finish();
  });
}

/**
 * NIP-45: how many events match, by the relays' own count (they hold more than they'd send). Resolves with
 * the highest count any of them gave, or null when none answered (not every relay counts).
 */
export function count(urls, filter, { timeout = 2500 } = {}) {
  const list = relays(urls);
  return new Promise((resolve) => {
    const id = `c${(nextId++).toString(36)}`;
    const waiting = new Set(list);
    let best = null, finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      for (const u of list) sockets.get(u)?.subs.delete(id);
      resolve(best);
    };
    const sub = {
      event() {},
      count(n) { if (Number.isFinite(n)) best = Math.max(best ?? 0, n); },
      done(url) { waiting.delete(url); if (!waiting.size) finish(); },
    };
    const timer = setTimeout(finish, timeout);
    if (!list.length) return finish();
    for (const u of list) {
      const r = relay(u);
      if (r.dead) { waiting.delete(u); continue; }
      r.subs.set(id, sub);
      r.send(["COUNT", id, filter]);
    }
    if (!waiting.size) finish();
  });
}

// Where each event was found, so a link to it can say where to look.
const where = new Map();
function seenOn(id, url) {
  const l = where.get(id) || [];
  if (!l.includes(url)) l.push(url);
  where.set(id, l);
}
export const foundOn = (id) => where.get(id) || [];

/** How long each open socket took to connect, for the timing notes. */
export const connectTimes = () => Object.fromEntries([...sockets.values()].filter((r) => r.open).map((r) => [r.url, Math.round(r.openedAt)]));
