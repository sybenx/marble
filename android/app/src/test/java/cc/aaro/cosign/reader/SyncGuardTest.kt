package cc.aaro.cosign.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** marble-content-fixtures fixtures/sync/guard.json, copied to resources/sync: when a synced copy may be written. */
class SyncGuardTest {
    private val v = JSONObject(javaClass.getResource("/sync/guard.json")!!.readText())
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun decide() { val a = v.getJSONArray("cases"); for (n in 0 until a.length()) { val c = a.getJSONObject(n); val i = c.getJSONObject("in")
        val d = SyncGuard.decide(i.getBoolean("complete"), i.optLong("found"), i.optLong("seenAt"), i.optInt("misses"), i.optLong("expires"), i.getLong("now"))
        assertEquals(c.getString("name"), c.getString("do"), d.doThis.name.lowercase())
        assertEquals(c.getString("name") + " misses", c.getInt("misses"), d.misses)
    } }

    @Test fun complete() { val a = v.getJSONArray("complete"); for (n in 0 until a.length()) { val c = a.getJSONObject(n)
        assertEquals(c.getString("name"), c.getBoolean("complete"), SyncGuard.complete(strings(c.getJSONArray("write")), strings(c.getJSONArray("answeredBy"))))
    } }

    @Test fun retryAfter() { val a = v.getJSONArray("retryAfter"); for (n in 0 until a.length()) { val c = a.getJSONObject(n)
        assertEquals("after ${c.getInt("failures")}", c.getLong("seconds"), SyncGuard.retryAfter(c.getInt("failures")))
    } }

    @Test fun aWriteTakesOnlyWithMostWriteRelays() {
        assertEquals(false, SyncGuard.written(3, 1))
        assertEquals(true, SyncGuard.written(3, 2))
        assertEquals(false, SyncGuard.written(2, 1))
        assertEquals(false, SyncGuard.written(0, 1))
    }
}
