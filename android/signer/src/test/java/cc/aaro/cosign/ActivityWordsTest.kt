package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The activity list on Apps and sites says what happened in plain words, with no kind numbers. */
class ActivityWordsTest {
    @Test fun knownKindsSayWhatHappened() {
        assertEquals("Marble posted a note", Describe.signed(1, "Marble"))
        assertEquals("jumble.social updated who you follow", Describe.signed(3, "https://jumble.social"))
        assertEquals("Marble set up your notes on this phone", Describe.signed(4811, "Marble"))
    }

    @Test fun unknownKindsSaySomethingPlain() = assertEquals("Signed something for Marble", Describe.signed(31337, "Marble"))

    @Test fun oldLinesAreReworded() {
        assertEquals("Marble set up your notes on this phone", Describe.plainLine("Signed kind 4811 event for Marble"))
        assertEquals("Marble saved your notes", Describe.plainLine("Signed kind 30810 event for Marble"))
        assertEquals("A site deleted something", Describe.plainLine("Signed deletion for A site"))
        assertEquals("Marble updated your servers", Describe.plainLine("Signed relay list for Marble"))
        assertEquals("Signed something for Marble", Describe.plainLine("Signed kind 31337 event for Marble"))
        // Lines that were never about a signature stay as they are.
        assertEquals("Made your account on this phone", Describe.plainLine("Made your account on this phone"))
        assertEquals("Said no to A site: Delete 6 of your events", Describe.plainLine("Said no to A site: Delete 6 of your events"))
    }

    @Test fun noKindWordsForAnyKindMarbleNames() {
        for (k in listOf(0, 1, 3, 4, 5, 6, 7, 1111, 9734, 10000, 10002, 10003, 22242, 24242, 27235, 30023, 30078, 4810, 4811, 30810, 30811, 99999)) {
            val s = Describe.signed(k, "Marble")
            assertFalse(s, Regex("\\bkind\\b|\\brelay\\b|\\bevent\\b").containsMatchIn(s))
        }
    }
}
