package cc.aaro.vaults

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §18's tests that apply to notes, two or three owner devices in one in-memory world (relays, Blossom, a box). */
class EngineTest {
    private val servers = listOf("https://s1.example", "https://s2.example")
    private val relays = listOf("wss://a", "wss://b")

    private fun create(w: World): Pair<Vaults, Vault> {
        val (a, _) = w.device("Pixel")
        return a to a.create("notes", "Notes", relays, servers)
    }
    private fun join(w: World, name: String = "Mac"): Triple<Vaults, Vault, FakeAccount> {
        val (b, acc) = w.device(name)
        val p = b.findOwned("notes", b.keyrings()!!)!!
        val v = b.join(p); assertTrue(v.catchUp()); return Triple(b, v, acc)
    }
    private fun put(v: Vault, id: String, text: String, at: Long, supersedes: List<String> = emptyList(), deleted: Boolean = false): Put {
        val p = if (deleted) Put.make(entryOf(id), supersedes, JSONObject().put("kind", "note"), null, true)
            else Put.make(entryOf(id), supersedes, note(id, text, at), JSONObject().put("text", text), false)
        v.enqueue(listOf(p.json)); return p
    }
    private fun text(v: Vault, id: String) = v.state().entries[entryOf(id)]?.shown?.data?.optString("text")

    @Test fun twoDevicesSyncAndConflict() {
        val w = World(); val (_, a) = create(w)
        val p1 = put(a, "n1", "Buy milk", w.clock.t); assertTrue(a.sync())
        val (_, b, _) = join(w)
        assertEquals("Buy milk", text(b, "n1"))
        // Test 1: both edit the same version offline: two current versions on every device, the same one first.
        w.clock.t += 10
        val pa = put(a, "n1", "Buy oat milk", w.clock.t, listOf(p1.ver)); val pb = put(b, "n1", "Buy milk and eggs", w.clock.t, listOf(p1.ver))
        assertTrue(a.sync()); assertTrue(b.sync()); a.catchUp()
        val ea = a.state().entries[entryOf("n1")]!!; val eb = b.state().entries[entryOf("n1")]!!
        assertTrue(ea.conflict && eb.conflict)
        assertEquals(ea.current.map { it.ver }, eb.current.map { it.ver })
        assertEquals(listOf(pa.ver, pb.ver).sorted(), ea.current.map { it.ver })
        // One pick resolves it everywhere.
        put(a, "n1", "Buy oat milk and eggs", w.clock.t + 5, listOf(pa.ver, pb.ver)); assertTrue(a.sync()); b.catchUp()
        assertEquals("Buy oat milk and eggs", text(b, "n1")); assertFalse(b.state().entries[entryOf("n1")]!!.conflict)
    }

    @Test fun undoAndToggles() {
        // Test 2: done twice, rename A→B→A, Undo to an earlier version: one current version.
        val w = World(); val (_, a) = create(w)
        var last = put(a, "n", "A", w.clock.t)
        for (t in listOf("A", "B", "A")) last = put(a, "n", t, w.clock.t + 1, listOf(last.ver))
        assertTrue(a.sync())
        val e = a.state().entries[entryOf("n")]!!; assertEquals(1, e.current.size); assertEquals("A", e.shown.data!!.getString("text"))
    }

    @Test fun newDeviceGetsEverythingFromCheckpointAndSegments() {
        // Test 4: relays keep only the newest head per address; a new device still gets every version.
        val w = World(); val (_, a) = create(w)
        for (i in 0 until 60) { put(a, "n$i", "note $i " + "x".repeat(200), w.clock.t + i); assertTrue(a.sync()) }
        assertTrue(a.writeCheckpoint())
        for (i in 60 until 70) { put(a, "n$i", "note $i", w.clock.t + i); assertTrue(a.sync()) }
        val (_, b, _) = join(w)
        assertEquals(70, b.state().entries.size)
        assertEquals("note 69", text(b, "n69"))
    }

    @Test fun importAsOneBatch() {
        val w = World(); val (_, a) = create(w)
        a.enqueue((0 until 2000).map { Put.make(entryOf("i$it"), emptyList(), note("i$it", "imported $it", 1L + it), JSONObject().put("text", "imported $it"), false).json })
        assertTrue(a.sync())
        val (_, b, _) = join(w)
        assertEquals(2000, b.state().entries.size)
    }

    @Test fun removalStartsGenerationTheRemovedDeviceCantOpen() {
        // Test 9 (and the task's removal): B is blocked and the keys cycled; A revokes B and starts a new generation.
        val w = World(); val (aVs, a) = create(w)
        put(a, "n1", "one", w.clock.t); assertTrue(a.sync())
        val (bVs, b, bAcc) = join(w)
        put(b, "n2", "from B", w.clock.t + 1); assertTrue(b.sync()); assertTrue(b.writeCheckpoint())
        assertTrue(a.sync()); assertEquals("from B", text(a, "n2"))
        val genBefore = a.current()!!.id
        w.box.block(bAcc.me)
        a.sync()
        assertEquals("blocked: distrusted, not yet revoked", genBefore, a.current()!!.id)
        assertTrue(a.devices().any { !it.trusted })
        w.box.cycle()
        assertTrue(a.sync())
        val genAfter = a.current()!!
        assertNotEquals(genBefore, genAfter.id)
        assertTrue(genAfter.because.strs("roots").isNotEmpty() && genAfter.because.strs("members").contains(bAcc.me))
        put(a, "n3", "after removal", w.clock.t + 5); assertTrue(a.sync())
        // B's kept checkpoint keeps its data.
        assertEquals("from B", text(a, "n2"))
        // B can't open the new keyring seal: the box refuses it, so it never holds the new generation.
        b.catchUp()
        assertTrue(b.generations().none { it.id == genAfter.id })
        assertNull(text(b, "n3"))
        // B's later writes don't count.
        put(b, "n4", "thief", w.clock.t + 6); b.sync(); a.catchUp()
        assertNull(text(a, "n4"))
        // A second device of the owner follows the new generation.
        val (_, c, _) = join(w, "Tablet")
        println("A: " + a.debug() + " gens " + a.generations().map { it.id + "<" + it.prev + " " + it.because })
        println("C: " + c.debug() + " gens " + c.generations().map { it.id + "<" + it.prev })
        assertEquals(genAfter.id, c.current()!!.id)
        assertEquals("after removal", text(c, "n3"))
        assertTrue(aVs.storage.domain("http://127.0.0.1:7447") == "127.0.0.1:7447")
    }

    @Test fun test33StolenPhonePrepublishesAChain() {
        val w = World(); val (_, a) = create(w)
        assertTrue(a.sync())
        val (bVs, b, bAcc) = join(w)
        assertTrue(b.sync())
        // While still trusted, the thief pre-publishes a keyring copy with a 50-long chain claiming its own block.
        var prev = a.current()!!.id
        val chain = JSONArray()
        repeat(50) { val v = Keys.newSecret(); val g = Gen(Keys.genId(v, prev), v, prev, Gen.because(emptyList(), listOf(bAcc.me), emptyList())); chain.put(g.json()); prev = g.id }
        val payload = JSONObject().put("vault", a.id).put("genesis", a.genesisId).put("owner", w.box.npub).put("name", "Notes").put("profile", "notes").put("role", "owner")
            .put("gens", chain).put("relays", JSONArray(relays)).put("servers", JSONArray(servers)).put("d", "00".repeat(16))
        bVs.publishKeyring(payload)
        val before = a.current()!!.id
        assertTrue(a.sync())
        assertEquals("nobody writes under the thief's chain", before, a.current()!!.id)
        w.box.block(bAcc.me); w.box.cycle()
        assertTrue(a.sync())
        val g = a.current()!!
        assertNotEquals(before, g.id); assertEquals(before, g.prev)
        assertFalse(chain.objs().any { it.getString("id") == g.id })
        b.catchUp(); assertTrue(b.generations().none { it.id == g.id })
    }

    @Test fun test36StolenPhoneStartsAGenerationNamingItself() {
        val w = World(); val (aVs, a) = create(w)
        assertTrue(a.sync())
        val (bVs, b, bAcc) = join(w)
        assertTrue(b.sync())
        val before = a.current()!!.id
        // While trusted, it starts a generation naming its own root and member key, and publishes a rekey naming it.
        val bad = b.startGeneration(Gen.because(listOf(bVs.rootPub), listOf(bAcc.me), emptyList()))!!
        put(b, "x", "under the thief's generation", w.clock.t + 1); assertTrue(b.sync())
        assertTrue(a.sync())
        assertTrue("A holds it", a.generations().any { it.id == bad.id })
        assertEquals("no other device follows it", before, a.current()!!.id)
        w.box.block(bAcc.me); w.box.cycle()
        assertTrue(a.sync())
        val g = a.current()!!
        assertNotEquals(bad.id, g.id); assertEquals(before, g.prev)
        b.catchUp(); assertTrue(b.generations().none { it.id == g.id })
    }

    @Test fun blockedByMistakeThenUnblockedIsTrustedAgain() {
        // Test 37.
        val w = World(); val (_, a) = create(w); assertTrue(a.sync())
        val (_, b, bAcc) = join(w); assertTrue(b.sync())
        val before = a.current()!!.id
        w.box.block(bAcc.me); a.sync(); assertTrue(a.devices().any { !it.trusted })
        w.box.members[bAcc.me] = false; a.sync()
        assertTrue(a.devices().all { it.trusted }); assertEquals(before, a.current()!!.id)
    }

    @Test fun twoDevicesStartGenerationsForOneRemoval() {
        // Test 10: both kept, everyone reads both, both devices write under the lower id once they see each other's.
        val w = World(); val (_, a) = create(w); assertTrue(a.sync())
        val (_, b, _) = join(w, "Mac"); assertTrue(b.sync())
        val (_, c, cAcc) = join(w, "Old phone"); assertTrue(c.sync()); a.sync(); b.sync()
        w.box.block(cAcc.me); w.box.cycle()
        w.relays.down = false
        // Each starts its own before seeing the other's: A syncs, then B without A's keyring read in between is hard to
        // stage here, so B starts one too by hand for the same trigger.
        assertTrue(a.sync()); val ga = a.current()!!
        val gb = b.startGeneration(ga.because)!!
        assertTrue(b.sync()); assertTrue(a.sync()); assertTrue(b.sync())
        val low = listOf(ga.id, gb.id).min()
        assertEquals(low, a.current()!!.id); assertEquals(low, b.current()!!.id)
    }

    @Test fun plainKeyRemovalAndRelaysDown() {
        // Test 25 in part: a plain-key account (no Keykin): a revoked device's later writes don't count.
        val w = World(); val (a0, acc) = w.device("Pixel", keykin = false)
        val a = a0.create("notes", "Notes", relays, servers); assertTrue(a.sync())
        val b0 = Vaults(FakeAccount(w.box, acc.me, keykin = false), w.relays, w.blobs, MapStore(), w.clock, { relays }, "Mac")
        val b = b0.join(b0.findOwned("notes", b0.keyrings()!!)!!); assertTrue(b.sync())
        a.reportLost(b0.rootPub)
        assertTrue(a.current()!!.because.strs("roots").contains(b0.rootPub))
        put(b, "n", "after", w.clock.t + 3); b.sync(); a.catchUp(); assertNull(text(a, "n"))
        // Relays down: nothing published, the ops wait.
        w.relays.down = true
        put(a, "q", "queued", w.clock.t + 4); assertFalse(a.sync()); assertEquals(1, a.pending)
        w.relays.down = false; assertTrue(a.sync()); assertEquals(0, a.pending)
        assertNotNull(text(a, "q"))
    }
}
