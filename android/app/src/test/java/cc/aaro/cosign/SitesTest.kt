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
        val goo = Sites.match("goo")!!
        assertFalse(goo.sure)
        assertEquals("google.com", goo.address)
    }

    @Test fun oneNameAndASlashGoes() {
        val x = Sites.match("xumbrel/")!!
        assertTrue(x.sure)
        assertEquals("http://xumbrel/", x.url)
        assertEquals("xumbrel/", x.address)
        assertEquals("http://xumbrel/apps", Sites.match("xumbrel/apps")!!.url)
        assertEquals("http://localhost:8080/admin", Sites.match("localhost:8080/admin")!!.url)
        assertEquals("http://localhost/", Sites.match("localhost/")!!.url)
        assertTrue(Sites.match("http://localhost")!!.sure)
        assertEquals("http://localhost", Sites.match("http://localhost")!!.url)
        // Without the slash, a name is only a search.
        assertNull(Sites.match("xumbrel"))
        assertNull(Sites.match("localhost"))
        assertNull(Sites.match("a/b c"))
    }

    @Test fun anAddressGoes() {
        assertTrue(Sites.match("apple.com")!!.sure)
        assertEquals("https://apple.com", Sites.match("apple.com")!!.url)
        assertTrue(Sites.match("lemmy.world")!!.sure)
        assertEquals("https://github.com/sybenx", Sites.match("github.com/sybenx")!!.url)
        assertTrue(Sites.match("https://example.org/a")!!.sure)
        // Not addresses: a file name, words.
        assertNull(Sites.match("node.js"))
        assertNull(Sites.match("notes.md"))
        assertNull(Sites.match("apple pie"))
    }
}
