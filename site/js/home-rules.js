// Marble's home, the shared rules (marble-content-fixtures/home-rules.md, fixtures/home/): what the focused empty box
// lists, and when typing offers "Go to" a place. Look is each app's own. No page, no network.

export const DAY = 86400;
const words = (s) => String(s || "").trim().replace(/\s+/g, " ").toLowerCase();

/**
 * The focused, empty box (at most 7 rows): Keep in view (up to 3, in your order); one older note (the oldest open note
 * not seen in 14 days, not in view, not offered already today: `older: false` turns it off); your 2 latest searches or
 * notes (not already above); then "Notes and searches". notes: open notes {id, text, created, updated}; seen: id → when
 * last shown or touched here; offered: {id, at} of the last older note offered.
 */
export function focusList({ inView = [], notes = [], searches = [], seen = {}, offered = null, now, older = true }) {
  const rows = [], used = new Set();
  const open = new Map(notes.map((n) => [n.id, n]));
  for (const id of inView) { const n = open.get(id); if (n && rows.length < 3) { rows.push({ kind: "inView", id, text: n.text }); used.add(id); } }
  if (older) {
    const today = Math.floor(now / DAY);
    const again = offered && Math.floor(offered.at / DAY) === today ? offered.id : null; // (the same one, all day)
    const pick = again && open.has(again) && !used.has(again) ? open.get(again)
      : !offered || Math.floor(offered.at / DAY) !== today
        ? notes.filter((n) => !used.has(n.id) && now - Math.max(seen[n.id] || 0, n.updated || n.created) >= 14 * DAY).sort((a, b) => a.created - b.created)[0]
        : null;
    if (pick) { rows.push({ kind: "older", id: pick.id, text: pick.text }); used.add(pick.id); }
  }
  const recent = [...searches.map((s) => ({ kind: "search", text: s.text, at: s.at })), ...notes.filter((n) => !used.has(n.id)).map((n) => ({ kind: "note", id: n.id, text: n.text, at: n.created }))]
    .sort((a, b) => b.at - a.at);
  const texts = new Set(rows.map((r) => words(r.text)));
  for (const r of recent) { if (rows.filter((x) => x.kind === "search" || x.kind === "note").length >= 2) break; if (!texts.has(words(r.text))) { rows.push({ kind: r.kind, ...(r.id ? { id: r.id } : {}), text: r.text }); texts.add(words(r.text)); } }
  rows.push({ kind: "all" });
  return rows;
}

/**
 * "Go to" a place for what's typed: the place whose name is typed as a whole word, or begins with 4 or more typed
 * letters; null otherwise. below: true when one of your notes is exactly what's typed (Go to never goes above it).
 */
export function goTo(typed, places, notes = []) {
  const t = words(typed);
  if (!t) return null;
  const place = places.find((p) => p.name.toLowerCase().split(/\s+/).includes(t) || p.name.toLowerCase() === t)
    || (t.length >= 4 ? places.find((p) => p.name.toLowerCase().startsWith(t)) : null);
  if (!place) return null;
  return { place, below: notes.some((n) => words(n.text) === t) };
}
