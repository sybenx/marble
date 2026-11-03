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

    @Test fun thePost() {
        val b = Body.of(event("post.json"))
        // The quoted test note is the card; the article gets its own card; the 10 closing mentions become chips.
        assertTrue(b.card is Body.Part.Quote)
        assertEquals(30023, b.article?.a?.kind)
        assertEquals(10, b.trailing.size)
        assertTrue(b.parts.none { it is Body.Part.Mention })
        assertEquals(null, b.previewLink)
        val (tokens, seen) = b.tokens()
        assertTrue(tokens.none { it.first == "shortlink" })
        assertTrue("no ↪ in the text", "↪" !in seen)
    }

    @Test fun articleCardLine() {
        // Its summary is its title, so the line is the first real sentence: the table's first row, as text.
        val line = Markdown.line(event("article.json"))
        assertTrue(line, line.startsWith("Client · Platform or version") || line.startsWith("Amethyst"))
    }

    @Test fun onePreviewOnlyForALinkOnItsOwnLineOrAtTheEnd() {
        fun ev(t: String) = Event("a", 1, 1, emptyList(), t)
        assertEquals("https://example.com/a", Body.of(ev("Read this:\nhttps://example.com/a\nso good")).previewLink?.url)
        assertEquals("https://example.com/b", Body.of(ev("Read this https://example.com/b")).previewLink?.url)
        assertEquals(null, Body.of(ev("See https://example.com/c for more")).previewLink)
        assertEquals(null, Body.of(ev("nothing\nhttps://njump.me/npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m")).previewLink)
    }

    @Test fun twoMentionsStayInTheText() {
        val pk = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        val b = Body.of(Event("a", 1, 1, emptyList(), "thanks nostr:$pk nostr:$pk"))
        assertTrue(b.trailing.isEmpty())
    }
}
