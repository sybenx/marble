package cc.aaro.cosign.notes

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Notes sync v3 (~/igloo/marble-notes-sync-v3.md), option A: notes travel in packs, each a kind 30078 event encrypted
// to yourself and signed by your account. This file is the pure part (layout, merge, what a device accepts,
// checkpoints); Social moves the events. Shared with Marble Web and the Mac (marble-content-fixtures: notes-packs).

internal fun hmac(key: ByteArray, msg: String): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg.toByteArray()) }
internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * Where notes go (v3 §1): a note's slot is HMAC(K_tag, id), and a pack is named by a prefix of those bits. Packs start at
 * [START] bits (16 packs); a full one splits by one more bit (extendible hashing) and never merges back.
 */
class Layout(val kid: String, val key: ByteArray) {
    /** The note's slot, as bits ("0110…", 256 of them). */
    fun slot(id: String): String = hmac(key, id).joinToString("") { b -> (0 until 8).joinToString("") { i -> if ((b.toInt() shr (7 - i)) and 1 == 1) "1" else "0" } }
    /** A pack's `d`: HMAC(K_tag, "pack:" + prefix), 32 hex. */
    fun d(prefix: String) = hmac(key, "pack:$prefix").hex().take(32)
    /** Groups the packs so relays can page them (`#w`): HMAC(K_tag, "notes"), 16 hex. */
    val w: String get() = hmac(key, "notes").hex().take(16)
    /** The checkpoint's opaque `d`. */
    val checkpointD: String get() = hmac(key, "checkpoint").hex().take(32)
    /** Device-to-device catch-up's tag (option D). */
    val catchupW: String get() = hmac(key, "catchup").hex().take(16)
    /** The base pointer's `d` (option I). */
    val baseD: String get() = hmac(key, "base").hex().take(32)
    /** The key Blossom uploads are signed with comes from this (§7): HKDF(K_tag, "blossom"). */
    val blossom: ByteArray get() = hkdf(key, "blossom")
    /** The live pack [id] belongs in, given the packs that have split ([retired]). */
    fun owner(id: String, retired: Set<String>): String {
        val s = slot(id)
        var p = s.take(START)
        while (p in retired && p.length < s.length) p = s.take(p.length + 1)
        return p
    }
    /** Every live pack's prefix. */
    fun live(retired: Set<String>): List<String> {
        val out = ArrayList<String>()
        fun walk(p: String) { if (p in retired) { walk(p + "0"); walk(p + "1") } else out += p }
        (0 until (1 shl START)).forEach { walk(Integer.toBinaryString(it).padStart(START, '0')) }
        return out
    }
    companion object {
        const val START = 4
        /** A pack's plaintext, at most (v3 §1). */
        const val LIMIT = 16_384
    }
}

/**
 * One note as packs carry it (v3 §1): `{id, text, created, updated, f:{text,done,deleted,url,quote}, done?, url?,
 * quote?, from?, base?, body?}`. [f]: each field's own change time; [base]: a short hash of the text an edit started
 * from. Fields this version doesn't know are kept ([unknown]).
 */
data class Note(
    val id: String, val text: String, val created: Long, val updated: Long,
    val done: Long? = null, val deleted: Long? = null, val url: String? = null, val quote: String? = null, val from: String? = null,
    val f: Map<String, Long> = emptyMap(), val base: String? = null, val body: JSONObject? = null, val unknown: Map<String, Any?> = emptyMap(),
) {
    /** When [field] last changed: its own time; none, unchanged since the note was made. (A note from the single-event list gets every field's time on the way in.) */
    fun at(field: String) = f[field] ?: created

    fun toJson(): JSONObject = JSONObject().apply {
        unknown.forEach { (k, v) -> put(k, v) }
        put("id", id); put("text", text); put("created", created); put("updated", updated)
        put("f", JSONObject().apply { f.toSortedMap().forEach { (k, v) -> put(k, v) } })
        done?.let { put("done", it) }; url?.let { put("url", it) }; quote?.let { put("quote", it) }; from?.let { put("from", it) }
        base?.let { put("base", it) }; body?.let { put("body", it) }
    }

    /** The same note, as written (JSON values inside compare by content, not by object). */
    fun same(o: Note) = this == o || toJson().toString() == o.toJson().toString()

    companion object {
        val FIELDS = listOf("text", "done", "deleted", "url", "quote")
        private val KNOWN = setOf("id", "text", "created", "updated", "f", "done", "deleted", "url", "quote", "from", "base", "body")
        fun of(x: JSONObject): Note? {
            val id = x.optString("id").takeIf { it.isNotEmpty() } ?: return null
            fun long(k: String) = if (!x.has(k) || x.isNull(k)) null else x.optLong(k).takeIf { it > 0 }
            fun str(k: String) = if (!x.has(k) || x.isNull(k)) null else x.optString(k).takeIf { it.isNotEmpty() }
            val fo = x.optJSONObject("f")
            val f = fo?.keys()?.asSequence()?.associateWith { fo.optLong(it) }?.filterValues { it > 0 } ?: emptyMap()
            return Note(id, x.optString("text"), x.optLong("created"), x.optLong("updated", x.optLong("created")), long("done"), long("deleted"),
                str("url"), str("quote"), str("from"), f, str("base"), x.optJSONObject("body"),
                x.keys().asSequence().filter { it !in KNOWN }.associateWith { x.opt(it) })
        }
        /** A short hash of a text: what an edit says it started from. */
        fun hashOf(text: String) = sha256(text.toByteArray()).hex().take(12)
    }
}

/** A removed note: a marker kept in its pack for good (v3 §6), so no device brings it back. */
data class Marker(val id: String, val deleted: Long, val unknown: Map<String, Any?> = emptyMap()) {
    fun toJson(): JSONObject = JSONObject().apply { unknown.forEach { (k, v) -> put(k, v) }; put("id", id); put("deleted", deleted) }
    companion object {
        fun of(x: JSONObject): Marker? {
            val id = x.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return Marker(id, x.optLong("deleted").takeIf { it > 0 } ?: return null, x.keys().asSequence().filter { it != "id" && it != "deleted" }.associateWith { x.opt(it) })
        }
    }
}

/** A pack's content, before encryption: `{"v":3, "kid", "prefix", "items":[…], "markers":[…]}`, or retired after a split. */
data class Pack(val prefix: String, val kid: String, val items: List<Note> = emptyList(), val markers: List<Marker> = emptyList(),
                val retired: Boolean = false, val unknown: Map<String, Any?> = emptyMap()) {
    fun toJson(): String = JSONObject().apply {
        unknown.forEach { (k, v) -> put(k, v) }
        put("v", 3); put("kid", kid); put("prefix", prefix)
        if (retired) put("retired", true)
        else { put("items", JSONArray(items.sortedBy { it.id }.map { it.toJson() })); put("markers", JSONArray(markers.sortedBy { it.id }.map { it.toJson() })) }
    }.toString()

    val bytes get() = toJson().toByteArray().size

    companion object {
        private val KNOWN = setOf("v", "kid", "prefix", "items", "markers", "retired")
        fun parse(json: String): Pack? = try {
            val o = JSONObject(json)
            if (o.optInt("v") != 3) null else {
                val prefix = o.optString("prefix").takeIf { p -> p.isNotEmpty() && p.all { it == '0' || it == '1' } }
                prefix?.let { p ->
                    val items = o.optJSONArray("items"); val markers = o.optJSONArray("markers")
                    Pack(p, o.optString("kid"), (0 until (items?.length() ?: 0)).mapNotNull { items!!.optJSONObject(it)?.let(Note::of) },
                        (0 until (markers?.length() ?: 0)).mapNotNull { markers!!.optJSONObject(it)?.let(Marker::of) }, o.optBoolean("retired"),
                        o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) })
                }
            }
        } catch (_: Exception) { null }
    }
}

/** The merge (v3 §5): per field, the newest wins; two text edits from different starting points keep both. */
object Merge {
    /**
     * [mine] and [theirs] for the same id: the merged note, and (when both edited the text from different starting
     * points) a second note with the other text, "from" [device]. Its id is derived from the pair, so every device makes
     * the same copy, once.
     */
    fun notes(mine: Note, theirs: Note, device: String = "another device"): List<Note> {
        if (mine == theirs) return listOf(mine)
        val newer = if (theirs.updated > mine.updated) theirs else mine
        val older = if (newer === theirs) mine else theirs
        val f = HashMap<String, Long>()
        fun <T> pick(field: String, a: T, b: T, ta: Long, tb: Long): T { val take = if (tb > ta) b else a; f[field] = maxOf(ta, tb); return take }
        // The text: newest wins, unless both changed it from different starting points (a conflict).
        val conflict = mine.text != theirs.text && mine.deleted == null && theirs.deleted == null &&
            theirs.base != Note.hashOf(mine.text) && mine.base != Note.hashOf(theirs.text) && mine.base != null && theirs.base != null
        val text = pick("text", mine.text, theirs.text, mine.at("text"), theirs.at("text"))
        val done = pick("done", mine.done, theirs.done, mine.at("done"), theirs.at("done"))
        // Removed stays removed (a marker is for good): the earliest removal counts.
        val deleted = listOfNotNull(mine.deleted, theirs.deleted).minOrNull().also { f["deleted"] = maxOf(mine.at("deleted"), theirs.at("deleted")) }
        val url = pick("url", mine.url, theirs.url, mine.at("url"), theirs.at("url"))
        val quote = pick("quote", mine.quote, theirs.quote, mine.at("quote"), theirs.at("quote"))
        val merged = newer.copy(text = text, done = done, deleted = deleted, url = url, quote = quote, f = f,
            created = minOf(mine.created, theirs.created), updated = maxOf(mine.updated, theirs.updated),
            base = if (text == mine.text) mine.base else theirs.base, unknown = older.unknown + newer.unknown)
        if (!conflict) return listOf(merged)
        val other = if (text == mine.text) theirs else mine
        val copyId = sha256("${mine.id}|${other.text}".toByteArray()).hex().take(32)
        return listOf(merged, other.copy(id = copyId, from = device, base = null, f = mapOf("text" to other.at("text")), unknown = emptyMap()))
    }
}

/** The checkpoint (v3 §2, §4): one per flush, so a new device can tell when it has every pack. */
data class Checkpoint(val seq: Long, val packs: Int, val notes: Int, val hash: String, val epoch: Int = 0) {
    fun toJson(): String = JSONObject().put("v", 3).put("seq", seq).put("packs", packs).put("notes", notes).put("hash", hash).put("epoch", epoch).toString()
    companion object {
        fun parse(json: String): Checkpoint? = try {
            JSONObject(json).let { o -> if (o.optInt("v") != 3) null else Checkpoint(o.getLong("seq"), o.optInt("packs"), o.optInt("notes"), o.getString("hash"), o.optInt("epoch")) }
        } catch (_: Exception) { null }
        /** The hash of the sorted (d, created_at) list. */
        fun hashOf(packs: Map<String, Long>) = sha256(packs.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray()).hex()
    }
}

/**
 * Notes as this device holds them, in packs (v3 §1–§6): what a pull may change (§4), what each changed pack becomes
 * (§2), and splits. Not thread-safe: Social uses it from one place.
 */
class Vault(val layout: Layout) {
    val notes = LinkedHashMap<String, Note>()
    val markers = HashMap<String, Marker>()
    /** Packs that have split. */
    val retired = HashSet<String>()
    /** Each pack's newest `created_at` taken (by `d`): never go backwards. */
    val seen = HashMap<String, Long>()
    /** Packs with changes to send. */
    val dirty = LinkedHashSet<String>()
    /**
     * Packs (by `d`) whose newest copy on relays this device couldn't read (it didn't decrypt, or it's a later version):
     * never written over, since that would lose what's in it. Until a readable copy comes back, changes there wait.
     */
    val unreadable = HashSet<String>()
    /** Each note as last sent (a hash of it), kept with the state: a note held that differs is sent again after a restart. */
    val sent = HashMap<String, Int>()
    /** The newest checkpoint taken. */
    var checkpoint: Checkpoint? = null

    /**
     * A note as this device holds it now (Later's copy), merged in like a pulled one: true if that changed anything
     * (its pack then goes out). [device]: for a conflict copy's "from".
     */
    fun take(n: Note, device: String = "another device"): Boolean {
        if (n.id in markers) return false
        val have = notes[n.id]
        if (have != null && have.same(n)) return false
        var changed = false
        for (o in if (have == null) listOf(n) else Merge.notes(have, n, device)) {
            if (o.id in markers) continue
            if (o.deleted != null) { markers[o.id] = Marker(o.id, o.deleted); notes.remove(o.id) }
            else if (notes[o.id]?.same(o) == true) continue
            else notes[o.id] = o
            dirty += layout.owner(o.id, retired); changed = true
        }
        return changed
    }

    /** A change made on this device. */
    fun put(n: Note) {
        if (n.id in markers) return
        if (n.deleted != null) { notes.remove(n.id); markers[n.id] = Marker(n.id, n.deleted) } else notes[n.id] = n
        dirty += layout.owner(n.id, retired)
    }

    /** Decrypted, and a version this device knows (v3): anything else isn't read, so never written over. */
    private fun readable(inc: Incoming): Boolean = inc.content != null && try { org.json.JSONObject(inc.content).optInt("v") == 3 } catch (_: Exception) { false }

    /** What a pull brought: each pack's `d`, `created_at` and decrypted content (null: it didn't decrypt). */
    data class Incoming(val d: String, val createdAt: Long, val content: String?)
    /** What applying a pull did: [halted] (nothing taken), packs to send again ([republish]), how many notes changed. */
    data class Applied(val halted: Boolean, val republish: Set<String>, val changed: Int, val removed: Int)

    /**
     * A pull, applied by v3 §4: a pack is taken only if it decrypts, its prefix names its `d`, and every note in it
     * belongs there; an older one than held isn't (ours goes out again); more than 10 notes (or 5%) removed or made
     * invalid in one pull stops the whole pull. [device]: who the other side is, for a conflict copy's "from".
     */
    fun apply(pull: List<Incoming>, device: String = "another device"): Applied {
        val byD = layout.live(retired).associateBy { layout.d(it) } + retired.associateBy { layout.d(it) }
        val republish = HashSet<String>()
        val valid = ArrayList<Pair<Incoming, Pack>>()
        var invalid = 0
        // A pack whose newest copy can't be read here: kept as it is on the relays (nothing taken, nothing sent over it).
        val newest = pull.groupBy { it.d }.mapValues { (_, l) -> l.maxBy { it.createdAt } }
        for ((d, inc) in newest) if (!readable(inc)) unreadable += d else unreadable -= d
        for (inc in pull.sortedBy { it.createdAt }) {
            if (!readable(inc)) continue
            val pack = inc.content?.let(Pack::parse)
            val ok = pack != null && layout.d(pack.prefix) == inc.d && pack.kid == layout.kid &&
                pack.items.all { layout.slot(it.id).startsWith(pack.prefix) } && pack.markers.all { layout.slot(it.id).startsWith(pack.prefix) }
            if (!ok) { invalid++; byD[inc.d]?.let { republish += it }; continue }
            if ((seen[inc.d] ?: 0) > inc.createdAt) { republish += pack!!.prefix; continue }
            valid += inc to pack!!
        }
        // What this pull would remove: notes held here that it marks removed.
        val removing = valid.flatMap { (_, p) -> p.markers.map { it.id } + p.items.filter { it.deleted != null }.map { it.id } }.toSet().count { it in notes }
        val total = notes.size.coerceAtLeast(1)
        if (invalid + removing > maxOf(10, total * 5 / 100)) return Applied(true, emptySet(), 0, 0)
        var changed = 0
        for ((inc, pack) in valid) {
            seen[inc.d] = maxOf(seen[inc.d] ?: 0, inc.createdAt)
            if (pack.retired) { if (retired.add(pack.prefix)) changed++; continue }
            for (m in pack.markers) if (markers[m.id] == null) { markers[m.id] = m; if (notes.remove(m.id) != null) changed++ }
            for (n in pack.items) {
                if (n.id in markers) continue
                val have = notes[n.id]
                val out = if (have == null) listOf(n) else Merge.notes(have, n, device)
                for (o in out) {
                    if (o.deleted != null) { markers[o.id] = Marker(o.id, o.deleted); notes.remove(o.id); changed++; continue }
                    if (notes[o.id] != o) { notes[o.id] = o; changed++ }
                    // A conflict's copy is a new note: its pack (maybe another) goes out.
                    if (o.id != n.id && o.id !in markers) dirty += layout.owner(o.id, retired)
                }
                // This device had something newer in that note: its pack goes out again.
                if (have != null && out.first() != n) republish += layout.owner(n.id, retired)
            }
        }
        dirty += republish
        return Applied(false, republish, changed, removing)
    }

    /**
     * What pack [prefix] holds now, ready to send: and if it's grown past the limit, the split (the old pack retired, its
     * notes into two packs by one more bit, again while either is still too big). Each entry: a pack to publish.
     */
    fun packsFor(prefix: String): List<Pack> {
        // Already split: its marker again, and its parts (never an empty live pack over the marker).
        if (prefix in retired) return listOf(Pack(prefix, layout.kid, retired = true)) + packsFor(prefix + "0") + packsFor(prefix + "1")
        val pack = Pack(prefix, layout.kid, notes.values.filter { layout.owner(it.id, retired) == prefix }, markers.values.filter { layout.owner(it.id, retired) == prefix })
        if (pack.bytes <= Layout.LIMIT || pack.items.size + pack.markers.size <= 1) return listOf(pack)
        retired += prefix
        return listOf(Pack(prefix, layout.kid, retired = true)) + packsFor(prefix + "0") + packsFor(prefix + "1")
    }

    /** The checkpoint for what's held now, with the next sequence number. */
    fun nextCheckpoint(): Checkpoint = Checkpoint((checkpoint?.seq ?: 0) + 1, seen.size, notes.size, Checkpoint.hashOf(seen)).also { checkpoint = it }

    /** A checkpoint read from relays: one with a lower `seq` than seen is refused. True: taken. */
    fun takeCheckpoint(c: Checkpoint): Boolean { if (c.seq < (checkpoint?.seq ?: 0)) return false; checkpoint = c; return true }

    /** Whether what's held matches the newest checkpoint (a new device: "syncing…" until it does). */
    fun complete(): Boolean = checkpoint?.let { it.hash == Checkpoint.hashOf(seen) } ?: true
}

/** The write cycle (v3 §2), apart from the relays: Social passes the real reads and writes, a test a fake relay. */
object Cycle {
    /**
     * Send the changed packs: for each, read it ([read]: what the relays hold for that `d`), merge, write it with
     * `created_at = max(now, newest seen + 1)` ([write]), and read it back; if another device's copy is there instead,
     * merge again and write again (three tries). Returns how many packs were written.
     */
    suspend fun flush(v: Vault, now: Long, read: suspend (String) -> List<Vault.Incoming>?, write: suspend (d: String, createdAt: Long, content: String) -> Boolean,
              device: String = "another device"): Int {
        var written = 0
        val todo = ArrayDeque(v.dirty.toList())
        val done = HashSet<String>()
        v.dirty.clear()
        // Stopping early (a read that didn't reach the account, a halt): this pack and every one not reached yet stay to send.
        fun stop(prefix: String): Int { v.dirty += prefix; v.dirty += todo.filter { it !in done }; return written }
        // Anything thrown (a cancel, the network) puts back what wasn't finished, as an early stop does.
        var current: String? = null
        try {
        while (todo.isNotEmpty()) {
            val prefix = todo.removeFirst()
            if (!done.add(prefix)) continue
            current = prefix
            for (attempt in 0 until 3) {
                // A read that didn't reach your account (null) tells nothing: the change waits for one that does.
                val before = read(v.layout.d(prefix)) ?: return stop(prefix)
                val applied = v.apply(before, device)
                if (applied.halted) return stop(prefix)
                // Every pack about to be written is read (and merged) first, as the parent was. A part read back as
                // itself split (another device went deeper) is drawn again, and its parts read, until none is unread.
                val heard = HashMap<String, List<Vault.Incoming>>().apply { put(v.layout.d(prefix), before) }
                var packs = v.packsFor(prefix)
                while (true) {
                    val unread = packs.filter { v.layout.d(it.prefix) !in heard }
                    if (unread.isEmpty()) break
                    for (p in unread) {
                        val d = v.layout.d(p.prefix)
                        val got = read(d) ?: return stop(prefix)
                        if (v.apply(got, device).halted) return stop(prefix)
                        heard[d] = got
                    }
                    packs = v.packsFor(prefix)
                }
                // A pack this device couldn't read stays as it is: the change waits for a readable copy.
                if (packs.any { v.layout.d(it.prefix) in v.unreadable }) { v.dirty += prefix; break }
                var clean = true
                var took = true
                for (p in packs) {
                    val d = v.layout.d(p.prefix)
                    val at = maxOf(now, (v.seen[d] ?: 0) + 1, ((heard[d] ?: before).maxOfOrNull { it.createdAt } ?: 0) + 1)
                    val content = p.toJson()
                    // Done only when most of your write relays took it; else it stays to send, and isn't the newest seen.
                    if (!write(d, at, content)) { took = false; break }
                    written++
                    v.seen[d] = at
                    p.items.forEach { v.sent[it.id] = it.fingerprint() }
                    // Read back: the newest copy should be ours.
                    val back = read(d)?.maxByOrNull { it.createdAt }
                    if (back == null || back.createdAt > at || (back.createdAt == at && back.content != content)) clean = false
                }
                if (!took) { v.dirty += prefix; v.dirty += packs.map { it.prefix }; break }
                v.dirty.removeAll(packs.map { it.prefix }.toSet())
                if (clean) break
                // Not ours on the relays after three tries (or none answered): it goes out again next flush.
                if (attempt == 2) v.dirty += prefix
            }
            // What merging just made (a conflict's copy in another pack) goes in this flush too.
            v.dirty.filter { it !in done }.forEach { todo.addLast(it) }
            current = null
        }
        } catch (e: Throwable) { current?.let { v.dirty += it }; v.dirty += todo.filter { it !in done }; throw e }
        return written
    }
}

/**
 * The pack keys (K_tag), random, kept in a small event encrypted to yourself (option A: not derived). Two devices that
 * both made one before seeing the other's keep both: the list only ever grows (union), packs under any are read, and new
 * writes use the oldest ([primary]), so notes under the other move over as they're written.
 */
data class Keys(val keys: List<Key>) {
    data class Key(val kid: String, val k: String, val created: Long)
    val primary: Key? get() = keys.minWithOrNull(compareBy<Key> { it.created }.thenBy { it.kid })
    fun union(o: Keys) = Keys((keys + o.keys).distinctBy { it.kid }.sortedBy { it.created })
    fun toJson(): String = JSONObject().put("v", 1).put("keys", JSONArray(keys.map { JSONObject().put("kid", it.kid).put("k", it.k).put("created", it.created) })).toString()
    companion object {
        const val D = "marble/notes/keys"
        fun parse(json: String?): Keys? = try {
            val a = JSONObject(json ?: return null).optJSONArray("keys") ?: return null
            Keys((0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                val k = o.optString("k"); if (k.length != 64 || !k.all { it in "0123456789abcdef" }) null else Key(o.optString("kid").ifEmpty { k.take(8) }, k, o.optLong("created"))
            } })
        } catch (_: Exception) { null }
        fun fresh(now: Long): Keys {
            val r = java.security.SecureRandom()
            val k = ByteArray(32).also(r::nextBytes).hex()
            return Keys(listOf(Key("k" + ByteArray(4).also(r::nextBytes).hex(), k, now)))
        }
    }
}

/** Notes another vault holds (packs under another key), merged in here and so rewritten under this one. */
fun Vault.adopt(other: Vault) {
    for (m in other.markers.values) take(Note(m.id, "", m.deleted, m.deleted, deleted = m.deleted))
    for (n in other.notes.values) take(n)
}

/** What this device keeps between runs (its notes are Later's items; this is the rest). */
/** What a note says that a change would touch (words, done, its url and when), stable across Later's round trip. */
fun Note.fingerprint(): Int = listOf(text, updated, done, url, quote).joinToString("\u0000").hashCode()

fun Vault.state(): String = JSONObject()
    .put("markers", JSONArray(markers.values.map { it.toJson() }))
    .put("retired", JSONArray(retired.toList())).put("dirty", JSONArray(dirty.toList()))
    .put("seen", JSONObject().apply { seen.forEach { (k, v) -> put(k, v) } })
    .put("sent", JSONObject().apply { sent.forEach { (k, v) -> put(k, v) } })
    .put("checkpoint", checkpoint?.toJson()).toString()

fun Vault.restore(json: String?, held: List<Note>) {
    held.forEach { notes[it.id] = it }
    val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
    o.optJSONArray("markers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Marker::of) }.forEach { markers[it.id] = it; notes.remove(it.id) } }
    o.optJSONArray("retired")?.let { a -> (0 until a.length()).forEach { retired += a.optString(it) } }
    o.optJSONArray("dirty")?.let { a -> (0 until a.length()).forEach { dirty += a.optString(it) } }
    o.optJSONObject("seen")?.let { s -> s.keys().forEach { seen[it] = s.optLong(it) } }
    o.optString("checkpoint").takeIf { it.isNotEmpty() && it != "null" }?.let(Checkpoint::parse)?.let { checkpoint = it }
    // A change taken in but never sent (the app stopped before its state was saved): to send. (A state from before this
    // was kept has no record: nothing is assumed.)
    o.optJSONObject("sent")?.let { s -> s.keys().forEach { sent[it] = s.optInt(it) }
        for (n in held) if (n.id !in markers && sent[n.id] != n.fingerprint()) dirty += layout.owner(n.id, retired) }
}
