// Who's signed in, and how: the Marble browser extension (NIP-07), or your phone (NIP-46: the code Marble
// scans; see connect.js). Remembered in this browser only. On localhost, ?as=<hex> acts as that account,
// read-only (testing).
import { req, relays, verify, FAST, INDEXERS } from "./pool.js";
import { outbox, keep } from "./data.js";
import { isHex64 } from "./ids.js";

const KEY = "marble.me";
let state = null; // {pubkey, method: "extension" | "phone" | null, phone?: {sk, signer, relays}}
try {
  const raw = localStorage.getItem(KEY);
  if (isHex64(raw)) state = { pubkey: raw, method: "extension" }; // as the first version saved it
  else if (raw) state = JSON.parse(raw);
} catch {}
const as = new URLSearchParams(location.search).get("as");
if (/^(localhost|127\.0\.0\.1)$/.test(location.hostname) && isHex64(as)) state = { pubkey: as.toLowerCase(), method: null };
if (!isHex64(state?.pubkey)) state = null;

let remote = null; // the phone connection, made when first needed
const listeners = new Set();
export const onMe = (fn) => listeners.add(fn);
export const current = () => state?.pubkey || null;
export const method = () => state?.method || null;
/** Whether a browser extension is here to sign in with. Extensions may arrive a moment after the page. */
export const hasExtension = () => !!window.nostr?.getPublicKey;

function set(next) {
  remote?.stop?.();
  remote = null;
  state = next;
  follows = null;
  try { next ? localStorage.setItem(KEY, JSON.stringify(next)) : localStorage.removeItem(KEY); } catch {}
  listeners.forEach((fn) => fn(current()));
}

/** Asks the extension which account this is. It may wait for you to approve in the extension's window. */
export async function signInWithExtension() {
  const pk = await window.nostr.getPublicKey();
  if (!isHex64(pk)) throw new Error("no account");
  set({ pubkey: pk.toLowerCase(), method: "extension" });
  return current();
}

/** Your phone connected (connect.js phoneSignIn): keep its connection for signing. */
export function signedInWithPhone({ pubkey, remote: r, saved }) {
  set({ pubkey, method: "phone", phone: saved });
  remote = r;
}

export function signOut() {
  if (state?.method === "phone") phoneRemote().then((r) => r.request("logout", [], 5000)).catch(() => {});
  set(null);
}

async function phoneRemote() {
  if (!remote) {
    const { Remote } = await import("./connect.js");
    remote = new Remote(state.phone);
  }
  return remote;
}

/**
 * Signs an event as you: through the extension, or your phone. Resolves with the signed event, checked
 * (right account, good signature); rejects if you refused, it timed out, or you're signed in read-only.
 */
export async function sign(template) {
  if (!state?.method) throw new Error("read-only");
  const t = { created_at: Math.floor(Date.now() / 1000), tags: [], content: "", ...template, pubkey: state.pubkey };
  const signed = state.method === "extension"
    ? await window.nostr.signEvent(t)
    : await (await phoneRemote()).signEvent(t);
  if (!signed || signed.pubkey !== state.pubkey || !verify(signed)) throw new Error("bad signature");
  return signed;
}

/** Where your posts go: your own write relays (NIP-65), or the fast ones if you have none listed. */
export async function myWriteRelays() {
  const o = await Promise.race([outbox(state.pubkey), new Promise((r) => setTimeout(() => r({ write: [] }), 2000))]);
  return o.write.length ? o.write : FAST.slice(0, 3);
}

let follows = null;
/** The people the signed-in account follows (its newest kind 3), read once a visit. */
export function myFollows() {
  if (!state) return Promise.resolve(new Set());
  if (!follows) {
    const who = state.pubkey;
    follows = (async () => {
      const own = await Promise.race([outbox(who).then((o) => o.write), new Promise((r) => setTimeout(() => r([]), 1200))]);
      const got = await req(relays(own, FAST, INDEXERS), [{ kinds: [3], authors: [who] }, { kinds: [0], authors: [who] }], { timeout: 3000, settle: 700, onEvent: keep });
      const list = got.filter((e) => e.kind === 3).sort((a, b) => b.created_at - a.created_at)[0];
      return new Set((list?.tags || []).filter((t) => t[0] === "p" && isHex64(t[1])).map((t) => t[1]));
    })();
  }
  return follows;
}
