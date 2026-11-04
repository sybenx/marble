package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Marble learns from you, for Today's news: light, on this phone where it should be, merged where it's synced. */
class LearningTest {
    init { LEARNING = true }
    /** SharedPreferences in memory: only what Interests uses. */
    private class Prefs : android.content.SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll() = m
        override fun getString(k: String, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = (m[k] as? Long) ?: d
        override fun getFloat(k: String, d: Float) = d
        override fun getBoolean(k: String, d: Boolean) = (m[k] as? Boolean) ?: d
        override fun contains(k: String) = k in m
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit() = object : android.content.SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { m[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { m[k] = v }
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

    private fun card(id: String, topic: String, title: String = "story $id", at: Long = 1000) =
        TopicCard(id, "https://example.com/$id", title, "", null, "src-$topic", "Site", at, topic)

    @Test fun aTopicGetsAsManyPlacesAsItWeighs() {
        val i = Interests(Prefs())
        i.learn(listOf("food"), 20.0)
        // Ten food stories, newest first, then one each of three other topics.
        val sorted = (1..10).map { card("f$it", "food") } + listOf(card("a", "art"), card("b", "bitcoin"), card("c", "cars"))
        val out = Calm.spread(sorted, i)
        // Every sixth place goes to a topic not yet shown.
        assertTrue(out[5].topic != "food")
        // Nothing is dropped; it only moves down.
        assertEquals(sorted.toSet(), out.toSet())
    }

    @Test fun aWeakTopicIsCappedNearTheTop() {
        val i = Interests(Prefs())
        i.learn(listOf("art"), 50.0); i.learn(listOf("food"), 0.01)
        val sorted = (1..8).map { card("f$it", "food") } + (1..8).map { card("a$it", "art") }
        val top = Calm.spread(sorted, i).take(6)
        assertTrue(top.count { it.topic == "food" } <= 3)
    }

    @Test fun entitiesStayOnThisPhone() {
        val i = Interests(Prefs())
        i.noticed("Richard Powers The Overstory")
        assertTrue(i.entityFit("A new novel by Richard Powers") > 0.9)
        assertEquals(0.0, i.entityFit("Weighing your flour"), 0.0)
        assertFalse(i.toJson().contains("powers"))
        assertEquals(setOf("overstory", "richard", "powers"), Entities.of("The Overstory, by Richard Powers"))
    }

    @Test fun syncedCopiesMergeTopicByTopic() {
        val a = Interests(Prefs()); a.learn(listOf("food"), 3.0); a.hide("hiddenPeople", "p1")
        val b = Interests(Prefs()); b.learn(listOf("art"), 2.0); b.hide("hiddenSources", "s1")
        // b is newer: its topics win where both have them; a's own are kept; hidden from both.
        Thread.sleep(1100); b.learn(listOf("art"), 0.1)
        assertTrue(a.adopt(b.toJson()))
        val j = org.json.JSONObject(a.toJson())
        assertTrue(j.getJSONObject("weights").has("food") && j.getJSONObject("weights").has("art"))
        assertTrue(a.hidden("hiddenPeople", "p1") && a.hidden("hiddenSources", "s1"))
        // A copy from a later app version is left alone.
        assertFalse(a.adopt(org.json.JSONObject(b.toJson()).put("v", 2).toString()))
    }
}
