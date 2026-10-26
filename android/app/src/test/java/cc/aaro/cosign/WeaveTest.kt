package cc.aaro.cosign

import cc.aaro.cosign.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover's posts: popular, newest first, with Special Follow people woven in near the top. */
class WeaveTest {
    private fun note(pk: String, at: Long, n: Int) = Event(pk.repeat(64).take(64), at, 1, emptyList(), "note $n").withId()

    @Test fun woven() {
        val popular = (1..30).map { note("a", 1000L + it, it) }
        val special = (1..8).map { note(if (it % 2 == 0) "b" else "c", 900L + it, 100 + it) }
        val w = Calm.weave(popular, special)
        // Popular newest first, all of it.
        assertEquals(popular.sortedByDescending { it.createdAt }.map { it.id }, w.filter { !it.second }.map { it.first.id })
        // Every third of the first twelve is yours, then every sixth; two of anyone's.
        val firstTwelve = w.take(12).map { it.second }
        assertEquals(listOf(true, false, false, true, false, false), firstTwelve.take(6))
        assertEquals(4, w.count { it.second })
        assertTrue(w.filter { it.second }.groupingBy { it.first.pubkey }.eachCount().values.all { it <= 2 })
    }

    @Test fun nothingPopular() {
        val special = (1..3).map { note("b", 900L + it, it) }
        assertEquals(2, Calm.weave(emptyList(), special).size)
    }
}
