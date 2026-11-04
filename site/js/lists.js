// Changing one of your lists (follows 3, bookmarks 10003, Special Follow 10010, favourite relays 10012, DM inbox 10050,
// people and relay sets 30000/30002, app data 30078): the shared list-write rule (marble-content-fixtures/list-writes.md,
// fixtures/lists/), the same as Marble Android. No page, no network, so the fixtures can run it as it is.
//  - Start from your newest version anywhere (what this device has seen signed, your write relays, the profile indexes);
//    a tie goes to the lowest id.
//  - Change only the one entry; every other tag, in its order, and the content (the private part) stay as they are.
//  - created_at = max(now, old + 1), so the new version always wins.
//  - Refuse when no list was found but one is expected, and when the result would take out more than the one entry.
//  - Never a NIP-09 deletion for a set.

export const LIST_KINDS = [3, 10003, 10010, 10012, 10050, 30000, 30002, 30078];
const addressable = (kind) => kind >= 30000 && kind < 40000;
const dOf = (e) => e.tags.find((t) => t[0] === "d")?.[1] ?? "";

// a relay address as one entry: wss://Relay.Example.com/ and wss://relay.example.com are the same relay
const normal = (name, v) => {
  if (!/^(r|relay)$/.test(name) || !/^wss?:\/\//i.test(v || "")) return v;
  try { const u = new URL(v); return `${u.protocol}//${u.host.toLowerCase()}${u.pathname.replace(/\/+$/, "")}${u.search}`; } catch { return v; }
};
/** What an entry is: its tag's name and value ("p" and a pubkey, "relay" and an address, "e", "a", "t", "word"…). */
export const entryOf = (t) => (t.length > 1 && t[0] !== "d" ? `${t[0]}\u0000${normal(t[0], t[1])}` : null);
const entries = (tags) => new Set(tags.map(entryOf).filter(Boolean));

/** Your newest version of the list: of everything found (this device's and the relays'), yours, of the kind (and d). */
export function newestOf(found, { me, kind, d = "" }) {
  return found.filter((e) => e.pubkey === me && e.kind === kind && (!addressable(kind) || dOf(e) === d))
    .sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1))[0] ?? null;
}

/**
 * What changing one entry writes: {write: unsigned event} | {nothing: why} | {refuse: why}.
 * change is {add: tag} or {remove: tag}; expected says a list should exist (this device saw one, or the account shows one).
 */
export function listWrite(found, { me, kind, d = "", change, expected, now = Math.floor(Date.now() / 1000) }) {
  const old = newestOf(found, { me, kind, d });
  const tag = change.add || change.remove, k = entryOf(tag);
  if (!k) return { refuse: "not an entry" };
  if (!old && expected) return { refuse: "no list found" };
  const tags = old?.tags ?? (addressable(kind) ? [["d", d]] : []);
  const has = tags.some((t) => entryOf(t) === k);
  if (change.add ? has : !has) return { nothing: change.add ? "already in it" : "not in it" };
  const next = change.add ? [...tags, tag] : tags.filter((t) => entryOf(t) !== k);
  const write = { kind, created_at: Math.max(now, (old?.created_at ?? 0) + 1), tags: next, content: old?.content ?? "" };
  const bad = guard(old, write);
  return bad ? { refuse: bad } : { write };
}

/** The last check before signing any list (whatever made it): why it mustn't go out, or null. */
export function guard(old, next) {
  if (!old) return null;
  if (next.kind !== old.kind) return "a different kind";
  if (addressable(old.kind) && dOf(next) !== dOf(old)) return "a different list";
  if (next.content !== old.content) return "the private part changed";
  if (next.created_at <= old.created_at) return "not newer";
  const was = entries(old.tags), now = entries(next.tags);
  const out = [...was].filter((k) => !now.has(k)).length, added = [...now].filter((k) => !was.has(k)).length;
  if (out > 1) return "takes out more than one";
  if (out + added !== 1) return "changes more than the one entry";
  // the tags that aren't entries (and the entries kept) stay as they were, in order
  const kept = (tags) => tags.filter((t) => !entryOf(t) || (was.has(entryOf(t)) && now.has(entryOf(t)))).map((t) => JSON.stringify(t));
  if (kept(old.tags).join() !== kept(next.tags).join()) return "other tags changed";
  return null;
}
