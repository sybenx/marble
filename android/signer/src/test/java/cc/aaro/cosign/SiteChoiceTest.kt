package cc.aaro.cosign

import cc.aaro.keykin.core.Children
import cc.aaro.keykin.core.KeykinException
import cc.aaro.keykin.core.SiteSigner
import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which account a site signs in with: a site new to the account is asked about (a new account of its own
 * by default), a site signed in before gets what it got then with no question, and Marble's own site and
 * the account's own pages are always the main account. And the site signer gives a site with its own
 * account that account's key and nothing else.
 */
class SiteChoiceTest {
    private fun site(origin: String?, place: String? = null) = SiteSigner.Site(hex(random(32)), origin, emptyList(), 0, 0, "A scanned code", "g", place)

    @Test fun aNewSiteIsAskedWithItsOriginAsTheName() {
        assertEquals(SiteChoice.Decision.Ask("https://jumble.social"), SiteChoice.decide("https://Jumble.Social/", emptyList()))
        assertEquals(SiteChoice.Decision.Ask("https://jumble.social"), SiteChoice.decide("https://jumble.social:443", emptyList()))
        assertEquals(SiteChoice.Decision.Ask("http://10.0.2.2:8080"), SiteChoice.decide("http://10.0.2.2:8080/page?x=1", emptyList()))
        // Another site already signed in changes nothing for this one.
        assertEquals(SiteChoice.Decision.Ask("https://a.example"), SiteChoice.decide("https://a.example", listOf(site("https://b.example", "https://b.example"))))
    }

    @Test fun aSiteGivenItsOwnAccountForChangingYourRelaysComesBackOnIt() {
        val moved = setOf("https://a.example")
        // Signed out when it was moved: its next sign-in gets its own account, with no question, and the card says why.
        assertEquals(SiteChoice.Decision.Own("https://a.example", moved = true), SiteChoice.decide("https://a.example/app", emptyList(), moved = moved))
        // Once it is signed in again, its record decides, as for any site.
        assertEquals(SiteChoice.Decision.Own("https://a.example"), SiteChoice.decide("https://a.example", listOf(site("https://a.example", "https://a.example")), moved = moved))
        // Another site is not affected, and an account page is always the main account.
        assertEquals(SiteChoice.Decision.Ask("https://b.example"), SiteChoice.decide("https://b.example", emptyList(), moved = moved))
        assertEquals(SiteChoice.Decision.AccountPage, SiteChoice.decide("https://a.example", emptyList(), accountPage = true, moved = moved))
        // A login-page note from it signs in on that account too.
        assertEquals("https://a.example", SiteChoice.forNote("https://a.example", null, "https://a.example", "main", emptyList(), moved).place)
    }

    @Test fun theChoiceIsRememberedPerSite() {
        // Signed in with its own account: every later sign-in from it gets that one, with no question.
        val own = listOf(site("https://a.example", "https://a.example"))
        assertEquals(SiteChoice.Decision.Own("https://a.example"), SiteChoice.decide("https://a.example", own))
        // A nostrconnect sign-in keeps only the host as the site's name: still the same site.
        assertEquals(SiteChoice.Decision.Own("https://a.example"), SiteChoice.decide("https://a.example", listOf(site("a.example", "https://a.example"))))
        // Signed in with the main account (or before this existed): the main account again.
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide("https://a.example", listOf(site("https://a.example"))))
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide("https://a.example", listOf(site("a.example"))))
    }

    @Test fun someSitesAreAlwaysTheMainAccount() {
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide("https://marble.syb.lol", emptyList()))
        // Even if a record says otherwise.
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide("https://marble.syb.lol", listOf(site("https://marble.syb.lol", "https://marble.syb.lol"))))
        // No web address to go by (an app's name only): the main account, no question.
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide(null, emptyList()))
        assertEquals(SiteChoice.Decision.Main, SiteChoice.decide("Some App", emptyList()))
        // An address is not enough to be an account page: account.syb.lol unmarked is a site like any other.
        assertEquals(SiteChoice.Decision.Ask("https://account.syb.lol"), SiteChoice.decide("https://account.syb.lol", emptyList()))
    }

    @Test fun aKeykinAccountPageIsAlwaysTheMainAccountWhereverItIs() {
        // The mark (Keykin SPEC 11.6), on each of the three forms a sign-in comes in.
        val scanned = "https://qrst.aaro.cc/#v=1&m=offer&p=keykin-signer-ticket&k=" + "ab".repeat(32) + "&c=none&t=tok&r=wss%3A%2F%2Frelay.example&origin=https://id.example.org&kk=account"
        val qrst = "qrst://pair?v=1&m=offer&p=keykin-signer-ticket&k=" + "ab".repeat(32) + "&c=none&t=tok&r=wss%3A%2F%2Frelay.example&origin=https%3A%2F%2Fid.example.org&kk=account"
        val link = "nostrconnect://" + "cd".repeat(32) + "?relay=wss%3A%2F%2Frelay.example&secret=s3&name=Account&url=https%3A%2F%2Fid.example.org&kk=account"
        for (code in listOf(scanned, qrst, link)) {
            assertEquals(code, true, SiteChoice.marked(code))
            assertEquals(SiteChoice.Decision.AccountPage, SiteChoice.decide("https://id.example.org", emptyList(), SiteChoice.marked(code)))
            // Even where it was signed in before with an account of its own.
            assertEquals(SiteChoice.Decision.AccountPage, SiteChoice.decide("https://id.example.org", listOf(site("https://id.example.org", "https://id.example.org")), SiteChoice.marked(code)))
        }
        // Unmarked, or a look-alike, is not an account page.
        for (code in listOf(scanned.removeSuffix("&kk=account"), link.removeSuffix("&kk=account"), link + "x", link.replace("kk=account", "kk=accounts"), "https://x.example/?q=kk=account"))
            assertEquals(code, false, SiteChoice.marked(code))
    }

    @Test fun aSiteWithItsOwnAccountOnlySeesThatAccount() {
        val nsec = cc.aaro.keykin.core.unhex(hex(random(32)))
        val mine = cc.aaro.keykin.core.Secp.pub(nsec)
        val child = Children.local(nsec, "https://a.example")
        val kids = mapOf("https://a.example" to child.npub)
        val acted = mutableListOf<String?>()
        val s = SiteSigner({ mine }, { site, _, _ -> acted += site.place; "ok" }, SiteSigner.fresh(), {}, {}, { kids[it] })
        // A ticket for a site with its own account names that account, and the site's record keeps it.
        val t = s.mint("https://a.example", emptyList(), place = "https://a.example")
        assertEquals(child.npub, t.getString("pubkey"))
        val client = hex(random(32))
        s.answer(client, "connect", listOf(s.pubkey, t.getString("t")))
        assertEquals("https://a.example", s.sites().single().place)
        assertEquals(child.npub, s.answer(client, "get_public_key", emptyList()))
        s.answer(client, "sign_event", listOf("{}"))
        assertEquals(listOf<String?>("https://a.example"), acted)
        // A site with the main account gets the main account, as before.
        val t2 = s.mint("https://b.example", emptyList())
        assertEquals(mine, t2.getString("pubkey"))
        val other = hex(random(32))
        s.answer(other, "connect", listOf(s.pubkey, t2.getString("t")))
        assertNull(s.sites().first { it.key == other }.place)
        assertEquals(mine, s.answer(other, "get_public_key", emptyList()))
        // A place with no account made for it is refused before anything is minted.
        assertEquals("no_account", try { s.mint("https://c.example", emptyList(), place = "https://c.example"); "ok" } catch (e: KeykinException) { e.code })
        // The record survives a restart (it is in the saved state).
        val st = JSONObject(s.let { val f = SiteSigner::class.java.getDeclaredField("state"); f.isAccessible = true; f.get(it) as JSONObject }.toString())
        val again = SiteSigner({ mine }, { _, _, _ -> "ok" }, st, {}, {}, { kids[it] })
        assertEquals(child.npub, again.answer(client, "get_public_key", emptyList()))
    }
}
