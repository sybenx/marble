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

    @Test fun ageingOutNeverReadsAnUnreadItem() {
        val day = 86_400L
        // A (older, never opened) and B (opened, aged out): upto rises only to just before A.
        val got = ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 5 * day, read = false, aged = false), ReaderRules.Aged("s", 7 * day, read = true, aged = true)), emptyMap())
        assertEquals(mapOf("s" to 5 * day - 1), got)
        val seen = ReaderRules.Seen(emptyMap(), got, emptyMap())
        assertFalse(ReaderRules.isRead("A", "s", 5 * day, seen))
        // Nothing unread: up to the aged item. Not lower than it already is.
        assertEquals(mapOf("s" to 7 * day), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, true)), emptyMap()))
        assertEquals(emptyMap<String, Long>(), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, true)), mapOf("s" to 8 * day)))
        assertEquals(emptyMap<String, Long>(), ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 7 * day, true, false)), emptyMap()))
    }

    @Test fun markAllReadAfterAKeptUnreadMarkReadsIt() {
        // Kept unread at 100 on another device; this one marked the source all read at 200 without having seen the mark.
        assertTrue(ReaderRules.isRead("k", "s", 50, ReaderRules.Seen(emptyMap(), mapOf("s" to 200), mapOf("k" to 100))))
        assertFalse(ReaderRules.isRead("k", "s", 50, ReaderRules.Seen(emptyMap(), mapOf("s" to 200), mapOf("k" to 300))))
    }

    @Test fun aPeopleFolderIsReadUpToOnce() {
        // Mark all as read on Your people: one folder entry, read for everyone in it.
        assertTrue(ReaderRules.isRead("k", "people:P", 50, ReaderRules.Seen(emptyMap(), mapOf("folder:people" to 100), emptyMap()), "people"))
        assertFalse(ReaderRules.isRead("k", "people:P", 50, ReaderRules.Seen(emptyMap(), mapOf("folder:people" to 100), emptyMap()), "first"))
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

    @Test fun aKeptUnreadMarkHoldsTheRiseEvenWhenItsItemIsntLoaded() {
        val day = 86_400L
        // K (day 1) kept unread on day 2, missing from this load; B (day 3) read and aged out: upto stays under the mark.
        val got = ReaderRules.ageUpto(listOf(ReaderRules.Aged("s", 3 * day, read = true, aged = true)), emptyMap(), marks = listOf(2 * day, -5 * day))
        assertEquals(mapOf("s" to 2 * day - 1), got)
        assertFalse(ReaderRules.isRead("K", "s", day, ReaderRules.Seen(emptyMap(), got, mapOf("K" to 2 * day))))
    }

    @Test fun rowsOnScreenStayThroughAReload() {
        val now = 10 * 86_400L
        assertTrue(ReaderRules.stays(read = true, at = now - 2 * 86_400, type = "news", starred = false, now = now, onScreen = true))
        assertFalse(ReaderRules.stays(read = true, at = now - 2 * 86_400, type = "news", starred = false, now = now, onScreen = false))
    }

    @Test fun aWarnedPostKeepsItsOwnWordsForSharing() {
        val e = cc.aaro.qrst.core.Event("%064x".format(1), 1, 1, listOf(listOf("content-warning", "spoilers")), "The ending was a surprise").withId()
        assertEquals("spoilers: show", Body.line(e))
        assertEquals("The ending was a surprise", Body.line(e, veil = false))
    }

    /** recheck4 #10: a mark on one source's item holds only that source; one whose item isn't known holds all. */
    @Test fun aMarkHoldsItsOwnSource() {
        val items = listOf(ReaderRules.Aged("a", 30, true, true), ReaderRules.Aged("b", 30, true, true))
        assertEquals(mapOf("a" to 19L, "b" to 30L), ReaderRules.ageUpto(items, emptyMap(), mapOf("a" to listOf(20L))))
        assertEquals(mapOf("a" to 19L, "b" to 19L), ReaderRules.ageUpto(items, emptyMap(), mapOf("" to listOf(20L))))
    }
}
