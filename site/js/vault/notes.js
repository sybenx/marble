// The notes profile (kist.md §11.1) as Marble writes it (the contract with Marble Android, 2026-11-17):
//   meta {"kind": "note", "created", "title": "", "tags": [], "done": bool, "pinned": false, "id": <Marble's note id>,
//         "updated", "done_at"? (only when done), "url"? , "quote"?, "app"? (Later's `from`),
//         "pictures"? [{"ref": nv1 ref, "mime", "w", "h"}] (a screenshot or other picture kept in the note, each its own
//         blob; Marble Web 2026-11-20)}; members it doesn't know are kept when a note is rewritten (§5.2);
//   entry = hex(TH("marble/later/1", utf8(note id)))[0:32], for imported and new notes alike;
//   data {"text": the whole note} inline up to 4 KiB, else {"blob": nv1 ref}.
import { thHex, utf8, isHex } from "./crypto.js";
import { makePut, refOK } from "./core.js";

export const INLINE = 4096;
export const entryOf = (noteId) => thHex("marble/later/1", 32, utf8(noteId));
const isObj = (o) => !!o && typeof o === "object" && !Array.isArray(o);

/** A note's meta, keeping members of `base` (the version it rewrites) that Marble doesn't set. */
export function metaOf(note, base = {}) {
  const m = { ...base, kind: "note", created: note.created, title: base.title ?? "", tags: Array.isArray(base.tags) ? base.tags : [],
    done: !!note.done, pinned: base.pinned === true, id: note.id, updated: note.updated };
  for (const k of ["done_at", "url", "quote", "app"]) delete m[k];
  if (note.done) m.done_at = typeof note.done === "number" ? note.done : note.updated;
  if (note.url) m.url = note.url;
  if (note.quote) m.quote = note.quote;
  if (note.app) m.app = note.app;
  if (note.pictures !== undefined) { if (note.pictures.length) m.pictures = note.pictures; else delete m.pictures; }
  return m;
}
/** A put for a note's text and fields; `data` given when the text is in a blob. */
export function notePut(note, supersedes, base = {}, data = null) {
  return makePut(entryOf(note.id), supersedes, metaOf(note, base), data ?? { text: note.text }, false);
}
/** Pictures a note can show: images in a browser's own formats, each a valid blob ref. */
export const PICTURE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
export const picturesOf = (m) => (Array.isArray(m?.pictures) ? m.pictures : []).filter((p) => isObj(p) && refOK(p.ref) && PICTURE_TYPES.includes(p.mime))
  .map((p) => ({ ref: p.ref, mime: p.mime, w: p.w | 0, h: p.h | 0 }));
export const needsBlob = (text) => utf8(text).length > INLINE;
/** Deleting a note: the deleted version (§5.3), superseding the versions it replaces. */
export const deletePut = (entry, supersedes) => makePut(entry, supersedes, { kind: "note" }, null, true);

/** A version as Marble shows it: {id, text (null while its blob loads), created, updated, done, url, quote, app}. */
export function noteOf(put, text = put.data?.text ?? null) {
  const m = put.meta;
  return { id: typeof m.id === "string" ? m.id : put.entry, entry: put.entry, ver: put.ver, text, blob: isObj(put.data?.blob) ? put.data.blob : null,
    created: m.created | 0, updated: m.updated | 0, done: m.done === true ? (m.done_at | 0) || m.updated | 0 : null,
    url: typeof m.url === "string" ? m.url : undefined, quote: typeof m.quote === "string" ? m.quote : undefined, app: typeof m.app === "string" ? m.app : undefined,
    pictures: picturesOf(m), meta: m };
}

/**
 * The notes of a state (state.js): [{note, versions, conflict, deletedOn}]. One current version: that note. Several:
 * a conflict, the lowest ver first (§5.4); a deletion beside an edit shows the edit, marked deleted on that device.
 */
export function notesOf(state, roster) {
  const out = [];
  for (const e of state.entries.values()) {
    if (!e.current.length) continue;
    const live = e.current.filter((p) => !p.deleted && p.meta?.kind === "note");
    if (!live.length) continue; // deleted
    const dels = e.current.filter((p) => p.deleted);
    const name = (ver) => roster?.dvks.get(e.by(ver))?.name || "another device";
    out.push({ entry: e.entry, note: noteOf(live[0]), versions: live.map((p) => ({ ...noteOf(p), device: name(p.ver) })),
      conflict: live.length > 1, deletedOn: dels.length ? name(dels[0].ver) : null, current: e.current.map((p) => p.ver) });
  }
  return out;
}
export { isHex };
