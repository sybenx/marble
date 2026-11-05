package cc.aaro.cosign.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** marble-content-fixtures fixtures/sync/guard.json (reader.md §6), copied to resources/sync: when a synced copy may be written, and when it's done. */
class SyncGuardTest {
    private val v = JSONObject(javaClass.getResource("/sync/guard.json")!!.readText())
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun each(k: String, f: (JSONObject) -> Unit) { val a = v.getJSONArray(k); for (n in 0 until a.length()) f(a.getJSONObject(n)) }

    @Test fun decide() = each("cases") { c -> val i = c.getJSONObject("in")
        val d = SyncGuard.decide(i.getBoolean("complete"), i.optBoolean("full"), i.optLong("found"), i.optLong("seenAt"), i.optInt("misses"), i.optLong("expires"), i.getLong("now"))
        assertEquals(c.getString("name"), c.getString("do"), d.doThis.name.lowercase())
        assertEquals(c.getString("name") + " misses", c.getInt("misses"), d.misses)
    }

    @Test fun completeAndFull() = each("complete") { c ->
        val w = strings(c.getJSONArray("write")); val a = strings(c.getJSONArray("answeredBy"))
        assertEquals(c.getString("name"), c.getBoolean("complete"), SyncGuard.complete(w, a))
        if (c.has("full")) assertEquals(c.getString("name") + " full", c.getBoolean("full"), SyncGuard.full(w, a))
    }

    @Test fun written() = each("written") { c ->
        assertEquals(c.getString("name"), c.getBoolean("written"), SyncGuard.written(strings(c.getJSONArray("write")), strings(c.getJSONArray("okBy"))))
    }

    @Test fun retryAfter() = each("retryAfter") { c -> assertEquals("after ${c.getInt("failures")}", c.getLong("seconds"), SyncGuard.retryAfter(c.getInt("failures"))) }

    /** recheck4 #14: misses against an older copy don't carry over to a newer one seen since. */
    @Test fun missesStartAgainWhenTheSeenCopyMovesOn() {
        assertEquals(2, SyncGuard.misses(600, 600, 2))
        assertEquals(0, SyncGuard.misses(600, 900, 2))
        // So the newer copy needs three full misses of its own before a rebase.
        var m = SyncGuard.misses(600, 900, 2)
        repeat(2) { m = SyncGuard.decide(true, true, 300, 900, m, 0, 1000).also { assertEquals(SyncGuard.Do.WAIT, it.doThis) }.misses }
        assertEquals(SyncGuard.Do.REBASE, SyncGuard.decide(true, true, 300, 900, m, 0, 1000).doThis)
    }

    @Test fun writeList() = each("writeList") { c ->
        val t = c.getJSONArray("tags").let { a -> (0 until a.length()).map { strings(a.getJSONArray(it)) } }
        assertEquals(c.getString("name"), strings(c.getJSONArray("write")), SyncGuard.writeList(t))
    }

    @Test fun missesAgainst() = each("missesAgainst") { c ->
        val st = c.optJSONObject("stored")
        assertEquals(c.getString("name"), c.getInt("misses"), SyncGuard.misses(st?.getLong("at") ?: -1, c.getLong("seenAt"), st?.getInt("n") ?: 0))
    }
}
