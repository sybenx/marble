package cc.aaro.keykin.core

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What tells one signed-in site from another on the account page (Keykin SPEC 11.5). */
class GrantLabelsTest {
    private val account = "a".repeat(64)
    private val client = "ab12".padEnd(64, '0')
    private fun signer(state: JSONObject) = SiteSigner({ account }, { _, _, _ -> "ok" }, state, {})

    @Test fun aNostrConnectSiteKeepsItsLabelAndHow() {
        // No relay answers here, so the sign-in is undone; what it kept first is caught as it is saved.
        var kept: JSONArray? = null
        val s = SiteSigner({ account }, { _, _, _ -> "ok" }, SiteSigner.fresh(), { st -> if (kept == null) kept = JSONArray(st.getJSONArray("clients").toString()) })
        runCatching { s.acceptNostrConnect(client, emptyList(), "sec", "marble.syb.lol", how = "login", device = " Chrome on Windows\u0007" + "x".repeat(80)) }
        s.stop()
        val site = signer(SiteSigner.fresh().put("clients", kept!!)).sites().single()
        assertEquals("Chrome on Windows" + "x".repeat(43), site.device)
        assertEquals("login", site.how)
    }

    @Test fun olderRecordsGetHowFromTheirVia() {
        fun rec(via: String) = JSONObject().put("key", client).put("relays", JSONArray()).put("added", 1).put("used", 1).put("grant", "0".repeat(32)).put("via", via)
        assertEquals("scan", signer(SiteSigner.fresh().put("clients", JSONArray().put(rec("A scanned code")))).sites().single().how)
        assertEquals("bunker", signer(SiteSigner.fresh().put("clients", JSONArray().put(rec("A pasted bunker link")))).sites().single().how)
        val nc = signer(SiteSigner.fresh().put("clients", JSONArray().put(rec("A nostrconnect code")))).sites().single()
        assertNull(nc.how); assertNull(nc.device)
        assertNull(SiteSigner.deviceLabel("  "))
    }

    @Test fun theReportCarriesClientDeviceAndVia() {
        var sent: JSONObject? = null
        val me = Me(ByteArray(32) { 1 }, "Pixel 9a", null)
        val h = Held(Piece(emptyList(), 1, BigInteger.ONE), "b".repeat(64), "c".repeat(64), "http://box")
        Device.reportGrants(me, h, listOf(
            Device.Grant("1".repeat(32), "site", "marble.syb.lol", 5, client, "Chrome on Windows", "login"),
            Device.Grant("2".repeat(32), "app", "Amethyst", 6)), { sent = it; JSONObject().put("ok", true) })
        val gs = sent!!.getJSONObject("body").getJSONArray("grants")
        val a = gs.getJSONObject(0); val b = gs.getJSONObject(1)
        assertEquals(client, a.getString("client")); assertEquals("Chrome on Windows", a.getString("device")); assertEquals("login", a.getString("via"))
        assertFalse(b.has("client") || b.has("device") || b.has("via"))
    }
}
