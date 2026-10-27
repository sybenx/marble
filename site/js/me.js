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

let remote = null; // the phone connection, made when first needed (at once if it came from a code: see watch)
const listeners = new Set();
export const onMe = (fn) => listeners.add(fn);
const asks = new Set();
/** fn(choices) when more than one account answered the code you signed in with: ask which one is you (see watch). */
export const onAsk = (fn) => asks.add(fn);
export const current = () => state?.pubkey || null;
export const method = () => state?.method || null;
/** Whether a browser extension is here to sign in with. Extensions may arrive a moment after the page. */
export const hasExtension = () => !!window.nostr?.getPublicKey;

function set(next) {
  remote?.stop?.();
  remote = null;
  for (const c of choices || []) c.remote.stop();
  choices = null;
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
  watch(r);
}

// ── More than one account answered the code ──
// A code on a shared screen can be scanned by someone else too, who may answer first. So a session from a code
// listens, for as long as it lasts, for another phone answering it (connect.js Remote). If that's another account,
// you're asked which one is you (onAsk), and nothing is signed until you say. Your pick is kept and the others are
// ignored from then on; more answers only add to the list, so nobody can keep you from signing in.
let choices = null; // [{pubkey, remote}] while you're being asked
let hold = null, asking = 0; // signing waits on hold while an answer is looked into or you're asked
function watch(r) {
  if (!r?.secret) return;
  r.onSecond = (signer) => contest(r, signer);
  for (const signer of r.others) contest(r, signer);
  r.start();
}
async function contest(r, signer) {
  if (remote !== r) return;
  if (!hold) { let resolve; hold = { promise: new Promise((ok) => (resolve = ok)), resolve }; }
  asking++;
  save();
  const { Remote } = await import("./connect.js");
  const other = new Remote({ sk: r.sk, signer, relays: r.relays });
  const pk = await other.request("get_public_key", [], 10000).then((x) => String(x).toLowerCase(), () => null);
  asking--;
  if (remote !== r) return other.stop();
  const list = choices || [{ pubkey: state.pubkey, remote: r }];
  if (!isHex64(pk) || list.some((c) => c.pubkey === pk)) {
    // no account given, or one already listed (the same person, through another app): nothing to ask about
    other.stop();
    r.others = r.others.filter((s) => s !== signer);
    r.ignored.add(signer);
    save();
  } else {
    choices = [...list, { pubkey: pk, remote: other }];
    asks.forEach((fn) => fn(choices));
  }
  if (!choices && !asking) release();
}
/** You picked an account (see contest): its connection is kept, and the other answers are ignored for good. */
export function choose(choice) {
  const all = choices || [choice], main = remote, keep = choice.remote;
  choices = null;
  for (const c of all) if (c !== choice && c.remote !== main) c.remote.stop();
  if (keep !== main) Object.assign(keep, { secret: main.secret, made: main.made, others: main.others, ignored: new Set(main.ignored) });
  for (const c of all) if (c !== choice) keep.ignored.add(c.remote.signer);
  keep.others = keep.others.filter((s) => !keep.ignored.has(s) && s !== keep.signer);
  if (keep !== main) signedInWithPhone({ pubkey: choice.pubkey, remote: keep, saved: keep.saved() });
  else save();
  if (!asking) release();
}
/** None of the accounts was you: signed out. */
export function neither() { signOut(); }
function release() { hold?.resolve(); hold = null; }
function save() {
  if (state?.method !== "phone" || !remote?.saved) return;
  state.phone = remote.saved();
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch {}
}
if (state?.method === "phone" && state.phone?.secret) setTimeout(() => phoneRemote().then(watch), 0);

export function signOut() {
  if (state?.method === "phone") phoneRemote().then((r) => r.request("logout", [], 5000)).catch(() => {});
  set(null);
  release();
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
  if (hold) await hold.promise;
  if (!state?.method) throw new Error("read-only");
  const t = { created_at: Math.floor(Date.now() / 1000), tags: [], content: "", ...template, pubkey: state.pubkey };
  const signed = state.method === "extension"
    ? await window.nostr.signEvent(t)
    : await (await phoneRemote()).signEvent(t);
  if (!signed || signed.pubkey !== state.pubkey || !verify(signed)) throw new Error("bad signature");
  return signed;
}

/**
 * NIP-44 to yourself (a note only you can read, like discover.syb.lol's interest profile): through the
 * extension. Signed in with your phone it throws "unsupported": your servers don't encrypt for sites, on
 * purpose (every site that signs in by code shares one signer key; cosign server/core/group.ts).
 */
export async function nip44Self(text, encrypt = true) {
  if (hold) await hold.promise;
  if (state?.method !== "extension" || !window.nostr?.nip44) throw new Error("unsupported");
  return encrypt ? window.nostr.nip44.encrypt(state.pubkey, text) : window.nostr.nip44.decrypt(state.pubkey, text);
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
