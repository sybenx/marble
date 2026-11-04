package cc.aaro.cosign.reader

import cc.aaro.cosign.ui.Body
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's side of the Reader sync and read-state rules (the re-check of 322d577), beyond the shared vectors. */
class ReaderRulesTest {
    private val w = listOf("wss://a", "wss://b", "wss://c")

    @Test fun writesOnlyWhenMostWriteRelaysAnswered() {
        assertTrue(ReaderRules.mayWrite(w, setOf("wss://a", "wss://b/"), null, 0))
        // One fast relay, or read relays that never hold the copy, isn't enough.
        assertFalse(ReaderRules.mayWrite(w, setOf("wss://a"), null, 0))
        assertFalse(ReaderRules.mayWrite(w, setOf("wss://read1", "wss://read2", "wss://read3"), null, 0))
        assertFalse(ReaderRules.mayWrite(emptyList(), setOf("wss://a"), null, 0))
        // Never under a newer copy this device has seen: none came back, or an older one did.
        assertFalse(ReaderRules.mayWrite(w, w.toSet(), null, 100))
        assertFalse(ReaderRules.mayWrite(w, w.toSet(), 90, 100))
        assertTrue(ReaderRules.mayWrite(w, w.toSet(), 100, 100))
    }

    @Test fun ageingOutNeverReadsAnUnreadItem() {
        val day = 86_400L
        // A (older, never opened) and B (opened, aged out): upto rises only to just before A.
        val got = ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 5 * day, read = false, aged = false), ReaderRules.Aged("s", 7 * day, read = true, aged = true)), emptyMap())
        assertEquals(mapOf("s" to 5 * day - 1), got)
        val seen = ReaderRules.Seen(emptySet(), got, emptyMap())
        assertFalse(ReaderRules.isRead("A", "s", 5 * day, seen))
        // Nothing unread: up to the aged item. Not lower than it already is.
        assertEquals(mapOf("s" to 7 * day), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, true)), emptyMap()))
        assertEquals(emptyMap<String, Long>(), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, true)), mapOf("s" to 8 * day)))
        assertEquals(emptyMap<String, Long>(), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, false)), emptyMap()))
    }

    @Test fun markAllReadAfterAKeptUnreadMarkReadsIt() {
        // Kept unread at 100 on another device; this one marked the source all read at 200 without having seen the mark.
        assertTrue(ReaderRules.isRead("k", "s", 50, ReaderRules.Seen(emptySet(), mapOf("s" to 200), mapOf("k" to 100))))
        // Kept unread after the mark-all: unread.
        assertFalse(ReaderRules.isRead("k", "s", 50, ReaderRules.Seen(emptySet(), mapOf("s" to 200), mapOf("k" to 300))))
    }

    @Test fun fitSeenKeepsTheNewestDayAndThenTrimsMarks() {
        val now = 3_000_000L
        val upto = JSONObject().apply { repeat(900) { put("people:" + "%064x".format(it), now - it) } }
        val unread = JSONObject().apply { repeat(200) { put("%016x".format(it), -(now - it)) }; put("kept", now - 5) }
        val o = JSONObject().put("v", 1).put("at", now).put("days", JSONObject().put("33", "a b").put("34", "c d")).put("upto", upto).put("unread", unread)
        val out = ReaderRules.fitSeen(o, now)
        assertTrue(out.toString().toByteArray().size <= ReaderRules.MAX_SEEN)
        assertEquals(setOf("34"), out.getJSONObject("days").keys().asSequence().toSet())
        // A positive mark (kept unread) is never what goes; the newest read-up-tos stay longest.
        assertTrue(out.getJSONObject("unread").has("kept"))
        assertTrue(out.getJSONObject("upto").has("people:" + "%064x".format(0)))
    }

    @Test fun readItemsStayUntilTheyAge() {
        val now = 10 * 86_400L
        assertTrue(ReaderRules.stays(read = true, at = now - 3600, type = "news", starred = false, now = now))
        assertFalse(ReaderRules.stays(read = true, at = now - 2 * 86_400, type = "news", starred = false, now = now))
        assertTrue(ReaderRules.stays(read = true, at = now - 2 * 86_400, type = "news", starred = true, now = now))
        assertTrue(ReaderRules.stays(read = false, at = now - 9 * 86_400, type = "news", starred = false, now = now))
    }

    @Test fun cutsAtAWord() {
        val s = "one two three four five six seven eight nine ten eleven twelve"
        val c = Body.cut(s, 30)
        assertTrue(c, c.endsWith("…") && s.startsWith(c.removeSuffix("…")) && s[c.length - 1] == ' ')
        // A space only early on: still at the space, not mid-word.
        assertEquals("ab…", Body.cut("ab " + "x".repeat(40), 20))
        assertEquals("x".repeat(20) + "…", Body.cut("x".repeat(40), 20))
    }
}
