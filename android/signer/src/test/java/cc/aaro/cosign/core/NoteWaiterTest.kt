package cc.aaro.cosign.core

import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.Held
import cc.aaro.keykin.core.KeykinException
import cc.aaro.keykin.core.Me
import cc.aaro.keykin.core.Piece
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The held `notes_wait` loop (Keykin SPEC 11.6): answers go to the same handler, errors back off, an older box ends it. */
class NoteWaiterTest {
    private val me = Me(ByteArray(32) { 1 }, "Pixel 9a", null)
    private val h = Held(Piece(emptyList(), 1, BigInteger.ONE), "b".repeat(64), "c".repeat(64), "http://box")
    private fun note(id: String, kind: String = "bunker") = JSONObject().put("id", id).put("kind", kind).put("text", "").put("at", 1).put("tap", kind == "cycle")
    private fun answer(vararg ids: String) = JSONObject().put("ok", true).put("notes", JSONArray(ids.map { note(it) }))

    @Test fun theRequestIsANotesWaitAndItsNotesComeBackAsNotes() {
        var sent: JSONObject? = null
        val got = Device.notesWait(me, h, 25, listOf("a".repeat(32)), { sent = it; answer("1".repeat(32)) })
        val body = sent!!.getJSONObject("body")
        assertEquals("notes_wait", body.getString("op")); assertEquals(h.npub, body.getString("account"))
        assertEquals(25, body.getInt("wait")); assertEquals("a".repeat(32), body.getJSONArray("seen").getString(0))
        assertEquals(listOf("1".repeat(32)), got.map { it.id })
        assertTrue(Device.WAIT_READ_MS > Device.WAIT_MOST * 1000 + 10_000)
    }

    @Test fun anOlderBoxEndsTheLoopForGood() {
        // What a box without notes_wait says, through the same Device call the loop makes.
        val old: (JSONObject) -> JSONObject = { JSONObject().put("error", "unknown_op") }
        var took = 0
        val w = NoteWaiter({ seen -> Device.notesWait(me, h, 25, seen, old) }, { took++ }, pause = {})
        assertNull(w.once())
        assertTrue(w.unsupported)
        w.start()
        assertFalse(w.running)
        assertEquals(0, took)
        // `unknown_call` is taken the same way.
        val w2 = NoteWaiter({ throw KeykinException("unknown_call") }, {}, pause = {})
        assertNull(w2.once()); assertTrue(w2.unsupported)
    }

    @Test fun notesFromAWaitReachTheHandlerAndAreSentBackAsSeen() {
        val asked = mutableListOf<List<String>>()
        val replies = ArrayDeque(listOf(listOf("n1"), listOf("n1", "n2"), emptyList()))
        val handed = mutableListOf<List<String>>()
        var t = 0L
        val w = NoteWaiter({ seen -> asked += seen; t += 25_000; replies.removeFirst().map { Device.Note(it, "bunker", "", 0, false) } },
            { list -> handed += list.map { it.id } }, pause = {}, millis = { t })
        assertEquals(0L, w.once()); assertEquals(0L, w.once()); assertEquals(0L, w.once())
        assertEquals(listOf(emptyList(), listOf("n1"), listOf("n1", "n2")), asked)
        assertEquals(listOf(listOf("n1"), listOf("n1", "n2")), handed)      // an empty answer is not handed on
    }

    @Test fun errorsBackOffAndAnAnswerResetsIt() {
        var fail = true
        val w = NoteWaiter({ if (fail) throw KeykinException("no_box") else emptyList() }, {}, pause = {}, millis = { 0 })
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), List(6) { w.once() })
        assertFalse(w.unsupported)
        // Something that is not the box's word (a broken answer) backs off the same way.
        val w2 = NoteWaiter({ throw IllegalStateException("bad json") }, {}, pause = {})
        assertEquals(5_000L, w2.once())
        var t = 0L
        val w3 = NoteWaiter({ if (fail) throw KeykinException("no_box") else { t += 25_000; emptyList() } }, {}, pause = {}, millis = { t })
        assertEquals(5_000L, w3.once()); fail = false
        assertEquals(0L, w3.once())
        fail = true
        assertEquals(5_000L, w3.once())
    }

    @Test fun anUnheldAnswerWithNothingNewDoesNotSpin() {
        // Back in no time with only what this phone has already: pause before asking again.
        val w = NoteWaiter({ listOf(Device.Note("n1", "cycle", "", 0, true)) }, {}, pause = {}, millis = { 0 })
        assertEquals(0L, w.once())                               // new: handled at once
        assertEquals(NoteWaiter.FIRST_BACKOFF, w.once())         // the same again at once: wait a little
    }

    @Test fun theLoopRunsOnItsOwnThreadAndStops() {
        val handled = CountDownLatch(1)
        var thread: String? = null
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val w = NoteWaiter({ when (calls.incrementAndGet()) { 1 -> listOf(Device.Note("n1", "bunker", "", 0, false)); else -> throw KeykinException("unknown_op") } },
            { thread = Thread.currentThread().name; handled.countDown() })
        w.start(); w.start()                                        // a second start is nothing
        assertTrue(handled.await(5, TimeUnit.SECONDS))
        assertEquals("keykin-notes-wait", thread)
        val until = System.currentTimeMillis() + 5000
        while (w.running && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(w.running); assertTrue(w.unsupported); assertEquals(2, calls.get())

        // stop() ends a loop that is pausing.
        val paused = CountDownLatch(1)
        val w2 = NoteWaiter({ throw KeykinException("no_box") }, {}, pause = { paused.countDown(); Thread.sleep(it) })
        w2.start()
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        w2.stop()
        assertFalse(w2.running)
    }
}
