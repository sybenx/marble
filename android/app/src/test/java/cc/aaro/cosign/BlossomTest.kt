package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blossom links (BUD-10): read as the spec says, and where they're asked for first. */
class BlossomTest {
    private val sha = "b1674191a88ec5cdd733e4240a81803105dc412d6c6708d53ab94fc248f4f553"
    private val pk = "ec4425ff5e9446080d2f70440188e3ca5d6da8713db7bdeef73d0ed54d9093f0"

    @Test fun theSpecsExample() {
        val l = Blossom.parse("blossom:$sha.pdf?xs=cdn.satellite.earth&as=$pk&xs=blossom.primal.net&sz=184292")!!
        assertEquals(sha, l.sha); assertEquals("pdf", l.ext); assertEquals(184292L, l.size)
        assertEquals(listOf("cdn.satellite.earth", "blossom.primal.net"), l.servers)
        assertEquals(listOf(pk), l.authors)
        // A bare domain: https first, then http; in the link's order.
        assertEquals(listOf("https://cdn.satellite.earth/$sha.pdf", "http://cdn.satellite.earth/$sha.pdf", "https://blossom.primal.net/$sha.pdf", "http://blossom.primal.net/$sha.pdf"),
            Blossom.hinted(l))
    }

    @Test fun aSchemeIsKept() = assertEquals(listOf("https://cdn.satellite.earth/$sha.png"), Blossom.hinted(Blossom.parse("blossom:$sha.png?xs=https://cdn.satellite.earth/")!!))

    @Test fun kinds() {
        assertTrue(Blossom.parse("blossom:$sha.jpg")!!.isImage)
        assertTrue(Blossom.parse("blossom:$sha.mp4")!!.isVideo)
        assertEquals(emptyList<String>(), Blossom.parse("blossom:$sha.bin")!!.servers)
    }

    @Test fun notBlossom() {
        assertNull(Blossom.parse("blossom:abc.pdf"))
        assertNull(Blossom.parse("https://x.example/$sha.pdf"))
        assertNull(Blossom.parse("blossom:$sha"))
    }
}
