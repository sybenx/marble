package cc.aaro.cosign.ui

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun article(text: String) = Event("1".repeat(64), 0, 30023, listOf(listOf("d", "x")), text)
    private fun parse(text: String) = Markdown.parse(article(text))
    private fun shown(b: Markdown.Block) = Markdown.spansOf(b).joinToString("") { (it.part as? Body.Part.Plain)?.s ?: (it.part as? Body.Part.Link)?.text ?: "" }

    @Test fun blocks() {
        val b = parse("# Title\n\nOne\ntwo lines.\n\n## Part\n\n1. first\n2. second\n- dot\n\n> said\n> twice\n\n```\ncode *not* bold\n```\n\n---\n\n![alt](https://x.example/p.jpg)")
        assertEquals(listOf("Heading", "Para", "Heading", "Item", "Item", "Item", "Quote", "Code", "Rule", "Picture"), b.map { it::class.simpleName })
        assertEquals("One two lines.", shown(b[1]))
        assertEquals(1, (b[3] as Markdown.Block.Item).n)
        assertEquals(null, (b[5] as Markdown.Block.Item).n)
        assertEquals("said twice", shown(b[6]))
        assertEquals("code *not* bold", (b[7] as Markdown.Block.Code).text)
        assertEquals(Markdown.Block.Picture("https://x.example/p.jpg", "alt"), b[9])
    }

    @Test fun emphasisButNotInsideWordsOrLinks() {
        val spans = Markdown.spansOf(parse("a **b** *c* _d_ snake_case_name https://x.example/a_b_c and `e*f*`")[0])
        assertTrue(spans.single { (it.part as? Body.Part.Plain)?.s == "b" }.bold)
        assertTrue(spans.single { (it.part as? Body.Part.Plain)?.s == "c" }.italic)
        assertTrue(spans.single { (it.part as? Body.Part.Plain)?.s == "d" }.italic)
        assertTrue(spans.any { (it.part as? Body.Part.Plain)?.s?.contains("snake_case_name") == true })
        assertEquals("https://x.example/a_b_c", spans.firstNotNullOf { it.part as? Body.Part.Link }.url)
        assertTrue(spans.single { (it.part as? Body.Part.Plain)?.s == "e*f*" }.code)
    }

    @Test fun nothingIsHtml() {
        assertEquals("<b>x</b> &amp; <script>", shown(parse("<b>x</b> &amp; <script>")[0]))
    }

    @Test fun aLinkKeepsItsWordsAndAPictureInALineComesAfterIt() {
        val b = parse("See [the studio](https://example.com/studio) ![p](https://example.com/p.png) done")
        assertEquals("the studio", Markdown.spansOf(b[0]).firstNotNullOf { it.part as? Body.Part.Link }.text)
        assertEquals(Markdown.Block.Picture("https://example.com/p.png", "p"), b[1])
    }

    @Test fun readingTime() {
        assertEquals(1, Markdown.minutes(parse("short")))
        assertEquals(3, Markdown.minutes(parse((1..700).joinToString(" ") { "word" })))
    }
}
