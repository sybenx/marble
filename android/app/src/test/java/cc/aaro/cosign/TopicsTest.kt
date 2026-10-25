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

    @Test fun likesAreCountedOnce() {
        val i = Interests(FakePrefs())
        assertEquals(null, i.likesTo)
        i.seed(mapOf("ai" to 2.0), likesTo = 1_700_000_000L)
        assertEquals(1_700_000_000L, i.likesTo)
        i.liked(listOf("food"), caughtUp = false)          // not caught up: the next catch-up still counts from before
        assertEquals(1_700_000_000L, i.likesTo)
        i.liked(listOf("food"), caughtUp = true)
        assertTrue(i.likesTo!! > 1_700_000_000L)
        // The web's field, and Marble's, travel in the same copy.
        assertEquals(i.likesTo, Interests(FakePrefs()).apply { adopt(i.toJson()) }.likesTo)
    }

    @Test fun seenStaysSeen() {
        val f = File.createTempFile("seen", ".txt").apply { deleteOnExit() }
        Seen(f).apply { add("t:abc"); add("t:abc"); add("note1") }
        val again = Seen(f)
        assertTrue("t:abc" in again && "note1" in again)
        assertFalse("other" in again)
        assertEquals(2, f.readLines().count { it.isNotBlank() })
        // A file from before times were kept still reads.
        val old = File.createTempFile("seen", ".txt").apply { deleteOnExit(); writeText("t:old\n") }
        assertTrue("t:old" in Seen(old))
    }

    @Test fun seenTravelsShortAndBounded() {
        val post = "ab".repeat(32)
        assertEquals("abababababababab", SeenSync.short(post))
        assertEquals("t0123456789abcdef", SeenSync.short("t:0123456789abcdef"))
        // Another phone's list: its short keys count as seen here.
        val f = File.createTempFile("seen", ".txt").apply { deleteOnExit() }
        val here = Seen(f)
        val json = SeenSync.encode(listOf(SeenSync.short(post) to now - 3600, "t0123456789abcdef" to now - 7200, "ffffffffffffffff" to now - 8 * 86_400), now)
        SeenSync.decode(json).forEach { (k, t) -> here.add(k, t) }
        assertTrue(post in here && "t:0123456789abcdef" in here)
        // Seen here and on the other phone: one entry, not two.
        assertFalse(here.add("t:0123456789abcdef"))
        assertFalse(here.add(post))
        assertFalse("f".repeat(64) in here)                                  // older than a week: not sent
        // At most CAP keys, the newest; small enough for NIP-44 (64 KB).
        val many = (1..5000).map { "%016x".format(it) to now - it }
        val big = SeenSync.encode(many, now)
        assertEquals(SeenSync.CAP, SeenSync.decode(big).size)
        assertTrue(big.length < 45_000)
        assertTrue(SeenSync.decode(big).any { it.first == "%016x".format(1) })
        assertTrue(SeenSync.decode("{\"v\":2}").isEmpty())
    }

    private fun linkPost(n: Int, text: String, by: String = "%064x".format(1000 + n), tags: List<String> = emptyList(), at: Long = now - 3600) =
        Event(by, at, 1, tags.map { listOf("t", it) }, text, id = "%064x".format(n))
    private val friend = Standing(follow = true, trustedBy = 0, followers = 0)

    @Test fun topicsAreStrictInTheFeed() {
        // One passing word isn't a topic; two are, and so is a hashtag.
        assertTrue(TopicGuess.hits(post(1, "Our state government is a joke, like an NPC"), strict = true).none { it.topic == "software" })
        assertEquals("ai", TopicGuess.hits(post(2, "Running a local model with ollama on my laptop"), strict = true).first().topic)
        assertEquals("games", TopicGuess.hits(post(3, "Look at this level from my game", listOf("gamedev")), strict = true).first().topic)
        assertTrue(TopicGuess.hits(post(8, "EU funding for bird protection in Cyprus", listOf("tech")), strict = true).isEmpty())   // a hashtag the text doesn't bear out
        assertTrue(TopicGuess.hits(post(4, "rust never sleeps"), strict = true).isEmpty())
        // Everyday words alone don't make a label: a post about a law that mentions phones and leaks isn't "Phones and gadgets".
        assertTrue(TopicGuess.hits(post(5, "Now he wants age checks built into your phone and the app store. A breach leaked IDs."), strict = true).none { it.topic == "gadgets" || it.topic == "privacy" })
        assertTrue(TopicGuess.hits(post(6, "Join us after dinner for coffee and the new episode"), strict = true).none { it.topic == "food" })
        assertEquals("food", TopicGuess.hits(post(7, "My sourdough recipe, and how baking it changed my mornings"), strict = true).first().topic)
    }

    @Test fun theMocksRulesLeaveOut() {
        val ok = "Wrote up how I run a local LLM with ollama on an old laptop, and what it costs https://example.com/llm-laptop"
        assertEquals(null, Calm.drop(linkPost(1, ok), "me", emptySet(), 1, "Ann", true, friend))
        assertEquals("own post", Calm.drop(linkPost(2, ok, by = "me"), "me", emptySet(), 1, "Me", true, friend))
        assertEquals("already liked", Calm.drop(linkPost(3, ok), "me", setOf("%064x".format(3)), 1, "Ann", true, friend))
        assertEquals("bot", Calm.drop(linkPost(4, ok), "me", emptySet(), 1, "Price Bot", true, friend))
        assertEquals("good-morning post", Calm.drop(linkPost(5, "GM everyone, coffee and a local LLM today https://example.com/gm-post-here"), "me", emptySet(), 1, "Ann", true, friend))
        assertEquals("no link or video, or too short", Calm.drop(linkPost(6, "Thinking about local models a lot today, they are getting good"), "me", emptySet(), 1, "Ann", true, friend))
        assertEquals("not in a language you read", Calm.drop(linkPost(7, ok), "me", emptySet(), 1, "Ann", false, friend))
        assertEquals("nobody you know follows them", Calm.drop(linkPost(8, ok), "me", emptySet(), 1, "Ann", true, Standing(false, 2, 120)))
        assertEquals(null, Calm.drop(linkPost(9, ok), "me", emptySet(), 1, "Ann", true, Standing(false, 3, 0)))
        assertEquals(null, Calm.drop(linkPost(10, ok), "me", emptySet(), 1, "Ann", true, Standing(false, 0, 300)))
        assertEquals("news feed or bot account", Calm.drop(linkPost(11, ok), "me", emptySet(), 26, "Ann", true, friend))
    }

    @Test fun newslettersStayOutFromStrangers() {
        val stranger = Standing(false, 5, 0)
        for (t in listOf("📰 **In this week's issue:** Bitcoin Core just changed the rules for AI-discoverable bugs https://nostrmag.com/x",
                "Issue #42 of the AI weekly is out, with the best agent tools of the week https://example.com/issue-42",
                "Our weekly roundup of local LLM news, models and tools you missed https://example.com/roundup",
                "New models everywhere. This edition is sponsored by Acme AI, subscribe now https://example.com/a",
                "Five good reads this week about LLM agents and the tools around them: https://a.com/1 https://b.com/2 https://c.com/3 https://d.com/4 https://e.com/5"))
            assertEquals(t, "newsletter or digest from a stranger", Calm.drop(linkPost(t.length, t), "me", emptySet(), 1, "Ann", true, stranger))
        // From someone you follow it can stay; and ordinary words near these don't trip it.
        val issue = "In this week's issue: the local LLM I run on my laptop and what it costs https://example.com/n"
        assertEquals(null, Calm.drop(linkPost(1, issue), "me", emptySet(), 1, "Ann", true, friend))
        for (t in listOf("Fixed the issue with my local LLM setup, here's what worked for me https://example.com/fix",
                "This week I finally ran an LLM agent on my own laptop, notes here https://example.com/week"))
            assertEquals(t, null, Calm.drop(linkPost(t.length + 7, t), "me", emptySet(), 1, "Ann", true, stranger))
    }

    @Test fun reasonsSayWhy() {
        val w = mapOf("ai" to 1.0)
        val e = linkPost(1, "Wrote up how I run a local LLM with ollama https://example.com/x")
        assertEquals("AI and agents · from someone you follow", Calm.score(e, w, friend, emptySet(), emptySet(), false, now)!!.reason)
        assertEquals("AI and agents · followed by 12 people you follow", Calm.score(e, w, Standing(false, 12, 0), emptySet(), emptySet(), false, now)!!.reason)
        assertEquals("AI and agents · 1.5k followers", Calm.score(e, w, Standing(false, 0, 1500), emptySet(), emptySet(), false, now)!!.reason)
        assertEquals("AI and agents · liked by 2 people you follow", Calm.score(e, w, Standing(false, 5, 0), setOf("a", "b", "c"), setOf("a", "b"), false, now)!!.reason)
        assertEquals(null, Calm.score(e, mapOf("food" to 1.0), friend, emptySet(), emptySet(), false, now))   // not one of your topics
    }

    @Test fun onePerPersonAndFewPerTopic() {
        val topics = TopicGuess.topics
        val picks = (1..60).map { i -> Pick(linkPost(i, "x", by = "%064x".format(if (i <= 3) 7 else i)), topics[i % 3], 100.0 - i, "") } +
            (61..120).map { i -> Pick(linkPost(i, "x"), topics[i % topics.size], 10.0 - i / 100.0, "") }
        val out = Calm.diversify(picks)
        assertEquals(20, out.size)                                                  // and then it ends
        assertEquals(out.size, out.map { it.e.pubkey }.distinct().size)             // one per person
        assertTrue(out.take(10).groupingBy { it.topic }.eachCount().values.all { it <= 2 })
        assertTrue(out.groupingBy { it.topic }.eachCount().values.all { it <= 5 })
    }

    @Test fun theSameStoryIsOneCard() {
        val a = Pick(linkPost(1, "Big news https://example.com/a"), "ai", 3.0, "")
        val b = Pick(linkPost(2, "Same link here https://example.com/a"), "ai", 2.0, "")
        val c = Pick(linkPost(3, "Something else entirely https://example.com/c"), "ai", 1.0, "")
        val out = Calm.collapse(listOf(a, b, c)) { "Bea" }
        assertEquals(2, out.size)
        assertEquals(listOf("Bea"), out[0].more)
    }

    @Test fun theBoxHasTheBestFitOnePerTopic() {
        val i = Interests(FakePrefs()).apply { learn(listOf("games"), 3.0); learn(listOf("food"), 1.0) }
        val s = listOf(card(1, "food", at = now - 600), card(2, "games", at = now - 7200), card(3, "games", at = now - 600), card(4, "art", at = now - 60), card(5, "ai", at = now - 3 * 86400))
        val box = Calm.todays(s, i, now)
        assertEquals(listOf("c3", "c1", "c4"), box.map { it.id })       // games first (and its newest), one per topic, nothing older than two days
        i.follow("art", true)
        assertEquals("art", Calm.stories(s, i, now).first().topic)      // followed topics first on "More stories"
        i.hide("hiddenSources", "s4")
        assertTrue(Calm.stories(s, i, now).none { it.id == "c4" })
    }

    @Test fun storiesKnowWhereTheyreDiscussed() {
        val d = Discussion.list(listOf(mapOf("url" to "https://news.ycombinator.com/item?id=1", "site" to "Hacker News", "comments" to 312L),
            mapOf("url" to "https://stacker.news/items/2", "site" to "Stacker News")), "https://example.com/a")
        assertEquals(listOf(Discussion("https://news.ycombinator.com/item?id=1", "Hacker News", 312L), Discussion("https://stacker.news/items/2", "Stacker News")), d)
        // A text post on Stacker News is its own discussion: nothing more to show.
        assertTrue(Discussion.list(listOf(mapOf("url" to "https://stacker.news/items/3", "site" to "Stacker News")), "https://stacker.news/items/3").isEmpty())
        // The older single field still works.
        assertEquals("Lobsters", Discussion.list(null, "https://example.com/b", "https://lobste.rs/s/x").single().site)
        // Kept when saved and read back.
        val c = TopicCard("c", "https://example.com/a", "T", "", null, "s", "S", 1L, "ai", d)
        assertEquals(c, TopicCard.fromMap(c.toMap()))
    }
}

/** SharedPreferences in memory, for what Interests keeps. */
internal class FakePrefs : SharedPreferences {
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
