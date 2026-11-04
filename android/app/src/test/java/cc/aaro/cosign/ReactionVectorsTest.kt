package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** marble-content-fixtures/fixtures/reactions/vectors.json (§10), copied to resources/reactions. */
class ReactionVectorsTest {
    private val v = JSONObject(javaClass.getResource("/reactions/vectors.json")!!.readText())
    private fun tags(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } }
    private fun reaction(o: JSONObject, at: Long = o.optLong("created_at")) = Event("me", at, 7, listOf(listOf("e", "post")) + tags(o.optJSONArray("tags")), o.optString("content"))
    private fun shown(x: Any?): ReactionRules.Shown? = when (x) {
        is String -> ReactionRules.Shown(x)
        is JSONObject -> ReactionRules.Shown(":${x.getString("shortcode")}:", x.getString("url"))
        else -> null
    }
    private fun list(a: JSONArray) = (0 until a.length()).map { a.get(it) }

    @Test fun defaults() = assertEquals(list(v.getJSONArray("defaults")), ReactionRules.DEFAULTS)

    @Test fun tray() {
        val cases = v.getJSONArray("tray")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val own = c.getJSONArray("own").let { a -> (0 until a.length()).map { reaction(a.getJSONObject(it)) } }
            val recents = ReactionRules.recents(own, "me", 12)
            assertEquals(c.getString("name"), list(c.getJSONArray("recents")).map(::shown), recents)
            assertEquals(c.getString("name"), list(c.getJSONArray("tray")).map(::shown), ReactionRules.tray(recents))
        }
    }

    @Test fun incoming() {
        val cases = v.getJSONArray("incoming")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.toString(), shown(c.opt("shows").takeIf { it != JSONObject.NULL }), ReactionRules.shown(reaction(c, 1)))
        }
    }

    @Test fun outgoing() {
        val note = Event("author", 1, 1, emptyList(), "", id = "post")
        val cases = v.getJSONArray("outgoing")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = shown(c.get("emoji"))!!
            assertEquals(c.getString("content"), ReactionRules.wire(s.emoji))
            // Beyond the post it's about (e, p, k): what the vector lists.
            assertEquals(tags(c.getJSONArray("tags")), ReactionRules.tags(note, s).filter { it[0] !in setOf("e", "p", "k") })
        }
    }

    @Test fun changes() {
        val note = Event("author", 1, 1, emptyList(), "", id = "post")
        val cases = v.getJSONArray("changes")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val before = list(c.getJSONArray("before")).map { it as String }
            val got = ReactionRules.sends(before, shown(c.opt("to").takeIf { it != JSONObject.NULL }), note)
            val want = c.getJSONArray("sends").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            assertEquals(c.getString("name"), want.size, got.size)
            want.forEachIndexed { j, w ->
                assertEquals(c.getString("name"), w.getInt("kind"), got[j].kind)
                if (w.has("content")) assertEquals(c.getString("name"), w.getString("content"), got[j].content)
                if (w.has("tags")) assertEquals(c.getString("name"), tags(w.getJSONArray("tags")), got[j].tags)
            }
        }
    }
}
