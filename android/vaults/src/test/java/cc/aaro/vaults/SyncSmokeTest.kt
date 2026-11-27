package cc.aaro.vaults

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release smoke test for notes sync: a note written on one device is read on another and on a fresh install, across a
 * new key generation, with the signer failing once while the new generation's keyring entry arrives (as a busy box
 * answering `slow_down`, or one out of reach, does). Before 0.16.21 that one failure was remembered for good, and the
 * device never read the other devices' notes again.
 */
class SyncSmokeTest {
    private val servers = listOf("https://s1.example", "https://s2.example")
    private val relays = listOf("wss://a", "wss://b")
    private fun put(v: Vault, id: String, text: String, at: Long) =
        v.enqueue(listOf(Put.make(entryOf(id), emptyList(), note(id, text, at), JSONObject().put("text", text), false).json))
    private fun texts(v: Vault) = v.state().entries.values.filter { !it.deleted }.mapNotNull { it.shown.data?.optString("text") }.sorted()
    private fun join(vs: Vaults) = vs.join(vs.findOwned("notes", vs.keyrings()!!)!!).also { assertTrue(it.catchUp()) }

    @Test fun aNoteWrittenOnOneDeviceIsReadOnTheOthers() {
        val w = World()
        val (aVs, _) = w.device("Pixel")
        val a = aVs.create("notes", "Notes", relays, servers)
        put(a, "a1", "phone 1", w.clock.t); assertTrue(a.sync())
        val (bVs, bAcc) = w.device("Tablet")
        val b = join(bVs)
        put(b, "b1", "tablet 1", w.clock.t); assertTrue(b.sync()); assertTrue(a.catchUp())
        assertEquals(listOf("phone 1", "tablet 1"), texts(a))
        assertEquals(listOf("phone 1", "tablet 1"), texts(b))

        // A web sign-in ends: the phone starts a new generation and writes under it.
        w.clock.t += 100; w.box.revokedGrants += "ab".repeat(32)
        put(a, "a2", "phone 2", w.clock.t); assertTrue(a.sync())
        // The tablet's signer fails once, just as it reads the keyring entry holding the new generation.
        bAcc.failOpens = 1; b.catchUp()
        // Later, with the signer answering again, it catches up and reads the phone's note.
        w.clock.t += 100; assertTrue(b.catchUp())
        assertEquals(listOf("phone 1", "phone 2", "tablet 1"), texts(b))
        put(b, "b2", "tablet 2", w.clock.t); assertTrue(b.sync()); assertTrue(a.catchUp())
        assertEquals(listOf("phone 1", "phone 2", "tablet 1", "tablet 2"), texts(a))
        assertEquals("one generation, not a fork", a.current()!!.id, b.current()!!.id)

        // A fresh install reads everything.
        val (cVs, _) = w.device("Fresh")
        assertEquals(listOf("phone 1", "phone 2", "tablet 1", "tablet 2"), texts(join(cVs)))
    }

    /**
     * An account that has removed many devices (each web sign-in that ends is one): the phone's last commit under the old
     * epoch carries every revocation it holds, which ran past 12 KiB at about 16. Before 0.16.24 that commit couldn't be
     * made, sync threw at the same place every time, and nothing written on the phone ever reached another device.
     */
    @Test fun aPhoneOnAnAccountThatRemovedManyDevicesStillSendsItsNotes() {
        val w = World()
        val (aVs, _) = w.device("Pixel")
        val a = aVs.create("notes", "Notes", relays, servers)
        put(a, "a1", "phone 1", w.clock.t); assertTrue(a.sync())
        val (bVs, _) = w.device("Tablet")
        val b = join(bVs); assertTrue(b.sync()); assertTrue(a.sync())
        repeat(25) { i ->
            val (wVs, wAcc) = w.device("Web $i")
            assertTrue(join(wVs).sync()); assertTrue(a.sync())
            w.clock.t += 10; w.box.block(wAcc.me); w.box.cycle()
            assertTrue("phone sync after removal ${i + 1}", a.sync())
            // Moved to the new generation with nothing to write: a sync with nothing queued writes no second rekey.
            val commits = a.debug().getInt("commits"); assertTrue(a.sync()); assertEquals(commits, a.debug().getInt("commits"))
        }
        w.clock.t += 10
        put(a, "a2", "phone 2", w.clock.t)
        assertTrue(a.sync()); assertEquals(0, a.pending)
        assertTrue(b.catchUp())
        assertEquals(listOf("phone 1", "phone 2"), texts(b))
        val (cVs, _) = w.device("Fresh")
        assertEquals(listOf("phone 1", "phone 2"), texts(join(cVs)))
    }

    /**
     * The keyring entry is published again within the same second (a new generation right after another copy): each
     * copy must be later than the last, or a relay keeping the lower id of a tie could drop the new generation, and the
     * other devices could never read what the phone writes under it.
     */
    @Test fun aNewGenerationInTheSameSecondStillReachesTheRelays() {
        val w = World()
        val (aVs, _) = w.device("Pixel")
        val a = aVs.create("notes", "Notes", relays, servers)
        put(a, "a1", "phone 1", w.clock.t); assertTrue(a.sync())
        val (bVs, _) = w.device("Tablet")
        val b = join(bVs)
        var last = 0L
        repeat(5) { i ->
            val g = a.startGeneration(Gen.because(emptyList(), emptyList(), listOf("%064x".format(i + 1))))!!
            val copies = w.relays.events.values.filter { it.kind == Kinds.KEYRING }
            val mine = copies.filter { c -> aVs.keyrings()!!.any { it.first.id == c.id && it.second.arrOr("gens").objs().any { o -> o.optString("id") == g.id } } }
            assertTrue("generation ${i + 1} is on the relays", mine.isNotEmpty())
            assertTrue(mine.maxOf { it.createdAt } > last); last = mine.maxOf { it.createdAt }
        }
        put(a, "a2", "phone 2", w.clock.t); assertTrue(a.sync())
        assertTrue(b.catchUp())
        assertEquals(listOf("phone 1", "phone 2"), texts(b))
    }

    /** A commit another engine made past 12 KiB (Marble Web's rekey on such an account) is still read; this one makes none. */
    @Test fun aLargeCommitMadeElsewhereIsReadButNeverMadeHere() {
        val dvk = Keys.newSecret()
        val big = List(40) { JSONObject().put("op", "sync-request").put("pad", "x".repeat(500)) }
        val c = Commit.make(dvk, "ab".repeat(32), 0, null, "cd".repeat(16), 1, big.take(20))
        assertTrue(c.c.length <= Commit.MAX)
        try { Commit.make(dvk, "ab".repeat(32), 0, null, "cd".repeat(16), 1, big); org.junit.Assert.fail("made a commit over 12 KiB") } catch (_: Malformed) {}
        // The same over-size commit signed by hand, as the JS engine does: read.
        val s = canon(jobj("genesis" to "ab".repeat(32), "dvk" to Keys.pub(dvk), "seq" to 0, "prev" to null, "kid" to "cd".repeat(16), "time" to 1, "ops" to org.json.JSONArray(big)))
        val sig = Keys.sign(th("nostr-vault/commit/1", cc.aaro.keykin.core.sha256(cc.aaro.keykin.core.utf8(s))), dvk)
        assertTrue(s.length > Commit.MAX)
        assertTrue(Commit.of(JSONObject().put("c", s).put("sig", sig)) != null)
    }

    @Test fun aSignerThatCantAnswerNeverMakesASecondNotesVault() {
        val w = World()
        val (aVs, _) = w.device("Pixel")
        aVs.create("notes", "Notes", relays, servers).also { put(it, "a1", "phone 1", w.clock.t); assertTrue(it.sync()) }
        val (bVs, bAcc) = w.device("Tablet")
        bAcc.failOpens = 1
        // Couldn't open the account's keyring entry: that isn't "no vault" (the app makes no new one while unopened > 0).
        assertEquals(0, bVs.keyrings()!!.size); assertEquals(1, bVs.unopened)
        val b = join(bVs)
        assertEquals(listOf("phone 1"), texts(b))
    }
}
