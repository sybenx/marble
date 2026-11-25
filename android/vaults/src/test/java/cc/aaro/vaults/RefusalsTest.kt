package cc.aaro.vaults

import cc.aaro.keykin.core.random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Being told no (good-guest §3.5): the same cases as the JS engine, from ~/kist/vectors/refusals.json (refusals.json
 * here is a copy; refresh it when the cases change), then Storage acting on them against servers that refuse.
 */
class RefusalsTest {
    private val v = JSONObject(javaClass.getResource("/refusals.json")!!.readText())
    private fun JSONArray.objs() = List(length()) { getJSONObject(it) }
    private fun JSONArray.strs() = List(length()) { getString(it) }
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

    @Test fun sharedCases() {
        for (x in v.getJSONArray("is_refusal").objs())
            assertEquals("is_refusal $x", x.getBoolean("refusal"), Refusing.isRefusal(x.getInt("status"), x.str("retry_after")))
        val b = v.getJSONArray("backoff")
        for (i in 0 until b.length()) assertEquals(b.getJSONArray(i).getLong(1), Refusing.backoff(b.getJSONArray(i).getInt(0)))
        for (x in v.getJSONArray("retry_after").objs())
            assertEquals("retry_after $x", if (x.isNull("seconds")) null else x.getLong("seconds"), Refusing.retryAfterSeconds(x.str("value"), x.getLong("t")))
        for (sc in v.getJSONArray("scenarios").objs()) {
            val r = Refusals()
            for (st in sc.getJSONArray("steps").objs()) {
                val m = "${sc.getString("name")}: ${st.getString("do")}"
                when (st.getString("do")) {
                    "refused" -> {
                        val e = r.refused(st.getString("server"), st.getString("kind"), st.getInt("status"), st.str("reason"), st.str("retry_after"), st.getLong("t"))
                        val x = st.getJSONObject("expect")
                        assertEquals(m, x.getString("host"), e.host); assertEquals(m, x.getString("kind"), e.kind)
                        assertEquals(m, x.getLong("until"), e.until); assertEquals(m, x.getInt("strikes"), e.strikes)
                        assertEquals(m, x.getString("line"), Refusing.line(e) { "{when}" })
                    }
                    "waiting" -> assertEquals(m, st.getBoolean("expect"), r.waiting(st.getString("server"), st.getString("kind"), st.getLong("t")) != null)
                    "open" -> assertEquals(m, st.getJSONArray("expect").strs(), r.open(st.getJSONArray("servers").strs(), st.getString("kind"), st.getLong("t")))
                    "next_try" -> assertEquals(m, st.getLong("expect"), r.nextTry(st.getJSONArray("servers").strs(), st.getString("kind"), st.getLong("t")))
                    "answered" -> r.answered(st.getString("server"), st.getString("kind"))
                    "active" -> assertEquals(m, st.getJSONArray("expect").strs(), r.active(st.getLong("t")).map { it.host }.sorted())
                    else -> error("unknown step $m")
                }
            }
        }
    }

    /** Servers that answer with what the test says, counting every request. */
    private class Saying : Blobs {
        val inner = FakeBlobs()
        val say = HashMap<String, BlobRefused>()
        val asked = ArrayList<Pair<String, String>>()
        override fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Int { asked += "put" to server; say[server]?.let { throw it }; return inner.upload(server, bytes, sha256, auth) }
        override fun get(server: String, sha256: String): ByteArray? { asked += "get" to server; say[server]?.let { throw it }; return inner.get(server, sha256) }
        override fun delete(server: String, sha256: String, auth: String): Boolean { asked += "delete" to server; say[server]?.let { throw it }; return inner.delete(server, sha256, auth) }
    }

    @Test fun storageLeavesRefusingServersAlone() {
        val net = Saying(); val clock = TestClock(); val key = Keys.newSecret()
        val st = Storage(net, clock).also { it.signer = { at, kind, tags, content -> Ev.sign(key, at, kind, tags, content) } }
        val servers = listOf("https://s1.test", "https://s2.test", "https://s3.test")

        // s1 rate-limits: the blob goes to s2 and s3; s1 isn't asked again while it waits, for any kind of request
        net.say["https://s1.test"] = BlobRefused(429, "slow down", "600")
        val a = Nv1.seal(random(3000))
        assertEquals(listOf("https://s2.test", "https://s3.test"), st.put(a, servers))
        net.asked.clear()
        st.put(Nv1.seal(random(3000)), servers)
        assertFalse(net.asked.any { it.second == "https://s1.test" })
        assertEquals("s1.test says too many requests, trying again soon", Refusing.line(st.refusals.active(clock.now())[0]) { "soon" })

        // downloads: every server that might have it refuses → null, held (not a gap); s1 not asked
        net.say["https://s2.test"] = BlobRefused(403, null, null); net.say["https://s3.test"] = BlobRefused(403, null, null)
        net.asked.clear()
        assertNull(st.get(a.ref, servers))
        assertTrue(st.lastGetHeld)
        assertFalse(net.asked.any { it.second == "https://s1.test" })
        // asked again within the wait: nothing goes out
        net.asked.clear(); clock.t += 30
        assertNull(st.get(a.ref, servers)); assertTrue(net.asked.isEmpty())

        // the waits over and the servers answering: it loads, and a blob nobody has is missing, not held
        clock.t += 700; net.say.clear()
        assertNotNull(st.get(a.ref, servers)); assertFalse(st.lastGetHeld)
        assertNull(st.get(Nv1.seal(random(10)).ref, servers)); assertFalse(st.lastGetHeld)
        assertTrue(st.refusals.active(clock.now()).isEmpty())

        // a refused delete is noted too, and the next delete skips that server
        net.say["https://s2.test"] = BlobRefused(402, "Pay up", null)
        st.delete("00".repeat(32), servers)
        net.asked.clear(); st.delete("00".repeat(32), servers)
        assertEquals(listOf("https://s1.test", "https://s3.test"), net.asked.map { it.second })
        assertEquals("s2.test refused deleting: 402 Pay up", Refusing.line(st.refusals.active(clock.now())[0]) { "" })
    }
}
