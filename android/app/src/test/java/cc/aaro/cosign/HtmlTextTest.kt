package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Test

/** Link previews and stories: titles as people read them, not as HTML escapes them. */
class HtmlTextTest {
    @Test fun numericAndNamed() {
        assertEquals("Dutch households hold over €3 billion", HtmlText.decode("Dutch households hold over &#x20AC;3 billion"))
        assertEquals("€3", HtmlText.decode("&#8364;3"))
        assertEquals("Tom & Jerry’s — “best” …", HtmlText.decode("Tom &amp; Jerry&rsquo;s &mdash; &ldquo;best&rdquo; &hellip;"))
        assertEquals("it's", HtmlText.decode("it&#39;s"))
    }

    @Test fun escapedTwice() {
        assertEquals("over €3 billion", HtmlText.decode("over &amp;#x20AC;3 billion"))
        assertEquals("a &amp; b", HtmlText.decode("a &amp;amp;amp; b"))   // three times over: two passes, as browsers would show it once
    }

    @Test fun leftAloneWhenNotAReference() {
        assertEquals("AT&T and &unknown; and & alone", HtmlText.decode("AT&T and &unknown; and & alone"))
        assertEquals("", HtmlText.decode(""))
    }

    @Test fun previewsDecode() {
        val p = Preview.of("<meta property=\"og:title\" content=\"Over &amp;#x20AC;3 billion\"><meta property=\"og:description\" content=\"&#x201C;Hi&#x201D;\">", "https://dnb.nl/x")!!
        assertEquals("Over €3 billion", p.title)
        assertEquals("“Hi”", p.summary)
    }
}
