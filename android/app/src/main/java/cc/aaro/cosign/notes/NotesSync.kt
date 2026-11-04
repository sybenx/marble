package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One of your own kind-30078 events as read: its `d`, time, and content decrypted (null: it didn't decrypt). */
data class Stored(val id: String, val d: String, val createdAt: Long, val content: String?)

/** The relays, as Social reaches them: your own events only, checked and decrypted on the way in, encrypted and signed on the way out. */
interface NotesNet {
    /** By `#d` or `#w` ([tag]); paged with [until], or only newer than [since]. Null: the read didn't reach your account (most of your write relays didn't answer). */
    suspend fun read(tag: String, values: List<String>, until: Long? = null, since: Long? = null, limit: Int = 500): List<Stored>?
    /** [d], and [w] for packs (so they page together). True: it's done (more than half of your write relays took it). */
    suspend fun write(d: String, w: String?, createdAt: Long, content: String): Boolean
    /** A Blossom blob from the first of [servers] that has it, checked against [sha256]. Null: none had it. */
    suspend fun fetchBlob(sha256: String, servers: List<String>): ByteArray? = null
    /** Uploaded, the auth signed by a key from [seed] (never the account's, §7). The servers that took it. */
    suspend fun uploadBlob(bytes: ByteArray, sha256: String, seed: ByteArray): List<String> = emptyList()
    suspend fun deleteBlob(sha256: String, servers: List<String>, seed: ByteArray) {}
    /** Ephemeral (not kept by relays), tagged [w], encrypted to yourself and signed by a throwaway key (option D). */
    suspend fun shout(w: String, content: String): Boolean = false
    /** Ephemeral events tagged [w] from now on, decrypted, to [onMessage] until closed. Null: can't listen. */
    fun hear(w: String, onMessage: (String) -> Unit): AutoCloseable? = null
}

/** Where this device keeps the keys and the vault's own state between runs. */
interface NotesStore { fun get(k: String): String?; fun put(k: String, v: String) }

/**
 * Notes sync v3 under option A, between Later's items and the relays: the keys event, the packs (paged), a checkpoint
 * per flush, and the single-event list (`marble/later`) kept current while it fits (§8, phase 1) and read always, so
 * an older Marble and a newer one see the same notes. One pull or flush at a time.
 */
class NotesSync(private val net: NotesNet, private val store: NotesStore, private val now: () -> Long,
                held: List<LaterItem>, private val device: String = "another device", private val answerWithinMs: Long = 3_000) {
    private val lock = Mutex()
    var keys: Keys? = Keys.parse(store.get(KEYS)); private set
    /** The vault under the primary key (or, before there are keys, one only for merging, never written). */
    private var vault: Vault = restore(held)
    /** What the single-event list last held as read or written (its items, sorted), so it's only written when it changes. */
    private var v1Last: String? = store.get(V1)
    private var v1Unknown: Map<String, Any?> = emptyMap()
    /** The last pull stopped: more than 10 notes (or 5%) would have gone at once. Nothing was taken. */
    var halted = false; private set
    /** The last pull hit the page cap (20 pages) for some key: what's shown may not be everything yet. */
    var partial = false; private set

    /** Something here hasn't gone out yet. */
    val pending: Boolean get() = vault.dirty.isNotEmpty() || (keys != null && v1Json() != v1Last)
    /** A newer device: not everything the checkpoint names is here yet. */
    val syncing: Boolean get() = !vault.complete()

    private fun restore(held: List<LaterItem>): Vault {
        val k = keys?.primary
        val v = Vault(k?.let { Layout(it.kid, it.k.unhex()) } ?: LOCAL)
        val notes = held.filter { it.deleted == null }.map(LaterNotes::note)
        val state = store.get(VAULT)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        if (k != null && state?.optString("kid") == k.kid) v.restore(state.optString("state"), notes) else notes.forEach { v.take(it, device) }
        held.filter { it.deleted != null }.forEach { v.take(LaterNotes.note(it), device) }
        return v
    }

    /** The notes as Later shows them. */
    fun items(): List<LaterItem> = vault.notes.values.map(LaterNotes::item).sortedByDescending { it.created }

    /**
     * What the relays hold, merged with [held] (Later's items now): the old list, then the packs under every key. [full]:
     * every page, not only what's new since the last pull. Null: nothing could be read.
     */
    suspend fun pull(held: List<LaterItem>, full: Boolean = false): List<LaterItem>? = lock.withLock {
        took(held)
        val since = if (full) null else store.get(PULLED)?.toLongOrNull()?.minus(600)
        val k = keys(create = false)?.also(::rekey)
        val v1 = net.read("d", listOf(OLD))
        if (v1 == null && k == null) return null
        v1?.maxByOrNull { it.createdAt }?.content?.let(::fromV1)
        halted = false; partial = false
        if (k != null) {
            // A device that has never pulled: from another device that's open, if one answers (option D); else pages.
            val caughtUp = store.get(PULLED) == null && catchUp()
            // A page that didn't come (null): the next pull reads from as far back again, not from now.
            var missed = false
            if (!caughtUp) for (key in k.keys) {
                val layout = Layout(key.kid, key.k.unhex())
                val got = page(layout.w, since) ?: run { missed = true; null } ?: continue
                // Per pack, its newest copy (a tie: the lower id, as relays keep).
                val newest = got.groupBy { it.d }.values.map { c -> c.sortedWith(compareByDescending<Stored> { it.createdAt }.thenBy { it.id }).first() }
                val incoming = newest.map { Vault.Incoming(it.d, it.createdAt, it.content) }
                if (key.kid == vault.layout.kid) { if (vault.apply(incoming, device).halted) halted = true }
                else Vault(layout).also { if (it.apply(incoming, device).halted) halted = true else vault.adopt(it) }
            }
            if (!caughtUp) base()
            if (!caughtUp) net.read("d", listOf(vault.layout.checkpointD)).also { if (it == null) missed = true }
                ?.maxByOrNull { it.createdAt }?.content?.let(Checkpoint::parse)?.let(vault::takeCheckpoint)
            // Only after every page came, all of them (no cap reached, no later page missing) and nothing halted.
            if (!missed && !partial && !halted) store.put(PULLED, now().toString())
        }
        save()
        items()
    }

    /** Send what changed: the packs (§2), a checkpoint, and the old list while it's kept. Null: nothing could be sent. */
    suspend fun flush(held: List<LaterItem>): List<LaterItem>? = lock.withLock {
        took(held)
        keys(create = vault.notes.isNotEmpty() || vault.markers.isNotEmpty())?.also(::rekey) ?: return null
        // An import's worth of changes: one file and its pointer, not a signature per pack (option I).
        if (vault.dirty.sumOf { p -> vault.notes.values.filter { vault.layout.owner(it.id, vault.retired) == p }.sumOf { it.toJson().toString().length } } > BASE_AT * Layout.LIMIT / 2) writeBase()
        cleanUp()
        val w = vault.layout.w
        val written = Cycle.flush(vault, now(), read = { d -> net.read("d", listOf(d))?.map { Vault.Incoming(it.d, it.createdAt, it.content) } },
            write = { d, at, content -> net.write(d, w, at, content) }, device = device)
        // The checkpoint after reading the one there (a higher seq, newer than it); none written if it couldn't be read.
        if (written > 0) net.read("d", listOf(vault.layout.checkpointD))?.let { there ->
            val newest = there.maxByOrNull { it.createdAt }
            // Never over one this device can't read, or a later version.
            if (newest != null && (newest.content == null || Checkpoint.parse(newest.content) == null)) return@let
            newest?.content?.let(Checkpoint::parse)?.let(vault::takeCheckpoint)
            // Not taken: the checkpoint seq goes back to the one held (the next flush tries again).
            val held = vault.checkpoint
            if (!net.write(vault.layout.checkpointD, null, maxOf(now(), (newest?.createdAt ?: 0) + 1), vault.nextCheckpoint().toJson())) vault.checkpoint = held
        }
        // The old list: never over a copy that wasn't read, or isn't v1 (a later version, or "moved").
        var v1 = v1Json()
        if (v1 != v1Last) net.read("d", listOf(OLD))?.let { there ->
            val newest = there.maxByOrNull { it.createdAt }
            val v = newest?.let { n -> n.content?.let { c -> try { JSONObject(c).optInt("v", 1) } catch (_: Exception) { 0 } } ?: 0 }
            // A v1 copy there (an older Marble's changes since): merged first, so they aren't written over.
            if (v == 1) { newest.content?.let(::fromV1); v1 = v1Json() }
            // A later version there (or "moved"): left as it is, and nothing waits to go (it isn't ours to write over).
            // (One that didn't decrypt here is left too, and not waited on: it's tried again at the next change.)
            if (newest != null && (newest.content == null || v != 1)) { v1Last = v1; store.put(V1, v1) }
            else if ((newest == null || v == 1) && net.write(OLD, null, maxOf(now(), (newest?.createdAt ?: 0) + 1), v1Event(v1))) { v1Last = v1; store.put(V1, v1) }
        }
        save()
        items()
    }

    /** A new primary key (the first, or an older one another device made): everything held moves under it. */
    private fun rekey(k: Keys) {
        val p = k.primary ?: return
        if (vault.layout.kid == p.kid) return
        val old = vault
        vault = Vault(Layout(p.kid, p.k.unhex())).also { it.adopt(old) }
    }

    /** The base file (option I) under the packs: fetched when its pointer names one not taken yet. */
    private suspend fun base(given: BasePointer? = null) {
        val p = given ?: BasePointer.parse(net.read("d", listOf(vault.layout.baseD))?.maxByOrNull { it.createdAt }?.content) ?: return
        if (p.kid != vault.layout.kid || p.sha256 == store.get(BASE)) return
        val file = net.fetchBlob(p.sha256, p.servers) ?: return
        if (sha256(file).hex() != p.sha256 || file.size.toLong() != p.size) return
        val body = Sealed.open(file, BaseFile.ID, p.updated, p.key.unhex()) ?: return
        val (notes, markers) = BaseFile.parse(body, vault.layout.kid) ?: return
        vault.under(notes, markers)
        store.put(BASE, p.sha256); store.put(BASE_POINTER, p.toJson())
    }

    /** Everything held, as one file on Blossom with a pointer to it; the packs then only carry later changes. */
    private suspend fun writeBase() {
        val l = vault.layout
        val there = net.read("d", listOf(l.baseD)) ?: return
        val old = there.maxByOrNull { it.createdAt }
        // Another device's base not taken here yet: packs as usual, rather than write over it.
        val oldPointer = old?.let { BasePointer.parse(it.content) }
        if (old != null && oldPointer?.sha256 != store.get(BASE)) return
        val r = java.security.SecureRandom()
        val key = ByteArray(32).also(r::nextBytes)
        val updated = maxOf(now(), (old?.createdAt ?: 0) + 1)
        val file = Sealed.seal(BaseFile.body(vault), BaseFile.ID, updated, key, ByteArray(12).also(r::nextBytes))
        val sha = sha256(file).hex()
        val servers = net.uploadBlob(file, sha, hkdf(l.blossom, sha))
        if (servers.isEmpty()) return
        val pointer = BasePointer(sha, file.size.toLong(), key.hex(), servers, updated, l.kid).toJson()
        if (!net.write(l.baseD, null, updated, pointer)) return
        if (net.read("d", listOf(l.baseD))?.maxByOrNull { it.createdAt }?.content != pointer) return
        store.put(BASE, sha); store.put(BASE_POINTER, pointer)
        vault.dirty.clear()
        // The file it replaces goes in 30 days (§7).
        if (oldPointer != null) store.put(OLD_BASES, JSONArray(oldBases() + JSONObject().put("sha256", oldPointer.sha256).put("servers", JSONArray(oldPointer.servers)).put("at", now())).toString())
    }

    private fun oldBases(): List<JSONObject> = try { JSONArray(store.get(OLD_BASES) ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } catch (_: Exception) { emptyList() }

    private suspend fun cleanUp() {
        val (due, later) = oldBases().partition { now() - it.optLong("at") > 30 * 86_400L }
        if (due.isEmpty()) return
        for (o in due) o.optJSONArray("servers")?.let { a -> net.deleteBlob(o.getString("sha256"), (0 until a.length()).map { a.getString(it) }, hkdf(vault.layout.blossom, o.getString("sha256"))) }
        store.put(OLD_BASES, JSONArray(later).toString())
    }

    // --- Option D: device to device ---------------------------------------------------------------
    //
    // A device that has never pulled asks (`want`, with a request id); an open device answers with every pack it holds
    // (`pack`: d, created_at, the pack), then `done` with the checkpoint of what it sent and its base pointer. Ephemeral
    // events, encrypted to yourself: nothing is stored, and nothing goes through your account's signature.

    private val basePointer: BasePointer? get() = BasePointer.parse(store.get(BASE_POINTER))

    /** Request ids this device sent (it doesn't answer itself). */
    private val asked = HashSet<String>()

    /**
     * Ask an open device for everything. True: it answered in full and what it sent matches its checkpoint. No answer
     * within [firstMs] (no device open), or a stall of [stallMs] between messages: false, and the caller pages.
     */
    private suspend fun catchUp(firstMs: Long = answerWithinMs, stallMs: Long = 20_000): Boolean {
        val w = vault.layout.catchupW
        val rid = ByteArray(8).also(java.security.SecureRandom()::nextBytes).hex()
        val inbox = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val sub = net.hear(w) { inbox.trySend(it) } ?: return false
        try {
            asked += rid
            if (!net.shout(w, JSONObject().put("t", "want").put("r", rid).toString())) return false
            val got = ArrayList<Vault.Incoming>()
            var done: JSONObject? = null
            var heard = false
            while (done == null) {
                val m = kotlinx.coroutines.withTimeoutOrNull(if (heard) stallMs else firstMs) { inbox.receive() } ?: return false
                val o = try { JSONObject(m) } catch (_: Exception) { continue }
                if (o.optString("r") != rid) continue
                heard = true
                when (o.optString("t")) {
                    "pack" -> got += Vault.Incoming(o.optString("d"), o.optLong("at"), o.optJSONObject("pack")?.toString())
                    "done" -> done = o
                }
            }
            if (got.size != done.optInt("n", -1)) return false
            if (vault.apply(got, device).halted) { halted = true; return false }
            done.optJSONObject("base")?.let { base(BasePointer.parse(it.toString())) }
            done.optString("checkpoint").takeIf { it.isNotEmpty() }?.let(Checkpoint::parse)?.let(vault::takeCheckpoint)
            return vault.complete()
        } finally { sub.close() }
    }

    /** While Marble is open: answer another device's request, with what [held] (Later's items) has then. Runs until cancelled. */
    suspend fun serve(held: () -> List<LaterItem>) {
        val w = lock.withLock { keys?.primary?.let { rekey(keys!!); vault.layout.catchupW } } ?: return
        val inbox = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val sub = net.hear(w) { inbox.trySend(it) } ?: return
        try {
            for (m in inbox) {
                val o = try { JSONObject(m) } catch (_: Exception) { continue }
                val rid = o.optString("r")
                if (o.optString("t") == "want" && rid.isNotEmpty() && rid !in asked) answer(w, rid, held())
            }
        } finally { sub.close() }
    }

    private suspend fun answer(w: String, rid: String, held: List<LaterItem>) {
        // At once, so the asker waits for the rest rather than going to the relays.
        if (!net.shout(w, JSONObject().put("t", "hi").put("r", rid).toString())) return
        // What's held now, as packs (not split here: that's the next flush's), with each one's time on the relays.
        val (packs, done) = lock.withLock {
            took(held)
            val l = vault.layout
            val live = l.live(vault.retired).map { p -> Pack(p, l.kid, vault.notes.values.filter { l.owner(it.id, vault.retired) == p }, vault.markers.values.filter { l.owner(it.id, vault.retired) == p }) }
                .filter { it.items.isNotEmpty() || it.markers.isNotEmpty() }
            val all = live + vault.retired.map { Pack(it, l.kid, retired = true) }
            val at = all.associate { l.d(it.prefix) to (vault.seen[l.d(it.prefix)] ?: 0L) }
            val c = Checkpoint(vault.checkpoint?.seq ?: 0, all.size, vault.notes.size, Checkpoint.hashOf(at))
            all.map { Triple(l.d(it.prefix), at[l.d(it.prefix)]!!, it.toJson()) } to
                JSONObject().put("t", "done").put("r", rid).put("n", all.size).put("checkpoint", c.toJson())
                    .apply { store.get(BASE)?.let { sha -> basePointer?.takeIf { it.sha256 == sha }?.let { put("base", JSONObject(it.toJson())) } } }
        }
        for ((d, at, pack) in packs) if (!net.shout(w, JSONObject().put("t", "pack").put("r", rid).put("d", d).put("at", at).put("pack", JSONObject(pack)).toString())) return
        net.shout(w, done.toString())
    }

    /** Later's items into the vault: what changed here since the last sync. */
    private fun took(held: List<LaterItem>) { for (i in held) vault.take(LaterNotes.note(i), device) }

    /**
     * The keys event: read, and joined with what this device knows. Made only when [create] (there's something to write)
     * and the relays answered with none; never written over one this device can't read.
     */
    private suspend fun keys(create: Boolean): Keys? {
        val got = net.read("d", listOf(Keys.D)) ?: return keys
        val parsed = got.map { Keys.parse(it.content) }
        val there = parsed.filterNotNull().fold(Keys(emptyList())) { a, b -> a.union(b) }
        var k = (keys ?: Keys(emptyList())).union(there)
        if (k.keys.isEmpty()) { if (!create || got.isNotEmpty()) return null; k = Keys.fresh(now()) }
        if (k.keys.toSet() != there.keys.toSet() && parsed.none { it == null }) {
            // Not taken by most of your write relays: not used (packs under a key no other device has would be lost to them).
            if (!net.write(Keys.D, null, maxOf(now(), (got.maxOfOrNull { it.createdAt } ?: 0) + 1), k.toJson())) {
                // What's there, and what this device already had: never the new key that wasn't taken.
                val known = (keys ?: Keys(emptyList())).union(there).takeIf { it.keys.isNotEmpty() } ?: return null
                if (known != keys) { keys = known; store.put(KEYS, known.toJson()) }
                return known
            }
            // Read back: another device may have written its own key at the same moment.
            net.read("d", listOf(Keys.D))?.mapNotNull { Keys.parse(it.content) }?.forEach { k = k.union(it) }
        }
        if (k != keys) { keys = k; store.put(KEYS, k.toJson()) }
        return k
    }

    /** One key's packs, paged (§3): `until` the oldest seen, with no −1; until a page adds nothing, at most 20 pages. */
    private suspend fun page(w: String, since: Long?): List<Stored>? {
        val all = LinkedHashMap<String, Stored>()
        var until: Long? = null
        repeat(20) {
            val got = net.read("w", listOf(w), until, since) ?: run { partial = true; return if (all.isEmpty()) null else all.values.toList() }
            if (got.count { all.put(it.id, it) == null } == 0) return all.values.toList()
            until = got.minOf { it.createdAt }
        }
        partial = true
        return all.values.toList()
    }

    /**
     * The old list read (§8): a note it has newer than here is taken, but for one cut short there (`long`, or its text is
     * the first 500 characters of the note's), only whether it's done or removed, never the cut text.
     */
    private fun fromV1(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        if (o.optInt("v", 1) != 1) return
        v1Unknown = o.keys().asSequence().filter { it !in setOf("v", "at", "items") }.associateWith { o.opt(it) }
        val items = cc.aaro.cosign.Later.fromJson(json)
        val take = items.mapNotNull { i ->
            val have = vault.notes[i.id]
            if (i.id in vault.markers || (have != null && i.updated <= have.updated)) return@mapNotNull null
            var n = LaterNotes.note(i).let { it.copy(unknown = it.unknown - "long") }
            // Changed by an app that doesn't keep field times: all of it counts as changed then.
            if ((n.f.values.maxOrNull() ?: 0) < i.updated) n = n.copy(f = Note.FIELDS.associateWith { f -> maxOf(n.f[f] ?: 0, i.updated) })
            if (have != null && (i.extra["long"] == true || (have.text.length > CUT && i.text == have.text.take(CUT))))
                n = n.copy(text = have.text, base = have.base, f = n.f + ("text" to have.at("text")))
            n
        }
        // The same stop as a pull's: too many removals at once, and nothing is taken.
        if (take.count { it.deleted != null && it.id in vault.notes } > maxOf(10, vault.notes.size * 5 / 100)) { halted = true; return }
        take.forEach { vault.take(it, device) }
        v1Last = v1Items(items.map(LaterNotes::note).filter { it.deleted == null }, items.filter { it.deleted != null }.map { Marker(it.id, it.deleted!!) })
    }

    /** The old list's items as this device would write them (texts cut to 500 with `long`, removals a month), sorted. */
    private fun v1Items(notes: Collection<Note>, markers: Collection<Marker>): String {
        val cutoff = now() - 30 * 86_400L
        val a = notes.sortedBy { it.id }.map { n ->
            val o = LaterNotes.item(n).let { i -> i.copy(text = i.text.take(CUT), extra = i.extra + (if (n.text.length > CUT) mapOf("long" to true) else emptyMap())) }
            cc.aaro.cosign.Later.itemJson(o)
        } + markers.filter { it.deleted > cutoff }.sortedBy { it.id }.map { m ->
            JSONObject().put("id", m.id).put("text", "").put("created", m.deleted).put("updated", m.deleted).put("done", JSONObject.NULL).put("deleted", m.deleted)
        }
        return JSONArray(a).toString()
    }
    private fun v1Json() = v1Items(vault.notes.values, vault.markers.values)

    /** The old list's content: v1 while it fits in 32,000 bytes, then a note that the notes have moved. */
    private fun v1Event(items: String): String {
        val o = JSONObject()
        v1Unknown.forEach { (k, v) -> o.put(k, v) }
        o.put("v", 1).put("at", now()).put("items", JSONArray(items))
        return o.toString().takeIf { it.toByteArray().size <= 32_000 } ?: JSONObject().put("v", 2).put("moved", "notes").toString()
    }

    private fun save() {
        if (vault.layout === LOCAL) return
        store.put(VAULT, JSONObject().put("kid", vault.layout.kid).put("state", vault.state()).toString())
    }

    companion object {
        const val OLD = "marble/later"
        const val CUT = 500
        /** About this many pack signatures in one flush, and it goes as a base file instead. */
        const val BASE_AT = 24
        private const val KEYS = "notesKeys"; private const val VAULT = "notesVault"; private const val V1 = "notesV1"; private const val PULLED = "notesPulled"
        private const val BASE = "notesBase"; private const val BASE_POINTER = "notesBasePointer"; private const val OLD_BASES = "notesOldBases"
        /** Before there are keys: a vault only for merging (never written, never saved). */
        private val LOCAL = Layout("local", ByteArray(32))
    }
}
