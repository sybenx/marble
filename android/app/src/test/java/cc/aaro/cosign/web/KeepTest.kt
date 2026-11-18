package cc.aaro.cosign.web

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Keep on a Visiting chip is a choice of that site, so it can ask to sign in from then on (each sign-in still asks). */
class KeepTest {
    private class Prefs : SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll() = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = d
        override fun getLong(k: String?, d: Long) = d
        override fun getFloat(k: String?, d: Float) = d
        override fun getBoolean(k: String?, d: Boolean) = d
        override fun contains(k: String?) = m.containsKey(k)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { m[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = this
            override fun putInt(k: String?, v: Int) = this
            override fun putLong(k: String?, v: Long) = this
            override fun putFloat(k: String?, v: Float) = this
            override fun putBoolean(k: String?, v: Boolean) = this
            override fun remove(k: String?) = apply { m.remove(k) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before fun reset() { WebSites.list.value = WebSites.builtIn }

    @Test fun keepTurnsOnCanAskToSignIn() {
        val v = WebSites.visiting("noStrudel", "https://nostrudel.ninja/")!!
        WebSites.put(v)
        assertFalse(WebSites.byOrigin(v.origin)!!.asks)
        WebPaneState(Prefs()).keep(v)
        val kept = WebSites.byOrigin(v.origin)!!
        assertEquals(WebSite.Kind.KEPT, kept.kind)
        assertTrue(kept.asks)
    }
}
