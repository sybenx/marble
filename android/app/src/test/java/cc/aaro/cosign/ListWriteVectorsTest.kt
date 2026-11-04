package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** marble-content-fixtures/fixtures/lists/vectors.json (list-writes.md), copied to resources/lists. */
class ListWriteVectorsTest {
    private val v = JSONObject(javaClass.getResource("/lists/vectors.json")!!.readText())
    private val me = v.getString("me"); private val now = v.getLong("now")
    private fun tags(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } }
    private fun strs(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.getString(it) } }
    private fun event(o: JSONObject) = Event(o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), tags(o.getJSONArray("tags")), o.optString("content"), o.getString("id"))
    private fun plan(c: JSONObject): ListWrite.Plan {
        val found = c.getJSONArray("found").let { a -> (0 until a.length()).map { event(a.getJSONObject(it)) } }
        val ch = c.getJSONObject("change")
        return ListWrite.plan(found, me, c.getInt("kind"), c.optString("d").ifEmpty { null }, ListWrite.Change(strs(ch.optJSONArray("add")), strs(ch.optJSONArray("remove"))), c.getBoolean("expected"), now)
    }
    private fun each(k: String, f: (JSONObject) -> Unit) { val a = v.getJSONArray(k); for (i in 0 until a.length()) f(a.getJSONObject(i)) }

    @Test fun writes() = each("writes") { c ->
        val p = plan(c) as? ListWrite.Plan.Write ?: error("${c.getString("name")}: ${plan(c)}")
        val w = c.getJSONObject("write")
        assertEquals(c.getString("name"), tags(w.getJSONArray("tags")), p.tags)
        assertEquals(c.getString("name"), w.getString("content"), p.content)
        assertEquals(c.getString("name"), w.getLong("created_at"), p.createdAt)
        // What it makes passes the last check too.
        p.old?.let { o -> assertEquals(c.getString("name"), null, ListWrite.guard(ListWrite.Version(o.kind, o.createdAt, o.content, o.tags), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags))) }
    }

    @Test fun nothing() = each("nothing") { c -> assertEquals(c.getString("name"), ListWrite.Plan.Nothing, plan(c)) }

    @Test fun refuse() = each("refuse") { c ->
        val p = plan(c)
        assertTrue("${c.getString("name")}: $p", p is ListWrite.Plan.Refuse && p.why == c.getString("why"))
    }

    @Test fun guard() = each("guard") { c ->
        fun ver(o: JSONObject) = ListWrite.Version(o.getInt("kind"), o.getLong("created_at"), o.optString("content"), tags(o.getJSONArray("tags")))
        val why = c.opt("why").takeIf { it != JSONObject.NULL } as String?
        assertEquals(c.getString("name"), why, ListWrite.guard(ver(c.getJSONObject("old")), ver(c.getJSONObject("next"))))
    }
}
