// Being told no (proposals/good-guest.md §3.5): a Blossom server's 402, 403, 413, 429 or 507 is an answer, not an
// error and not missing data. The device stops that kind of request to that server until the server's Retry-After,
// or else for a wait that doubles from a minute up to a day, and says plainly which server said what. A 503 counts
// too when it says when to come back. Marble's Android app keeps the same rules in cc.aaro.vaults.Refusals; both are
// checked against vectors/refusals.json.
//
// A kind of request: "upload", "get" or "delete". A 429 is about the server, not the kind: every kind waits.

export const REFUSING = [402, 403, 413, 429, 507];
export const FIRST_WAIT = 60, LONGEST_WAIT = 86400;
const PHRASE = { 402: "payment required", 403: "not allowed", 413: "too large", 429: "too many requests", 503: "busy", 507: "out of space" };
const WHAT = { upload: "storage", get: "downloads", delete: "deleting" };

/** Whether an HTTP answer is a refusal (§3.5), not a failure: the five codes, and a 503 that says when to come back. */
export const isRefusal = (status, retryAfter) => REFUSING.includes(status) || (status === 503 && retryAfter != null && retryAfter !== "");

/** Retry-After in seconds from `t` (seconds): a number of seconds or an HTTP date. Null when absent or unreadable. */
export function retryAfterSeconds(value, t) {
  if (value == null) return null;
  const v = String(value).trim();
  if (/^\d+$/.test(v)) return Number(v);
  const ms = Date.parse(v);
  return Number.isFinite(ms) ? Math.max(0, Math.round(ms / 1000) - t) : null;
}
/** How long to wait after the `strikes`-th refusal in a row with no Retry-After: 1 min, 2, 4, … up to a day. */
export const backoff = (strikes) => Math.min(LONGEST_WAIT, FIRST_WAIT * 2 ** Math.max(0, Math.min(20, strikes - 1)));

export const hostOf = (server) => { try { return new URL(server).host.toLowerCase(); } catch { return String(server); } };
const keyOf = (server, kind) => `${kind} ${hostOf(server)}`;

/**
 * Per server and kind: {server, host, kind, status, reason, retryAfter, at, until, strikes}. `store` (get/set) keeps it
 * across page loads when given.
 */
export class Refusals {
  constructor({ store = null, key = "blossomRefusals" } = {}) {
    this.store = store; this.key = key;
    this.map = new Map(Object.entries((store && store.get(key)) || {}));
  }
  save() { if (this.store) this.store.set(this.key, Object.fromEntries(this.map)); }
  /** The refusal that holds `kind` to `server` back at `t`, or null. */
  waiting(server, kind, t) {
    for (const k of [keyOf(server, kind), keyOf(server, "*")]) { const r = this.map.get(k); if (r && r.until > t) return r; }
    return null;
  }
  /** The servers of `servers` that may be asked for `kind` at `t`, in order. */
  open(servers, kind, t) { return servers.filter((s) => !this.waiting(s, kind, t)); }
  /**
   * A refusal from `server` for `kind`: {status, reason (X-Reason), retryAfter (the header as sent)}. Returns the entry.
   * Strikes count refusals in a row; the wait doubles with them unless the server said how long.
   */
  refused(server, kind, { status, reason = null, retryAfter = null }, t) {
    const k = keyOf(server, status === 429 ? "*" : kind);
    const prev = this.map.get(k);
    const strikes = (prev?.strikes || 0) + 1;
    const said = retryAfterSeconds(retryAfter, t);
    const wait = Math.min(LONGEST_WAIT, said && said > 0 ? said : backoff(strikes));
    const r = { server, host: hostOf(server), kind: status === 429 ? "*" : kind, status, reason: cleanReason(reason), retryAfter: said, at: t, until: t + wait, strikes };
    this.map.set(k, r); this.save();
    return r;
  }
  /** `server` answered a `kind` request: its refusal for that kind (and a 429) is over. */
  answered(server, kind) {
    let changed = false;
    for (const k of [keyOf(server, kind), keyOf(server, "*")]) changed = this.map.delete(k) || changed;
    if (changed) this.save();
  }
  /** Refusals still holding something back at `t`, the newest first. */
  active(t) { return [...this.map.values()].filter((r) => r.until > t).sort((a, b) => b.at - a.at); }
  /** When the first of `servers` refused for `kind` may be asked again (seconds), or null if none is waiting. */
  nextTry(servers, kind, t) {
    const u = servers.map((s) => this.waiting(s, kind, t)?.until).filter((x) => x != null);
    return u.length ? Math.min(...u) : null;
  }
}
const cleanReason = (s) => (typeof s === "string" && s.trim() ? s.trim().replace(/\s+/g, " ").slice(0, 80) : null);

/**
 * The line Notes shows for a refusal. `when(until)` words the time ("at 3 pm", "tomorrow at 9 am"); the host decides.
 *   "blossom.x says too many requests, trying again at 3 pm"
 *   "blossom.x refused storage: 402 payment required"
 */
export function refusalLine(r, when) {
  if (r.status === 429) return `${r.host} says too many requests, trying again ${when(r.until)}`;
  const what = WHAT[r.kind] || "a request";
  const why = r.reason || PHRASE[r.status] || "";
  const line = `${r.host} refused ${what}: ${r.status}${why ? " " + why : ""}`;
  return r.status === 503 ? `${line}, trying again ${when(r.until)}` : line;
}
/** An Error for a blob held back by refusals rather than missing: blobJSON leaves it out of the gaps. */
export class RefusedError extends Error {
  constructor(refusals) { super("refused: " + refusals.map((r) => `${r.host} ${r.status}`).join(", ")); this.refusals = refusals; }
}
