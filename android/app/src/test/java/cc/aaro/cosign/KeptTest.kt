package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The marbles you kept, on your account a year to an event (d=marble/marbles/<year>). */
class KeptTest {
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

    @Test fun keptOnceAndReadBack() {
        val p = Prefs(); val k = Kept(p)
        val years = ArrayList<String>(); k.changed = { years += it }
        assertTrue(k.keep("2026-10-27"))
        assertFalse(k.keep("2026-10-27"))            // the second look: no flourish, nothing new
        assertEquals(listOf("2026"), years)
        assertEquals(setOf("marble-2026-10-27"), Kept(p).all.value.keys)   // as this phone keeps it
    }

    @Test fun takenInFromBeforeSync() {
        val k = Kept(Prefs())
        k.takeIn(setOf("marble-2026-10-26", "not-a-marble"))
        assertEquals(setOf("marble-2026-10-26"), k.all.value.keys)
        assertEquals(java.time.LocalDate.parse("2026-10-26").toEpochDay() * 86_400, k.all.value["marble-2026-10-26"]!!.at)
    }

    @Test fun mergedByYearKeepingTheFirst() {
        val k = Kept(Prefs())
        k.keep("2026-10-27")
        val mineAt = k.all.value["marble-2026-10-27"]!!.at
        // The account's 2026 has an older keeping of the same day and one this phone hasn't.
        val ahead = k.merge("""{"v":1,"at":1,"kept":[{"id":"marble-2026-10-27","at":${mineAt - 100}},{"id":"marble-2026-10-20","at":5}]}""", "2026")
        assertFalse(ahead)
        assertEquals(mineAt - 100, k.all.value["marble-2026-10-27"]!!.at)
        assertEquals(2, k.all.value.size)
        // One of 2026 the copy lacks: this phone is ahead for that year.
        k.keep("2026-10-28")
        assertTrue(k.merge("""{"v":1,"kept":[{"id":"marble-2026-10-20","at":5}]}""", "2026"))
        val o = org.json.JSONObject(k.json("2026"))
        assertEquals(3, o.getJSONArray("kept").length())
    }

    @Test fun theAwardIsKeptAndSynced() {
        val k = Kept(Prefs())
        k.keep("2026-10-26")
        k.awarded("marble-2026-10-26", """{"kind":8,"id":"ab"}""")
        val o = org.json.JSONObject(k.json("2026")).getJSONArray("kept").getJSONObject(0)
        assertEquals(8, o.getJSONObject("award").getInt("kind"))
        // The account's copy without it: this phone is ahead; with it from elsewhere, it's taken in.
        assertTrue(k.merge("""{"v":1,"kept":[{"id":"marble-2026-10-26","at":1}]}""", "2026"))
        val other = Kept(Prefs())
        other.merge(k.json("2026"), "2026")
        assertEquals("ab", org.json.JSONObject(other.all.value["marble-2026-10-26"]!!.award!!).getString("id"))
    }

    @Test fun aLaterVersionIsLeftAlone() {
        val k = Kept(Prefs())
        assertFalse(k.merge("""{"v":2,"kept":[{"id":"marble-2026-10-01","at":1}]}""", "2026"))
        assertTrue(k.all.value.isEmpty())
    }
}
