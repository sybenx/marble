// Follow and unfollow (kind 3), by the shared list-write rule (lists.js; marble-content-fixtures/list-writes.md): from
// your newest follow list anywhere (this browser's copy, your write relays, the profile indexes), one person in or
// out, everything else kept; refused when no list is found but one is expected (this browser has seen one, or you have
// a profile), so an extension that checks nothing can't be handed a near-empty list.
import { req, relays, publish, FAST, INDEXERS } from "./pool.js";
import { outbox } from "./data.js";
import { current, sign, seenContacts, contactsSeen, followsNow, myFollows } from "./me.js";
import { listWrite } from "./lists.js";

const pending = new Set();
/** Follows (on) or unfollows pk. Returns {ok} or {error: what to say}. */
export async function follow(pk, on) {
  const me = current();
  if (!me || pk === me || pending.has(pk)) return { error: "" };
  pending.add(pk);
  try {
    const own = await Promise.race([outbox(me), new Promise((r) => setTimeout(() => r({ write: [], read: [] }), 2000))]);
    const answer = await req(relays(own.write, FAST, INDEXERS), [{ kinds: [3], authors: [me] }, { kinds: [0], authors: [me], limit: 1 }], { timeout: 5000, settle: 1200 });
    // no relay answered: nothing is known about your list, so nothing is written (never a list started from nothing)
    if (!answer.answered) return { error: "Couldn't reach your relays. Nothing changed." };
    const got = answer.filter((e) => e.pubkey === me);
    const local = contactsSeen(me);
    const found = [...got.filter((e) => e.kind === 3), ...(local ? [local] : [])];
    const expected = !!local || got.some((e) => e.kind === 0);
    const r = listWrite(found, { me, kind: 3, change: on ? { add: ["p", pk] } : { remove: ["p", pk] }, expected });
    if (r.refuse) return { error: "Couldn't find your follow list, so nothing changed. Try again in a moment." };
    if (r.nothing) return { ok: true };
    const signed = await sign(r.write);
    const sent = await publish(relays(own.write, FAST), signed);
    if (!sent.ok.length) return { error: "Couldn't reach your relays. Nothing changed." };
    seenContacts(me, signed);
    followsNow(new Set(signed.tags.filter((t) => t[0] === "p").map((t) => t[1])));
    return { ok: true };
  } catch (err) {
    return { error: /read-only/.test(String(err?.message)) ? "Sign in to follow." : "Not followed: it wasn't signed." };
  } finally { pending.delete(pk); }
}
export const following = async (pk) => (await myFollows()).has(pk);
