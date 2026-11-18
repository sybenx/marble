package cc.aaro.cosign.web

import cc.aaro.cosign.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSitesTest {
    @Before fun reset() { WebSites.list.value = WebSites.builtIn }

    @Test fun aVisitIsHttpsOnlyAndCantSignIn() {
        val v = WebSites.visiting("noStrudel", "https://nostrudel.ninja/")!!
        assertEquals("https://nostrudel.ninja", v.origin)
        assertEquals(WebSite.Kind.VISITING, v.kind)
        assertFalse("nothing found by search can sign in by being opened", v.asks)
        assertNull(WebSites.visiting("x", "http://nostrudel.ninja/"))
        assertNull(WebSites.visiting("x", "javascript:alert(1)"))
        assertNull(WebSites.visiting("x", "nostrconnect://abc"))
        assertTrue(WebSites.marble.asks && WebSites.jumble.asks)
    }

    @Test fun oneVisitAtATimeAfterTheChips() {
        val a = WebSites.visiting("A", "https://a.example/")!!
        val b = WebSites.visiting("B", "https://b.example/")!!
        WebSites.put(a); WebSites.put(b)
        assertEquals("a second visit replaces the first", listOf("Marble", "Jumble", "B"), WebSites.all.map { it.name })
        WebSites.update(b.copy(kind = WebSite.Kind.KEPT))
        WebSites.put(a)
        assertEquals("a kept chip is never replaced", listOf("Marble", "Jumble", "B", "A"), WebSites.all.map { it.name })
        assertEquals(WebSite.Kind.KEPT, WebSites.byOrigin("https://b.example")!!.kind)
        // Another site's links reach a kept or visiting chip too.
        assertEquals(Nav.OtherSite(WebSites.byOrigin("https://b.example")!!, "https://b.example/x"), WebRules.decide(WebSites.jumble, "https://b.example/x", true, true))
        WebSites.drop(WebSites.marble)
        assertTrue("the built in stay", WebSites.byOrigin(WebSites.marble.origin) != null)
        WebSites.drop(WebSites.byOrigin("https://a.example")!!)
        assertNull(WebSites.byOrigin("https://a.example"))
    }

    @Test fun keptSitesRoundTrip() {
        val k = WebSites.visiting("noStrudel", "https://nostrudel.ninja/")!!.copy(kind = WebSite.Kind.KEPT, asks = true)
        val back = WebSiteCodec.decode(WebSiteCodec.encode(listOf(k)))
        assertEquals(listOf(k), back)
        assertEquals("o:https://nostrudel.ninja", back.single().id)
        assertEquals(emptyList<WebSite>(), WebSiteCodec.decode("not json"))
        assertEquals(emptyList<WebSite>(), WebSiteCodec.decode(null))
        assertEquals("never anything but https", emptyList<WebSite>(), WebSiteCodec.decode("""[{"name":"x","start":"http://plain.example/"}]"""))
    }

    private fun site(pk: String, origin: String, own: Boolean = false, via: String? = null) = Site(pk, origin, 0, 0, null, via, own)

    @Test fun whichAccountASignInGets() {
        val o = "https://nostrudel.ninja"
        assertEquals("new to this account: the choice", PaneChoice.ASK, PaneChoice.decide(o, emptyList()))
        assertEquals(PaneChoice.OWN, PaneChoice.decide(o, listOf(site("a", "nostrudel.ninja", own = true))))
        assertEquals(PaneChoice.MAIN, PaneChoice.decide(o, listOf(site("a", "nostrudel.ninja"))))
        assertEquals("by the host, however the record names it", PaneChoice.MAIN, PaneChoice.decide(o, listOf(site("a", "https://nostrudel.ninja/"))))
        assertEquals("another site doesn't count", PaneChoice.ASK, PaneChoice.decide(o, listOf(site("a", "jumble.social", own = true))))
        assertEquals("an app on the phone doesn't count", PaneChoice.ASK, PaneChoice.decide(o, listOf(site("a", "nostrudel.ninja", via = "App on this phone"))))
        val mine = site("a", "nostrudel.ninja")
        assertEquals("the pane's own earlier sign-in ends first", PaneChoice.ASK, PaneChoice.decide(o, listOf(mine), listOf(mine)))
        assertEquals("Marble's own site is always the main account", PaneChoice.MAIN, PaneChoice.decide(WebSites.marble.origin, emptyList()))
    }

    @Test fun promptsSayWhereTheSiteCameFrom() {
        assertNull(siteNote(WebSites.jumble))
        val n = siteNote(WebSites.visiting("n", "https://nostrudel.ninja/")!!)!!
        assertTrue(n.contains("search"))
        assertFalse(n.contains('—'))
    }
}
