package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Enter goes to a site only when what's typed is an address; a bare name only searches. */
class SitesTest {
    @Test fun aBareNameSearches() {
        assertNull(Sites.match("apple"))
        assertNull(Sites.match("youtube"))
        assertNull(Sites.match("goo"))
    }

    @Test fun anAddressGoes() {
        assertEquals("apple.com", Sites.match("apple.com")!!.address)
        assertEquals("https://lemmy.world", Sites.match("lemmy.world")!!.url)
        assertEquals("https://github.com/sybenx", Sites.match("github.com/sybenx")!!.url)
        assertEquals("example.org", Sites.match("https://example.org/a")!!.address)
        // Not addresses: a file name, words.
        assertNull(Sites.match("node.js"))
        assertNull(Sites.match("apple pie"))
    }
}
