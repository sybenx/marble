// Who's signed in, and how: your phone or another signer app (NIP-46: the code Marble scans, the login page your
// account names, or a bunker link; see connect.js). The browser extension is gone: a sign-in from it is signed out once. Remembered in this browser only. On localhost, ?as=<hex> acts as that account,
// read-only (testing). Moved from Marble Web's me.js, without its Marble-only parts (follows, contacts seen, vaults:
// see README.md).
import { verify, FAST } from "./pool.js";
import { hooks } from "./hooks.js";
import { isHex64 } from "./ids.js";

const KEY = "marble.me";
let state = null; // {pubkey, method: "phone" | null, phone?: {sk, signer, relays}}
let wasExtension = false;
try {
  const raw = localStorage.getItem(KEY);
  if (isHex64(raw)) wasExtension = true; // the extension, as the first version saved it
  else if (raw) state = JSON.parse(raw);
  if (state?.method === "extension") { wasExtension = true; state = null; }
  if (wasExtension) localStorage.removeItem(KEY);
} catch {}
/** Whether this page had a sign-in from the browser extension, now signed out (said once: act.js). */
export const extensionSignedOut = () => wasExtension;
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
/** This browser's own NIP-46 key (hex secret) when signed in with a phone, for its short key in the account menu. */
export const phoneSecret = () => (state?.method === "phone" && state.phone?.sk) || null;

// ── Signed out somewhere else ──
// Taking this site away on the phone, or removing the device: the site finds out at its next
// contact (a refusal that says so, or another account answering) and shows itself signed out, rather than keeping a
// sign-in that no longer works (~/igloo/signin-signout-audit.md, fix 2). onSignedOut(fn) tells the page, once.
const outs = new Set();
export const onSignedOut = (fn) => outs.add(fn);
const GONE = /not-connected|revoked|was removed|removed from your key|not signed in|said not now/i;
function signedOutElsewhere() {
  if (!state) return;
  set(null);
  release();
  outs.forEach((fn) => fn());
}
/** A refusal from the signer: signed out, if it says we are. Returns the error, to throw on. */
function checked(err) {
  if (state && GONE.test(String(err?.message || err))) signedOutElsewhere();
  return err;
}
function set(next) {
  remote?.stop?.();
  remote = null;
  for (const c of choices || []) c.remote.stop();
  choices = null;
  state = next;
  try { next ? localStorage.setItem(KEY, JSON.stringify(next)) : localStorage.removeItem(KEY); } catch {}
  listeners.forEach((fn) => fn(current()));
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
  // the phone's session is read before it's cleared, so the logout always goes (fix 9)
  if (state?.method === "phone") logout(state.phone);
  set(null);
  release();
}
async function logout(saved) {
  const { Remote } = await import("./connect.js");
  const r = new Remote(saved);
  await r.request("logout", [], 5000).catch(() => {});
  r.stop();
}

async function phoneRemote() {
  if (!remote) {
    const saved = state.phone;
    const { Remote } = await import("./connect.js");
    remote = new Remote(saved);
  }
  return remote;
}

/**
 * Signs an event as you, through your phone (or signer app). Resolves with the signed event, checked
 * (right account, good signature); rejects if you refused, it timed out, or you're signed in read-only.
 */
export async function sign(template) {
  if (hold) await hold.promise;
  if (!state?.method) throw new Error("read-only");
  const t = { created_at: Math.floor(Date.now() / 1000), tags: [], content: "", ...template, pubkey: state.pubkey };
  let signed;
  try {
    signed = await (await phoneRemote()).signEvent(t);
  } catch (err) { throw checked(err); }
  // another account answered: the signer has moved on from the one this site knows
  if (signed && isHex64(signed.pubkey) && signed.pubkey !== t.pubkey) { signedOutElsewhere(); throw new Error("not signed in: another account"); }
  if (!signed || signed.pubkey !== t.pubkey || !verify(signed)) throw new Error("bad signature");
  return signed;
}

/**
 * NIP-44 decrypt as you, of a message from `pubkey` (Kist: a keyring entry's one-time key, kist.md §3.6): through
 * your phone's signer (NIP-46 nip44_decrypt). Throws "unsupported" signed in read-only.
 */
export async function nip44DecryptFrom(pubkey, ct) {
  if (hold) await hold.promise;
  try {
    if (state?.method === "phone") return await (await phoneRemote()).request("nip44_decrypt", [pubkey, ct], 60000);
  } catch (err) { throw checked(err); }
  throw new Error("unsupported");
}

/** NIP-44 encrypt as you, to `pubkey` (yourself, for what only you read): through your phone's signer (NIP-46 nip44_encrypt). */
export async function nip44EncryptTo(pubkey, text) {
  if (hold) await hold.promise;
  try {
    if (state?.method === "phone") return await (await phoneRemote()).request("nip44_encrypt", [pubkey, text], 60000);
  } catch (err) { throw checked(err); }
  throw new Error("unsupported");
}

/** Where your posts go: your own write relays (NIP-65), or the fast ones if you have none listed. */
export async function myWriteRelays() {
  const o = await Promise.race([hooks.outbox(state.pubkey), new Promise((r) => setTimeout(() => r({ write: [] }), 2000))]);
  return o.write.length ? o.write : FAST.slice(0, 3);
}
