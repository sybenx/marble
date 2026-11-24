// NIP-42 relay login, as Marble Android does it (net/RelayAuth.kt). Marble logs in only to relays you chose yourself:
// your relay lists (10002, 10050) and your private relays (private-relays.js). Any other relay that asks gets nothing,
// and its request ends as before. A request refused with `auth-required:` is sent again once after logging in, never
// more. No page, no network here: tools/archive.test.mjs runs it.

export const KIND = 22242;

/** An address as both apps write them: scheme and host in lower case, no trailing slash; null if it isn't ws(s)://. */
export function norm(url) {
  try {
    const t = String(url || "").trim();
    if (!/^wss?:\/\//i.test(t)) return null;
    const p = new URL(t);
    return `${p.protocol}//${p.host.toLowerCase()}${p.pathname.replace(/\/+$/, "")}`;
  } catch { return null; }
}

/** May Marble log in to `relay`? Only if it's one of `chosen` (compared normalised). */
export function allowed(relay, chosen) {
  const r = norm(relay);
  if (!r) return false;
  for (const c of chosen || []) if (norm(c) === r) return true;
  return false;
}

/** The unsigned login for `relay`'s `challenge`: kind 22242, `relay` and `challenge` tags, no content. */
export const loginEvent = (pubkey, relay, challenge, now = Math.floor(Date.now() / 1000)) =>
  ({ kind: KIND, pubkey, created_at: now, tags: [["relay", relay], ["challenge", challenge]], content: "" });

/** A refusal that asks for a login first (CLOSED, or OK false). */
export const needsAuth = (message) => typeof message === "string" && message.startsWith("auth-required:");

/**
 * One socket's login, with no network in it: what to do when the relay sends a challenge, refuses a request with
 * `auth-required:`, or answers the login. Actions: {send: challenge} (sign and send a login), {resend: key},
 * {giveUp: key}. key: a subscription id or an event id. Each request is retried at most once.
 * isAllowed: () => boolean, asked each time (the chosen list may arrive after the socket opened).
 */
export class Session {
  constructor(relay, isAllowed) {
    this.relay = relay;
    this.isAllowed = typeof isAllowed === "function" ? isAllowed : () => !!isAllowed;
    this.challenge = null; this.sentFor = null; this.authId = null;
    this.authed = false; this.failed = false;
    this.retried = new Set(); this.waiting = new Set();
  }
  /** ["AUTH", challenge]: log in at once (a chosen relay may hold things only for you). */
  onChallenge(c) {
    if (!this.isAllowed() || typeof c !== "string" || !c) return [];
    this.challenge = c;
    if (this.sentFor === c) return [];
    this.sentFor = c; this.authed = false; this.failed = false;
    return [{ send: c }];
  }
  /** Request `key` came back auth-required:. */
  onAuthRequired(key) {
    if (!this.isAllowed() || this.failed || this.retried.has(key)) return [{ giveUp: key }];
    this.retried.add(key);
    if (this.authed) return [{ resend: key }];
    this.waiting.add(key);
    // no login under way yet: one goes now if there's a challenge; else wait for the relay to send one
    const c = this.challenge;
    if (c && this.sentFor !== c) { this.sentFor = c; return [{ send: c }]; }
    return [];
  }
  /** The signed login went out as `id`, or couldn't be signed (null). */
  authSent(id) {
    if (id) { this.authId = id; return []; }
    this.failed = true;
    return this.drain("giveUp");
  }
  /** ["OK", id, ok, …]: {ours: true if it answered our login, actions}. */
  onOk(id, ok) {
    if (!id || id !== this.authId) return { ours: false, actions: [] };
    this.authId = null;
    if (ok) { this.authed = true; return { ours: true, actions: this.drain("resend") }; }
    this.failed = true;
    return { ours: true, actions: this.drain("giveUp") };
  }
  drain(what) { const out = [...this.waiting].map((k) => ({ [what]: k })); this.waiting.clear(); return out; }
}
