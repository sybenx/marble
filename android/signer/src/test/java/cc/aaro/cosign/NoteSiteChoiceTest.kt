package cc.aaro.cosign

import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.SiteSigner
import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `nostrconnect` note from the login page (Keykin SPEC 11.6, "Which key a site gets"): the card's choice
 * (`as`) for a site new to the account, the remembered one for a site signed in before, and the grant named
 * by the sender (`from`) only when the card showed it matched the code's `url`.
 */
class NoteSiteChoiceTest {
    private fun site(origin: String?, place: String? = null) = SiteSigner.Site(hex(random(32)), origin, emptyList(), 0, 0, "A nostrconnect code", "g", place)
    private val url = "https://a.example/app"

    @Test fun asSiteSignsInAsTheChildForTheOrigin() {
        assertEquals(SiteChoice.NoteSignIn("https://a.example", "https://a.example"), SiteChoice.forNote(url, "A", "https://a.example", "site", emptyList()))
        // No sender to go by: the code's url names the child, as SiteChoice.origin makes it.
        assertEquals("https://a.example", SiteChoice.forNote("https://A.Example:443/x", "A", null, "site", emptyList()).place)
        // Neither a sender nor a url: no web origin for a child, so the main account.
        assertNull(SiteChoice.forNote(null, "Some App", null, "site", emptyList()).place)
    }

    @Test fun asMainOrMissingIsTheMainAccount() {
        assertNull(SiteChoice.forNote(url, "A", "https://a.example", "main", emptyList()).place)
        assertNull(SiteChoice.forNote(url, "A", "https://a.example", null, emptyList()).place)
        // An older box: neither field, the old behaviour (main account, named by the url host).
        assertEquals(SiteChoice.NoteSignIn("a.example", null), SiteChoice.forNote(url, "A", null, null, emptyList()))
        // Something else in `as` is not a request for a separate account.
        assertNull(SiteChoice.forNote(url, "A", "https://a.example", "child", emptyList()).place)
        // Marble's own site is always the main account.
        assertNull(SiteChoice.forNote("https://marble.syb.lol", "Marble", "https://marble.syb.lol", "site", emptyList()).place)
    }

    @Test fun aRememberedSitesChoiceWins() {
        // Signed in before with its own account: that one, even if the card now says main (or says nothing).
        val own = listOf(site("a.example", "https://a.example"))
        for (signAs in listOf("main", "site", null))
            assertEquals(signAs.toString(), "https://a.example", SiteChoice.forNote(url, "A", "https://a.example", signAs, own).place)
        // Signed in before with the main account: the main account, even if the card now says site.
        val main = listOf(site("https://a.example"))
        assertNull(SiteChoice.forNote(url, "A", "https://a.example", "site", main).place)
        // Matched by origin: another site's record changes nothing.
        assertEquals("https://a.example", SiteChoice.forNote(url, "A", "https://a.example", "site", listOf(site("https://b.example"))).place)
    }

    @Test fun theGrantIsNamedByTheSenderOnlyWhenItMatched() {
        // Matched (from's host is the url host): named and recorded by from.
        assertEquals("https://a.example", SiteChoice.forNote(url, "A", "https://a.example", null, emptyList()).name)
        assertEquals("https://a.example", SiteChoice.forNote(url, "A", "https://A.example/", null, emptyList()).name)
        // Unmatched: the code's url host, and the child (if asked for) is the url's, not the sender's.
        val other = SiteChoice.forNote(url, "A", "https://evil.example", "site", emptyList())
        assertEquals(SiteChoice.NoteSignIn("a.example", "https://a.example"), other)
        // The sender's own record is not used for an unmatched note: the url's site decides.
        assertNull(SiteChoice.forNote(url, "A", "https://evil.example", "site", listOf(site("https://a.example"))).place)
        // No url in the code: never matched; the code's name, then "A site".
        assertEquals("Some App", SiteChoice.forNote(null, "Some App", "https://a.example", null, emptyList()).name)
        assertEquals("A site", SiteChoice.forNote(null, null, "https://a.example", null, emptyList()).name)
    }

    @Test fun theNoteCarriesFromAndAsWhenTheBoxSendsThem() {
        val n = Device.Note.of(JSONObject("""{"id":"n1","kind":"nostrconnect","text":"nostrconnect://x","at":5,"tap":false,"from":"https://a.example","as":"site"}"""))
        assertEquals(Device.Note("n1", "nostrconnect", "nostrconnect://x", 5, false, "https://a.example", "site"), n)
        // A null from (the page could not tell) and an older box (neither field) are both null.
        val n2 = Device.Note.of(JSONObject("""{"id":"n2","kind":"nostrconnect","text":"t","at":5,"tap":false,"from":null,"as":"main"}"""))
        assertNull(n2.from); assertEquals("main", n2.signAs)
        val old = Device.Note.of(JSONObject("""{"id":"n3","kind":"invite","text":"t","at":5}"""))
        assertNull(old.from); assertNull(old.signAs)
    }
}
