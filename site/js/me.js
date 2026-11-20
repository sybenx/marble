// Who's signed in, and how: the sign-in's own (signin/me.js, Keykin's shared copy), and what only Marble keeps beside it:
// your follows, the follow list this browser has seen, and whether this sign-in may open vaults.
import "./signin-hooks.js";
import { current, method, onMe } from "./signin/me.js";
import { req, relays, FAST, INDEXERS } from "./signin/pool.js";
import { outbox, keep } from "./data.js";
import { isHex64 } from "./signin/ids.js";

export * from "./signin/me.js";

let follows = null;
// A change of account clears the follows read for the last one, before anyone else hears of it (registered first).
onMe(() => { follows = null; });

/**
 * NIP-44 to yourself: no sign-in here can, now the extension is gone (your servers don't encrypt for sites, on purpose:
 * cosign server/core/group.ts). Kept so marble/later and marble/reader callers fail as "unsupported" and stay local.
 */
export async function nip44Self() { throw new Error("unsupported"); }

/** Whether this sign-in may open vaults (it signs and decrypts as you). */
export const canVault = () => method() === "phone";

// Your newest follow list as this browser has seen it (so a Follow here never starts from an empty one by mistake)
const CONTACTS = (pk) => `marble.contacts.${pk}`;
export function seenContacts(pk, e) {
  try {
    const had = JSON.parse(localStorage.getItem(CONTACTS(pk)) || "null");
    if (!had || e.created_at > had.created_at) localStorage.setItem(CONTACTS(pk), JSON.stringify(e));
  } catch {}
}
export const contactsSeen = (pk) => { try { return JSON.parse(localStorage.getItem(CONTACTS(pk)) || "null"); } catch { return null; } };
/** After a Follow here: the follows as they are now. */
export const followsNow = (set) => { follows = Promise.resolve(set); };
/** The people the signed-in account follows (its newest kind 3), read once a visit. */
export function myFollows() {
  if (!current()) return Promise.resolve(new Set());
  if (!follows) {
    const who = current();
    follows = (async () => {
      const own = await Promise.race([outbox(who).then((o) => o.write), new Promise((r) => setTimeout(() => r([]), 1200))]);
      const got = await req(relays(own, FAST, INDEXERS), [{ kinds: [3], authors: [who] }, { kinds: [0], authors: [who] }], { timeout: 3000, settle: 700, onEvent: keep });
      const list = got.filter((e) => e.kind === 3 && e.pubkey === who).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0];
      if (list) seenContacts(who, list);
      return new Set((list?.tags || []).filter((t) => t[0] === "p" && isHex64(t[1])).map((t) => t[1]));
    })();
  }
  return follows;
}
