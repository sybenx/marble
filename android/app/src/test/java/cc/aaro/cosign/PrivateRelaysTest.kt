package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.aaro.qrst.core.Event

class PrivateRelaysTest {
    @Test fun parseAndLabel() {
        assertEquals("wss://archive.example.com", PrivateRelays.parse(" Archive.Example.com/ "))
        assertEquals("ws://192.168.1.5:7777", PrivateRelays.parse("ws://192.168.1.5:7777"))
        assertNull(PrivateRelays.parse("https://example.com"))
        assertNull(PrivateRelays.parse(""))
        assertEquals("archive.example.com/me", PrivateRelays.label("wss://archive.example.com/me/"))
    }

    @Test fun addRemoveAndMerge() {
        val a = PrivateRelays(null)
        assertEquals("archive.example.com", a.add("archive.example.com")!!.label)
        assertEquals(listOf("wss://archive.example.com"), a.urls())
        val b = PrivateRelays(null)
        assertTrue(b.take(a.json()).not())
        assertEquals(a.urls(), b.urls())
        b.remove("wss://archive.example.com/")
        assertTrue(b.urls().isEmpty())
        assertTrue(b.all.value.single().gone)
        // The removal (newer) wins on the other device; it has nothing newer to send back.
        assertFalse(a.take(b.json()))
        assertTrue(a.urls().isEmpty())
        assertFalse(a.take("{\"v\":2,\"relays\":[]}"))
    }

    // What Chronicle sends back for "new app" (any one of the words, newest first): a probe of its search took
    // "gm, working on the app today" and "fiatjaf shipped a new khatru" for it. Only posts with both words are shown.
    private fun post(at: Long, text: String, vararg tags: List<String>) = Event("a".repeat(64), at, 1, tags.toList(), text)
    @Test fun archiveShowsOnlyPostsWithEveryWord() {
        val sent = listOf(
            post(500, "gm, working on the app today"),
            post(400, "fiatjaf shipped a new khatru"),
            post(300, "The new Marble app is out!"),
            post(200, "an app that is new to me"),
            post(100, "Release notes", listOf("title", "A new app for notes")),
        )
        val shown = PrivateRelays.relevant(sent, "New app", null).map { it.content }
        // The phrase first ("new app" in the text or title), then the rest with both words, newest first.
        assertEquals(listOf("Release notes", "The new Marble app is out!", "an app that is new to me"), shown)
        assertTrue(PrivateRelays.relevant(sent, "pizza recipe", null).isEmpty())
        assertTrue(PrivateRelays.relevant(sent, "  !! ", null).isEmpty())
        // A #tag search was exact at the relay: all of it, newest first.
        assertEquals(sent.size, PrivateRelays.relevant(sent.reversed(), "#nostr", "nostr").size)
        assertEquals(500L, PrivateRelays.relevant(sent.reversed(), "#nostr", "nostr").first().createdAt)
    }
}
