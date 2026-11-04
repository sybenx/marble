// Whether a synced copy on your account (Notes' marble/later, Reader's marble/reader and discover/seen) may be written
// now, and whether a write is done (reader.md §6, agreed with Marble Android). No page, no network:
// marble-content-fixtures/fixtures/sync/guard.json runs it.
//  - Your write relays are your list as configured (kind 10002), every one counted, down or not: one that failed to
//    connect, answered CLOSED or never ended its answer simply didn't answer.
//  - Reached: more than half of them ended their answer (EOSE). Not reached tells nothing: wait.
//  - Never under a newer copy this device has seen: wait. The escape, so it never stops for good: 3 reads in a row
//    where every write relay answered and none had it (your relays changed, or one dropped it), or that copy is past
//    its own expiration: what your relays have is the truth (rebase). A relay that's down or refuses never lets the
//    escape go over the copy it may hold.
//  - A write is done when more than half of them sent OK; until then it's pending, retried, and not "seen".
//  - Retry after the n-th failure in a row: a minute, doubling, at most ten.

export const MISSES = 3;

const answered = (write, by) => write.filter((u) => by.has(u)).length;
/** Reached the account: more than half of the configured write relays ended their answer. */
export const completeRead = (write, answeredBy) => write.length > 0 && answered(write, answeredBy) * 2 > write.length;
/** Every configured write relay ended its answer (only such reads count toward the escape). */
export const fullRead = (write, answeredBy) => write.length > 0 && answered(write, answeredBy) === write.length;
/** A write is done: more than half of the configured write relays took it (OK). */
export const written = (write, okBy) => write.length > 0 && write.filter((u) => okBy.has(u)).length * 2 > write.length;

/**
 * {complete, full, found, seenAt, misses, expires, now} → {do: "write" | "wait" | "rebase", misses}. complete: reached
 * (completeRead); full: every write relay answered (fullRead); found: the newest copy's created_at the relays gave (0:
 * none); seenAt: the newest this device has seen (0: none); misses: full reads in a row that missed it; expires: how
 * long a copy lives on relays (seconds; 0: for good).
 */
export function decide({ complete, full = false, found = 0, seenAt = 0, misses = 0, expires = 0, now = Math.floor(Date.now() / 1000) }) {
  if (!complete) return { do: "wait", misses };
  if (!seenAt || found >= seenAt) return { do: "write", misses: 0 };
  if (expires && now - seenAt > expires) return { do: "rebase", misses: 0 };
  if (!full) return { do: "wait", misses }; // (a relay that didn't answer may hold it: neither counted nor gone over)
  if (misses + 1 >= MISSES) return { do: "rebase", misses: 0 };
  return { do: "wait", misses: misses + 1 };
}

/**
 * Your write relays from your relay list's tags (kind 10002): every r tag not marked "read", ws:// or wss://, once each,
 * none dropped (an .onion or a relay on your own network counts too: a browser just never hears from it). Written as a
 * relay pool writes them: scheme and host in lower case, no trailing slash.
 */
export function writeList(tags) {
  const out = [];
  for (const t of tags || []) {
    if (t[0] !== "r" || t[2] === "read" || !/^wss?:\/\//i.test(t[1] || "")) continue;
    let u;
    try { const p = new URL(t[1].trim()); u = `${p.protocol}//${p.host.toLowerCase()}${p.pathname.replace(/\/+$/, "")}`; } catch { continue; }
    if (!out.includes(u)) out.push(u);
  }
  return out;
}

/** Misses as stored ({n, at}: counted against the copy seen at `at`), for the copy seen now: a newer copy seen starts again. */
export const missesAgainst = (stored, seenAt) => (stored && typeof stored === "object" ? (stored.at === seenAt ? stored.n : 0) : 0);

/** The wait before trying a write again after the n-th failure in a row (1, 2, …): a minute, doubling, at most ten. */
export const retryAfter = (n) => Math.min(60 * 2 ** Math.max(0, n - 1), 600);
