package cc.aaro.cosign

import org.junit.Assert.assertEquals
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
        NewsSources.load("A", ::prefs)
        assertEquals(listOf("https://a.example/feed"), NewsSources.shownOwn().map { it.url })
        assertEquals("A", prefs("news-sources").getString("movedTo", null))
        // B, signed in after: none of A's.
        NewsSources.load("B", ::prefs)
        assertTrue(NewsSources.shownOwn().isEmpty())
        assertEquals("B", NewsSources.account)
        // A copy B pulls is taken into B's alone: back on A, A's list is as it was.
        NewsSources.take("""{"v":1,"own":[{"url":"https://b.example/feed","name":"B's","site":"b.example","topic":"","at":20}],"off":{}}""")
        NewsSources.load("A", ::prefs)
        assertEquals(listOf("https://a.example/feed"), NewsSources.shownOwn().map { it.url })
    }
}
