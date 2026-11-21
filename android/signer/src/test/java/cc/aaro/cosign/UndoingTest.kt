package cc.aaro.cosign

import cc.aaro.cosign.core.Policy
import cc.aaro.cosign.core.Undoing
import cc.aaro.cosign.core.Undoing.Before
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Secp
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undo of a list (2026-11-21): Vector wrote its DM relays over Aaron's, and "Undo all" sent a deletion by id, which
 * most relays ignore for a list. Undo signs a new copy of the version before, dated after the change.
 */
class UndoingTest {
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val other = ByteArray(32) { (it + 9).toByte() }
    private fun signed(at: Long, vararg relays: String, by: ByteArray = key, kind: Int = 10050) =
        Event.sign(Secp.publicKey(by).toHex(), at, kind, relays.map { listOf("relay", it) }, "", by, SystemEnv)

    private val oct25 = signed(1_761_434_424, "wss://haven.aaro.cc/inbox", "wss://herbstmeister.com/inbox", "wss://relay.vantis.ninja", "wss://herbstmeister.com", "wss://bothy-relay.sybenx.workers.dev")
    private val vector = signed(1_763_689_497, "wss://jskitty.com/nostr", "wss://asia.vectorapp.io/nostr", "wss://nostr.computingcache.com", "wss://relay.ditto.pub")

    @Test fun theCopyIsDatedAfterWhatItReplaces() {
        // A phone whose clock is behind still dates the copy after the change, so relays take it.
        val at = Undoing.at(vector.createdAt - 3600, null, vector.createdAt, oct25.createdAt)
        assertTrue(at > vector.createdAt)
        assertEquals(1_800_000_000L, Undoing.at(1_800_000_000L, vector.createdAt, oct25.createdAt))
        assertEquals(vector.createdAt + 1, Undoing.at(0, vector.createdAt))
    }

    @Test fun theVersionBeforeIsFoundAmongWhatRelaysKeep() {
        val older = signed(1_700_000_000, "wss://old")
        val b = Undoing.before(listOf(vector, oct25, older, oct25), vector)
        assertEquals(Before.Found(oct25), b)
        // The copy keeps Oct 25's relays.
        assertEquals(oct25.tags, (b as Before.Found).version.tags)
    }

    @Test fun aNewerChangeStopsIt() {
        val fixed = signed(1_763_700_000, "wss://haven.aaro.cc/inbox")
        assertEquals(Before.ChangedSince(fixed), Undoing.before(listOf(oct25, vector, fixed), vector))
        // Unless it is one being undone with it (Undo all, later changes by the same app).
        assertEquals(Before.Found(oct25), Undoing.before(listOf(oct25, vector, fixed), vector, ours = setOf(fixed.id)))
    }

    @Test fun onlyYourOwnSignedVersionsOfTheSameListCount() {
        val theirs = signed(1_761_000_000, "wss://x", by = other)
        val forged = oct25.copy(tags = listOf(listOf("relay", "wss://evil")))
        val otherList = signed(1_761_000_000, "wss://y", kind = 10002)
        assertEquals(Before.NotFound, Undoing.before(listOf(theirs, forged, otherList, vector), vector))
    }

    @Test fun listsAreReplaceable() {
        for (k in listOf(0, 3, 10002, 10050, 10051, 10063, 30000, 30078)) assertTrue("$k", Undoing.replaceable(k))
        for (k in listOf(1, 5, 7, 1111, 20000, 22242, 40000)) assertFalse("$k", Undoing.replaceable(k))
    }

    @Test fun aChangeWithNoEarlierVersionSaysWhatItBecame() {
        assertEquals("DM relays: replaced with jskitty.com/nostr, asia.vectorapp.io/nostr, nostr.computingcache.com and relay.ditto.pub", Policy.replacedText(vector))
        assertEquals("DM relays: emptied", Policy.replacedText(signed(1, )))
        assertEquals("App data: replaced", Policy.replacedText(signed(1, kind = 30078)))
    }
}
