package cc.aaro.keykin.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A bunker link says nothing of its site: the name typed for it, else the site's first client tag, names it. */
class SiteNamesTest {
    private val account = "a".repeat(64)
    private val client = "d".repeat(64)
    private val acted = ArrayList<SiteSigner.Site>()
    private var changes = 0
    private fun signer(state: JSONObject = SiteSigner.fresh()) = SiteSigner({ account }, { site, _, _ -> acted += site; "ok" }, state, { changes++ })
    private fun secret(link: String) = Regex("secret=([0-9a-f]+)").find(link)!!.groupValues[1]
    private fun post(vararg tags: List<String>) = JSONObject().put("kind", 1).put("content", "hi").put("tags", JSONArray(tags.map { JSONArray(it) })).toString()

    @Test fun aBunkerLinkWithANameNamesTheSite() {
        val s = signer()
        s.answer(client, "connect", listOf(account, secret(s.bunker(emptyList(), " Jumble\u0007\n"))))
        val site = s.sites().single()
        assertEquals("Jumble", site.name); assertNull(site.origin)
        assertEquals("Jumble", site.label); assertEquals("bunker", site.how)
        assertFalse(site.lists || site.dms)
    }

    @Test fun aBunkerLinkCarriesTheSwitches() {
        val s = signer()
        s.answer(client, "connect", listOf(account, secret(s.bunker(emptyList(), null, lists = true, dms = true))))
        val site = s.sites().single()
        assertTrue(site.lists && site.dms); assertNull(site.name)
        assertEquals("A site", site.label)
    }

    @Test fun theFirstClientTagNamesAnUnnamedSite() {
        val s = signer()
        s.answer(client, "connect", listOf(account, secret(s.bunker(emptyList(), "   "))))
        assertNull(s.sites().single().name)
        val before = changes
        s.answer(client, "sign_event", listOf(post(listOf("t", "keykin"))))
        assertNull(s.sites().single().name); assertEquals(before, changes)
        s.answer(client, "sign_event", listOf(post(listOf("client", ""), listOf("client", "  Coracle  ", "31990:abc:def", "wss://r"))))
        assertEquals("Coracle", s.sites().single().label)
        assertEquals(before + 1, changes)                                  // saved, so the list redraws and the box hears of it
        assertEquals("Coracle", acted.last().label)                        // the request itself already goes under the new name
        // The first tag wins over later ones.
        s.answer(client, "sign_event", listOf(post(listOf("client", "Snort"))))
        assertEquals("Coracle", s.sites().single().name); assertEquals(before + 1, changes)
    }

    @Test fun theTypedNameIsKept() {
        val s = signer()
        s.answer(client, "connect", listOf(account, secret(s.bunker(emptyList(), "My blog"))))
        s.answer(client, "sign_event", listOf(post(listOf("client", "Coracle"))))
        assertEquals("My blog", s.sites().single().label)
    }

    @Test fun anOriginWins() {
        val s = signer()
        s.answer(client, "connect", listOf(account, s.mint("jumble.social", emptyList(), name = "Something else").getString("t")))
        s.answer(client, "sign_event", listOf(post(listOf("client", "Coracle"))))
        val site = s.sites().single()
        assertEquals("jumble.social", site.label); assertEquals("Something else", site.name)
    }

    @Test fun anOlderUnnamedRecordIsNamedByItsPosts() {
        // A site signed in before names existed, shown as "A site": its next post with a client tag names it.
        val rec = JSONObject().put("key", client).put("relays", JSONArray()).put("added", 1).put("used", 1).put("grant", "0".repeat(32)).put("via", "A pasted bunker link")
        val s = signer(SiteSigner.fresh().put("clients", JSONArray().put(rec)))
        assertEquals("A site", s.sites().single().label)
        s.answer(client, "sign_event", listOf(post(listOf("client", "Nostrudel"))))
        assertEquals("Nostrudel", s.sites().single().label)
        // So is one whose record holds "A site" as its origin (a code with no url or name).
        val placeholder = JSONObject(rec.toString()).put("origin", "A site")
        val s2 = signer(SiteSigner.fresh().put("clients", JSONArray().put(placeholder)))
        s2.answer(client, "sign_event", listOf(post(listOf("client", "Nostrudel"))))
        assertEquals("Nostrudel", s2.sites().single().label)
    }

    @Test fun aBunkerNoteCarriesItsNameAndSwitches() {
        val n = Device.Note.of(JSONObject().put("id", "n").put("kind", "bunker").put("text", "Jumble").put("at", 1).put("tap", false).put("lists", true).put("dms", false))
        assertEquals("Jumble", n.text); assertTrue(n.lists); assertFalse(n.dms)
        val old = Device.Note.of(JSONObject().put("id", "n").put("kind", "bunker").put("text", "").put("at", 1).put("tap", false))
        assertFalse(old.lists || old.dms)
    }

    @Test fun aSignInNoteCarriesTheSwitchesTheAccountPageSet() {
        val n = Device.Note.of(JSONObject().put("id", "n").put("kind", "nostrconnect").put("text", "nostrconnect://x").put("at", 1).put("tap", false).put("lists", true).put("dms", false))
        assertTrue(n.scoped); assertTrue(n.lists); assertFalse(n.dms)
        val off = Device.Note.of(JSONObject().put("id", "n").put("kind", "nostrconnect").put("text", "nostrconnect://x").put("at", 1).put("tap", false).put("lists", false).put("dms", false))
        assertTrue("both off is a choice, not a missing field", off.scoped); assertFalse(off.lists)
        // An older box sends none: the phone falls back to the code's perms.
        val old = Device.Note.of(JSONObject().put("id", "n").put("kind", "nostrconnect").put("text", "nostrconnect://x").put("at", 1).put("tap", false))
        assertFalse(old.scoped)
    }
}
