package cc.aaro.cosign

import cc.aaro.qrst.core.Bech32
import cc.aaro.qrst.core.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The web pane's "Open in Marble": which pages are a single post or a person, from the address alone. */
class PageRefTest {
    private val id = "a".repeat(64)
    private val pk = "b".repeat(64)
    private val note = Bech32.encode("note", id.hexToBytes())
    private val npub = Bech32.encode("npub", pk.hexToBytes())
    private val nevent = nevent(id, pk, 1, emptyList())
    private val naddr = naddr("my-article", pk, 30023)

    @Test fun jumblesPostsAndPeople() {
        assertEquals(Ref.Note(id), pageRef("https://jumble.social/notes/$nevent"))
        assertEquals(Ref.Note(id), pageRef("https://jumble.social/notes/$note"))
        assertEquals(Ref.Profile(pk), pageRef("https://jumble.social/users/$npub"))
    }

    @Test fun anySitesPath() {
        assertEquals(Ref.Note(id), pageRef("https://some.client.example/thread/$nevent?ref=x#reply"))
        assertEquals(Ref.Addr(30023, pk, "my-article"), pageRef("https://reader.example/a/$naddr"))
        assertEquals(Ref.Profile(pk), pageRef("http://localhost:3000/p/$npub/"))
        // A person's page with a post open in it: the post, the last one named.
        assertEquals(Ref.Note(id), pageRef("https://x.example/users/$npub/notes/$note"))
    }

    @Test fun knownViewersStillWork() {
        assertEquals(Ref.Note(id), pageRef("https://njump.me/e/$id"))
        assertEquals(Ref.Profile(pk), pageRef("https://primal.net/p/$npub"))
    }

    @Test fun notAPostOrAPerson() {
        assertNull(pageRef(null))
        assertNull(pageRef(""))
        assertNull(pageRef("https://jumble.social/"))
        assertNull(pageRef("https://jumble.social/notes"))
        assertNull(pageRef("https://example.com/search?q=$npub"))        // only in the query
        assertNull(pageRef("https://example.com/#/$note"))               // only in the fragment
        assertNull(pageRef("https://example.com/e/$id"))                 // bare hex, not a viewer
        assertNull(pageRef("https://example.com/notes/${note.dropLast(1)}x")) // broken checksum
        assertNull(pageRef("https://example.com/notes/my-${note}"))      // part of a word
        assertNull(pageRef("javascript:$note"))
    }
}

/** nostr: links handed to Marble by Android (MainActivity). */
class NostrLinkTest {
    private val id = "a".repeat(64)
    private val pk = "b".repeat(64)
    private val npub = Bech32.encode("npub", pk.hexToBytes())
    private val nevent = nevent(id, pk, 1, listOf("wss://relay.example"))

    @Test fun postsAndPeople() {
        assertEquals(Ref.Note(id), nostrLinkRef("nostr:$nevent"))
        assertEquals(Ref.Profile(pk), nostrLinkRef("nostr:$npub"))
        assertEquals(Ref.Profile(pk), nostrLinkRef("NOSTR:${npub.uppercase()}"))
        assertEquals(Ref.Profile(pk), nostrLinkRef("nostr://$npub"))
        assertEquals(listOf("wss://relay.example"), nostrLinkRef("nostr:$nevent")!!.relays)
    }

    @Test fun notOnes() {
        assertNull(nostrLinkRef("nostr:"))
        assertNull(nostrLinkRef("nostr:hello"))
        assertNull(nostrLinkRef("nostr:$pk"))                              // bare hex says nothing
        assertNull(nostrLinkRef("nostr:nsec1" + "q".repeat(58)))
        assertNull(nostrLinkRef("nostr:${npub.dropLast(1)}q"))              // broken checksum
        assertNull(nostrLinkRef("https://njump.me/$npub"))
    }
}

/** Shared into Marble (KeepActivity): only a post or someone is opened; anything else is kept as before. */
class SharedRefTest {
    private val id = "a".repeat(64)
    private val note = Bech32.encode("note", id.hexToBytes())

    @Test fun onlyAReference() {
        assertEquals(Ref.Note(id), sharedRef("nostr:$note"))
        assertEquals(Ref.Note(id), sharedRef("  $note\n"))
        assertEquals(Ref.Note(id), sharedRef("https://njump.me/$note"))
        assertNull(sharedRef("look at this $note"))
        assertNull(sharedRef("https://example.com/notes/$note"))   // an unknown site's page: kept, as a link
        assertNull(sharedRef("https://example.com/article"))
    }
}
