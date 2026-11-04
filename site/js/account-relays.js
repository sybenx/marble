// Where your synced copies live (reader.md §6): your write relays as your list (kind 10002) says, every one of them,
// including any that's down right now (sync-guard.js counts a down one as not answering, never as not being there).
// A lookup that heard from too few relays is "unknown", never "you have none": nothing is written until it's known.
import { req, relays, normalize, FAST, INDEXERS, testRelay } from "./pool.js";
import { writeList } from "./sync-guard.js";

const known = new Map(); // pubkey → {write, read}, once the lookup has worked (for the visit)

/** {write, read} for your account, or null if it can't be told yet (try again later). */
export async function accountRelays(pk) {
  // (testing on localhost: the local relay is where writes go)
  if (testRelay()) { const t = { write: [normalize(testRelay())], read: [] }; known.set(pk, t); return t; }
  if (known.has(pk)) return known.get(pk);
  const got = await req(relays(INDEXERS, FAST), { kinds: [10002], authors: [pk] }, { timeout: 6000 });
  const list = got.filter((e) => e.pubkey === pk).sort((a, b) => b.created_at - a.created_at)[0];
  let out = null;
  if (list) {
    // every write relay counts, even one a browser can't reach (.onion, your own network): it just never answers
    const write = writeList(list.tags);
    const read = relays(list.tags.filter((t) => t[0] === "r" && t[2] !== "write").map((t) => t[1]));
    // a list with no write relays at all: as an account with none, the fast defaults
    out = { write: write.length ? write : FAST.slice(0, 3).map(normalize), read, none: !write.length };
  } else if (got.answered >= 3) {
    // three or more relays say there's no list: an account without one, which writes to the fast defaults
    out = { write: FAST.slice(0, 3).map(normalize), read: [] };
  }
  if (out?.write.length) known.set(pk, out);
  return out?.write.length ? out : null;
}

// How your write relays have been answering (this browser, the last week), for a quiet line under your picture:
// "2 of your 4 write relays haven't answered in a week". The rule doesn't change; this only says why sync waits.
const HEARD = (pk) => `marble.relays.heard.${pk}`;
const readJ = (k) => { try { return JSON.parse(localStorage.getItem(k)) || {}; } catch { return {}; } };
/** After a read of your copy: which write relays answered. */
export function heard(pk, write, answeredBy) {
  const h = readJ(HEARD(pk)), t = Date.now();
  for (const u of write) h[u] = { first: h[u]?.first || t, last: answeredBy.has(u) ? t : h[u]?.last || 0 };
  try { localStorage.setItem(HEARD(pk), JSON.stringify(h)); } catch {}
}
/** {silent, of}: how many of your write relays haven't answered in a week (only once watched for a week). */
export function silence(pk) {
  const a = known.get(pk), h = readJ(HEARD(pk)), week = 7 * 86400000, t = Date.now();
  if (!a) return null;
  const silent = a.write.filter((u) => h[u] && t - h[u].first > week && t - h[u].last > week).length;
  return { silent, of: a.write.length, unreachable: a.write.filter((u) => !normalize(u)).length };
}
/** The quiet line itself, or "". */
export function silenceLine(pk) {
  const s = silence(pk);
  if (!s) return "";
  if (s.unreachable === s.of) return "None of your write relays can be reached from a browser, so your notes and Reader stay here.";
  return s.silent ? `${s.silent} of your ${s.of} write relays haven't answered in a week.` : "";
}
