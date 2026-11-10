package cc.aaro.cosign

import cc.aaro.qrst.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** apps/vectors.json, written by apps-index/vectors.ts from site/js/apps.js: the phone must match Marble Web. */
class AppIndexVectorsTest {
    private val v = Json.parse(javaClass.getResource("/apps/vectors.json")!!.readText()) as Map<*, *>
    private val apps = (v["apps"] as List<*>).map { AppEntry.from(it)!! }
    private val follows = (v["follows"] as List<*>).map { it as String }.toSet()

    @Test fun search() {
        for (c in v["cases"] as List<*>) {
            val m = c as Map<*, *>; val q = m["q"] as String
            assertEquals("scores for \"$q\"", (m["scores"] as List<*>).map { (it as Number).toInt() }, apps.map { AppIndex.textScore(it, q) })
            assertEquals("results for \"$q\"", (m["top"] as List<*>).map { it as String }, AppIndex.search(apps, q, follows, limit = 8).map { it.address })
        }
    }

    @Test fun lines() = assertEquals((v["lines"] as List<*>).map { it as String }, apps.map { AppIndex.line(it, follows) })

    @Test fun parse() {
        val good = """{"v":1,"apps":[{"a":"x","kind":"web","name":"A","url":"https://a.example/","pk":"${"1".repeat(64)}","score":1.5}]}"""
        assertEquals(1, AppIndex.parse(good)!!.size)
        assertNull("another version", AppIndex.parse(good.replace("\"v\":1", "\"v\":2")))
        assertNull("not https", AppIndex.parse(good.replace("https://a.example/", "http://a.example/")))
        assertNull("javascript", AppIndex.parse(good.replace("https://a.example/", "javascript:alert(1)")))
        assertNull("empty", AppIndex.parse("""{"v":1,"apps":[]}"""))
        assertNull("broken", AppIndex.parse("""{"v":1,"apps":["""))
    }
}
