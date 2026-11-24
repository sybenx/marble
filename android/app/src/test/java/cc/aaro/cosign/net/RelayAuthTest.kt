package cc.aaro.cosign.net

import cc.aaro.cosign.net.RelayAuth.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayAuthTest {
    private val pk = "a".repeat(64)

    @Test fun allowsOnlyChosenRelays() {
        val chosen = listOf("wss://Relay.Example.com/", "wss://archive.me/chronicle")
        assertTrue(RelayAuth.allowed("wss://relay.example.com", chosen))
        assertTrue(RelayAuth.allowed("WSS://RELAY.EXAMPLE.COM/", chosen))
        assertTrue(RelayAuth.allowed("wss://archive.me/chronicle/", chosen))
        assertFalse(RelayAuth.allowed("wss://archive.me", chosen))
        assertFalse(RelayAuth.allowed("wss://evil.example.com", chosen))
        assertFalse(RelayAuth.allowed("wss://relay.example.com.evil.io", chosen))
        assertFalse(RelayAuth.allowed("https://relay.example.com", listOf("https://relay.example.com")))
        assertFalse(RelayAuth.allowed("wss://relay.example.com", emptyList()))
    }

    @Test fun loginEventShape() {
        val e = RelayAuth.loginEvent(pk, "wss://relay.example.com", "chal-1", 1_700_000_000)
        assertEquals(22242, e.kind)
        assertEquals(pk, e.pubkey)
        assertEquals("", e.content)
        assertEquals(1_700_000_000L, e.createdAt)
        assertEquals(listOf(listOf("relay", "wss://relay.example.com"), listOf("challenge", "chal-1")), e.tags)
        assertEquals(e.computeId(), e.id)
    }

    @Test fun needsAuth() {
        assertTrue(RelayAuth.needsAuth("auth-required: log in first"))
        assertFalse(RelayAuth.needsAuth("restricted: no"))
        assertFalse(RelayAuth.needsAuth(null))
    }

    @Test fun deniedRelayNeverSigns() {
        val s = RelayAuth.Session("wss://x", allowed = false)
        assertEquals(emptyList<Action>(), s.onChallenge("c"))
        assertEquals(listOf(Action.GiveUp("q1")), s.onAuthRequired("q1"))
    }

    @Test fun challengeSignsOnceThenRetriesOnce() {
        val s = RelayAuth.Session("wss://x", allowed = true)
        assertEquals(listOf(Action.SendAuth("c")), s.onChallenge("c"))
        assertEquals(emptyList<Action>(), s.onChallenge("c")) // same challenge: no second login
        s.authSent("auth1")
        assertEquals(emptyList<Action>(), s.onAuthRequired("q1")) // waits for the login's answer
        assertEquals(true to listOf<Action>(Action.Resend("q1")), s.onOk("auth1", true))
        // Refused again after logging in: given up, no loop.
        assertEquals(listOf(Action.GiveUp("q1")), s.onAuthRequired("q1"))
        assertEquals(listOf(Action.GiveUp("q1")), s.onAuthRequired("q1"))
    }

    @Test fun refusalBeforeChallengeWaitsForIt() {
        val s = RelayAuth.Session("wss://x", allowed = true)
        assertEquals(emptyList<Action>(), s.onAuthRequired("q1"))
        assertEquals(listOf(Action.SendAuth("c")), s.onChallenge("c"))
        s.authSent("a")
        assertEquals(true to listOf<Action>(Action.Resend("q1")), s.onOk("a", true))
    }

    @Test fun alreadyLoggedInResendsAtOnce() {
        val s = RelayAuth.Session("wss://x", allowed = true)
        s.onChallenge("c"); s.authSent("a"); s.onOk("a", true)
        assertEquals(listOf(Action.Resend("ev1")), s.onAuthRequired("ev1"))
    }

    @Test fun failedLoginGivesUp() {
        val s = RelayAuth.Session("wss://x", allowed = true)
        s.onChallenge("c"); s.authSent("a")
        s.onAuthRequired("q1")
        assertEquals(true to listOf<Action>(Action.GiveUp("q1")), s.onOk("a", false))
        assertEquals(listOf(Action.GiveUp("q2")), s.onAuthRequired("q2"))
        val unsigned = RelayAuth.Session("wss://x", allowed = true)
        unsigned.onChallenge("c"); unsigned.onAuthRequired("q1")
        assertEquals(listOf<Action>(Action.GiveUp("q1")), unsigned.authSent(null))
    }

    @Test fun otherOksAreNotOurs() {
        val s = RelayAuth.Session("wss://x", allowed = true)
        s.onChallenge("c"); s.authSent("a")
        assertEquals(false to emptyList<Action>(), s.onOk("someEvent", true))
    }
    /** A socket that only records what was sent on it. */
    private class Sock : okhttp3.WebSocket {
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun request() = okhttp3.Request.Builder().url("http://x").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: okio.ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }

    /**
     * Logins are signed one at a time, and with a box each is a round trip to it. Typing a search starts a query (and a
     * login) per pause; the ones typed past must not be signed, or the last query's login waits behind them all and
     * your archive times out with nothing (seen with a box ~1.6 s away: 0 posts at 8 s).
     */
    @Test fun aLoginForASocketThatEndedIsNotSigned() {
        val signed = java.util.Collections.synchronizedList(ArrayList<String>())
        val slowBox = CountDownLatch(1)
        val was = Triple(RelayAuth.signer, RelayAuth.pubkey, RelayAuth.chosen)
        try {
            RelayAuth.pubkey = pk
            RelayAuth.signer = { e ->
                val relay = e.tags.first { it[0] == "relay" }[1]
                if (relay == "wss://first") slowBox.await(5, TimeUnit.SECONDS)
                signed += relay
                e.copy(sig = "00")
            }
            val first = RelayAuth.Session("wss://first", allowed = true)
            val typedPast = RelayAuth.Session("wss://typed-past", allowed = true)
            val last = RelayAuth.Session("wss://last", allowed = true)
            val lastSock = Sock()
            RelayAuth.run(Sock(), first, first.onChallenge("c1"), {}, {})
            RelayAuth.run(Sock(), typedPast, typedPast.onChallenge("c2"), {}, {})
            RelayAuth.run(lastSock, last, last.onChallenge("c3"), {}, {})
            typedPast.close()          // its query ended while the first login was still at the box
            slowBox.countDown()
            val deadline = System.currentTimeMillis() + 5000
            while (lastSock.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf("wss://first", "wss://last"), signed.toList())
            assertTrue(lastSock.sent.single().startsWith("[\"AUTH\","))
        } finally { RelayAuth.signer = was.first; RelayAuth.pubkey = was.second; RelayAuth.chosen = was.third }
    }
}
