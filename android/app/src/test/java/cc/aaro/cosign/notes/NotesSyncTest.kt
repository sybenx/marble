package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync against a fake relay: moving over from the single list, two devices, keys made at once, an older Marble. */
class NotesSyncTest {
    /** Replaceable events by `d` (the newest; a tie: the lower id), with `w` tags, paged newest first. */
    class Relay {
        data class Ev(val id: String, val d: String, val w: String?, val at: Long, val content: String)
        val held = HashMap<String, Ev>()
        var reads = 0
        var down = false
        var blossom = true
        val ears = HashMap<String, MutableList<(String) -> Unit>>()
        /** Lose this many-th ephemeral message (0: none). */
        var drop = 0
        private var shouts = 0
        var pageReads = 0
        var writes = 0
        var packWrites = 0
        /** Reads that don't reach the account (null), and writes most relays don't take (false), by tag and value. */
        var unread: (String, List<String>) -> Boolean = { _, _ -> false }
        var untaken: (String) -> Boolean = { false }
        val blobs = HashMap<String, ByteArray>()
        private var n = 0
        val net = object : NotesNet {
            override suspend fun read(tag: String, values: List<String>, until: Long?, since: Long?, limit: Int): List<Stored>? {
                if (down || unread(tag, values)) return null
                reads++; if (tag == "w") pageReads++
                return held.values.filter { if (tag == "d") it.d in values else it.w in values }
                    .filter { e -> (until == null || e.at <= until) && (since == null || e.at >= since) }
                    .sortedByDescending { it.at }.take(limit).map { Stored(it.id, it.d, it.at, it.content) }
            }
            override suspend fun fetchBlob(sha256: String, servers: List<String>) = if (down) null else blobs[sha256]
            override suspend fun uploadBlob(bytes: ByteArray, sha256: String, seed: ByteArray): List<String> {
                if (down || !blossom) return emptyList()
                blobs[sha256] = bytes; return listOf("https://blossom.test")
            }
            override suspend fun deleteBlob(sha256: String, servers: List<String>, seed: ByteArray) { blobs.remove(sha256) }
            override suspend fun shout(w: String, content: String): Boolean {
                if (down) return false
                if (drop > 0 && ++shouts == drop) return true
                ears[w]?.toList()?.forEach { it(content) }; return true
            }
            override fun hear(w: String, onMessage: (String) -> Unit): AutoCloseable? {
                if (down) return null
                ears.getOrPut(w) { mutableListOf() }.add(onMessage); return AutoCloseable { ears[w]?.remove(onMessage) }
            }
            override suspend fun write(d: String, w: String?, createdAt: Long, content: String): Boolean {
                if (down || untaken(d)) return false
                writes++; if (w != null) packWrites++
                val e = Ev("%08d".format(++n), d, w, createdAt, content)
                val have = held[d]
                if (have == null || createdAt > have.at || (createdAt == have.at && e.id < have.id)) held[d] = e
                return true
            }
        }
    }
    class Store : NotesStore { val m = HashMap<String, String>(); override fun get(k: String) = m[k]; override fun put(k: String, v: String) { m[k] = v } }

    /** A device: its Later items, its store, and the sync between them. */
    inner class Device(val relay: Relay, var items: List<LaterItem> = emptyList(), val store: Store = Store()) {
        val sync = NotesSync(relay.net, store, { clock }, items, answerWithinMs = 100)
        fun pull(full: Boolean = true) = runBlocking { sync.pull(items, full) }?.also { items = it }
        fun flush() = runBlocking { sync.flush(items) }?.also { items = it }
        fun edit(id: String, f: (LaterItem) -> LaterItem) { val was = items.first { it.id == id }; items = items.map { if (it.id == id) LaterNotes.touched(was, f(it)) else it } }
        fun add(id: String, text: String) { items = items + LaterNotes.touched(null, LaterItem(id, text, clock, clock)) }
        fun shown() = items.filter { it.deleted == null }.associate { it.id to it.text }
    }
    var clock = 1_000_000L

    private fun v1(vararg items: LaterItem, extra: String = "") =
        "{\"v\":1,\"at\":1$extra,\"items\":" + JSONArray(items.map { cc.aaro.cosign.Later.itemJson(it) }) + "}"
    private fun Relay.v1() = held["marble/later"]?.content?.let(::JSONObject)

    @Test fun theSingleListMovesIntoPacksWithNothingLost() {
        val relay = Relay()
        val old = (0 until 40).map { LaterItem("n$it", "note $it", 1000L + it, 1000L + it, done = if (it % 3 == 0) 2000L else null) } +
            LaterItem("gone", "", 900, 1500, deleted = 1500)
        runBlocking { relay.net.write("marble/later", null, 5000, v1(*old.toTypedArray(), extra = ",\"theme\":\"blue\"")) }
        // An older Marble's phone had them; the new version starts with the same.
        val phone = Device(relay, cc.aaro.cosign.Later.fromJson(relay.v1().toString()))
        phone.pull(); clock += 200; phone.flush()
        assertTrue(relay.held.values.any { it.w != null })
        // A new device reads only packs and keys … and the old list too; drop the old list to show the packs alone hold it all.
        val packsOnly = Relay().also { r -> relay.held.filterKeys { it != "marble/later" }.forEach { (k, v) -> r.held[k] = v } }
        val tablet = Device(packsOnly); tablet.pull()
        assertEquals(old.filter { it.deleted == null }.associate { it.id to it.text }, tablet.shown())
        assertEquals(2000L, tablet.items.first { it.id == "n3" }.done)
        // The old list is still current, with what it had kept.
        assertEquals("blue", relay.v1()!!.optString("theme"))
        assertEquals(41, relay.v1()!!.getJSONArray("items").length())
    }

    @Test fun twoDevicesEditingAtOnceKeepBoth() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "milk"); phone.add("b", "bread"); phone.flush()
        val tablet = Device(relay); tablet.pull()
        clock += 10
        phone.edit("a") { it.copy(done = clock, updated = clock) }
        tablet.edit("a") { it.copy(text = "oat milk", updated = clock + 1) }
        tablet.add("c", "eggs")
        phone.flush(); tablet.flush(); phone.pull()
        val fresh = Device(relay); fresh.pull()
        assertEquals(mapOf("a" to "oat milk", "b" to "bread", "c" to "eggs"), fresh.shown())
        assertNotNull(fresh.items.first { it.id == "a" }.done)
        assertEquals(fresh.shown(), phone.shown())
    }

    @Test fun keysMadeOnTwoDevicesAtOnceEndUpAsOne() {
        val relay = Relay()
        val phone = Device(relay); val tablet = Device(relay)
        phone.add("p", "from the phone"); tablet.add("t", "from the tablet")
        // Neither saw the other's key: each makes one. Both are kept; notes end up under the oldest.
        phone.flush(); clock += 1
        val keysOfPhone = relay.held[Keys.D]!!
        relay.held.remove(Keys.D)
        tablet.flush()
        relay.held[Keys.D] = keysOfPhone.copy(at = clock - 5)
        tablet.flush(); phone.pull(); phone.flush(); tablet.pull(); tablet.flush()
        assertEquals(2, Keys.parse(relay.held[Keys.D]!!.content)!!.keys.size)
        assertEquals(phone.sync.keys!!.primary, tablet.sync.keys!!.primary)
        val fresh = Device(relay); fresh.pull()
        assertEquals(mapOf("p" to "from the phone", "t" to "from the tablet"), fresh.shown())
    }

    @Test fun anOlderMarbleCantShortenALongNote() {
        val relay = Relay()
        val phone = Device(relay); val long = "x".repeat(800); phone.add("l", long); phone.add("s", "short"); phone.flush()
        // The old list carries it cut, marked long.
        val inV1 = relay.v1()!!.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "l" } }
        assertEquals(500, inV1.getString("text").length); assertTrue(inV1.getBoolean("long"))
        // An older Marble ticks it done and writes the list back: cut text, and it drops the field times.
        clock += 100
        val items = cc.aaro.cosign.Later.fromJson(relay.v1().toString()).map { if (it.id == "l") it.copy(done = clock, updated = clock, extra = it.extra - "f" - "long") else it }
        runBlocking { relay.net.write("marble/later", null, clock, v1(*items.toTypedArray())) }
        phone.pull()
        assertEquals(long, phone.items.first { it.id == "l" }.text)
        assertEquals(clock, phone.items.first { it.id == "l" }.done)
    }

    @Test fun anOlderMarblesEditStillCounts() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "milk"); phone.flush()
        clock += 100
        val items = cc.aaro.cosign.Later.fromJson(relay.v1().toString()).map { it.copy(text = "milk and honey", updated = clock) }
        runBlocking { relay.net.write("marble/later", null, clock, v1(*items.toTypedArray())) }
        val tablet = Device(relay); tablet.pull(); tablet.flush()
        val packsOnly = Relay().also { r -> relay.held.filterKeys { it != "marble/later" }.forEach { (k, v) -> r.held[k] = v } }
        assertEquals(mapOf("a" to "milk and honey"), Device(packsOnly).also { it.pull() }.shown())
    }

    @Test fun aBigListSaysItMoved() {
        val relay = Relay()
        val phone = Device(relay)
        (0 until 200).forEach { phone.add("n$it", "a note long enough to fill the old list quickly, number $it") }
        phone.flush()
        assertEquals(2, relay.v1()!!.getInt("v")); assertEquals("notes", relay.v1()!!.getString("moved"))
        assertEquals(200, Device(relay).also { it.pull() }.shown().size)
    }

    @Test fun removedOnOneDeviceGoneOnTheOther() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "keep"); phone.add("b", "drop"); phone.flush()
        val tablet = Device(relay); tablet.pull()
        clock += 10
        phone.edit("b") { it.copy(text = "", deleted = clock, updated = clock) }; phone.flush()
        tablet.pull()
        assertEquals(mapOf("a" to "keep"), tablet.shown())
    }

    @Test fun offlineKeepsChangesForLater() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "one"); phone.flush()
        relay.down = true
        phone.add("b", "two")
        phone.flush()
        assertTrue(phone.sync.pending)
        relay.down = false; phone.flush()
        assertFalse(phone.sync.pending)
        assertEquals(mapOf("a" to "one", "b" to "two"), Device(relay).also { it.pull() }.shown())
    }

    @Test fun aRestartedDeviceKeepsItsPlace() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "one"); phone.flush()
        val again = Device(relay, phone.items, phone.store)
        assertFalse(again.sync.pending)
        again.add("b", "two"); again.flush()
        assertEquals(mapOf("a" to "one", "b" to "two"), Device(relay).also { it.pull() }.shown())
    }

    private fun big(d: Device, n: Int = 600) = (0 until n).forEach { d.add("n$it", "a long note, number $it: " + "words ".repeat(60)) }

    @Test fun anImportGoesAsOneFile() {
        val relay = Relay()
        val mac = Device(relay); big(mac); mac.flush()
        assertEquals(1, relay.blobs.size)
        assertTrue("no packs, only the file", relay.held.values.none { it.w != null })
        val phone = Device(relay); phone.pull()
        assertEquals(600, phone.shown().size)
        // A later edit goes in a pack and wins over the file.
        clock += 100
        phone.edit("n5") { it.copy(text = "changed", updated = clock) }
        phone.edit("n6") { it.copy(text = "", deleted = clock, updated = clock) }
        phone.flush()
        assertTrue("only the two edited packs (and their splits)", relay.held.values.count { it.w != null } in 1..8)
        val fresh = Device(relay); fresh.pull()
        assertEquals("changed", fresh.shown()["n5"]); assertNull(fresh.shown()["n6"]); assertEquals(599, fresh.shown().size)
        // The phone took the file without sending everything again.
        assertFalse(phone.sync.pending)
    }

    @Test fun withoutBlossomAnImportGoesInPacks() {
        val relay = Relay().also { it.blossom = false }
        val mac = Device(relay); big(mac); mac.flush()
        assertTrue(relay.blobs.isEmpty())
        assertEquals(600, Device(relay).also { it.pull() }.shown().size)
    }

    @Test fun aSecondImportKeepsTheFirst() {
        val relay = Relay()
        val mac = Device(relay); big(mac); mac.flush()
        val phone = Device(relay); phone.pull()
        (0 until 600).forEach { phone.add("m$it", "another long note, $it: " + "more ".repeat(60)) }
        clock += 100; phone.flush()
        assertEquals(2, relay.blobs.size)
        assertEquals(1200, Device(relay).also { it.pull() }.shown().size)
        // The first file goes 30 days later.
        clock += 31 * 86_400L; phone.add("x", "x"); phone.flush()
        assertEquals(1, relay.blobs.size)
    }

    /** [newcomer] pulls for the first time while [open] answers. */
    private fun catchUp(open: Device, newcomer: Device) = runBlocking {
        val answering = launch { open.sync.serve { open.items } }
        kotlinx.coroutines.yield()
        newcomer.items = newcomer.sync.pull(newcomer.items, full = true)!!
        answering.cancel()
    }

    @Test fun aNewDeviceCatchesUpFromAnOpenOne() {
        val relay = Relay()
        val phone = Device(relay); (0 until 50).forEach { phone.add("n$it", "note $it") }; phone.flush()
        phone.add("unsent", "not on the relays yet")
        val tablet = Device(relay)
        catchUp(phone, tablet)
        assertEquals(phone.shown(), tablet.shown())
        assertEquals("no paging", 0, relay.pageReads)
        assertFalse(tablet.sync.syncing)
        // Later pulls carry on as usual.
        clock += 10; phone.edit("n1") { it.copy(text = "edited", updated = clock) }; phone.flush()
        tablet.pull(); assertEquals("edited", tablet.shown()["n1"])
    }

    @Test fun withNoDeviceOpenItPages() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "one"); phone.flush()
        val tablet = Device(relay); tablet.pull()
        assertEquals(mapOf("a" to "one"), tablet.shown())
        assertTrue(relay.pageReads > 0)
    }

    @Test fun aLostMessageFallsBackToPaging() {
        val relay = Relay()
        val phone = Device(relay); (0 until 50).forEach { phone.add("n$it", "note $it") }; phone.flush()
        relay.drop = 4
        val tablet = Device(relay)
        catchUp(phone, tablet)
        assertEquals(phone.shown(), tablet.shown())
        assertTrue(relay.pageReads > 0)
    }

    @Test fun catchingUpBringsTheImportFileToo() {
        val relay = Relay()
        val mac = Device(relay); big(mac); mac.flush()
        val phone = Device(relay); phone.pull()
        val tablet = Device(relay)
        catchUp(phone, tablet)
        assertEquals(600, tablet.shown().size)
        assertEquals(0, relay.pageReads - 1)
    }

    /** 0.12.28: the old list is never written over a copy that isn't v1 (here, "moved"), nor when it couldn't be read. */
    @Test fun theOldListIsntWrittenOverALaterVersion() {
        val relay = Relay()
        runBlocking { relay.net.write("marble/later", null, 5000, """{"v":2,"moved":"notes"}""") }
        val phone = Device(relay); phone.add("a", "milk"); phone.flush()
        assertEquals(2, relay.v1()!!.optInt("v"))
        relay.held.remove("marble/later"); relay.unread = { _, v -> "marble/later" in v }
        phone.add("b", "bread"); phone.flush()
        assertNull(relay.held["marble/later"])
    }

    /** 0.12.28: new keys most write relays didn't take aren't used (packs under them would be lost to other devices). */
    @Test fun keysThatDidntTakeArentUsed() {
        val relay = Relay(); relay.untaken = { it == Keys.D }
        val phone = Device(relay); phone.add("a", "milk")
        assertNull(phone.flush())
        assertTrue(relay.held.values.none { it.w != null })
        relay.untaken = { false }
        assertNotNull(phone.flush())
        assertTrue(relay.held.values.any { it.w != null })
    }

    /** 0.12.28: a pull whose pages didn't all come doesn't move "pulled up to", so the next reads from as far back. */
    @Test fun aPullWithAMissingPageReadsAgainFromAsFarBack() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "milk"); phone.flush()
        val tablet = Device(relay); tablet.pull()
        val pulled = tablet.store.m["notesPulled"]
        clock += 5000
        relay.unread = { tag, _ -> tag == "w" }
        tablet.pull(full = false)
        assertEquals(pulled, tablet.store.m["notesPulled"])
    }


    /** 0.12.28 gate (b): a restart doesn't send again what was sent, pulled or put in the base file. */
    @Test fun aBigNotebookUploadsOnceAcrossRestarts() {
        val relay = Relay()
        val mac = Device(relay); big(mac); mac.flush()
        assertEquals(1, relay.blobs.size)
        var items = mac.items
        repeat(3) { clock += 100; val again = Device(relay, items, mac.store); again.pull(full = false); again.flush(); items = again.items }
        assertEquals(1, relay.blobs.size)
    }

    @Test fun anUpgradeResendsNothing() {
        val relay = Relay()
        val phone = Device(relay); (0 until 20).forEach { phone.add("n$it", "note $it") }; phone.flush()
        // A state saved by 0.12.27: no "sent" record.
        val saved = JSONObject(phone.store.m["notesVault"]!!)
        saved.put("state", JSONObject(saved.getString("state")).apply { remove("sent") }.toString())
        phone.store.m["notesVault"] = saved.toString()
        val before = relay.packWrites
        var items = phone.items
        repeat(2) { clock += 100; val again = Device(relay, items, phone.store); again.flush(); items = again.items }
        assertEquals(before, relay.packWrites)
    }

    @Test fun aPulledNoteIsntResent() {
        val relay = Relay()
        val phone = Device(relay); phone.add("a", "milk"); phone.add("b", "bread"); phone.flush()
        val tablet = Device(relay); tablet.pull(); tablet.flush()
        val before = relay.packWrites
        clock += 100
        val again = Device(relay, tablet.items, tablet.store); again.flush()
        assertEquals(before, relay.packWrites)
    }
}
