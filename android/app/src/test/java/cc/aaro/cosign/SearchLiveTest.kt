package cc.aaro.cosign

import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Marble's real search (SearchRun) against the real, public relays, read only (tools/search-smoke.sh runs it with
 * SEARCH_LIVE=1; skipped otherwise): "failed" finds posts with the word, and "from:fiatjaf failed" only fiatjaf's
 * (3bf0c63f…) posts with "failed". A failure says which step, what each step logged, and what each relay answers.
 */
class SearchLiveTest {
    private val fiatjaf = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val big = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://syb.lol", "wss://relay.ditto.pub")
    private val known = ConcurrentHashMap<String, Person>()
    private val log = CopyOnWriteArrayList<String>()

    private fun search(q: String): Found = runBlocking {
        val env = SearchEnv(big = big, extra = { emptyList() }, known = { known }, wantPeople = {},
            addPeople = { evs -> evs.mapNotNull { Person.of(it) }.forEach { p -> known.merge(p.pubkey, p) { a, b -> if (b.at > a.at) b else a } } })
        var last: Found? = null
        withTimeout(60_000) { SearchRun(env).run(q, log = { log += it }) { last = it } }
        last!!
    }

    /** What each search relay says to [q] by itself, for a failure's message. */
    private fun relays(q: String, filter: Map<String, Any>) = runBlocking {
        (SearchRelays.FAST + SearchRelays.SLOW).joinToString("\n") { url ->
            val (got, answered) = try { Fetch.queryAnswered(listOf(url), filter + ("search" to q), 8000) } catch (e: Exception) { emptyList<cc.aaro.qrst.core.Event>() to emptySet() }
            "  $url: ${if (answered.isEmpty()) "no answer" else "${got.size} events, ${got.count { SearchRank.mentions(it, q) }} with the words"}"
        }
    }
    private fun why(q: String, filter: Map<String, Any>) = "\nsteps:\n  " + log.joinToString("\n  ") + "\nrelays, asked alone:\n" + relays(q, filter)

    @Test fun failedFindsPosts() {
        assumeTrue(System.getenv("SEARCH_LIVE") != null)
        val f = search("failed")
        val posts = mapOf("kinds" to listOf(1), "limit" to 20)
        assertTrue("\"failed\" finds no posts${why("failed", posts)}", f.notes.isNotEmpty())
        assertTrue("\"failed\" shows posts without the word${why("failed", posts)}", f.notes.all { SearchRank.mentions(it, "failed") })
    }

    @Test fun fromFiatjafFailed() {
        assumeTrue(System.getenv("SEARCH_LIVE") != null)
        val f = search("from:fiatjaf failed")
        assertEquals("from:fiatjaf names someone else (people search for \"fiatjaf\")${why("fiatjaf", mapOf("kinds" to listOf(0), "limit" to 20))}", fiatjaf, f.split?.author)
        val his = mapOf("kinds" to listOf(1), "authors" to listOf(fiatjaf), "limit" to 20)
        assertTrue("no posts of his with \"failed\"${why("failed", his)}", f.byAuthor.isNotEmpty())
        assertTrue("a post that isn't his, or lacks \"failed\": ${f.byAuthor.filter { it.pubkey != fiatjaf || "failed" !in it.content.lowercase() }.map { it.content.take(60) }}",
            f.byAuthor.all { it.pubkey == fiatjaf && "failed" in it.content.lowercase() })
        assertTrue("other posts under from: ${f.notes.map { it.content.take(40) }} ${f.archive.size}", f.notes.isEmpty() && f.archive.isEmpty())
    }
}
