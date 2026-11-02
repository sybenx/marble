package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
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
        private var n = 0
        val net = object : NotesNet {
            override suspend fun read(tag: String, values: List<String>, until: Long?, since: Long?, limit: Int): List<Stored>? {
                if (down) return null
                reads++
                return held.values.filter { if (tag == "d") it.d in values else it.w in values }
                    .filter { e -> (until == null || e.at <= until) && (since == null || e.at >= since) }
                    .sortedByDescending { it.at }.take(limit).map { Stored(it.id, it.d, it.at, it.content) }
            }
            override suspend fun write(d: String, w: String?, createdAt: Long, content: String): Boolean {
                if (down) return false
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
        val sync = NotesSync(relay.net, store, { clock }, items)
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
}
