package cc.aaro.cosign

import cc.aaro.cosign.core.Bech32
import cc.aaro.cosign.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Share sends: a marble.syb.lol link that names the post (or the article) and nothing that changes. */
class MarbleLinkTest {
    private val author = "b".repeat(64)

    @Test fun aPostIsANevent() {
        val e = Event(author, 1_700_000_000, 1, emptyList(), "hello").withId()
        val link = marbleLink(e)
        assertTrue(link, link.startsWith("https://marble.syb.lol/nevent1"))
        assertEquals(e.id, (Ref.parse(link.removePrefix("https://marble.syb.lol/")) as Ref.Note).id)
        assertEquals(link, marbleLink(e))   // the same every time
    }

    @Test fun anArticleIsAnNaddr() {
        val e = Event(author, 1_700_000_000, 30023, listOf(listOf("d", "my-article"), listOf("title", "T")), "body").withId()
        val link = marbleLink(e)
        assertTrue(link, link.startsWith("https://marble.syb.lol/naddr1"))
        val (hrp, data) = Bech32.decode(link.removePrefix("https://marble.syb.lol/"), 2000)
        assertEquals("naddr", hrp)
        // TLV: 0 = the d tag, 2 = the author, 3 = the kind.
        assertEquals(0, data[0].toInt()); assertEquals("my-article", String(data, 2, data[1].toInt()))
    }
}
