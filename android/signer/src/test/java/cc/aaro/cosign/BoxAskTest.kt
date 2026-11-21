package cc.aaro.cosign

import cc.aaro.cosign.core.BoxAsk
import cc.aaro.keykin.core.KeykinException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Signing with a box that decides (Keykin SPEC 11.6, The tap): the box's needs_tap is asked once and sent again with tapped. */
class BoxAskTest {
    /** A box that asks about everything until it hears tapped, and records what it was sent. */
    private class FakeBox(val why: String? = "Empty your follow list") {
        val sent = mutableListOf<Boolean>()
        fun sign(tapped: Boolean): String { sent += tapped; if (!tapped) throw KeykinException("needs_tap", why, why); return "sig" }
    }

    @Test fun theBoxAsksAndYesSendsItAgainWithTapped() {
        val box = FakeBox(); val asked = mutableListOf<String>()
        val r = BoxAsk.sign(null, false, "unsaid", { asked += it; true }, box::sign)
        assertEquals("sig", r.value); assertTrue(r.yes)
        assertEquals(listOf("Empty your follow list"), asked)
        assertEquals(listOf(false, true), box.sent)
    }

    @Test fun noLeavesNothingSigned() {
        val box = FakeBox()
        try { BoxAsk.sign(null, false, "unsaid", { false }, box::sign); fail() } catch (e: KeykinException) { assertEquals("denied", e.code) }
        assertEquals(listOf(false), box.sent)
    }

    @Test fun whenThisPhoneAskedFirstTappedGoesUpFrontAndNobodyIsAskedTwice() {
        val box = FakeBox(); val asked = mutableListOf<String>()
        val r = BoxAsk.sign("Remove 10 of your 30 follows", false, "unsaid", { asked += it; true }, box::sign)
        assertTrue(r.yes); assertEquals(listOf("Remove 10 of your 30 follows"), asked); assertEquals(listOf(true), box.sent)
        // Its own No: the box is never asked.
        val quiet = FakeBox()
        try { BoxAsk.sign("Remove 10 of your 30 follows", false, "unsaid", { false }, quiet::sign); fail() } catch (e: KeykinException) { assertEquals("denied", e.code) }
        assertTrue(quiet.sent.isEmpty())
    }

    @Test fun alreadyApprovedSendsTappedAndAsksNothing() {
        val box = FakeBox()
        val r = BoxAsk.sign("anything", true, "unsaid", { fail("asked"); true }, box::sign)
        assertTrue(r.yes); assertEquals(listOf(true), box.sent)
    }

    @Test fun whenItCannotAskNowTheCallerHearsTheBoxsWords() {
        val box = FakeBox()
        try { BoxAsk.sign(null, false, "unsaid", { throw NeedsApproval(it) }, box::sign); fail() } catch (e: NeedsApproval) { assertEquals("Empty your follow list", e.what) }
        // Asked later and the person said Yes: sent once, with tapped.
        val again = FakeBox()
        BoxAsk.sign(null, true, "unsaid", { fail("asked"); true }, again::sign)
        assertEquals(listOf(true), again.sent)
    }

    @Test fun anOlderBoxThatGivesNoReasonIsAskedAboutInGeneralWords() {
        val box = FakeBox(why = null); val asked = mutableListOf<String>()
        BoxAsk.sign(null, false, "Your box asks for your Yes before signing this", { asked += it; true }, box::sign)
        assertEquals(listOf("Your box asks for your Yes before signing this"), asked)
    }

    @Test fun nothingToAskSignsAtOnceAndOtherErrorsPassThrough() {
        val r = BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { t -> assertFalse(t); "sig" }
        assertFalse(r.yes)
        try { BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { throw KeykinException("dated_ahead", "Follow list dated 2099-01-01", "Follow list dated 2099-01-01") }; fail() }
        catch (e: KeykinException) { assertEquals("dated_ahead", e.code); assertEquals("Follow list dated 2099-01-01", e.why) }
        // A box that still says needs_tap after tapped is not asked about again.
        var n = 0
        try { BoxAsk.sign(null, true, "unsaid", { fail("asked"); true }) { n++; throw KeykinException("needs_tap") }; fail() } catch (e: KeykinException) { assertEquals("needs_tap", e.code) }
        assertEquals(1, n)
    }
}
