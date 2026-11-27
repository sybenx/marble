// Fake relays for the search smoke test (tools/search-fixtures.mjs's world), answering the way the real ones do at
// their worst. Android's copy of these rules is android/app/src/test/java/cc/aaro/cosign/net/FakeSearchRelay.kt.
//   "loose"  NIP-50 that takes a post with ANY one of the words (Chronicle, our archive; a sloppy search relay).
//            Operators ("from:x", "sort:new") are dropped, as the real ones drop them.
//   "ignore" a relay that ignores `search` and sends whatever else matches (every post, for a plain search).
//   "primal" Primal's cache: user_search (people by name) and user_infos (follower counts), unsigned.
// COUNT (NIP-45) on kind 3 #p answers from the fixture's follower numbers.

const norm = (s) => String(s || "").normalize("NFKC").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const words = (q) => norm(String(q || "").split(/\s+/).filter((w) => !/^[a-z_]+:\S/i.test(w)).join(" ")).split(" ").filter(Boolean);
function body(e) {
  if (e.kind === 0) { try { const p = JSON.parse(e.content); return norm([p.name, p.display_name, p.about, p.nip05].join(" ")); } catch { return ""; } }
  return norm(`${e.content} ${(e.tags.find((t) => t[0] === "title") || [])[1] || ""}`);
}
const has = (e, f) => (!f.ids || f.ids.includes(e.id)) && (!f.kinds || f.kinds.includes(e.kind)) && (!f.authors || f.authors.includes(e.pubkey))
  && (f.since == null || e.created_at >= f.since) && (f.until == null || e.created_at <= f.until)
  && Object.entries(f).every(([k, v]) => !k.startsWith("#") || e.tags.some((t) => t[0] === k.slice(1) && v.includes(t[1])));

/** What a relay of [how] sends for [filter] out of [events], newest first, at most its limit. */
export function answer(how, events, filter) {
  let list = events.filter((e) => has(e, filter));
  if (how === "loose" && filter.search != null) {
    const ws = words(filter.search);
    list = list.filter((e) => { const b = ` ${body(e)} `; return ws.some((w) => b.includes(w)); });
  }
  return list.sort((a, b) => b.created_at - a.created_at).slice(0, filter.limit ?? 500);
}

/** Primal's cache: [events], unsigned where Primal's are. */
export function primal(fixtures, filter) {
  const [what, args] = filter.cache || [];
  if (what === "user_search") {
    const ws = words(args?.query);
    return fixtures.publicEvents.filter((e) => e.kind === 0 && ws.length && ws.every((w) => body(e).includes(w)))
      .sort((a, b) => (fixtures.followers[b.pubkey] || 0) - (fixtures.followers[a.pubkey] || 0)).slice(0, args?.limit || 10);
  }
  if (what === "user_infos") {
    const counts = Object.fromEntries((args?.pubkeys || []).map((p) => [p, fixtures.followers[p] || 0]));
    return [{ kind: 10000133, content: JSON.stringify(counts), tags: [], created_at: 0 }];
  }
  return [];
}

/** A COUNT's number: followers when it asks for kind 3 #p, else the matching events. */
export function count(fixtures, events, filter) {
  if (filter.kinds?.includes(3) && filter["#p"]) return filter["#p"].reduce((n, p) => n + (fixtures.followers[p] || 0), 0);
  return answer("ignore", events, filter).length;
}

/**
 * One socket of a fake relay: feed it what the client sends (a string), it calls send(string) with the answers. Every
 * request is logged in [log] as {relay, filter}.
 */
export function relaySocket(name, how, events, fixtures, send, log = []) {
  return (raw) => {
    let msg; try { msg = JSON.parse(raw); } catch { return; }
    if (msg[0] === "REQ") {
      const [, id, ...filters] = msg, seen = new Set();
      for (const f of filters) {
        log.push({ relay: name, filter: f });
        const got = how === "primal" ? primal(fixtures, f) : answer(how, events, f);
        for (const e of got) if (!e.id || !seen.has(e.id)) { if (e.id) seen.add(e.id); send(JSON.stringify(["EVENT", id, e])); }
      }
      send(JSON.stringify(["EOSE", id]));
    } else if (msg[0] === "COUNT") {
      const [, id, f] = msg;
      send(JSON.stringify(["COUNT", id, { count: count(fixtures, events, f) }]));
    } else if (msg[0] === "EVENT") send(JSON.stringify(["OK", msg[1]?.id, false, "blocked: read-only test relay"]));
  };
}
