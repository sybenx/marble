package cc.aaro.cosign

import cc.aaro.cosign.Handles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun aHandleIsNeverASite() {
        assertNull(Sites.match("dave@gmail.com"))
        assertNull(Sites.match("@example.com"))
        assertNull(Sites.match("@apple"))
        assertEquals("example.com", Sites.match("example.com")!!.address)
    }
}
