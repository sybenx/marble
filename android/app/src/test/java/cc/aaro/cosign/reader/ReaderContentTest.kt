package cc.aaro.cosign.reader

import cc.aaro.cosign.Person
import cc.aaro.cosign.ReaderRows
import cc.aaro.cosign.Ref
import cc.aaro.cosign.inAppRef
import cc.aaro.cosign.marbleLink
import cc.aaro.cosign.naddr
import cc.aaro.cosign.nevent
import cc.aaro.cosign.ui.Body
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Nip19
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * marble-content-fixtures/fixtures/reader/content.json (reader.md §5), copied to resources/reader: people you follow
 * post these, and Reader's lines never show a raw reference; opened, the posts are the shared renderer's.
 */
class ReaderContentTest {
    private val fx = JSONObject(File(javaClass.getResource("/reader/content.json")!!.toURI()).readText())
    // Now-relative, as the fixture's times are (a day's posts are "Today's posts").
    private val now = System.currentTimeMillis() / 1000
    // Each person's key: distinct from the first character (a digest's key uses the first 8).
    private val keys = fx.getJSONObject("people").keys().asSequence().toList().associateWith { k ->
        java.security.MessageDigest.getInstance("SHA-256").digest(k.toByteArray()).joinToString("") { "%02x".format(it) } }
    private val people = fx.getJSONObject("people").let { p -> keys.entries.associate { (k, pk) -> pk to Person(pk, p.getString(k), null, null, null, 0) } }
    private val events = LinkedHashMap<String, Event>()
    init {
        val list = fx.getJSONArray("events")
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val pk = keys.getValue(o.getString("by")); val kind = o.getInt("kind")
            val content = Regex("""\{(nevent|npub|naddr|blossom):([a-z]+)\}""").replace(o.getString("content")) { m ->
                val (what, ref) = m.destructured
                when (what) {
                    "nevent" -> events.getValue(ref).let { nevent(it.id, it.pubkey, it.kind, emptyList()) }
                    "npub" -> Nip19.npub(keys.getValue(ref))
                    "naddr" -> events.getValue(ref).let { naddr(it.firstTag("d")!!, it.pubkey, it.kind) }
                    else -> "blossom:" + "ab".repeat(32) + "." + ref
                }
            }
            val extra = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { t -> a.getJSONArray(t).let { x -> (0 until x.length()).map { x.getString(it) } } } } ?: emptyList()
            val tags = (if (kind == 30023) listOf(listOf("d", o.getString("d")), listOf("title", o.getString("title")), listOf("summary", o.getString("summary"))) else emptyList()) + extra
            events[o.getString("ref")] = Event(pk, now - o.getLong("ago"), kind, tags, content).withId()
        }
    }
    private val follow = (0 until fx.getJSONArray("follow").length()).map { keys.getValue(fx.getJSONArray("follow").getString(it)) }.toSet()
    private val rows = ReaderRows.people(events.values.filter { it.pubkey in follow }, people, { "people:$it" }, "people")
    private val expect = fx.getJSONObject("expect")

    @Test fun lines() {
        val want = expect.getJSONArray("lines")
        assertEquals(rows.map { "${it.sourceName} | ${it.title}" }.toString(), want.length(), rows.size)
        val raw = Regex(expect.getString("noRaw"))
        for (r in rows) assertFalse("raw reference in \"${r.title} ${r.snippet}\"", raw.containsMatchIn(r.title + " " + r.snippet))
        for (i in 0 until want.length()) {
            val w = want.getJSONObject(i)
            val r = rows.firstOrNull { it.sourceName == w.getString("source") && it.title == w.getString("title") }
            assertNotNull("line ${w.getString("source")} | ${w.getString("title")} in ${rows.map { "${it.sourceName} | ${it.title}" }}", r)
            w.optJSONArray("has")?.let { h -> for (j in 0 until h.length()) assertTrue("\"${h.getString(j)}\" in ${r!!.title} ${r.snippet}", (r!!.title + " " + r.snippet).contains(h.getString(j))) }
            w.optJSONArray("hasNot")?.let { h -> for (j in 0 until h.length()) assertFalse("\"${h.getString(j)}\" in ${r!!.title} ${r.snippet}", (r!!.title + " " + r.snippet).contains(h.getString(j))) }
        }
    }

    /** What an opened item shows, checked against each of the fixture's "opened" (the line it is, then what's in it). */
    @Test fun opened() {
        val o = expect.getJSONArray("opened")
        for (n in 0 until o.length()) {
            val w = o.getJSONObject(n)
            val (src, title) = w.getJSONArray("line").let { it.getString(0) to it.getString(1) }
            val r = rows.firstOrNull { it.sourceName == src && it.title == title } ?: throw AssertionError("no line $src | $title")
            val posts = r.posts.ifEmpty { listOfNotNull(r.event) }
            val bodies = posts.filter { it.kind == 1 }.map { Body.of(it) }
            w.optString("quote").takeIf { it.isNotEmpty() }?.let { want ->
                val q = bodies.mapNotNull { it.card as? Body.Part.Quote }.single()
                assertEquals("$src | $title: its quote card", want, events.values.first { it.id == q.id }.content)
            }
            w.optString("article").takeIf { it.isNotEmpty() }?.let { want ->
                val a = bodies.mapNotNull { (it.card as? Body.Part.QuoteAddr) ?: it.article }.single().a
                assertEquals("$src | $title: its article card", want, events.values.first { it.kind == a.kind && it.pubkey == a.pubkey && it.firstTag("d") == a.d }.firstTag("title"))
            }
            w.optString("mention").takeIf { it.isNotEmpty() }?.let { want -> assertTrue("$src | $title: $want", posts.any { Body.line(it, people).contains(want) }) }
            if (w.optBoolean("blossomImage")) assertTrue("$src | $title: a Blossom picture", bodies.any { b -> b.images.any { it.url.startsWith("blossom:") } })
            w.optJSONArray("openIn")?.let { want ->
                // A post or an article: Open in… offers Marble, your Nostr app, njump.me; an article opens by its naddr.
                assertNotNull("$src | $title: a post to open", r.event)
                assertEquals((0 until want.length()).map { want.getString(it) }, ReaderRules.OPEN_IN)
                if (r.event!!.kind == 30023) assertTrue(marbleLink(r.event!!).startsWith("https://marble.syb.lol/naddr1"))
            }
            w.optString("warningGate").takeIf { it.isNotEmpty() }?.let { want -> assertTrue("$src | $title: veiled", posts.any { Body.warning(it) == want }) }
            // v goes where the title goes: a post (with or without a link in it) opens the post here.
            w.optString("vOpens").takeIf { it.isNotEmpty() }?.let { want ->
                val p = ReaderRows.openTarget(r)
                val target = p?.let { "/" + nevent(it.id, it.pubkey, it.kind, emptyList()) } ?: r.link
                assertTrue("$src | $title: the title opens $target", target != null && Regex(want).containsMatchIn(target)) }
            w.optString("linkHref").takeIf { it.isNotEmpty() }?.let { want ->
                val here = inAppRef(r.link!!)
                assertTrue("$src | $title: opens here", here is Ref.Note && here.id == events.getValue("quoted").id)
                assertTrue(("/" + r.link!!.substringAfterLast('/')).matches(Regex("$want.*")))
            }
        }
    }
}
