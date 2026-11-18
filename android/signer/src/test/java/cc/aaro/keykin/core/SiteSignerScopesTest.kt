package cc.aaro.keykin.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each signed-in site keeps its two switches; sites signed in before 0.15.1 keep posting with both off. */
class SiteSignerScopesTest {
    private val account = "a".repeat(64)
    private val client = "d".repeat(64)
    private val acted = ArrayList<Pair<SiteSigner.Site, String>>()
    private fun signer(state: JSONObject) = SiteSigner({ account }, { site, method, _ -> acted += site to method; "ok" }, state, {})

    @Test fun anOldGrantKeepsPostingWithListsAndDmsOff() {
        // A client record as 0.15.0 and before wrote it: no lists, no dms.
        val old = SiteSigner.fresh().put("clients", JSONArray().put(JSONObject().put("key", client).put("relays", JSONArray()).put("added", 1).put("used", 1)
            .put("grant", "0".repeat(32)).put("via", "A scanned code").put("origin", "jumble.social")))
        val s = signer(old)
        val site = s.sites().single()
        assertFalse(site.lists); assertFalse(site.dms)
        assertEquals("ok", s.answer(client, "sign_event", listOf("{\"kind\":1}")))
        assertEquals("sign_event", acted.single().second)
        assertFalse(acted.single().first.lists || acted.single().first.dms)
    }

    @Test fun aTicketCarriesItsSwitchesIntoTheSignIn() {
        val s = signer(SiteSigner.fresh())
        val t = s.mint("jumble.social", emptyList(), lists = true, dms = false)
        s.answer(client, "connect", listOf(account, t.getString("t")))
        val site = s.sites().single()
        assertTrue(site.lists); assertFalse(site.dms)
    }

    @Test fun theSheetChangesThem() {
        val s = signer(SiteSigner.fresh())
        s.answer(client, "connect", listOf(account, s.mint(null, emptyList()).getString("t")))
        assertFalse(s.sites().single().dms)
        assertTrue(s.setScopes(client, lists = false, dms = true))
        assertTrue(s.sites().single().dms)
        assertFalse("no such site", s.setScopes("e".repeat(64), true, true))
    }
}
