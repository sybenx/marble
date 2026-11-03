package cc.aaro.cosign.ui

import cc.aaro.qrst.core.Event

/**
 * An article's Markdown (kind 30023), a careful subset (content-rules §7): headings, paragraphs, lists, block quotes,
 * code, rules, pictures; **bold**, *italic*, `code` and [text](https://…) in a line. Nothing is HTML: what isn't
 * Markdown shows as written. Mentions, references, #tags and :emoji: in it go through [Body] (references as short links).
 */
internal object Markdown {
    /** A piece of a line, and how it's set. */
    data class Span(val part: Body.Part, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    sealed class Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block()
        data class Para(val spans: List<Span>) : Block()
        /** A list item; [n]: its number, in a numbered list. */
        data class Item(val n: Int?, val spans: List<Span>) : Block()
        data class Quote(val spans: List<Span>) : Block()
        data class Code(val text: String) : Block()
        data class Picture(val url: String, val alt: String) : Block()
        data class Video(val v: VideoRef) : Block()
        data object Rule : Block()
        /** A GFM table: its header cells, then each row's (every row as wide as the header). */
        data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block()
    }

    private val FENCE = Regex("^\\s*(```|~~~)")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    private val PICTURE_LINE = Regex("^\\s*!\\[([^\\]]*)]\\((https://[^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")

    fun parse(e: Event, text: String = e.content): List<Block> {
        val out = ArrayList<Block>()
        val lines = text.replace("\r\n", "\n").split('\n')
        val para = ArrayList<String>()
        fun flush() { if (para.isNotEmpty()) { out += inline(e, para.joinToString(" ") { it.trim() }) { spans -> Block.Para(spans) }; para.clear() } }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                FENCE.containsMatchIn(l) -> {
                    flush()
                    val fence = FENCE.find(l)!!.groupValues[1]
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { code += lines[i]; i++ }
                    out += Block.Code(code.joinToString("\n"))
                }
                l.isBlank() -> flush()
                // A table: a row of cells, then a line of dashes under it.
                '|' in l && i + 1 < lines.size && TABLE_RULE.matches(lines[i + 1]) && '|' in lines[i + 1] -> {
                    flush()
                    val header = cells(l)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                    fun row(c: List<String>) = header.indices.map { j -> spans(e, c.getOrElse(j) { "" }.trim(), false, false, ArrayList()) }
                    out += Block.Table(row(header), rows.map(::row))
                    continue
                }
                HEADING.matches(l) -> { flush(); HEADING.find(l)!!.let { m -> out += inline(e, m.groupValues[2]) { Block.Heading(m.groupValues[1].length, it) } } }
                RULE.matches(l) -> { flush(); out += Block.Rule }
                PICTURE_LINE.matches(l) -> { flush(); PICTURE_LINE.find(l)!!.let { out += Block.Picture(it.groupValues[2], it.groupValues[1].trim()) } }
                BULLET.matches(l) -> { flush(); out += inline(e, BULLET.find(l)!!.groupValues[1]) { Block.Item(null, it) } }
                NUMBERED.matches(l) -> { flush(); NUMBERED.find(l)!!.let { m -> out += inline(e, m.groupValues[2]) { Block.Item(m.groupValues[1].toIntOrNull(), it) } } }
                QUOTE.matches(l) -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && QUOTE.matches(lines[i])) { q += QUOTE.find(lines[i])!!.groupValues[1].trim(); i++ }
                    out += inline(e, q.filter { it.isNotEmpty() }.joinToString(" ")) { Block.Quote(it) }
                    continue
                }
                else -> para += l
            }
            i++
        }
        flush()
        return out
    }

    /** A table row's cells: split on | (not \| or one inside `code`), the outer ones dropped. */
    private fun cells(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var code = false; var k = 0
        val t = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        while (k < t.length) {
            val c = t[k]
            when {
                c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> { cur.append('|'); k++ }
                c == '`' -> { code = !code; cur.append(c) }
                c == '|' && !code -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            k++
        }
        out += cur.toString()
        return out
    }

    // In a line: `code`, ![picture](…), [text](…), a bare link (so nothing inside it is taken for emphasis), **bold**
    // (or __bold__), *italic* (or _italic_, not inside a word).
    private val INLINE = Regex(
        "`([^`]+)`" +
            "|!\\[([^\\]]*)]\\((https://[^)\\s]+)\\)" +
            "|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)" +
            "|(https?://[^\\s<>\"]+)" +
            "|\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*|__(?!\\s)(.+?)(?<!\\s)__" +
            "|(?<![\\p{L}\\p{N}*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\p{L}\\p{N}*])" +
            "|(?<![\\p{L}\\p{N}_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\p{L}\\p{N}_])")

    /** A line's spans, as [block]; pictures and videos in it come after it, as their own blocks. */
    private fun inline(e: Event, s: String, block: (List<Span>) -> Block): List<Block> {
        val media = ArrayList<Block>()
        val spans = spans(e, s, false, false, media)
        return listOfNotNull(block(spans).takeIf { spans.isNotEmpty() }) + media
    }

    private fun spans(e: Event, s: String, bold: Boolean, italic: Boolean, media: MutableList<Block>): List<Span> {
        val out = ArrayList<Span>()
        fun plain(t: String) {
            if (t.isEmpty()) return
            val b = Body.of(e, t, raw = true)
            b.parts.forEach { out += Span(it, bold, italic) }
            b.media.forEach { m -> media += when (m) { is Body.Img -> Block.Picture(m.url, ""); is Body.Vid -> Block.Video(m.v) } }
        }
        var at = 0
        for (m in INLINE.findAll(s)) {
            plain(s.substring(at, m.range.first))
            at = m.range.last + 1
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out += Span(Body.Part.Plain(g[1]), bold, italic, code = true)
                g[3].isNotEmpty() -> media += Block.Picture(g[3], g[2].trim())
                g[5].isNotEmpty() -> out += Span(Body.Part.Link(g[5], g[4]), bold, italic)
                g[6].isNotEmpty() -> plain(g[6])
                g[7].isNotEmpty() || g[8].isNotEmpty() -> out += spans(e, g[7].ifEmpty { g[8] }, true, italic, media)
                else -> out += spans(e, g[9].ifEmpty { g[10] }, bold, true, media)
            }
        }
        plain(s.substring(at))
        return out
    }

    /** How long it takes to read, in minutes (at 230 words a minute; at least one). */
    fun minutes(blocks: List<Block>): Int = (blocks.sumOf { b ->
        when (b) {
            is Block.Heading -> words(b.spans); is Block.Para -> words(b.spans); is Block.Item -> words(b.spans); is Block.Quote -> words(b.spans)
            is Block.Code -> b.text.split(Regex("\\s+")).size
            is Block.Table -> (b.rows + listOf(b.header)).sumOf { r -> r.sumOf { words(it) } }; else -> 0
        }
    } / 230).coerceAtLeast(1)

    private fun words(spans: List<Span>) = spans.sumOf { (it.part as? Body.Part.Plain)?.s?.split(Regex("\\s+"))?.count { w -> w.isNotEmpty() } ?: 1 }

    /**
     * What the reader draws, as the shared fixtures' tokens (see [Body.tokens]): the text's, in order (every reference a
     * short link), the pictures and videos (["image" or "video", url]), and the text you can see outside links.
     */
    fun tokens(blocks: List<Block>): Triple<List<Pair<String, String>>, List<Pair<String, String>>, String> {
        val none = Body(emptyList(), emptyList(), emptyList())
        val inline = ArrayList<Pair<String, String>>(); val media = ArrayList<Pair<String, String>>(); val seen = StringBuilder()
        for (b in blocks) {
            when (b) { is Block.Picture -> media += "image" to b.url; is Block.Video -> media += "video" to b.v.url; else -> {} }
            for (sp in spansOf(b)) {
                val p = sp.part
                inline += when (p) {
                    is Body.Part.Plain -> "text" to p.s; is Body.Part.Link -> "link" to p.url; is Body.Part.Mention -> "mention" to p.pubkey
                    is Body.Part.Tag -> "hashtag" to p.tag; is Body.Part.Emoji -> "emoji" to p.code
                    is Body.Part.Quote -> "shortlink" to p.raw; is Body.Part.QuoteAddr -> "shortlink" to p.raw
                }
                if (p !is Body.Part.Link) none.label(p, emptyMap())?.let { seen.append(it).append(' ') }
            }
            if (b is Block.Picture) seen.append(b.alt).append(' ')
        }
        return Triple(inline, media, seen.toString())
    }

    /** The spans of every block, in order (for the shared fixtures' tokens). */
    fun spansOf(b: Block): List<Span> = when (b) {
        is Block.Heading -> b.spans; is Block.Para -> b.spans; is Block.Item -> b.spans; is Block.Quote -> b.spans
        is Block.Code -> listOf(Span(Body.Part.Plain(b.text), code = true))
        is Block.Table -> (listOf(b.header) + b.rows).flatMap { r -> r.flatMap { it + Span(Body.Part.Plain(" ")) } }
        else -> emptyList()
    }
}
