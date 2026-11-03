package cc.aaro.cosign

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewsSourcesTest {
    @Before fun setUp() { NewsSources.clear(); NewsSources.newParser = { org.kxml2.io.KXmlParser() } }
    @After fun tearDown() = NewsSources.clear()

    @Test fun rss() {
        val xml = """<?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Simon Willison&#8217;s Weblog</title><link>https://simonwillison.net/</link>
            <item><title>Notes on &amp; things</title><link>https://simonwillison.net/2026/Oct/1/notes/</link><description>&lt;p&gt;Some &lt;b&gt;words&lt;/b&gt;&lt;/p&gt;</description>
            <pubDate>Thu, 01 Oct 2026 12:00:00 +0000</pubDate><media:content url="https://example.com/a.jpg" medium="image"/></item>
            <item><title>No link</title></item></channel></rss>"""
        val (site, title, cards) = NewsSources.parse(xml, "https://simonwillison.net/atom/everything/", "", "ai")!!
        assertEquals("https://simonwillison.net/", site); assertEquals("Simon Willison’s Weblog", title)
        assertEquals(1, cards.size)
        val c = cards[0]
        assertEquals("Notes on & things", c.title); assertEquals("Some words", c.summary); assertEquals("https://example.com/a.jpg", c.image)
        assertEquals("ai", c.topic); assertEquals("Simon Willison’s Weblog", c.sourceName); assertEquals(1_790_856_000L, c.publishedAt)
    }

    @Test fun atom() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>The Verge</title><link rel="self" href="https://www.theverge.com/rss/index.xml"/><link href="https://www.theverge.com/"/>
            <entry><title type="html">A phone</title><link rel="alternate" href="https://www.theverge.com/a-phone"/><id>tag:1</id><published>2026-10-01T12:00:00Z</published>
            <author><name>Someone</name></author><summary>Short</summary></entry></feed>"""
        val (site, title, cards) = NewsSources.parse(xml, "https://www.theverge.com/rss/index.xml", "The Verge", "gadgets")!!
        assertEquals("https://www.theverge.com/", site); assertEquals("The Verge", title)
        assertEquals(listOf("A phone"), cards.map { it.title }); assertEquals("https://www.theverge.com/a-phone", cards[0].url); assertEquals("Short", cards[0].summary)
    }

    @Test fun notAFeed() = assertEquals(null, NewsSources.parse("<html><body>hi</body></html>", "https://x", "", "news")?.third?.takeIf { it.isNotEmpty() })

    @Test fun mergesByNewestChange() {
        NewsSources.own.value = listOf(OwnFeed("https://a/feed", "A", "a", "ai", 100), OwnFeed("https://b/feed", "B", "b", "news", 100))
        NewsSources.off.value = mapOf("bbc" to (true to 100L))
        val other = """{"v":1,"own":[{"url":"https://a/feed","name":"A","site":"a","topic":"ai","at":200,"gone":true},{"url":"https://c/feed","name":"C","site":"c","topic":"food","at":150,"gone":false}],
            "off":{"bbc":{"off":false,"at":200},"fox":{"off":true,"at":50}}}"""
        assertTrue("this one still has B", NewsSources.take(other))
        assertEquals(listOf("https://b/feed", "https://c/feed"), NewsSources.shownOwn().map { it.url }.sorted())
        assertFalse(NewsSources.isOff("bbc")); assertTrue(NewsSources.isOff("fox"))
        // Taking its own copy back changes nothing and has nothing new.
        val mine = NewsSources.json()
        assertFalse(NewsSources.take(mine)); assertEquals(mine, NewsSources.json())
        assertNotNull(NewsSources.own.value.firstOrNull { it.url == "https://a/feed" && it.gone })
    }
}
