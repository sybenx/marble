package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
import cc.aaro.vaults.BlobRef
import cc.aaro.vaults.Entry
import cc.aaro.vaults.Put
import cc.aaro.vaults.State
import cc.aaro.vaults.Vault
import cc.aaro.vaults.Vaults
import cc.aaro.vaults.canon
import cc.aaro.vaults.thHex
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Marble's notes in a vault (~/nostr-sync-nip/vaults.md, the `notes` profile, §11.1), between Later's items and the
 * engine. The mapping is the one agreed with Marble Web (side-session notes, vaults-android.md):
 *
 *  meta `{"kind":"note","created","title":"","tags":[],"done","pinned":false,"id","updated"}` plus `done_at`, `url`,
 *  `quote` and `app` (Later's `from`) when set, and `pictures` `[{"ref": nv1 ref, "mime", "w", "h"}]` (a screenshot
 *  kept on Marble Web: each its own blob; shown here, kept as it is when the note changes); data `{"text"}`, or a blob over 4 KiB; entry =
 *  hex(TH("marble/later/1", id))[0:32]; a removal is a deleted version.
 *
 * Each Later item carries the version it was based on (`vv`) and its entry (`ve`) in its extra fields, so an edit here
 * supersedes only that version (§5.3) and an edit on two devices at once is a conflict the person settles (§5.4).
 * Everything here blocks: call it off the main thread.
 */
class VaultNotes(
    val vaults: Vaults,
    private val now: () -> Long,
    /** Relays and Blossom servers for a new vault, already checked (§8.2). Null: none usable now. */
    private val newVaultPlaces: () -> Pair<List<String>, List<String>>?,
) {
    /** A note with several current versions: each one's text, and whether it is a deletion. */
    data class Version(val ver: String, val text: String, val deleted: Boolean, val done: Boolean)
    data class Trashed(val entry: String, val id: String, val text: String)

    val conflicts = MutableStateFlow<Map<String, List<Version>>>(emptyMap())
    val trash = MutableStateFlow<List<Trashed>>(emptyList())
    /** Why notes can't sync now, in a few words, or null. */
    val trouble = MutableStateFlow<String?>(null)

    @Volatile var vault: Vault? = vaults.store.get("notesVault")?.let { vaults.open(it) }; private set

    /** The notes vault: the one held, else found in the account's keyring entries, else (if [create]) a new one. */
    @Synchronized fun ensure(create: Boolean): Vault? {
        vault?.let { return it }
        val entries = vaults.keyrings() ?: return null.also { trouble.value = "Can't reach your relays" }
        val found = vaults.findOwned(PROFILE, entries)
        val v = when {
            found != null -> vaults.join(found)
            create -> { val (r, s) = newVaultPlaces() ?: return null.also { trouble.value = "No storage for notes yet" }; vaults.create(PROFILE, "Notes", r, s) }
            else -> return null
        }
        vaults.store.put("notesVault", v.id)
        vault = v
        return v
    }

    // --- Mapping ------------------------------------------------------------------------------------------

    fun entryOf(item: LaterItem): String = item.extra["ve"] as? String ?: entryFor(item.id)

    private fun textOf(v: Vault, p: Put): String? = p.data?.let { d ->
        if (d.has("text")) d.optString("text") else BlobRef.of(d.opt("blob"))?.let { r -> v.fetch(r)?.let { String(it) } }
    }

    /** A current version as Later's item. */
    private fun item(v: Vault, p: Put): LaterItem {
        val m = p.meta
        val updated = m.optLong("updated", m.optLong("created"))
        return LaterItem(m.optString("id").ifEmpty { p.entry }, textOf(v, p) ?: "", m.optLong("created"), updated,
            done = if (m.optBoolean("done")) m.optLong("done_at", updated) else null, url = m.optString("url").ifEmpty { null },
            extra = mapOf("ve" to p.entry, "vv" to p.ver) + (picturesOf(m)?.let { mapOf("pics" to it) } ?: emptyMap()), quote = m.optString("quote").ifEmpty { null }, from = m.optString("app").ifEmpty { null })
    }

    /** A note's pictures that can be shown (a valid ref, a picture type), or null when it has none. */
    private fun picturesOf(m: JSONObject): JSONArray? {
        val a = m.optJSONArray("pictures") ?: return null
        val out = JSONArray()
        for (k in 0 until a.length()) {
            val p = a.optJSONObject(k) ?: continue
            if (p.optString("mime") !in PICTURE_TYPES || runCatching { BlobRef(p.getJSONObject("ref")) }.isFailure) continue
            out.put(JSONObject().put("ref", p.getJSONObject("ref")).put("mime", p.optString("mime")).put("w", p.optInt("w")).put("h", p.optInt("h")))
        }
        return out.takeIf { it.length() > 0 }
    }

    /**
     * A note's picture as a file in [dir] (this app's own cache), fetched and opened from the vault the first time. Null
     * while it can't be had. Blocks: call it off the main thread.
     */
    fun picture(pic: JSONObject, dir: java.io.File): java.io.File? {
        val f = pictureFile(pic, dir) ?: return null
        if (f.length() > 0) return f
        val ref = BlobRef(pic.getJSONObject("ref"))
        val v = vault ?: return null
        val bytes = runCatching { v.fetch(ref, keep = false) }.getOrNull() ?: return null
        dir.mkdirs()
        val tmp = java.io.File(dir, f.name + ".part")
        tmp.writeBytes(bytes)
        return f.takeIf { tmp.renameTo(it) }
    }

    /** Later's item as the meta it maps to, over [base]'s meta so members this version doesn't know are kept (§5.2). */
    private fun metaOf(i: LaterItem, base: Put?): JSONObject {
        val m = base?.meta?.takeIf { !base.deleted }?.let { JSONObject(it.toString()) } ?: JSONObject()
        m.put("kind", "note").put("created", i.created).put("done", i.done != null).put("id", i.id).put("updated", i.updated)
        if (!m.has("title")) m.put("title", "")
        if (!m.has("tags")) m.put("tags", JSONArray())
        if (!m.has("pinned")) m.put("pinned", false)
        fun opt(k: String, v: Any?) { if (v == null) m.remove(k) else m.put(k, v) }
        opt("done_at", i.done); opt("url", i.url); opt("quote", i.quote); opt("app", i.from)
        return m
    }

    /** The put for a change here, or null if [i] says what its base version already says. */
    private fun putFor(v: Vault, st: State, i: LaterItem, dry: Boolean = false): Put? {
        val entry = entryOf(i)
        val base = (i.extra["vv"] as? String)?.let { st.puts["$entry:$it"] }
        val vv = i.extra["vv"] as? String
        if (vv == null && (st.entries.containsKey(entry) || st.dead.containsKey(entry))) return null // the vault's own wins: pull adopts it
        if (i.deleted != null) {
            if (vv == null || base?.deleted == true) return null
            return Put.make(entry, listOf(vv), JSONObject().put("kind", "note"), null, true)
        }
        val meta = metaOf(i, base)
        if (base != null && !base.deleted && canon(meta) == canon(base.meta) && textOf(v, base) == i.text) return null
        val data = if (dry || i.text.toByteArray().size <= 4096) JSONObject().put("text", i.text)
            else JSONObject().put("blob", (v.upload(i.text.toByteArray(), need = 2) ?: throw IllegalStateException("can't sync: storage unavailable")).json)
        return Put.make(entry, listOfNotNull(vv), meta, data, false)
    }

    // --- Pull and push ------------------------------------------------------------------------------------

    /** Caught up, and the notes as the vault has them, with changes made here that haven't gone out kept. Null: couldn't. */
    @Synchronized fun pull(before: List<LaterItem>): List<LaterItem>? {
        val v = ensure(create = false) ?: return null
        if (!v.catchUp()) { trouble.value = v.trouble ?: "Can't reach your relays"; return null }
        trouble.value = v.trouble
        return merged(v, before)
    }

    private fun merged(v: Vault, before: List<LaterItem>): List<LaterItem> {
        val st = v.state()
        val out = ArrayList<LaterItem>()
        val byEntry = before.associateBy { entryOf(it) }
        for (i in before) if (!st.entries.containsKey(entryOf(i))) out += i   // not in the vault yet, or missing for now: kept
        val conf = HashMap<String, List<Version>>(); val tr = ArrayList<Trashed>()
        val emptied = vaults.store.get("trashEmptied")?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        for (e in st.entries.values) {
            val local = byEntry[e.id]
            if (local != null && unsent(v, st, local)) { out += local; continue }
            if (e.deleted) {
                trashed(v, st, e)?.takeIf { it.entry !in emptied }?.let(tr::add)
                local?.let { out += it.copy(text = "", deleted = it.deleted ?: now(), extra = it.extra + ("vv" to e.shown.ver)) }
                continue
            }
            val it0 = item(v, e.shown)
            out += it0
            if (e.conflict) conf[it0.id] = e.current.map { p -> Version(p.ver, if (p.deleted) "" else textOf(v, p) ?: "", p.deleted, p.meta.optBoolean("done")) }
        }
        conflicts.value = conf; trash.value = tr
        return out.sortedByDescending { it.created }
    }
    private fun unsent(v: Vault, st: State, i: LaterItem): Boolean = try { putFor(v, st, i, dry = true) != null } catch (_: Exception) { true }
    private fun trashed(v: Vault, st: State, e: Entry): Trashed? {
        val prev = e.current.flatMap { it.supersedes }.mapNotNull { st.puts["${e.id}:$it"] }.firstOrNull { !it.deleted } ?: return null
        return Trashed(e.id, prev.meta.optString("id").ifEmpty { e.id }, textOf(v, prev) ?: "")
    }

    /** What changed here goes out (§7.1). The notes after, or null if it couldn't. */
    @Synchronized fun push(before: List<LaterItem>): List<LaterItem>? {
        val v = ensure(create = before.any { it.deleted == null }) ?: return null
        if (!v.catchUp()) { trouble.value = v.trouble ?: "Can't reach your relays"; return null }
        val st = v.state()
        val puts = before.mapNotNull { try { putFor(v, st, it) } catch (_: Exception) { null } }
        if (puts.isNotEmpty()) v.enqueue(puts.map { it.json })
        val ok = v.sync(); v.checkpointIfDue()
        trouble.value = v.trouble
        if (!ok) return null
        // Each item now based on the version it just wrote.
        val vers = puts.associateBy { it.entry }
        return merged(v, before.map { i -> vers[entryOf(i)]?.let { p -> i.copy(extra = i.extra + ("ve" to p.entry) + ("vv" to p.ver)) } ?: i })
    }

    // --- Conflicts (§5.4, §11.1) -----------------------------------------------------------------------------

    /** Keep [ver] (one side), or [text] (both combined by hand): one put superseding every current version. */
    @Synchronized fun resolve(itemId: String, ver: String? = null, text: String? = null, keepBoth: Boolean = false): Boolean {
        val v = vault ?: return false
        val st = v.state()
        val e = st.entries.values.firstOrNull { it.current.any { p -> p.meta.optString("id") == itemId } || it.id == entryFor(itemId) } ?: return false
        val all = e.current.map { it.ver }
        val chosen = e.current.firstOrNull { it.ver == ver } ?: e.shown
        val puts = ArrayList<Put>()
        if (keepBoth) for (p in e.current.filter { it.ver != chosen.ver && !it.deleted }) {
            // The other side as a note of its own, with a new id.
            val id = java.security.SecureRandom().let { r -> ByteArray(8).also(r::nextBytes) }.joinToString("") { "%02x".format(it) }
            puts += Put.make(entryFor(id), emptyList(), JSONObject(p.meta.toString()).put("id", id).put("updated", now()), p.data, false)
        }
        val meta = JSONObject(chosen.meta.toString()).put("updated", now())
        val data = if (text != null) JSONObject().put("text", text) else chosen.data
        puts += if (chosen.deleted && text == null) Put.make(e.id, all, JSONObject().put("kind", "note"), null, true) else Put.make(e.id, all, meta, data, false)
        v.enqueue(puts.map { it.json })
        return true
    }

    // --- Trash (§12.4) ------------------------------------------------------------------------------------

    /** A removed note back: a new version with its last words, superseding the removal. */
    @Synchronized fun restore(entry: String): Boolean {
        val v = vault ?: return false
        val st = v.state(); val e = st.entries[entry] ?: return false
        val prev = e.current.flatMap { it.supersedes }.mapNotNull { st.puts["$entry:$it"] }.firstOrNull { !it.deleted } ?: return false
        v.enqueue(listOf(Put.make(entry, e.current.map { it.ver }, JSONObject(prev.meta.toString()).put("updated", now()), prev.data, false).json))
        return true
    }

    /**
     * Empty Trash, after the person's Yes (§12.4: hard to undo, so only here in the app). Their last words are forgotten
     * on this phone and the notes leave Trash here. Other devices keep their own copies until they empty theirs.
     */
    @Synchronized fun emptyTrash() {
        val emptied = (vaults.store.get("trashEmptied")?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) + trash.value.map { it.entry }
        vaults.store.put("trashEmptied", JSONArray(emptied.distinct()).toString())
        trash.value = emptyList()
    }

    // --- Import (§17) ---------------------------------------------------------------------------------------

    /** Imported here already (once per account per device, and only entries the vault never had). */
    val imported: Boolean get() = vault?.let { vaults.store.get("imported/${it.id}") != null } == true
    /** The import read back and found whole: the old packs stop being read. */
    val importChecked: Boolean get() = vault?.let { vaults.store.get("importChecked/${it.id}") != null } == true

    /**
     * The person's v3 notes and marble/later ([old], read from the relays, removals included) into the vault, once, as
     * one batch (§17). Only entries the vault has never had in any version. The old events stay as they are.
     */
    @Synchronized fun import(old: List<LaterItem>): Int {
        val v = ensure(create = true) ?: return -1
        if (imported) return 0
        if (!v.catchUp()) return -1
        val st = v.state()
        val puts = old.filter { val e = entryFor(it.id); !st.entries.containsKey(e) && !st.dead.containsKey(e) }.mapNotNull { i ->
            val e = entryFor(i.id)
            if (i.deleted != null) Put.make(e, emptyList(), JSONObject().put("kind", "note"), null, true)
            else try { putFor(v, st, i.copy(extra = emptyMap())) } catch (_: Exception) { null }
        }
        if (puts.isNotEmpty()) { v.enqueue(puts.map { it.json }); if (!v.sync()) return -1 }
        vaults.store.put("imported/${v.id}", JSONArray(puts.map { it.entry + ":" + it.ver }).toString())
        return puts.size
    }

    /** Reads the import back from the relays (a fresh catch-up): every imported version counts. */
    @Synchronized fun checkImport(): Boolean {
        val v = vault ?: return false
        if (importChecked) return true
        val list = vaults.store.get("imported/${v.id}")?.let { JSONArray(it) } ?: return false
        if (!v.catchUp()) return false
        val st = v.state()
        val ok = (0 until list.length()).all { st.puts.containsKey(list.getString(it)) }
        if (ok) vaults.store.put("importChecked/${v.id}", now().toString())
        return ok
    }

    companion object {
        const val PROFILE = "notes"
        /** Where a note's picture is kept on this phone once opened (named by its first part's hash), or null for a bad one. */
        fun pictureFile(pic: JSONObject, dir: java.io.File): java.io.File? {
            val ref = runCatching { BlobRef(pic.getJSONObject("ref")) }.getOrNull() ?: return null
            val ext = when (pic.optString("mime")) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> return null }
            return java.io.File(dir, "${ref.parts[0]}.$ext")
        }
        /** Pictures a note can show (as Marble Web keeps them). */
        val PICTURE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        fun entryFor(id: String) = thHex("marble/later/1", id.toByteArray())
    }
}
