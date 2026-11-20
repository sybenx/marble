package cc.aaro.cosign

import cc.aaro.cosign.Signer.Kept
import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.SiteSigner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account page turns a site's switches on and off, through the same path as the site's sheet on this phone. */
class SiteSwitchesTest {
    private val account = "a".repeat(64)
    private val jumble = "1".repeat(32)
    private val coracle = "2".repeat(32)
    private fun rec(key: Char, grant: String, origin: String, lists: Boolean, dms: Boolean) = JSONObject().put("key", key.toString().repeat(64)).put("relays", JSONArray())
        .put("added", 1).put("used", 1).put("grant", grant).put("via", "A scanned code").put("origin", origin).put("lists", lists).put("dms", dms)
    private var saves = 0
    private val sites = SiteSigner({ account }, { _, _, _ -> "ok" }, SiteSigner.fresh().put("clients", JSONArray()
        .put(rec('d', jumble, "jumble.social", lists = false, dms = false))
        .put(rec('e', coracle, "coracle.social", lists = true, dms = true))), { saves++ })
    private val kept = Kept()
    private val notes = ArrayList<Pair<String, String>>()
    private fun apply(wants: Map<String, Device.Want>) = SiteSwitches.fromPage(sites, wants, kept::forget, { t, k -> notes += t to k })
    private fun site(grant: String) = sites.sites().single { it.grant == grant }

    @Test fun aWantFlipsThatSiteAndOnlyThatOne() {
        assertEquals(1, apply(mapOf(jumble to Device.Want(lists = true, dms = true))))
        assertTrue(site(jumble).lists); assertTrue(site(jumble).dms)
        assertTrue(site(coracle).lists); assertTrue(site(coracle).dms)
        assertEquals(listOf("Private lists turned on for jumble.social from your account page" to "approved",
            "DMs turned on for jumble.social from your account page" to "approved"), notes)
        assertEquals("one save, so one report after", 1, saves)
    }

    @Test fun aPartialWantChangesOnlyTheNamedSwitch() {
        assertEquals(1, apply(mapOf(coracle to Device.Want(dms = false))))
        assertTrue(site(coracle).lists); assertFalse(site(coracle).dms)
        assertEquals(listOf("DMs turned off for coracle.social from your account page" to "denied"), notes)
    }

    @Test fun unknownIdsAreIgnored() {
        assertEquals(0, apply(mapOf("9".repeat(32) to Device.Want(true, true))))
        assertFalse(site(jumble).lists || site(jumble).dms)
        assertTrue(notes.isEmpty()); assertEquals(0, saves)
    }

    @Test fun turningDmsOffForgetsItsSharedKeys() {
        kept["$coracle:peer"] = ByteArray(32) { 7 }
        kept["$jumble:peer"] = ByteArray(32) { 8 }
        apply(mapOf(coracle to Device.Want(dms = false)))
        assertNull("the site's keys are gone", kept["$coracle:peer"])
        assertNotNull("another site's stay", kept["$jumble:peer"])
    }

    @Test fun turningOnForgetsNothing() {
        kept["$jumble:peer"] = ByteArray(32) { 8 }
        apply(mapOf(jumble to Device.Want(dms = true)))
        assertNotNull(kept["$jumble:peer"])
    }

    @Test fun aRepeatedWantIsANoOp() {
        val want = mapOf(coracle to Device.Want(lists = false))
        assertEquals(1, apply(want))
        notes.clear(); val before = saves
        // The box asks again before it has seen the report that says it is done: nothing happens, nothing is saved.
        assertEquals(0, apply(want))
        assertTrue(notes.isEmpty()); assertEquals(before, saves)
        // Wants that are already so from the start, too.
        assertEquals(0, apply(mapOf(jumble to Device.Want(false, false), coracle to Device.Want(dms = true))))
    }

    @Test fun theSheetGoesTheSameWay() {
        kept["$coracle:peer"] = ByteArray(32) { 7 }
        assertTrue(SiteSwitches.set(sites, site(coracle), lists = false, dms = true, forget = kept::forget))
        assertNull("lists off forgets too", kept["$coracle:peer"])
        assertFalse(site(coracle).lists)
    }
}
