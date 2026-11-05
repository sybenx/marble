package cc.aaro.cosign.reader

import cc.aaro.cosign.Later
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.12.29 re-check A: Star and Keep share one Undo per item, back to the note as it was, never a duplicate or a loss. */
class NoteUndoTest {
    private class Prefs : android.content.SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll() = m
        override fun getString(k: String, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST") override fun getStringSet(k: String, d: MutableSet<String>?) = (m[k] as? Set<String>)?.toMutableSet() ?: d
        override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String) = k in m
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit() = object : android.content.SharedPreferences.Editor {
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
    private val url = "https://example.com/story"
    private fun live(l: Later) = l.items.value.filter { it.deleted == null && it.url == url }

    @Test fun unstarThenUndoIsOneNoteBack() {
        val l = Later(Prefs()); val n = l.keep("my words", url)!!
        l.remove(n)
        NoteUndo.undo(l, NoteChange(url, before = n, after = null))
        assertEquals(listOf("my words"), live(l).map { it.text })
    }

    @Test fun keepOnAStarredItemThenUnstarThenUndoKeepsTheEarlierWords() {
        val l = Later(Prefs()); val n = l.keep("my earlier words", url)!!
        l.keep("a new note", url)                      // Keep (same note, new words)
        l.remove(l.forUrl(url)!!)                      // un-star: removed (its id has a marker now)
        NoteUndo.undo(l, NoteChange(url, before = n, after = null))
        assertEquals(listOf("my earlier words"), live(l).map { it.text })
        assertTrue(live(l).single().id != n.id)        // a fresh id: a removed one never comes back
    }

    @Test fun aNewerEditMergedInIsLeftAlone() {
        val l = Later(Prefs()); val n = l.keep("mine", url)!!
        val after = l.keep("kept with a note", url)!!
        l.setText(l.forUrl(url)!!, "edited on another device")  // newer than this phone's write
        NoteUndo.undo(l, NoteChange(url, before = n, after = after))
        assertEquals(listOf("edited on another device"), live(l).map { it.text })
    }

    @Test fun keepWithNoNoteBeforeIsTakenAway() {
        val l = Later(Prefs())
        val after = l.keep("kept", url)!!
        NoteUndo.undo(l, NoteChange(url, before = null, after = after))
        assertTrue(live(l).isEmpty())
    }

    @Test fun anotherNoteThereNowIsLeftAlone() {
        val l = Later(Prefs()); val n = l.keep("mine", url)!!
        l.remove(n)
        l.keep("someone else's, since", url)
        NoteUndo.undo(l, NoteChange(url, before = n, after = null))
        assertEquals(listOf("someone else's, since"), live(l).map { it.text })
        assertNull(null)
    }
}
