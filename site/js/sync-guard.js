// Whether a synced copy on your account (Notes' marble/later, Reader's marble/reader and discover/seen) may be written
// now, from what a read just found. No page, no network: marble-content-fixtures/fixtures/sync/guard.json runs it.
//  - A read that didn't hear back from most of your write relays (more than half sent EOSE; read relays don't count, a
//    copy is only ever written to write relays) tells nothing: wait.
//  - This device has seen a newer copy than any relay gave: the relay holding it may be down, so wait, but not forever:
//    after MISSES complete reads in a row without it (your relays changed, or one dropped it), or once it's past its
//    own expiration (the seen list's two weeks), what your relays have now is the truth: start again from that.
//  - Otherwise write (merged with what was found).

export const MISSES = 3;

/**
 * {complete, found, seenAt, misses, expires, now} → {do: "write" | "wait" | "rebase", misses}. found: the newest
 * copy's created_at on the relays (0: none); seenAt: the newest this device has seen (0 or missing: none); expires:
 * how long a copy lives on relays (seconds; missing: for good).
 */
export function decide({ complete, found = 0, seenAt = 0, misses = 0, expires = 0, now = Math.floor(Date.now() / 1000) }) {
  if (!complete) return { do: "wait", misses };
  if (!seenAt || found >= seenAt) return { do: "write", misses: 0 };
  if (expires && now - seenAt > expires) return { do: "rebase", misses: 0 };
  if (misses + 1 >= MISSES) return { do: "rebase", misses: 0 };
  return { do: "wait", misses: misses + 1 };
}

/** Whether a read reached the account: more than half of the write relays ended their answer (answeredBy, as pool.req gives). */
export const completeRead = (write, answeredBy) => write.length > 0 && write.filter((u) => answeredBy.has(u)).length * 2 > write.length;

/** The wait before trying a write again after the n-th failure in a row (1, 2, …): a minute, doubling, at most ten. */
export const retryAfter = (n) => Math.min(60 * 2 ** Math.max(0, n - 1), 600);
