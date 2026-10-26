package cc.aaro.cosign

import cc.aaro.cosign.ui.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StretchTest {
    private val refresh = 90f * 2.625f
    private val max = (923f / 3f - 72f) * 2.625f   // a Pixel 9a, in pixels

    @Test fun refreshTakesTheSamePull() {
        // Before the cap, the stretch is the pull: the refresh line is where it always was.
        assertEquals(refresh, Stretch.band(refresh, refresh, max), 0.001f)
    }

    @Test fun neverPastTheMost() {
        var s = 0f
        repeat(10_000) { s = Stretch.band(Stretch.unband(s, refresh, max) + 50f * Stretch.GIVE, refresh, max) }
        assertTrue(s < max)
        assertTrue(s > max * 0.99f)
        assertTrue(Stretch.band(1e9f, refresh, max) <= max)
    }

    @Test fun smoothAndGrowing() {
        var last = 0f
        for (p in 1..4000) {
            val s = Stretch.band(p.toFloat(), refresh, max)
            assertTrue(s >= last); assertTrue(s - last <= 1.0001f); last = s
        }
    }

    @Test fun unbandUndoesBand() {
        for (p in listOf(0f, 50f, 200f, 400f, 900f)) assertEquals(p, Stretch.unband(Stretch.band(p, refresh, max), refresh, max), 0.5f)
    }
}
