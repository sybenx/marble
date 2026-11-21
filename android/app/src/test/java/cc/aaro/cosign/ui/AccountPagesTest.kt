package cc.aaro.cosign.ui

import cc.aaro.cosign.Change
import cc.aaro.cosign.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The account is a short list; apps and what they changed come first, one tap each. */
class AccountPagesTest {
    private fun site(origin: String, used: Long, pk: String = origin) = Site(pk, origin, 0, used)
    private fun change(id: String, by: String) = Change(id, "Changed your DM relays", by, 0, held = false, undone = false)

    @Test fun appsAndChangesComeFirst() {
        val rows = AccountPages.rows(listOf(site("https://a.com", 1), site("https://a.com", 2, "k2"), site("Vector", 3)), 3)
        assertEquals(listOf("Apps and sites", "Recent changes", "Devices", "Following", "Languages", "Search", "Advanced"), rows.map { it.label })
        assertEquals("2", rows[0].count) // one per website, not one per way in
        assertEquals("3", rows[2].count)
        assertNull(rows[1].count)
    }

    @Test fun noSitesNoCount() = assertNull(AccountPages.rows(emptyList(), 1)[0].count)

    @Test fun sitesNewestUsedFirst() {
        val rows = AccountPages.siteRows(listOf(site("https://old.com", 5), site("Vector", 9), site("https://old.com", 20, "k2")))
        assertEquals(listOf("https://old.com" to 20L, "Vector" to 9L), rows)
    }

    @Test fun aSitePageHasOnlyItsChanges() {
        val all = listOf(change("1", "Vector"), change("2", "https://a.com"), change("3", "Vector"))
        assertEquals(listOf("1", "3"), AccountPages.changesBy("Vector", all).map { it.id })
    }

    @Test fun backFromASiteGoesToAppsAndSites() {
        val p = AccountPages.site("https://a.com")
        assertEquals("https://a.com", AccountPages.origin(p))
        assertEquals(AccountPages.SITES, AccountPages.up(p))
        assertNull(AccountPages.up(AccountPages.SITES))
        assertNull(AccountPages.origin(AccountPages.CHANGES))
    }

    @Test fun titles() {
        assertEquals("Your account", AccountPages.title(null))
        assertEquals("Recent changes", AccountPages.title(AccountPages.CHANGES))
        assertEquals("a.com", AccountPages.title(AccountPages.site("https://a.com")))
    }
}
