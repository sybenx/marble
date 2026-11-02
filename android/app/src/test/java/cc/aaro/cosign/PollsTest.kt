package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollsTest {
    private fun poll(type: String = "singlechoice", ends: Long? = 1000) = Event("author", 1, 1068,
        listOf(listOf("option", "a", "Saturday"), listOf("option", "b", "Sunday"), listOf("option", "c", "Friday"), listOf("polltype", type)) +
            listOfNotNull(ends?.let { listOf("endsAt", it.toString()) }), "Best time?", id = "p1")
    private fun vote(who: String, at: Long, vararg opts: String) = Event(who, at, 1018, listOf(listOf("e", "p1")) + opts.map { listOf("response", it) }, "")

    @Test fun parses() {
        val p = Polls.of(poll())!!
        assertEquals("Best time?", p.question); assertEquals(listOf("a", "b", "c"), p.options.map { it.first }); assertEquals(1000L, p.endsAt)
        assertNull(Polls.of(Event("x", 1, 1068, listOf(listOf("option", "a", "Only one")), "?")))
    }

    @Test fun newestVoteBeforeTheEndCountsOnce() {
        val p = Polls.of(poll())!!
        val t = Polls.tally(p, listOf(vote("x", 10, "a"), vote("x", 20, "b"), vote("y", 30, "a", "b"), vote("z", 2000, "c"), vote("w", 40, "nope")))
        // x changed to b; y's single-choice vote counts its first; z voted after the end; w's choice isn't an option.
        assertEquals(mapOf("b" to 1, "a" to 1), t.counts); assertEquals(2, t.voters)
        assertEquals(0.5f, t.share("a"))
    }

    @Test fun multipleChoice() {
        val p = Polls.of(poll("multiplechoice", null))!!
        val t = Polls.tally(p, listOf(vote("x", 10, "a", "b"), vote("y", 30, "b")))
        assertEquals(mapOf("a" to 1, "b" to 2), t.counts); assertEquals(2, t.voters)
        assertTrue(!p.ended(99999))
    }
}
