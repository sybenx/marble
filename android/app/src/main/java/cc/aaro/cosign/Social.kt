package cc.aaro.cosign

import android.content.Context
import cc.aaro.cosign.core.Bech32
import cc.aaro.cosign.core.Clock
import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import cc.aaro.cosign.core.Nip19
import cc.aaro.cosign.core.isHex32
import cc.aaro.cosign.core.toHex
import cc.aaro.cosign.core.hexToBytes
import cc.aaro.cosign.net.Conn
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
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
    /**
     * Android brought Marble back after closing it in the background (you were in the browser, say): the
     * page goes back on screen as it was, what you'd seen included. A fresh start leaves out what's been seen.
     */
    @Volatile var resumed = false
    /** Debug builds only: build the home feed as this account sees it (its follows, likes and topics), read-only, to compare with a mock made for it. */
    @Volatile var viewAs: String? = null
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
/**
 * A post's address for other apps (NIP-19 nevent): its id, the relays to look on, its author and kind,
 * so an app that has never seen it can find it, not only one that already has it.
 */
fun nevent(id: String, author: String, kind: Int, relays: List<String>): String {
    val out = java.io.ByteArrayOutputStream()
    fun tlv(t: Int, v: ByteArray) { out.write(t); out.write(v.size); out.write(v) }
    tlv(0, id.hexToBytes())
    relays.forEach { tlv(1, it.toByteArray()) }
    tlv(2, author.hexToBytes())
    tlv(3, java.nio.ByteBuffer.allocate(4).putInt(kind).array())
    return Bech32.encode("nevent", out.toByteArray())
}

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
    /** Relays the link said to look on (nprofile, nevent): not part of what it is. */
    var relays: List<String> = emptyList()
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
                "nprofile" -> tlv(data)?.let { Profile(it).apply { relays = hints(data) } }
                "nevent" -> tlv(data)?.let { Note(it).apply { relays = hints(data) } }
                else -> null
            }
        }

        private val ENTITY = Regex("(npub1|nprofile1|note1|nevent1)[02-9ac-hj-np-z]+")

        /** The relays under type 1 of a NIP-19 TLV (wss:// ones, at most four). */
        private fun hints(b: ByteArray): List<String> {
            val out = ArrayList<String>(); var i = 0
            while (i + 2 <= b.size) {
                val t = b[i].toInt() and 0xff; val l = b[i + 1].toInt() and 0xff
                if (i + 2 + l > b.size) break
                if (t == 1) String(b, i + 2, l).trim().takeIf { it.startsWith("wss://") }?.let { out += it.trimEnd('/') }
                i += 2 + l
            }
            return out.take(4)
        }

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

/** The home feed: today's stories for the box at the top, and the posts from people. It ends. */
data class Feed(val stories: List<TopicCard> = emptyList(), val posts: List<Pick> = emptyList(), val loading: Boolean = false, val failed: Boolean = false, val at: Long = 0) {
    val notes get() = posts.map { it.e }
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
    /** Whose follows, likes and lists the feed is read for: yours (or, in a debug build, the account it's told to look as). */
    private val reader get() = Sources.viewAs ?: me

    val feed = MutableStateFlow(Feed())
    /** Done when the first Discover cards are on screen. Reads in the background wait for it (2.5 s at most), so they don't slow that first paint. */
    val painted = kotlinx.coroutines.CompletableDeferred<Unit>()
    private suspend fun afterPaint() { kotlinx.coroutines.withTimeoutOrNull(2500) { painted.await() } }
    val notices = MutableStateFlow(Notices())
    /** Notifications newer than this were not seen yet: the bell's dot. */
    val seenAt = MutableStateFlow(prefs.getLong("seen", 0))
    val liked = MutableStateFlow(prefs.getStringSet("liked", emptySet())!!.toSet())
    /** Your newest reaction to each post you reacted to with an emoji ("+" is a plain like, and isn't kept here). */
    val reactions = MutableStateFlow(prefs.getStringSet("reactions", emptySet())!!.mapNotNull { r -> r.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap())
    /** How often you've used each emoji, for the row a long press on the heart opens. */
    private val emojiUse = HashMap<String, Int>().apply {
        prefs.getString("emojiUse", null)?.let { s -> try { val o = org.json.JSONObject(s); o.keys().forEach { k -> put(k, o.optInt(k)) } } catch (_: Exception) {} }
    }
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
    val interests = Interests(if (Sources.viewAs == null) prefs else context.getSharedPreferences("viewas", Context.MODE_PRIVATE).also { it.edit().clear().commit() }) { interestsChanged() }
    /** The topics you follow, for the screen. */
    val followedTopics = MutableStateFlow(interests.followed.toSet())
    /** It's been on screen properly: never again. */
    /**
     * On screen properly. A post: never again (and on your other phones too). A story ("t:" and its id): only
     * counted as shown; it goes when you open it ([opened]) or dismiss it, and makes way for newer ones after
     * being shown on a few opens.
     */
    fun saw(key: String) { if (key.startsWith("t:")) shown(key.drop(2)) else if (seen.add(key)) seenChanged() }
    private fun unseen(key: String) = key !in seen && key !in dismissed.value
    /** A story you opened, or dismissed: not in the box or More stories again (unless there's nothing else). */
    private fun storyGone(c: TopicCard) = storyOpened(c) || storyDismissed(c)
    private fun storyOpened(c: TopicCard) = "o:" + c.id in seen
    private fun storyDismissed(c: TopicCard) = "t:" + c.id in dismissed.value

    // How many fresh opens each story was shown on (on this phone only): counted once a start.
    private val shownCounts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap<String, Int>().apply {
        prefs.getString("storiesShown", null)?.let { s -> try { val o = org.json.JSONObject(s); o.keys().forEach { k -> put(k, o.optInt(k)) } } catch (_: Exception) {} }
    }
    private val shownThisOpen = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun shown(id: String) {
        if (!shownThisOpen.add(id)) return
        shownCounts[id] = (shownCounts[id] ?: 0) + 1
        val keep = shownCounts.entries.toList().takeLast(1000).associate { it.key to it.value }
        prefs.edit().putString("storiesShown", org.json.JSONObject(keep).toString()).apply()
    }
    private fun shownCount(id: String) = shownCounts[id] ?: 0
    /** Today's stories, all of them, for "More stories" (seen ones are left out there). */
    val allStories = MutableStateFlow<List<TopicCard>>(emptyList())
    /** These stories again, in this order (More stories coming back from one), from the phone's copy if need be. */
    suspend fun storiesById(ids: List<String>): List<TopicCard> {
        if (allStories.value.isEmpty()) allStories.value = TopicFeeds.load(CosignApp.instance, cachedOnly = true)
        val byId = allStories.value.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun unseenStories(): List<TopicCard> {
        // Opened from last time's feed, the full list may not be read yet: the phone's copy, or the site's.
        if (allStories.value.isEmpty()) allStories.value = TopicFeeds.load(CosignApp.instance, cachedOnly = true).ifEmpty { try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { emptyList() } }
        val left = allStories.value.filter { !storyGone(it) }
        // A slow news day, or an old copy (no connection): the last week's rather than next to none.
        val out = Calm.stories(left, interests, Clock.now(), ::shownCount).takeIf { it.size >= 12 }
            ?: Calm.stories(left, interests, Clock.now(), ::shownCount, window = 7 * 86_400L)
        if (debuggable) storyCounts("more stories", out.size)
        return out
    }

    /** Debug builds: where today's stories went (how many, how many recent, opened, dismissed, hidden, the phone's copy's age). */
    private fun storyCounts(what: String, showing: Int) {
        val all = allStories.value; val now = Clock.now()
        val file = java.io.File(CosignApp.instance.cacheDir, "topic-feeds.json")
        android.util.Log.i("CosignStories", "$what: showing $showing of ${all.size}; within 48 h ${all.count { now - it.publishedAt < Calm.WINDOW }}; " +
            "opened ${all.count(::storyOpened)}; dismissed ${all.count(::storyDismissed)}; hidden sites ${all.count { interests.hidden("hiddenSources", it.sourceId) }}; " +
            "newest ${all.minOfOrNull { (now - it.publishedAt) / 3600 } ?: "-"} h old; " +
            "copy ${if (file.exists()) "${(System.currentTimeMillis() - file.lastModified()) / 60_000} min old" else "none"}")
    }
    /** Link previews (title, picture, summary from the page's own tags), by link. */
    val previews = MutableStateFlow<Map<String, Preview>>(emptyMap())

    /** What the last ranking had to go on, kept so it can be done again as what you like changes. */
    private class Inputs(val pool: List<Event>, val standing: Map<String, Standing>, val fans: Map<String, Set<String>>, val follows: Set<String>, val perAuthor: Map<String, Int>)
    @Volatile private var inputs: Inputs? = null
    /** How far down the feed you've looked, in posts: what's above it stays put when things change. */
    @Volatile var lookedAt = 0

    /**
     * Load the home feed: posts from people on your topics from the last two days, ranked as the mock
     * does, and today's stories for the box. Last time's shows at once; this never holds it up.
     * [force] (you pulled down): only things you haven't seen, not even what's on screen now.
     */
    fun loadFeed(force: Boolean = false, top: Boolean = false) {
        val f = feed.value
        // Coming back to Marble (from the browser, say) changes nothing on screen: not the box, not the posts,
        // not their order. Only a refresh you ask for (pulling down) starts over.
        if (f.loading || (!force && !top && f.posts.isNotEmpty() && System.currentTimeMillis() - f.at < 30 * 60_000)) {
            // No box at all (all of last time's had been seen): fill it; that changes nothing you're looking at.
            if (!f.loading && f.stories.isEmpty()) scope.launch { refillStories() }
            // The full list for More stories, kept up to date all the same (the box stays as it is).
            if (!f.loading && !TopicFeeds.fresh(CosignApp.instance)) scope.launch { allStories.value = try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { allStories.value } }
            return
        }
        feed.update { it.copy(loading = true, failed = false) }
        scope.launch { afterPaint(); knowMutes(); knowSaved(); knowInterests() }
        val onScreen = if (force) (f.posts.take(lookedAt + 2).map { it.e.id } + f.stories.map { "t:" + it.id }).toSet() else emptySet()
        if (force) lookedAt = 0
        scope.launch {
            if (restored.await()) afterPaint()
            // The box changes only when you ask (or when there's none yet); otherwise just the full list for More stories.
            // The box changes when you pull, when there's none, or (once, while opening) when it was put together
            // from stories hours old; otherwise only the full list for More stories is brought up to date.
            launch {
                if (force || feed.value.stories.isEmpty() || (!Sources.resumed && !boxSettled && !TopicFeeds.fresh(CosignApp.instance))) loadStories(onScreen)
                else allStories.value = try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { allStories.value }
            }
            // Nothing to show yet (a new phone, say): a few good posts from quick asks first, in a second or two.
            if (f.posts.isEmpty() || force) launch {
                val quick = try { quickPosts(onScreen) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
                if (quick.isNotEmpty() && feed.value.loading) { feed.update { if (it.posts.isEmpty() || force) it.copy(posts = quick) else it }; Startup.mark("feed quick ${quick.size}"); loadPreviews(quick.map { it.e }) }
            }
            val posts = try { buildPosts(onScreen) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            if (posts == null) return@launch feed.update { it.copy(loading = false, failed = it.posts.isEmpty() && it.stories.isEmpty()) }
            // Pulled: what you just pulled for (the quick posts at the top, if any, stay there). On its own (every
            // 30 minutes, or back after a while): everything stays as it is, the new posts come in below it.
            feed.update { cur ->
                val keep = if (force) (if (cur.posts.none { p -> posts.any { it.e.id == p.e.id } }) emptyList() else cur.posts.take(lookedAt + 3)) else cur.posts
                val fresh = posts.filter { p -> keep.none { it.e.id == p.e.id } }
                // Back after a while: the new ones first, unless you've already started down the list (then below, as ever).
                val first = top && lookedAt <= 1
                cur.copy(posts = if (first) fresh + keep else keep + (if (force) fresh.take((20 - keep.size).coerceAtLeast(0)) else fresh), loading = false, at = System.currentTimeMillis())
            }
            if (posts.isNotEmpty()) Startup.mark("feed posts ${posts.size}")
            saveFeed()
            loadPreviews(posts.map { it.e })
        }
    }

    /**
     * Back after a while (5 minutes or more: on the main page again, at the top). What you've seen goes, at once;
     * what's new since you last looked comes first (fetched if the feed is [fetchAfter] old or more, ten minutes in
     * use). A short hop never comes here, so coming back from an article changes nothing.
     */
    fun returned(fetchAfter: Long = 10 * 60_000L) {
        if (feed.value.loading) return
        lookedAt = 0
        feed.update { it.copy(posts = it.posts.filter { p -> unseen(p.e.id) }) }
        if (System.currentTimeMillis() - feed.value.at >= fetchAfter) loadFeed(top = true) else loadFeed()
    }

    /** The box again, from the stories already on the phone (no waiting on the site). */
    private suspend fun refillStories() {
        val all = allStories.value.ifEmpty { TopicFeeds.load(CosignApp.instance, cachedOnly = true).also { allStories.value = it } }
        // Only into an empty box: a box on screen is never swapped for another.
        val box = Calm.box(all, interests, Clock.now(), ::storyOpened, ::storyDismissed, ::shownCount)
        feed.update { if (it.stories.isNotEmpty()) it else it.copy(stories = box) }
    }

    /** The box: today's stories that fit you best, one per topic. */
    /** The box was put together from the site's latest stories since Marble started. */
    @Volatile private var boxSettled = false

    private suspend fun loadStories(skip: Set<String>) {
        boxSettled = true
        val all = try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { TopicFeeds.load(CosignApp.instance, cachedOnly = true) }
        allStories.value = all
        // Pulled: not the ones that were just on screen, unless there's nothing else.
        val box = Calm.box(all.filter { "t:" + it.id !in skip }, interests, Clock.now(), ::storyOpened, ::storyDismissed, ::shownCount)
            .ifEmpty { Calm.box(all, interests, Clock.now(), ::storyOpened, ::storyDismissed, ::shownCount) }
        feed.update { it.copy(stories = box) }
        if (debuggable) storyCounts("box from the site", box.size)
        if (box.isNotEmpty()) Startup.mark("feed stories ${box.size}")
        saveFeed()
    }

    /**
     * The first few posts, from quick asks: what's hot on the search relays over the last two days and in
     * your three strongest topics, with trust from who you follow (asked at the same time), and follower counts for a few others.
     * About two seconds on a good connection, a few at most on a slow one; the full ranking comes after and fills in below.
     */
    private suspend fun quickPosts(skip: Set<String>): List<Pick> = kotlinx.coroutines.coroutineScope {
        val me = reader ?: return@coroutineScope emptyList()
        val now = Clock.now()
        val since = now - Calm.WINDOW
        val weights = topicWeights()
        // Who you follow (from the relays that answer fastest), then what they've posted: the first thing to trust.
        val followsAsk = async {
            follows.value ?: kotlinx.coroutines.withTimeoutOrNull(3000) {
                followsIn(Fetch.query(listOf("wss://purplepag.es", "wss://relay.primal.net", "wss://relay.damus.io"), listOf(mapOf("kinds" to listOf(3), "authors" to listOf(me), "limit" to 1)), 3000, graceMs = 200).maxByOrNull { it.createdAt })
            } ?: emptySet()
        }
        val filters = listOf(mapOf<String, Any>("kinds" to Calm.POOL_KINDS, "search" to "sort:hot", "since" to since, "limit" to 100)) +
            weights.keys.take(3).mapNotNull { t -> TopicGuess.queries(t).firstOrNull()?.let { q -> mapOf<String, Any>("kinds" to Calm.POOL_KINDS, "search" to "$q sort:hot", "since" to since, "limit" to 50) } }
        val hot = async { Fetch.query(searchRelays.take(2), filters, 3000, graceMs = 600) }
        // What the people you follow posted lately: the likeliest to pass, asked as soon as their list is in.
        val theirs = async {
            val f = followsAsk.await()
            if (f.isEmpty()) emptyList() else Fetch.query(bigRelays.take(3), listOf(mapOf("kinds" to Calm.POOL_KINDS, "authors" to f.take(500).toList(), "since" to since, "limit" to 200)), 2000, graceMs = 400)
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        fun step(what: String) { if (debuggable) android.util.Log.i("CosignStartup", "quick $what at +${android.os.SystemClock.uptimeMillis() - t0} ms") }
        val follows = followsAsk.await().also { step("follows ${it.size}") }
        // What's hot, people you follow first; a hundred and fifty at most (checking them all is the slow part on a phone).
        val got = (theirs.await().also { step("theirs ${it.size}") } + hot.await().also { step("hot ${it.size}") })
            .filter { it.createdAt in since..now + 600 && unseen(it.id) && it.id !in skip && !interests.hidden("hiddenPeople", it.pubkey) }.distinctBy { it.id }
            .sortedByDescending { it.pubkey in follows }.take(150)
        val liked = liked.value + readerLiked
        val names = People.known.value
        val perAuthor = got.groupingBy { it.pubkey }.eachCount()
        val passes = got.filter { e -> Calm.drop(e, me, liked, perAuthor[e.pubkey] ?: 0, names[e.pubkey]?.name, true, Standing(true, 0, 0)) == null &&
            TopicGuess.hits(e, strict = true).any { (weights[it.topic] ?: 0.0) > 0 } && forDiscover(e) }
        val strangers = passes.map { it.pubkey }.distinct().filter { it !in follows }.take(12)
        val followers = ConcurrentHashMap<String, Long>()
        if (strangers.isNotEmpty()) kotlinx.coroutines.withTimeoutOrNull(1000) { Fetch.counts(SearchRelays.COUNTS[0], strangers.associateWith { mapOf("kinds" to listOf(3), "#p" to listOf(it)) }, 1000, inFlight = 12) { pk, c -> followers[pk] = c } }
        step("counts ${followers.size}")
        val picks = passes.mapNotNull { e -> Calm.score(e, weights, Standing(e.pubkey in follows, 0, followers[e.pubkey] ?: 0), emptySet(), follows, false, now, interests.digestWeight()) }
        if (debuggable) Startup.mark("feed quick asked ${got.size}, passed ${passes.size}, follows ${follows.size}, counted ${followers.size}, picked ${picks.size}")
        Calm.diversify(picks, max = 6).also { remember(it.map { p -> p.e }); People.want(it.map { p -> p.e.pubkey }) }
    }

    /** The relays a topic's posts are looked for on: those that search, and the big ones for its hashtags. */
    private val searchRelays = listOf("wss://syb.lol", "wss://relay.ditto.pub", "wss://search.nos.today")
    private val bigRelays = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://syb.lol", "wss://relay.ditto.pub")

    /** Your topics, 0..1, the strongest ten: what posts are matched to. Nothing learned yet: all of them alike. */
    private fun topicWeights(): Map<String, Double> {
        val w = TopicGuess.topics.associateWith { interests.of(it) }.filterValues { it > 0.05 }.entries.sortedByDescending { it.value }.take(10)
        val max = w.firstOrNull()?.value ?: return emptyMap()
        return w.associate { it.key to it.value / max }
    }

    private suspend fun buildPosts(skip: Set<String>): List<Pick> = kotlinx.coroutines.coroutineScope {
        val me = reader
        val now = Clock.now()
        val since = now - Calm.WINDOW
        val weights = topicWeights()
        // What you like stays out of release logs.
        if (debuggable) Startup.mark("feed weights " + weights.entries.joinToString { "${it.key} %.2f".format(it.value) })
        knowFollows()
        val follows = follows.value ?: emptySet()
        // The pool: each topic's searches and hashtags, four topics at a time.
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val found = weights.keys.map { t ->
            async {
                gate.withPermit {
                    val searches = TopicGuess.queries(t).flatMap { q -> listOf(" sort:top", " sort:hot", "").map { s -> mapOf("kinds" to Calm.POOL_KINDS, "search" to q + s, "since" to since, "limit" to 100) } }
                    val a = async { Fetch.query(searchRelays, searches, 10_000) }
                    val b = async { Fetch.query(bigRelays, mapOf("kinds" to Calm.POOL_KINDS, "#t" to TopicGuess.tags(t).toList(), "since" to since, "limit" to 300), 10_000) }
                    a.await() + b.await()
                }
            }
        }.awaitAll().flatten().filter { it.createdAt in since..now + 600 && unseen(it.id) && it.id !in skip && !interests.hidden("hiddenPeople", it.pubkey) }.distinctBy { it.id }
        Startup.mark("feed pool ${found.size}")
        val perAuthor = found.groupingBy { it.pubkey }.eachCount()
        People.want(found.map { it.pubkey }.distinct())
        val fof = friendsOfFriends()
        Startup.mark("feed trust ${fof.size}")
        // Follower counts only for strangers whose posts pass everything else.
        val names = People.known.value
        val liked = liked.value + readerLiked
        // Everything but trust first (the language check, the slowest, last); trust once the strangers' follower counts are in.
        val passes = found.filter { e -> Calm.drop(e, me, liked, perAuthor[e.pubkey] ?: 0, names[e.pubkey]?.name, true, Standing(true, 0, 0)) == null &&
            TopicGuess.hits(e, strict = true).any { (weights[it.topic] ?: 0.0) > 0 } && forDiscover(e) }
        Startup.mark("feed passes ${passes.size}")
        val strangers = passes.map { it.pubkey }.distinct().filter { it !in follows && (fof[it] ?: 0) < 3 }.take(150)
        val followers = ConcurrentHashMap<String, Long>()
        kotlinx.coroutines.withTimeoutOrNull(6000) {
            SearchRelays.COUNTS.map { url -> async { Fetch.counts(url, strangers.associateWith { mapOf("kinds" to listOf(3), "#p" to listOf(it)) }, 6000, inFlight = 15) { pk, c -> followers.merge(pk, c, ::maxOf) } } }.awaitAll()
        }
        Startup.mark("feed followers ${followers.size}")
        val standing = passes.map { it.pubkey }.distinct().associateWith { Standing(it in follows, fof[it] ?: 0, followers[it] ?: 0) }
        // First without likes; then likes and shares for the best few hundred, and again.
        fun rank(fans: Map<String, Set<String>>) = passes.mapNotNull { e ->
            val s = standing[e.pubkey] ?: return@mapNotNull null
            if (Calm.drop(e, me, liked, perAuthor[e.pubkey] ?: 0, names[e.pubkey]?.name, true, s) != null) return@mapNotNull null
            Calm.score(e, weights, s, fans[e.id] ?: emptySet(), follows, false, now, interests.digestWeight())
        }.sortedByDescending { it.score }
        val first = rank(emptyMap()).take(200)
        Startup.mark("feed first pass ${first.size}")
        val fans = HashMap<String, MutableSet<String>>()
        first.map { it.e.id }.chunked(150).map { ids -> async { Fetch.query(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol", "wss://syb.lol"), mapOf("kinds" to listOf(7, 6, 9735), "#e" to ids, "limit" to 5000), 8000) } }
            .awaitAll().flatten().forEach { r ->
                val target = r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) ?: return@forEach
                val who = if (r.kind == 9735) (try { (Json.parse(r.tags.firstOrNull { it.size > 1 && it[0] == "description" }?.get(1) ?: "") as? Map<*, *>)?.get("pubkey") as? String } catch (_: Exception) { null }) ?: return@forEach else r.pubkey
                fans.getOrPut(target) { HashSet() } += who
            }
        Startup.mark("feed fans ${fans.size}")
        inputs = Inputs(passes, standing, fans, follows, perAuthor)
        val picks = Calm.collapse(Calm.diversify(rank(fans)), ::nameOf)
        remember(picks.map { it.e })
        People.want(picks.map { it.e.pubkey })
        Startup.mark("feed ranked ${picks.size}")
        picks
    }
    private fun nameOf(pk: String) = People.known.value[pk]?.shown ?: shortNpub(pk)

    /** Link previews for these posts' links, in the background, kept on this phone. */
    private fun loadPreviews(posts: List<Event>) = scope.launch {
        afterPaint()
        val links = posts.mapNotNull { e -> Calm.link(e)?.takeIf { !Regex("github\\.com/[^/]+/[^/]+/(pull|commit)").containsMatchIn(it) && it !in previews.value } }.distinct()
        if (links.isEmpty()) return@launch
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        links.map { url -> async { gate.withPermit { Preview.fetch(url) }?.let { p -> previews.update { it + (url to p) } } } }.awaitAll()
        Preview.save(CosignApp.instance, previews.value)
    }

    // The last feed, on disk, so opening shows it at once (filtered again: you may have seen or hidden some since).
    private val feedFile = File(context.cacheDir, "home-" + (phone.ui.value.member?.group?.groupX?.take(16) ?: phone.slot) + ".json")
    /** Whether last time's feed went on screen from disk. */
    private val restored = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /** Called once the whole object is set up: the filters below need it. */
    private fun restoreFeed() {
        // Once a start, whatever the feed does: what your other phones have seen (never before the first screen).
        scope.launch {
            restored.await(); afterPaint(); pullSeen(); pullDeviceNames(); pullLater()
            // Marbles kept on this phone before they were synced (0.12.16): taken in once, then the account's.
            kept.takeIn(CosignApp.instance.getSharedPreferences("marbles", Context.MODE_PRIVATE).getStringSet("collected", emptySet())!!)
            pullKept()
        }
        scope.launch {
            try {
                previews.value = Preview.load(CosignApp.instance)
                val m = try { Json.parse(feedFile.readText()) as? Map<*, *> } catch (_: Exception) { null } ?: return@launch
                val posts = (m["posts"] as? List<*>)?.mapNotNull { x ->
                    val p = x as? Map<*, *> ?: return@mapNotNull null
                    val e = Event.fromMap(p["event"]) ?: return@mapNotNull null
                    if ((!Sources.resumed && !unseen(e.id)) || !forDiscover(e) || interests.hidden("hiddenPeople", e.pubkey)) return@mapNotNull null
                    val reason = p["reason"] as? String ?: ""
                    // A fresh start checks last time's page against today's rules (a newsletter, a heavy post from a stranger).
                    if (!Sources.resumed && !reason.contains("someone you follow") && Curate.heavy(Calm.text(e))) return@mapNotNull null
                    Pick(e, p["topic"] as? String ?: return@mapNotNull null, 0.0, reason, (p["likes"] as? Long)?.toInt() ?: 0, (p["more"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList())
                } ?: emptyList()
                // The box as it was only when coming back (Android recreated Marble); a fresh start puts a new one together.
                val stories = Calm.restoredBox((m["stories"] as? List<*>)?.mapNotNull { TopicCard.fromMap(it) } ?: emptyList(), Sources.resumed)
                if (stories.isEmpty()) launch { refillStories() }
                if (posts.isEmpty() && stories.isEmpty()) return@launch
                remember(posts.map { it.e })
                feed.update { if (it.posts.isNotEmpty()) it else it.copy(posts = posts, stories = stories.ifEmpty { it.stories }, at = m["at"] as? Long ?: 0) }
                restored.complete(true)
                People.want(posts.map { it.e.pubkey })
                Startup.mark("feed from cache ${posts.size}")
            } finally { restored.complete(false) }
        }
    }

    private fun saveFeed() {
        val f = feed.value
        try { feedFile.writeText(Json.stringify(mapOf("at" to f.at,
            "posts" to f.posts.map { mapOf("event" to it.e.toMap(), "topic" to it.topic, "reason" to it.reason, "likes" to it.likes.toLong(), "more" to it.more) },
            "stories" to f.stories.map { it.toMap() }))) } catch (_: Exception) {}
    }

    // --- What you like: topics, learned on this phone ------------------------------------

    /**
     * In the background, never on the way to the first screen: your account's copy of what you like
     * (from another phone, say), or else, once, what you've liked and shared in the last 90 days.
     * Discover's order below where you are gets better as this lands.
     */
    private suspend fun knowInterests() {
        val me = reader ?: return
        if (interestsKnown) return
        interestsKnown = true
        // Looking as another account: learn its topics from its likes, and keep nothing.
        if (Sources.viewAs != null) { seedInterests(me); rerank(); return }
        if (pullInterests(me)) { catchUpLikes(me); rerank() }
        else if (!interests.seeded) { seedInterests(me); rerank() }
        else catchUpLikes(me)
        if (interests.at > prefs.getLong("interestsSent", 0)) interestsChanged()
    }

    /** Likes made since the profile last counted them, here or in any other app: learned, then counted. */
    private suspend fun catchUpLikes(me: String) {
        val since = (interests.likesTo ?: interests.at).takeIf { it > 0 } ?: return
        val now = Clock.now()
        val likes = Fetch.query(readRelays().take(6), mapOf("kinds" to listOf(7), "authors" to listOf(me), "since" to since + 1, "limit" to 200), 8000)
        // Nothing new: nothing to change (or to send), and the same window is looked at again next time.
        if (likes.isEmpty()) { likesCaughtUp = true; return }
        val votes = HashMap<String, Double>()
        val ids = likes.filter { it.content != "-" }.mapNotNull { r -> r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) }.distinct()
        for (chunk in ids.chunked(150)) Fetch.query(readRelays().take(6), mapOf("ids" to chunk), 8000)
            .forEach { e -> TopicGuess.of(e).take(2).forEachIndexed { i, t -> votes[t] = (votes[t] ?: 0.0) + 1.0 / (i + 1) } }
        interests.seed(votes, likesTo = now)
        likesCaughtUp = true
    }
    /** Likes are counted up to now: a like made here can say so. */
    @Volatile private var likesCaughtUp = false

    /** What the account liked in the last 90 days (seen when learning): already liked, so not in the feed. */
    @Volatile private var readerLiked: Set<String> = emptySet()

    private suspend fun seedInterests(me: String) {
        val now = Clock.now()
        val mine = Fetch.query(readRelays().take(6), mapOf("kinds" to listOf(7, 6, 1), "authors" to listOf(me), "since" to now - 90 * 86_400L, "limit" to 400), 8000)
        val votes = HashMap<String, Double>()
        fun vote(e: Event, by: Double) = TopicGuess.of(e).take(2).forEachIndexed { i, t -> votes[t] = (votes[t] ?: 0.0) + by / (i + 1) }
        mine.filter { it.kind == 1 }.forEach { vote(it, 0.3) }
        mine.filter { it.kind == 6 }.forEach { r -> reposted(r)?.let { vote(it, 2.0) } }
        // The emojis you react with elsewhere, to offer first on a long press.
        if (emojiUse.isEmpty()) mine.filter { it.kind == 7 && it.content.isNotBlank() && it.content != "+" && it.content != "-" && !it.content.startsWith(":") && it.content.length <= 8 }
            .forEach { emojiUse[it.content] = (emojiUse[it.content] ?: 0) + 1 }
        readerLiked = mine.filter { it.kind == 7 }.mapNotNull { r -> r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) }.toSet()
        val likedIds = mine.filter { it.kind == 7 && it.content != "-" }.mapNotNull { r -> r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) }.distinct().take(300)
        for (chunk in likedIds.chunked(150)) Fetch.query(readRelays().take(6), mapOf("ids" to chunk), 8000).forEach { vote(it, 1.0) }
        interests.seed(votes, likesTo = now)
        likesCaughtUp = true
    }

    /** Rank again from what was gathered (what you like is better known now); what you're reading stays where it is. */
    private fun rerank() {
        val i = inputs ?: return
        val weights = topicWeights()
        val now = Clock.now()
        val ranked = i.pool.mapNotNull { e ->
            val st = i.standing[e.pubkey] ?: return@mapNotNull null
            if (!unseen(e.id) || interests.hidden("hiddenPeople", e.pubkey)) return@mapNotNull null
            Calm.score(e, weights, st, i.fans[e.id] ?: emptySet(), i.follows, false, now, interests.digestWeight())
        }
        val picks = Calm.collapse(Calm.diversify(ranked), ::nameOf)
        feed.update { cur ->
            val keep = cur.posts.take(lookedAt + 3)
            cur.copy(posts = keep + picks.filter { p -> keep.none { it.e.id == p.e.id } }.take((20 - keep.size).coerceAtLeast(0)))
        }
    }

    // Your account's copy: NIP-78 app data, NIP-44 encrypted to yourself, so only you can read it and
    // your other phones start from it. Saved a minute after the last change, in the background.
    /** Shared with discover.syb.lol: one profile for both (spec: interest-profile.md in the notes). */
    private val interestsTag = "discover/interests"
    @Volatile private var interestsKnown = false
    private var sending: Job? = null

    private fun interestsChanged() {
        if (Sources.viewAs != null) return
        sending?.cancel()
        sending = scope.launch { delay(60_000); sendInterests() }
    }

    @Volatile private var newerProfile = false

    private suspend fun sendInterests() {
        val me = me ?: return
        if (newerProfile) return
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
        // A later version than this app knows: leave it alone, and don't write over it.
        if ((try { org.json.JSONObject(json).optInt("v") } catch (_: Exception) { 0 }) > 1) { newerProfile = true; return true }
        if (interests.adopt(json)) { followedTopics.value = interests.followed.toSet(); prefs.edit().putLong("interestsSent", interests.at).apply() }
        return true
    }

    // What you've seen, on your account too (SeenSync): read once a start, after the first screen and never
    // before it; written a couple of minutes after the last thing you saw, merged with what's there first.
    private var seenSending: Job? = null
    @Volatile private var seenPulled = false

    private fun seenChanged() {
        if (Sources.viewAs != null) return
        seenSending?.cancel()
        seenSending = scope.launch { delay(120_000); sendSeen() }
    }

    private suspend fun sendSeen() {
        val me = me ?: return
        pullSeen(again = true)
        val now = Clock.now()
        val text = toSelf(SeenSync.encode(seen.since(now - SeenSync.WINDOW).filter { !it.first.startsWith("t") }, now), encrypt = true) ?: return
        // NIP-40: relays may drop it two weeks after the last write, so it doesn't outlive your use of Marble (each write moves it on).
        val tags = listOf(listOf("d", SeenSync.TAG), listOf("expiration", (now + SeenSync.EXPIRES).toString()))
        val signed = try { phone.signOwn(Event(me, now, 30078, tags, text).withId()) } catch (_: Exception) { return }
        val to = if (Sources.localOnly) Sources.extra.value else writeRelays()
        val n = Fetch.publish(to, signed)
        if (debuggable) android.util.Log.i("CosignSync", "seen sent: ${text.length} chars to $n of ${to.size} relays")
    }

    // --- Later: what you saved from the search box ------------------------------------------------
    //
    // Your account's copy (kind 30078, d=marble/later, NIP-44 to yourself), read once a start and written half a
    // minute after the last change (read again and merged first, so another device's saves are never lost).

    val later = Later(prefs).also { it.changed = { laterChanged() } }
    private var laterSending: kotlinx.coroutines.Job? = null

    private fun laterChanged() {
        if (Sources.viewAs != null) return
        laterSending?.cancel()
        laterSending = scope.launch { delay(30_000); sendLater() }
    }

    /**
     * The page on screen in a tab Marble opened, saved with its Save button: by the title Marble knows for it,
     * else by its site until the page itself says (fetched here, in the background).
     */
    fun savePage(url: String): LaterItem? {
        val host = android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
        val known = (later.pages.value.firstOrNull { it.url == url }?.title ?: allStories.value.firstOrNull { it.url == url }?.title)
            ?.takeIf { it.isNotBlank() && it != host && it != url }
        val item = later.savePage(url, known ?: host) ?: return null
        if (item.text == host) scope.launch { Preview.fetch(url)?.title?.let { later.retitle(item.id, host, it) } }
        return item
    }

    /** A page opened from search: its title for Later, from the page itself, in the background. */
    fun titlePage(url: String) = scope.launch { Preview.fetch(url)?.title?.let { later.titled(url, it) } }

    @Volatile private var laterPulledAt = 0L
    /** What you saved on your other devices: coming back to Marble or opening Later, at most once a minute. */
    fun refreshLater() {
        if (System.currentTimeMillis() - laterPulledAt < 60_000L) return
        scope.launch { pullLater() }
    }

    /** [sending]: just before a write, which follows anyway (so no second one is scheduled). */
    private suspend fun pullLater(sending: Boolean = false) {
        laterPulledAt = System.currentTimeMillis()
        val me = me ?: return
        if (Sources.viewAs != null) return
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to listOf(Later.TAG), "limit" to 4), 6000)
            .filter { it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt }
        // This phone has things the account's copy hasn't (or there's no copy yet): send them.
        val ahead = if (got == null) later.items.value.isNotEmpty() else later.merge(toSelf(got.content, encrypt = false) ?: return)
        if (ahead && !sending) laterChanged()
    }

    private suspend fun sendLater() {
        val me = me ?: return
        pullLater(sending = true)
        val text = toSelf(later.json(), encrypt = true) ?: return Unit.also { if (debuggable) android.util.Log.i("CosignSync", "later not sent: couldn't encrypt") }
        val signed = try { phone.signOwn(Event(me, Clock.now(), 30078, listOf(listOf("d", Later.TAG)), text).withId()) } catch (e: Exception) {
            if (debuggable) android.util.Log.i("CosignSync", "later not sent: ${e.javaClass.simpleName}"); return
        }
        val to = if (Sources.localOnly) Sources.extra.value else writeRelays()
        val n = Fetch.publish(to, signed)
        if (debuggable) android.util.Log.i("CosignSync", "later sent: ${later.items.value.size} items to $n of ${to.size} relays")
    }

    // --- Your marbles: the ones you kept -------------------------------------------------------
    //
    // A year to an event on your account (kind 30078, d=marble/marbles/<year>, NIP-44 to yourself), read once a
    // start and written half a minute after a change (read and merged first, as Later is).

    val kept = Kept(prefs).also { it.changed = { y -> keptChanged(y) } }
    private val keptDirty = java.util.Collections.synchronizedSet(HashSet<String>())
    private var keptSending: kotlinx.coroutines.Job? = null

    private fun keptChanged(year: String) {
        if (Sources.viewAs != null) return
        keptDirty += year
        keptSending?.cancel()
        keptSending = scope.launch { delay(30_000); sendKept() }
    }

    /**
     * Today's marble, kept: its award from the issuer (POST /collect {"id","date"}, NIP-98 signed by you), kept with it. Quietly;
     * tried again on later opens the same UTC day if the issuer isn't there or says no.
     */
    fun claimToday() {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val id = "marble-$today"
        if (kept.all.value[id]?.let { it.award == null } != true || claiming) return
        val issuer = cc.aaro.cosign.ui.Daily.issuer ?: return
        claiming = true
        scope.launch {
            try {
                val me = me ?: return@launch
                val url = "$issuer/collect"
                val body = org.json.JSONObject().put("id", id).put("date", today).toString()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
                val auth = phone.signOwn(Event(me, Clock.now(), 27235, listOf(listOf("u", url), listOf("method", "POST"), listOf("payload", hash)), "").withId())
                val req = okhttp3.Request.Builder().url(url).header("Authorization", "Nostr " + java.util.Base64.getEncoder().encodeToString(auth.toJson().toByteArray()))
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body)).build()
                val award = claimHttp.newCall(req).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (debuggable) android.util.Log.i("CosignMarble", "collect: ${r.code}")
                    if (r.isSuccessful) org.json.JSONObject(text).optJSONObject("award") else null
                } ?: return@launch
                // Only an award for this day, to you, of kind 8.
                val ev = Event.fromMap(Json.parse(award.toString())) ?: return@launch
                if (ev.kind != 8 || ev.tags.none { it.size > 1 && it[0] == "p" && it[1] == me } || !ev.verify()) return@launch
                kept.awarded(id, award.toString())
            } catch (e: Exception) {
                if (debuggable) android.util.Log.i("CosignMarble", "collect failed: ${e.javaClass.simpleName}")
            } finally { claiming = false }
        }
    }
    @Volatile private var claiming = false

    // --- A marble on your profile: one at a time, only when you say ------------------------------
    //
    // NIP-58: your profile_badges (kind 30008) lists the awards other apps show on your profile, as a/e pairs.
    // A marble's award goes in only when you tap "Show on profile" for that marble; nothing is added by itself.

    /** The award ids on your profile_badges, as last read. */
    val onProfile = MutableStateFlow<Set<String>>(emptySet())

    private suspend fun profileBadges(pk: String): Event? =
        Fetch.query((writeRelays() + readRelays() + Sources.PROFILES.take(2)).distinct().take(8),
            mapOf("kinds" to listOf(30008), "authors" to listOf(pk), "#d" to listOf("profile_badges"), "limit" to 4), 6000)
            .filter { it.pubkey == pk && it.verify() }.maxByOrNull { it.createdAt }

    fun readOnProfile() = scope.launch {
        val me = me ?: return@launch
        onProfile.value = profileBadges(me)?.tags?.filter { it.size > 1 && it[0] == "e" }?.map { it[1] }?.toSet() ?: emptySet()
    }

    /** Show this marble's award on your profile, or stop: your profile_badges, read fresh, with its pair added or taken out. */
    fun showOnProfile(awardJson: String, on: Boolean) {
        val me = me ?: return
        val award = Event.fromMap(Json.parse(awardJson)) ?: return
        val a = award.tags.firstOrNull { it.size > 1 && it[0] == "a" }?.get(1) ?: return
        onProfile.update { if (on) it + award.id else it - award.id }
        scope.launch {
            val cur = profileBadges(me)
            val tags = (cur?.tags ?: listOf(listOf("d", "profile_badges"))).toMutableList()
            // Pairs: an "a" then its "e". Take this award's pair out; put it back at the end if showing.
            val out = ArrayList<List<String>>()
            var i = 0
            while (i < tags.size) {
                val t = tags[i]; val next = tags.getOrNull(i + 1)
                if (t.size > 1 && t[0] == "a" && next != null && next.size > 1 && next[0] == "e" && next[1] == award.id) { i += 2; continue }
                out += t; i++
            }
            if (on) { out += listOf("a", a); out += listOf("e", award.id) }
            if (actEvent(Event(me, Clock.now(), 30008, out, cur?.content ?: "").withId()) == null) onProfile.update { if (on) it - award.id else it + award.id }
        }
    }

    /** The marbles someone shows on their profile: their profile_badges' awards from Marble's issuer, as (name, picture). */
    suspend fun marblesShownBy(pk: String): List<Pair<String, String?>> {
        val list = profileBadges(pk) ?: return emptyList()
        val issuer = cc.aaro.cosign.ui.Daily.issuerKey() ?: kept.all.value.values.firstNotNullOfOrNull { k ->
            k.award?.let { Event.fromMap(Json.parse(it)) }?.tags?.firstOrNull { it.size > 1 && it[0] == "a" }?.get(1)?.split(':')?.getOrNull(1)
        } ?: return emptyList()
        val ds = list.tags.filter { it.size > 1 && it[0] == "a" && it[1].startsWith("30009:$issuer:") }.map { it[1].substringAfter("30009:$issuer:") }
        if (ds.isEmpty()) return emptyList()
        val defs = Fetch.query((Sources.PROFILES.take(3) + readRelays().take(3)).distinct(), mapOf("kinds" to listOf(30009), "authors" to listOf(issuer), "#d" to ds), 6000)
            .filter { it.pubkey == issuer && it.verify() }.associateBy { e -> e.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "" }
        return ds.mapNotNull { d -> defs[d]?.let { e ->
            val tag = { k: String -> e.tags.firstOrNull { it.size > 1 && it[0] == k }?.get(1) }
            (tag("name") ?: "Marble") to (tag("thumb") ?: tag("image"))
        } }
    }
    private val claimHttp by lazy { Fetch.http.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }

    private suspend fun pullKept(sending: Boolean = false) {
        val me = me ?: return
        if (Sources.viewAs != null) return
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val years = (2026..java.time.LocalDate.now(java.time.ZoneOffset.UTC).year).map { it.toString() }
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to years.map { "${Kept.TAG}/$it" }, "limit" to 40), 6000)
            .filter { it.pubkey == me && it.verify() }
            .groupBy { e -> e.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1)?.removePrefix("${Kept.TAG}/") ?: "" }
            .mapValues { (_, l) -> l.maxBy { it.createdAt } }
        for (y in (kept.years() + got.keys).filter { it in years }) {
            val e = got[y]
            // This phone has ones that year's copy hasn't (or there's no copy yet): send that year.
            val ahead = if (e == null) true else kept.merge(toSelf(e.content, encrypt = false) ?: continue, y)
            if (ahead) keptDirty += y
        }
        if (!sending && keptDirty.isNotEmpty()) { keptSending?.cancel(); keptSending = scope.launch { delay(30_000); sendKept() } }
    }

    private suspend fun sendKept() {
        val me = me ?: return
        pullKept(sending = true)
        for (y in keptDirty.toList()) {
            val text = toSelf(kept.json(y), encrypt = true) ?: return
            val signed = try { phone.signOwn(Event(me, Clock.now(), 30078, listOf(listOf("d", "${Kept.TAG}/$y")), text).withId()) } catch (_: Exception) { return }
            if (Fetch.publish(if (Sources.localOnly) Sources.extra.value else writeRelays(), signed) > 0) keptDirty -= y
        }
    }

    // --- What you call your devices ------------------------------------------------------------
    //
    // Names you give your devices, by each one's key, on your account (NIP-78, encrypted to you) so every device
    // shows the same ones. Cosign's own list keeps the name each device joined with (often a model number).

    data class DeviceName(val name: String, val at: Long)
    val deviceNames = MutableStateFlow(namesFrom(prefs.getString("deviceNames", null)))

    /** Call a device [name] on every device; [key]: its key ([deviceKey]). */
    fun nameDevice(key: String, name: String) {
        val n = name.trim().take(40)
        if (n.isEmpty() || deviceNames.value[key]?.name == n) return
        deviceNames.update { it + (key to DeviceName(n, Clock.now())) }
        prefs.edit().putString("deviceNames", namesJson(deviceNames.value)).apply()
        if (Sources.viewAs == null) scope.launch { sendDeviceNames() }
    }

    private suspend fun pullDeviceNames() {
        val me = me ?: return
        if (Sources.viewAs != null) return
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to listOf(DEVICE_NAMES), "limit" to 4), 6000)
            .filter { it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt }
        val theirs = got?.let { toSelf(it.content, encrypt = false) }?.let(::namesFrom) ?: emptyMap()
        val mine = deviceNames.value
        // Each name as it was last set, here or on another device.
        val merged = (theirs.keys + mine.keys).associateWith { k -> listOfNotNull(theirs[k], mine[k]).maxBy { it.at } }
        deviceNames.value = merged
        prefs.edit().putString("deviceNames", namesJson(merged)).apply()
        // This device's own name, as people know it, if it has none yet.
        val own = phone.ui.value.member?.let { m -> m.group.members.firstOrNull { it.idx == m.idx } }?.let(::deviceKey)
        if (own != null && own !in merged) nameDevice(own, readableDeviceName(CosignApp.instance))
        else if (merged != theirs) sendDeviceNames()
    }

    private suspend fun sendDeviceNames() {
        val me = me ?: return
        val text = toSelf(namesJson(deviceNames.value), encrypt = true) ?: return
        val signed = try { phone.signOwn(Event(me, Clock.now(), 30078, listOf(listOf("d", DEVICE_NAMES)), text).withId()) } catch (_: Exception) { return }
        Fetch.publish(if (Sources.localOnly) Sources.extra.value else writeRelays(), signed)
    }

    // --- Tomorrow's marble: the pipeline's owner sees it a day early, and can veto it -----------
    // On hold, unused: the preview comes as a direct message instead, which any app can read and answer.
    //
    // The pipeline's bot sends its owner tomorrow's marble as app data (kind 30078, d=marble/preview/<date>,
    // p=owner, NIP-44 from the bot), the picture AES-GCM encrypted at an address that says nothing. Only the
    // owner can read one, so nobody else ever sees the card. A veto is the owner's own kind 30078 back.

    data class Tomorrow(val date: String, val name: String, val picture: java.io.File, val vetoed: Boolean)
    val tomorrow = MutableStateFlow<Tomorrow?>(null)
    private val PREVIEW_RELAYS = listOf("wss://syb.lol", "wss://relay.primal.net", "wss://relay.damus.io", "wss://relay.snort.social")

    fun refreshTomorrow() { scope.launch { loadTomorrow() } }

    private suspend fun loadTomorrow() {
        val me = me ?: return
        val bot = cc.aaro.cosign.ui.Daily.previewBot ?: return
        if (Sources.viewAs != null) return
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val got = Fetch.query(PREVIEW_RELAYS, mapOf("kinds" to listOf(30078), "authors" to listOf(bot), "#p" to listOf(me), "limit" to 20), 6000)
            .filter { it.pubkey == bot && it.verify() }
        // The newest per day; only days still to come, and not far off (a stray test can linger on a relay).
        val days = got.groupBy { e -> e.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "" }
            .filterKeys { it.startsWith("marble/preview/") }.mapValues { (_, l) -> l.maxBy { it.createdAt } }
            .mapNotNull { (d, e) -> runCatching { java.time.LocalDate.parse(d.removePrefix("marble/preview/")) }.getOrNull()?.let { it to e } }
            .filter { (date, _) -> date > today && date <= today.plusDays(3) }.sortedBy { it.first }
        for ((date, e) in days) {
            val text = fromBot(bot, e.content) ?: continue
            val o = try { org.json.JSONObject(text) } catch (_: Exception) { continue }
            val url = o.optString("url").takeIf { it.startsWith("https://") } ?: continue
            val key = o.optString("key").takeIf { it.isNotEmpty() } ?: continue
            val sha = o.optString("sha256").lowercase().takeIf { it.length == 64 } ?: continue
            if (o.optString("date") != date.toString()) continue
            val file = java.io.File(CosignApp.instance.cacheDir, "tomorrow-$date.png")
            if (!file.exists() || sha256(file.readBytes()) != sha) {
                val blob = cc.aaro.cosign.ui.Daily.get(url) ?: continue
                val png = try { openPicture(blob, java.util.Base64.getDecoder().decode(key)) } catch (_: Exception) { continue }
                if (sha256(png) != sha) continue
                file.writeBytes(png)
            }
            tomorrow.value = Tomorrow(date.toString(), o.optString("name").trim().take(40).ifEmpty { "Tomorrow's marble" }, file, o.optString("status") == "vetoed")
            return
        }
        tomorrow.value = null
    }

    /** Say no to tomorrow's marble: it never goes live, and the collection's stands in that day. */
    fun veto(t: Tomorrow) {
        val me = me ?: return
        val bot = cc.aaro.cosign.ui.Daily.previewBot ?: return
        tomorrow.value = t.copy(vetoed = true)
        scope.launch {
            val tags = listOf(listOf("d", "marble/veto/${t.date}"), listOf("p", bot))
            val ok = actEvent(Event(me, Clock.now(), 30078, tags, "").withId(), relays = PREVIEW_RELAYS) != null
            if (!ok) tomorrow.value = t
        }
    }

    /** The picture: nonce (12 bytes), then the ciphertext and its tag (AES-256-GCM). */
    private fun openPicture(blob: ByteArray, key: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** NIP-44 from the bot to this account. Waits on Cosign: Marble's own way in decrypts to itself only, by design. */
    private fun fromBot(bot: String, text: String): String? = null

    /** Your account's seen list, merged into this phone's; what's further down the feed and seen elsewhere goes. */
    private suspend fun pullSeen(again: Boolean = false) {
        val me = me ?: return
        if (Sources.viewAs != null || (seenPulled && !again)) return
        seenPulled = true
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to listOf(SeenSync.TAG), "limit" to 4), 6000)
            .filter { it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt } ?: return
        if (got.createdAt <= prefs.getLong("seenPulled", 0)) return
        val keys = SeenSync.decode(toSelf(got.content, encrypt = false) ?: return)
        if (debuggable) android.util.Log.i("CosignSync", "seen pulled: ${keys.size} keys from ${relays.size} relays")
        var added = 0
        for ((k, t) in keys) if (seen.add(k, t)) added++
        prefs.edit().putLong("seenPulled", got.createdAt).apply()
        if (added > 0) feed.update { cur -> cur.copy(posts = cur.posts.filterIndexed { i, p -> i <= lookedAt + 1 || unseen(p.e.id) }) }
        // This phone has seen things the account's copy hasn't: send the union.
        val theirs = keys.map { it.first }.toSet()
        if (!again && seen.since(Clock.now() - SeenSync.WINDOW).any { it.first !in theirs }) seenChanged()
    }

    /** NIP-44 with yourself (the signer pins the other side to your own key). Blocks for a few seconds: never on the open path. */
    private fun toSelf(text: String, encrypt: Boolean): String? = phone.nip44Self(text, encrypt)

    // --- The ⋯ on a card, and Save -----------------------------------------------------------

    /** Hashtags a followed topic goes into your public interests list (kind 10015) as. */
    private val TOPIC_TAG = mapOf("ai" to "ai", "software" to "programming", "selfhosting" to "selfhosting", "nostr" to "nostr", "bitcoin" to "bitcoin",
        "privacy" to "privacy", "food" to "food", "photos" to "photography", "games" to "gaming", "gadgets" to "tech", "art" to "art", "news" to "news")

    /**
     * Follow a topic (or stop): more of it, on this phone and in your saved profile, and in your public
     * interests list (kind 10015), which other apps read too.
     */
    fun followTopic(topic: String, on: Boolean) {
        interests.follow(topic, on)
        followedTopics.value = interests.followed.toSet()
        if (on) message.value = "You'll see more about ${TopicFeeds.name(topic).lowercase()}."
        val tag = TOPIC_TAG[topic] ?: return
        val me = me ?: return
        scope.launch {
            val list = newest(10015, null, fetch = true)
            val tags = list?.tags ?: emptyList()
            val has = tags.any { it.size > 1 && it[0] == "t" && it[1].equals(tag, true) }
            if (has == on) return@launch
            val next = if (on) tags + listOf(listOf("t", tag)) else tags.filterNot { it.size > 1 && it[0] == "t" && it[1].equals(tag, true) }
            act(Event(me, Clock.now(), 10015, next, list?.content ?: "").withId())
        }
    }

    /** "More like this": a little more of its topic (and, for a newsletter, of newsletters). */
    fun moreLike(topic: String, post: Event? = null) { interests.learn(listOf(topic), 1.0); post?.let { newsletterTaste(it, 1) }; message.value = "More like this from now on." }

    /** You opened a post's link. */
    fun openedLink(post: Event) = newsletterTaste(post, 1)

    /** A stranger's newsletter you liked (or didn't): more of them, or fewer. */
    private fun newsletterTaste(e: Event, by: Int) {
        if (e.pubkey in (follows.value ?: emptySet()) || !Calm.digest(e)) return
        interests.newsletters(by)
    }

    /** "Less like this": this one goes, and a little less of its topic. [key]: the post's id, or "t:" and the story's. */
    fun lessLike(topic: String, key: String, post: Event? = null) {
        interests.learn(listOf(topic), -1.5)
        post?.let { newsletterTaste(it, -1) }
        hide(key)
        message.value = "Less like this from now on."
    }

    /** "Hide": nothing more from this person, or from this site. */
    fun hidePerson(pk: String) {
        interests.hide("hiddenPeople", pk)
        feed.update { f -> f.copy(posts = f.posts.filter { it.e.pubkey != pk }) }
        message.value = "You won't see posts from ${nameOf(pk)} here."
    }
    fun hideSource(c: TopicCard) {
        interests.hide("hiddenSources", c.sourceId)
        feed.update { f -> f.copy(stories = f.stories.filter { it.sourceId != c.sourceId }) }
        message.value = "You won't see stories from ${c.sourceName}."
    }

    /** You opened a story: a little more of its topic. */
    fun opened(c: TopicCard) { interests.learn(listOf(c.topic), 1.0); if (seen.add("o:" + c.id)) seenChanged() }

    private fun hide(key: String) {
        dismissed.update { it + key }
        prefs.edit().putStringSet("dismissed", dismissed.value.toList().takeLast(2000).toSet()).apply()
    }

    /** What you've saved (your bookmarks, kind 10003): posts by id, stories by link. */
    val saved = MutableStateFlow<Set<String>>(emptySet())
    private suspend fun knowSaved() {
        saved.value = newest(10003, null, fetch = true)?.tags?.filter { it.size > 1 && (it[0] == "e" || it[0] == "r") }?.map { it[1] }?.toSet() ?: emptySet()
    }

    /** Save (or unsave) a post or a story, in your bookmarks, which other apps show too. */
    fun save(id: String, isLink: Boolean) {
        val me = me ?: return
        val on = id !in saved.value
        saved.update { if (on) it + id else it - id }
        scope.launch {
            val list = newest(10003, null, fetch = true)
            val tags = list?.tags ?: emptyList()
            val name = if (isLink) "r" else "e"
            val next = if (on) (if (tags.any { it.size > 1 && it[0] == name && it[1] == id }) return@launch else tags + listOf(listOf(name, id)))
                else tags.filterNot { it.size > 1 && it[0] == name && it[1] == id }
            if (!act(Event(me, Clock.now(), 10003, next, list?.content ?: "").withId())) saved.update { if (on) it - id else it + id }
            else if (on) message.value = "Saved."
        }
    }

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

    /** The relays a post's replies and reactions are looked for on, first: the big ones (with search). */
    private val POST_RELAYS = listOf("wss://syb.lol", "wss://relay.ditto.pub", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

    /** What can be shown of thread [id] right now, with no network: as opened before, or the note itself (under its first note, if that's here). Null if neither is. */
    fun threadNow(id: String): List<Pair<Event, Int>>? {
        synchronized(threads) { threads[id] }?.let { return it }
        val n = note(id) ?: return null
        val root = rootOf(n)?.let { note(it) }
        return if (root != null && root.id != n.id) listOf(root to 0, n to 1) else listOf(n to 0)
    }

    /** The whole thread [id] is in: its first note, then the replies, each with how deep it sits. */
    /**
     * A thread, gathered where replies and reactions land (as Wisp does, and more): the link's relays, the big
     * ones, the root author's inbox (NIP-65 read relays), yours; then each replier's own relays. [onSome]: the
     * thread so far, each time more comes in. Its reactions are counted on the side ([engagement]).
     */
    suspend fun thread(id: String, hints: List<String> = emptyList(), onSome: (List<Pair<Event, Int>>) -> Unit = {}): List<Pair<Event, Int>> = kotlinx.coroutines.coroutineScope {
        val note = note(id) ?: Fetch.query(hints + POST_RELAYS + readRelays().take(4), mapOf("ids" to listOf(id)), 6000).firstOrNull()?.also { remember(listOf(it)) } ?: return@coroutineScope emptyList()
        val rootId = rootOf(note) ?: note.id
        val rootNote = note(rootId) ?: if (rootId == note.id) note else null
        val relays = (hints + postRelays(rootNote?.pubkey ?: note.pubkey)).distinct()
        val got = java.util.concurrent.ConcurrentHashMap<String, Event>()
        fun build(): List<Pair<Event, Int>> = arrange(note, rootId, got.values.toList()).also { synchronized(threads) { threads[id] = it }; onSome(it) }
        val first = Fetch.query(relays, listOf(mapOf("ids" to listOf(rootId)), mapOf("kinds" to listOf(1, 1111), "#e" to listOf(rootId)), mapOf("kinds" to listOf(1111), "#E" to listOf(rootId))), 7000)
        first.forEach { got[it.id] = it }
        build()
        // Then where the people who replied post (their write relays), for the replies that only went there.
        val repliers = got.values.filter { it.id != rootId }.map { it.pubkey }.distinct().take(30)
        val second = repliers.map { pk -> async { outboxOf(pk).take(3) } }.awaitAll().flatten().distinct().filter { it !in relays }.take(25)
        val more = if (second.isEmpty()) emptyList() else Fetch.query(second, listOf(mapOf("kinds" to listOf(1, 1111), "#e" to listOf(rootId)), mapOf("kinds" to listOf(1111), "#E" to listOf(rootId))), 5000)
        more.forEach { got.putIfAbsent(it.id, it) }
        val out = build()
        launch { countReactions(out.map { it.first }, relays + second) }
        out
    }

    /** The thread laid out: the root, then each reply under the note it answers, oldest first. */
    private fun arrange(note: Event, rootId: String, got: List<Event>): List<Pair<Event, Int>> {
        val root = if (rootId == note.id) note else note(rootId) ?: got.firstOrNull { it.id == rootId }
        // Replies, not notes that only mention it.
        val replies = got.filter { e -> (e.kind == 1 || e.kind == 1111) && e.id != rootId && safe(e) && e.tags.any { it.size > 1 && (it[0] == "e" || it[0] == "E") && it[1] == rootId && it.getOrNull(3) != "mention" } }
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
        return out
    }

    // --- How many: replies, likes, reposts, zaps -------------------------------------------

    /** What a post has drawn: what the relays handed over, and what they counted (NIP-45), whichever is more. */
    data class Engagement(val replies: Int = 0, val likes: Int = 0, val reposts: Int = 0, val zaps: Int = 0,
                          val emojis: List<Pair<String, Int>> = emptyList(), val countedReplies: Long? = null, val countedLikes: Long? = null) {
        val replyTotal get() = maxOf(replies.toLong(), countedReplies ?: 0)
        val likeTotal get() = maxOf(likes.toLong(), countedLikes ?: 0)
    }
    val engagement = MutableStateFlow<Map<String, Engagement>>(emptyMap())

    /** The relays where a post's replies and reactions land: the big ones, its author's inbox, yours. */
    private suspend fun postRelays(author: String) = (POST_RELAYS + inboxOf(author).take(8) + readRelays().take(4)).distinct()

    /** Reactions to these posts (likes, with their emoji; reposts; zaps), counted as they come; the first one's totals asked for too. */
    private suspend fun countReactions(posts: List<Event>, relays: List<String>) = kotlinx.coroutines.coroutineScope {
        if (posts.isEmpty()) return@coroutineScope
        val root = posts.first()
        launch {
            val counts = SearchRelays.COUNTS.map { url -> async {
                Fetch.counts(url, mapOf("r" to mapOf("kinds" to listOf(1, 1111), "#e" to listOf(root.id)), "l" to mapOf("kinds" to listOf(7), "#e" to listOf(root.id))), 5000)
            } }.awaitAll()
            val r = counts.mapNotNull { it["r"] }.maxOrNull(); val l = counts.mapNotNull { it["l"] }.maxOrNull()
            engagement.update { m -> m + (root.id to (m[root.id] ?: Engagement()).copy(countedReplies = r, countedLikes = l)) }
        }
        val replies = posts.drop(1).groupBy { parentOf(it) ?: root.id }.mapValues { it.value.size }
        val got = posts.map { it.id }.chunked(100).map { ids -> async { Fetch.query(relays, mapOf("kinds" to listOf(7, 6, 16, 9735), "#e" to ids), 6000) } }.awaitAll().flatten().distinctBy { it.id }
        val byTarget = got.groupBy { r -> r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) }
        engagement.update { m ->
            m + posts.associate { p ->
                val rs = byTarget[p.id] ?: emptyList()
                val likes = rs.filter { it.kind == 7 && it.content != "-" }
                val emojis = likes.groupingBy { it.content.ifBlank { "+" } }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
                p.id to (m[p.id] ?: Engagement()).copy(replies = replies[p.id] ?: 0, likes = likes.size, reposts = rs.count { it.kind == 6 || it.kind == 16 }, zaps = rs.count { it.kind == 9735 }, emojis = emojis)
            }
        }
    }

    // --- A pasted link: the post or person, and what points at them ------------------------------

    /** What a pasted post's page shows under it: quotes of it, its replies, posts that mention it. */
    data class Lookup(val quotedBy: List<Event> = emptyList(), val replies: List<Event> = emptyList(), val mentionedIn: List<Event> = emptyList(), val done: Boolean = false)

    /** Quotes, replies and mentions of a post, from where they land; each section as soon as it's in. */
    suspend fun lookupNote(id: String, hints: List<String>, onSome: (Lookup) -> Unit) = kotlinx.coroutines.coroutineScope {
        val note = note(id) ?: Fetch.query(hints + POST_RELAYS + readRelays().take(4), mapOf("ids" to listOf(id)), 6000).firstOrNull()?.also { remember(listOf(it)) }
        val relays = (hints + if (note != null) postRelays(note.pubkey) else POST_RELAYS).distinct()
        val got = Fetch.query(relays, listOf(mapOf("kinds" to listOf(1, 1111), "#e" to listOf(id), "limit" to 300), mapOf("kinds" to listOf(1111), "#E" to listOf(id)), mapOf("kinds" to listOf(1), "#q" to listOf(id), "limit" to 100)), 7000)
            .filter { safe(it) && it.id != id }
        val quoted = got.filter { e -> e.tags.any { it.size > 1 && it[0] == "q" && it[1] == id } }
        val replies = got.filter { e -> e !in quoted && e.tags.any { it.size > 1 && (it[0] == "e" || it[0] == "E") && it[1] == id && it.getOrNull(3) != "mention" } }
        val mentions = got.filter { e -> e !in quoted && e !in replies }
        remember(got)
        People.want(got.map { it.pubkey })
        onSome(Lookup(quoted.sortedByDescending { it.createdAt }, replies.sortedBy { it.createdAt }, mentions.sortedByDescending { it.createdAt }, done = true))
        if (note != null) launch { countReactions(listOf(note), relays) }
    }

    /** Posts that mention someone (not replies to them), newest first, from where they land: their inbox and the big relays. */
    suspend fun mentionsOf(pk: String, hints: List<String>): List<Event> {
        val relays = (hints + POST_RELAYS + inboxOf(pk).take(8)).distinct()
        val got = Fetch.query(relays, mapOf("kinds" to listOf(1), "#p" to listOf(pk), "limit" to 100), 7000)
            .filter { e -> safe(e) && e.pubkey != pk && parentOf(e) == null }.sortedByDescending { it.createdAt }.take(50)
        remember(got); People.want(got.map { it.pubkey })
        return got
    }

    /** Someone's name and picture, straight from the two fast relays (over connections kept open a while), not the batched path. */
    /** A profile's posts as last shown (the last ten profiles), so coming back to one finds it as you left it. */
    data class ProfilePosts(val notes: List<Event>?, val settled: Boolean, val more: Boolean)
    private val profiles = object : LinkedHashMap<String, ProfilePosts>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProfilePosts>?) = size > 10
    }
    fun profilePosts(pk: String): ProfilePosts? = synchronized(profiles) { profiles[pk] }
    fun keepProfilePosts(pk: String, p: ProfilePosts) = synchronized(profiles) { profiles[pk] = p }

    /**
     * Someone's profile from anywhere it may be: the fast relays, the usual profile relays, where they say they
     * post, and any relay a mention or a link pointed to (people brought over from other networks are often
     * only there). True if it's known now.
     */
    suspend fun findProfile(pk: String): Boolean = kotlinx.coroutines.coroutineScope {
        val filter = mapOf("kinds" to listOf(0), "authors" to listOf(pk), "limit" to 1)
        val usual = (SearchRelays.FAST + Sources.PROFILES + hintsOf(pk)).distinct()
        val first = async { Fetch.query(usual, filter, 6000) }
        val theirs = async { outboxOf(pk).filter { it !in usual }.let { if (it.isEmpty()) emptyList() else Fetch.query(it, filter, 6000) } }
        People.add(first.await() + theirs.await())
        People.known.value[pk] != null
    }

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

    /** [e]'s nevent, with two relays to find it on: where its author posts, if that's known here, else two big ones. */
    fun neventOf(e: Event) = nevent(e.id, e.pubkey, e.kind, ((outboxes[e.pubkey]?.first ?: emptyList()) + listOf("wss://relay.damus.io", "wss://nos.lol")).distinct().take(2))
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

    /** Where someone reads (their NIP-65 read relays, up to eight): where replies and reactions to them land. Kept an hour. */
    private suspend fun inboxOf(pk: String): List<String> {
        inboxes[pk]?.let { (l, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return l }
        val l = Fetch.query(Sources.PROFILES.take(4), mapOf("kinds" to listOf(10002), "authors" to listOf(pk), "limit" to 1), 4000).maxByOrNull { it.createdAt }
        val list = l?.tags?.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "write" && it[1].startsWith("wss://") }?.map { it[1].trimEnd('/') }?.distinct()?.take(8) ?: emptyList()
        inboxes[pk] = list to System.currentTimeMillis()
        return list
    }
    private val inboxes = ConcurrentHashMap<String, Pair<List<String>, Long>>()

    // --- Replying and reporting ----------------------------------------------------------

    /**
     * Reply to [parent] (NIP-10: the thread's first post as root, the post you answer as reply,
     * everyone in the conversation tagged), sent to your relays and the author's. The reply, once sent; null if it wasn't.
     */
    /**
     * A post of your own: [text], and [link] under it (a story, say). A link to a post or article here is quoted
     * as one (nostr: and a q tag), so apps show the post itself rather than a web page.
     */
    suspend fun post(text: String, link: String = ""): Event? {
        val me = me ?: return null
        val quoted = Ref.parse(link.substringAfterLast('/').substringBefore('?')) as? Ref.Note
        val tail = when {
            quoted != null -> "nostr:" + cc.aaro.cosign.core.Bech32.encode("note", quoted.id.hexToBytes())
            else -> link.trim()
        }
        val body = listOf(text.trim(), tail).filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { return null }
        val tags = if (quoted != null) listOf(listOf("q", quoted.id)) else emptyList()
        val signed = actEvent(Event(me, Clock.now(), 1, tags, body).withId()) ?: return null
        remember(listOf(signed))
        return signed
    }

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
        if (note.id in liked.value) return
        react(note, "+")
    }

    /**
     * A reaction: "+" is a like, anything else the emoji it is. Again on a post you've reacted to, it's a new
     * reaction, and the newest is the one shown (what's learned from it is learned once).
     */
    fun react(note: Event, emoji: String) {
        val me = me ?: return
        val first = note.id !in liked.value
        val before = reactions.value[note.id]
        mark(note.id, true)
        setReaction(note.id, emoji.takeIf { it != "+" })
        if (emoji != "+") { emojiUse[emoji] = (emojiUse[emoji] ?: 0) + 1; prefs.edit().putString("emojiUse", org.json.JSONObject(emojiUse.entries.sortedByDescending { it.value }.take(40).associate { it.key to it.value }).toString()).apply() }
        if (first) {
            interests.liked(TopicGuess.of(reposted(note) ?: note), likesCaughtUp)
            newsletterTaste(reposted(note) ?: note, 1)
        }
        scope.launch {
            val tags = listOf(listOf("e", note.id), listOf("p", note.pubkey), listOf("k", note.kind.toString()))
            if (!act(Event(me, Clock.now(), 7, tags, emoji).withId())) { if (first) mark(note.id, false); setReaction(note.id, before) }
        }
    }

    /** The emojis a long press on the heart offers: the ones you use most, then the usual ones. */
    fun topEmojis(): List<String> = (emojiUse.entries.sortedByDescending { it.value }.map { it.key } + listOf("❤️", "😂", "😮", "😢", "🔥", "🤙")).distinct().take(6)

    private fun setReaction(id: String, emoji: String?) {
        reactions.update { if (emoji == null) it - id else it + (id to emoji) }
        prefs.edit().putStringSet("reactions", reactions.value.entries.toList().takeLast(1000).map { "${it.key} ${it.value}" }.toSet()).apply()
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
        val me = reader ?: return null
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


    /** People whose notes you said you're not interested in, until when (ms): Discover leaves them out for a while. */
    val snoozed = MutableStateFlow(prefs.getStringSet("snoozed", emptySet())!!.mapNotNull { s ->
        s.substringAfter(':').toLongOrNull()?.takeIf { it > System.currentTimeMillis() }?.let { s.substringBefore(':') to it } }.toMap())

    /** Not this note, and less like it: the same author is left out of Discover for three days. */
    fun notInterested(e: Event) {
        interests.learn(TopicGuess.of(e), -1.0)
        newsletterTaste(e, -1)
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
        if (Sources.viewAs != null) { message.value = "Looking as another account: nothing is signed or sent."; return null }
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
        const val DEVICE_NAMES = "marble/devices"
        /** What a device is known by in [deviceNames]: its key, or its place in the account if it has none. */
        fun deviceKey(d: cc.aaro.cosign.Member) = d.pk ?: "idx:${d.idx}"
        /** {"v":1,"names":{"<key>":{"name":"…","at":<seconds>}}} */
        fun namesJson(m: Map<String, DeviceName>): String = org.json.JSONObject().put("v", 1)
            .put("names", org.json.JSONObject().apply { m.forEach { (k, v) -> put(k, org.json.JSONObject().put("name", v.name).put("at", v.at)) } }).toString()
        fun namesFrom(json: String?): Map<String, DeviceName> = try {
            val o = org.json.JSONObject(json ?: "{}")
            if (o.optInt("v", 1) != 1) emptyMap() else o.optJSONObject("names")?.let { n ->
                n.keys().asSequence().mapNotNull { k -> n.optJSONObject(k)?.let { v -> v.optString("name").trim().takeIf { it.isNotEmpty() }?.let { k to DeviceName(it.take(40), v.optLong("at")) } } }.toMap()
            } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        /** This device as people call it: the name set in the system's settings, else its maker and model ("Onn 11 Tablet Pro", not "100071485"). */
        fun readableDeviceName(context: android.content.Context): String {
            val set = try { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
            if (!set.isNullOrBlank() && set != android.os.Build.MODEL) return set.trim().take(40)
            val maker = android.os.Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
            val model = android.os.Build.MODEL.trim()
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifEmpty { "This device" }.take(40)
        }

        private fun remember(es: List<Event>) {
            synchronized(notes) { for (e in es) notes[e.id] = e }
            for (e in es) for (t in e.tags) if (t.size > 2 && t[0] == "p") hint(t[1], listOf(t[2]))
        }

        /** Where people were said to be found (a mention's relay, a link's): asked too when looking for their profile. */
        private val hints = ConcurrentHashMap<String, List<String>>()
        fun hint(pk: String, relays: List<String>) {
            val l = relays.map { it.trim().trimEnd('/') }.filter { it.startsWith("wss://") }
            if (l.isNotEmpty()) hints.merge(pk, l) { a, b -> (a + b).distinct().take(4) }
        }
        fun hintsOf(pk: String) = hints[pk] ?: emptyList()
        private fun noteOf(id: String) = synchronized(notes) { notes[id] }

        private val all = HashMap<String, Social>()
        fun of(phone: Phone): Social = synchronized(all) {
            all.getOrPut(phone.slot + "/" + phone.ui.value.member?.group?.groupX) { People.init(CosignApp.instance); Social(phone, CosignApp.instance).also { it.restoreFeed() } }
        }
    }

    private fun remember(es: List<Event>) = Companion.remember(es)
}
