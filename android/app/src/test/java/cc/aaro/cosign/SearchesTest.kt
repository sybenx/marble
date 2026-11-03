package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Searches: on this phone, 30 days; an edit straight after is the same search, and the pages opened sit under it. */
class SearchesTest {
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
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
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

    @Test fun anEditIsTheSameSearch() {
        val p = Prefs(); val s = Searches(p)
        val a = s.searched("foo")
        val b = s.searched("foo bar", a.id)
        assertEquals(a.id, b.id)
        assertEquals(listOf("foo bar"), s.all.value.map { it.text })
        // A new search (no current one): its own.
        val c = s.searched("weather")
        assertNotEquals(a.id, c.id)
        assertEquals(listOf("weather", "foo bar"), Searches(p).all.value.map { it.text })   // as this phone keeps it
    }

    @Test fun pagesSitUnderTheirSearch() {
        val s = Searches(Prefs())
        val a = s.searched("flights to lisbon")
        s.opened(a.id, "https://tap.pt/a", "tap.pt")
        s.opened(a.id, "https://skyscanner.net/b", "skyscanner.net")
        s.opened(a.id, "https://tap.pt/a", "tap.pt")
        assertEquals(listOf("https://tap.pt/a", "https://skyscanner.net/b"), s.all.value.single().pages.map { it.url })
        // The same words again: back on top, pages and all, not twice.
        s.searched("weather")
        val again = s.searched("Flights to  Lisbon")
        assertEquals(a.id, again.id)
        assertEquals(2, again.pages.size)
        assertEquals(listOf("Flights to  Lisbon", "weather"), s.all.value.map { it.text })
    }

    @Test fun oldOnesFade() {
        val p = Prefs()
        p.edit().putString("searches", Searches.toJson(listOf(SearchEntry("x", "old", 1), SearchEntry("y", "new", cc.aaro.cosign.core.Clock.now())))).apply()
        assertEquals(listOf("new"), Searches(p).all.value.map { it.text })
    }
}
