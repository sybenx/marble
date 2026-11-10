package cc.aaro.cosign.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRulesTest {
    private val key = "a".repeat(64)
    private val code = "nostrconnect://$key?relay=wss%3A%2F%2Frelay.example&secret=s3&name=Totally%20Marble&url=https%3A%2F%2Fevil.example"
    private val marble = WebSites.marble
    private val jumble = WebSites.jumble

    @Test fun origins() {
        assertEquals("https://marble.syb.lol", marble.origin)
        assertEquals("https://jumble.social", jumble.origin)
        assertEquals("https://a.b:8443", WebRules.originOf("https://A.b:8443/x?y"))
        assertEquals("https://a.b", WebRules.originOf("https://a.b:443/"))
        assertNull(WebRules.originOf("javascript:alert(1)"))
        assertNull(WebRules.originOf("file:///sdcard/x"))
    }

    @Test fun sameOriginStays() {
        assertEquals(Nav.Stay, WebRules.decide(marble, "https://marble.syb.lol/npub1x", mainFrame = true, gesture = false))
        assertEquals(Nav.Stay, WebRules.decide(jumble, "https://jumble.social/notes/1", mainFrame = true, gesture = true))
    }

    @Test fun anotherSiteOpensItsChip() {
        val n = WebRules.decide(jumble, "https://marble.syb.lol/nevent1x", mainFrame = true, gesture = true)
        assertEquals(Nav.OtherSite(marble, "https://marble.syb.lol/nevent1x"), n)
    }

    @Test fun offSiteNeedsATap() {
        assertEquals(Nav.Outside("https://example.com/a"), WebRules.decide(marble, "https://example.com/a", mainFrame = true, gesture = true))
        assertEquals(Nav.Blocked("https://example.com/a"), WebRules.decide(marble, "https://example.com/a", mainFrame = true, gesture = false))
        // Another port, or plain http, on the same host is another origin.
        assertEquals(Nav.Blocked("https://marble.syb.lol:8443/"), WebRules.decide(marble, "https://marble.syb.lol:8443/", mainFrame = true, gesture = false))
        assertEquals(Nav.Outside("http://marble.syb.lol/"), WebRules.decide(marble, "http://marble.syb.lol/", mainFrame = true, gesture = true))
        // Google's sign-in is always a Custom Tab.
        assertEquals(Nav.Outside("https://accounts.google.com/o"), WebRules.decide(marble, "https://accounts.google.com/o", mainFrame = true, gesture = true))
    }

    @Test fun framesLoadInPlace() {
        assertEquals(Nav.Stay, WebRules.decide(jumble, "https://www.youtube.com/embed/x", mainFrame = false, gesture = false))
    }

    @Test fun signInOnlyFromATapInTheMainFrame() {
        assertEquals(Nav.SignIn(code), WebRules.decide(jumble, code, mainFrame = true, gesture = true))
        assertEquals(Nav.Drop, WebRules.decide(jumble, code, mainFrame = true, gesture = false)) // scripted
        assertEquals(Nav.Drop, WebRules.decide(jumble, code, mainFrame = false, gesture = true)) // from a subframe
    }

    @Test fun marbleWebsIntentLink() {
        val intent = "intent://$key?relay=wss%3A%2F%2Fr.x&secret=s#Intent;scheme=nostrconnect;S.browser_fallback_url=https%3A%2F%2Fmarble.syb.lol%2F%23signin-no-app;end"
        assertEquals(Nav.SignIn("nostrconnect://$key?relay=wss%3A%2F%2Fr.x&secret=s"), WebRules.decide(marble, intent, mainFrame = true, gesture = true))
    }

    @Test fun otherIntentsAndSchemesAreDropped() {
        for (u in listOf(
            "intent://x#Intent;component=cc.aaro.cosign.keykin/.Home;end",
            "intent://$key?secret=s#Intent;scheme=nostrconnect;package=cc.aaro.cosign;end",
            "intent://$key?secret=s#Intent;scheme=nostrconnect;component=cc.aaro.cosign/.MainActivity;end",
            "intent://$key?secret=s#Intent;scheme=https;end",
            "intent://$key?secret=s#Intent;scheme=nostrconnect;scheme=https;end",
            "nostrsigner:abc", "file:///data/data/x", "content://x/y", "javascript:alert(1)", "data:text/html,hi", "market://details?id=x",
            "nostrconnect://notakey?secret=s", "nostrconnect://$key",
        )) assertEquals(u, Nav.Drop, WebRules.decide(marble, u, mainFrame = true, gesture = true))
    }

    @Test fun codeIsNamedAfterTheVerifiedOrigin() {
        val named = WebRules.namedFor(code, "https://jumble.social")
        assertFalse(named.contains("name="))
        assertFalse(named.contains("evil"))
        assertTrue(named.endsWith("&url=https%3A%2F%2Fjumble.social"))
        assertTrue(named.startsWith("nostrconnect://$key?relay=wss%3A%2F%2Frelay.example&secret=s3"))
        assertEquals(key, WebRules.clientOf(named))
    }
}
