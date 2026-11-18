package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Test

/** What each search row says it is, and where a tap on it goes. */
class SearchRowsTest {
    private val pk = "1".repeat(64)
    private fun app(kind: String, name: String = "Jumble", url: String = "https://jumble.social/", domain: String? = null, by: String? = null, zapstore: String? = null) =
        AppEntry("a", kind, name, "A user-friendly Nostr client", null, url, domain, null, pk, by, false, zapstore, 0.0)

    private fun check(r: SearchRows.Row, title: String, kind: String, detail: String, goes: Goes, say: String) {
        assertEquals(title, r.title); assertEquals(kind, r.kind); assertEquals(detail, r.detail); assertEquals(goes, r.goes); assertEquals(say, r.say)
    }

    @Test fun webAppOnThePane() = check(SearchRows.app(app("web", domain = "jumble.social"), pane = true),
        "Jumble", "Web app", "jumble.social", Goes.Marble, "Open Jumble in Marble")

    @Test fun webAppElsewhere() = check(SearchRows.app(app("web", "noStrudel", "https://nostrudel.ninja/", domain = "nostrudel.ninja"), pane = false),
        "noStrudel", "Web app", "nostrudel.ninja", Goes.Browser, "Open noStrudel in your browser")

    @Test fun paneIsTheSameTestOpenAppUses() {
        // openApp opens SearchRows.paneSite(a) on the pane, else the browser; the row says the same.
        fun both(a: AppEntry, goes: Goes) { assertEquals(goes == Goes.Marble, SearchRows.paneSite(a) != null); assertEquals(goes, SearchRows.app(a).goes) }
        // A site already on the pane: its chip.
        both(app("web", domain = "jumble.social"), Goes.Marble)
        assertEquals("https://jumble.social", SearchRows.paneSite(app("web", domain = "jumble.social"))!!.origin)
        both(app("web", "Marble", "https://marble.syb.lol/somewhere", domain = "marble.syb.lol"), Goes.Marble)
        // Any other web app or nsite at an https address: a visiting chip, in Marble too.
        both(app("web", "noStrudel", "https://nostrudel.ninja/", domain = "nostrudel.ninja"), Goes.Marble)
        both(app("web", url = "https://www.jumble.social/", domain = "jumble.social"), Goes.Marble)
        both(app("nsite", url = "https://x.nsite.lol/", by = "Cody"), Goes.Marble)
        // Not https: the browser.
        both(app("web", "Local", "http://192.168.1.5:3000/"), Goes.Browser)
        both(app("nsite", url = "http://y.nsite.lol/"), Goes.Browser)
        // An Android app's Zapstore page never gets a visiting chip.
        both(app("android", url = "https://zapstore.dev/apps/com.codytseng.jumble", zapstore = "com.codytseng.jumble"), Goes.Zapstore)
    }

    @Test fun webAppWithAnAndroidVersionSaysNothingAboutIt() = check(
        SearchRows.app(app("web", "Coracle", "https://coracle.social/", domain = "coracle.social", zapstore = "social.coracle"), pane = false),
        "Coracle", "Web app", "coracle.social", Goes.Browser, "Open Coracle in your browser")

    @Test fun webAppWithNoDomain() = check(SearchRows.app(app("web", url = "https://x.example/"), pane = false),
        "Jumble", "Web app", "", Goes.Browser, "Open Jumble in your browser")

    @Test fun nsiteBySomeone() = check(SearchRows.app(app("nsite", url = "https://x.nsite.lol/", by = "Cody")),
        "Jumble", "nsite", "by Cody", Goes.Marble, "Open Jumble in Marble")

    @Test fun nsiteByNobodyInTheBrowser() = check(SearchRows.app(app("nsite", url = "https://y.nsite.lol/"), pane = false),
        "Jumble", "nsite", "", Goes.Browser, "Open Jumble nsite in your browser")

    @Test fun androidWithItsId() = check(SearchRows.app(app("android", url = "https://zapstore.dev/apps/com.codytseng.jumble", zapstore = "com.codytseng.jumble")),
        "Jumble", "Android app", "com.codytseng.jumble", Goes.Zapstore, "Open Jumble on Zapstore")

    @Test fun androidWithoutAnId() = check(SearchRows.app(app("android", url = "https://zapstore.dev/apps/x")),
        "Jumble", "Android app", "", Goes.Zapstore, "Open Jumble on Zapstore")

    @Test fun androidOnAKeptZapstoreChipOpensThere() = assertEquals(Goes.Marble, SearchRows.app(app("android", zapstore = "com.codytseng.jumble"), pane = true).goes)

    @Test fun followedAuthorAfterTheDetail() {
        assertEquals("jumble.social · You follow them", SearchRows.app(app("web", domain = "jumble.social"), setOf(pk), pane = true).detail)
        assertEquals("You follow them", SearchRows.app(app("nsite", url = "https://y.nsite.lol/"), setOf(pk)).detail)
    }

    @Test fun aboutIsNotOnTheLine() {
        val r = SearchRows.app(app("web", domain = "jumble.social"), pane = true)
        assertEquals(false, r.detail.contains("Nostr client"))
    }

    @Test fun site() = check(SearchRows.site("jumble.social"), "jumble.social", "Website", "", Goes.Browser, "Open jumble.social in your browser")

    @Test fun handleWithAName() = check(SearchRows.handle("Mastodon", "Cody", "@cody@mastodon.social"),
        "Cody", "Profile on Mastodon", "@cody@mastodon.social", Goes.Browser, "Open Cody's profile on Mastodon in your browser")

    @Test fun handleWithoutAName() = check(SearchRows.handle("Bluesky", null, "@cody.bsky.social"),
        "@cody.bsky.social", "Profile on Bluesky", "", Goes.Browser, "Open @cody.bsky.social's profile on Bluesky in your browser")

    @Test fun person() = check(SearchRows.person("Cody", "cody@jumble.social · 1.2K followers"),
        "Cody", "Profile", "cody@jumble.social · 1.2K followers", Goes.Marble, "Open Cody's profile")

    @Test fun personWithNothingElse() = check(SearchRows.person("Cody", ""), "Cody", "Profile", "", Goes.Marble, "Open Cody's profile")

    @Test fun words() = assertEquals(listOf("Marble", "Browser", "Zapstore"), Goes.entries.map { it.word })
}
