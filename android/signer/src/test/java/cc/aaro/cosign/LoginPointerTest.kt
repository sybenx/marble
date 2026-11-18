package cc.aaro.cosign

import cc.aaro.cosign.LoginPointer.Plan
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Secp
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.toHex
import cc.aaro.keykin.core.Texts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keeping the login-page pointer on the account's relays (Keykin SPEC 11.1). */
class LoginPointerTest {
    private val sk = SystemEnv.random(32)
    private val pk = Secp.publicKey(sk).toHex()
    private val page = "https://account.syb.lol"
    private val a = "wss://a.example"; private val b = "wss://b.example"
    private fun pointer(url: String?, at: Long = 100, by: ByteArray = sk) =
        Event.sign(Secp.publicKey(by).toHex(), at, 30078, listOf(listOf("d", "keykin/login")) + (url?.let { listOf(listOf("url", it)) } ?: emptyList()), "", by, SystemEnv)

    @Test fun onlyAnHttpsOriginIsALoginPage() {
        assertEquals(page, LoginPointer.origin("https://Account.syb.lol/"))
        assertEquals("https://box.example:8443", LoginPointer.origin("https://box.example:8443"))
        assertEquals("https://box.example", LoginPointer.origin("https://box.example:443"))
        assertNull(LoginPointer.origin("http://10.0.2.2:8790"))
        assertNull(LoginPointer.origin("https://box.example/keykin"))
        assertNull(LoginPointer.origin("https://box.example?x=1"))
        assertNull(LoginPointer.origin("not a url"))
        assertEquals("http://10.0.2.2:8790", LoginPointer.origin("http://10.0.2.2:8790", allowHttp = true))
    }

    @Test fun noPointerAnywhereIsSignedAnew() =
        assertEquals(Plan.Sign(page), LoginPointer.plan(page, pk, mapOf(a to emptyList(), b to emptyList()), listOf(a, b)))

    @Test fun aRelayThatLacksTheNewestGetsTheSameEventAgain() {
        val p = pointer(page)
        assertEquals(Plan.Resend(p, listOf(b)), LoginPointer.plan(page, pk, mapOf(a to listOf(p), b to emptyList()), listOf(a, b)))
        // A relay holding only an older one gets the newest too.
        val old = pointer(page, 50)
        assertEquals(Plan.Resend(p, listOf(b)), LoginPointer.plan(page, pk, mapOf(a to listOf(p), b to listOf(old)), listOf(a, b)))
        // A relay that didn't answer is left for next time.
        assertEquals(Plan.Resend(p, listOf(b)), LoginPointer.plan(page, pk, mapOf(a to listOf(p), b to emptyList()), listOf(a, b, "wss://silent.example")))
    }

    @Test fun withNoLoginPageKnownNothingIsSignedOrSentAround() {
        // Not a new one, and not the one already out there: it can't be checked against a page this phone was told of.
        val p = pointer("https://box.lookalike.example")
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to emptyList(), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to listOf(p), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, emptyMap(), listOf(a)))
    }

    @Test fun theLoginPageComesOnlyFromTheCodeNeverTheBoxAddress() {
        val k = "ab".repeat(32)
        // A code naming no login page gives none, even when its box address is a perfectly good https origin
        // (a lookalike domain proxying the real box would answer anything asked of it there).
        val bare = Texts.qr("""{"keykin":2,"addr":"https://box.lookalike.example","key":"$k","code":"c"}""")
        assertNull(LoginPointer.fromCode(bare.login))
        val named = Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c","loginPage":"https://Account.syb.lol/"}""")
        assertEquals(page, LoginPointer.fromCode(named.login))
        assertNull(LoginPointer.fromCode("https://account.syb.lol/login"))
        // A pointer naming the page the code named is still sent to a relay lacking it.
        val p = pointer(page)
        assertEquals(Plan.Resend(p, listOf(b)), LoginPointer.plan(LoginPointer.fromCode(named.login), pk, mapOf(a to listOf(p), b to emptyList()), listOf(a, b)))
    }

    @Test fun theSignerNeverProbesTheBoxForALoginPage() {
        // The guess (POST <box address>/api answering 403) is gone; the origin is only ever read from what a code named.
        val src = java.io.File("src/main/java/cc/aaro/cosign/Signer.kt").readText()
        org.junit.Assert.assertFalse(src.contains("isKeykinLogin"))
        org.junit.Assert.assertFalse(src.contains("LoginPointer.origin(h.addr"))
        org.junit.Assert.assertFalse(src.contains("/api\""))
    }

    @Test fun onEveryRelayAlreadyIsLeft() =
        assertEquals(Plan.Leave("on every write relay that answered"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer(page)), b to listOf(pointer(page + "/")))
            .let { m -> mapOf(a to m[a]!!, b to m[a]!!) }, listOf(a, b)))

    @Test fun aPointerRemovedOnPurposeIsNeverRecreated() {
        val gone = pointer(null, 200)
        assertEquals(Plan.Leave("removed on purpose"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer(page, 100)), b to listOf(gone)), listOf(a, b)))
    }

    @Test fun aPointerToAnotherPageIsNeverReplacedOrSentAround() =
        assertEquals(Plan.Leave("names https://other.example"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer("https://other.example")), b to emptyList()), listOf(a, b)))

    @Test fun noRelayAnsweredWaits() {
        assertEquals(Plan.Wait, LoginPointer.plan(page, pk, emptyMap(), listOf(a)))
    }

    @Test fun forgedAndOtherAccountsPointersDontCount() {
        val forged = pointer(page, 300).copy(sig = "00".repeat(64))
        val someoneElse = pointer("https://other.example", 400, SystemEnv.random(32))
        assertEquals(Plan.Sign(page), LoginPointer.plan(page, pk, mapOf(a to listOf(forged, someoneElse)), listOf(a)))
    }

    @Test fun theBoxCodeAndJoinTextCarryTheLoginPage() {
        val k = "ab".repeat(32)
        assertEquals(page, Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c","loginPage":"$page"}""").login)
        assertNull(Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c"}""").login)
        assertEquals(page, Texts.readJoin("""{"keykin":2,"join":{"addr":"https://box.example","boxKey":"$k","npub":"$k","loginPage":"$page"}}""").login)
    }
}
