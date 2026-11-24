package cc.aaro.cosign.ui

import cc.aaro.cosign.Change
import cc.aaro.cosign.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account's pages open straight from the picture menu; each title says what is on it. */
class AccountPagesTest {
    private fun site(origin: String, used: Long, pk: String = origin) = Site(pk, origin, 0, used)
    private fun change(id: String, by: String) = Change(id, "Changed your DM relays", by, 0, held = false, undone = false)

    @Test fun sitesNewestUsedFirst() {
        val rows = AccountPages.siteRows(listOf(site("https://old.com", 5), site("Vector", 9), site("https://old.com", 20, "k2")))
        assertEquals(listOf("https://old.com" to 20L, "Vector" to 9L), rows)
    }

    @Test fun aSitePageHasOnlyItsChanges() {
        val all = listOf(change("1", "Vector"), change("2", "https://a.com"), change("3", "Vector"))
        assertEquals(listOf("1", "3"), AccountPages.changesBy("Vector", all).map { it.id })
    }

    @Test fun backFromASiteGoesToAppsAndSitesElseLeaves() {
        val p = AccountPages.site("https://a.com")
        assertEquals("https://a.com", AccountPages.origin(p))
        assertEquals(AccountPages.SITES, AccountPages.up(p))
        assertNull(AccountPages.up(AccountPages.SITES))
        assertNull(AccountPages.up(AccountPages.DEVICES))
        assertNull(AccountPages.origin(AccountPages.CHANGES))
    }

    @Test fun titlesSayWhatIsThere() {
        assertEquals("Devices and backup", AccountPages.title(AccountPages.DEVICES))
        assertEquals("Recent changes", AccountPages.title(AccountPages.CHANGES))
        assertEquals("How your account works", AccountPages.title(AccountPages.ADVANCED))
        assertEquals("a.com", AccountPages.title(AccountPages.site("https://a.com")))
    }

    @Test fun oldOrUnknownPagesAreNotShown() {
        for (p in listOf(AccountPages.SITES, AccountPages.CHANGES, AccountPages.DEVICES, AccountPages.LANGUAGES, AccountPages.SEARCH, AccountPages.ADVANCED, AccountPages.TRIALS, "site:https://x"))
            assertTrue(p, AccountPages.known(p))
        for (p in listOf(null, "", "following", "junk")) assertFalse("$p", AccountPages.known(p))
    }
}
