package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayFeedsTest {
    @Test fun addresses() {
        assertEquals(RelayFeeds.Relay("wss://pyramid.fiatjaf.com/popular", "pyramid.fiatjaf.com/popular"), RelayFeeds.of("pyramid.fiatjaf.com/popular"))
        assertEquals("wss://nos.lol", RelayFeeds.of("wss://NOS.lol/")?.url)
        assertNull(RelayFeeds.of("not a relay")); assertNull(RelayFeeds.of("https://nos.lol"))
        assertEquals("pyramid.fiatjaf.com/popular", RelayFeeds.fromLink("https://marble.syb.lol/relay/pyramid.fiatjaf.com/popular")?.address)
        assertNull(RelayFeeds.fromLink("https://marble.syb.lol/nevent1abc"))
        assertEquals("https://marble.syb.lol/relay/nos.lol", RelayFeeds.link(RelayFeeds.of("nos.lol")!!))
    }

    @Test fun lists() {
        val fav = Event("me", 1, 10012, listOf(listOf("relay", "wss://nos.lol"), listOf("relay", "wss://nos.lol/"), listOf("t", "x")), "")
        assertEquals(listOf("wss://nos.lol"), RelayFeeds.relaysIn(fav).map { it.url })
        val added = RelayFeeds.with(fav.tags, RelayFeeds.of("nostr.wine")!!, true)
        assertEquals(listOf("wss://nos.lol", "wss://nostr.wine"), RelayFeeds.relaysIn(fav.copy(tags = added)).map { it.url })
        assertEquals(listOf(listOf("t", "x")), RelayFeeds.with(fav.tags, RelayFeeds.of("nos.lol")!!, false))
        val sets = RelayFeeds.sets(listOf(Event("me", 1, 30002, listOf(listOf("d", "b"), listOf("title", "Builders"), listOf("relay", "wss://a.com")), ""),
            Event("me", 2, 30002, listOf(listOf("d", "b"), listOf("title", "Bitcoin builders"), listOf("relay", "wss://a.com"), listOf("relay", "wss://b.com")), ""),
            Event("me", 3, 30002, listOf(listOf("d", "empty")), "")), "me")
        assertEquals(1, sets.size); assertEquals("Bitcoin builders", sets[0].title); assertEquals(2, sets[0].relays.size)
    }

    @Test fun relaysYourPeopleUse() {
        fun list(who: String, vararg r: List<String>) = Event(who, 1, 10002, r.toList(), "")
        val got = RelayFeeds.peopleUse(listOf(list("a", listOf("r", "wss://x.com"), listOf("r", "wss://big.com")), list("b", listOf("r", "wss://x.com", "write")),
            list("c", listOf("r", "wss://x.com", "read"), listOf("r", "wss://y.com")), list("stranger", listOf("r", "wss://y.com"))),
            setOf("a", "b", "c"), kept = emptySet(), common = setOf("wss://big.com"))
        // x: a and b write there (c only reads); y: one of your people only; big: everyone's.
        assertEquals(listOf("wss://x.com" to listOf("a", "b")), got.map { it.first.url to it.second })
    }
}
