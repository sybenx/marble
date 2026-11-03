package cc.aaro.cosign.ui

import cc.aaro.cosign.OtherApps
import cc.aaro.cosign.Ref
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import cc.aaro.cosign.readable
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The content rules shared with Marble Web and Astral (marble-content-fixtures, copied into resources/content-fixtures;
 * FROM.txt says from which commit): each fixture drawn the way a post in a list is (Body, its card, its media, its
 * warning), turned into the fixtures' tokens and compared as compare.mjs does.
 */
class ContentFixturesTest {
    /** What was drawn, as compare.mjs's `got`. */
    private class Card(val card: String, val ref: String, val text: String, val open: String?)
    private class Got(val inline: List<Pair<String, String>>, val cards: List<Card>, val media: List<String>, val warning: String?, val visible: String,
                      val mentionRun: Map<String, Int>? = null, val tables: List<Map<String, Int>> = emptyList(), val headings: List<String> = emptyList(),
                      val preview: String? = null)

    private fun draw(e: Event, related: List<Event>): Got {
        // An article, as the reader draws it (its Markdown); anything else as a post in a list.
        if (e.kind == 30023) {
            // As the reader draws it: a leading heading repeating the title dropped.
            val title = e.firstTag("title")?.trim()
            val blocks = Markdown.parse(e).dropWhile { b -> b is Markdown.Block.Heading && title != null &&
                b.spans.joinToString("") { (it.part as? Body.Part.Plain)?.s ?: "" }.trim().equals(title, ignoreCase = true) }
            val (inline, media, seen) = Markdown.tokens(blocks)
            val tables = blocks.filterIsInstance<Markdown.Block.Table>().map { mapOf("rows" to it.rows.size, "columns" to it.header.size) }
            val headings = blocks.filterIsInstance<Markdown.Block.Heading>().map { h -> h.spans.joinToString("") { (it.part as? Body.Part.Plain)?.s ?: "" }.trim() }
            return Got(inline, emptyList(), media.map { (k, u) -> "$k $u" }, Body.warning(e), seen, tables = tables, headings = headings)
        }
        val body = Body.of(e)
        val (inline, seen) = body.tokens()
        val cards = listOfNotNull(when (val c = body.card) {
            is Body.Part.Quote -> related.firstOrNull { it.id == c.id }?.let { q -> Card(OtherApps.card(q.kind), q.id, cardText(q), open(q, related)) }
            is Body.Part.QuoteAddr -> related.filter { it.kind == c.a.kind && it.pubkey == c.a.pubkey && it.firstTag("d") == c.a.d }.maxByOrNull { it.createdAt }
                ?.let { q -> Card(OtherApps.card(q.kind), "${c.a.kind}:${c.a.pubkey}:${c.a.d}", cardText(q), open(q, related)) }
            else -> null
        }, body.article?.let { c -> related.filter { it.kind == c.a.kind && it.pubkey == c.a.pubkey && it.firstTag("d") == c.a.d }.maxByOrNull { it.createdAt }
            ?.let { q -> Card(OtherApps.card(q.kind), "${c.a.kind}:${c.a.pubkey}:${c.a.d}", cardText(q), open(q, related)) } })
        val media = body.media.map { m -> when (m) { is Body.Img -> "image ${m.url}"; is Body.Vid -> "video ${m.v.url}" } }
        val run = body.trailing.takeIf { it.isNotEmpty() }?.let { mapOf("shown" to minOf(4, it.size), "more" to maxOf(0, it.size - 4)) }
        return Got(inline, cards, media, Body.warning(e), seen + " " + cards.joinToString(" ") { it.text }, run, preview = body.previewLink?.url)
    }

    /** Where a card's Open goes (OtherBox: Social.openTarget, here offline from [related]); a post or article opens in Marble. */
    private fun open(q: Event, related: List<Event>): String? = if (OtherApps.drawn(q.kind)) null else
        OtherApps.open(q, OtherApps.clientHandler(q)?.let { a -> related.firstOrNull { it.kind == 31990 && it.pubkey == a.pubkey && it.firstTag("d") == a.d } },
            related.filter { it.kind == 31990 || it.kind == 31989 }, setOf(q.pubkey))

    /** What a card shows (QuoteBox, OtherBox): a post's words, an article's title and summary, anything else's line. */
    private fun cardText(q: Event) = when (OtherApps.card(q.kind)) {
        "note" -> readable(q.content.trim())
        "article" -> listOfNotNull(q.firstTag("title") ?: "Untitled", Markdown.line(q)).joinToString(" ")
        else -> OtherApps.what(q)
    }

    /** compare.mjs, ported: the problems (none: it passes). */
    private fun compare(expect: Map<*, *>, got: Got): List<String> {
        val problems = ArrayList<String>()
        // (A link opened here carries its "here": "<url>|<here>".)
        val want = (expect["inline"] as? List<*>).orEmpty().map { t -> (t as Map<*, *>).let { m ->
            val k = listOf("text", "mention", "hashtag", "link", "emoji", "shortlink").first { it in m }
            k to (m[k] as String + ((m["here"] as? String)?.let { "|$it" } ?: "")) } }
        val kinds = setOf("mention", "hashtag", "link", "emoji", "shortlink")
        val gotTokens = got.inline.filter { it.first in kinds }
        val wantTokens = want.filter { it.first in kinds }
        if (gotTokens != wantTokens) problems += "inline tokens: want $wantTokens got $gotTokens"
        val flat = got.inline.joinToString("") { (k, v) -> if (k == "text") v else "\u0000$k\u0000" }
        var at = 0
        for ((k, v) in want) {
            val piece = if (k == "text") v else "\u0000$k\u0000"
            val i = flat.indexOf(piece, at)
            if (i < 0) { problems += "inline: $k \"$v\" missing or out of order"; continue }
            at = i + piece.length
        }
        val wantCards = (expect["cards"] as? List<*>).orEmpty().map { it as Map<*, *> }
        if (got.cards.size != wantCards.size) problems += "cards: want ${wantCards.size} got ${got.cards.size}"
        wantCards.forEachIndexed { i, c ->
            val g = got.cards.getOrNull(i) ?: return@forEachIndexed
            if (g.card != c["card"]) problems += "card $i: want a ${c["card"]} card, got ${g.card}"
            if (g.ref != c["ref"]) problems += "card $i: want ${c["ref"]}, got ${g.ref}"
            (c["line"] as? String)?.let { if (it !in g.text) problems += "card $i: want \"$it\", shows \"${g.text.take(80)}\"" }
            (c["open"] as? String)?.let { if (g.open != it) problems += "card $i: Open should go to $it, goes to ${g.open ?: "nowhere"}" }
        }
        val wantMedia = (expect["media"] as? List<*>).orEmpty().map { m -> (m as Map<*, *>).let { if ("image" in it) "image ${it["image"]}" else "video ${it["video"]}" } }
        if (got.media != wantMedia) problems += "media: want ${wantMedia.ifEmpty { "none" }} got ${got.media.ifEmpty { "none" }}"
        if (expect["warning"] as String? != got.warning) problems += "warning: want ${expect["warning"]} got ${got.warning}"
        (expect["mentionRun"] as? Map<*, *>)?.let { w -> val m = w.mapKeys { it.key as String }.mapValues { (it.value as Number).toInt() }; if (m != got.mentionRun) problems += "mention run: want $m got ${got.mentionRun}" }
        (expect["tables"] as? List<*>)?.let { w -> val t = w.map { (it as Map<*, *>).mapKeys { e -> e.key as String }.mapValues { e -> (e.value as Number).toInt() } }; if (t != got.tables) problems += "tables: want $t got ${got.tables}" }
        (expect["notHeading"] as? String)?.let { if (it in got.headings) problems += "heading \"$it\" shown twice" }
        if (expect.containsKey("preview") && expect["preview"] as String? != got.preview) problems += "preview: want ${expect["preview"]} got ${got.preview ?: "none"}"
        for (s in (expect["never"] as? List<*>).orEmpty().map { it as String }) if (s in got.visible) problems += "shows \"$s\""
        return problems
    }

    @Test fun theSharedFixtures() {
        val dir = File(javaClass.getResource("/content-fixtures/FROM.txt")!!.toURI()).parentFile
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        val failed = files.mapNotNull { f ->
            val fx = Json.parse(f.readText()) as Map<*, *>
            val e = Event.fromMap(fx["event"])!!
            val related = (fx["related"] as? List<*>).orEmpty().mapNotNull { Event.fromMap(it) }
            compare(fx["expect"] as Map<*, *>, draw(e, related)).takeIf { it.isNotEmpty() }?.let { f.name + "\n    " + it.joinToString("\n    ") }
        }
        val report = "Android: ${files.size - failed.size}/${files.size} pass" + failed.joinToString("") { "\n  FAIL $it" }
        println(report)
        File(System.getProperty("java.io.tmpdir"), "marble-content-fixtures-android.txt").writeText(report + "\n")
        val known = emptySet<String>()
        assertTrue(report, failed.all { it.substringBefore('\n') in known })
    }
}
