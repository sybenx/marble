package cc.aaro.cosign.ui

import cc.aaro.qrst.core.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laan Tungir's "BUD-10 Client Adoption Status" (kind 30023): a table of 17 lines (header, rule, 15 rows), a leading # title, njump links. */
class ArticleTest {
    private fun event(name: String): Event = JSONObject(javaClass.getResource("/bud10/$name")!!.readText()).let { o ->
        val tags = o.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } } }
        Event(o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), tags, o.getString("content"), o.getString("id"), o.getString("sig"))
    }

    @Test fun theWholeTableAndTheRest() {
        val a = event("article.json")
        val blocks = Markdown.parse(a)
        val tables = blocks.filterIsInstance<Markdown.Block.Table>()
        assertEquals(1, tables.size)
        val t = tables[0]
        assertEquals(4, t.header.size)
        assertEquals(15, t.rows.size)
        // Every row is as wide as the header, and the article is there to its end (its last row's last link).
        assertTrue(t.rows.all { it.size == 4 })
        val lastLink = Regex("https://njump\\.me/nevent1[0-9a-z]+").findAll(a.content).last().value
        assertTrue(Markdown.tokens(blocks).first.any { it.first == "link" && it.second == lastLink })
        // Links in cells stay links (njump ones open in Marble).
        assertTrue(Markdown.tokens(blocks).first.any { it.first == "link" && it.second.startsWith("https://njump.me/nevent1") })
        // The leading # title is there to be dropped (the reader drops a heading equal to the title).
        val h = blocks.first() as Markdown.Block.Heading
        assertEquals(a.firstTag("title"), h.spans.joinToString("") { (it.part as? Body.Part.Plain)?.s ?: "" }.trim())
    }

    @Test fun readTime() = assertTrue(Markdown.minutes(Markdown.parse(event("article.json"))) in 2..6)
}
