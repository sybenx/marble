package cc.aaro.cosign

import cc.aaro.cosign.core.Asking
import cc.aaro.cosign.core.Asking.Action
import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words and buttons of asking you (Keykin SPEC 11.6, The tap). Level 2 is never done by accident: its notification
 * has no Approve (Android shows the fingerprint prompt only over a screen of this app), and every place that asks says
 * that the fingerprint comes after Yes.
 */
class AskingTest {
    private val pk = "f".repeat(64)
    private fun ev(kind: Int, tags: List<List<String>> = emptyList(), content: String = "") = Event(pk, 1_800_000_000L, kind, tags, content)

    @Test fun aLevel2NotificationHasNoApprove() {
        assertEquals(listOf(Action.DENY, Action.APPROVE), Asking.actions(1))
        assertEquals(listOf(Action.DENY), Asking.actions(2))
    }

    @Test fun level2SaysTheFingerprintComesNext() {
        assertEquals("TestSite asked. It doesn't happen unless you say yes.", Asking.cardLine("TestSite", 1))
        assertEquals("TestSite asked. After you say yes, your fingerprint confirms it.", Asking.cardLine("TestSite", 2))
        assertEquals("TestSite asked" to "TestSite asked. It doesn't happen unless you say yes.", Asking.noticeLines("TestSite", 1, "Marble"))
        assertEquals("TestSite asked. Tap to answer in Marble." to "TestSite asked. Tap to answer in Marble. After you say yes, your fingerprint confirms it.",
            Asking.noticeLines("TestSite", 2, "Marble"))
        assertEquals("Your account asks you first before anything like this.", Asking.ownLine(1))
        assertEquals("Your account asks you first before anything like this. After you say yes, your fingerprint confirms it.", Asking.ownLine(2))
    }

    @Test fun aSignaturesPromptIsNotAnAccountChange() {
        assertEquals("Changes to your account need your fingerprint", Asking.ACCOUNT_CHANGE)
        assertEquals("Your fingerprint confirms it", Asking.SIGNATURE)
        assertNotEquals(Asking.ACCOUNT_CHANGE, Asking.SIGNATURE)
        assertEquals("Tap to finish with your fingerprint.", Asking.FINISH)
    }

    /** The prompt's title is the card's question, so the two read as one thought. */
    @Test fun thePromptAsksWhatTheCardAsked() {
        assertEquals("Erase everything you've ever posted?", Asking.question("Ask relays to erase everything you have ever published"))
        assertEquals("Unfollow all 3 people?", Asking.question("Empty your follow list (it has 3 follows)"))
        assertEquals("Clear your profile (everything)?", Asking.question("Wipe your profile: everything"))
        assertEquals("Remove 3 of your 4 relays?", Asking.question("Remove 3 of your 4 relays"))
    }

    /** A box from before it gave its reasons: say what is being signed, from the event itself. */
    @Test fun anOldBoxsQuestionSaysWhatItIsAbout() {
        assertEquals("Erase everything you've ever posted?", Asking.question(Asking.unsaid(ev(62))))
        assertEquals("Delete 5 posts?", Asking.question(Asking.unsaid(ev(5, (1..5).map { listOf("e", "$it".repeat(64)) }))))
        assertEquals("Delete several posts?", Asking.question(Asking.unsaid(ev(5))))
        assertEquals("Empty your follow list?", Asking.question(Asking.unsaid(ev(3))))
        assertEquals("Clear your profile (everything)?", Asking.question(Asking.unsaid(ev(0, content = "{}"))))
        assertEquals("Sign this note?", Asking.question(Asking.unsaid(ev(1))))
        assertEquals("Sign this relay list?", Asking.question(Asking.unsaid(ev(10002))))
        // A kind with no plain name: no internal name either.
        assertEquals("Sign this?", Asking.question(Asking.unsaid(ev(31234))))
    }

    @Test fun noEmDashes() {
        val all = listOf(Asking.ACCOUNT_CHANGE, Asking.SIGNATURE, Asking.FINISH, Asking.cardLine("A", 1), Asking.cardLine("A", 2), Asking.ownLine(1), Asking.ownLine(2)) +
            Asking.noticeLines("A", 1, "M").toList() + Asking.noticeLines("A", 2, "M").toList() +
            listOf(0, 1, 3, 5, 62, 31234).map { Asking.question(Asking.unsaid(ev(it))) }
        for (s in all) assertFalse(s, '—' in s)
    }

    /** The app's own dialog hears the level from the signer process. */
    @Test fun theLevelCrossesToTheApp() {
        val bundle = HashMap<String, Any>()
        NeedsApproval.Wire.write(NeedsApproval("Ask relays to erase everything you have ever published", 2), { k, v -> bundle[k] = v }, { k, v -> bundle[k] = v })
        val back = NeedsApproval.Wire.read({ bundle[it] as? String }, { k, d -> bundle[k] as? Int ?: d })!!
        assertEquals("Ask relays to erase everything you have ever published", back.what)
        assertEquals(2, back.level)
        // An answer with no level is level 1; one with no question is none.
        assertEquals(1, NeedsApproval.Wire.read({ if (it == NeedsApproval.Wire.WHAT) "Delete 5 of your events" else null }, { _, d -> d })!!.level)
        assertNull(NeedsApproval.Wire.read({ null }, { _, d -> d }))
    }
}
