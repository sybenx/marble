// Who's signed in, if anyone: read-only for now. A browser extension (NIP-07) names the account; nothing
// is signed or sent. Remembered in this browser only. On localhost, ?as=<hex> acts as that account (testing).
import { req, relays, FAST, INDEXERS } from "./pool.js";
import { outbox, keep } from "./data.js";
import { isHex64 } from "./ids.js";

const KEY = "marble.me";
let me = null;
try { me = localStorage.getItem(KEY); } catch {}
const as = new URLSearchParams(location.search).get("as");
if (/^(localhost|127\.0\.0\.1)$/.test(location.hostname) && isHex64(as)) me = as.toLowerCase();
if (!isHex64(me)) me = null;

const listeners = new Set();
export const onMe = (fn) => listeners.add(fn);
export const current = () => me;

/** Whether this browser can sign in here (an extension is installed). Extensions may arrive a moment late. */
export const canSignIn = () => !!window.nostr?.getPublicKey;

export async function signIn() {
  const pk = await window.nostr.getPublicKey();
  if (!isHex64(pk)) throw new Error("no account");
  me = pk.toLowerCase();
  try { localStorage.setItem(KEY, me); } catch {}
  follows = null;
  listeners.forEach((fn) => fn(me));
  return me;
}

export function signOut() {
  me = null;
  follows = null;
  try { localStorage.removeItem(KEY); } catch {}
  listeners.forEach((fn) => fn(null));
}

let follows = null;
/** The people the signed-in account follows (its newest kind 3), read once a visit. */
export function myFollows() {
  if (!me) return Promise.resolve(new Set());
  if (!follows) {
    const who = me;
    follows = (async () => {
      const own = await Promise.race([outbox(who).then((o) => o.write), new Promise((r) => setTimeout(() => r([]), 1200))]);
      const got = await req(relays(own, FAST, INDEXERS), [{ kinds: [3], authors: [who] }, { kinds: [0], authors: [who] }], { timeout: 3000, settle: 700, onEvent: keep });
      const list = got.filter((e) => e.kind === 3).sort((a, b) => b.created_at - a.created_at)[0];
      return new Set((list?.tags || []).filter((t) => t[0] === "p" && isHex64(t[1])).map((t) => t[1]));
    })();
  }
  return follows;
}
