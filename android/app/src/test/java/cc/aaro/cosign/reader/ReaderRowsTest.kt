package cc.aaro.cosign.reader

import cc.aaro.cosign.Person
import cc.aaro.cosign.ReaderRows
import cc.aaro.cosign.nevent
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Nip19
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a person's post becomes a Reader row (Android's side, beyond fixtures/reader/content.json). */
class ReaderRowsTest {
    private val ana = "%064x".format(1); private val ben = "%064x".format(2)
    private fun post(content: String, tags: List<List<String>> = emptyList(), kind: Int = 1, at: Long = 1000) = Event(ana, at, kind, tags, content).withId()

    @Test fun theLinkIsntAPictureOrVideo() {
        assertEquals("https://example.com/story", ReaderRows.link(post("Look https://x.org/a.mp4 and https://y.org/b.jpg then https://example.com/story).")))
        assertEquals(null, ReaderRows.link(post("Just https://x.org/a.mp4")))
    }

    @Test fun aViewerLinkOnlyIsTitledAPost() {
        val q = post("the quoted one", at = 1)
        assertEquals("A post", ReaderRows.title(post("https://njump.me/" + nevent(q.id, q.pubkey, 1, emptyList())), emptyMap()).first)
    }

    @Test fun contentWarningsHideTitles() {
        val art = post("# Hidden", listOf(listOf("d", "x"), listOf("title", "Secret title"), listOf("content-warning", "spoilers")), kind = 30023)
        assertEquals("spoilers: show", ReaderRows.title(art, emptyMap()).first)
        assertEquals("Sensitive: show", ReaderRows.title(post("words https://example.com", listOf(listOf("content-warning"))), emptyMap()).first)
    }

    @Test fun namesFillInWhenKnown() {
        val rows = ReaderRows.people(listOf(post("Thanks nostr:" + Nip19.npub(ben))), emptyMap(), { "people:$it" }, "people")
        val before = ReaderRows.text(rows.single(), emptyMap()).second
        val after = ReaderRows.text(rows.single(), mapOf(ben to Person(ben, "Ben Builder", null, null, null, 0))).second
        assertTrue(before, !before.contains("Ben Builder"))
        assertTrue(after, after.contains("@Ben Builder"))
    }

    /** User bug (0.12.27): a day's second post opened its first. Each post of a day opens itself; the row opens none of them. */
    @Test fun aDaysSecondPostOpensItself() {
        val posts = listOf(post("first", at = 1000), post("second", at = 1100), post("third", at = 1200))
        val day = ReaderRows.people(posts, emptyMap(), { "people:$it" }, "people").single()
        assertEquals(3, day.posts.size)
        val second = posts[1]
        assertEquals(second.id, ReaderRows.openTarget(day, second)!!.id)
        assertEquals(null, ReaderRows.openTarget(day))
        // A post from elsewhere isn't this row's.
        assertEquals(null, ReaderRows.openTarget(day, post("other", at = 1300)))
        // A day of one post: that post.
        val one = ReaderRows.people(listOf(posts[0]), emptyMap(), { "people:$it" }, "people").single()
        assertEquals(posts[0].id, ReaderRows.openTarget(one)!!.id)
    }
}
