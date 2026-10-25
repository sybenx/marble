package cc.aaro.cosign

import android.content.Context
import cc.aaro.cosign.core.Bech32
import cc.aaro.cosign.core.Clock
import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import cc.aaro.cosign.core.Nip19
import cc.aaro.cosign.core.isHex32
import cc.aaro.cosign.core.toHex
import cc.aaro.cosign.net.Conn
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.joinAll
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// The home screen's Nostr: a Discover feed, search, threads, your notifications, and
// the two things you can do here (like a note, follow someone). Read once when asked,
// never kept subscribed. Your likes and follows sign through your servers like an app's.

/** What Discover shows: notes, and NIP-71 video events (normal and short) when a feed carries them. */
val FEED_KINDS = listOf(1, 21, 22)

/** Where things are read from. Lists, so one relay being down never empties a screen. */
object Sources {
    /** Discover, best first: the first that has notes is the feed. discover.syb.lol runs on one home machine. */
    val FEEDS = listOf(
        "wss://discover.syb.lol",
        "wss://feeds.nostrarchives.com/notes/trending/reactions/today",
        "wss://trending.relays.land",
    )
    /** NIP-50 search, all asked at once (relay.nostr.band and relay.noswhere.com didn't answer when this was written). */
    val SEARCH = listOf("wss://search.nos.today", "wss://search.nostrarchives.com", "wss://relay.ditto.pub")
    val PROFILES = listOf("wss://purplepag.es", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://indexer.coracle.social")
    val GENERAL = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    /** Debug builds only: more relays to read from (a local relay for tests). */
    val extra = MutableStateFlow<List<String>>(emptyList())
    /** Debug builds only: publish your own likes, follows and mutes to [extra] alone, nowhere public. */
    @Volatile var localOnly = false
}

/** Someone's kind 0, as shown. */
data class Person(val pubkey: String, val name: String?, val picture: String?, val about: String?, val nip05: String?, val at: Long) {
    val npub get() = Nip19.npub(pubkey)
    val shown get() = name?.takeIf { it.isNotBlank() } ?: shortNpub(pubkey)

    companion object {
        fun of(e: Event): Person? {
            val m = try { Json.parse(e.content) as? Map<*, *> } catch (_: Exception) { null } ?: return null
            fun s(k: String) = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            return Person(e.pubkey, (s("display_name") ?: s("displayName") ?: s("name"))?.take(60), s("picture")?.takeIf { it.startsWith("https://") },
                s("about")?.take(400), s("nip05"), e.createdAt)
        }
    }
}

/** Someone with no name yet, shown by a piece of their account ID (without the "npub1" in front). */
fun shortNpub(pk: String) = try { Nip19.npub(pk).let { it.drop(5).take(6) + "…" + it.takeLast(4) } } catch (_: Exception) { pk.take(8) }

/**
 * Profiles, for every key on this phone: in memory, and the most recent few hundred on disk
 * so the feed has names and pictures the moment it opens. Asked for in batches.
 */
object People {
    val known = MutableStateFlow<Map<String, Person>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val asked = ConcurrentHashMap<String, Long>()
    private val pending = LinkedHashSet<String>()
    private var batch: Job? = null
    private var file: File? = null
    private var saving: Job? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.cacheDir, "people.json")
        scope.launch {
            val list = try { (Json.parse(file!!.readText()) as? List<*>)?.mapNotNull { Event.fromMap(it)?.let(Person::of) } } catch (_: Exception) { null } ?: return@launch
            known.update { have -> list.associateBy { it.pubkey } + have }
        }
    }

    /** Fetch these profiles, unless asked for in the last ten minutes. */
    fun want(pubkeys: Collection<String>) {
        val now = System.currentTimeMillis()
        synchronized(pending) {
            for (pk in pubkeys) if (isHex32(pk) && now - (asked[pk] ?: 0) > 600_000) { asked[pk] = now; pending += pk }
            if (pending.isEmpty() || batch?.isActive == true) return
            batch = scope.launch {
                delay(250)
                val take = synchronized(pending) { pending.toList().also { pending.clear() } }
                take.chunked(100).map { part -> async { Fetch.query(Sources.PROFILES + Sources.extra.value, mapOf("kinds" to listOf(0), "authors" to part), 6000) } }
                    .awaitAll().flatten().let(::add)
                synchronized(pending) { if (pending.isNotEmpty()) want(emptyList()) }
            }
        }
    }

    /** Kind 0s from anywhere (a search, say): keep the newest of each. */
    fun add(events: List<Event>) {
        val fresh = events.filter { it.kind == 0 }.groupBy { it.pubkey }.mapNotNull { (_, es) -> es.maxBy { it.createdAt }.let { e -> Person.of(e)?.let { e to it } } }
        if (fresh.isEmpty()) return
        known.update { have -> have + fresh.filter { (_, p) -> (have[p.pubkey]?.at ?: -1) < p.at }.associate { (_, p) -> p.pubkey to p } }
        raw.putAll(fresh.associate { (e, p) -> p.pubkey to e })
        save()
    }

    private val raw = ConcurrentHashMap<String, Event>()

    private fun save() {
        if (saving?.isActive == true) return
        saving = scope.launch {
            delay(3000)
            val f = file ?: return@launch
            // Keep the newest few hundred: what was seen last is what's likely to be seen again.
            val old = try { (Json.parse(f.readText()) as? List<*>)?.mapNotNull { Event.fromMap(it) } } catch (_: Exception) { null } ?: emptyList()
            val all = (old.associateBy { it.pubkey } + raw).values.sortedByDescending { it.createdAt }.take(600)
            try { f.writeText(Json.stringify(all.map { it.toMap() })) } catch (_: Exception) {}
        }
    }
}

/** A Nostr identifier typed or pasted into search. */
sealed class Ref {
    data class Profile(val pubkey: String) : Ref()
    data class Note(val id: String) : Ref()

    companion object {
        fun parse(input: String): Ref? {
            val s = input.trim().removePrefix("nostr:").removePrefix("@")
            if (isHex32(s.lowercase())) return Profile(s.lowercase())
            // A web link that carries one (njump.me/npub1…, primal.net/e/nevent1…): the identifier inside it.
            val id = if (s.contains('/') || s.contains('?')) ENTITY.find(s.lowercase())?.value ?: return null else s
            val (hrp, data) = try { Bech32.decode(id, 2000) } catch (_: Exception) { return null }
            return when (hrp) {
                "npub" -> if (data.size == 32) Profile(data.toHex()) else null
                "note" -> if (data.size == 32) Note(data.toHex()) else null
                "nprofile" -> tlv(data)?.let { Profile(it) }
                "nevent" -> tlv(data)?.let { Note(it) }
                else -> null
            }
        }

        private val ENTITY = Regex("(npub1|nprofile1|note1|nevent1)[02-9ac-hj-np-z]+")

        /** The 32 bytes under type 0 of a NIP-19 TLV. */
        private fun tlv(b: ByteArray): String? {
            var i = 0
            while (i + 2 <= b.size) {
                val t = b[i].toInt() and 0xff; val l = b[i + 1].toInt() and 0xff
                if (i + 2 + l > b.size) return null
                if (t == 0 && l == 32) return b.copyOfRange(i + 2, i + 34).toHex()
                i += 2 + l
            }
            return null
        }
    }
}

/** What a note replies to: the note right above it, and the thread's first note. */
fun parentOf(e: Event): String? {
    val es = e.tags.filter { it.size > 1 && it[0] == "e" }
    return es.firstOrNull { it.getOrNull(3) == "reply" }?.get(1) ?: es.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
        ?: es.lastOrNull { it.getOrNull(3).isNullOrEmpty() || it[3] !in setOf("mention") }?.get(1)
}
fun rootOf(e: Event): String? {
    val es = e.tags.filter { it.size > 1 && it[0] == "e" }
    return es.firstOrNull { it.getOrNull(3) == "root" }?.get(1) ?: es.firstOrNull { it.getOrNull(3).isNullOrEmpty() }?.get(1)
}

/** Discover: posts and link cards, in the order they're shown. */
data class Feed(val items: List<Item> = emptyList(), val loading: Boolean = false, val source: String? = null, val failed: Boolean = false, val at: Long = 0, val more: Boolean = true) {
    val notes get() = items.mapNotNull { (it as? Item.Post)?.e }
}

data class Notice(val event: Event, val type: Type, val about: String?) {
    enum class Type { REPLY, MENTION, REACTION, REPOST }
}

data class Notices(val list: List<Notice> = emptyList(), val loading: Boolean = false, val at: Long = 0)

data class Found(val query: String, val people: List<String> = emptyList(), val notes: List<Event> = emptyList(), val loading: Boolean = true, val hits: Map<String, Hit> = emptyMap())

/** One of your actions in Cosign that your servers want you to approve first. */
data class Held(val what: String, val event: Event)

/** The home screen's state for one key. */
class Social private constructor(private val phone: Phone, context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("social." + (phone.ui.value.member?.group?.groupX?.take(16) ?: phone.slot), Context.MODE_PRIVATE)
    val me get() = phone.ui.value.member?.group?.groupX

    val feed = MutableStateFlow(Feed())
    /** Done when the first Discover cards are on screen. Reads in the background wait for it (2.5 s at most), so they don't slow that first paint. */
    val painted = kotlinx.coroutines.CompletableDeferred<Unit>()
    private suspend fun afterPaint() { kotlinx.coroutines.withTimeoutOrNull(2500) { painted.await() } }
    val notices = MutableStateFlow(Notices())
    /** Notifications newer than this were not seen yet: the bell's dot. */
    val seenAt = MutableStateFlow(prefs.getLong("seen", 0))
    val liked = MutableStateFlow(prefs.getStringSet("liked", emptySet())!!.toSet())
    /** Who you follow, from your newest follow list. Null until it's known. */
    val follows = MutableStateFlow<Set<String>?>(null)
    /** People being followed or unfollowed right now. */
    val busy = MutableStateFlow<Set<String>>(emptySet())
    /** Something to say briefly ("Couldn't like that: …"). */
    val message = MutableStateFlow<String?>(null)
    val held = MutableStateFlow<Held?>(null)

    private var contacts: Event? = null

    private fun readRelays(): List<String> {
        val mine = phone.latest("10002")?.tags?.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "write" }?.map { it[1] } ?: emptyList()
        return (mine.take(4) + Sources.GENERAL + Sources.extra.value).distinct()
    }
    private fun writeRelays(): List<String> {
        val mine = phone.latest("10002")?.tags?.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "read" }?.map { it[1] } ?: emptyList()
        val group = phone.ui.value.member?.group?.relays ?: emptyList()
        return (mine.take(4) + group + Sources.GENERAL + Sources.extra.value).distinct().take(9)
    }

    // --- Discover -----------------------------------------------------------------

    /** What's been on your screen already: Discover doesn't show it again. On this phone only. */
    private val seen = Seen(File(context.filesDir, "seen-" + (phone.ui.value.member?.group?.groupX?.take(16) ?: phone.slot) + ".txt"))
    /** What you're interested in, worked out on this phone; a copy on your account, encrypted to you. */
    val interests = Interests(prefs) { interestsChanged() }
    /** The topics you follow, for the screen. */
    val followedTopics = MutableStateFlow(interests.followed.toSet())
    /** Candidates that didn't make it onto the last page: the next page takes from these first. */
    @Volatile private var pool: Pair<List<Event>, List<TopicCard>> = emptyList<Event>() to emptyList()
    /** Where the endless tail (the curated feed, older and older) goes on from. */
    @Volatile private var tailUntil: Long? = null
    /** Whether last time's Discover went on screen from disk. */
    private val restored = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /** It's been on screen properly: never again. */
    fun saw(key: String) = seen.add(key)
    private fun unseen(key: String) = key !in seen && key !in dismissed.value
    private fun fits(e: Event) = e.kind in FEED_KINDS && Curate.keep(e) && forDiscover(e) && unseen(e.id)

    /**
     * Load Discover: posts from the first source that has them, and stories on topics, put in order by
     * what you like (as it's known now: learning more never holds this up). [force] (you pulled down):
     * only things you haven't seen, not even the ones on screen now.
     */
    fun loadFeed(force: Boolean = false) {
        val f = feed.value
        if (f.loading || (!force && f.items.isNotEmpty() && System.currentTimeMillis() - f.at < 30 * 60_000)) {
            // The notes are fresh enough; the videos may not be loaded yet.
            if (!f.loading && videos.value.cards.isEmpty()) loadPlaces()
            return
        }
        feed.update { it.copy(loading = true, failed = false) }
        scope.launch { afterPaint(); knowFollows(); knowMutes(); knowInterests() }
        val onScreen = if (force) f.items.take(lookedAt + 2).map { it.key }.toSet() else emptySet()
        lookedAt = 0
        loadPlaces()
        scope.launch {
            // With last time's feed on screen, that's painted first; without one, this is what's awaited.
            if (restored.await()) afterPaint()
            // Stories: from disk at once if there are any; the site is asked alongside the posts and is
            // only waited for while they come in. Late ones go in the pool for the pages after.
            val onDisk = TopicFeeds.load(CosignApp.instance, cachedOnly = true)
            val fromSite = async { try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { emptyList() } }
            // The curated feed first. If it has less than a screenful or two, the next source tops it up
            // (after it, never mixed in). A bit more is asked for than is shown, as some is left out.
            var used: String? = null
            val fresh = ArrayList<Event>()
            val foreign = ArrayList<Event>()
            var oldest: Long? = null
            for (src in Sources.FEEDS) {
                Startup.mark("feed asked")
                val got = Fetch.query(listOf(src), mapOf("kinds" to FEED_KINDS, "limit" to 100), 8000)
                Startup.mark("feed fetched ${got.size}")
                if (src == Sources.FEEDS[0]) oldest = got.minOfOrNull { it.createdAt }
                // Roots with something in them, nothing heavy, nothing you've seen; then three per person and one "GM" a page.
                // The curated source keeps its own order (its ranking); the others go newest first.
                val ok = got.filter { fits(it) && it.id !in onScreen && fresh.none { f -> f.id == it.id } }
                val notes = Curate.page(if (src == Sources.FEEDS[0]) ok else ok.sortedByDescending { it.createdAt }, perAuthor = 3, already = fresh)
                Startup.mark("feed filtered ${notes.size}")
                foreign += got.filter { it.kind in FEED_KINDS && Curate.keep(it) && foreignOk(it) && unseen(it.id) && foreign.none { f -> f.id == it.id } }
                if (notes.isEmpty()) continue
                if (used == null) used = src
                fresh += notes
                if (fresh.size >= 30) break
            }
            tailUntil = oldest?.minus(1); hotUntil = null
            val cardsNow = (if (onDisk.isNotEmpty()) kotlinx.coroutines.withTimeoutOrNull(300) { fromSite.await() } else kotlinx.coroutines.withTimeoutOrNull(2500) { fromSite.await() })
                ?.takeIf { it.isNotEmpty() } ?: onDisk
            val cards = cardsNow.filter { unseen("t:" + it.id) && "t:" + it.id !in onScreen }
            // Seen nearly everything: the tail fills in.
            if (fresh.size + cards.size < 10) fresh += tail(fresh)
            Startup.mark("topic cards ${cards.size}")
            if (fresh.isEmpty() && cards.isEmpty()) return@launch feed.update { it.copy(loading = false, failed = it.items.isEmpty()) }
            remember(fresh)
            People.want(fresh.map { it.pubkey })
            // What's on screen stays where it is; the new things come after it. A refresh you asked for starts over.
            feed.update { cur ->
                val keep = if (force || cur.items.isEmpty()) emptyList() else cur.items.take(lookedAt + 3)
                val page = Arrange.page(fresh.filter { n -> keep.none { it.key == n.id } }, cards.filter { c -> keep.none { it.key == "t:" + c.id } }, interests)
                pool = fresh.filter { n -> page.none { it.key == n.id } } to cards.filter { c -> page.none { it.key == "t:" + c.id } }
                Feed(keep + page, source = used ?: Sources.FEEDS[0], at = System.currentTimeMillis())
            }
            saveFeed()
            translateIn(foreign)
            // Stories that came after the page was put together: for the next pages.
            scope.launch {
                val late = fromSite.await().takeIf { it !== cardsNow } ?: return@launch
                pool = pool.first to (pool.second + late.filter { c -> unseen("t:" + c.id) && feed.value.items.none { it.key == "t:" + c.id } }).distinctBy { it.id }
            }
        }
    }

    /**
     * The endless tail: Diamonds (the curated feed) further and further back, then, when that's all
     * been seen, what was hot on syb.lol, a few days at a time, back for years. Past what you've seen,
     * until there's enough: Discover never runs out.
     */
    private suspend fun tail(have: List<Event>, want: Int = 12): List<Event> {
        val out = ArrayList<Event>()
        repeat(5) {
            val hot = tailUntil == -1L || hotUntil != null
            val until = (if (hot) hotUntil else tailUntil) ?: Clock.now()
            val got = if (!hot) Fetch.query(listOf(Sources.FEEDS[0]), mapOf("kinds" to FEED_KINDS, "limit" to 100, "until" to until), 8000)
                else Fetch.query(SearchRelays.FAST.take(1), mapOf("kinds" to listOf(1), "limit" to 100, "search" to "sort:hot", "until" to until), 8000)
            if (got.isEmpty()) { if (hot) return out; tailUntil = -1L; return@repeat }
            val next = got.minOf { it.createdAt } - 1
            if (hot) hotUntil = next else tailUntil = next
            val ok = got.filter { e -> fits(e) && have.none { it.id == e.id } && out.none { it.id == e.id } && feed.value.items.none { it.key == e.id } }
            out += Curate.page(ok, perAuthor = 2, already = have + out)
            if (out.size >= want) return out
        }
        return out
    }
    /** Where syb.lol's hot posts go on from, once Diamonds is used up. */
    @Volatile private var hotUntil: Long? = null

    // The last feed, on disk, so opening shows it at once (filtered again: you may have muted someone since, or seen it).
    private val feedFile = File(context.cacheDir, "feed-" + (phone.ui.value.member?.group?.groupX?.take(16) ?: phone.slot) + ".json")

    /** Called once the whole object is set up: the filters below need it. */
    private fun restoreFeed() {
        scope.launch {
            try {
                loadTranslations()
                val m = try { Json.parse(feedFile.readText()) as? Map<*, *> } catch (_: Exception) { null } ?: return@launch
                // What was on screen first; what was left for later pages after it's painted (or now, if that's all there is).
                val order = (m["order"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: return@launch
                val allNotes = (m["notes"] as? List<*>)?.mapNotNull { Event.fromMap(it) } ?: return@launch
                val allCards = (m["cards"] as? List<*>)?.mapNotNull { TopicCard.fromMap(it) } ?: emptyList()
                fun ok(e: Event) = Curate.keep(e) && forDiscover(e) && unseen(e.id)
                val notes = allNotes.filter { it.id in order && ok(it) }.associateBy { it.id }
                val cards = allCards.filter { "t:" + it.id in order && unseen("t:" + it.id) }.associateBy { "t:" + it.id }
                val shown = order.mapNotNull { k -> notes[k]?.let { e -> Item.Post(e) } ?: cards[k]?.let { c -> Item.Card(c) } }
                val rest = {
                    val posts = allNotes.filter { it.id !in order && ok(it) }; val more = allCards.filter { "t:" + it.id !in order && unseen("t:" + it.id) }
                    val page = Arrange.page(posts, more, interests)
                    remember(posts)
                    pool = posts.filter { n -> page.none { it.key == n.id } } to more.filter { c -> page.none { it.key == "t:" + c.id } }
                    page
                }
                val items = if (shown.size >= 5) shown else shown + rest()
                if (items.isEmpty()) return@launch
                remember(notes.values.toList())
                feed.update { if (it.items.isNotEmpty()) it else it.copy(items = items, source = m["source"] as? String, at = m["at"] as? Long ?: 0) }
                restored.complete(true)
                People.want(notes.values.map { it.pubkey })
                Startup.mark("feed from cache ${items.size}")
                if (shown.size >= 5) { afterPaint(); val page = rest(); feed.update { cur -> cur.copy(items = cur.items + page.filter { p -> cur.items.none { it.key == p.key } }) } }
            } finally { restored.complete(false) }
        }
    }

    private fun saveFeed() {
        val f = feed.value
        val items = f.items.filter { unseen(it.key) }.take(60)
        try { feedFile.writeText(Json.stringify(mapOf("source" to f.source, "at" to f.at, "order" to items.map { it.key },
            "notes" to (items.mapNotNull { (it as? Item.Post)?.e } + pool.first.take(40)).map { it.toMap() },
            "cards" to (items.mapNotNull { (it as? Item.Card)?.c } + pool.second.take(40)).map { it.toMap() }))) } catch (_: Exception) {}
    }

    /** The next page: what didn't fit on the last one (the less likely topics among it), then the tail. */
    fun loadMore() {
        val f = feed.value
        if (f.loading || !f.more || f.items.isEmpty()) return
        feed.update { it.copy(loading = true) }
        scope.launch {
            val (leftPosts, leftCards) = pool
            val posts = leftPosts.filter { unseen(it.id) }.let { if (it.size >= 12) it else it + tail(f.notes + it) }
            val cards = leftCards.filter { unseen("t:" + it.id) }
            val page = Arrange.page(posts, cards, interests)
            pool = posts.filter { n -> page.none { it.key == n.id } } to cards.filter { c -> page.none { it.key == "t:" + c.id } }
            remember(posts)
            People.want(posts.map { it.pubkey })
            // Only the very end of the curated feed's history stops it.
            feed.update { cur -> cur.copy(items = cur.items + page.filter { p -> cur.items.none { it.key == p.key } }, loading = false, more = page.isNotEmpty()) }
            saveFeed()
        }
    }

    // --- What you like: topics, learned on this phone ------------------------------------

    /**
     * In the background, never on the way to the first screen: your account's copy of what you like
     * (from another phone, say), or else, once, what you've liked and shared in the last 90 days.
     * Discover's order below where you are gets better as this lands.
     */
    private suspend fun knowInterests() {
        val me = me ?: return
        if (interestsKnown) return
        interestsKnown = true
        if (pullInterests(me)) rerank()
        else if (!interests.seeded) { seedInterests(me); rerank() }
        if (interests.at > prefs.getLong("interestsSent", 0)) interestsChanged()
    }

    private suspend fun seedInterests(me: String) {
        val mine = Fetch.query(readRelays().take(6), mapOf("kinds" to listOf(7, 6, 1), "authors" to listOf(me), "since" to Clock.now() - 90 * 86_400L, "limit" to 400), 8000)
        val votes = HashMap<String, Double>()
        fun vote(e: Event, by: Double) = TopicGuess.of(e).take(2).forEachIndexed { i, t -> votes[t] = (votes[t] ?: 0.0) + by / (i + 1) }
        mine.filter { it.kind == 1 }.forEach { vote(it, 0.3) }
        mine.filter { it.kind == 6 }.forEach { r -> reposted(r)?.let { vote(it, 2.0) } }
        val likedIds = mine.filter { it.kind == 7 && it.content != "-" }.mapNotNull { r -> r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) }.distinct().take(300)
        for (chunk in likedIds.chunked(150)) Fetch.query(readRelays().take(6), mapOf("ids" to chunk), 8000).forEach { vote(it, 1.0) }
        interests.seed(votes)
    }

    /** Put what's below where you are in order again (what you like is better known now). */
    private fun rerank() = feed.update { cur ->
        val keep = cur.items.take(lookedAt + 3)
        val rest = cur.items.drop(keep.size)
        val posts = rest.mapNotNull { (it as? Item.Post)?.e } + pool.first
        val cards = rest.mapNotNull { (it as? Item.Card)?.c } + pool.second
        val page = Arrange.page(posts, cards, interests, size = maxOf(rest.size, 24))
        pool = posts.filter { n -> page.none { it.key == n.id } } to cards.filter { c -> page.none { it.key == "t:" + c.id } }
        cur.copy(items = keep + page)
    }

    // Your account's copy: NIP-78 app data, NIP-44 encrypted to yourself, so only you can read it and
    // your other phones start from it. Saved a minute after the last change, in the background.
    private val interestsTag = "marble/interests"
    @Volatile private var interestsKnown = false
    private var sending: Job? = null

    private fun interestsChanged() {
        sending?.cancel()
        sending = scope.launch { delay(60_000); sendInterests() }
    }

    private suspend fun sendInterests() {
        val me = me ?: return
        val at = interests.at
        val text = toSelf(interests.toJson(), encrypt = true) ?: return
        val ev = Event(me, Clock.now(), 30078, listOf(listOf("d", interestsTag)), text).withId()
        val signed = try { phone.signOwn(ev) } catch (_: Exception) { return }
        if (Fetch.publish(if (Sources.localOnly) Sources.extra.value else writeRelays(), signed) > 0) prefs.edit().putLong("interestsSent", at).apply()
    }

    /** Your account's copy, if it's newer than this phone's: true if there is one. */
    private suspend fun pullInterests(me: String): Boolean {
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to listOf(interestsTag), "limit" to 4), 6000)
            .filter { it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt } ?: return false
        val json = toSelf(got.content, encrypt = false) ?: return true
        if (interests.adopt(json)) { followedTopics.value = interests.followed.toSet(); prefs.edit().putLong("interestsSent", interests.at).apply() }
        return true
    }

    /**
     * NIP-44 with yourself, as this app: blocks for a moment, so never on the open path. Null until the
     * signer has a call for it (its own grant can't read messages through NIP-55): till then the copy stays on this phone.
     */
    private fun toSelf(text: String, encrypt: Boolean): String? = null

    /** You opened a link card: a little more of its topic. */
    fun opened(c: TopicCard) = interests.learn(listOf(c.topic), 1.0)

    fun followTopic(topic: String, on: Boolean) {
        interests.follow(topic, on)
        followedTopics.value = interests.followed.toSet()
        if (on) message.value = "You'll see more about ${TopicFeeds.name(topic).lowercase()}."
    }

    /** Less about a topic, or less from a site: the cards below where you are go, and fewer come after. */
    fun lessAbout(topic: String) {
        interests.lessOfTopic(topic)
        followedTopics.value = interests.followed.toSet()
        drop { it is Item.Card && it.c.topic == topic }
        message.value = "Less about ${TopicFeeds.name(topic).lowercase()} from now on."
    }
    fun lessFrom(c: TopicCard) {
        interests.lessOfSource(c.sourceId)
        drop { it is Item.Card && it.c.sourceId == c.sourceId }
        message.value = "Less from ${c.sourceName} from now on."
    }
    /** Not this card: gone, and a little less of its topic. */
    fun notInterested(c: TopicCard) {
        interests.learn(listOf(c.topic), -1.0)
        dismissed.update { it + ("t:" + c.id) }
        prefs.edit().putStringSet("dismissed", dismissed.value.toList().takeLast(2000).toSet()).apply()
    }

    /** Take out of Discover what's below where you've looked; what you're reading stays. */
    private fun drop(what: (Item) -> Boolean) {
        feed.update { cur -> cur.copy(items = cur.items.filterIndexed { i, it -> i <= lookedAt + 1 || !what(it) }) }
        pool = pool.first to pool.second.filter { !what(Item.Card(it)) }
    }

    // --- Places: a few videos from sites you're signed in to, mixed into Discover ---------

    /** The cards, and the note they go before (-1: not placed). Placed once, never moved. */
    data class Videos(val cards: List<PlaceCard> = emptyList(), val start: Int = -1)
    val videos = MutableStateFlow(Videos())
    /** How far down Discover you've looked, in notes: cards that come late go below it. */
    @Volatile var lookedAt = 0
    private val tube by lazy { TubeFeed(CosignApp.instance, phone.slot + "." + (me?.take(16) ?: ""), { me }, { phone.signOwn(it) }) }
    private val places: List<PlaceFeed> get() = if (Places.fake) listOf(FakeTube()) else listOf(tube)

    private fun loadPlaces() {
        val opened = System.currentTimeMillis()
        // Last time's cards show at once; this time's are kept for next time unless they're on time.
        val cached = cachedCards()
        videos.value = if (cached.isEmpty()) Videos() else Videos(cached, 1)
        scope.launch {
            afterPaint()
            val got = kotlinx.coroutines.withTimeoutOrNull(8000) {
                places.map { p -> async { try { p.fetch() } catch (_: Exception) { emptyList() } } }.awaitAll()
            } ?: return@launch
            // Every place turned this account away, or has nothing: no cards at all.
            val cards = got.filterNotNull().flatten()
            if (got.all { it == null } || cards.isEmpty()) { prefs.edit().remove("places").apply(); videos.value = Videos(); return@launch }
            prefs.edit().putString("places", Json.stringify(cards.map { mapOf("id" to it.id, "title" to it.title, "channel" to it.channel, "thumb" to it.thumb,
                "url" to it.url, "duration" to it.duration, "published" to it.published, "label" to it.label) })).apply()
            if (videos.value.cards.isNotEmpty()) return@launch // never reshuffle what's on screen
            val late = System.currentTimeMillis() - opened
            val start = when {
                // Within the budget, or before Discover had anything to show: near the top.
                late <= 1500 || feed.value.items.isEmpty() -> 1
                // A little late: below what you've looked at.
                else -> lookedAt + 4
            }
            videos.value = Videos(cards, start)
        }
    }

    private fun cachedCards(): List<PlaceCard> = try {
        (Json.parse(prefs.getString("places", null) ?: "[]") as List<*>).mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            PlaceCard(m["id"] as String, m["title"] as String, m["channel"] as String, m["thumb"] as? String, m["url"] as String,
                (m["duration"] as? Long)?.toInt(), m["published"] as? Long, m["label"] as? String ?: "")
        }
    } catch (_: Exception) { emptyList() }

    // --- Search ---------------------------------------------------------------------

    val found = MutableStateFlow<Found?>(null)
    private var searching: Job? = null

    /**
     * Search: people and posts. Your follows whose names match come first, at once; then what the
     * search relays find, each as it answers (none is dropped for being slow); then the order settles
     * as names, NIP-05s and trust come in. A #tag searches posts only.
     */
    fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        if (found.value?.query == text && found.value?.loading == true) return
        searching?.cancel()
        val gen = searchGen.incrementAndGet()
        found.value = Found(text)
        val t0 = android.os.SystemClock.uptimeMillis()
        // Debug builds only: what was searched and who came up are the user's business, not the log's.
        fun mark(what: String) = if (!debuggable || searchGen.get() != gen) Unit else android.util.Log.i("CosignSearch", "\"$text\" $what at ${android.os.SystemClock.uptimeMillis() - t0} ms; top: " +
            found.value?.people?.take(3)?.joinToString { pk -> (People.known.value[pk]?.shown ?: pk.take(8)) + " " + pk.take(8) })
        searching = scope.launch {
            val tag = text.removePrefix("#").takeIf { text.startsWith("#") && it.isNotBlank() && !it.contains(' ') }?.lowercase()
            val hits = ConcurrentHashMap<String, Hit>()
            val notes = java.util.Collections.synchronizedList(ArrayList<Event>())
            val order = java.util.concurrent.atomic.AtomicInteger()
            val mine = follows.value ?: emptySet()
            var first: String? = null
            fun show(loading: Boolean) {
                if (searchGen.get() != gen) return
                val people = SearchRank.sort(hits.values.filter { it.pubkey !in muted.value }, text).take(30)
                people.firstOrNull()?.pubkey?.takeIf { it != first }?.let { pk -> first = pk; mark("first is now ${People.known.value[pk]?.shown ?: ""} ${pk.take(8)}") }
                val posts = synchronized(notes) { notes.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(60) }
                found.value = Found(text, people.map { it.pubkey }, posts, loading, people.associateBy { it.pubkey })
            }
            // Until trust is known, the order the relays give, syb.lol's before relay.ditto.pub's before the slower ones'.
            fun add(pk: String, name: String?, rank: Int = 9) { hits.putIfAbsent(pk, Hit(pk, name, rank * 1000 + order.getAndIncrement(), follow = pk in mine)) }

            val relayJobs = ArrayList<kotlinx.coroutines.Job>()
            // What arrives (a count, a checked address) only marks the list changed; it's redrawn at most five times a second.
            val dirty = java.util.concurrent.atomic.AtomicBoolean(false)
            val redraw = launch { while (true) { delay(200); if (dirty.getAndSet(false)) show(true) } }
            // Trust is looked up one batch after another, so each relay sees one counting connection at a time.
            val toEnrich = kotlinx.coroutines.channels.Channel<List<Person>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val enricher = launch { for (batch in toEnrich) enrich(batch, text, hits) { dirty.set(true) } }
            if (tag == null) {
                // Your follows, from what this phone knows: no waiting.
                People.known.value.values.filter { it.pubkey in mine && SearchRank.matches(it.name, text) }.forEach { add(it.pubkey, it.name) }
                show(true); mark("follows: ${hits.size}")
                // name@domain: whoever it names, at the top.
                if (Nip05.isAddress(text)) relayJobs += launch {
                    val pk = Nip05.lookup(text)?.takeIf { isHex32(it) } ?: return@launch
                    People.want(listOf(pk))
                    hits[pk] = Hit(pk, People.known.value[pk]?.name, -1, follow = pk in mine, nip05 = Nip05.Check.OK)
                    show(true); mark("address found")
                }
                val relays = SearchRelays.FAST.map { it to 8000L } + SearchRelays.SLOW.map { it to 3500L } + Sources.extra.value.map { it to 3000L }
                for ((i, rw) in relays.withIndex()) relayJobs += launch {
                    val (url, wait) = rw
                    val evs = Fetch.query(listOf(url), mapOf("kinds" to listOf(0), "search" to text, "limit" to 50), wait).filter { it.kind == 0 }
                    People.add(evs)
                    val fresh = evs.mapNotNull { e -> Person.of(e)?.takeIf { !hits.containsKey(it.pubkey) } }
                    fresh.forEach { add(it.pubkey, it.name, i) }
                    show(true); mark("${url.removePrefix("wss://")}: ${evs.size} people")
                    // Trust for the ones whose names match: those are the ones whose order matters.
                    val named = fresh.filter { SearchRank.matches(it.name, text) }
                    if (named.isNotEmpty()) toEnrich.send(named.take(30))
                }
            }
            // Posts: the fast relays together, the slow ones on their own.
            val noteFilter: Map<String, Any> = if (tag != null) mapOf("kinds" to listOf(1), "#t" to listOf(tag), "limit" to 40) else mapOf("kinds" to listOf(1), "search" to text, "limit" to 40)
            for ((relays, wait) in listOf((SearchRelays.FAST + if (tag != null) Sources.GENERAL else emptyList()) to 8000L, SearchRelays.SLOW to 3500L)) relayJobs += launch {
                val got = Fetch.query(relays + Sources.extra.value, noteFilter, wait).filter { it.kind == 1 }
                remember(got)
                notes += got.filter { safe(it) }
                People.want(got.map { it.pubkey })
                show(true); mark("posts from ${relays.size} relays: ${got.size}")
            }
            // The people your follows follow, once per run: the strongest sign of who's real to you.
            if (tag == null) relayJobs += launch {
                val f = friendsOfFriends()
                if (f.isEmpty()) return@launch
                hits.replaceAll { _, h -> h.copy(fof = f[h.pubkey] ?: 0) }
                show(true); mark("friends of friends")
            }
            relayJobs.joinAll()
            toEnrich.close(); enricher.join(); redraw.cancel()
            People.want(hits.keys.toList())
            show(false); mark("done")
        }
    }

    /** Follower counts and NIP-05 checks for people a search just found, filled into [hits] as they come. */
    private suspend fun enrich(people: List<Person>, query: String, hits: ConcurrentHashMap<String, Hit>, changed: () -> Unit) = kotlinx.coroutines.withTimeoutOrNull(6500) { kotlinx.coroutines.coroutineScope {
        // Each count and each check counts as soon as it's in: no waiting for the slowest.
        for (url in SearchRelays.COUNTS) launch {
            Fetch.counts(url, people.associate { it.pubkey to mapOf("kinds" to listOf(3), "#p" to listOf(it.pubkey)) }, 6000, inFlight = 15) { pk, c ->
                hits.computeIfPresent(pk) { _, h -> if ((h.followers ?: -1) >= c) h else h.copy(followers = c) }; changed()
            }
        }
        // Addresses checked for the exact names only (the order among those is what they decide), within a few seconds.
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        people.filter { it.nip05 != null && SearchRank.exact(it.name, query) }.take(12).map { p ->
            async { gate.withPermit { Nip05.check(p.pubkey, p.nip05) }.also { c -> hits.computeIfPresent(p.pubkey) { _, h -> h.copy(nip05 = c) }; changed() } }
        }.awaitAll()
    }
    }

    private val searchGen = java.util.concurrent.atomic.AtomicInteger()
    private val debuggable = CosignApp.instance.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    @Volatile private var fofCache: Map<String, Int>? = null
    /** For each person, how many of the people you follow follow them (from up to 300 follow lists). */
    private suspend fun friendsOfFriends(): Map<String, Int> {
        fofCache?.let { return it }
        val mine = follows.value?.toList()?.take(300) ?: return emptyMap()
        if (mine.isEmpty()) return emptyMap()
        val lists = mine.chunked(100).map { part -> scope.async { Fetch.query(Sources.PROFILES.take(3), mapOf("kinds" to listOf(3), "authors" to part), 6000) } }.awaitAll().flatten()
        val count = HashMap<String, Int>()
        lists.filter { it.kind == 3 }.groupBy { it.pubkey }.values.map { l -> l.maxBy { it.createdAt } }
            .forEach { l -> l.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }.distinct().forEach { count[it] = (count[it] ?: 0) + 1 } }
        return count.also { fofCache = it }
    }

    fun clearSearch() { searching?.cancel(); searchGen.incrementAndGet(); found.value = null }

    // --- Threads and profiles --------------------------------------------------------

    /** Threads opened lately, so going back to one shows it at once. */
    private val threads = object : LinkedHashMap<String, List<Pair<Event, Int>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<Event, Int>>>?) = size > 30
    }

    /** What can be shown of thread [id] right now, with no network: as opened before, or the note itself (under its first note, if that's here). Null if neither is. */
    fun threadNow(id: String): List<Pair<Event, Int>>? {
        synchronized(threads) { threads[id] }?.let { return it }
        val n = note(id) ?: return null
        val root = rootOf(n)?.let { note(it) }
        return if (root != null && root.id != n.id) listOf(root to 0, n to 1) else listOf(n to 0)
    }

    /** The whole thread [id] is in: its first note, then the replies, each with how deep it sits. */
    suspend fun thread(id: String): List<Pair<Event, Int>> {
        val relays = readRelays() + listOfNotNull(feed.value.source) + Sources.SEARCH.take(1)
        val note = note(id) ?: Fetch.query(relays, mapOf("ids" to listOf(id)), 6000).firstOrNull()?.also { remember(listOf(it)) } ?: return emptyList()
        val rootId = rootOf(note) ?: note.id
        val got = Fetch.query(relays, listOf(mapOf("ids" to listOf(rootId)), mapOf("kinds" to listOf(1), "#e" to listOf(rootId), "limit" to 200)), 7000)
        val root = if (rootId == note.id) note else note(rootId) ?: got.firstOrNull { it.id == rootId }
        // Replies, not notes that only mention it.
        val replies = got.filter { e -> e.kind == 1 && e.id != rootId && safe(e) && e.tags.any { it.size > 1 && it[0] == "e" && it[1] == rootId && it.getOrNull(3) != "mention" } }
        val all = listOfNotNull(root) + replies + (if (root == null) listOf(note) else emptyList())
        remember(all)
        People.want(all.map { it.pubkey })
        // Each reply under the note it answers, oldest first.
        val byParent = all.drop(1).distinctBy { it.id }.groupBy { parentOf(it)?.takeIf { p -> all.any { a -> a.id == p } } ?: all.first().id }
        val out = ArrayList<Pair<Event, Int>>()
        fun walk(e: Event, depth: Int) {
            if (out.any { it.first.id == e.id }) return
            out += e to depth
            byParent[e.id]?.sortedBy { it.createdAt }?.forEach { walk(it, depth + 1) }
        }
        walk(all.first(), 0)
        synchronized(threads) { threads[id] = out }
        return out
    }

    /** Someone's name and picture, straight from the two fast relays (over connections kept open a while), not the batched path. */
    fun profileNow(pk: String) {
        for (url in SearchRelays.FAST) scope.launch { People.add(Conn.of(url).req(listOf(mapOf("kinds" to listOf(0), "authors" to listOf(pk), "limit" to 1)), 4000)) }
        People.want(listOf(pk))
    }

    /**
     * What someone posted (posts, reposts, videos), delivered relay by relay as each answers, so one
     * slow relay never holds the screen: syb.lol and relay.ditto.pub at once over kept connections,
     * and, in parallel, the relays they say they write to (remembered between visits) and yours.
     * [until]: older than this, for the next page. Returns when every relay has answered or given up.
     */
    suspend fun notesBy(pk: String, until: Long? = null, onSome: (List<Event>) -> Unit) = kotlinx.coroutines.coroutineScope {
        val filter = buildMap<String, Any> { put("kinds", listOf(1, 6, 21, 22)); put("authors", listOf(pk)); put("limit", 30); until?.let { put("until", it - 1) } }
        val lock = Any()
        fun deliver(got: List<Event>) {
            val mine = got.filter { it.pubkey == pk && it.kind in listOf(1, 6, 21, 22) }
            if (mine.isEmpty()) return
            val inner = mine.filter { it.kind == 6 }.mapNotNull { reposted(it) }
            remember(mine.filter { it.kind != 6 } + inner)
            People.want(inner.map { it.pubkey })
            synchronized(lock) { onSome(mine) }
        }
        for (url in SearchRelays.FAST) launch { deliver(Conn.of(url).req(listOf(filter), 5000)) }
        launch {
            val others = (outboxOf(pk) + readRelays().take(3) + Sources.PROFILES.take(2)).distinct() - SearchRelays.FAST.toSet()
            for (url in others) launch { deliver(Fetch.query(listOf(url), filter, 6000)) }
        }
    }

    /** A post by id, for a repost that only points at it: asked for when that repost comes on screen. */
    suspend fun fetchNote(id: String): Event? {
        note(id)?.let { return it }
        val got = kotlinx.coroutines.coroutineScope {
            SearchRelays.FAST.map { url -> async { Conn.of(url).req(listOf(mapOf("ids" to listOf(id))), 4000) } }.awaitAll().flatten()
        }.firstOrNull { it.id == id && it.kind == 1 } ?: return null
        remember(listOf(got))
        People.want(listOf(got.pubkey))
        return got
    }

    /** The post a repost (kind 6) carries in its content, if it's there and genuine; else the one it points at, if it's been seen. */
    fun reposted(r: Event): Event? = (try { Event.fromMap(Json.parse(r.content))?.takeIf { it.kind == 1 && it.verify() } } catch (_: Exception) { null })
        ?: r.firstTag("e")?.let { note(it) }

    /** The relays someone says they write to (their NIP-65 list): up to four; kept on disk for a week, so the next visit asks them at once. */
    private val outboxes = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    private val outboxFile = File(context.cacheDir, "outboxes.json")
    private suspend fun outboxOf(pk: String): List<String> {
        if (outboxes.isEmpty()) loadOutboxes()
        outboxes[pk]?.let { (l, at) -> if (System.currentTimeMillis() - at < 7 * 86_400_000L) return l }
        val l = Fetch.query(Sources.PROFILES.take(3), mapOf("kinds" to listOf(10002), "authors" to listOf(pk), "limit" to 1), 4000).maxByOrNull { it.createdAt }
        val list = l?.tags?.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "read" && it[1].startsWith("wss://") }?.map { it[1] }?.take(4) ?: emptyList()
        outboxes[pk] = list to System.currentTimeMillis()
        saveOutboxes()
        return list
    }
    private fun loadOutboxes() = try {
        (Json.parse(outboxFile.readText()) as? Map<*, *>)?.forEach { (k, v) ->
            val m = v as? Map<*, *> ?: return@forEach
            outboxes[k as String] = ((m["r"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()) to ((m["at"] as? Long) ?: 0L)
        }
    } catch (_: Exception) {}
    private fun saveOutboxes() = synchronized(outboxFile) {
        try { outboxFile.writeText(Json.stringify(outboxes.entries.sortedByDescending { it.value.second }.take(500).associate { (k, v) -> k to mapOf("r" to v.first, "at" to v.second) })) } catch (_: Exception) {}
    }

    /** The relays someone reads from (their inbox): where a reply or a reaction to them should go. */
    private suspend fun inboxOf(pk: String): List<String> {
        val l = Fetch.query(Sources.PROFILES.take(3), mapOf("kinds" to listOf(10002), "authors" to listOf(pk), "limit" to 1), 4000).maxByOrNull { it.createdAt }
        return l?.tags?.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "write" && it[1].startsWith("wss://") }?.map { it[1] }?.take(3) ?: emptyList()
    }

    // --- Replying and reporting ----------------------------------------------------------

    /**
     * Reply to [parent] (NIP-10: the thread's first post as root, the post you answer as reply,
     * everyone in the conversation tagged), sent to your relays and the author's. The reply, once sent; null if it wasn't.
     */
    suspend fun reply(parent: Event, text: String): Event? {
        val me = me ?: return null
        val body = text.trim().ifEmpty { return null }
        val root = rootOf(parent) ?: parent.id
        val tags = buildList {
            add(listOf("e", root, "", "root"))
            if (root != parent.id) add(listOf("e", parent.id, "", "reply"))
            (listOf(parent.pubkey) + parent.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }).distinct().filter { it != me && cc.aaro.cosign.core.isHex32(it) }
                .forEach { add(listOf("p", it)) }
        }
        val signed = actEvent(Event(me, Clock.now(), 1, tags, body).withId(), relays = writeRelays() + inboxOf(parent.pubkey)) ?: return null
        remember(listOf(signed))
        // In the thread it was written in, under the post it answers, at once.
        synchronized(threads) {
            for ((k, list) in threads.entries.toList()) {
                val i = list.indexOfFirst { it.first.id == parent.id }.takeIf { it >= 0 } ?: continue
                val depth = list[i].second
                var end = i + 1
                while (end < list.size && list[end].second > depth) end++
                threads[k] = list.take(end) + (signed to depth + 1) + list.drop(end)
            }
        }
        return signed
    }

    /** NIP-56: report a post and its author (spam, nudity, illegal, impersonation, other), and stop showing it. */
    fun report(n: Event, reason: String) {
        val me = me ?: return
        notInterested(n)
        scope.launch {
            val tags = listOf(listOf("e", n.id, reason), listOf("p", n.pubkey, reason))
            if (act(Event(me, Clock.now(), 1984, tags, "").withId())) message.value = "Reported. Thanks for telling us."
        }
    }

    // --- Notifications ---------------------------------------------------------------

    fun loadNotices(force: Boolean = false) {
        val me = me ?: return
        val n = notices.value
        if (n.loading || (!force && n.at != 0L && System.currentTimeMillis() - n.at < 5 * 60_000)) return
        notices.update { it.copy(loading = true) }
        scope.launch {
            afterPaint()
            val evs = Fetch.query(readRelays(), mapOf("kinds" to listOf(1, 6, 7), "#p" to listOf(me), "limit" to 80), 7000).filter { it.pubkey != me && it.pubkey !in muted.value }
            val list = evs.sortedByDescending { it.createdAt }.map { e ->
                when (e.kind) {
                    7 -> Notice(e, Notice.Type.REACTION, e.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1))
                    6 -> Notice(e, Notice.Type.REPOST, e.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1))
                    else -> if (parentOf(e) != null) Notice(e, Notice.Type.REPLY, e.id) else Notice(e, Notice.Type.MENTION, e.id)
                }
            }
            remember(evs.filter { it.kind == 1 })
            // What the likes were on: your notes.
            val about = list.filter { it.type == Notice.Type.REACTION || it.type == Notice.Type.REPOST }.mapNotNull { it.about }.distinct().filter { note(it) == null }
            if (about.isNotEmpty()) remember(Fetch.query(readRelays(), mapOf("ids" to about.take(60)), 5000))
            People.want(list.map { it.event.pubkey })
            notices.value = Notices(list, at = System.currentTimeMillis())
        }
    }

    fun unseen(): Int = notices.value.list.count { it.event.createdAt > seenAt.value }

    fun markSeen() {
        val newest = notices.value.list.maxOfOrNull { it.event.createdAt } ?: return
        if (newest <= seenAt.value) return
        seenAt.value = newest
        prefs.edit().putLong("seen", newest).apply()
    }

    // --- Likes and follows -------------------------------------------------------------

    fun like(note: Event) {
        val me = me ?: return
        if (note.id in liked.value) return
        mark(note.id, true)
        interests.learn(TopicGuess.of(reposted(note) ?: note), 1.0)
        scope.launch {
            val tags = listOf(listOf("e", note.id), listOf("p", note.pubkey), listOf("k", note.kind.toString()))
            if (!act(Event(me, Clock.now(), 7, tags, "+").withId())) mark(note.id, false)
        }
    }

    private fun mark(id: String, on: Boolean) {
        liked.update { if (on) it + id else it - id }
        prefs.edit().putStringSet("liked", liked.value.toList().takeLast(2000).toSet()).apply()
    }

    fun follow(pk: String, on: Boolean) {
        val me = me ?: return
        if (pk in busy.value || pk == me) return
        busy.update { it + pk }
        scope.launch {
            try {
                // Start from your newest follow list anywhere, never from an empty one by mistake.
                val list = newestContacts(fetch = true)
                if (list == null && !on) return@launch
                val old = list?.tags ?: emptyList()
                val has = old.any { it.size > 1 && it[0] == "p" && it[1] == pk }
                if (has == on) { contacts = list; follows.value = followsIn(list); return@launch }
                val tags = if (on) old + listOf(listOf("p", pk)) else old.filterNot { it.size > 1 && it[0] == "p" && it[1] == pk }
                val at = maxOf(Clock.now(), (list?.createdAt ?: 0) + 1)
                act(Event(me, at, 3, tags, list?.content ?: "").withId())
            } finally { busy.update { it - pk } }
        }
    }

    private fun followsIn(e: Event?) = e?.tags?.filter { it.size > 1 && it[0] == "p" }?.map { it[1] }?.toSet() ?: emptySet()

    /** Your newest follow list: what this phone has seen signed, or what your relays have. */
    private suspend fun newestContacts(fetch: Boolean): Event? = newest(3, contacts, fetch)

    /** Your newest version of a list (follows, mutes): what this phone has seen signed, or what your relays have. */
    private suspend fun newest(kind: Int, have: Event?, fetch: Boolean): Event? {
        val me = me ?: return null
        val local = listOfNotNull(phone.latest(kind.toString()), have)
        val remote = if (fetch) Fetch.query((writeRelays() + Sources.PROFILES).distinct(), mapOf("kinds" to listOf(kind), "authors" to listOf(me), "limit" to 1), 6000)
            .filter { it.pubkey == me && it.kind == kind } else emptyList()
        return (local + remote).maxByOrNull { it.createdAt }
    }

    // --- Keeping Discover pleasant ---------------------------------------------------------

    /** People you muted: your mute list's public part, and the ones muted here. */
    val muted = MutableStateFlow(prefs.getStringSet("muted", emptySet())!!.toSet())
    /** Notes you said you're not interested in. Only on this phone. */
    val dismissed = MutableStateFlow(prefs.getStringSet("dismissed", emptySet())!!.toSet())
    private val languages by lazy { Lang.phone() }
    private var mutes: Event? = null

    /** Shown anywhere: no content warning, nothing adult, nobody you muted. */
    fun safe(e: Event) = !Safety.flagged(e) && e.pubkey !in muted.value && e.id !in dismissed.value && !mutedText(e.content, e)
    /** In Discover, also only in a language this phone is set to, or translated into it. */
    private fun forDiscover(e: Event) = safe(e) && !snoozedNow(e.pubkey) && (Lang.readable(e, languages) || translations.value[e.id]?.let { okText(it.text) } == true)
    /** In another language, and fine otherwise: worth translating. */
    private fun foreignOk(e: Event) = Translate.on && safe(e) && !snoozedNow(e.pubkey) && !Lang.readable(e, languages) && e.id !in translations.value

    /** Words and hashtags you muted (your mute list's "word" and "t" entries). */
    val mutedWords = MutableStateFlow(prefs.getStringSet("mutedWords", emptySet())!!.toSet())
    private fun mutedText(text: String, e: Event? = null): Boolean {
        val words = mutedWords.value
        if (words.isEmpty()) return false
        val lower = text.lowercase()
        return words.any { w -> if (w.startsWith("#")) e?.tags?.any { it.size > 1 && it[0] == "t" && it[1].lowercase() == w.drop(1) } == true || "(?<![\\p{L}\\p{N}_])${Regex.escape(w)}(?![\\p{L}\\p{N}_])".toRegex().containsMatchIn(lower) else lower.contains(w) }
    }
    /** A translation passes the same text filters as an original: adult hashtags, muted words. */
    private fun okText(text: String) = !Safety.flaggedText(text) && !mutedText(text)

    // --- Translations (your LibreTranslate, if you set one) ---------------------------------

    /** A note's text in this phone's language, and the language it was in (null: it was in yours after all). */
    data class Translated(val text: String, val from: String?)
    val translations = MutableStateFlow<Map<String, Translated>>(emptyMap())
    private val translationsFile = File(context.cacheDir, "translations-" + (phone.ui.value.member?.group?.groupX?.take(16) ?: phone.slot) + ".json")

    private fun loadTranslations() {
        val m = try { Json.parse(translationsFile.readText()) as? Map<*, *> } catch (_: Exception) { null } ?: return
        translations.value = m.entries.mapNotNull { (k, v) -> val x = v as? Map<*, *> ?: return@mapNotNull null; (k as String) to Translated(x["text"] as? String ?: return@mapNotNull null, x["from"] as? String) }.toMap()
    }

    /** Translate these, in one request, in the background; the ones that pass go in below what's on screen. */
    private fun translateIn(notes: List<Event>) {
        if (notes.isEmpty() || !Translate.on) return
        scope.launch {
            afterPaint()
            val todo = notes.distinctBy { it.id }.take(40)
            val target = Translate.target()
            val out = Translate.batch(todo.map { it.content.take(1500) }, target) ?: return@launch
            val got = todo.zip(out).associate { (e, r) -> e.id to Translated(r.first, r.second?.takeIf { it != target }) }
            translations.update { (it + got).entries.toList().takeLast(600).associate { (k, v) -> k to v } }
            try { translationsFile.writeText(Json.stringify(translations.value.mapValues { (_, t) -> mapOf("text" to t.text, "from" to t.from) })) } catch (_: Exception) {}
            val add = todo.filter { e -> got[e.id]?.let { okText(it.text) && !Curate.heavy(it.text) } == true }
            if (add.isEmpty()) return@launch
            remember(add)
            People.want(add.map { it.pubkey })
            // Below what's on screen, by time among the rest; nothing you're looking at moves.
            feed.update { cur ->
                val keep = cur.items.take(lookedAt + 3)
                val rest = cur.items.drop(keep.size) + add.filter { a -> cur.items.none { it.key == a.id } }.map { Item.Post(it) }
                cur.copy(items = keep + rest)
            }
            saveFeed()
        }
    }

    /** People whose notes you said you're not interested in, until when (ms): Discover leaves them out for a while. */
    val snoozed = MutableStateFlow(prefs.getStringSet("snoozed", emptySet())!!.mapNotNull { s ->
        s.substringAfter(':').toLongOrNull()?.takeIf { it > System.currentTimeMillis() }?.let { s.substringBefore(':') to it } }.toMap())

    /** Not this note, and less like it: the same author is left out of Discover for three days. */
    fun notInterested(e: Event) {
        interests.learn(TopicGuess.of(e), -1.0)
        dismissed.update { it + e.id }
        snoozed.update { it + (e.pubkey to System.currentTimeMillis() + 3 * 86_400_000L) }
        prefs.edit().putStringSet("dismissed", dismissed.value.toList().takeLast(2000).toSet())
            .putStringSet("snoozed", snoozed.value.map { (pk, until) -> "$pk:$until" }.toSet()).apply()
    }

    fun snoozedNow(pk: String) = (snoozed.value[pk] ?: 0) > System.currentTimeMillis()

    /** Mute someone: hidden here at once, and added to your mute list (kind 10000), signed like a follow. */
    fun mute(pk: String) {
        val me = me ?: return
        if (pk == me) return
        muted.update { it + pk }
        prefs.edit().putStringSet("muted", muted.value).apply()
        scope.launch {
            val list = newest(10000, mutes, fetch = true)
            val old = list?.tags ?: emptyList()
            if (old.any { it.size > 1 && it[0] == "p" && it[1] == pk }) return@launch
            // The private part (encrypted in content) stays as it was.
            val ev = Event(me, maxOf(Clock.now(), (list?.createdAt ?: 0) + 1), 10000, old + listOf(listOf("p", pk)), list?.content ?: "").withId()
            act(ev)
        }
    }

    /** Your mute list, read from your relays once per run of the app; between runs, what was read is kept here. */
    @Volatile private var mutesKnown = false
    private suspend fun knowMutes() {
        if (mutesKnown) return
        val list = newest(10000, mutes, fetch = true) ?: return
        mutesKnown = true
        mutes = list
        val pks = list.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }
        if (pks.isNotEmpty()) { muted.update { it + pks }; prefs.edit().putStringSet("muted", muted.value).apply() }
        val words = list.tags.filter { it.size > 1 && (it[0] == "word" || it[0] == "t") }.map { (if (it[0] == "t") "#" else "") + it[1].lowercase() }.filter { it.length > 1 }.toSet()
        mutedWords.value = words
        prefs.edit().putStringSet("mutedWords", words).apply()
    }

    private suspend fun knowFollows() {
        if (follows.value != null) return
        val c = newestContacts(fetch = true)
        contacts = c
        follows.value = followsIn(c)
    }

    /** Your servers sign it (or hand it to you to approve), then it goes to your relays. */
    private suspend fun act(ev: Event, approved: Boolean = false): Boolean = actEvent(ev, approved) != null

    /** Your servers sign it (or hand it to you to approve), then it goes to [relays] (yours by default). What was sent, or null. */
    private suspend fun actEvent(ev: Event, approved: Boolean = false, relays: List<String>? = null): Event? {
        val signed = try { phone.signOwn(ev, approved) } catch (e: NeedsApproval) {
            held.value = Held(e.what, ev); return null
        } catch (e: Exception) {
            message.value = "Couldn't do that right now: ${e.message ?: "no answer"}"; return null
        }
        if (signed.kind == 3) { contacts = signed; follows.value = followsIn(signed) }
        if (signed.kind == 10000) mutes = signed
        val n = Fetch.publish(if (Sources.localOnly) Sources.extra.value else (relays ?: writeRelays()).distinct().take(10), signed)
        if (n == 0) message.value = "Couldn't send that right now. Try again later."
        return signed.takeIf { n > 0 }
    }

    /** You approved (or not) one of your own actions your servers held. */
    fun decideHeld(approve: Boolean) {
        val h = held.value ?: return
        held.value = null
        if (!approve) { if (h.event.kind == 7) h.event.firstTag("e")?.let { mark(it, false) }; return }
        scope.launch { act(h.event, approved = true) }
    }

    // --- Notes seen, by id ----------------------------------------------------------------

    fun note(id: String): Event? = noteOf(id)

    companion object {
        private val notes = object : LinkedHashMap<String, Event>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Event>?) = size > 1500
        }
        private fun remember(es: List<Event>) = synchronized(notes) { for (e in es) notes[e.id] = e }
        private fun noteOf(id: String) = synchronized(notes) { notes[id] }

        private val all = HashMap<String, Social>()
        fun of(phone: Phone): Social = synchronized(all) {
            all.getOrPut(phone.slot + "/" + phone.ui.value.member?.group?.groupX) { People.init(CosignApp.instance); Social(phone, CosignApp.instance).also { it.restoreFeed() } }
        }
    }

    private fun remember(es: List<Event>) = Companion.remember(es)
}
