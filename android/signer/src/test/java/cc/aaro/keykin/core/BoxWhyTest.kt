package cc.aaro.keykin.core

import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The box's reason travels with its error as `why` (Keykin SPEC 11.6, The tap), cleaned, and only when it gave one. */
class BoxWhyTest {
    private val me = Me(ByteArray(32) { 1 }, "Pixel 9a", null)
    private val h = Held(Piece(emptyList(), 1, BigInteger.ONE), "b".repeat(64), "c".repeat(64), "http://box")
    private fun failing(answer: JSONObject): KeykinException = try { Device.block(me, h, "d".repeat(64)) { answer }; fail(); throw AssertionError() } catch (e: KeykinException) { e }

    @Test fun needsTapCarriesTheBoxsWords() {
        val e = failing(JSONObject().put("error", "needs_tap").put("why", "Empty your follow list (it has 3 follows)"))
        assertEquals("needs_tap", e.code); assertEquals("Empty your follow list (it has 3 follows)", e.why)
        assertEquals("needs_tap: Empty your follow list (it has 3 follows)", e.message)
    }

    @Test fun controlCharactersAndLengthAreTrimmed() {
        val e = failing(JSONObject().put("error", "needs_tap").put("why", "Wipe\nyour\u0007 profile" + "x".repeat(400)))
        assertEquals(300, e.why!!.length); assertEquals("Wipe your  profile", e.why!!.take(18))
    }

    @Test fun noWhyNoWords() {
        val e = failing(JSONObject().put("error", "needs_tap"))
        assertEquals("needs_tap", e.code); assertNull(e.why); assertEquals("needs_tap", e.message)
        assertNull(failing(JSONObject().put("error", "blocked").put("why", "  ")).why)
    }
}
