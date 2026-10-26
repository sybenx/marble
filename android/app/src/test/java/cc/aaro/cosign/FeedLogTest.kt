package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/** The feed log's lines and files: JSON an analysis can read, kept within its cap. */
class FeedLogTest {
    @Test fun aLineIsOneJsonObjectWithoutNulls() {
        val s = FeedLog.line(1_700_000_000_123, "abc-123", "act", listOf("id" to "t:x\"y", "a" to "react", "emoji" to "🤙", "gone" to null,
            "n" to 3, "f" to 0.123456, "nan" to Double.NaN, "list" to listOf(1, "b"), "map" to mapOf("ai" to 0.5, "x" to null), "ok" to true))
        assertEquals("""{"t":1700000000123,"s":"abc-123","ev":"act","id":"t:x\"y","a":"react","emoji":"🤙","n":3,"f":0.1235,"nan":null,"list":[1,"b"],"map":{"ai":0.5},"ok":true}""", s)
        val o = org.json.JSONObject(s)
        assertEquals("t:x\"y", o.getString("id"))
        assertFalse(o.has("gone"))
        assertFalse('\n' in s)
    }

    @Test fun nothingIsLoggedWhileItsOff() {
        assertFalse(FeedLog.on.value)
        FeedLog.ev("act", "id" to "x")   // no files set up, and off: must simply do nothing
    }

    @Test fun oneFileADayAndTheOldestGoFirst() {
        val dir = createTempDir("feedlog")
        try {
            val files = LogFiles(dir, cap = 1_000, days = 2, zone = ZoneOffset.UTC)
            val day = 86_400_000L
            val t0 = 1_700_000_000_000L
            for (d in 0..3) files.append("x".repeat(100), t0 + d * day)
            // Four days written; with days = 2, the oldest is dropped once a later day is more than two days on.
            val names = files.list().map { it.name }
            assertEquals(listOf("2023-11-15.jsonl", "2023-11-16.jsonl", "2023-11-17.jsonl"), names)
            // Over the cap: older days go, today's stays.
            repeat(12) { files.append("y".repeat(100), t0 + 3 * day) }
            files.trim("2023-11-17")
            assertEquals(listOf("2023-11-17.jsonl"), files.list().map { it.name })
            assertTrue(files.bytes() > 1_000)
        } finally { dir.deleteRecursively() }
    }

    @Test fun theZipHoldsEveryDay() {
        val dir = createTempDir("feedlog")
        try {
            val files = LogFiles(dir, zone = ZoneOffset.UTC)
            files.append("{\"a\":1}", 1_700_000_000_000L); files.append("{\"a\":2}", 1_700_086_400_000L)
            val out = java.io.ByteArrayOutputStream()
            files.zip(out)
            val names = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
            assertEquals(listOf("feedlog/2023-11-14.jsonl", "feedlog/2023-11-15.jsonl"), names)
            files.clear()
            assertEquals(0L, files.bytes())
        } finally { dir.deleteRecursively() }
    }

    private fun createTempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile()
}
