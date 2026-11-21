package cc.aaro.cosign

import cc.aaro.cosign.core.BoxAsk
import cc.aaro.cosign.core.BoxAsk.Question
import cc.aaro.keykin.core.KeykinException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Signing with a box that decides (Keykin SPEC 11.6, The tap). Level 1: the box's needs_tap is asked once and sent
 * again with tapped. Level 2: its needs_fingerprint is asked once and sent again with the fingerprint; tapped alone
 * never signs it.
 */
class BoxAskTest {
    /** What one request carried: tapped, and the words of the fingerprint prompt when the fingerprint went with it. */
    private data class Sent(val tapped: Boolean, val fingerprint: String?)

    /** A box that asks about everything at [level] until it hears tapped (level 1) or gets the fingerprint (level 2). */
    private class FakeBox(val why: String? = "Remove 10 of your 30 follows", val level: Int = 1) {
        val sent = mutableListOf<Sent>()
        fun sign(tapped: Boolean, fingerprint: String?): String {
            sent += Sent(tapped, fingerprint)
            if (level == 2 && fingerprint == null) throw KeykinException("needs_fingerprint", why, why)
            if (level == 1 && !tapped) throw KeykinException("needs_tap", why, why)
            return "sig"
        }
    }
    private fun q(s: String, level: Int = 1) = Question(s, level)

    @Test fun theBoxAsksAndYesSendsItAgainWithTapped() {
        val box = FakeBox(); val asked = mutableListOf<Question>()
        val r = BoxAsk.sign(null, false, "unsaid", { asked += it; true }, box::sign)
        assertEquals("sig", r.value); assertTrue(r.yes)
        assertEquals(listOf(q("Remove 10 of your 30 follows")), asked)
        assertEquals(listOf(Sent(false, null), Sent(true, null)), box.sent)
    }

    @Test fun noLeavesNothingSigned() {
        val box = FakeBox()
        try { BoxAsk.sign(null, false, "unsaid", { false }, box::sign); fail() } catch (e: KeykinException) { assertEquals("denied", e.code) }
        assertEquals(listOf(Sent(false, null)), box.sent)
    }

    @Test fun whenThisPhoneAskedFirstTappedGoesUpFrontAndNobodyIsAskedTwice() {
        val box = FakeBox(); val asked = mutableListOf<Question>()
        val r = BoxAsk.sign(q("Remove 10 of your 30 follows"), false, "unsaid", { asked += it; true }, box::sign)
        assertTrue(r.yes); assertEquals(listOf(q("Remove 10 of your 30 follows")), asked); assertEquals(listOf(Sent(true, null)), box.sent)
        // Its own No: the box is never asked.
        val quiet = FakeBox()
        try { BoxAsk.sign(q("Remove 10 of your 30 follows"), false, "unsaid", { false }, quiet::sign); fail() } catch (e: KeykinException) { assertEquals("denied", e.code) }
        assertTrue(quiet.sent.isEmpty())
    }

    @Test fun alreadyApprovedSendsTappedAndAsksNothing() {
        val box = FakeBox()
        val r = BoxAsk.sign(q("anything"), true, "unsaid", { fail("asked"); true }, box::sign)
        assertTrue(r.yes); assertEquals(listOf(Sent(true, null)), box.sent)
    }

    @Test fun whenItCannotAskNowTheCallerHearsTheBoxsWords() {
        val box = FakeBox()
        try { BoxAsk.sign(null, false, "unsaid", { throw NeedsApproval(it.says, it.level) }, box::sign); fail() }
        catch (e: NeedsApproval) { assertEquals("Remove 10 of your 30 follows", e.what); assertEquals(1, e.level) }
        // Asked later and the person said Yes: sent once, with tapped.
        val again = FakeBox()
        BoxAsk.sign(null, true, "unsaid", { fail("asked"); true }, again::sign)
        assertEquals(listOf(Sent(true, null)), again.sent)
    }

    @Test fun anOlderBoxThatGivesNoReasonIsAskedAboutInGeneralWords() {
        val box = FakeBox(why = null); val asked = mutableListOf<Question>()
        BoxAsk.sign(null, false, "Your box asks for your Yes before signing this", { asked += it; true }, box::sign)
        assertEquals(listOf(q("Your box asks for your Yes before signing this")), asked)
    }

    @Test fun nothingToAskSignsAtOnceAndOtherErrorsPassThrough() {
        val r = BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { t, f -> assertFalse(t); assertNull(f); "sig" }
        assertFalse(r.yes)
        try { BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { _, _ -> throw KeykinException("dated_ahead", "Follow list dated 2099-01-01", "Follow list dated 2099-01-01") }; fail() }
        catch (e: KeykinException) { assertEquals("dated_ahead", e.code); assertEquals("Follow list dated 2099-01-01", e.why) }
        // A box that still says needs_tap after tapped is not asked about again.
        var n = 0
        try { BoxAsk.sign(null, true, "unsaid", { fail("asked"); true }) { _, _ -> n++; throw KeykinException("needs_tap") }; fail() } catch (e: KeykinException) { assertEquals("needs_tap", e.code) }
        assertEquals(1, n)
    }

    // --- Level 2: never by accident ---------------------------------------------------------------

    @Test fun level2FromTheBoxIsAskedWithItsWordsThenTheFingerprintGoesWithTheSameRequest() {
        val box = FakeBox("Empty your follow list", 2); val asked = mutableListOf<Question>()
        val r = BoxAsk.sign(null, false, "unsaid", { asked += it; true }, box::sign)
        assertEquals("sig", r.value); assertTrue(r.yes)
        assertEquals(listOf(q("Empty your follow list", 2)), asked)
        // The fingerprint prompt says the box's words.
        assertEquals(listOf(Sent(false, null), Sent(true, "Empty your follow list")), box.sent)
    }

    @Test fun level2NoTakesNoFingerprintAndSendsNothingMore() {
        val box = FakeBox("Empty your follow list", 2)
        try { BoxAsk.sign(null, false, "unsaid", { false }, box::sign); fail() } catch (e: KeykinException) { assertEquals("denied", e.code) }
        assertEquals(listOf(Sent(false, null)), box.sent)
    }

    @Test fun level2AYesAlreadyGivenGoesStraightToTheFingerprint() {
        // This phone asked in its own words at level 1, and the box wants the fingerprint: no second Yes or No.
        val box = FakeBox("Empty your follow list", 2); val asked = mutableListOf<Question>()
        BoxAsk.sign(q("Remove 30 of your 30 follows"), false, "unsaid", { asked += it; true }, box::sign)
        assertEquals(listOf(q("Remove 30 of your 30 follows")), asked)
        assertEquals(listOf(Sent(true, null), Sent(true, "Empty your follow list")), box.sent)
        // A Yes given elsewhere (the person said Yes to NeedsApproval): the same.
        val later = FakeBox("Empty your follow list", 2)
        BoxAsk.sign(null, true, "unsaid", { fail("asked"); true }, later::sign)
        assertEquals(listOf(Sent(true, null), Sent(true, "Empty your follow list")), later.sent)
    }

    @Test fun level2WhenItCannotAskNowTheCallerHearsTheLevel() {
        val box = FakeBox("Ask relays to erase everything you have ever published", 2)
        try { BoxAsk.sign(null, false, "unsaid", { throw NeedsApproval(it.says, it.level) }, box::sign); fail() }
        catch (e: NeedsApproval) { assertEquals("Ask relays to erase everything you have ever published", e.what); assertEquals(2, e.level) }
        assertEquals(listOf(Sent(false, null)), box.sent)
    }

    @Test fun thisPhonesOwnLevel2SendsTheFingerprintWithTheFirstRequest() {
        // With a box that also wants it, and with no box at all (sign always signs): one ask, one fingerprint.
        for (box in listOf(FakeBox("Empty your follow list", 2), FakeBox(null, 0))) {
            val asked = mutableListOf<Question>()
            val r = BoxAsk.sign(q("Empty your follow list (it has 3 follows)", 2), false, "unsaid", { asked += it; true }, box::sign)
            assertTrue(r.yes)
            assertEquals(listOf(q("Empty your follow list (it has 3 follows)", 2)), asked)
            assertEquals(listOf(Sent(true, "Empty your follow list (it has 3 follows)")), box.sent)
        }
        // Already approved: no ask, but the fingerprint still goes.
        val box = FakeBox(null, 0)
        BoxAsk.sign(q("Ask relays to erase everything you have ever published", 2), true, "unsaid", { fail("asked"); true }, box::sign)
        assertEquals(listOf(Sent(true, "Ask relays to erase everything you have ever published")), box.sent)
    }

    @Test fun level2AFingerprintTheBoxDidNotTakeIsNotAskedForAgain() {
        var n = 0
        try {
            BoxAsk.sign(q("Empty your follow list", 2), false, "unsaid", { true }) { _, f -> n++; throw KeykinException("needs_fingerprint", "Empty your follow list", "Empty your follow list") }
            fail()
        } catch (e: KeykinException) { assertEquals("needs_fingerprint", e.code) }
        assertEquals(1, n)
        // The box's own level 2 after it already had one: once too.
        n = 0
        try {
            BoxAsk.sign(null, false, "unsaid", { true }) { _, _ -> n++; throw KeykinException("needs_fingerprint", "Empty your follow list", "Empty your follow list") }
            fail()
        } catch (e: KeykinException) { assertEquals("needs_fingerprint", e.code) }
        assertEquals(2, n)
    }

    @Test fun anAccountChangesNeedsFingerprintWithNoWordsIsNoQuestion() {
        var n = 0
        try { BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { _, _ -> n++; throw KeykinException("needs_fingerprint") }; fail() }
        catch (e: KeykinException) { assertEquals("needs_fingerprint", e.code) }
        assertEquals(1, n)
        // And no_chip_key (a phone with no fingerprint set up) is said, never asked about.
        try { BoxAsk.sign(null, false, "unsaid", { fail("asked"); true }) { _, _ -> throw KeykinException("no_chip_key", "Empty your follow list", "Empty your follow list") }; fail() }
        catch (e: KeykinException) { assertEquals("no_chip_key", e.code); assertEquals("Empty your follow list", e.why) }
    }
}
