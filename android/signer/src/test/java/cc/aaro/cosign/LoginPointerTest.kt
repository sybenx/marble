package cc.aaro.cosign

import cc.aaro.cosign.LoginPointer.Decision
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Secp
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Publish the login-page pointer after joining a box only when the account has none (Keykin SPEC 11.1). */
class LoginPointerTest {
    private val sk = SystemEnv.random(32)
    private val pk = Secp.publicKey(sk).toHex()
    private fun pointer(url: String?, at: Long = 100, by: ByteArray = sk) =
        Event.sign(Secp.publicKey(by).toHex(), at, 30078, listOf(listOf("d", "keykin/login")) + (url?.let { listOf(listOf("url", it)) } ?: emptyList()), "", by, SystemEnv)

    @Test fun onlyAnHttpsOriginIsALoginPage() {
        assertEquals("https://account.syb.lol", LoginPointer.origin("https://Account.syb.lol/"))
        assertEquals("https://box.example:8443", LoginPointer.origin("https://box.example:8443"))
        assertEquals("https://box.example", LoginPointer.origin("https://box.example:443"))
        assertNull(LoginPointer.origin("http://10.0.2.2:8790"))
        assertNull(LoginPointer.origin("https://box.example/keykin"))
        assertNull(LoginPointer.origin("https://box.example?x=1"))
        assertNull(LoginPointer.origin("not a url"))
        assertEquals("http://10.0.2.2:8790", LoginPointer.origin("http://10.0.2.2:8790", allowHttp = true))
    }

    @Test fun noPointerYetIsPublished() =
        assertEquals(Decision.Publish("https://account.syb.lol"), LoginPointer.decide("https://account.syb.lol", null, heard = true))

    @Test fun aPointerToAnotherBoxIsLeftAlone() {
        val d = LoginPointer.decide("https://second.example", pointer("https://account.syb.lol"), heard = true)
        assertEquals(Decision.Skip("names https://account.syb.lol"), d)
    }

    @Test fun theSamePointerIsNotPublishedAgain() =
        assertEquals(Decision.Skip("already names https://account.syb.lol"), LoginPointer.decide("https://account.syb.lol", pointer("https://account.syb.lol/"), heard = true))

    @Test fun aPointerRemovedOnPurposeStaysRemoved() =
        assertEquals(Decision.Skip("removed on purpose"), LoginPointer.decide("https://account.syb.lol", pointer(null), heard = true))

    @Test fun nothingIsPublishedWhenNoRelayAnsweredOrThereIsNoPage() {
        assertEquals(Decision.Skip("no relay answered"), LoginPointer.decide("https://account.syb.lol", null, heard = false))
        assertEquals(Decision.Skip("no login page"), LoginPointer.decide(null, null, heard = true))
    }

    @Test fun theNewestSignedPointerOfThisAccountWins() {
        val old = pointer("https://old.example", 100); val new = pointer("https://new.example", 200)
        val forged = pointer("https://evil.example", 300).copy(sig = "00".repeat(64))
        val someoneElse = pointer("https://other.example", 400, SystemEnv.random(32))
        assertEquals(new, LoginPointer.newest(listOf(old, new, forged, someoneElse), pk))
        assertNull(LoginPointer.newest(listOf(someoneElse), pk))
    }
}
