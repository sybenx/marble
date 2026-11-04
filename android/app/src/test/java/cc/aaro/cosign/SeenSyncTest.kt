package cc.aaro.cosign

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The seen list keeps what this version doesn't write (Reader's upto and unread, reader.md §2). */
class SeenSyncTest {
    @Test fun othersSurviveAWrite() {
        val theirs = """{"v":1,"at":5,"days":{"1":"abc"},"upto":{"feed:https://x/feed":900},"unread":{"r0123456789abcdef":950},"future":[1,2]}"""
        val keep = SeenSync.others(theirs)!!
        val mine = JSONObject(SeenSync.encode(listOf("def" to 86_400L * 20000), 86_400L * 20000 + 10, keep))
        assertEquals(900, mine.getJSONObject("upto").getInt("feed:https://x/feed"))
        assertEquals(950, mine.getJSONObject("unread").getInt("r0123456789abcdef"))
        assertEquals(2, mine.getJSONArray("future").length())
        assertEquals("def", mine.getJSONObject("days").getString("20000"))
    }
}
