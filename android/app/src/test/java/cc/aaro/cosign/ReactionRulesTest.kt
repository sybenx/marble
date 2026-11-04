package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionRulesTest {
    private fun r(who: String, at: Long, c: String, target: String = "p", vararg extra: List<String>) = Event(who, at, 7, listOf(listOf("e", target)) + extra, c)
    private val defaults = listOf("👍", "❤️", "😂", "😢", "👀", "🧐")

    @Test fun likeBothWays() {
        assertEquals("❤️", ReactionRules.shown(r("a", 1, "+"))!!.emoji)
        assertEquals("❤️", ReactionRules.shown(r("a", 1, ""))!!.emoji)
        assertEquals("+", ReactionRules.wire("❤️")); assertEquals("😂", ReactionRules.wire("😂"))
        assertNull(ReactionRules.shown(r("a", 1, "-")))
        assertNull(ReactionRules.shown(r("a", 1, "great post")))
        assertNull(ReactionRules.shown(r("a", 1, ":marble:")))
        assertEquals("https://x/m.png", ReactionRules.shown(r("a", 1, ":marble:", "p", listOf("emoji", "marble", "https://x/m.png")))!!.url)
        assertEquals("👍🏽", ReactionRules.shown(r("a", 1, "👍🏽"))!!.emoji)
        assertEquals("👨‍👩‍👧", ReactionRules.shown(r("a", 1, "👨‍👩‍👧"))!!.emoji)
        assertNull(ReactionRules.shown(r("a", 1, "😂😂😂")))
    }

    @Test fun openedPostTally() {
        val t = ReactionRules.tally(listOf(r("a", 1, "😂"), r("b", 1, "😂"), r("c", 1, "+"), r("d", 1, "❤️"), r("e", 1, "🔥"),
            r("f", 1, "😂"), r("f", 2, "-"), r("g", 1, "😂"), r("x", 1, "😂", target = "other")), "p")
        // f's newest is "-" (left out); c's + and d's ❤️ are one like; x reacted to another post.
        assertEquals(listOf("😂" to 3, "❤️" to 2, "🔥" to 1), t.map { it.first.emoji to it.second.size })
    }

    @Test fun trayForANewAccountAndWithRecents() {
        assertEquals(defaults, ReactionRules.tray(emptyList()).map { it.emoji })
        val recents = ReactionRules.recents(listOf(r("me", 1, "🔥", "a"), r("me", 5, "😂", "b"), r("me", 3, "🔥", "c"), r("me", 4, "+", "d"), r("someone", 9, "🎉")), "me")
        assertEquals(listOf("😂", "❤️", "🔥"), recents.map { it.emoji })
        assertEquals(listOf("😂", "❤️", "🔥", "👍", "😢", "👀"), ReactionRules.tray(recents).map { it.emoji })
    }
}
