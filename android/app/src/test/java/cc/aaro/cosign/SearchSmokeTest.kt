package cc.aaro.cosign

import cc.aaro.cosign.net.FakeSearchRelay
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Search smoke test (tools/search-smoke.sh): Marble's real search (SearchRun, as Social.search runs it) against fake
 * relays on this machine (FakeSearchRelay) that answer the way real ones do at their worst: search relays that take a
 * post with ANY of the words, one that ignores the search and sends everything, an archive that matches any word, and
 * copycats of fiatjaf. The world is tools/search-fixtures.json (the web page test's too). Nothing outside is reached.
 */
class SearchSmokeTest {
    companion object {
        private val json = Json.parse(listOf(File("../../tools/search-fixtures.json"), File("../tools/search-fixtures.json"), File("tools/search-fixtures.json")).first { it.exists() }.readText()) as Map<*, *>
        private fun events(k: String) = (json[k] as List<*>).map { Event.fromMap(it)!! }
        private fun strings(k: String) = (json[k] as Map<*, *>).entries.associate { it.key as String to it.value as String }
        val pk = strings("pk"); val posts = strings("posts"); val archived = strings("archive")
        val public = events("publicEvents"); val archive = events("archiveEvents")
        val followers = (json["followers"] as Map<*, *>).entries.associate { it.key as String to (it.value as Number).toLong() }
        val nip05 = (json["nip05"] as Map<*, *>).entries.associate { (d, names) -> d as String to (names as Map<*, *>).entries.associate { it.key as String to it.value as String } }
        val byId = (public + archive).associateBy { it.id }
        lateinit var relay: FakeSearchRelay

        @BeforeClass @JvmStatic fun start() { relay = FakeSearchRelay(mapOf("public" to public, "archive" to archive + public.filter { it.kind == 0 }), followers) }
        @AfterClass @JvmStatic fun stop() { relay.close() }
    }

    private val known = ConcurrentHashMap<String, Person>()
    private fun lookup(address: String): String? { val (n, d) = address.lowercase().split('@'); return nip05[d]?.get(n.ifEmpty { "_" }) }
    private fun env(archive: Boolean = false, follows: Set<String> = emptySet()) = SearchEnv(
        fast = listOf(relay.url("loose-public-ditto"), relay.url("loose-public-syb")),
        slow = listOf(relay.url("ignore-public-nostoday"), relay.url("loose-public-archives")),
        counts = listOf(relay.url("loose-public-ditto")),
        general = listOf(relay.url("ignore-public-damus")),
        big = listOf(relay.url("ignore-public-damus"), relay.url("ignore-public-nos")),
        extra = { emptyList() },
        privates = { if (archive) listOf(relay.url("loose-archive-mine")) else emptyList() },
        follows = { follows },
        known = { known },
        addPeople = { evs -> evs.mapNotNull { Person.of(it) }.forEach { p -> known.merge(p.pubkey, p) { a, b -> if (b.at > a.at) b else a } } },
        wantPeople = {},
        nip05Lookup = { lookup(it) },
        nip05Check = { p, a -> if (a == null) Nip05.Check.NONE else when (lookup(a)) { null -> Nip05.Check.UNKNOWN; p -> Nip05.Check.OK; else -> Nip05.Check.WRONG } },
    )

    private fun search(q: String, archive: Boolean = false, follows: Set<String> = emptySet()): Found = runBlocking {
        var last: Found? = null
        withTimeout(30_000) { SearchRun(env(archive, follows)).run(q) { last = it } }
        last!!.also { assertTrue("the search finished", !it.loading) }
    }

    private fun name(e: Event) = (posts.entries + archived.entries).firstOrNull { it.value == e.id }?.key ?: e.content.take(24)
    private fun names(l: List<Event>) = l.joinToString { name(it) }
    private fun who(h: String?) = pk.entries.firstOrNull { it.value == h }?.key ?: h ?: "nobody"
    private fun has(e: Event, vararg words: String) = words.all { it in e.content.lowercase() }

    @Test fun plainWordFindsPosts() {
        val f = search("failed")
        assertTrue("\"failed\" finds posts, not none", f.notes.isNotEmpty())
        assertTrue("every post has the word: ${names(f.notes)}", f.notes.all { has(it, "fail") })
        assertTrue("nothing from the relay that ignores the search: ${names(f.notes)}", f.notes.none { it.pubkey == pk["JUNK"] })
        assertTrue("the posts with the word are there: ${names(f.notes)}", listOf("a1", "b1", "f1").all { k -> f.notes.any { it.id == posts[k] } })
    }

    @Test fun relayThatIgnoresSearchLeaksNothing() {
        val f = search("sourdough")
        assertEquals("only the one post that says it, though a relay sent everything", listOf(posts["a1"]), f.notes.map { it.id })
        assertTrue("the relay that ignores search was asked", relay.log.any { it.first == "ignore-public-nostoday" && it.second["search"] == "sourdough" })
    }

    @Test fun fromNameWordsIsOnlyTheirPostsWithEveryWord() {
        val f = search("from:fiatjaf failed")
        assertEquals("from: names the real fiatjaf, not a copycat (copycats are newer and first)", "FIAT", who(f.split?.author))
        assertEquals("their posts with \"failed\", only", setOf(posts["f1"], posts["f3"]), f.byAuthor.map { it.id }.toSet())
        assertTrue("nothing else under from:: ${names(f.notes)} ${names(f.archive)}", f.notes.isEmpty() && f.archive.isEmpty())
        assertTrue("\"from:\" never goes to a relay as words", relay.log.none { (it.second["search"] as? String)?.contains("from:") == true })
    }

    @Test fun fromAddressOrKeyNamesWhoeverItNames() {
        for (q in listOf("from:_@fiatjaf.test failed", "from:${pk["FIAT"]} failed")) {
            val f = search(q)
            assertEquals(q, "FIAT", who(f.split?.author))
            assertEquals("$q: their posts with \"failed\", only", setOf(posts["f1"], posts["f3"]), f.byAuthor.map { it.id }.toSet())
            assertTrue("$q: nothing else", f.notes.isEmpty())
        }
    }

    @Test fun fromNobodyFindsNothing() {
        val f = search("from:nobodyatall failed")
        assertTrue("no author, no posts: ${f.split} ${names(f.notes)}", f.split == null && f.notes.isEmpty() && f.byAuthor.isEmpty())
    }

    @Test fun bareFromShowsTheirLatest() {
        val f = search("from:fiatjaf")
        assertEquals("FIAT", who(f.split?.author))
        assertEquals("their latest posts", listOf("f1", "f2", "f3", "f4").map { posts[it] }.toSet(), f.byAuthor.map { it.id }.toSet())
        assertTrue("nothing else: ${names(f.notes)}", f.notes.isEmpty())
    }

    @Test fun atNameIsTheSofterKind() {
        val f = search("@fiatjaf failed")
        assertEquals("FIAT", who(f.split?.author))
        assertTrue("their posts: ${names(f.byAuthor)}", f.byAuthor.isNotEmpty() && f.byAuthor.all { it.pubkey == pk["FIAT"] && has(it, "fail") })
        assertTrue("and the usual results for \"failed\": ${names(f.notes)}", f.notes.isNotEmpty() && f.notes.all { has(it, "fail") })
    }

    @Test fun strangersNameFindsTheTrustedAccount() {
        val f = search("fiatjaf")
        assertEquals("people search: the real fiatjaf first, not a copycat (${f.people.map { who(it) }})", "FIAT", who(f.people.firstOrNull()))
    }

    @Test fun archiveNeverFloodsTheTop() {
        val f = search("relay failed", archive = true)
        assertTrue("the archive was asked", relay.log.any { it.first == "loose-archive-mine" })
        assertTrue("from your archive: only the posts with every word (${names(f.archive)})", f.archive.isNotEmpty() && f.archive.all { has(it, "relay", "failed") })
        assertTrue("…all of them (${names(f.archive)})", listOf("r3", "fa1").all { k -> f.archive.any { it.id == archived[k] } } && f.archive.size == 3)
        assertTrue("and the usual results: ${names(f.notes)}", f.notes.isNotEmpty() && f.notes.all { has(it, "relay", "fail") })
    }

    @Test fun fromWithArchiveIsOnlyTheirs() {
        val f = search("from:fiatjaf relay failed", archive = true)
        assertEquals("FIAT", who(f.split?.author))
        assertEquals("from your archive: only theirs with every word (${names(f.archive)})", listOf(archived["fa1"]), f.archive.map { it.id })
        assertTrue("their public posts with every word: ${names(f.byAuthor)}", f.byAuthor.all { it.pubkey == pk["FIAT"] && has(it, "relay", "failed") })
        assertTrue("nothing else: ${names(f.notes)}", f.notes.isEmpty())
    }

    @Test fun signedInWithFollowsStillFindsPosts() {
        val f = search("failed", archive = true, follows = setOf(pk["BOB"]!!))
        assertTrue("a plain search with follows and an archive isn't empty: ${names(f.notes)}", f.notes.isNotEmpty() && f.notes.all { has(it, "fail") })
        assertTrue("the archive's posts all have the word: ${names(f.archive)}", f.archive.all { has(it, "fail") })
    }
}
