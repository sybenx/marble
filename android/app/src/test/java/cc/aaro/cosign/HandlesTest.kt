package cc.aaro.cosign

import cc.aaro.cosign.Handles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handles in the box (handles-rule.md): what's looked up, and only once it's whole. Never a site. */
class HandlesTest {
    @Test fun addressesAskNip05AndWebFinger() {
        assertEquals(Kind.Address("alice", "nostr-only.test"), Handles.kind("alice@nostr-only.test"))
        assertEquals(Kind.Address("bob", "fedi-only.test"), Handles.kind("@bob@fedi-only.test"))
        assertEquals(Kind.Address("_", "both.test"), Handles.kind("_@both.test"))
        assertEquals(Kind.Address("dave", "gmail.com"), Handles.kind("Dave@Gmail.com"))   // an email: asked, and nothing answers
    }

    @Test fun blueskyAndNames() {
        assertEquals(Kind.Bsky("name.bsky.social"), Handles.kind("@name.bsky.social"))
        assertEquals(Kind.Bsky("example.com"), Handles.kind("@example.com"))
        assertEquals(Kind.Name("elonmusk"), Handles.kind("@elonmusk"))
        assertEquals(Kind.Name("rosa"), Handles.kind("@rosa"))
    }

    @Test fun nothingUntilItsWhole() {
        assertNull(Handles.kind("bob@fedi-only.t"))
        assertNull(Handles.kind("dave@gmail.co".dropLast(3)))   // dave@gmail
        assertNull(Handles.kind("@a"))
        assertNull(Handles.kind("@"))
        assertNull(Handles.kind("bob @fedi.test"))
        assertNull(Handles.kind("example.com"))
    }

    @Test fun aBareNameIsOnlySomeoneYouFollow() {
        val rosa = "a".repeat(64); val alice = "b".repeat(64); val fake = "c".repeat(64)
        val known = mapOf(
            rosa to Person(rosa, "Rosa", null, null, "rosa@nostr.example", 0),
            alice to Person(alice, "alice", null, null, null, 0),
            fake to Person(fake, "Elonmusk", null, null, "elonmusk85836@divine.video", 0),
        )
        assertEquals(listOf(rosa), Handles.followed("rosa", setOf(rosa, fake), known))
        assertEquals(emptyList<String>(), Handles.followed("rosa", null, known))            // signed out
        assertEquals(emptyList<String>(), Handles.followed("alice", setOf(rosa), known))    // not followed
        assertEquals(emptyList<String>(), Handles.followed("elonmusk", null, known))
        assertEquals(emptyList<String>(), Handles.followed("elonmusk", setOf(rosa), known))
        // By their NIP-05's name too (followed, so it's who you meant).
        val bob = "d".repeat(64)
        assertEquals(listOf(bob), Handles.followed("bob", setOf(bob), known + (bob to Person(bob, "Robert", null, null, "bob@example.com", 0))))
    }

    @Test fun aHandleIsNeverASite() {
        assertNull(Sites.match("dave@gmail.com"))
        assertNull(Sites.match("@example.com"))
        assertNull(Sites.match("@apple"))
        assertEquals("example.com", Sites.match("example.com")!!.address)
    }
}
