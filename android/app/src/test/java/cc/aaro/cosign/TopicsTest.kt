package cc.aaro.cosign

import android.content.SharedPreferences
import cc.aaro.cosign.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Discover's topics: what a post is about, what you like, what you've seen, and how a page is put together. */
class TopicsTest {
    private val now = 1_800_000_000L
    private fun post(n: Int, text: String, tags: List<String> = emptyList(), at: Long = now - 3600) =
        Event("%064x".format(n), at, 1, tags.map { listOf("t", it) }, text, id = "%064x".format(n))
    private fun card(n: Int, topic: String, source: String = "s$n", at: Long = now - 3600) =
        TopicCard("c$n", "https://example.com/$n", "Story $n", "", null, source, source, at, topic)

    @Test fun postsAreSortedIntoTopics() {
        assertEquals("ai", TopicGuess.of(post(1, "Tried a local LLM with ollama today")).first())
        assertEquals("food", TopicGuess.of(post(2, "Sunday roast", listOf("foodstr"))).first())
        assertEquals("bitcoin", TopicGuess.of(post(3, "Stacking sats, as ever")).first())
        // Whole words only, and links don't count.
        assertTrue(TopicGuess.of(post(4, "The rainbow was lovely, see https://example.com/ai/llm")).isEmpty())
        assertTrue(TopicGuess.of(post(5, "gm")).isEmpty())
    }

    @Test fun likesFollowsAndLessShapeInterests() {
        val i = Interests(FakePrefs())
        assertEquals(i.of("food"), i.of("ai"), 0.0)
        i.learn(listOf("ai"), 1.0); i.learn(listOf("ai"), 1.0); i.learn(listOf("food"), 1.0)
        assertTrue(i.of("ai") > i.of("food") && i.of("food") > i.of("games"))
        i.follow("games", true)
        assertEquals(1.0, i.of("games"), 0.0)
        i.lessOfTopic("ai")
        assertTrue(i.of("ai") < i.of("food"))
        i.lessOfSource("verge")
        assertEquals(0.5, i.sourcePenalty("verge"), 0.0)
        // Not interested takes some away, never below nothing.
        repeat(5) { i.learn(listOf("food"), -1.0) }
        assertTrue(i.of("food") >= 0.0)
    }

    @Test fun interestsAreKept() {
        val p = FakePrefs()
        Interests(p).apply { learn(listOf("art"), 2.0); follow("food", true); lessOfSource("bbc") }
        val again = Interests(p)
        assertTrue("food" in again.followed)
        assertTrue(again.of("art") > again.of("games"))
        assertEquals(0.5, again.sourcePenalty("bbc"), 0.0)
    }

    @Test fun theNewerCopyWins() {
        var changes = 0
        val phone = Interests(FakePrefs()) { changes++ }
        phone.follow("art", true)
        assertEquals(1, changes)
        val account = phone.toJson()
        // Another phone, older: takes the account's copy, and learning on from it doesn't lose it.
        val other = Interests(FakePrefs())
        assertTrue(other.adopt(account))
        assertTrue("art" in other.followed)
        assertFalse(other.adopt(account))                 // not newer
        other.learn(listOf("food"), 1.0)
        assertFalse(other.adopt(account))                 // this phone's is newer now
        assertTrue(other.at > phone.at)
        assertEquals(0, changes - 1)                      // taking a copy isn't a change to send back
        assertFalse(other.adopt("not json"))
        // Another app's fields survive this app writing it back; a later version isn't taken.
        val withMore = org.json.JSONObject(phone.toJson()).put("at", other.at + 5).put("sites", org.json.JSONArray(listOf("x"))).toString()
        assertTrue(other.adopt(withMore))
        assertEquals("x", org.json.JSONObject(other.toJson()).getJSONArray("sites").getString(0))
        assertFalse(other.adopt(org.json.JSONObject(withMore).put("v", 2).put("at", other.at + 50).toString()))
    }

    @Test fun seenStaysSeen() {
        val f = File.createTempFile("seen", ".txt").apply { deleteOnExit() }
        Seen(f).apply { add("t:abc"); add("t:abc"); add("note1") }
        val again = Seen(f)
        assertTrue("t:abc" in again && "note1" in again)
        assertFalse("other" in again)
        assertEquals(2, f.readLines().count { it.isNotBlank() })
    }

    @Test fun pagesMixAndCap() {
        val i = Interests(FakePrefs())
        val posts = (1..10).map { post(it, "Thinking about things $it") }
        // Twelve stories on one topic, four of them from one site.
        val cards = (1..12).map { card(it, "news", if (it <= 4) "bbc" else "s$it") }
        val page = Arrange.page(posts, cards, i, now)
        val stories = page.filterIsInstance<Item.Card>()
        assertEquals(3, stories.size)                                   // three a topic a page
        assertTrue(stories.count { it.c.sourceId == "bbc" } <= 2)       // two a site
        page.zipWithNext().forEach { (a, b) -> assertFalse(a is Item.Card && b is Item.Card) }
    }

    @Test fun whatYouFollowComesFirst() {
        val i = Interests(FakePrefs()).apply { follow("food", true) }
        val page = Arrange.page(listOf(post(1, "Hello there")), listOf(card(1, "games"), card(2, "food")), i, now)
        assertEquals("food", (page.first { it is Item.Card } as Item.Card).c.topic)
    }

    @Test fun freshBeatsOld() {
        val i = Interests(FakePrefs())
        val page = Arrange.page(emptyList(), listOf(card(1, "games", at = now - 3 * 86400), card(2, "food", at = now - 600)), i, now)
        assertEquals("c2", (page.first() as Item.Card).c.id)
        // Out of posts: stories may follow stories.
        assertEquals(2, page.size)
    }
}

/** SharedPreferences in memory, for what Interests keeps. */
private class FakePrefs : SharedPreferences {
    val m = HashMap<String, Any?>()
    override fun getAll() = m
    override fun getString(k: String, d: String?) = m[k] as? String ?: d
    @Suppress("UNCHECKED_CAST") override fun getStringSet(k: String, d: MutableSet<String>?) = (m[k] as? Set<String>)?.toMutableSet() ?: d
    override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
    override fun contains(k: String) = k in m
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(k: String, v: String?) = apply { m[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { m[k] = v?.toSet() }
        override fun putInt(k: String, v: Int) = apply { m[k] = v }
        override fun putLong(k: String, v: Long) = apply { m[k] = v }
        override fun putFloat(k: String, v: Float) = apply { m[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { m[k] = v }
        override fun remove(k: String) = apply { m.remove(k) }
        override fun clear() = apply { m.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
