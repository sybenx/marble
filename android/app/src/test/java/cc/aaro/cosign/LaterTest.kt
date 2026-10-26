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

    @Test fun pageSavedByItsAddress() {
        val l = Later(Prefs())
        val a = l.savePage("https://example.com/a", "example.com")!!
        l.done(a, true)
        // Saved again: the same item comes back (not done), not a second one; another page on the same site is its own.
        assertEquals(a.id, l.savePage("https://example.com/a", "example.com")!!.id)
        l.savePage("https://example.com/b", "example.com")
        assertEquals(2, l.shown().size)
        assertNull(l.shown().first { it.id == a.id }.done)
        // In the account's copy as an item like any other, with its url (no new field for Marble desktop).
        val back = Later.fromJson(l.json()).first { it.id == a.id }
        assertEquals("https://example.com/a", back.url)
        assertTrue(back.extra.isEmpty())
    }

    @Test fun pageTitledOnceThePageSays() {
        val l = Later(Prefs())
        val a = l.savePage("https://example.com/a", "example.com")!!
        l.retitle(a.id, "example.com", "  A good read ")
        val b = l.shown().first { it.id == a.id }
        assertEquals("A good read", b.text)
        assertTrue(b.updated > a.updated || b.updated >= now)
        // Only while it still shows what it was saved with.
        l.retitle(a.id, "example.com", "Something else")
        assertEquals("A good read", l.shown().first { it.id == a.id }.text)
    }

    @Test fun keptFromAShareWithItsQuote() {
        val l = Later(Prefs())
        val a = l.keep("ask tom which edition", quote = "the paperback with the green cover, not the anniversary one", from = "Signal")!!
        val back = Later.fromJson(l.json()).first { it.id == a.id }
        assertEquals("the paperback with the green cover, not the anniversary one", back.quote)
        assertEquals("Signal", back.from)
        // Known now, so not carried twice as an unknown field.
        assertTrue(back.extra.isEmpty())
        // A shared link kept again comes back, with the new words.
        val b = l.keep("for the trip", url = "https://example.com/x")!!
        assertEquals(b.id, l.keep("for the trip, really", url = "https://example.com/x")!!.id)
        assertEquals("for the trip, really", l.shown().first { it.id == b.id }.text)
    }

    @Test fun sharedSorted() {
        val link = Shared.of("The city that fought Flock https://www.theverge.com/x/flock.", null)!!
        assertEquals("https://www.theverge.com/x/flock", link.url)
        assertEquals("The city that fought Flock", link.title)
        assertEquals("", link.words)
        assertEquals("Its subject", Shared.of("https://a.example/b", "Its subject")!!.title)
        assertEquals("call the plumber", Shared.of("  call the plumber ", null)!!.words)
        val long = Shared.of("x".repeat(300), null)!!
        assertEquals("", long.words); assertEquals(300, long.quote!!.length)
        assertNull(Shared.of("  ", null))
    }
}
