package cc.aaro.keykin.core

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A site's two switches go to the box with the `grants` report, and the account page's wants come back in its reply. */
class GrantScopesTest {
    private val me = Me(ByteArray(32) { 1 }, "Pixel 9a", null)
    private val h = Held(Piece(emptyList(), 1, BigInteger.ONE), "b".repeat(64), "c".repeat(64), "http://box")
    private val site = "1".repeat(32)
    private val app = "2".repeat(32)
    private val grants = listOf(
        Device.Grant(site, "site", "jumble.social", 5, "d".repeat(64), null, "scan", lists = true, dms = false),
        Device.Grant(app, "app", "Amethyst", 6))

    @Test fun theReportCarriesASitesSwitchesAndNotAnApps() {
        var sent: JSONObject? = null
        Device.reportGrants(me, h, grants, { sent = it; JSONObject().put("ok", true) })
        val gs = sent!!.getJSONObject("body").getJSONArray("grants")
        val s = gs.getJSONObject(0); val a = gs.getJSONObject(1)
        assertTrue(s.getBoolean("lists")); assertFalse(s.getBoolean("dms"))
        assertFalse(a.has("lists") || a.has("dms"))
    }

    @Test fun theReplyCarriesRevokedAndScopes() {
        val reply = JSONObject().put("ok", true).put("revoked", JSONArray().put(app))
            .put("scopes", JSONObject()
                .put(site, JSONObject().put("dms", true))                      // only DMs: lists stays as it is
                .put("3".repeat(32), JSONObject().put("lists", false).put("dms", true))
                .put("4".repeat(32), JSONObject())                              // neither: nothing to do
                .put("5".repeat(32), JSONObject().put("lists", "yes").put("dms", JSONObject.NULL)))   // not booleans: absent
        val r = Device.grantsReply(me, h, grants, { reply })
        assertEquals(listOf(app), r.revoked)
        assertEquals(mapOf(site to Device.Want(dms = true), "3".repeat(32) to Device.Want(false, true)), r.scopes)
        // The older call still gives just the revoked ids.
        assertEquals(listOf(app), Device.reportGrants(me, h, grants, { reply }))
    }

    @Test fun aReplyWithoutScopesWantsNothing() {
        val r = Device.grantsReply(me, h, grants, { JSONObject().put("ok", true) })
        assertTrue(r.revoked.isEmpty()); assertTrue(r.scopes.isEmpty())
    }
}
