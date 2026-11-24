package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
