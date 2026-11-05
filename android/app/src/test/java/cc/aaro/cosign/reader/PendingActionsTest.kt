package cc.aaro.cosign.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reader's Undo: one per item, held by the account; each ends exactly once, and none is dropped. */
class PendingActionsTest {
    @Test fun runsOutAndCommitsOnce() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var posted = 0
        p.offer("post:a", "Posting publicly", {}, commit = { posted++ })
        delay(150)
        assertEquals(1, posted); assertNull(p.shown.value)
    }

    @Test fun anotherForTheSameItemCommitsTheFirst() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var posted = 0
        p.offer("post:a", "Posting publicly", {}, commit = { posted++ })
        p.offer("post:a", "Posting publicly", {}, commit = { posted++ })
        assertEquals(1, posted)
        delay(150)
        assertEquals(2, posted)
    }

    @Test fun anotherItemDoesntDropIt() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var posted = 0; var unstarred = 0
        p.offer("post:a", "Posting publicly", {}, commit = { posted++ })
        p.offer("star:b", "Unstarred", { unstarred++ })
        delay(150)
        assertEquals(1, posted); assertEquals(0, unstarred)
    }

    @Test fun undoIsOnceAndNeverCommits() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var posted = 0; var undone = 0
        val a = p.offer("post:a", "Posting publicly", { undone++ }, commit = { posted++ })
        p.undo(a); p.undo(a)
        delay(150)
        assertEquals(0, posted); assertEquals(1, undone)
    }

    @Test fun aSwitchOfAccountCancels() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var posted = 0; var undone = 0
        p.offer("post:a", "Posting publicly", { undone++ }, commit = { posted++ })
        p.cancelAll()
        delay(150)
        assertEquals(0, posted); assertEquals(0, undone); assertNull(p.shown.value)
    }

    @Test fun aSecondChangeToAnItemFoldsIntoOneUndo() = runBlocking {
        val p = PendingActions(this, waitMs = 50); var undone = 0; var committed = 0
        p.offer("note:a", "Unstarred", { undone++ }, { committed++ }, merge = true)
        val b = p.offer("note:a", "Kept in Notes", { undone += 10 }, { committed += 10 }, merge = true)
        assertEquals(0, committed)
        p.undo(b)
        delay(150)
        assertEquals(10, undone); assertEquals(0, committed)
    }

    @Test fun theNewestIsShownAndACancelSaysSo() = runBlocking {
        val p = PendingActions(this, waitMs = 1000); var said = 0
        p.offer("a", "first", {})
        val second = p.offer("b", "second", {}, cancel = { said++ })
        assertEquals(second, p.shown.value)
        p.cancelAll()
        assertEquals(1, said)
    }
}
