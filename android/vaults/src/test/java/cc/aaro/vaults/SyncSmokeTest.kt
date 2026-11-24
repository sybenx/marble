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
