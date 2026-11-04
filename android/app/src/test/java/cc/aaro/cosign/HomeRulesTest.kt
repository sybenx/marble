package cc.aaro.cosign

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** marble-content-fixtures fixtures/home/rules.json (home-rules.md), copied to resources/home. */
class HomeRulesTest {
    private val v = JSONObject(javaClass.getResource("/home/rules.json")!!.readText())
    private fun arr(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.get(it) }

    @Test fun focus() { val a = v.getJSONArray("focus"); for (n in 0 until a.length()) { val c = a.getJSONObject(n); val i = c.getJSONObject("in")
        val notes = arr(i.optJSONArray("notes")).map { (it as JSONObject).let { o -> HomeRules.Note(o.getString("id"), o.getString("text"), o.getLong("created")) } }
        val searches = arr(i.optJSONArray("searches")).map { (it as JSONObject).let { o -> HomeRules.Search(o.getString("text"), o.getLong("at")) } }
        val seen = i.optJSONObject("seen")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } ?: emptyMap()
        val offered = i.optJSONObject("offered")?.let { it.getString("id") to it.getLong("at") }
        val rows = HomeRules.focus(arr(i.optJSONArray("inView")).map { it as String }, notes, searches, v.getLong("now"), seen, offered, i.optBoolean("older", true))
        assertEquals(c.getString("name"), arr(c.getJSONArray("rows")).map { it as String }, rows.map { it.toString() })
    } }

    @Test fun goTo() { val places = arr(v.getJSONArray("places")).map { (it as JSONObject).getString("name") }
        val a = v.getJSONArray("goTo"); for (n in 0 until a.length()) { val c = a.getJSONObject(n)
            val got = HomeRules.goTo(c.getString("typed"), places, arr(c.getJSONArray("notes")).map { (it as JSONObject).getString("text") })
            val want = if (c.isNull("place")) null else c.getString("place")
            assertEquals(c.getString("typed"), want, got?.first)
            if (want != null) assertEquals(c.getString("typed") + " below", c.getBoolean("below"), got!!.second)
        } }
}
