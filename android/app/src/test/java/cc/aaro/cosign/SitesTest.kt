package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enter goes to a site only when what's typed is an address; a bare name searches (with a row to go there). */
class SitesTest {
    @Test fun aBareNameSearches() {
        val apple = Sites.match("apple")!!
        assertFalse(apple.sure)
        assertEquals("apple.com", apple.address)
        assertFalse(Sites.match("youtube")!!.sure)
    }

    @Test fun anAddressGoes() {
        assertTrue(Sites.match("apple.com")!!.sure)
        assertTrue(Sites.match("lemmy.world")!!.sure)
        assertEquals("https://github.com/sybenx", Sites.match("github.com/sybenx")!!.url)
        assertTrue(Sites.match("https://example.org/a")!!.sure)
        // Not addresses: a file name, words.
        assertNull(Sites.match("node.js"))
        assertNull(Sites.match("apple pie"))
    }
}
