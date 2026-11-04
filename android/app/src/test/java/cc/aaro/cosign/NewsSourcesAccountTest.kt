package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** recheck5: news sources are one account's, as everything else; the phone's old shared list moves once, to one account. */
class NewsSourcesAccountTest {
    private val files = HashMap<String, FakePrefs>()
    private fun prefs(name: String) = files.getOrPut(name) { FakePrefs() }

    @Test fun eachAccountHasItsOwnAndTheOldListMovesOnce() {
        // Before: one list for the phone, with A's feed in it.
        val old = """{"v":1,"own":[{"url":"https://a.example/feed","name":"A's feed","site":"a.example","topic":"","at":10}],"off":{}}"""
        prefs("news-sources").edit().putString("state", old).apply()
        NewsSources.load("A", "A", ::prefs)
        assertEquals(listOf("https://a.example/feed"), NewsSources.shownOwn().map { it.url })
        assertEquals("A", prefs("news-sources").getString("movedTo", null))
        // B, signed in after: none of A's.
        NewsSources.load("B", "A", ::prefs)
        assertTrue(NewsSources.shownOwn().isEmpty())
        assertEquals("B", NewsSources.account)
        // A copy B pulls is taken into B's alone: back on A, A's list is as it was.
        NewsSources.take("""{"v":1,"own":[{"url":"https://b.example/feed","name":"B's","site":"b.example","topic":"","at":20}],"off":{}}""")
        NewsSources.load("A", "A", ::prefs)
        assertEquals(listOf("https://a.example/feed"), NewsSources.shownOwn().map { it.url })
    }

    /** Gate check 3: the old list goes to its recorded owner, never to whoever happens to load first. */
    @Test fun theOldListGoesToItsOwner() {
        val f = HashMap<String, FakePrefs>(); fun p(n: String) = f.getOrPut(n) { FakePrefs() }
        p("news-sources").edit().putString("state", """{"v":1,"own":[{"url":"https://b.example/feed","name":"B's","site":"b.example","topic":"","at":10}],"off":{}}""").apply()
        p("news-sources-owner").edit().putString("owner", "B").apply()
        NewsSources.load("A", "A", ::p)
        assertTrue(NewsSources.shownOwn().isEmpty())
        NewsSources.load("B", "A", ::p)
        assertEquals(listOf("https://b.example/feed"), NewsSources.shownOwn().map { it.url })
        // No owner recorded: the account signed in at the start, not the first one shown.
        val g = HashMap<String, FakePrefs>(); fun q(n: String) = g.getOrPut(n) { FakePrefs() }
        q("news-sources").edit().putString("state", """{"v":1,"own":[{"url":"https://c.example/feed","name":"C","site":"c.example","topic":"","at":10}],"off":{}}""").apply()
        NewsSources.load("X", "Y", ::q)
        assertTrue(NewsSources.shownOwn().isEmpty())
        NewsSources.load("Y", "Y", ::q)
        assertEquals(listOf("https://c.example/feed"), NewsSources.shownOwn().map { it.url })
    }

    /** Gate check 4: a copy pulled for A while the phone switched to B is never taken into B's list. */
    @Test fun aPullForOneAccountIsntTakenIntoAnother() {
        val f = HashMap<String, FakePrefs>(); fun p(n: String) = f.getOrPut(n) { FakePrefs() }
        NewsSources.load("A", "A", ::p)
        NewsSources.load("B", "A", ::p)
        assertFalse(NewsSources.takeFor("A", """{"v":1,"own":[{"url":"https://a.example/feed","name":"A's","site":"a.example","topic":"","at":10}],"off":{}}"""))
        assertTrue(NewsSources.shownOwn().isEmpty())
    }
}
