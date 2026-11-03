package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleTest {
    private fun list(who: String, at: Long, vararg follows: String) = Event(who, at, 3, follows.map { listOf("p", it) }, "")
    private val me = "me"; private val follows = setOf("a", "b", "c")

    @Test fun twoOfYourFollowsBringSomeoneIn() {
        val counts = Circle.counts(listOf(list("a", 1, "x", "y"), list("b", 1, "x"), list("c", 1, "z")), follows)
        fun has(pk: String) = Circle.has(pk, me, follows, counts) { false }
        assertTrue(has("x")); assertFalse(has("y")); assertFalse(has("z"))
        assertTrue(has(me)); assertTrue(has("a"))
    }

    @Test fun onlyEachFollowsNewestListCountsOnce() {
        // b's newer list dropped x; a lists x twice; a stranger's list doesn't count; nobody counts themselves.
        val counts = Circle.counts(listOf(list("a", 1, "x", "x"), list("b", 1, "x"), list("b", 2, "y"), list("stranger", 3, "x"), list("c", 1, "c")), follows)
        assertEquals(1, counts["x"]); assertEquals(1, counts["y"]); assertEquals(null, counts["c"])
        assertFalse(Circle.has("x", me, follows, counts) { false })
    }

    @Test fun notSpamBringsSomeoneIn() {
        val counts = Circle.counts(listOf(list("a", 1, "x")), follows)
        assertFalse(Circle.has("x", me, follows, counts) { false })
        assertTrue(Circle.has("x", me, follows, counts) { it == "x" })
    }
}
