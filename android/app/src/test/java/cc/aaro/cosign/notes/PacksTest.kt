package cc.aaro.cosign.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notes sync v3's shared tests (§12) that don't need relays, on a fake relay that keeps the newest copy per `d`. */
class PacksTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".unhex()
    private fun layout() = Layout("k1", key)

    /** A replaceable-event relay: per `d`, the newest copy (a tie: the lower content wins, as relays break ties by id). */
    class Relay {
        val held = HashMap<String, Vault.Incoming>()
        fun read(d: String) = listOfNotNull(held[d])
        fun write(d: String, at: Long, content: String) {
            val have = held[d]
            if (have == null || at > have.createdAt || (at == have.createdAt && content < (have.content ?: ""))) held[d] = Vault.Incoming(d, at, content)
        }
    }

    private fun note(id: String, text: String, t: Long = 1000) = Note(id, text, t, t, f = mapOf("text" to t))
    private fun flush(v: Vault, r: Relay, now: Long, device: String = "the tablet") = Cycle.flush(v, now, r::read, r::write, device)
    private fun pullAll(v: Vault, r: Relay) = v.apply(r.held.values.toList())

    /** Two ids that land in the same pack, for the collision tests. */
    private fun twoInOnePack(l: Layout): Pair<String, String> {
        val ids = (0 until 200).map { "note-$it" }
        return ids.groupBy { l.owner(it, emptySet()) }.values.first { it.size >= 2 }.let { it[0] to it[1] }
    }

    @Test fun layoutIsStable() {
        val l = layout()
        assertEquals(256, l.slot("a").length)
        assertEquals(l.slot("a").take(4), l.owner("a", emptySet()))
        assertEquals(16, l.live(emptySet()).size)
        assertEquals(32, l.d("0101").length); assertEquals(16, l.w.length)
        // A split pack's notes move one bit down; the rest stay.
        val p = l.owner("a", emptySet())
        assertEquals(l.slot("a").take(5), l.owner("a", setOf(p)))
        assertEquals(17, l.live(setOf(p)).size)
    }

    @Test fun twoDevicesEditDifferentNotesInOnePackWithinASecond() {
        val l = layout(); val (x, y) = twoInOnePack(l)
        val relay = Relay()
        val phone = Vault(l); val tablet = Vault(l)
        phone.put(note(x, "from the phone")); tablet.put(note(y, "from the tablet"))
        // Both write the same pack in the same second; read-back makes the loser merge and write again.
        flush(phone, relay, 5000); flush(tablet, relay, 5000); flush(phone, relay, 5000)
        val fresh = Vault(l); pullAll(fresh, relay)
        assertEquals(setOf(x, y), fresh.notes.keys)
    }

    @Test fun aTickAndAnEditOfOneNoteAreBothKept() {
        val l = layout(); val relay = Relay()
        val a = Vault(l); val b = Vault(l)
        a.put(note("n", "milk")); flush(a, relay, 1000); pullAll(b, relay)
        val orig = b.notes["n"]!!
        a.put(orig.copy(done = 2000, updated = 2000, f = orig.f + ("done" to 2000L)))
        b.put(orig.copy(text = "oat milk", updated = 2001, base = Note.hashOf("milk"), f = orig.f + ("text" to 2001L)))
        flush(a, relay, 3000); flush(b, relay, 3001); flush(a, relay, 3002)
        val fresh = Vault(l); pullAll(fresh, relay)
        assertEquals("oat milk", fresh.notes["n"]!!.text); assertEquals(2000L, fresh.notes["n"]!!.done)
        assertEquals(1, fresh.notes.size)
    }

    @Test fun twoTextEditsFromTheSameStartKeepACopy() {
        val l = layout(); val relay = Relay()
        val a = Vault(l); val b = Vault(l)
        a.put(note("n", "milk")); flush(a, relay, 1000); pullAll(b, relay)
        val orig = b.notes["n"]!!
        a.put(orig.copy(text = "oat milk", updated = 2000, base = Note.hashOf("milk"), f = mapOf("text" to 2000L)))
        b.put(orig.copy(text = "almond milk", updated = 2001, base = Note.hashOf("milk"), f = mapOf("text" to 2001L)))
        flush(a, relay, 3000); flush(b, relay, 3001); flush(a, relay, 3002); flush(b, relay, 3003)
        val fresh = Vault(l); pullAll(fresh, relay)
        assertEquals(setOf("almond milk", "oat milk"), fresh.notes.values.map { it.text }.toSet())
        assertEquals(2, fresh.notes.size)
        assertNotNull(fresh.notes.values.firstOrNull { it.from != null })
        // Both devices made the same copy, once.
        pullAll(a, relay); pullAll(b, relay)
        assertEquals(a.notes.keys, b.notes.keys)
    }

    @Test fun aFullPackSplitsWithNothingLostOrDoubled() {
        val l = layout(); val relay = Relay()
        val v = Vault(l)
        // Enough long notes in one pack to pass the limit.
        val ids = (0 until 4000).map { "n$it" }.filter { l.owner(it, emptySet()) == "0000" }.take(60)
        ids.forEach { v.put(note(it, "x".repeat(400))) }
        flush(v, relay, 1000)
        assertTrue("0000" in v.retired)
        val fresh = Vault(l); pullAll(fresh, relay)
        assertTrue("0000" in fresh.retired)
        assertEquals(ids.toSet(), fresh.notes.keys)
        // A device that knew only the old pack reads all of it.
        val old = Vault(l); old.apply(listOf(relay.held[l.d("0000")]!!)); old.apply(relay.held.values.toList())
        assertEquals(ids.toSet(), old.notes.keys)
        relay.held.values.forEach { assertTrue((Pack.parse(it.content!!)!!.bytes) <= Layout.LIMIT) }
    }

    @Test fun junkFromAnotherAppChangesNothingAndHalts() {
        val l = layout(); val relay = Relay()
        val v = Vault(l)
        (0 until 60).forEach { v.put(note("n$it", "keep $it")) }
        flush(v, relay, 1000)
        val before = v.notes.toMap()
        val junk = l.live(emptySet()).map { Vault.Incoming(l.d(it), 9999, "{\"v\":3,\"kid\":\"k1\",\"prefix\":\"$it\",\"items\":[],\"markers\":[" +
            (0 until 4000).map { i -> "n$i" }.filter { id -> l.slot(id).startsWith(it) }.joinToString(",") { id -> "{\"id\":\"$id\",\"deleted\":9999}" } + "]}") }
        val r = v.apply(junk)
        assertTrue(r.halted); assertEquals(before, v.notes.toMap())
    }

    @Test fun anOlderPackIsRefusedAndOursGoesOutAgain() {
        val l = layout(); val relay = Relay()
        val v = Vault(l)
        v.put(note("n", "new text", 2000)); flush(v, relay, 3000)
        val d = l.d(l.owner("n", emptySet()))
        val old = Vault.Incoming(d, 1000, Pack(l.owner("n", emptySet()), "k1", listOf(note("n", "old text", 500))).toJson())
        val r = v.apply(listOf(old))
        assertEquals("new text", v.notes["n"]!!.text); assertTrue(l.owner("n", emptySet()) in r.republish)
    }

    @Test fun aPackInTheWrongPlaceIsRefused() {
        val l = layout(); val v = Vault(l)
        val wrong = l.live(emptySet()).first { it != l.owner("n", emptySet()) }
        v.apply(listOf(Vault.Incoming(l.d(wrong), 1000, Pack(wrong, "k1", listOf(note("n", "sneaky"))).toJson())))
        assertNull(v.notes["n"])
        // And a pack whose prefix doesn't name its d.
        v.apply(listOf(Vault.Incoming(l.d("0000"), 1000, Pack("0001", "k1", emptyList()).toJson())))
        assertTrue(v.notes.isEmpty())
    }

    @Test fun removalsStayRemovedForGood() {
        val l = layout(); val relay = Relay()
        val a = Vault(l)
        a.put(note("n", "gone soon")); flush(a, relay, 1000)
        // A device that was away (120 days) still has the note.
        val away = Vault(l); pullAll(away, relay)
        a.put(a.notes["n"]!!.copy(deleted = 2000, updated = 2000)); flush(a, relay, 2000)
        // It comes back, edits it, and syncs: the removal holds.
        away.put(away.notes["n"]!!.copy(text = "edited later", updated = 2000 + 120 * 86_400L, f = mapOf("text" to 2000 + 120 * 86_400L)))
        flush(away, relay, 3000 + 120 * 86_400L)
        val fresh = Vault(l); pullAll(fresh, relay)
        assertNull(fresh.notes["n"]); assertNotNull(fresh.markers["n"])
    }

    @Test fun unknownFieldsSurvive() {
        val l = layout(); val relay = Relay()
        val v = Vault(l)
        v.put(note("n", "hello").copy(unknown = mapOf("colour" to "blue")))
        flush(v, relay, 1000)
        val d = l.d(l.owner("n", emptySet()))
        val content = JSONObject(relay.held[d]!!.content!!).put("future", "kept").toString()
        relay.held[d] = Vault.Incoming(d, 2000, content)
        val fresh = Vault(l); fresh.apply(relay.held.values.toList())
        assertEquals("blue", fresh.notes["n"]!!.unknown["colour"])
        assertEquals("kept", Pack.parse(content)!!.unknown["future"])
    }

    @Test fun checkpoints() {
        val l = layout(); val relay = Relay()
        val v = Vault(l)
        v.put(note("n", "one")); flush(v, relay, 1000)
        val c = v.nextCheckpoint()
        val fresh = Vault(l)
        assertTrue(fresh.takeCheckpoint(c)); assertFalse(fresh.complete())
        pullAll(fresh, relay); assertTrue(fresh.complete())
        // An older checkpoint is refused.
        assertFalse(fresh.takeCheckpoint(c.copy(seq = 0)))
    }
}
