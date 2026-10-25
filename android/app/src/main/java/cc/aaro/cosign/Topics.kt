package cc.aaro.cosign

import android.content.Context
import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln

// Discover's second source: link cards from news sites and blogs, sorted into twelve everyday
// topics and rebuilt every 30 minutes (sybenx/topic-feeds, served from GitHub Pages). Nostr alone
// gives about 20 good posts a day; these make Discover feel like a front page. What you like and
// open, which topics you follow and what you've seen are learned and kept on this phone only.

/** A link card: a story on a site, to open there. */
data class TopicCard(
    val id: String, val url: String, val title: String, val summary: String, val image: String?,
    val sourceId: String, val sourceName: String, val publishedAt: Long, val topic: String,
    /** Where people are talking about it (Hacker News, Stacker News, Lobsters…), apart from the article itself. */
    val discussions: List<Discussion> = emptyList(),
) {
    fun toMap() = mapOf("id" to id, "url" to url, "title" to title, "summary" to summary, "image" to image, "source" to sourceId, "sourceName" to sourceName, "at" to publishedAt, "topic" to topic,
        "discussions" to discussions.map { mapOf("url" to it.url, "site" to it.site, "comments" to it.comments) })
    companion object {
        fun fromMap(x: Any?): TopicCard? {
            val m = x as? Map<*, *> ?: return null
            return TopicCard(m["id"] as? String ?: return null, m["url"] as? String ?: return null, m["title"] as? String ?: return null, m["summary"] as? String ?: "",
                m["image"] as? String, m["source"] as? String ?: "", m["sourceName"] as? String ?: "", m["at"] as? Long ?: 0, m["topic"] as? String ?: return null,
                Discussion.list(m["discussions"], m["url"] as String))
        }
    }
}

/** A discussion of a story: the page, the site, and how many comments when the feed says. */
data class Discussion(val url: String, val site: String, val comments: Long? = null) {
    companion object {
        /** From a feed item's "discussions" (or the older single "discussion"); not the article's own page. */
        fun list(x: Any?, article: String, single: Any? = null): List<Discussion> =
            ((x as? List<*>)?.mapNotNull { d -> (d as? Map<*, *>)?.let { m -> Discussion((m["url"] as? String)?.takeIf { it.startsWith("https://") } ?: return@let null, m["site"] as? String ?: "", m["comments"] as? Long) } }
                ?.ifEmpty { null } ?: listOfNotNull((single as? String)?.takeIf { it.startsWith("https://") }?.let { Discussion(it, siteOf(it)) }))
                .filter { it.url != article }.distinctBy { it.url }
        private fun siteOf(url: String) = when {
            "news.ycombinator.com" in url -> "Hacker News"; "stacker.news" in url -> "Stacker News"; "lobste.rs" in url -> "Lobsters"
            else -> try { java.net.URI(url).host.removePrefix("www.") } catch (_: Exception) { "" }
        }
    }
}

data class Topic(val id: String, val name: String)

object TopicFeeds {
    private const val INDEX = "https://sybenx.github.io/topic-feeds/index.json"
    private val http by lazy { Fetch.http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    @Volatile var topics: List<Topic> = emptyList(); private set
    /** What to call a topic. The one about Nostr apps goes by what they are. */
    fun name(id: String) = if (id == "nostr") "Open social apps" else topics.firstOrNull { it.id == id }?.name ?: NAMES[id] ?: id
    /** The names as the site gives them, for before it has been read. */
    private val NAMES = mapOf("ai" to "AI and agents", "games" to "Games and game making", "bitcoin" to "Bitcoin", "software" to "Building software",
        "privacy" to "Privacy and security", "gadgets" to "Phones and gadgets", "selfhosting" to "Self-hosting", "food" to "Food",
        "art" to "Art and design", "photos" to "Photos and places", "news" to "General news")

    /** All the cards, from disk if fetched in the last 30 minutes (or [cachedOnly]), else from the site. */
    suspend fun load(context: Context, cachedOnly: Boolean = false): List<TopicCard> = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "topic-feeds.json")
        val fresh = System.currentTimeMillis() - file.lastModified() < 30 * 60_000L
        if (file.exists() && (fresh || cachedOnly)) parse(file.readText())?.let { return@withContext it }
        if (cachedOnly) return@withContext emptyList()
        val indexText = get(INDEX) ?: return@withContext (if (file.exists()) parse(file.readText()) else null) ?: emptyList()
        val list = ((Json.parse(indexText) as? Map<*, *>)?.get("topics") as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        val bodies = coroutineScope { list.map { t -> async { (t["url"] as? String)?.let { get(it) } } }.awaitAll() }
        // Kept as the site sent it: the index, then each topic's file.
        val text = org.json.JSONArray(listOf(indexText) + bodies.filterNotNull()).toString()
        try { file.writeText(text) } catch (_: Exception) {}
        parse(text) ?: emptyList()
    }

    private fun get(url: String): String? = try {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) { null }

    private fun parse(text: String): List<TopicCard>? {
        val parts = (Json.parse(text) as? List<*>)?.mapNotNull { (it as? String)?.let { b -> Json.parse(b) as? Map<*, *> } } ?: return null
        val index = parts.firstOrNull() ?: return null
        topics = (index["topics"] as? List<*>)?.mapNotNull { t -> (t as? Map<*, *>)?.let { m -> Topic(m["id"] as? String ?: return@let null, m["name"] as? String ?: "") } } ?: topics
        return parts.drop(1).flatMap { (it["items"] as? List<*>) ?: emptyList() }.mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val src = m["source"] as? Map<*, *> ?: return@mapNotNull null
            TopicCard(m["id"] as? String ?: return@mapNotNull null, (m["url"] as? String)?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null,
                (m["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null, (m["summary"] as? String)?.trim().orEmpty(),
                (m["image"] as? String)?.takeIf { it.startsWith("https://") }, src["id"] as? String ?: "", src["name"] as? String ?: "",
                (m["published_at"] as? Long) ?: (m["first_seen"] as? Long) ?: 0L, m["topic"] as? String ?: return@mapNotNull null,
                Discussion.list(m["discussions"], m["url"] as String, m["discussion"]))
        }
            // The same article from several feeds: one card, with every place it's discussed.
            .groupBy { it.id }.values.map { same -> same[0].copy(discussions = same.flatMap { it.discussions }.distinctBy { it.url }) }
    }
}

/** Which of the twelve topics a post is about, from its words and hashtags (the mock's lists, keyed like the topic feeds). */
object TopicGuess {
    private class T(val id: String, words: List<String>, val tags: Set<String>, val q: List<String> = emptyList()) {
        /** Whole words: one word looked up, a phrase found with spaces round it (so "game" isn't in "games"). */
        val terms = words.map { norm(it) }
    }
    /**
     * Everyday words that say little on their own ("phone", "game", "dinner", "war"): half as much as a
     * word only the topic uses. A label takes a hashtag, or words adding up to two of the topic's own.
     */
    private val WEAK = setOf("agent", "agents", "gemini", "developer", "developers", "coding", "terminal", "rust", "swift", "python", "linux",
        "nas", "relay", "relays", "primal", "outbox", "nip", "nips", "lightning", "mining", "miners",
        "security", "leak", "leaked", "breach", "hacked", "exploit", "vpn", "tor",
        "food", "dinner", "lunch", "breakfast", "coffee", "cake", "soup", "bread", "roast",
        "photo", "photos", "travel", "beach", "mountains", "sunset", "sunrise", "landscape",
        "game", "games", "steam", "unity", "unreal", "phone", "hardware", "keyboard", "apple", "android", "pixel",
        "art", "design", "designer", "sketch", "drawing", "painting",
        "government", "politics", "election", "regulation", "war", "parliament", "president", "minister")
    private fun norm(s: String) = NONWORD.replace(s.lowercase(), " ").trim()
    private val NONWORD = Regex("[^\\p{L}\\p{N}]+")
    private val ALL = listOf(
        T("ai", listOf("ai", "llm", "llms", "claude", "chatgpt", "gpt", "openai", "anthropic", "agent", "agents", "agentic", "vibe coding", "vibecoding", "qwen", "ollama", "gemini", "deepseek", "local model", "machine learning"),
            setOf("ai", "llm", "vibecoding", "aiagents", "artificialintelligence", "machinelearning"), listOf("AI", "LLM", "vibe coding")),
        T("software", listOf("programming", "programmer", "developer", "developers", "github", "open source", "opensource", "coding", "javascript", "typescript", "rust", "python", "kotlin", "swift", "compiler", "codebase", "pull request", "linux", "terminal"),
            setOf("programming", "dev", "opensource", "coding", "developer", "rust", "python", "javascript", "foss", "linux"), listOf("programming", "open source")),
        T("selfhosting", listOf("self-host", "self-hosted", "self-hosting", "selfhost", "selfhosted", "selfhosting", "homelab", "umbrel", "start9", "docker", "vps", "raspberry pi", "nas", "home server", "proxmox"),
            setOf("selfhosting", "selfhosted", "homelab", "umbrel"), listOf("self-hosting", "homelab")),
        T("nostr", listOf("relay", "relays", "nip", "nips", "amethyst", "damus", "primal", "ditto", "blossom", "zapstore", "outbox"), setOf("nostrdev", "asknostr", "nostrapps"), listOf("nostr client", "relay")),
        T("bitcoin", listOf("bitcoin", "btc", "sats", "satoshi", "lightning", "coldcard", "mining", "miners", "hodl", "self-custody", "ecash", "cashu"),
            setOf("bitcoin", "btc", "plebchain", "lightning", "cashu", "ecash"), listOf("bitcoin")),
        T("privacy", listOf("privacy", "surveillance", "security", "leak", "leaked", "breach", "hacked", "encryption", "encrypted", "vpn", "tor", "grapheneos", "spyware", "malware", "exploit", "vulnerability", "censorship", "kyc"),
            setOf("privacy", "security", "cybersecurity", "surveillance", "infosec", "censorship"), listOf("privacy", "security")),
        T("food", listOf("food", "cooking", "recipe", "roast", "dinner", "lunch", "breakfast", "dessert", "coffee", "bbq", "baking", "bread", "pizza", "steak", "soup", "cake"),
            setOf("foodstr", "food", "cooking", "coffeechain", "dessertstr", "zapcooking", "recipe", "baking"), listOf("foodstr", "recipe")),
        T("photos", listOf("photography", "photo", "photos", "sunrise", "sunset", "landscape", "mountains", "beach", "scenery", "cityscape", "skyline", "travel", "hiking"),
            setOf("photography", "photostr", "cityscapes", "travel", "landscape", "nature", "sunrise", "sunset", "streetphotography"), listOf("photography", "travel")),
        T("games", listOf("game", "games", "gaming", "gamedev", "game dev", "indie game", "unity", "godot", "unreal", "minecraft", "steam", "level design", "webgl"),
            setOf("gaming", "gamedev", "indiedev", "games", "videogames"), listOf("gamedev", "indie game")),
        T("gadgets", listOf("apple", "iphone", "android", "pixel", "macbook", "ipad", "app store", "gadget", "hardware", "keyboard", "phone"),
            setOf("apple", "android", "tech", "gadgets", "hardware"), listOf("iphone", "android")),
        T("art", listOf("art", "artist", "drawing", "illustration", "painting", "design", "designer", "sketch", "pixel art", "pixelart"),
            setOf("art", "artstr", "design", "illustration", "pixelart", "digitalart"), listOf("art", "pixel art")),
        T("news", listOf("government", "politics", "election", "regulation", "war", "parliament", "president", "minister"), setOf("politics", "news"), listOf("free speech", "government")),
    )
    private val NOISE = Regex("nostr:\\S+|https?://\\S+")

    /** One topic a post is about, and how clearly: a hashtag counts 1, each different word 0.5 (an everyday one 0.25), up to 1.5. */
    data class Hit(val topic: String, val strength: Double)

    /**
     * The topics a post is about, clearest first. [strict] (what the feed labels posts with): a hashtag
     * the text bears out, or words adding up to two of the topic's own; one passing word isn't enough.
     * Learning from what you like isn't strict: a like says more than a label does.
     */
    fun hits(e: Event, strict: Boolean = false): List<Hit> {
        val text = " " + norm(textOf(e)) + " "
        val words = text.split(' ').toHashSet()
        val tags = e.tags.filter { it.size > 1 && it[0] == "t" }.map { it[1].lowercase() }.toSet()
        return ALL.mapNotNull { t ->
            val byTag = t.tags.any { it in tags }
            val found = t.terms.filter { if (' ' in it) text.contains(" $it ") else it in words }
            val said = found.sumOf { if (it in WEAK) 0.25 else 0.5 }.coerceAtMost(1.5)
            // In the feed a hashtag counts only if the post also says something of the topic (hashtags get stuffed).
            val tagged = byTag && (!strict || found.isNotEmpty())
            if (tagged || (if (strict) said >= 1.0 else found.isNotEmpty())) Hit(t.id, (if (tagged) 1.0 else 0.0) + said) else null
        }.sortedByDescending { it.strength }
    }

    /** The topics a post is about, strongest first: a hashtag counts more than a word. */
    fun of(e: Event): List<String> = hits(e).map { it.topic }

    /** What a post says, for matching: an article's title and summary too; no links or mentions. */
    fun textOf(e: Event): String {
        val s = if (e.kind == 30023) listOf("title", "summary").mapNotNull { k -> e.tags.firstOrNull { it.size > 1 && it[0] == k }?.get(1) }.joinToString("\n") + "\n" + e.content.take(3000) else e.content
        return NOISE.replace(s, " ")
    }

    /** What to search for, to find posts on a topic (relays that search), and its hashtags. */
    fun queries(topic: String) = ALL.firstOrNull { it.id == topic }?.q ?: emptyList()
    fun tags(topic: String) = ALL.firstOrNull { it.id == topic }?.tags ?: emptySet()
    val topics get() = ALL.map { it.id }
}

/**
 * What you care about, worked out on this phone and nowhere else: topic weights learned from what
 * you like and open, the topics you follow, and what you've said you want less of (a topic, a site).
 * A copy goes to your own account encrypted to yourself, so your other phones have it too; no
 * server reads or ranks it. [changed]: it changed here (time to save that copy).
 */
class Interests(private val prefs: android.content.SharedPreferences, private val changed: () -> Unit = {}) {
    private val weights = HashMap<String, Double>()
    val followed: MutableSet<String> = LinkedHashSet()
    private val lessTopic = HashMap<String, Int>()
    private val lessSource = HashMap<String, Int>()
    /** When it last changed, in seconds: the newer copy wins, this phone's or your account's. */
    @Volatile var at = 0L; private set
    /** Fields this app doesn't know (another app's, or a later version's): kept and written back as they were. */
    private var extra = org.json.JSONObject()
    val seeded get() = prefs.getBoolean("interestsSeeded", false)

    init { prefs.getString("interests", null)?.let { try { read(org.json.JSONObject(it)) } catch (_: Exception) {} } }

    @Synchronized fun learn(topics: List<String>, by: Double) {
        if (topics.isEmpty()) return
        topics.take(2).forEachIndexed { i, t -> weights[t] = ((weights[t] ?: 0.0) + by / (i + 1)).coerceAtLeast(0.0) }
        save()
    }
    /** Learned in one go, from what you liked before up to [likesTo] (seconds): the 90 days at first, then what's new since. */
    @Synchronized fun seed(votes: Map<String, Double>, likesTo: Long? = null) {
        votes.forEach { (t, v) -> weights[t] = (weights[t] ?: 0.0) + v }
        likesTo?.let { extra.put("likesTo", it) }
        prefs.edit().putBoolean("interestsSeeded", true).apply(); save()
    }
    /**
     * Likes up to this time (seconds) are counted already: the next catch-up reads only newer ones. Shared with
     * discover.syb.lol, so a like is counted once whichever app sees it first. Null: never caught up.
     */
    val likesTo: Long? @Synchronized get() = extra.optLong("likesTo", 0).takeIf { it > 0 }
    /** A like made here, learned at once: counted, as far as the next catch-up is concerned, if likes were caught up to now. */
    @Synchronized fun liked(topics: List<String>, caughtUp: Boolean) {
        if (caughtUp) extra.put("likesTo", System.currentTimeMillis() / 1000)
        learn(topics, 1.0)
    }
    @Synchronized fun follow(topic: String, on: Boolean) { if (on) { followed += topic; lessTopic -= topic } else followed -= topic; save() }
    @Synchronized fun lessOfTopic(topic: String) { lessTopic[topic] = (lessTopic[topic] ?: 0) + 1; followed -= topic; save() }
    @Synchronized fun lessOfSource(source: String) { lessSource[source] = (lessSource[source] ?: 0) + 1; save() }

    /** Hidden for good ("Hide" in a card's ⋯): someone's posts, or a site's stories. In the shared profile, so every phone hides them. */
    @Synchronized fun hide(kind: String, id: String) {
        val list = extra.optJSONArray(kind) ?: org.json.JSONArray().also { extra.put(kind, it) }
        if ((0 until list.length()).none { list.optString(it) == id }) { list.put(id); save() }
    }
    @Synchronized fun hidden(kind: String, id: String) = extra.optJSONArray(kind)?.let { l -> (0 until l.length()).any { l.optString(it) == id } } == true

    /** 0..1 for a topic: followed is the most; learned weight relative to your strongest; each "less" halves it. */
    @Synchronized fun of(topic: String?): Double {
        if (topic == null) return 0.25
        val max = weights.values.maxOrNull()?.takeIf { it > 0 }
        val learned = if (max == null) 0.4 else 0.15 + 0.85 * ln(1 + (weights[topic] ?: 0.0)) / ln(1 + max)
        val base = if (topic in followed) 1.0 else learned
        return base / (1 shl (lessTopic[topic] ?: 0).coerceAtMost(4))
    }
    @Synchronized fun sourcePenalty(source: String) = 1.0 / (1 shl (lessSource[source] ?: 0).coerceAtMost(4))

    /** All of it, as the copy on your account holds it (before it's encrypted). */
    @Synchronized fun toJson(): String = org.json.JSONObject(extra.toString()).put("v", 1).put("at", at)
        .put("weights", org.json.JSONObject(weights as Map<*, *>)).put("followed", org.json.JSONArray(followed.toList()))
        .put("lessTopics", org.json.JSONObject(lessTopic as Map<*, *>)).put("lessSources", org.json.JSONObject(lessSource as Map<*, *>)).toString()

    /** Your account's copy, if it's newer than this phone's: true if taken. */
    @Synchronized fun adopt(json: String): Boolean {
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return false }
        if (o.optInt("v") != 1 || o.optLong("at") <= at) return false
        read(o)
        prefs.edit().putString("interests", toJson()).putBoolean("interestsSeeded", true).apply()
        return true
    }

    private fun read(o: org.json.JSONObject) {
        weights.clear(); followed.clear(); lessTopic.clear(); lessSource.clear()
        o.optJSONObject("weights")?.let { w -> w.keys().forEach { k -> weights[k] = w.optDouble(k, 0.0).coerceAtLeast(0.0) } }
        o.optJSONArray("followed")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { followed += it } }
        o.optJSONObject("lessTopics")?.let { w -> w.keys().forEach { k -> lessTopic[k] = w.optInt(k) } }
        o.optJSONObject("lessSources")?.let { w -> w.keys().forEach { k -> lessSource[k] = w.optInt(k) } }
        at = o.optLong("at")
        extra = org.json.JSONObject(o.toString()).apply { KNOWN.forEach { remove(it) } }
    }
    private companion object { val KNOWN = listOf("v", "at", "weights", "followed", "lessTopics", "lessSources") }

    @Synchronized private fun save() {
        at = maxOf(at + 1, System.currentTimeMillis() / 1000)
        prefs.edit().putString("interests", toJson()).apply()
        changed()
    }
}

/**
 * What's been on your screen properly (most of it, for a second and a half): never shown again. Kept on
 * this phone, and the last week of it on your account too, encrypted to you ([SeenSync]), so your other
 * phones don't show it again either. Lines are "key time"; keys from another phone are short ones.
 */
class Seen(private val file: File) {
    private val at = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())
    /** Every key's short form: a post seen here and the same post from another phone are one. */
    private val shorts = java.util.Collections.synchronizedSet(HashSet<String>())
    init {
        try {
            val now = System.currentTimeMillis() / 1000
            file.readLines().filter { it.isNotBlank() }.forEach { l -> val k = l.substringBefore(' '); at[k] = l.substringAfter(' ', "").toLongOrNull() ?: now; shorts += SeenSync.short(k) }
        } catch (_: Exception) {}
    }
    /** Seen here, or on another of your phones. */
    operator fun contains(id: String) = SeenSync.short(id) in shorts
    /** Seen just now; true if it's new. */
    fun add(id: String, time: Long = System.currentTimeMillis() / 1000): Boolean {
        if (!shorts.add(SeenSync.short(id))) return false
        at[id] = time
        try {
            if (at.size > 20_000) { val keep = synchronized(at) { at.entries.toList().takeLast(15_000) }; at.clear(); shorts.clear(); keep.forEach { at[it.key] = it.value; shorts += SeenSync.short(it.key) }; file.writeText(keep.joinToString("") { "${it.key} ${it.value}\n" }) }
            else file.appendText("$id $time\n")
        } catch (_: Exception) {}
        return true
    }
    /** What's been seen since [since] (seconds), short keys, newest last. */
    fun since(since: Long): List<Pair<String, Long>> = synchronized(at) { at.entries.filter { it.value >= since }.map { SeenSync.short(it.key) to it.value } }
}

/**
 * The seen list on your account (NIP-78, d "discover/seen", NIP-44 to yourself): the last week, at most
 * [CAP] keys, by day, so it stays small (about 36 KB at most). Keys are short: a post's id's first 16
 * hex characters, a story's "t" and its 16. Two copies merge into everything either has seen.
 */
object SeenSync {
    const val TAG = "discover/seen"
    const val WINDOW = 7 * 86_400L
    const val CAP = 2000

    fun short(key: String) = if (key.startsWith("t:")) "t" + key.drop(2).take(16) else if (key.length > 17 && key.first() != 't') key.take(16) else key

    /** The account's copy: {"v":1,"at":…,"days":{"<day number>":"key key …"}}, the newest [CAP] within [WINDOW] of [now]. */
    fun encode(keys: List<Pair<String, Long>>, now: Long): String {
        val recent = keys.filter { it.second > now - WINDOW }.sortedBy { it.second }.distinctBy { it.first }.takeLast(CAP)
        val days = org.json.JSONObject()
        recent.groupBy { it.second / 86_400 }.forEach { (d, l) -> days.put(d.toString(), l.joinToString(" ") { it.first }) }
        return org.json.JSONObject().put("v", 1).put("at", now).put("days", days).toString()
    }

    /** The keys in a copy, each with the start of its day; empty if it isn't one (or is a later version). */
    fun decode(json: String): List<Pair<String, Long>> = try {
        val o = org.json.JSONObject(json)
        if (o.optInt("v") != 1) emptyList() else {
            val days = o.optJSONObject("days") ?: org.json.JSONObject()
            days.keys().asSequence().flatMap { d -> days.optString(d).split(' ').filter { it.isNotEmpty() }.map { it to (d.toLongOrNull() ?: 0) * 86_400 } }.toList()
        }
    } catch (_: Exception) { emptyList() }
}
