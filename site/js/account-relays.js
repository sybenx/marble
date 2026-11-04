// Where your synced copies live (reader.md §6): your write relays as your list (kind 10002) says, every one of them,
// including any that's down right now (sync-guard.js counts a down one as not answering, never as not being there).
// A lookup that heard from too few relays is "unknown", never "you have none": nothing is written until it's known.
import { req, relays, normalize, FAST, INDEXERS, testRelay } from "./pool.js";

const known = new Map(); // pubkey → {write, read}, once the lookup has worked (for the visit)

/** {write, read} for your account, or null if it can't be told yet (try again later). */
export async function accountRelays(pk) {
  // (testing on localhost: the local relay is where writes go)
  if (testRelay()) return { write: [normalize(testRelay())], read: [] };
  if (known.has(pk)) return known.get(pk);
  const got = await req(relays(INDEXERS, FAST), { kinds: [10002], authors: [pk] }, { timeout: 6000 });
  const list = got.filter((e) => e.pubkey === pk).sort((a, b) => b.created_at - a.created_at)[0];
  let out = null;
  if (list) {
    const write = [], read = [];
    for (const t of list.tags) {
      const u = t[0] === "r" && normalize(t[1]);
      if (!u) continue;
      if (t[2] !== "read" && !write.includes(u)) write.push(u);
      if (t[2] !== "write" && !read.includes(u)) read.push(u);
    }
    out = { write: write.slice(0, 8), read: read.slice(0, 8) };
  } else if (got.answered >= 3) {
    // three or more relays say there's no list: an account without one, which writes to the fast defaults
    out = { write: FAST.slice(0, 3).map(normalize), read: [] };
  }
  if (out?.write.length) known.set(pk, out);
  return out?.write.length ? out : null;
}
