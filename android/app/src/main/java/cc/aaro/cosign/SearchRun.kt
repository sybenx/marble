package cc.aaro.cosign

import cc.aaro.cosign.net.Fetch
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Nip19
import cc.aaro.qrst.core.isHex32
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a search asks and what of the app it uses: the real relays and Social's own (Social.search), or a test's
 * (SearchSmokeTest: fake relays on this machine, nothing else reached).
 */
class SearchEnv(
    val fast: List<String> = SearchRelays.FAST,
    val slow: List<String> = SearchRelays.SLOW,
    val counts: List<String> = SearchRelays.COUNTS,
    /** A #tag's posts are asked of these too (they don't search, but they have tags). */
    val general: List<String> = Sources.GENERAL,
    /** The big relays someone's latest posts are read from (besides where they write). */
    val big: List<String>,
    val extra: () -> List<String> = { Sources.extra.value },
    /** Your archive (private relays, logged in as you). */
    val privates: () -> List<String> = { emptyList() },
    val follows: () -> Set<String> = { emptySet() },
    val muted: () -> Set<String> = { emptySet() },
    val known: () -> Map<String, Person> = { People.known.value },
    val addPeople: (List<Event>) -> Unit = { People.add(it) },
    val wantPeople: (List<String>) -> Unit = { People.want(it) },
    /** Where someone writes (their NIP-65 write relays). */
    val outbox: suspend (String) -> List<String> = { emptyList() },
    val remember: (List<Event>) -> Unit = {},
    /** Not flagged, muted or dismissed. */
    val safe: (Event) -> Boolean = { true },
    val friendsOfFriends: suspend () -> Map<String, Int> = { emptyMap() },
    /** Search's posts through the spam checks; what's caught goes into the map (shown apart). */
    val spam: suspend (List<Event>, Set<String>, MutableMap<String, cc.aaro.cosign.spam.Spam.Why>, () -> Unit) -> Unit = { _, _, _, _ -> },
    val nip05Lookup: suspend (String) -> String? = { Nip05.lookup(it) },
    val nip05Check: suspend (String, String?) -> Nip05.Check = { pk, a -> Nip05.check(pk, a) },
)

/**
 * One search: people and posts. Your follows whose names match come first, at once; then what the search relays find,
 * each as it answers (none is dropped for being slow); then the order settles as names, NIP-05s and trust come in. A
 * #tag searches posts only. Every post shown is checked here: the relays' own search can take a post with any one of
 * the words, or ignore the search and send everything.
 */
class SearchRun(private val env: SearchEnv) {
    /**
     * Search [text] ([author]: a chip picked); [publish] gets each better Found as it comes, the last one with loading false.
     * [log] (debug builds): what came from where, and when.
     */
    suspend fun run(text: String, author: String? = null, log: ((String) -> Unit)? = null, publish: (Found) -> Unit) = coroutineScope {
        val t0 = System.nanoTime()
        var last: Found? = null
        // What was searched and who came up are the user's business, not the log's: debug builds only (Social decides).
        fun mark(what: String) = log?.invoke("\"$text\" $what at ${(System.nanoTime() - t0) / 1_000_000} ms; top: " +
            last?.people?.take(3)?.joinToString { pk -> (env.known()[pk]?.shown ?: pk.take(8)) + " " + pk.take(8) })
        val tag = text.removePrefix("#").takeIf { text.startsWith("#") && it.isNotBlank() && !it.contains(' ') }?.lowercase()
        val hits = ConcurrentHashMap<String, Hit>()
        val notes = java.util.Collections.synchronizedList(ArrayList<Event>())
        val order = java.util.concurrent.atomic.AtomicInteger()
        val mine = env.follows()
        // "from:fiatjaf failed": fiatjaf's posts with "failed" and nothing else ("@fiatjaf failed": and the usual results
        // for "failed"). The relays never see the name (they'd send "failed" by anyone); the people search looks for it.
        val from = if (tag != null) null else AuthorQuery.from(text)
        val who = from?.name ?: text
        // Someone you follow, named in it (or picked from the chips): their posts with the other words, first.
        var split = if (tag != null) null else AuthorQuery.split(text, mine, env.known())?.let { s ->
            if (author == null || author == s.author) s else s.copy(author = author, others = (listOf(s.author) + s.others).filter { it != author })
        } ?: if (from != null && author != null) AuthorQuery.Split(author, from.rest) else null  // a chip picked under a from: someone you don't follow
        val byAuthor = java.util.Collections.synchronizedList(ArrayList<Event>())
        val archive = java.util.Collections.synchronizedList(ArrayList<Event>())
        val caught = ConcurrentHashMap<String, cc.aaro.cosign.spam.Spam.Why>()
        var first: String? = null
        fun show(loading: Boolean) {
            val muted = env.muted()
            val people = SearchRank.sort(hits.values.filter { it.pubkey !in muted }, who).take(30)
            people.firstOrNull()?.pubkey?.takeIf { it != first }?.let { pk -> first = pk; mark("first is now ${env.known()[pk]?.shown ?: ""} ${pk.take(8)}") }
            val posts = synchronized(notes) { notes.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(60) }
            val theirs = synchronized(byAuthor) { byAuthor.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(40) }
            val yours = synchronized(archive) { archive.distinctBy { it.id }.take(40) }  // in PrivateRelays.relevant's order
                // from: only theirs with the words, from your archive too.
                .filter { e -> from?.strict != true || split?.let { s -> e.pubkey == s.author && AuthorQuery.has(e, s.rest, all = true) } == true }
            val f = Found(text, people.map { it.pubkey }, posts.filter { p -> theirs.none { it.id == p.id } && yours.none { it.id == p.id } }, loading, people.associateBy { it.pubkey }, split, theirs, HashMap(caught), yours)
            last = f; publish(f)
        }
        // Until trust is known, the order the relays give, syb.lol's before relay.ditto.pub's before the slower ones'.
        fun add(p: Person, rank: Int = 9) { hits.putIfAbsent(p.pubkey, Hit(p.pubkey, p.name, rank * 1000 + order.getAndIncrement(), follow = p.pubkey in mine, bare = Hit.bare(p))) }

        val relayJobs = ArrayList<kotlinx.coroutines.Job>()
        // What arrives (a count, a checked address) only marks the list changed; it's redrawn at most five times a second.
        val dirty = java.util.concurrent.atomic.AtomicBoolean(false)
        val redraw = launch { while (true) { delay(200); if (dirty.getAndSet(false)) show(true) } }
        // Follower counts: each counting relay takes the batches one after another (one counting connection
        // each), on its own, so a slow one never holds up what the other has to say.
        val toCount = env.counts.associateWith { kotlinx.coroutines.channels.Channel<List<Person>>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
        val checks = java.util.Collections.synchronizedList(ArrayList<kotlinx.coroutines.Job>())
        val counters = toCount.map { (url, queue) -> launch { for (batch in queue) count(url, batch, hits) { dirty.set(true) } } }
        fun enrich(batch: List<Person>) {
            toCount.values.forEach { it.trySend(batch) }
            checks += launch { checkAddresses(batch, who, hits) { dirty.set(true) } }
        }
        if (tag == null) {
            // Your follows, from what this phone knows: no waiting.
            env.known().values.filter { it.pubkey in mine && SearchRank.matches(it.name, who) }.forEach { add(it) }
            show(true); mark("follows: ${hits.size}")
            // name@domain: whoever it names, at the top.
            if (Nip05.isAddress(who)) relayJobs += launch {
                val pk = env.nip05Lookup(who)?.takeIf { isHex32(it) } ?: return@launch
                env.wantPeople(listOf(pk))
                hits[pk] = Hit(pk, env.known()[pk]?.name, -1, follow = pk in mine, nip05 = Nip05.Check.OK)  // an address: never bare
                show(true); mark("address found")
            }
            val relays = env.fast.map { it to 8000L } + env.slow.map { it to 3500L } + env.extra().map { it to 3000L }
            for ((i, rw) in relays.withIndex()) relayJobs += launch {
                val (url, wait) = rw
                val evs = Fetch.query(listOf(url), mapOf("kinds" to listOf(0), "search" to who, "limit" to 50), wait).filter { it.kind == 0 }
                env.addPeople(evs)
                val fresh = evs.mapNotNull { e -> Person.of(e)?.takeIf { !hits.containsKey(it.pubkey) } }
                fresh.forEach { add(it, i) }
                show(true); mark("${url.removePrefix("wss://")}: ${evs.size} people")
                // Trust for the ones whose names match: those are the ones whose order matters.
                val named = fresh.filter { SearchRank.matches(it.name, who) }
                if (named.isNotEmpty()) enrich(named.take(30))
            }
        }
        // Their posts with the other words: from the search relays, and their latest looked through here. Each is checked
        // here (a relay that ignores search would send everything): from: needs every word, the softer kinds one of them.
        suspend fun lookIn(s: AuthorQuery.Split) = coroutineScope {
            fun theirs(got: List<Event>, how: String) {
                val ok = got.filter { it.pubkey == s.author && AuthorQuery.has(it, s.rest, all = from?.strict == true || how == "latest") }
                env.remember(ok); byAuthor += ok.filter { env.safe(it) }
                show(true); mark("theirs ($how): ${ok.size}")
            }
            val f = mapOf("kinds" to listOf(1, 30023), "authors" to listOf(s.author), "limit" to 40)
            // Newest first where the relay can (Ditto's own search ranks by relevance, and a busy poster's new post falls
            // past any limit); the others don't know sort:new. No words (a bare from:name): their latest is the answer.
            if (s.rest.isNotBlank()) {
                launch { theirs(Fetch.query(env.fast, f + ("search" to s.rest + " sort:new"), 6000), "newest") }
                launch { theirs(Fetch.query(env.slow + env.extra(), f + ("search" to s.rest), 4000), "search") }
            }
            // And their latest posts (where they write, and the big relays), looked through here: what no search has indexed yet.
            launch {
                theirs(Fetch.query((env.outbox(s.author) + env.big).map { it.trimEnd('/') }.distinct(), mapOf("kinds" to listOf(1, 30023), "authors" to listOf(s.author), "limit" to 200), 5000), "latest")
            }
        }
        split?.let { s -> relayJobs += launch { lookIn(s) } }
        // from: someone you don't follow (or whose profile isn't on this phone yet): the most trusted one by that name.
        if (from != null && split == null) relayJobs += launch {
            val s = fromWhom(from, mine) ?: run { mark("from: no one by that name"); return@launch }
            split = s; env.wantPeople(listOf(s.author) + s.others); show(true); mark("from: found")
            lookIn(s)
        }
        // Your archive (private relays, logged in as you): only when you have one, and never anywhere else.
        val privates = env.privates()
        if (privates.isNotEmpty()) relayJobs += launch {
            val q = from?.rest?.takeIf { it.isNotBlank() } ?: text
            val f = PrivateRelays.searchFilter(q, tag)
            // Its login is signed first (with a box, a round trip to it), so it gets longer than the search relays.
            val got = try { Fetch.query(privates, f, 12_000) } catch (e: Exception) { mark("archive: ${e.javaClass.simpleName}"); emptyList() }
            env.addPeople(got.filter { it.kind == 0 })
            // Only the posts with all the words: the archive's own search takes any one of them.
            val posts = PrivateRelays.relevant(got.filter { it.kind == 1 || it.kind == 30023 }, q, tag)
            env.remember(posts); archive += posts
            env.wantPeople(posts.map { it.pubkey })
            show(true); mark("archive: ${posts.size} posts from ${privates.size} relays")
        }
        // Posts: the fast relays together, the slow ones on their own.
        val words = from?.rest ?: text
        val noteFilter: Map<String, Any> = if (tag != null) mapOf("kinds" to listOf(1, COMMENT), "#t" to listOf(tag), "limit" to 40) else mapOf("kinds" to listOf(1, COMMENT, 30023), "search" to words, "limit" to 40)
        // (Not for from:, which is their posts alone.)
        if (from?.strict != true) for ((relays, wait) in listOf((env.fast + if (tag != null) env.general else emptyList()) to 8000L, env.slow to 3500L)) relayJobs += launch {
            // Posts, comments and long-form articles (an article counts once, as its newest version).
            val got = Fetch.query(relays + env.extra(), noteFilter, wait).filter { it.kind == 1 || it.kind == COMMENT || it.kind == 30023 }
                .sortedByDescending { it.createdAt }.distinctBy { if (it.kind == 30023) "a:" + it.pubkey + ":" + (it.firstTag("d") ?: it.id) else it.id }
            // Only the ones with the words (or the #tag): a relay may take any one of them, or ignore the search and send everything.
            val ok = got.filter { if (tag != null) it.tags.any { t -> t.size > 1 && t[0] == "t" && t[1].equals(tag, ignoreCase = true) } else SearchRank.mentions(it, words) }
            env.remember(ok)
            notes += ok.filter { env.safe(it) }
            env.wantPeople(ok.map { it.pubkey })
            show(true); mark("posts from ${relays.size} relays: ${ok.size} of ${got.size}")
        }
        // The people your follows follow, once per run: the strongest sign of who's real to you.
        if (tag == null) relayJobs += launch {
            val f = env.friendsOfFriends()
            if (f.isEmpty()) return@launch
            hits.replaceAll { _, h -> h.copy(fof = f[h.pubkey] ?: 0) }
            show(true); mark("friends of friends")
        }
        relayJobs.joinAll()
        // Likely spam out of the ranking (strangers only): manufactured likes, or an author who writes like spam.
        if (!notes.isEmpty()) kotlinx.coroutines.withTimeoutOrNull(6000) { env.spam(synchronized(notes) { notes.distinctBy { it.id }.toList() }, mine, caught) { show(true) } }
        // Every batch is queued by now (the relay jobs queue them).
        synchronized(checks) { checks.toList() }.joinAll()
        toCount.values.forEach { it.close() }; counters.joinAll(); redraw.cancel()
        env.wantPeople(hits.keys.toList())
        show(false); mark("done")
    }

    /**
     * Whom a from: names, when it isn't someone you follow: a key or npub as it is; name@domain, whoever it names; else
     * the most trusted of the people the fast search relays know by that name (AuthorQuery.pick). Null: no one.
     */
    private suspend fun fromWhom(from: AuthorQuery.From, mine: Set<String>): AuthorQuery.Split? = coroutineScope {
        val n = from.name.trim().removePrefix("nostr:")
        fun one(pk: String?) = pk?.takeIf { isHex32(it) }?.let { AuthorQuery.Split(it, from.rest) }
        if (isHex32(n.lowercase())) return@coroutineScope one(n.lowercase())
        if (n.startsWith("npub1", ignoreCase = true)) return@coroutineScope one(try { Nip19.npubDecode(n.lowercase()) } catch (_: Exception) { null })
        if (Nip05.isAddress(n)) return@coroutineScope one(env.nip05Lookup(n))
        val evs = Fetch.query(env.fast, mapOf("kinds" to listOf(0), "search" to n, "limit" to 50), 6000).filter { it.kind == 0 }
        env.addPeople(evs)
        val found = evs.sortedByDescending { it.createdAt }.mapNotNull { Person.of(it) }.distinctBy { it.pubkey }
        val strong = AuthorQuery.ranked(found, n).filter { it.second >= AuthorQuery.STRONG }.map { it.first }.take(12).toSet()
        if (strong.size <= 1) return@coroutineScope one(strong.firstOrNull())
        val them = found.filter { it.pubkey in strong }
        val hits = ConcurrentHashMap(them.associate { it.pubkey to Hit(it.pubkey, it.name, 0, follow = it.pubkey in mine, bare = Hit.bare(it)) })
        val counted = launch { env.counts.firstOrNull()?.let { count(it, them, hits) {} } }
        them.filter { it.nip05 != null }.map { p -> async { env.nip05Check(p.pubkey, p.nip05).also { c -> hits.computeIfPresent(p.pubkey) { _, h -> h.copy(nip05 = c) } } } }.awaitAll()
        counted.join()
        AuthorQuery.pick(n, from.rest, them, hits)
    }

    /** Follower counts from one relay for people a search just found, filled into [hits] as they come; the higher count wins. */
    private suspend fun count(url: String, people: List<Person>, hits: ConcurrentHashMap<String, Hit>, changed: () -> Unit) {
        Fetch.counts(url, people.associate { it.pubkey to mapOf("kinds" to listOf(3), "#p" to listOf(it.pubkey)) }, 6000, inFlight = 15) { pk, c ->
            hits.computeIfPresent(pk) { _, h -> if ((h.followers ?: -1) >= c) h else h.copy(followers = c) }; changed()
        }
    }

    /** NIP-05 checks for the exact names only (the order among those is what they decide), within a few seconds. */
    private suspend fun checkAddresses(people: List<Person>, query: String, hits: ConcurrentHashMap<String, Hit>, changed: () -> Unit) = kotlinx.coroutines.withTimeoutOrNull(6500) { coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        people.filter { it.nip05 != null && SearchRank.exact(it.name, query) }.take(12).map { p ->
            async { gate.withPermit { env.nip05Check(p.pubkey, p.nip05) }.also { c -> hits.computeIfPresent(p.pubkey) { _, h -> h.copy(nip05 = c) }; changed() } }
        }.awaitAll()
    } }
}
