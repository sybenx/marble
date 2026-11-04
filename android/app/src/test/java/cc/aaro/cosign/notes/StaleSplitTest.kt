package cc.aaro.cosign.notes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.12.28 gate: a stale device's flush after another device split two levels deep reads every pack before writing it. */
class StaleSplitTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".unhex()
    private fun layout() = Layout("k1", key)
    private fun note(id: String, text: String, t: Long = 1000) = Note(id, text, t, t, f = mapOf("text" to t))

    @Test fun staleDeviceAfterTwoLevelSplit() {
        val l = layout(); val relay = PacksTest.Relay()
        val all = (0 until 20000).map { "n$it" }.filter { l.slot(it).startsWith("00000") }
        val bIds = all.take(60); val aId = all[60]
        val a = Vault(l); val b = Vault(l)
        // Both start with A's note there (A wrote it first; B pulled it).
        a.put(note(aId, "a's note"))
        runBlocking { Cycle.flush(a, 1000, { relay.read(it) }, { d, at, c -> relay.write(d, at, c) }) }
        b.apply(relay.held.values.toList())
        // B imports 60 long notes into the same region: splits 0000 -> 00000 -> deeper.
        bIds.forEach { b.put(note(it, "x".repeat(400), 2000)) }
        runBlocking { Cycle.flush(b, 2000, { relay.read(it) }, { d, at, c -> relay.write(d, at, c) }) }
        // A (never pulled since) edits its note.
        a.put(note(aId, "a's note, edited", 3000))
        val written = ArrayList<String>(); val read = ArrayList<String>()
        runBlocking { Cycle.flush(a, 3000, { read += it; relay.read(it) }, { d, at, c -> if (d !in read) written += d; relay.write(d, at, c) }) }
        val fresh = Vault(l); repeat(4) { fresh.apply(relay.held.values.toList()) }
        val missing = (bIds + aId).filter { it !in fresh.notes }
        assertTrue("written unread $written", written.isEmpty())
        assertEquals(0, missing.size)
        // And the stale device's edit isn't dropped (pre-existing (a)): it's on the relays.
        assertEquals("a's note, edited", fresh.notes[aId]?.text)
    }

    /** (e): a change taken in but never sent (the app stopped first) is sent after a restart. */
    @Test fun aChangeNotSentBeforeAStopIsSentAfter() {
        val l = layout(); val relay = PacksTest.Relay(); val v = Vault(l)
        v.put(note("n", "first")); runBlocking { Cycle.flush(v, 1000, { relay.read(it) }, { d, at, c -> relay.write(d, at, c) }) }
        val saved = v.state()
        // The note changes in Later; the app stops before the vault's state is saved again.
        val restored = Vault(l); restored.restore(saved, listOf(note("n", "changed", 2000)))
        assertTrue(restored.dirty.isNotEmpty())
        // Unchanged: nothing to send.
        val same = Vault(l); same.restore(saved, listOf(note("n", "first")))
        assertTrue(same.dirty.isEmpty())
    }
}
