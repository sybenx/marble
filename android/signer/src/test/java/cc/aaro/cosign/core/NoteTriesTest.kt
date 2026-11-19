package cc.aaro.cosign.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTriesTest {
    @Test fun aNoteThatWorkedIsNotDoneAgain() {
        val t = NoteTries()
        assertTrue(t.begin("a")); t.worked("a")
        assertTrue(t.has("a")); assertFalse(t.begin("a"))
    }
    @Test fun aFailedNoteIsTriedAgainThenGivenUp() {
        val t = NoteTries(most = 3)
        assertTrue(t.begin("a")); assertFalse(t.failedOnce("a")); assertFalse(t.has("a"))
        assertTrue(t.begin("a")); assertFalse(t.failedOnce("a"))
        assertTrue(t.begin("a")); assertTrue(t.failedOnce("a"))
        assertTrue(t.has("a")); assertFalse(t.begin("a"))
    }
    @Test fun aRetryThatWorksIsDone() {
        val t = NoteTries()
        t.begin("a"); t.failedOnce("a")
        assertTrue(t.begin("a")); t.worked("a"); assertTrue(t.has("a"))
    }
    @Test fun onlyOneCheckWorksOnANoteAtATime() {
        val t = NoteTries()
        assertTrue(t.begin("a")); assertFalse(t.begin("a"))
        t.failedOnce("a"); assertTrue(t.begin("a"))
    }
}
