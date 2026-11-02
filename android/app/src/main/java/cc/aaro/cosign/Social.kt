package cc.aaro.cosign

import android.content.Context
import cc.aaro.qrst.core.Bech32
import cc.aaro.cosign.core.Clock
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import cc.aaro.qrst.core.Nip19
import cc.aaro.qrst.core.isHex32
import cc.aaro.qrst.core.toHex
import cc.aaro.qrst.core.hexToBytes
import cc.aaro.cosign.net.Conn
import cc.aaro.cosign.net.Fetch
import cc.aaro.cosign.notes.NotesNet
import cc.aaro.cosign.notes.NotesStore
import cc.aaro.cosign.notes.NotesSync
import cc.aaro.cosign.notes.Stored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
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
    /** Discover's posts: fiatjaf's Pyramid, what its members liked or replied to this week (read only, no sign-in). */
    const val POPULAR = "wss://pyramid.fiatjaf.com/popular"
    /** Discover reads the popular list (and your Special Follow list) instead of building its own. */
    const val POPULAR_ONLY = true
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

/** An article's address for other apps (NIP-19 naddr): its d tag, author and kind (an article is replaceable: this finds its newest version). */
fun naddr(d: String, author: String, kind: Int): String {
    val out = java.io.ByteArrayOutputStream()
    fun tlv(t: Int, v: ByteArray) { out.write(t); out.write(v.size); out.write(v) }
    tlv(0, d.toByteArray())
    tlv(2, author.hexToBytes())
    tlv(3, java.nio.ByteBuffer.allocate(4).putInt(kind).array())
    return Bech32.encode("naddr", out.toByteArray())
}

/**
 * A post's or article's link to share: marble.syb.lol, which opens it at once (Ditto's search finds it by its id) and
 * which Marble opens itself. The same every time (no relay hints, which change).
 */
fun marbleLink(e: Event): String = "https://marble.syb.lol/" +
    if (e.kind in 30000..39999) naddr(e.firstTag("d") ?: "", e.pubkey, e.kind) else nevent(e.id, e.pubkey, e.kind, emptyList())

/** What a marble.syb.lol link names: a post or someone (/nevent1…, /note1…, /npub1…, /nprofile1…, and the older /e/… and /p/…). */
fun marbleRef(url: String): Ref? = refIn(url, setOf("marble.syb.lol"))

/**
 * A link that's a post, article or someone, opened in Marble rather than a browser: marble.syb.lol (/nevent1…, /e/…,
 * /p/…, /a/…), njump.me, or nostr:…. Anything else (a real web page): null.
 */
fun inAppRef(url: String): Ref? = if (url.startsWith("nostr:", ignoreCase = true)) Ref.parse(url) else refIn(url, setOf("marble.syb.lol", "njump.me"))

private fun refIn(url: String, hosts: Set<String>): Ref? {
    val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
    if (u.scheme?.lowercase() !in setOf("https", "http") || u.host?.lowercase()?.removePrefix("www.") !in hosts) return null
    val segs = u.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val last = segs.lastOrNull() ?: return null
    // /e/<hex> and /p/<hex>; a bare hex says nothing about what it is.
    if (isHex32(last.lowercase())) return when (segs.getOrNull(segs.size - 2)) { "e" -> Ref.Note(last.lowercase()); "p" -> Ref.Profile(last.lowercase()); else -> null }
    return Ref.parse(last)
}

/** The post a marble.syb.lol link stands for, if that's what it is. */
fun marblePost(url: String): String? = (marbleRef(url) as? Ref.Note)?.id

/** Folded news stories ("seen, but keep it"): small rows in Following, kept across restarts. Not a ranking signal. */
/**
 * A post's text for one line (a kept post in Notes): a mention becomes @their name, and a quoted post or article
 * "↪ quoted" / "↪ an article", never the raw nostr:… string. [name]: someone's name, if known.
 */
fun readable(text: String, name: (String) -> String? = { null }): String = MENTION.replace(text) { m ->
    when (val r = Ref.parse(m.value)) {
        is Ref.Profile -> "@" + (name(r.pubkey) ?: "someone")
        is Ref.Note -> "↪ quoted"
        is Ref.Addr -> if (r.kind == 30023) "↪ an article" else "↪ quoted"
        null -> m.value
    }
}
private val MENTION = Regex("nostr:(npub1|nprofile1|note1|nevent1|naddr1)[02-9ac-hj-np-z]+", RegexOption.IGNORE_CASE)

object FoldedStories {
    private fun prefs() = CosignApp.instance.getSharedPreferences("marble", android.content.Context.MODE_PRIVATE)
    val ids = kotlinx.coroutines.flow.MutableStateFlow(try { prefs().getStringSet("folded", emptySet())!!.toSet() } catch (_: Exception) { emptySet() })
    fun toggle(id: String) {
        val on = id !in ids.value
        ids.value = if (on) (ids.value + id).toList().takeLast(1000).toSet() else ids.value - id
        prefs().edit().putStringSet("folded", ids.value).apply()
        FeedLog.act("t:$id", if (on) "seen" else "unseen")
    }
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
    /** An addressable event (NIP-19 naddr): kind, author and d tag; its newest version is the one. */
    data class Addr(val kind: Int, val pubkey: String, val d: String) : Ref() {
        val naddr get() = naddr(d, pubkey, kind)
    }

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
                "naddr" -> addr(data)?.apply { relays = hints(data) }
                else -> null
            }
        }

        private val ENTITY = Regex("(npub1|nprofile1|note1|nevent1|naddr1)[02-9ac-hj-np-z]+")

        /** An naddr's TLV: 0 the d tag, 2 the author, 3 the kind (4 bytes, big-endian). */
        private fun addr(b: ByteArray): Addr? {
            var d: String? = null; var author: String? = null; var kind: Int? = null; var i = 0
            while (i + 2 <= b.size) {
                val t = b[i].toInt() and 0xff; val l = b[i + 1].toInt() and 0xff
                if (i + 2 + l > b.size) break
                val v = b.copyOfRange(i + 2, i + 2 + l)
                when (t) { 0 -> d = String(v); 2 -> if (l == 32) author = v.toHex(); 3 -> if (l == 4) kind = java.nio.ByteBuffer.wrap(v).int }
                i += 2 + l
            }
            return if (d != null && author != null && kind != null) Addr(kind, author, d) else null
        }

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

/** A search's results; [split]: someone you follow named in it, and [byAuthor], their posts with the other words (first). */
data class Found(val query: String, val people: List<String> = emptyList(), val notes: List<Event> = emptyList(), val loading: Boolean = true, val hits: Map<String, Hit> = emptyMap(),
                 val split: AuthorQuery.Split? = null, val byAuthor: List<Event> = emptyList(), val spam: Map<String, cc.aaro.cosign.spam.Spam.Why> = emptyMap())

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
        logStories("stories", out)
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
        if (force) FeedLog.ev("pull", "screen" to "discover")
        // (Feed 3's "where you'd got to" moves on a new visit only, never on a pull: pulling twice mustn't make it all "earlier".)
        if (f.posts.isEmpty()) editionVisit()
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
            // (Discover reads one curated list now, quick enough on its own.)
            if (!Sources.POPULAR_ONLY && (f.posts.isEmpty() || force)) launch {
                val quick = try { quickPosts(onScreen) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
                if (quick.isNotEmpty() && feed.value.loading) { feed.update { if (it.posts.isEmpty() || force) it.copy(posts = quick) else it }; Startup.mark("feed quick ${quick.size}"); loadPreviews(quick.map { it.e }) }
            }
            val posts = try { if (Sources.POPULAR_ONLY) popularPosts(onScreen) else buildPosts(onScreen) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            if (posts == null) return@launch feed.update { it.copy(loading = false, failed = it.posts.isEmpty() && it.stories.isEmpty()) }
            // Pulled: what you just pulled for (the quick posts at the top, if any, stay there). On its own (every
            // 30 minutes, or back after a while): everything stays as it is, the new posts come in below it.
            feed.update { cur ->
                val keep = if (force) (if (cur.posts.none { p -> posts.any { it.e.id == p.e.id } }) emptyList() else cur.posts.take(lookedAt + 3)) else cur.posts
                val fresh = posts.filter { p -> keep.none { it.e.id == p.e.id } }
                // Back after a while: the new ones first, unless you've already started down the list (then below, as ever).
                val first = top && lookedAt <= 1
                // The popular list: what's new goes above what you have (the list keeps your place; a "new" pill says so),
                // and nothing you were reading moves or goes.
                if (Sources.POPULAR_ONLY) cur.copy(posts = (posts.filter { p -> cur.posts.none { it.e.id == p.e.id } } + cur.posts).take(400), loading = false, at = System.currentTimeMillis())
                else cur.copy(posts = if (first) fresh + keep else keep + (if (force) fresh.take((20 - keep.size).coerceAtLeast(0)) else fresh), loading = false, at = System.currentTimeMillis())
            }
            // A refresh of the list lets what you put away go (its slim rows with Undo).
            justDismissed.value = emptyMap()
            if (posts.isNotEmpty()) Startup.mark("feed posts ${posts.size}")
            logPosts(if (force) "pull" else if (top) "back" else "load", feed.value.posts)
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
        editionVisit()
        if (feed.value.loading) return
        lookedAt = 0
        if (!Sources.POPULAR_ONLY) feed.update { it.copy(posts = it.posts.filter { p -> unseen(p.e.id) }) }
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
        Calm.diversify(withoutSpam(picks.sortedByDescending { it.score }.take(30), follows), max = 6).also { remember(it.map { p -> p.e }); People.want(it.map { p -> p.e.pubkey }) }
    }

    /** Posts that look like spam, by id, and why (cued wherever they show; see [Spam]). */
    val spam = MutableStateFlow<Map<String, cc.aaro.cosign.spam.Spam.Why>>(emptyMap())

    /**
     * Picks without manufactured engagement (what hot lists are full of): a stranger's goes, a follow's is only cued.
     * [got]: the picks' reactions and reposts, if already fetched; else they're asked for (two seconds at most).
     */
    private suspend fun withoutSpam(picks: List<Pick>, follows: Set<String>, got: List<Event>? = null): List<Pick> {
        if (picks.isEmpty()) return picks
        val all = got ?: kotlinx.coroutines.withTimeoutOrNull(2000) {
            Fetch.query(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol"), mapOf("kinds" to listOf(7, 6, 16), "#e" to picks.map { it.e.id }, "limit" to 3000), 2000)
        } ?: emptyList()
        val fof = friendsOfFriends()
        val why = picks.filter { !cc.aaro.cosign.spam.Spam.notSpam(it.e.pubkey) }.mapNotNull { p ->
            (cc.aaro.cosign.spam.Spam.inflated(p.e.id, all) ?: cc.aaro.cosign.spam.Spam.advert(p.e)?.takeIf { (fof[p.e.pubkey] ?: 0) == 0 })?.let { p.e.id to it }
        }.toMap()
        if (why.isEmpty()) return picks
        spam.update { it + why }
        if (debuggable) Startup.mark("feed spam ${why.size}")
        return picks.filter { it.e.id !in why || it.e.pubkey in follows || it.e.pubkey == me }
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
        val reactions = first.map { it.e.id }.chunked(150).map { ids -> async { Fetch.query(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol", "wss://syb.lol"), mapOf("kinds" to listOf(7, 6, 9735), "#e" to ids, "limit" to 5000), 8000) } }
            .awaitAll().flatten().distinctBy { it.id }
        reactions.forEach { r ->
                val target = r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1) ?: return@forEach
                val who = if (r.kind == 9735) (try { (Json.parse(r.tags.firstOrNull { it.size > 1 && it[0] == "description" }?.get(1) ?: "") as? Map<*, *>)?.get("pubkey") as? String } catch (_: Exception) { null }) ?: return@forEach else r.pubkey
                fans.getOrPut(target) { HashSet() } += who
            }
        Startup.mark("feed fans ${fans.size}")
        inputs = Inputs(passes, standing, fans, follows, perAuthor)
        val picks = Calm.collapse(Calm.diversify(withoutSpam(rank(fans), follows, reactions)), ::nameOf)
        remember(picks.map { it.e })
        People.want(picks.map { it.e.pubkey })
        Startup.mark("feed ranked ${picks.size}")
        picks
    }
    private fun nameOf(pk: String) = People.known.value[pk]?.shown ?: shortNpub(pk)

    /**
     * Following's posts, the simple way: what the people you follow posted in the last two days (four each at most),
     * newest first, lifted a little by what you like, with your Special Follow people (★) woven in near the top.
     */
    private suspend fun popularPosts(skip: Set<String>): List<Pick> = kotlinx.coroutines.coroutineScope {
        val now = Clock.now()
        if (feedVersion.value == 3) return@coroutineScope editionPosts(skip, now)
        // Following: what the people you follow posted in the last two days (their own posts, not replies).
        val pop = async { followingPosts(now) }
        val theirs = async { specialPosts(now) }
        val hidden = muted.value
        fun ok(e: Event) = e.id !in skip && e.pubkey !in hidden && !interests.hidden("hiddenPeople", e.pubkey) && !mutedText(e.content, e) && e.createdAt <= now + 600
        val woven = Calm.weave(pop.await().filter { ok(it) && !snoozedNow(it.pubkey) }, theirs.await().filter(::ok), ::lift)
        if (debuggable) Startup.mark("feed popular ${woven.count { !it.second }}, special ${woven.count { it.second }}")
        val starred = special.value
        woven.map { (e, sp) -> Pick(e, TopicGuess.of(e).firstOrNull() ?: "", 0.0, if (sp || e.pubkey in starred) "★ Special Follow" else "") }.also {
            remember(it.map { p -> p.e }); People.want(it.map { p -> p.e.pubkey }.distinct())
        }
    }

    // --- Feed 3 · Edition ---------------------------------------------------------------------------

    /**
     * Feed 3's people: everyone you follow, their own posts of the last two days, strictly newest first (no lift, no
     * cap: a burst folds on screen instead). Special Follow people are marked, not moved. Nothing here is ranked.
     */
    private suspend fun editionPosts(skip: Set<String>, now: Long): List<Pick> = kotlinx.coroutines.coroutineScope {
        val sp = async { specialPeople() }
        val hidden = muted.value
        val posts = followingPosts(now, cap = Int.MAX_VALUE).filter { e ->
            e.id !in skip && e.pubkey !in hidden && !interests.hidden("hiddenPeople", e.pubkey) && !mutedText(e.content, e) && e.createdAt <= now + 600 && !snoozedNow(e.pubkey)
        }
        val starred = sp.await()
        scope.launch { beyond.value = try { beyondPosts(now) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; beyond.value } }
        posts.map { e -> Pick(e, TopicGuess.of(e).firstOrNull() ?: "", 0.0, if (e.pubkey in starred) "★ Special Follow" else "") }.also {
            remember(it.map { p -> p.e }); People.want(it.map { p -> p.e.pubkey }.distinct())
        }
    }

    /** Feed 3's end: three posts from outside your follows, each with why it's here. */
    val beyond = MutableStateFlow<List<Pick>>(emptyList())

    /**
     * Beyond your people: posts of the last two days that people you follow liked or shared, from people you don't follow.
     * Trust comes from your people only: no counts, no follower numbers, no hot lists. The more of your people, the
     * likelier; three at most, one per person; likely spam out. The why says who, and what they did.
     */
    private suspend fun beyondPosts(now: Long): List<Pick> {
        val mine = follows.value.orEmpty()
        if (mine.isEmpty()) return emptyList()
        val relays = (readRelays() + POST_RELAYS).distinct().take(6)
        val acts = mine.toList().chunked(300).map { part ->
            Fetch.query(relays, mapOf("kinds" to listOf(7, 6), "authors" to part, "since" to now - 2 * 86_400L, "limit" to 800), 6000)
        }.flatten().distinctBy { it.id }
        val who = HashMap<String, MutableSet<String>>()
        for (a in acts) {
            if (a.kind == 7 && a.content == "-") continue
            a.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1)?.let { who.getOrPut(it) { LinkedHashSet() } += a.pubkey }
        }
        val top = who.entries.sortedByDescending { it.value.size }.take(60).map { it.key }
        if (top.isEmpty()) return emptyList()
        val hidden = muted.value
        val evs = Fetch.query((relays + bigRelays).distinct(), mapOf("ids" to top), 6000).distinctBy { it.id }.filter { e ->
            e.kind == 1 && parentOf(e) == null && e.pubkey !in mine && e.pubkey != me && e.pubkey !in hidden && safe(e) && !mutedText(e.content, e) &&
                !interests.hidden("hiddenPeople", e.pubkey) && e.createdAt > now - 3 * 86_400L
        }
        circleCounts()
        val clean = withoutSpam(evs.filter { inCircle(it.pubkey) }.map { Pick(it, "", 0.0, "") }, mine).map { it.e }
        val chosen = clean.sortedByDescending { who[it.id]?.size ?: 0 }.distinctBy { it.pubkey }.take(3)
        People.want(chosen.flatMap { who[it.id].orEmpty().take(3) } + chosen.map { it.pubkey })
        kotlinx.coroutines.delay(800)
        return chosen.map { e ->
            val names = who[e.id].orEmpty().mapNotNull { People.known.value[it]?.name?.takeIf { n -> n.isNotBlank() } }.take(2)
            val did = if (acts.any { it.kind == 6 && it.tags.any { t -> t.size > 1 && t[0] == "e" && t[1] == e.id } }) "shared" else "liked"
            val reason = when {
                names.isEmpty() -> "People you follow $did this"
                (who[e.id]?.size ?: 0) > names.size -> "${names.joinToString(", ")} and others you follow $did this"
                else -> "${names.joinToString(" and ")} $did this"
            }
            Pick(e, TopicGuess.of(e).firstOrNull() ?: "", 0.0, reason)
        }.also { remember(it.map { p -> p.e }) }
    }

    /** Where you'd got to last time in Feed 3 (the newest post's time then): an "Earlier" line goes there. Kept on this phone. */
    val editionMark = MutableStateFlow(prefs.getLong("editionMark", 0L))
    @Volatile private var editionTop = prefs.getLong("editionMark", 0L)
    /** Seen at the top: remembered for next time (the line on screen now stays where it is). */
    fun editionSeen(newest: Long) { if (newest > editionTop) { editionTop = newest; prefs.edit().putLong("editionMark", newest).apply() } }
    /** A new visit (opening Marble, or back after a while; never a pull): the line moves to where you'd got to. */
    fun editionVisit() { editionMark.value = editionTop }
    /** A story in the box, opened already. */
    fun storyRead(c: TopicCard) = storyOpened(c)

    /** What the people you follow posted in the last two days, their own posts (not replies), from their relays and the big ones. */
    private suspend fun followingPosts(now: Long, cap: Int = 4): List<Event> {
        knowFollows()
        val who = follows.value.orEmpty().toList()
        if (who.isEmpty()) return emptyList()
        val relays = (readRelays() + POST_RELAYS + bigRelays).distinct().take(8)
        return who.chunked(400).map { part ->
            Fetch.query(relays, mapOf("kinds" to listOf(1, Polls.POLL), "authors" to part, "since" to now - 2 * 86_400L, "limit" to 300), 8000)
        }.flatten().filter { parentOf(it) == null }.distinctBy { it.id }.sortedByDescending { it.createdAt }
            .let { l -> val each = HashMap<String, Int>(); l.filter { (each.merge(it.pubkey, 1, Int::plus) ?: 0) <= cap } }.take(if (cap == 4) 300 else 600)
    }

    /** What you like, lightly, in Discover's popular posts: up to a couple of hours earlier for your strongest topics, a little later for your weakest. */
    private fun lift(e: Event) = (((TopicGuess.of(e).maxOfOrNull { interests.of(it) } ?: 0.4) - 0.4) * 4 * 3600).toLong()

    /** Your Special Follow list (kind 10010, the one Jumble keeps), and how many: public names, and private ones if it has any (NIP-44 to yourself). */
    val special = MutableStateFlow<Set<String>>(prefs.getStringSet("special", emptySet())!!.toSet())
    @Volatile private var specialAt = 0L
    private suspend fun specialPeople(): Set<String> {
        if (System.currentTimeMillis() - specialAt < 30 * 60_000L) return special.value
        val me = reader ?: return special.value
        val list = Fetch.query((writeRelays() + readRelays()).distinct().take(8), mapOf("kinds" to listOf(10010), "authors" to listOf(me), "limit" to 3), 6000)
            .maxByOrNull { it.createdAt } ?: return special.value
        val public = list.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }
        val private = list.content.takeIf { it.isNotBlank() && me == this.me }?.let { c -> toSelf(c, encrypt = false) }?.let { j ->
            try { (Json.parse(j) as? List<*>)?.mapNotNull { t -> (t as? List<*>)?.takeIf { it.size > 1 && it[0] == "p" }?.get(1) as? String } } catch (_: Exception) { null }
        } ?: emptyList()
        val all = (public + private).filter { it.length == 64 }.toSet()
        specialAt = System.currentTimeMillis()
        special.value = all
        prefs.edit().putStringSet("special", all).apply()
        return all
    }

    /** What your Special Follow people posted in the last two days (their own posts, not replies). */
    private suspend fun specialPosts(now: Long): List<Event> {
        val who = specialPeople().take(300)
        if (who.isEmpty()) return emptyList()
        return Fetch.query((readRelays() + writeRelays() + POST_RELAYS).distinct().take(8),
            mapOf("kinds" to listOf(1), "authors" to who, "since" to now - 2 * 86_400L, "limit" to 150), 8000)
            .filter { parentOf(it) == null }
    }

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
        // The feed log switched on with a feed already on screen: that list, at once (otherwise only the next load would say what's in it).
        scope.launch { var was = FeedLog.on.value; FeedLog.on.collect { if (it && !was) logPosts("on", feed.value.posts); was = it } }
        // Once a start, whatever the feed does: what your other phones have seen (never before the first screen).
        scope.launch {
            restored.await(); afterPaint(); try { loadTranslations() } catch (_: Exception) {}; knowCircle(); pullSeen(); pullDeviceNames(); pullLater(); if (pullNews()) newsChanged()
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
                    // (The popular list is already curated, and short: it comes back whole, seen or not.)
                    if (!Sources.POPULAR_ONLY && ((!Sources.resumed && !unseen(e.id)) || !forDiscover(e))) return@mapNotNull null
                    if (interests.hidden("hiddenPeople", e.pubkey)) return@mapNotNull null
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
                logPosts("cache", feed.value.posts)
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

    // --- The feed log (when it's on): each list in the order shown, with what its ranking went on --------

    /** Discover's posts, in the order shown. [why]: "load", "pull", "back" (after a while away), "cache" (last time's) or "on" (the log just switched on). */
    private fun logPosts(why: String, posts: List<Pick>) {
        if (!FeedLog.on.value || posts.isEmpty()) return
        scope.launch {
            val follows = follows.value ?: emptySet()
            for (p in posts) {
                val inner = p.e.takeIf { it.kind == 6 }?.let(::reposted) ?: p.e
                val (pics, vids) = Calm.media(inner)
                FeedLog.item(p.e.id, "kind" to "post", "ek" to p.e.kind, "src" to inner.pubkey, "at" to inner.createdAt, "from" to if (p.reason.startsWith("★")) "special" else "pyramid",
                    "why" to p.reason.takeIf { it.isNotEmpty() }, "topics" to TopicGuess.hits(inner).take(3).associate { it.topic to it.strength },
                    "words" to Entities.of(Calm.text(inner)).take(8), "len" to Calm.text(inner).length, "link" to (Calm.link(inner) != null), "pics" to pics, "vids" to vids,
                    "digest" to Calm.digest(inner), "reply" to (parentOf(inner) != null), "repost" to (p.e.kind == 6), "follow" to (inner.pubkey in follows), "likes" to p.likes.takeIf { it > 0 })
            }
            FeedLog.ev("feed", "screen" to "discover", "why" to why, "rv" to FeedLog.RANKER, "prof" to logProfile(),
                "list" to posts.map { p -> mapOf("id" to p.e.id, "lift" to lift(p.e.takeIf { it.kind == 6 }?.let(::reposted) ?: p.e), "sp" to p.reason.startsWith("★").takeIf { it }) })
        }
    }

    /** Today's stories for More stories, in the order shown, each with its score. */
    /** The feed log (when on): a list of stories as a screen showed it (Following's News and Topics). */
    fun logStoryList(screen: String, list: List<TopicCard>) = logStories(screen, list)
    private fun logStories(screen: String, list: List<TopicCard>) {
        if (!FeedLog.on.value || list.isEmpty()) return
        val now = Clock.now()
        for (c in list) FeedLog.item("t:" + c.id, "kind" to "story", "src" to c.sourceId, "srcName" to c.sourceName, "topic" to c.topic, "at" to c.publishedAt,
            "words" to Entities.of(c.title).take(8), "img" to (c.image != null), "disc" to c.discussions.map { mapOf("site" to it.site, "n" to it.comments) })
        FeedLog.ev("feed", "screen" to screen, "rv" to FeedLog.RANKER, "prof" to logProfile(),
            "list" to list.map { c -> mapOf("id" to "t:" + c.id, "sc" to Calm.storyScore(c, interests, now, shownCount(c.id)), "shown" to shownCount(c.id).takeIf { it > 0 }) })
    }

    /** What the ranking knew of you just then: each topic's interest (0 to 1), and the topics you follow. */
    private fun logProfile() = mapOf("i" to TopicGuess.topics.associateWith { interests.of(it) }, "fol" to synchronized(interests) { interests.followed.toList() })

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
    // Your account's notes (notes sync v3, cc.aaro.cosign.notes): packs of kind 30078, NIP-44 to yourself, read on
    // start and on coming back, and written at most every 3 minutes after a change, and on leaving. The old single
    // list (d=marble/later) is still read, and kept current while it fits, for older Marbles.

    val later = Later(prefs).also { it.changed = { laterChanged() } }
    init { NewsSources.load(CosignApp.instance); NewsSources.changed = { newsChanged() }; OnDevice.load() }
    /** What you searched, on this phone only (not synced), 30 days. */
    val searches = Searches(prefs)
    private var laterSending: kotlinx.coroutines.Job? = null

    private fun laterChanged() {
        if (Sources.viewAs != null) return
        if (laterSending?.isActive == true) return
        laterSending = scope.launch { delay(180_000); sendLater() }
    }

    private var answering: kotlinx.coroutines.Job? = null
    /** Marble open: another of your devices setting up can catch up from this one (notes sync, option D). */
    fun answerDevices() {
        if (me == null || Sources.viewAs != null || answering?.isActive == true) return
        answering = scope.launch { notesSync.serve { later.items.value } }
    }

    /** Leaving Marble: what's waiting goes now. */
    fun left() {
        answering?.cancel()
        if (laterSending?.isActive != true) return
        laterSending?.cancel()
        laterSending = scope.launch { sendLater() }
    }

    private val notesNet = object : NotesNet {
        override suspend fun read(tag: String, values: List<String>, until: Long?, since: Long?, limit: Int): List<Stored>? {
            val me = me ?: return null
            val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
            if (relays.isEmpty()) return null
            val f = mutableMapOf<String, Any>("kinds" to listOf(30078), "authors" to listOf(me), "#$tag" to values, "limit" to limit)
            until?.let { f["until"] = it }; since?.let { f["since"] = it }
            return Fetch.query(relays, f, 8000).filter { it.pubkey == me && it.kind == 30078 && it.verify() }.distinctBy { it.id }
                .map { Stored(it.id, it.firstTag("d") ?: "", it.createdAt, toSelf(it.content, encrypt = false)) }
        }
        override suspend fun write(d: String, w: String?, createdAt: Long, content: String): Boolean {
            val me = me ?: return false
            val text = toSelf(content, encrypt = true) ?: return false.also { if (debuggable) android.util.Log.i("CosignSync", "notes not sent: couldn't encrypt") }
            val signed = try { phone.signOwn(Event(me, createdAt, 30078, listOfNotNull(listOf("d", d), w?.let { listOf("w", it) }), text).withId()) } catch (e: Exception) {
                if (debuggable) android.util.Log.i("CosignSync", "notes not sent: ${e.javaClass.simpleName}"); return false
            }
            val to = if (Sources.localOnly) Sources.extra.value else writeRelays()
            return Fetch.publish(to, signed) > 0
        }
        override suspend fun fetchBlob(sha256: String, servers: List<String>): ByteArray? { val me = me ?: return null; return Blossom.fetch((servers + Blossom.mine(me)).distinct(), sha256) }
        override suspend fun uploadBlob(bytes: ByteArray, sha256: String, seed: ByteArray): List<String> { val me = me ?: return emptyList(); return Blossom.upload(Blossom.mine(me), bytes, sha256, seed) }
        override suspend fun deleteBlob(sha256: String, servers: List<String>, seed: ByteArray) = Blossom.delete(servers, sha256, seed)
        // Catch-up between your devices: kind 21078 (ephemeral), signed by a key made for the one message.
        private fun live() = if (Sources.localOnly) Sources.extra.value else writeRelays().take(4)
        override suspend fun shout(w: String, content: String): Boolean {
            val text = toSelf(content, encrypt = true) ?: return false
            val r = java.security.SecureRandom()
            var secret = ByteArray(32).also(r::nextBytes)
            while (!cc.aaro.qrst.core.Secp.isValidSecret(secret)) r.nextBytes(secret)
            val pk = cc.aaro.qrst.core.Secp.publicKey(secret).joinToString("") { "%02x".format(it) }
            val e = Event(pk, Clock.now(), 21078, listOf(listOf("w", w)), text).withId()
            val sig = cc.aaro.qrst.core.Secp.sign(e.id.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), secret, ByteArray(32).also(r::nextBytes))
            secret.fill(0)
            return Fetch.publish(live(), e.copy(sig = sig.joinToString("") { "%02x".format(it) })) > 0
        }
        override fun hear(w: String, onMessage: (String) -> Unit): AutoCloseable? {
            if (me == null) return null
            return Fetch.listen(live(), mapOf("kinds" to listOf(21078), "#w" to listOf(w), "since" to Clock.now() - 5)) { e -> toSelf(e.content, encrypt = false)?.let(onMessage) }
        }
    }
    private val notesSync by lazy {
        NotesSync(notesNet, object : NotesStore {
            override fun get(k: String) = prefs.getString(k, null)
            override fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()
        }, Clock::now, later.items.value)
    }

    /**
     * Your own name and picture, from your relays (where your profile is; the big public ones may not have it),
     * kept with the others on this phone.
     */
    fun knowMe() = scope.launch {
        val me = me ?: return@launch
        People.add(Fetch.query((writeRelays() + readRelays() + listOf("wss://syb.lol", "wss://relay.ditto.pub") + Sources.PROFILES).distinct().take(10),
            mapOf("kinds" to listOf(0), "authors" to listOf(me), "limit" to 3), 6000))
    }

    /**
     * Your profile and follow list where apps look for them. Whenever your relay list changes (and once, to catch up),
     * the newest of each, from every relay known to have them, goes as it is (signed already: no round with your
     * servers) to the list's write relays and the profile indexes. Only the newest, so an old copy never wins.
     * Tried again at the next start if a copy reached fewer than two of them.
     */
    fun spreadIdentity() = scope.launch {
        val me = me ?: return@launch
        val indexes = listOf("wss://purplepag.es", "wss://user.kindpag.es")
        val lookIn = (indexes + Sources.PROFILES + writeRelays() + readRelays()).distinct()
        val list = (Fetch.query(lookIn.take(10), mapOf("kinds" to listOf(10002), "authors" to listOf(me), "limit" to 3), 6000) + listOfNotNull(phone.latest("10002")))
            .filter { it.pubkey == me && it.kind == 10002 && it.verify() }.maxByOrNull { it.createdAt } ?: return@launch
        val listed = list.tags.filter { it.size > 1 && it[0] == "r" && it[1].startsWith("wss://") }
        val writes = listed.filter { it.getOrNull(2) != "read" }.map { it[1].trimEnd('/') }.distinct()
        if (writes.isEmpty()) return@launch
        val key = list.id
        if (prefs.getString("identitySpreadFor", null) == key) return@launch
        val known = (listed.map { it[1].trimEnd('/') } + lookIn + POST_RELAYS).distinct()
        val got = Fetch.query(known.take(14), mapOf("kinds" to listOf(0, 3), "authors" to listOf(me), "limit" to 10), 8000)
        val newest = listOf(0, 3).mapNotNull { k -> got.filter { it.kind == k && it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt } }
        if (newest.isEmpty()) return@launch
        val to = (writes + indexes).distinct()
        val reached = newest.map { e -> Fetch.publish(to, e).also { n -> if (debuggable) android.util.Log.i("CosignSync", "kind ${e.kind} sent to $n of ${to.size} relays") } }
        if (reached.all { it >= writes.size.coerceAtMost(2) }) prefs.edit().putString("identitySpreadFor", key).apply()
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
        interests.noticed(known ?: host)
        if (item.text == host) scope.launch { Preview.fetch(url)?.title?.let { later.retitle(item.id, host, it) } }
        return item
    }

    /** A page opened from search: its title for Later, from the page itself, in the background. */
    fun titlePage(url: String) = scope.launch { Preview.fetch(url)?.title?.let { later.titled(url, it); searches.titled(url, it) } }

    @Volatile private var laterPulledAt = 0L
    /** What you saved on your other devices: coming back to Marble or opening Later, at most once a minute. */
    fun refreshLater() {
        if (System.currentTimeMillis() - laterPulledAt < 60_000L) return
        scope.launch { pullLater() }
    }

    /** [sending]: just before a write, which follows anyway (so no second one is scheduled). */
    private suspend fun pullLater(sending: Boolean = false) {
        laterPulledAt = System.currentTimeMillis()
        if (me == null || Sources.viewAs != null) return
        // Everything once a day; otherwise what's new since the last pull.
        val full = System.currentTimeMillis() - prefs.getLong("notesFull", 0) > 86_400_000L
        val before = later.items.value
        val after = notesSync.pull(before, full) ?: return
        if (full) prefs.edit().putLong("notesFull", System.currentTimeMillis()).apply()
        later.synced(before, after)
        if (debuggable) android.util.Log.i("CosignSync", "notes pulled: ${after.size}${if (notesSync.halted) ", stopped (too many removals)" else ""}${if (notesSync.partial) ", not all pages" else ""}")
        if (notesSync.pending && !sending) laterChanged()
    }

    private suspend fun sendLater() {
        if (me == null || Sources.viewAs != null) return
        val before = later.items.value
        val after = notesSync.flush(before) ?: return Unit.also { if (debuggable) android.util.Log.i("CosignSync", "notes not sent") }
        later.synced(before, after)
        if (debuggable) android.util.Log.i("CosignSync", "notes sent: ${after.size}${if (notesSync.pending) ", some still waiting" else ""}")
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
     * One of your marbles taken off (the trash can): the marker goes to your account within seconds, not the usual half
     * minute, so your other devices drop it too. (Missed, it's sent at the next start: this phone's change is the newer.)
     */
    fun removeMarble(id: String) {
        kept.remove(id)
        if (Sources.viewAs != null) return
        keptSending?.cancel()
        keptSending = scope.launch { delay(2_000); sendKept() }
    }

    /**
     * Today's marble, claimed: its award from the issuer (POST /collect {"id","date"}, NIP-98 signed by you), kept with it,
     * privately (the issuer only signs and returns it; nothing is published). Quietly; tried again on later opens the
     * same UTC day if the issuer isn't there or says no.
     */
    fun claimToday() {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val id = "marble-$today"
        if (kept.all.value[id]?.let { it.here && it.award == null } != true || claiming) return
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

    // Marble keeps your marbles and their awards; what other apps show on your profile (profile_badges) is theirs to decide.

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

    // --- News sources (Account › News sources): kept on your account like device names ------

    private var newsSending: kotlinx.coroutines.Job? = null
    private fun newsChanged() {
        if (Sources.viewAs != null) return
        // What's shown changes at once; the account's copy half a minute after the last change.
        scope.launch { allStories.value = try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { allStories.value } }
        newsSending?.cancel()
        newsSending = scope.launch { delay(30_000); sendNews() }
    }

    private suspend fun pullNews(): Boolean {
        val me = me ?: return false
        if (Sources.viewAs != null) return false
        val relays = if (Sources.localOnly) Sources.extra.value else (writeRelays() + readRelays()).distinct().take(8)
        val got = Fetch.query(relays, mapOf("kinds" to listOf(30078), "authors" to listOf(me), "#d" to listOf(NewsSources.TAG), "limit" to 4), 6000)
            .filter { it.pubkey == me && it.verify() }.maxByOrNull { it.createdAt }
        val before = NewsSources.json()
        val ahead = if (got == null) NewsSources.own.value.isNotEmpty() || NewsSources.off.value.isNotEmpty() else NewsSources.take(toSelf(got.content, encrypt = false) ?: return false)
        if (NewsSources.json() != before) allStories.value = try { TopicFeeds.load(CosignApp.instance) } catch (_: Exception) { allStories.value }
        return ahead
    }

    private suspend fun sendNews() {
        val me = me ?: return
        if (pullNews().not() && NewsSources.own.value.isEmpty() && NewsSources.off.value.isEmpty()) return
        val text = toSelf(NewsSources.json(), encrypt = true) ?: return
        val signed = try { phone.signOwn(Event(me, Clock.now(), 30078, listOf(listOf("d", NewsSources.TAG)), text).withId()) } catch (_: Exception) { return }
        Fetch.publish(if (Sources.localOnly) Sources.extra.value else writeRelays(), signed)
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
        FeedLog.act(null, if (on) "followTopic" else "unfollowTopic", "topic" to topic)
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
    fun moreLike(topic: String, post: Event? = null, key: String? = post?.id) { FeedLog.act(key, "more", "topic" to topic); interests.learn(listOf(topic), 1.0); post?.let { newsletterTaste(it, 1) }; message.value = "More like this from now on." }

    /** You opened a post's link. */
    fun openedLink(post: Event) { FeedLog.open(post.id, "link"); newsletterTaste(post, 1) }

    /** A stranger's newsletter you liked (or didn't): more of them, or fewer. */
    private fun newsletterTaste(e: Event, by: Int) {
        if (e.pubkey in (follows.value ?: emptySet()) || !Calm.digest(e)) return
        interests.newsletters(by)
    }

    /** "Less like this": this one goes, and a little less of its topic. [key]: the post's id, or "t:" and the story's. */
    fun lessLike(topic: String, key: String, post: Event? = null) {
        FeedLog.act(key, "less", "topic" to topic)
        interests.learn(listOf(topic), -0.25)
        post?.let { newsletterTaste(it, -1) }
        hide(key)
        folded(key, "Less like this") { interests.learn(listOf(topic), 0.25); post?.let { newsletterTaste(it, 1) } }
    }

    /**
     * What you've just put away in Following (Not interested, Less like this, Hide…), by its key, with how to take it
     * back: it stays where it was as a slim row with Undo, and nothing moves up to take its place. The next refresh
     * of the list lets it go.
     */
    data class Dismissal(val label: String, val undo: () -> Unit)
    val justDismissed = MutableStateFlow<Map<String, Dismissal>>(emptyMap())
    private fun folded(key: String, label: String, undo: () -> Unit) = justDismissed.update { it + (key to Dismissal(label, undo)) }
    /** Undo on a put-away row: it comes back as it was, and what was learned from it is taken back. */
    fun undoDismiss(key: String) {
        val d = justDismissed.value[key] ?: return
        FeedLog.act(key, "undo")
        d.undo()
        dismissed.update { it - key }
        prefs.edit().putStringSet("dismissed", dismissed.value).apply()
        justDismissed.update { it - key }
    }

    /**
     * Which feed Following is: 1, as it was on 27 Oct (branch feed-1: reaction counts beside the heart), or 2 (no counts
     * in lists; they're a tap in, on the post). A hidden switch in Account › Advanced, so the two can be compared.
     */
    // The user asked to try Feed 3 (27 Oct): this build starts in it, once; the switch takes it back in one tap.
    val feedVersion = MutableStateFlow(prefs.let { p ->
        if (!p.getBoolean("feed3Tried", false)) p.edit().putInt("feedVersion", 3).putBoolean("feed3Tried", true).apply()
        p.getInt("feedVersion", 2)
    }).also { FeedLog.RANKER = "following" + it.value }
    fun setFeedVersion(v: Int) {
        if (v == feedVersion.value) return
        val was = feedVersion.value
        feedVersion.value = v
        prefs.edit().putInt("feedVersion", v).apply()
        FeedLog.RANKER = "following$v"
        FeedLog.ev("feedVersion", "v" to v)
        // Into or out of Feed 3 (a different list): start over.
        if (v == 3 || was == 3) { feed.update { it.copy(posts = emptyList()) }; loadFeed(force = true) }
    }

    /** A post's own link (what keeping it in Notes keeps, and what Share sends): its marble.syb.lol link. */
    fun postLink(e: Event) = marbleLink(e)

    /**
     * The bookmark under a post in Following: kept in Notes (who wrote it, and its words), or, tapped again, not. Keeping
     * is a strong yes for what it's about.
     */
    fun keepPost(e: Event, author: String) {
        val url = postLink(e)
        // (Kept before links were marble.syb.lol ones: under the njump.me link.)
        val old = "https://njump.me/" + nevent(e.id, e.pubkey, e.kind, emptyList())
        later.items.value.firstOrNull { (it.url == url || it.url == old) && it.deleted == null }?.let { FeedLog.act(e.id, "unkeep"); later.remove(it); return }
        FeedLog.act(e.id, "keep", "author" to e.pubkey)
        interests.learn(TopicGuess.of(e).take(2), 1.0)
        later.keep(author, url, e.content.trim().take(2000).ifEmpty { null })
    }

    /** The bookmark on a news story: kept in Notes (its headline and link), or, tapped again, not. */
    fun keepStory(c: TopicCard) {
        later.items.value.firstOrNull { it.url == c.url && it.deleted == null }?.let { FeedLog.act("t:" + c.id, "unkeep"); later.remove(it); return }
        FeedLog.act("t:" + c.id, "keep", "topic" to c.topic, "src" to c.sourceId)
        interests.learn(listOf(storyTopic(c)), 1.0)
        later.savePage(c.url, c.title)
    }

    /** "Not interested" in one story: it goes, and only it (one story says little about its topic or its site). */
    fun notInterested(c: TopicCard) {
        FeedLog.act("t:" + c.id, "notInterested", "topic" to c.topic, "src" to c.sourceId)
        hide("t:" + c.id)
        folded("t:" + c.id, "Not interested") {}
    }

    /** "Fewer stories about" a topic: much less of it from now on (more than Less like this). */
    fun fewerAbout(topic: String, key: String?) {
        FeedLog.act(key, "fewerTopic", "topic" to topic)
        interests.learn(listOf(topic), -0.6)
        key?.let { hide(it); folded(it, "Fewer stories about ${TopicFeeds.name(topic)}") { interests.learn(listOf(topic), 0.6) } }
    }

    /** What a story is about, as finely as its words say (else its feed's topic). */
    fun storyTopic(c: TopicCard): String = TopicGuess.of(Event("", 0, 1, emptyList(), c.title + " " + c.summary)).firstOrNull() ?: c.topic

    /** "Hide": nothing more from this person, or from this site. */
    fun hidePerson(pk: String, from: String? = null) {
        FeedLog.act(from, "hideAuthor", "author" to pk)
        interests.hide("hiddenPeople", pk)
        // From a post in the list: that post folds where it was (with Undo); their others go at the next refresh.
        if (from != null) { hide(from); folded(from, "Hidden ${nameOf(pk)}") { interests.unhide("hiddenPeople", pk) }; return }
        feed.update { f -> f.copy(posts = f.posts.filter { it.e.pubkey != pk }) }
        message.value = "You won't see posts from ${nameOf(pk)} here."
    }
    fun hideSource(c: TopicCard) {
        FeedLog.act("t:" + c.id, "hideSource", "src" to c.sourceId)
        interests.hide("hiddenSources", c.sourceId)
        hide("t:" + c.id)
        folded("t:" + c.id, "Hidden ${c.sourceName}") { interests.unhide("hiddenSources", c.sourceId) }
    }

    /** You opened a story: a little more of its topic (never of its site, from an open alone), and of what it's about. */
    fun opened(c: TopicCard) { FeedLog.open("t:" + c.id, "story"); learnOpened(c) }
    /** You opened what people say about a story ([site]: where): as much as opening it. */
    fun openedComments(c: TopicCard, site: String? = null) { FeedLog.open("t:" + c.id, "comments", "site" to site); learnOpened(c) }
    private fun learnOpened(c: TopicCard) { interests.learn(listOf(c.topic), 0.5); interests.noticed(c.title); if (seen.add("o:" + c.id)) seenChanged() }

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
        FeedLog.act(if (isLink) allStories.value.firstOrNull { it.url == id }?.let { "t:" + it.id } ?: id else id, if (on) "save" else "unsave")
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
    fun search(q: String, author: String? = null) {
        val text = q.trim()
        if (text.isEmpty()) return
        if (found.value?.query == text && found.value?.loading == true && (author == null || found.value?.split?.author == author)) return
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
            // Someone you follow, named in it (or picked from the chips): their posts with the other words, first.
            val split = if (tag != null) null else AuthorQuery.split(text, mine, People.known.value)?.let { s ->
                if (author == null || author == s.author) s else s.copy(author = author, others = (listOf(s.author) + s.others).filter { it != author })
            }
            val byAuthor = java.util.Collections.synchronizedList(ArrayList<Event>())
            val caught = ConcurrentHashMap<String, cc.aaro.cosign.spam.Spam.Why>()
            var first: String? = null
            fun show(loading: Boolean) {
                if (searchGen.get() != gen) return
                val people = SearchRank.sort(hits.values.filter { it.pubkey !in muted.value }, text).take(30)
                people.firstOrNull()?.pubkey?.takeIf { it != first }?.let { pk -> first = pk; mark("first is now ${People.known.value[pk]?.shown ?: ""} ${pk.take(8)}") }
                val posts = synchronized(notes) { notes.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(60) }
                val theirs = synchronized(byAuthor) { byAuthor.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(40) }
                found.value = Found(text, people.map { it.pubkey }, posts.filter { p -> theirs.none { it.id == p.id } }, loading, people.associateBy { it.pubkey }, split, theirs, HashMap(caught))
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
            if (split != null) {
                val words = SearchRank.norm(split.rest).split(' ').filter { it.isNotEmpty() }
                fun has(e: Event, all: Boolean) = SearchRank.norm(e.content + " " + (e.firstTag("title") ?: "")).let { c -> if (all) words.all { it in c } else words.any { it in c } }
                // (From a search relay, at least one of the words: one that ignores search would send everything.)
                fun theirs(got: List<Event>, how: String) {
                    val ok = got.filter { it.pubkey == split.author && (it.kind == 1 || it.kind == 30023) && has(it, how == "latest") }
                    remember(ok); byAuthor += ok.filter { safe(it) }
                    show(true); mark("theirs ($how): ${ok.size}")
                }
                val f = mapOf("kinds" to listOf(1, 30023), "authors" to listOf(split.author), "limit" to 40)
                // Newest first where the relay can (Ditto's own search ranks by relevance, and a busy poster's new post falls
                // past any limit); the others don't know sort:new.
                relayJobs += launch { theirs(Fetch.query(SearchRelays.FAST, f + ("search" to split.rest + " sort:new"), 6000), "newest") }
                relayJobs += launch { theirs(Fetch.query(SearchRelays.SLOW + Sources.extra.value, f + ("search" to split.rest), 4000), "search") }
                // And their latest posts (where they write, and the big relays), looked through here: what no search has indexed yet.
                relayJobs += launch {
                    theirs(Fetch.query((outboxOf(split.author) + bigRelays).map { it.trimEnd('/') }.distinct(), mapOf("kinds" to listOf(1, 30023), "authors" to listOf(split.author), "limit" to 200), 5000), "latest")
                }
            }
            // Posts: the fast relays together, the slow ones on their own.
            val noteFilter: Map<String, Any> = if (tag != null) mapOf("kinds" to listOf(1), "#t" to listOf(tag), "limit" to 40) else mapOf("kinds" to listOf(1, 30023), "search" to text, "limit" to 40)
            for ((relays, wait) in listOf((SearchRelays.FAST + if (tag != null) Sources.GENERAL else emptyList()) to 8000L, SearchRelays.SLOW to 3500L)) relayJobs += launch {
                // Posts and long-form articles (an article counts once, as its newest version).
                val got = Fetch.query(relays + Sources.extra.value, noteFilter, wait).filter { it.kind == 1 || it.kind == 30023 }
                    .sortedByDescending { it.createdAt }.distinctBy { if (it.kind == 30023) "a:" + it.pubkey + ":" + (it.firstTag("d") ?: it.id) else it.id }
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
            // Likely spam out of the ranking (strangers only): manufactured likes, or an author who writes like spam.
            if (!notes.isEmpty()) kotlinx.coroutines.withTimeoutOrNull(6000) { searchSpam(synchronized(notes) { notes.distinctBy { it.id }.toList() }, mine, caught) { show(true) } }
            toEnrich.close(); enricher.join(); redraw.cancel()
            People.want(hits.keys.toList())
            show(false); mark("done")
        }
    }

    /** Search's posts through the spam checks; what's caught goes into [into]'s spam (shown apart, and cued). */
    private suspend fun searchSpam(posts: List<Event>, mine: Set<String>, caught: MutableMap<String, cc.aaro.cosign.spam.Spam.Why>, changed: () -> Unit) = kotlinx.coroutines.coroutineScope {
        val strangers = posts.filter { it.pubkey != me && it.pubkey !in mine && !cc.aaro.cosign.spam.Spam.notSpam(it.pubkey) }.sortedByDescending { it.createdAt }.take(40)
        // Guesses from what someone writes count only for people nobody you follow follows (trust holds up when known).
        val fof = friendsOfFriends()
        fun unknown(pk: String) = (fof[pk] ?: 0) == 0
        if (strangers.isEmpty()) return@coroutineScope
        fun publish() { spam.update { it + caught }; changed() }
        val likes = async { Fetch.query(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol"), mapOf("kinds" to listOf(7, 6, 16), "#e" to strangers.map { it.id }, "limit" to 3000), 4000) }
        val authors = strangers.map { it.pubkey }.distinct().take(25)
        val notes = async { Fetch.query(SearchRelays.FAST + "wss://relay.primal.net", mapOf("kinds" to listOf(1), "authors" to authors, "limit" to 250), 4000) }
        val got = likes.await()
        strangers.forEach { p -> (cc.aaro.cosign.spam.Spam.inflated(p.id, got) ?: cc.aaro.cosign.spam.Spam.advert(p)?.takeIf { unknown(p.pubkey) })?.let { caught[p.id] = it } }
        val byAuthor = notes.await().groupBy { it.pubkey }
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            for (pk in authors.filter(::unknown)) {
                val score = cc.aaro.cosign.spam.Spam.author(CosignApp.instance, pk, byAuthor[pk] ?: strangers.filter { it.pubkey == pk }) ?: continue
                if (score >= cc.aaro.cosign.spam.Spam.AUTHOR_LINE) strangers.filter { it.pubkey == pk }.forEach { caught.putIfAbsent(it.id, cc.aaro.cosign.spam.Spam.authorWhy(score)) }
            }
        }
        if (caught.isNotEmpty()) publish()
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
    /** For each person, how many of the people you follow follow them (the circle's counts, Circle). */
    private suspend fun friendsOfFriends(): Map<String, Int> = circleCounts()

    // --- The trust filter: your circle (Circle) -----------------------------------------------

    /** Account › Only your circle: on by default. */
    val circleOn = MutableStateFlow(prefs.getBoolean("circleOn", true))
    fun setCircleOn(on: Boolean) { circleOn.value = on; prefs.edit().putBoolean("circleOn", on).apply() }
    /** Bumped when the circle changes, so screens filter again. */
    val circleAt = MutableStateFlow(0L)
    @Volatile private var counts: Map<String, Int>? = null
    private val circleFile get() = File(CosignApp.instance.cacheDir, "circle-" + (me?.take(16) ?: "none") + ".json")
    private val circleLock = kotlinx.coroutines.sync.Mutex()

    /** The counts: from this phone's copy (a day at most, and for the same follows), else from your follows' lists. */
    private suspend fun circleCounts(): Map<String, Int> = circleLock.withLock {
        counts?.let { return@withLock it }
        val mine = follows.value ?: return@withLock emptyMap()
        if (mine.isEmpty()) return@withLock emptyMap()
        val f = circleFile
        try {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000L) {
                val o = org.json.JSONObject(f.readText())
                if (o.optInt("follows") == mine.size) {
                    val c = o.getJSONObject("counts"); val m = HashMap<String, Int>()
                    c.keys().forEach { m[it] = c.getInt(it) }
                    counts = m; circleAt.value = System.currentTimeMillis(); return@withLock m
                }
            }
        } catch (_: Exception) {}
        val lists = mine.toList().take(1000).chunked(150).map { part -> scope.async { Fetch.query(Sources.PROFILES.take(3), mapOf("kinds" to listOf(3), "authors" to part), 8000) } }.awaitAll().flatten()
        val m = Circle.counts(lists, mine)
        // Only the people the circle takes in are kept (2 or more), to keep the copy small.
        try { f.writeText(org.json.JSONObject().put("follows", mine.size).put("counts", org.json.JSONObject(m.filterValues { it >= Circle.AT } as Map<*, *>)).toString()) } catch (_: Exception) {}
        counts = m; circleAt.value = System.currentTimeMillis()
        m
    }

    /** Worked out in the background (screens filter once it's ready; until then nothing is hidden). */
    fun knowCircle() { if (counts == null) scope.launch { try { circleCounts() } catch (_: Exception) {} } }

    /** Whether [pk] is in your circle; true while it's not known yet, or with the filter off. */
    fun inCircle(pk: String): Boolean {
        if (!circleOn.value) return true
        val c = counts ?: return true
        return Circle.has(pk, me, follows.value.orEmpty(), c) { cc.aaro.cosign.spam.Spam.notSpam(it) }
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
        // A stranger's post, opened: does its author write like spam?
        val stranger = root.pubkey != me && follows.value?.contains(root.pubkey) != true && !cc.aaro.cosign.spam.Spam.notSpam(root.pubkey)
        val unknown = stranger && (friendsOfFriends()[root.pubkey] ?: 0) == 0
        if (unknown) launch { checkAuthor(root) }
        val got = posts.map { it.id }.chunked(100).map { ids -> async { Fetch.query(relays, mapOf("kinds" to listOf(7, 6, 16, 9735), "#e" to ids), 6000) } }.awaitAll().flatten().distinctBy { it.id }
        (cc.aaro.cosign.spam.Spam.inflated(root.id, got)?.takeIf { !cc.aaro.cosign.spam.Spam.notSpam(root.pubkey) } ?: cc.aaro.cosign.spam.Spam.advert(root).takeIf { unknown })
            ?.let { w -> spam.update { it + (root.id to w) } }
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

    /** "Not spam", from you: [e]'s author isn't marked on this phone again (and the feed log says so). */
    fun notSpam(e: Event) {
        cc.aaro.cosign.spam.Spam.markNotSpam(e.pubkey)
        FeedLog.act(e.id, "notSpam", "author" to e.pubkey, "why" to (spam.value[e.id]?.short ?: ""))
        spam.update { m -> m.filterKeys { id -> note(id)?.pubkey != e.pubkey && id != e.id } }
        circleAt.value = System.currentTimeMillis()
    }

    /** [e]'s author's latest posts, through the spam check; flagged: [e] says so. */
    private suspend fun checkAuthor(e: Event) {
        val notes = Fetch.query((outboxOf(e.pubkey) + POST_RELAYS).distinct().take(6), mapOf("kinds" to listOf(1), "authors" to listOf(e.pubkey), "limit" to 10), 5000)
        val score = kotlinx.coroutines.withContext(Dispatchers.Default) { cc.aaro.cosign.spam.Spam.author(CosignApp.instance, e.pubkey, notes) } ?: return
        if (score >= cc.aaro.cosign.spam.Spam.AUTHOR_LINE) spam.update { it + (e.id to cc.aaro.cosign.spam.Spam.authorWhy(score)) }
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
    /** The newest version of an addressable event (a quoted naddr), from its hints and the usual relays. */
    suspend fun fetchAddr(a: Ref.Addr): Event? {
        val f = mapOf("kinds" to listOf(a.kind), "authors" to listOf(a.pubkey), "#d" to listOf(a.d), "limit" to 2)
        fun newest(l: List<Event>) = l.filter { it.kind == a.kind && it.pubkey == a.pubkey && it.verify() }.maxByOrNull { it.createdAt }
        val asked = (a.relays + POST_RELAYS + Sources.GENERAL).map { it.trimEnd('/') }.distinct().take(8)
        // Not there (the link's hint can be wrong): where its author writes.
        return (newest(Fetch.query(asked, f, 6000))
            ?: outboxOf(a.pubkey).map { it.trimEnd('/') }.filter { it !in asked }.takeIf { it.isNotEmpty() }?.let { newest(Fetch.query(it, f, 5000)) })
            ?.also { remember(listOf(it)) }
    }

    /** A quoted post of any kind (a post, an article, or something from another app), by id. */
    suspend fun fetchQuoted(id: String): Event? {
        note(id)?.let { return it }
        val got = Fetch.query(SearchRelays.FAST + Sources.GENERAL, mapOf("ids" to listOf(id)), 4000).firstOrNull { it.id == id && it.verify() } ?: return null
        remember(listOf(got))
        People.want(listOf(got.pubkey))
        return got
    }

    private val openTargets = ConcurrentHashMap<String, String>()

    /**
     * Where "Open" takes something Marble doesn't draw: the app its client tag names (that app's NIP-89 handler), else a
     * handler for its kind that you, someone you follow or its author published or recommended (never a stranger's: a
     * handler is a redirect), else the client's own site, else its marble.syb.lol page.
     */
    suspend fun openTarget(e: Event): String = openTargets[e.id] ?: kotlinx.coroutines.coroutineScope {
        val named = OtherApps.clientHandler(e)?.let { a -> async { fetchAddr(a) } }
        val trusted = (follows.value.orEmpty() + listOfNotNull(reader, e.pubkey)).toSet()
        val k = e.kind.toString()
        val found = async {
            Fetch.query(POST_RELAYS + Sources.GENERAL, listOf(mapOf("kinds" to listOf(31990), "#k" to listOf(k), "limit" to 50),
                mapOf("kinds" to listOf(31989), "#d" to listOf(k), "authors" to trusted.take(500), "limit" to 50)), 4000).filter { it.verify() }
        }
        OtherApps.open(e, named?.await(), found.await(), trusted)
    }.also { openTargets[e.id] = it }

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
            quoted != null -> "nostr:" + cc.aaro.qrst.core.Bech32.encode("note", quoted.id.hexToBytes())
            else -> link.trim()
        }
        val body = listOf(text.trim(), tail).filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { return null }
        val tags = if (quoted != null) listOf(listOf("q", quoted.id)) else emptyList()
        val signed = actEvent(Event(me, Clock.now(), 1, tags, body).withId()) ?: return null
        if (link.isNotBlank()) FeedLog.act(quoted?.id ?: allStories.value.firstOrNull { it.url == link.trim() }?.let { "t:" + it.id } ?: link.trim(), if (quoted != null) "quote" else "post")
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
            (listOf(parent.pubkey) + parent.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }).distinct().filter { it != me && cc.aaro.qrst.core.isHex32(it) }
                .forEach { add(listOf("p", it)) }
        }
        val signed = actEvent(Event(me, Clock.now(), 1, tags, body).withId(), relays = writeRelays() + inboxOf(parent.pubkey)) ?: return null
        FeedLog.act(parent.id, "reply", "root" to root.takeIf { it != parent.id })
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
        FeedLog.act(n.id, "report", "why" to reason)
        // Gone from Discover, the author for a while; what you like isn't changed by a report.
        notInterested(n, learn = false)
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
        FeedLog.act(note.id, if (emoji == "+") "like" else "react", "emoji" to emoji.takeIf { it != "+" }, "first" to first)
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

    /** The emojis a long press on the heart offers, as Marble Web does: your four most-used (besides the usual ones), then the usual six. */
    fun topEmojis(): List<String> = (emojiUse.entries.sortedByDescending { it.value }.map { it.key }.take(3) + USUAL_EMOJIS).distinct().take(7)

    // Counts under posts in a list (reactions with counts, as Jumble): each post on screen asks, and they're fetched together.
    private val wantCounts = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val counted = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var countBatch: kotlinx.coroutines.Job? = null
    fun wantCounts(id: String) {
        if (id in counted || !wantCounts.add(id)) return
        if (countBatch?.isActive == true) return
        countBatch = scope.launch {
            delay(600)
            val ids = synchronized(wantCounts) { wantCounts.toList().also { wantCounts.clear() } }.filter { counted.add(it) }
            if (ids.isEmpty()) return@launch
            val got = ids.chunked(100).map { part -> async { Fetch.query(listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol"),
                mapOf("kinds" to listOf(1, 6, 7, 16), "#e" to part, "limit" to 4000), 5000) } }.awaitAll().flatten().distinctBy { it.id }
            val byTarget = HashMap<String, MutableList<Event>>()
            for (r in got) {
                val target = if (r.kind == 1) parentOf(r) else r.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1)
                if (target != null && target in ids) byTarget.getOrPut(target) { ArrayList() } += r
            }
            engagement.update { m ->
                m + ids.associateWith { id ->
                    val rs = byTarget[id].orEmpty()
                    val likes = rs.filter { it.kind == 7 && it.content != "-" }.distinctBy { it.pubkey }
                    val emojis = likes.groupingBy { it.content.ifBlank { "+" } }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
                    val had = m[id] ?: Engagement()
                    had.copy(replies = maxOf(had.replies, rs.count { it.kind == 1 }), likes = maxOf(had.likes, likes.size),
                        reposts = maxOf(had.reposts, rs.count { it.kind == 6 || it.kind == 16 }), emojis = if (likes.size >= had.likes) emojis else had.emojis)
                }
            }
        }
    }

    /** The posts you reposted from here (this phone), so the button shows it. */
    val reposted = MutableStateFlow(prefs.getStringSet("reposted", emptySet())!!.toSet())

    /** A repost (kind 6): the post, as it is, shared with everyone who follows you. Public; the button asks first. */
    fun repost(note: Event) {
        val me = me ?: return
        if (note.id in reposted.value) return
        FeedLog.act(note.id, "repost")
        reposted.update { (it + note.id).toList().takeLast(500).toSet() }
        prefs.edit().putStringSet("reposted", reposted.value).apply()
        scope.launch {
            val ev = Event(me, Clock.now(), 6, listOf(listOf("e", note.id, ""), listOf("p", note.pubkey), listOf("k", note.kind.toString())), note.toJson()).withId()
            if (!act(ev)) { reposted.update { it - note.id }; prefs.edit().putStringSet("reposted", reposted.value).apply() }
        }
    }

    /** Your reactions to pages (the news among your people's posts): its address → the emoji. */
    val pageReactions = MutableStateFlow<Map<String, String>>(try {
        org.json.JSONObject(prefs.getString("pageReactions", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    } catch (_: Exception) { emptyMap() })

    /** A reaction to a web page (kind 17, NIP-25's reaction to a site): a news story, say. */
    fun reactToPage(url: String, emoji: String) {
        val me = me ?: return
        val before = pageReactions.value[url]
        FeedLog.act(null, "react", "url" to url, "emoji" to emoji)
        pageReactions.update { (it + (url to emoji)).entries.toList().takeLast(500).associate { e -> e.key to e.value } }
        prefs.edit().putString("pageReactions", org.json.JSONObject(pageReactions.value).toString()).apply()
        scope.launch {
            if (!act(Event(me, Clock.now(), 17, listOf(listOf("r", url), listOf("k", "web")), emoji).withId())) {
                pageReactions.update { if (before == null) it - url else it + (url to before) }
                prefs.edit().putString("pageReactions", org.json.JSONObject(pageReactions.value).toString()).apply()
            }
        }
    }

    // The usual six, the ones you use: like, love, haha, sad, eyes, the monocle. (Others' 😮 or 😡 still show as they are.)
    private val USUAL_EMOJIS = listOf("👍", "❤️", "😂", "🥲", "👀", "🫡", "🫂")

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

    // --- Polls (NIP-88, Polls) ------------------------------------------------------------------

    /** The votes counted for each poll (by its id), as last fetched; and what you chose. */
    val polls = MutableStateFlow<Map<String, Polls.Tally>>(emptyMap())

    suspend fun pollVotes(p: Polls.Poll) {
        val relays = (p.relays.take(4) + POST_RELAYS + readRelays().take(3)).distinct()
        val got = Fetch.query(relays, mapOf("kinds" to listOf(Polls.VOTE), "#e" to listOf(p.id), "limit" to 2000), 6000).distinctBy { it.id }.filter { it.verify() }
        polls.update { it + (p.id to Polls.tally(p, got)) }
    }

    /** Your vote: to the poll's relays (where it asks votes to go) and yours. Public, like a reply. */
    fun vote(p: Polls.Poll, options: List<String>) {
        val me = me ?: return
        if (options.isEmpty()) return
        scope.launch {
            val ev = Event(me, Clock.now(), Polls.VOTE, Polls.voteTags(p, options), "").withId()
            if (actEvent(ev, relays = (p.relays.take(4) + writeRelays()).distinct()) != null) {
                polls.update { m -> val t = m[p.id]; val choices = (t?.choices ?: emptyMap()) + (me to options)
                    val counts = HashMap<String, Int>(); choices.values.flatten().forEach { counts[it] = (counts[it] ?: 0) + 1 }
                    m + (p.id to Polls.Tally(counts, choices.size, choices)) }
            }
        }
    }

    // --- Pinned posts (kind 10001): first on a profile; yours pinned and unpinned from a post's ⋯ ---------

    /** Your pinned posts' ids, as last read or written (the profile's Unpin and the menu's label follow it). */
    val myPins = MutableStateFlow<List<String>>(prefs.getString("myPins", null)?.split(',')?.filter { it.length == 64 } ?: emptyList())

    /** [pk]'s pinned posts, newest pin first (their newest list; the posts from their relays and the big ones). */
    suspend fun pinnedOf(pk: String): List<Event> {
        val relays = (outboxOf(pk).take(4) + POST_RELAYS + readRelays().take(4)).distinct()
        val list = Fetch.query((relays + Sources.PROFILES.take(2)).distinct(), mapOf("kinds" to listOf(10001), "authors" to listOf(pk), "limit" to 3), 6000)
            .filter { it.pubkey == pk && it.kind == 10001 }.maxByOrNull { it.createdAt } ?: return emptyList()
        val ids = list.tags.filter { it.size > 1 && it[0] == "e" && it[1].length == 64 }.map { it[1] }.distinct().reversed()
        if (pk == me) { myPins.value = ids; prefs.edit().putString("myPins", ids.joinToString(",")).apply() }
        if (ids.isEmpty()) return emptyList()
        val got = Fetch.query(relays, mapOf("ids" to ids.take(20)), 6000).filter { it.pubkey == pk }.associateBy { it.id }
        return ids.mapNotNull { got[it] }.also { remember(it) }
    }

    /** Your post [e] pinned to your profile (or not): from your newest list anywhere, the rest kept as it is. */
    fun pin(e: Event, on: Boolean) {
        val me = me ?: return
        if (e.pubkey != me) return
        scope.launch {
            val list = newest(10001, null, fetch = true)
            val old = list?.tags ?: emptyList()
            val has = old.any { it.size > 1 && it[0] == "e" && it[1] == e.id }
            if (has == on) return@launch
            val tags = if (on) old + listOf(listOf("e", e.id)) else old.filterNot { it.size > 1 && it[0] == "e" && it[1] == e.id }
            val at = maxOf(Clock.now(), (list?.createdAt ?: 0) + 1)
            if (act(Event(me, at, 10001, tags, list?.content ?: "").withId())) {
                val ids = tags.filter { it.size > 1 && it[0] == "e" }.map { it[1] }.reversed()
                myPins.value = ids; prefs.edit().putString("myPins", ids.joinToString(",")).apply()
                message.value = if (on) "Pinned to your profile" else "Unpinned"
            }
        }
    }

    /**
     * [pk] into (or out of) your Special Follow list (kind 10010, the one Jumble keeps): started from your newest list
     * anywhere, its other people and private part kept as they are.
     */
    fun special(pk: String, on: Boolean) {
        val me = me ?: return
        if (pk in busy.value || pk == me) return
        busy.update { it + pk }
        scope.launch {
            try {
                val list = newest(10010, null, fetch = true)
                if (list == null && !on) return@launch
                val old = list?.tags ?: emptyList()
                val public = old.any { it.size > 1 && it[0] == "p" && it[1] == pk }
                // Someone kept privately: taken out of the private part (which only you can read).
                val private = list?.content?.takeIf { it.isNotBlank() }?.let { toSelf(it, encrypt = false) }
                    ?.let { j -> try { (Json.parse(j) as? List<*>)?.mapNotNull { t -> (t as? List<*>)?.map { it.toString() } } } catch (_: Exception) { null } }
                val inPrivate = private?.any { it.size > 1 && it[0] == "p" && it[1] == pk } == true
                if (on == (public || inPrivate)) return@launch
                val tags = if (on) old + listOf(listOf("p", pk)) else old.filterNot { it.size > 1 && it[0] == "p" && it[1] == pk }
                val content = if (!on && inPrivate) toSelf(Json.stringify(private!!.filterNot { it.size > 1 && it[0] == "p" && it[1] == pk }), encrypt = true) ?: return@launch
                    else list?.content ?: ""
                val at = maxOf(Clock.now(), (list?.createdAt ?: 0) + 1)
                if (act(Event(me, at, 10010, tags, content).withId())) {
                    special.update { if (on) it + pk else it - pk }
                    specialAt = System.currentTimeMillis()
                    prefs.edit().putStringSet("special", special.value).apply()
                }
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

    // --- Translating on the phone (OnDevice, PostLanguage) ------------------------------------------

    /** A post not in a language you read, not translated yet: from [from] once its language is downloaded, or ask which of [likeliest]. */
    data class Offer(val from: String?, val likeliest: List<String>)
    val offers = MutableStateFlow<Map<String, Offer>>(emptyMap())
    private val looked = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The language you picked for someone's posts when the guess was unsure. */
    private val authorLang by lazy { HashMap<String, String>().apply {
        prefs.getString("authorLang", null)?.let { s -> try { val o = org.json.JSONObject(s); o.keys().forEach { put(it, o.getString(it)) } } catch (_: Exception) {} }
    } }

    /** A post on screen: translated at once if it isn't in a language you read and its language is on the phone; else offered. */
    fun lookAt(e: Event) {
        if (e.id in translations.value || !looked.add(e.id) || e.pubkey == me) return
        scope.launch(Dispatchers.Default) {
            try {
                val text = e.content
                val cleaned = PostLanguage.clean(text)
                val known = PostLanguage.tagged(e) ?: synchronized(authorLang) { authorLang[e.pubkey] }
                val guess = if (known != null) PostLanguage.Guess(known, listOf(known)) else if (!PostLanguage.guessable(cleaned)) return@launch
                    else PostLanguage.decide(OnDevice.candidates(cleaned))
                val reads = OnDevice.reads()
                val from = guess.lang
                if (from == null) {
                    // Unsure, and maybe in a language you read: offered only when none of the likeliest is yours.
                    val likely = guess.likeliest.filter { OnDevice.supported(it) }
                    if (likely.isNotEmpty() && likely.none { it in reads }) offers.update { it + (e.id to Offer(null, likely)) }
                    return@launch
                }
                if (from in reads || !OnDevice.supported(from)) return@launch
                val out = OnDevice.translate(text, from, download = false)
                if (out != null) keepTranslation(e.id, Translated(out, from)) else offers.update { it + (e.id to Offer(from, listOf(from))) }
            } catch (x: Exception) { if (x is kotlinx.coroutines.CancellationException) throw x }
        }
    }

    /** Translate [e] from [from] (downloading that language once, if needed); [pickedFor]: remember it for this author. */
    fun translateFrom(e: Event, from: String, remember: Boolean) {
        if (remember) synchronized(authorLang) { authorLang[e.pubkey] = from; prefs.edit().putString("authorLang", org.json.JSONObject(authorLang as Map<*, *>).toString()).apply() }
        offers.update { it + (e.id to Offer(from, listOf(from))) }
        scope.launch {
            val out = OnDevice.translate(e.content, from, download = true)
            if (out != null) { keepTranslation(e.id, Translated(out, from)); offers.update { it - e.id } }
            else message.value = "Couldn't translate that right now."
        }
    }

    private fun keepTranslation(id: String, t: Translated) {
        translations.update { (it + (id to t)).entries.toList().takeLast(500).associate { e -> e.key to e.value } }
        offers.update { it - id }
        try { translationsFile.writeText(Json.stringify(translations.value.mapValues { (_, v) -> mapOf("text" to v.text, "from" to v.from) })) } catch (_: Exception) {}
    }

    private fun loadTranslations() {
        val m = try { Json.parse(translationsFile.readText()) as? Map<*, *> } catch (_: Exception) { null } ?: return
        translations.value = m.entries.mapNotNull { (k, v) -> val x = v as? Map<*, *> ?: return@mapNotNull null; (k as String) to Translated(x["text"] as? String ?: return@mapNotNull null, x["from"] as? String) }.toMap()
    }


    /** People whose notes you said you're not interested in, until when (ms): Discover leaves them out for a while. */
    val snoozed = MutableStateFlow(prefs.getStringSet("snoozed", emptySet())!!.mapNotNull { s ->
        s.substringAfter(':').toLongOrNull()?.takeIf { it > System.currentTimeMillis() }?.let { s.substringBefore(':') to it } }.toMap())

    /** Not this note, and a little less like it: the same author is left out of Discover for three days. */
    fun notInterested(e: Event, learn: Boolean = true) {
        if (learn) FeedLog.act(e.id, "notint", "author" to e.pubkey)
        if (learn) { interests.learn(TopicGuess.of(e), -0.25); newsletterTaste(e, -1) }
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
        FeedLog.act(null, "mute", "author" to pk)
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
