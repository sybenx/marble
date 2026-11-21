package cc.aaro.cosign.net

import cc.aaro.cosign.PrivateRelays
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Secp
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.toHex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a local Chronicle with NIP-50 search (run by hand: CHRONICLE_URL=ws://127.0.0.1:port, its OWNER_PUBKEY the
 * key of secret 3). Skipped otherwise. Marble's own Fetch and login: the owner finds their notes; nobody else does.
 */
class ChronicleLiveTest {
    private val url = System.getenv("CHRONICLE_URL")
    private fun secret(n: Int) = ByteArray(32).also { it[31] = n.toByte() }
    private val owner = secret(3)
    private val stranger = secret(1)
    private fun pk(s: ByteArray) = Secp.publicKey(s).toHex()
    private fun signer(s: ByteArray): (Event) -> Event = { e -> Event.sign(e.pubkey, e.createdAt, e.kind, e.tags, e.content, s, SystemEnv) }
    private fun as_(s: ByteArray?) {
        RelayAuth.chosen = { listOf(url!!) }
        RelayAuth.pubkey = s?.let(::pk)
        RelayAuth.signer = s?.let(::signer)
    }
    private fun search(q: String) = runBlocking { Fetch.query(listOf(url!!), PrivateRelays.searchFilter(q, null), 6000) }

    @Test fun ownerOnlySearch() {
        assumeTrue(url != null)
        val word = "marblearchive" + System.currentTimeMillis()
        as_(owner)
        val notes = (1..3).map { i -> signer(owner)(Event(pk(owner), System.currentTimeMillis() / 1000 - i, 1, emptyList(), "seed $i $word")) }
        notes.forEach { n -> assertEquals(setOf(url), runBlocking { Fetch.publishOk(listOf(url!!), n) }) }
        Thread.sleep(1500)

        as_(null)
        assertTrue("logged out sees nothing", search(word).isEmpty())
        as_(stranger)
        assertTrue("another key sees nothing", search(word).isEmpty())
        as_(owner)
        val got = search(word)
        assertEquals(notes.map { it.id }.toSet(), got.map { it.id }.toSet())
        // Not on the chosen list: no login, so nothing, even with the owner's key at hand.
        RelayAuth.chosen = { emptyList() }
        assertTrue("unchosen relay gets no login", search(word).isEmpty())
    }
}
