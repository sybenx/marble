package cc.aaro.cosign

import cc.aaro.qrst.core.Json
import cc.aaro.qrst.core.isHex32
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.log10

// Finding people, as the user's Wisp does it (~/src/wisp, commits f51ab1c and 0634e29): the two
// search relays that answer fastest first (syb.lol, and relay.ditto.pub with the big database),
// the slower ones as fallbacks; your follows before anyone; a pasted address opens what it names.
// Ranked: the exact name, a checked NIP-05, then trust (your follows, theirs, everyone's).

object SearchRelays {
    /** Asked first, together: they answer in well under a second. */
    val FAST = listOf("wss://syb.lol", "wss://relay.ditto.pub")
    /** Slower, with a short wait: whatever they add comes in after. */
    val SLOW = listOf("wss://search.nos.today", "wss://search.nostrarchives.com")
    /** Count followers (NIP-45), both at once; the higher count wins (relay.ditto.pub's database is the bigger, syb.lol answers when it's busy). */
    val COUNTS = listOf("wss://relay.ditto.pub", "wss://syb.lol")
}

/** What we know about someone a search found: enough to put them in order. */
data class Hit(
    val pubkey: String,
    val name: String?,
    /** Where the relays put them, first seen first. */
    val order: Int,
    val follow: Boolean = false,
    /** How many of the people you follow follow them. */
    val fof: Int = 0,
    val followers: Long? = null,
    val nip05: Nip05.Check = Nip05.Check.NONE,
)

object SearchRank {
    /** A name as typed: lower case, the emoji and signs around it gone, spaces single. */
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun exact(name: String?, q: String) = name != null && norm(name) == norm(q) && norm(q).isNotEmpty()
    fun matches(name: String?, q: String) = name != null && norm(q).isNotEmpty() && norm(q) in norm(name)

    /**
     * How much to trust someone, for the order among the same kind of match: a NIP-05 that checks out
     * counts for a lot and one that names another key counts against; the people you follow following
     * them; then everyone's follows, on a log scale (174,000 followers against 19,000 is less than one step).
     */
    fun trust(h: Hit): Double {
        val n = when (h.nip05) { Nip05.Check.OK -> 1.5; Nip05.Check.WRONG -> -2.0; else -> 0.0 }
        val friends = if (h.fof > 0) 2.0 + 2 * log10(h.fof + 1.0) else 0.0
        return n + friends + log10((h.followers ?: 0) + 1.0)
    }

    /** Your follows first, then exact names, then the rest; within each, the most trusted first, then the relays' order. */
    fun sort(hits: Collection<Hit>, q: String): List<Hit> {
        val nq = norm(q)
        // Worked out once per person, not on every comparison.
        return hits.map { Triple(it, nq.isNotEmpty() && it.name != null && norm(it.name) == nq, trust(it)) }.sortedWith(
            compareByDescending<Triple<Hit, Boolean, Double>> { it.first.follow }
                .thenByDescending { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.order },
        ).map { it.first }
    }
}

/** NIP-05: does name@domain name this key? Asked once per address and remembered. */
object Nip05 {
    enum class Check { NONE, OK, WRONG, UNKNOWN }
    private val checked = ConcurrentHashMap<String, Pair<Check, Long>>()
    private val http by lazy { Fetch.http.newBuilder().callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build() }
    private val ADDRESS = Regex("^([a-zA-Z0-9._+-]*)@([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,})$")

    fun isAddress(s: String) = ADDRESS.matches(s.trim())

    /** OK, WRONG (the domain names another key), or UNKNOWN (no answer: not held against them). */
    suspend fun check(pubkey: String, address: String?): Check {
        val a = address?.trim()?.lowercase()?.takeIf { isAddress(it) } ?: return Check.NONE
        val key = "$a $pubkey"
        checked[key]?.let { (c, at) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L) return c }
        val c = when (lookup(a)) { null -> Check.UNKNOWN; pubkey -> Check.OK; else -> Check.WRONG }
        checked[key] = c to System.currentTimeMillis()
        return c
    }

    /** The key name@domain stands for; "" when the domain answers without it; null when it doesn't answer. */
    suspend fun lookup(address: String): String? = withContext(Dispatchers.IO) {
        val m = ADDRESS.matchEntire(address.trim().lowercase()) ?: return@withContext null
        val name = m.groupValues[1].ifEmpty { "_" }
        try {
            http.newCall(okhttp3.Request.Builder().url("https://${m.groupValues[2]}/.well-known/nostr.json?name=$name").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val names = (Json.parse(r.body?.string().orEmpty()) as? Map<*, *>)?.get("names") as? Map<*, *> ?: return@use ""
                (names[name] as? String)?.lowercase()?.takeIf { isHex32(it) } ?: ""
            }
        } catch (_: Exception) { null }
    }
}

/** A row of people results: someone, and the identical look-alikes folded under them. */
data class Folded(val pubkey: String, val alike: List<String> = emptyList())

/**
 * Look-alikes (bridge bots, copies): the same name, picture and about. The best-ranked one stays;
 * the rest fold under it. Someone you follow, or whose address checks out, is never folded away;
 * nor is a bare profile with no picture and no about (that's just a common name).
 */
object LookAlikes {
    private fun key(p: Person?): String? {
        p ?: return null
        val name = SearchRank.norm(p.name ?: "").ifEmpty { return null }
        val picture = p.picture?.trim().orEmpty(); val about = p.about?.trim().orEmpty()
        if (picture.isEmpty() && about.isEmpty()) return null
        return "$name\u0000$picture\u0000$about"
    }

    fun fold(order: List<String>, people: Map<String, Person>, hits: Map<String, Hit>): List<Folded> {
        val out = ArrayList<Folded>()
        val first = HashMap<String, Int>()   // look → its row
        for (pk in order) {
            val h = hits[pk]
            val k = key(people[pk])
            val keep = h?.follow == true || h?.nip05 == Nip05.Check.OK
            val at = k?.let { first[it] }
            if (at != null && !keep) out[at] = out[at].copy(alike = out[at].alike + pk)
            else { if (k != null && k !in first) first[k] = out.size; out += Folded(pk) }
        }
        return out
    }
}

/**
 * Someone you follow, inside a search ("thailand anarko", "thailand arko", "from:anarko thailand", "@anarko thailand"):
 * their posts with the other words, as well as (never instead of) the usual results. Only people you follow (anyone can
 * call themselves anything: handles-rule.md), by their name or their NIP-05's name part: exactly, then a start of it
 * (3+ letters), a part of it (4+), one typo off (4+). Shared with Marble Web (search-rules).
 */
object AuthorQuery {
    /**
     * [author], whose posts to look in, for [rest]; [others]: other people you follow it could mean (chips). [loose]: a
     * part of a name, not the name (it could be wrong): its posts come after the first of the usual results, not before.
     */
    data class Split(val author: String, val rest: String, val others: List<String> = emptyList(), val loose: Boolean = false)

    private val EXPLICIT = Regex("^(?:from:|@|＠)(.+)$", RegexOption.IGNORE_CASE)

    /** How well a typed name names someone; at least [STRONG]: it's their name, not a part of it. */
    const val EXACT = 7; const val TIGHT = 6; const val FIRST = 5; const val PREFIX = 4; const val PART = 3; const val TYPO = 2
    const val STRONG = FIRST

    fun split(q: String, follows: Set<String>?, known: Map<String, Person>): Split? {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || follows.isNullOrEmpty()) return null
        val mine = follows.mapNotNull { known[it] }
        // from:name, @name: that word only (a name that isn't someone you follow: no author, the words as they are).
        words.indexOfFirst { EXPLICIT.matches(it) }.takeIf { it >= 0 }?.let { i ->
            val name = EXPLICIT.find(words[i])!!.groupValues[1]
            val rest = words.filterIndexed { j, _ -> j != i }.joinToString(" ")
            return ranked(mine, name).takeIf { it.isNotEmpty() }?.let { Split(it[0].first, rest, it.drop(1).map { h -> h.first }.take(4), it[0].second < STRONG) }
        }
        // Otherwise the run of words (three at most, never all of them) that best names someone you follow: the closest
        // match, then the longest run, then the leftmost.
        var best: Triple<Int, Int, Split>? = null
        for (len in minOf(3, words.size - 1) downTo 1) for (start in 0..words.size - len) {
            val hits = ranked(mine, words.subList(start, start + len).joinToString(" ")).takeIf { it.isNotEmpty() } ?: continue
            val score = hits[0].second
            if (best == null || score > best.first || (score == best.first && len > best.second)) {
                best = Triple(score, len, Split(hits[0].first, (words.subList(0, start) + words.subList(start + len, words.size)).joinToString(" "),
                    hits.drop(1).map { it.first }.take(4), score < STRONG))
            }
        }
        return best?.third
    }

    /** Who [typed] could name among [mine], with how well, best first. */
    fun ranked(mine: List<Person>, typed: String): List<Pair<String, Int>> {
        val t = SearchRank.norm(typed)
        if (t.length < 2) return emptyList()
        val tight = t.replace(" ", "")
        return mine.map { p -> p.pubkey to score(p, t, tight) }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    private fun score(p: Person, t: String, tight: String): Int {
        val n = SearchRank.norm(p.name ?: "")
        val local = SearchRank.norm(p.nip05?.substringBefore('@')?.takeIf { it != "_" } ?: "")
        val names = listOf(n.replace(" ", ""), local.replace(" ", "")).filter { it.isNotEmpty() }
        val words = (n.split(' ') + local.split(' ')).filter { it.isNotEmpty() }
        return when {
            n == t -> EXACT
            tight in names -> TIGHT
            tight.length >= 4 && words.firstOrNull() == t -> FIRST
            tight.length >= 3 && (names + words).any { it.startsWith(tight) } -> PREFIX
            tight.length >= 4 && names.any { tight in it } -> PART
            tight.length >= 4 && (names + words).any { oneOff(it, tight) } -> TYPO
            else -> 0
        }
    }

    /** One letter added, dropped, changed, or two side by side swapped. */
    fun oneOff(a: String, b: String): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val d = a.indices.filter { a[it] != b[it] }
            return d.size == 1 || (d.size == 2 && d[1] == d[0] + 1 && a[d[0]] == b[d[1]] && a[d[1]] == b[d[0]])
        }
        val (long, short) = if (a.length > b.length) a to b else b to a
        var i = 0
        while (i < short.length && long[i] == short[i]) i++
        return long.substring(i + 1) == short.substring(i)
    }
}
