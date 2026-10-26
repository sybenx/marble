package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Later's account copy (kind 30078, d=marble/later), as agreed with Marble desktop. */
class LaterTest {
    /** SharedPreferences in memory: only what Later uses. */
    private class Prefs : android.content.SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll() = m
        override fun getString(k: String, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun getBoolean(k: String, d: Boolean) = d
        override fun contains(k: String) = k in m
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit() = object : android.content.SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { m[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { m[k] = v }
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
            override fun putBoolean(k: String, v: Boolean) = this
            override fun remove(k: String) = apply { m.remove(k) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    private val now = System.currentTimeMillis() / 1000

    @Test fun savedThenReadBack() {
        val l = Later(Prefs())
        val a = l.save("  the overstory book  ")!!
        assertEquals("the overstory book", a.text)
        val again = Later.fromJson(l.json())
        assertEquals(listOf("the overstory book"), again.map { it.text })
        assertNull(again[0].done); assertNull(again[0].deleted)
        // Saving the same words again doesn't make a second one.
        l.save("The Overstory Book")
        assertEquals(1, l.shown().size)
    }

    @Test fun mergeTakesTheNewerOfEach() {
        val l = Later(Prefs())
        val mine = l.save("fix the gate latch")!!
        // The other device ticked it done later; and has one this phone hasn't.
        val theirs = """{"v":1,"at":${now + 100},"items":[
            {"id":"${mine.id}","text":"fix the gate latch","created":${mine.created},"updated":${now + 100},"done":${now + 100},"deleted":null},
            {"id":"aaaaaaaaaaaaaaaa","text":"learn sourdough","created":${now - 50},"updated":${now - 50},"done":null,"deleted":null}]}"""
        val ahead = l.merge(theirs)
        assertFalse("nothing this phone has is newer", ahead)
        assertEquals(now + 100, l.items.value.first { it.id == mine.id }.done)
        assertEquals(2, l.shown().size)
        // Now this phone changes something: the copy is behind.
        l.save("hotdog place near grandmas")
        assertTrue(l.merge(theirs))
    }

    @Test fun desktopsRemovedMarkerHidesIt() {
        val l = Later(Prefs())
        val mine = l.save("paint the gate")!!
        // Marble desktop removes it: the text is cleared, only the marker stays.
        l.merge("""{"v":1,"at":${now + 10},"items":[{"id":"${mine.id}","text":"","created":${mine.created},"updated":${mine.updated + 10},"done":null,"deleted":${now + 10}}]}""")
        assertTrue(l.shown().isEmpty())
        assertEquals(1, l.items.value.size)
        // And it's written back as a marker, so it stays removed everywhere.
        val back = org.json.JSONObject(l.json()).getJSONArray("items").getJSONObject(0)
        assertEquals("", back.getString("text"))
    }

    @Test fun removingHereLeavesOnlyAMarker() {
        val l = Later(Prefs())
        val a = l.save("old idea")!!
        l.remove(l.items.value.first { it.id == a.id })
        val back = org.json.JSONObject(l.json()).getJSONArray("items").getJSONObject(0)
        assertEquals("", back.getString("text"))
        assertTrue(back.getLong("deleted") > 0)
        assertTrue(back.getLong("updated") > a.updated)   // even within the same second
    }

    @Test fun unknownFieldsAreKept() {
        val l = Later(Prefs())
        l.merge("""{"v":1,"at":$now,"vault":"x","items":[{"id":"bbbbbbbbbbbbbbbb","text":"keep me","created":$now,"updated":$now,"done":null,"deleted":null,"color":"red"}]}""")
        val o = org.json.JSONObject(l.json())
        assertEquals("x", o.getString("vault"))
        assertEquals("red", o.getJSONArray("items").getJSONObject(0).getString("color"))
    }

    @Test fun aLaterVersionIsLeftAlone() {
        val l = Later(Prefs())
        l.save("mine")
        assertFalse(l.merge("""{"v":2,"items":[{"id":"cccccccccccccccc","text":"from the future","created":$now,"updated":$now}]}"""))
        assertEquals(listOf("mine"), l.shown().map { it.text })
    }
}
