package cc.aaro.cosign

import cc.aaro.cosign.core.Policy
import cc.aaro.cosign.core.Policy.Found
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changes to your lists made somewhere else (another app with your key, another device with your box), as Recent
 * changes says them: one row for everything since this phone last saw the list, amber when something was lost.
 */
class OutsideTest {
    /** Aaron's bookmarks on Oct 27, and the version another web client wrote on 2026-11-18 without the private part. */
    private val real = (Json.parse(javaClass.classLoader!!.getResource("bookmarks-2026-11-18.json")!!.readText()) as List<*>).map { Event.fromMap(it)!! }
    private val oct27 = real[0]
    private val nov18 = real[1]
    private val me = oct27.pubkey

    private fun list(kind: Int, tags: List<List<String>>, content: String = "", at: Long = 1_800_000_000) = Event("aa".repeat(32), at, kind, tags, content)
    private fun e(n: Int, vararg more: String) = listOf("e", n.toString(16).padStart(64, '0'), *more)
    private fun p(n: Int, vararg more: String) = listOf("p", n.toString(16).padStart(64, '0'), *more)

    @Test fun theRealCaseIsAmberAndSaysThePrivateBookmarksWentNotEntries() {
        assertEquals("9f389a005238", oct27.id.take(12)); assertEquals("698cd1c5c201", nov18.id.take(12))
        val o = Policy.outside(oct27, nov18)!!
        assertEquals("Bookmarks: your private bookmarks were emptied", o.text)
        assertEquals("Your private bookmarks were emptied.", o.alarm)
        assertEquals("Your bookmarks were changed in another app or device", o.title)
        // The new version also dropped the app's own "alt" and "client" tags and an empty title: those aren't bookmarks.
        assertFalse(o.text, o.text.contains("removed") || o.text.contains("added"))
    }

    @Test fun theRealCaseIsAChangeOnceOct27WasSeen() {
        assertEquals(Found.BASELINE, Policy.found(oct27, me, null, now = nov18.createdAt))
        assertEquals(Found.CHANGED, Policy.found(nov18, me, oct27, now = nov18.createdAt))
    }

    @Test fun whatIsIgnored() {
        val now = nov18.createdAt
        assertEquals("the same version", Found.IGNORE, Policy.found(nov18, me, nov18, now))
        assertEquals("older", Found.IGNORE, Policy.found(oct27, me, nov18, now))
        assertEquals("as old", Found.IGNORE, Policy.found(nov18, me, oct27.copy(id = "bb".repeat(32), createdAt = nov18.createdAt), now))
        assertEquals("dated ahead", Found.IGNORE, Policy.found(nov18, me, oct27, now = nov18.createdAt - Policy.AHEAD - 1))
        assertEquals("someone else's (or a site's own account)", Found.IGNORE, Policy.found(nov18, "cc".repeat(32), oct27, now))
        assertEquals("no account", Found.IGNORE, Policy.found(nov18, null, oct27, now))
        assertEquals("not signed", Found.IGNORE, Policy.found(nov18.copy(content = "made up"), me, oct27, now))
        assertEquals("not signed, first seen", Found.IGNORE, Policy.found(nov18.copy(content = "made up"), me, null, now))
        assertEquals("not one of your lists", Found.IGNORE, Policy.found(nov18.copy(kind = 1), me, null, now))
    }

    @Test fun countsAreByEntry() {
        val prev = list(10003, listOf(e(1), e(2), e(3)))
        assertEquals("Bookmarks: 1 added", Policy.outside(prev, list(10003, listOf(e(1), e(2), e(3), e(4))))!!.text)
        val o = Policy.outside(prev, list(10003, listOf(e(1), e(4), e(5))))!!
        assertEquals("Bookmarks: 2 added, 2 removed", o.text)
        assertNull(o.alarm)
        // Five edits elsewhere are one row: only the two ends count.
        assertEquals("Follow list: 1 added", Policy.outside(list(3, listOf(p(1))), list(3, listOf(p(1), p(2))))!!.text)
    }

    @Test fun detailsRewrittenAreNotAddedOrRemoved() {
        val prev = list(3, (1..10).map { p(it, "wss://hint", "pet$it") })
        // Two of ten lose their hints: just "changed".
        val few = list(3, (1..10).map { if (it <= 2) p(it) else p(it, "wss://hint", "pet$it") })
        assertEquals(Policy.Outside("Follow list changed", null, "Your follow list was changed in another app or device"), Policy.outside(prev, few))
        // Most of them: amber.
        val most = list(3, (1..10).map { if (it <= 8) p(it) else p(it, "wss://hint", "pet$it") })
        val o = Policy.outside(prev, most)!!
        assertEquals("Follow list: 8 of 10 entries rewritten", o.text)
        assertEquals("8 of 10 entries rewritten.", o.alarm)
    }

    @Test fun mostOfAListRemovedIsAmberAtHoldsThresholds() {
        val prev = list(3, (1..40).map { p(it) })
        val o = Policy.outside(prev, list(3, (1..30).map { p(it) } + listOf(p(99))))!!
        assertEquals("Follow list: 10 of 40 follows removed, 1 added", o.text)
        assertNotNull(o.alarm)
        assertNull("9 is not most", Policy.outside(prev, list(3, (1..31).map { p(it) }))!!.alarm)
        assertEquals("Follow list: all 40 follows removed", Policy.outside(prev, list(3, emptyList()))!!.text)
        // The relay list and DM relays: any change at all is an alarm (Aaron, 2026-11-21), said as old against new.
        val relays = list(10002, listOf(listOf("r", "wss://a"), listOf("r", "wss://b"), listOf("r", "wss://c"), listOf("r", "wss://d")))
        val half = Policy.outside(relays, list(10002, listOf(listOf("r", "wss://a/"), listOf("r", "wss://b"))))!!
        assertEquals("Relay list: changed from a, b, c and d to a and b", half.text)
        assertEquals("Changed from a, b, c and d to a and b.", half.alarm)
        val one = Policy.outside(relays, list(10002, listOf(listOf("r", "wss://a"), listOf("r", "wss://b"), listOf("r", "wss://c"))))!!
        assertEquals("Changed from a, b, c and d to a, b and c.", one.alarm)
        assertEquals("Relay list: changed from a, b, c and d to a, b and c", one.text)
        // Other relay lists: two or more and half, or all of them.
        val search = list(10007, listOf(listOf("relay", "wss://a"), listOf("relay", "wss://b"), listOf("relay", "wss://c"), listOf("relay", "wss://d")))
        assertNull(Policy.outside(search, list(10007, listOf(listOf("relay", "wss://a"), listOf("relay", "wss://b"), listOf("relay", "wss://c"))))!!.alarm)
    }

    /** 2026-11-21 01:44:57 UTC: Vector wrote its own DM relays (kind 10050) over Aaron's from 2026-10-25. */
    private val T = 1_800_000_000L
    private fun dm(vararg urls: String) = list(10050, urls.map { listOf("relay", it) })
    private val oct25 = dm("wss://haven.aaro.cc/inbox", "wss://herbstmeister.com/inbox", "wss://relay.vantis.ninja", "wss://herbstmeister.com", "wss://bothy-relay.sybenx.workers.dev")
    private val vector = dm("wss://jskitty.com/nostr", "wss://asia.vectorapp.io/nostr", "wss://nostr.computingcache.com", "wss://relay.ditto.pub")

    @Test fun dmRelaysAreKeptAndTheirOverwriteIsAmberWithTheRelaysNamed() {
        assertTrue(10050 in Policy.ARCHIVED); assertTrue(10051 in Policy.ARCHIVED)
        val o = Policy.outside(oct25, vector)!!
        assertEquals("Your DM relays were changed in another app or device", o.title)
        assertEquals("Changed from haven.aaro.cc/inbox, herbstmeister.com/inbox, relay.vantis.ninja, herbstmeister.com and bothy-relay.sybenx.workers.dev " +
            "to jskitty.com/nostr, asia.vectorapp.io/nostr, nostr.computingcache.com and relay.ditto.pub.", o.alarm)
        assertTrue(o.text, o.text.startsWith("DM relays: changed from haven.aaro.cc/inbox"))
        assertEquals("Remove every relay from your DM relays: a and b", Policy.question(dm(), dm("wss://a", "wss://b"), T)!!.says)
        assertEquals("DM relays: every relay removed (a and b)", Policy.outside(dm("wss://a", "wss://b"), dm())!!.text)
    }

    @Test fun signingAnOverwriteOfTheDmRelaysAsksAtLevel1WithOldAndNew() {
        val q = Policy.question(vector, oct25, T)!!
        assertEquals(1, q.level)
        assertEquals("Change your DM relays from haven.aaro.cc/inbox, herbstmeister.com/inbox, relay.vantis.ninja, herbstmeister.com and bothy-relay.sybenx.workers.dev " +
            "to jskitty.com/nostr, asia.vectorapp.io/nostr, nostr.computingcache.com and relay.ditto.pub", q.says)
        // Every change to the DM relays is asked about (Aaron, 2026-11-21): adding one, dropping one of several.
        assertEquals("Add x to your DM relays", Policy.question(dm("wss://haven.aaro.cc/inbox", "wss://x"), dm("wss://haven.aaro.cc/inbox"), T)!!.says)
        assertEquals("Remove c from your DM relays", Policy.question(dm("wss://a", "wss://b"), dm("wss://a", "wss://b", "wss://c"), T)!!.says)
        assertNull(Policy.question(dm("wss://b", "wss://a/"), dm("wss://a", "wss://b"), T))
        // Its only relay swapped for another: asked, as for any other relay list.
        assertEquals("Change your group invite relays from a to x", Policy.question(list(10051, listOf(listOf("relay", "wss://x"))), list(10051, listOf(listOf("relay", "wss://a"))), T)!!.says)
        // With no earlier version seen, an app's first list would replace one signed elsewhere: asked too.
        assertEquals("Set your DM relays to jskitty.com/nostr, asia.vectorapp.io/nostr, nostr.computingcache.com and relay.ditto.pub", Policy.question(vector, null, T)!!.says)
        // A child key's relays are its app's to set.
        assertNull(Policy.question(vector, oct25, T, child = true))
        assertNull(Policy.question(vector, null, T, child = true))
    }

    @Test fun aRelayListPutBackGoesToTheRelaysBothVersionsName() {
        assertTrue(Policy.isRelayList(10050)); assertTrue(Policy.isRelayList(10002)); assertFalse(Policy.isRelayList(3))
        assertEquals(listOf("wss://jskitty.com/nostr", "wss://asia.vectorapp.io/nostr", "wss://nostr.computingcache.com", "wss://relay.ditto.pub"), Policy.relaysIn(vector))
        assertEquals(listOf("wss://a"), Policy.relaysIn(dm("wss://a/", "wss://a", "ws://local")))
    }

    @Test fun privateEntriesLostAreAskedAboutOnlyByADeviceThatCanReadThem() {
        val bm = { n: Int -> (1..n).map { listOf("e", "%064x".format(it)) } }
        // Some of a short private list gone: asked.
        assertEquals("Remove 2 of your 3 private bookmarks", Policy.privateQuestion(10003, bm(3), bm(1))!!.says)
        assertEquals(1, Policy.privateQuestion(10003, bm(4), bm(2))!!.level)
        // One of three, or one of two kept: everyday.
        assertNull(Policy.privateQuestion(10003, bm(3), bm(2)))
        assertNull(Policy.privateQuestion(10003, bm(2), bm(2) + listOf(listOf("e", "f".repeat(64)))))
        // A long list: 10 and a fifth, as for the public entries.
        assertEquals("Remove 10 of your 40 private bookmarks", Policy.privateQuestion(10003, bm(40), bm(30))!!.says)
        assertNull(Policy.privateQuestion(10003, bm(60), bm(51)))
        // Which part counts as private: encrypted content, not blank and not an old follow list's relays.
        assertTrue(Policy.hasPrivate(list(10003, emptyList(), "c2VhbGVk")))
        assertFalse(Policy.hasPrivate(list(10003, emptyList(), "")))
        assertFalse(Policy.hasPrivate(list(3, emptyList(), "{\"wss://a\":{}}")))
    }

    @Test fun severalLossesInOneRow() {
        val prev = list(10003, (1..12).map { e(it) }, "c2VhbGVk")
        val o = Policy.outside(prev, list(10003, emptyList()))!!
        assertEquals("Bookmarks: your private bookmarks were emptied, all 12 bookmarks removed", o.text)
        assertEquals("Your private bookmarks were emptied. All 12 bookmarks removed.", o.alarm)
    }

    @Test fun privatePartChangedIsSaidPlainly() {
        assertEquals("Mute list: your private mute list changed", Policy.outside(list(10000, listOf(p(1)), "b2xk"), list(10000, listOf(p(1)), "bmV3"))!!.text)
        assertTrue(Policy.outside(list(10000, listOf(p(1)), "b2xk"), list(10000, listOf(p(1)), ""))!!.text.contains("your private mute list was emptied"))
    }

    @Test fun oldRelaysInAFollowListAreNoPrivatePart() {
        val prev = list(3, listOf(p(1)), """{"wss://nos.lol":{"read":true,"write":true}}""")
        assertEquals(Policy.Outside("Follow list changed", null, "Your follow list was changed in another app or device"), Policy.outside(prev, list(3, listOf(p(1)))))
    }

    @Test fun profile() {
        val prev = list(0, emptyList(), """{"name":"Aaron","picture":"https://x/p.png","about":"hi"}""")
        assertEquals(Policy.Outside("Profile changed", null, "Your profile was changed in another app or device"),
            Policy.outside(prev, list(0, emptyList(), """{"name":"Aaron B","picture":"https://x/p.png","about":"hi"}""")))
        val wiped = Policy.outside(prev, list(0, emptyList(), """{"name":"Aaron"}"""))!!
        assertEquals("Profile: your profile was wiped (picture, about)", wiped.text)
        assertEquals("Your profile was wiped (picture, about).", wiped.alarm)
        assertEquals("Your profile was wiped (name, picture, about).", Policy.outside(prev, list(0, emptyList(), "{}"))!!.alarm)
    }

    @Test fun nothingDifferentIsNoRow() {
        val l = list(10003, listOf(e(1)), "c2VhbGVk")
        assertNull(Policy.outside(l, l.copy(createdAt = l.createdAt + 5)))
    }

    @Test fun noJargon() {
        val texts = listOf(Policy.outside(oct27, nov18)!!) + listOf(0, 3, 10000, 10002, 10003, 30001).map { k ->
            Policy.outside(list(k, (1..12).map { e(it) }, if (k == 0) """{"name":"a","about":"b"}""" else "c2VhbGVk"), list(k, emptyList()))!!
        }
        for (o in texts) for (s in listOfNotNull(o.text, o.alarm, o.title))
            assertFalse(s, Regex("""\b(kind|event|npub|content|tag)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s))
    }
}
