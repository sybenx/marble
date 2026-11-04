package cc.aaro.cosign.reader

import cc.aaro.qrst.core.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** marble-content-fixtures/fixtures/reader/vectors.json (reader.md), copied to resources/reader. */
class ReaderVectorsTest {
    private val v = JSONObject(javaClass.getResource("/reader/vectors.json")!!.readText())
    private fun each(k: String, f: (JSONObject) -> Unit) { val a = v.getJSONArray(k); for (i in 0 until a.length()) f(a.getJSONObject(i)) }
    private fun tags(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } }

    @Test fun kindOf() = each("kindOf") { c ->
        val e = c.getJSONObject("event")
        val got = ReaderRules.kindOf(Event("p", 1, e.getInt("kind"), tags(e.optJSONArray("tags")), e.optString("content")))
        assertEquals(c.getString("name"), c.getString("is"), got.name.lowercase())
    }

    @Test fun keys() {
        each("digestKey") { c -> assertEquals(c.getString("key"), ReaderRules.digestKey(c.getString("pubkey"), c.getLong("created_at"))) }
        each("feedItemKey") { c -> assertEquals(c.getString("key"), ReaderRules.feedItemKey(c.optString("link"), c.optString("guid"))) }
    }

    @Test fun read() = each("read") { c ->
        val s = c.getJSONObject("seen"); val i = c.getJSONObject("item")
        fun longs(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.getLong(it) } ?: emptyMap()
        val days = s.optJSONObject("days")?.let { d -> d.keys().asSequence().flatMap { d.getString(it).split(' ').filter { k -> k.isNotEmpty() } }.toSet() } ?: emptySet()
        assertEquals(c.getString("name"), c.getBoolean("read"), ReaderRules.isRead(i.getString("key"), i.getString("source"), i.getLong("at"), ReaderRules.Seen(days, longs(s.optJSONObject("upto")), longs(s.optJSONObject("unread")))))
    }

    @Test fun mergeSeen() = each("mergeSeen") { c ->
        val out = ReaderRules.mergeSeen(c.getJSONObject("a"), c.getJSONObject("b")); val want = c.getJSONObject("out")
        val wd = want.getJSONObject("days")
        for (k in wd.keys()) assertEquals(c.getString("name"), (0 until wd.getJSONArray(k).length()).map { wd.getJSONArray(k).getString(it) }.toSet(), out.getJSONObject("days").getString(k).split(' ').toSet())
        for (name in listOf("upto", "unread")) { val w = want.getJSONObject(name); for (k in w.keys()) assertEquals("${c.getString("name")} $name $k", w.getLong(k), out.getJSONObject(name).getLong(k)) }
        for (k in want.keys()) if (k !in setOf("days", "upto", "unread")) assertEquals(want.get(k).toString(), out.get(k).toString())
    }

    @Test fun mergeReader() = each("mergeReader") { c ->
        val out = ReaderRules.mergeReader(c.getJSONObject("a"), c.getJSONObject("b")); val want = c.getJSONObject("out")
        fun byId(name: String, key: String) = out.getJSONArray(name).let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString(key) to o } } }
        for (name in listOf("sources", "folders")) {
            val w = want.getJSONObject(name); val got = byId(name, "id")
            for (id in w.keys()) { val fields = w.getJSONObject(id); for (f in fields.keys()) assertEquals("$name $id $f", fields.get(f).toString(), got[id]!!.opt(f).toString()) }
        }
        val wp = want.getJSONObject("people"); for (f in wp.keys()) assertEquals("people $f", wp.get(f).toString(), out.getJSONObject("people").get(f).toString())
        val wm = want.getJSONArray("muted"); assertEquals((0 until wm.length()).map { wm.getString(it) }.toSet(), byId("muted", "source").keys)
    }

    @Test fun discussion() = each("discussion") { c ->
        val i = c.getJSONObject("item")
        fun s(k: String) = i.optString(k).takeIf { it.isNotEmpty() }
        assertEquals(c.getString("name"), c.getString("discussion"), ReaderRules.discussion(s("link"), s("comments"), s("replies"), s("guid"), s("description")))
    }

    @Test fun addressKey() = each("addressKey") { c -> assertEquals(c.getString("key"), ReaderRules.addressKey(c.getInt("kind"), c.getString("pubkey"), c.getString("d"))) }

    @Test fun fitSeen() = each("fitSeen") { c ->
        val out = ReaderRules.fitSeen(JSONObject(c.getJSONObject("in").toString()), c.getLong("now")); val want = c.getJSONObject("out")
        for (name in listOf("upto", "unread", "days")) want.optJSONArray(name)?.let { w ->
            assertEquals("${c.getString("name")} $name", (0 until w.length()).map { w.getString(it) }.toSet(), out.optJSONObject(name)?.keys()?.asSequence()?.toSet() ?: emptySet<String>())
        }
        if (want.has("maxBytes")) assertTrue(c.getString("name"), out.toString().toByteArray().size <= want.getInt("maxBytes"))
    }
}
