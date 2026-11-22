package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The other side's key in an app's NIP-55 request (peerKey): hex in any case or an npub; anything else is the app's mistake. */
class PeerKeyTest {
    private val hex = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"

    @Test fun hexAndNpub() {
        assertEquals(hex, peerKey(hex))
        assertEquals(hex, peerKey(hex.uppercase()))
        assertEquals(hex, peerKey(cc.aaro.qrst.core.Nip19.npub(hex)))
    }

    @Test fun anythingElseIsNull() {
        assertNull(peerKey(""))
        assertNull(peerKey(hex.drop(2)))
        assertNull(peerKey("02$hex"))
        assertNull(peerKey(cc.aaro.qrst.core.Nip19.npub(hex).dropLast(1) + "q"))
        assertEquals("len=0 start=", peerShape(""))
    }
}
