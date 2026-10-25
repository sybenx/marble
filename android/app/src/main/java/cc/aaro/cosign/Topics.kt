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
)

data class Topic(val id: String, val name: String)

object TopicFeeds {
    private const val INDEX = "https://sybenx.github.io/topic-feeds/index.json"
    private val http by lazy { Fetch.http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    @Volatile var topics: List<Topic> = emptyList(); private set
    /** What to call a topic. The one about Nostr apps goes by what they are. */
    fun name(id: String) = if (id == "nostr") "Open social apps" else topics.firstOrNull { it.id == id }?.name ?: id

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
                (m["published_at"] as? Long) ?: (m["first_seen"] as? Long) ?: 0L, m["topic"] as? String ?: return@mapNotNull null)
        }.distinctBy { it.id }
    }
}

/** Which of the twelve topics a post is about, from its words and hashtags (the mock's lists, keyed like the topic feeds). */
object TopicGuess {
    private class T(val id: String, words: List<String>, val tags: Set<String>) {
        val res = words.map { w -> Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(w).replace(" ", "\\E[\\s-]?\\Q") + "(?=$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    }
    private val ALL = listOf(
        T("ai", listOf("ai", "llm", "llms", "claude", "chatgpt", "gpt", "openai", "anthropic", "agent", "agents", "agentic", "vibe coding", "vibecoding", "qwen", "ollama", "gemini", "deepseek", "local model", "machine learning"),
            setOf("ai", "llm", "vibecoding", "aiagents", "artificialintelligence", "machinelearning")),
        T("software", listOf("programming", "programmer", "developer", "developers", "github", "open source", "opensource", "coding", "javascript", "typescript", "rust", "python", "kotlin", "swift", "compiler", "codebase", "pull request", "linux", "terminal"),
            setOf("programming", "dev", "opensource", "coding", "developer", "rust", "python", "javascript", "foss", "linux")),
        T("selfhosting", listOf("self-host", "self-hosted", "self-hosting", "selfhost", "selfhosted", "selfhosting", "homelab", "umbrel", "start9", "docker", "vps", "raspberry pi", "nas", "home server", "proxmox"),
            setOf("selfhosting", "selfhosted", "homelab", "umbrel")),
        T("nostr", listOf("relay", "relays", "nip", "nips", "amethyst", "damus", "primal", "ditto", "blossom", "zapstore", "outbox"), setOf("nostrdev", "asknostr", "nostrapps")),
        T("bitcoin", listOf("bitcoin", "btc", "sats", "satoshi", "lightning", "coldcard", "mining", "miners", "hodl", "self-custody", "ecash", "cashu"),
            setOf("bitcoin", "btc", "plebchain", "lightning", "cashu", "ecash")),
        T("privacy", listOf("privacy", "surveillance", "security", "leak", "leaked", "breach", "hacked", "encryption", "encrypted", "vpn", "tor", "grapheneos", "spyware", "malware", "exploit", "vulnerability", "censorship", "kyc"),
            setOf("privacy", "security", "cybersecurity", "surveillance", "infosec", "censorship")),
        T("food", listOf("food", "cooking", "recipe", "roast", "dinner", "lunch", "breakfast", "dessert", "coffee", "bbq", "baking", "bread", "pizza", "steak", "soup", "cake"),
            setOf("foodstr", "food", "cooking", "coffeechain", "dessertstr", "zapcooking", "recipe", "baking")),
        T("photos", listOf("photography", "photo", "photos", "sunrise", "sunset", "landscape", "mountains", "beach", "scenery", "cityscape", "skyline", "travel", "hiking"),
            setOf("photography", "photostr", "cityscapes", "travel", "landscape", "nature", "sunrise", "sunset", "streetphotography")),
        T("games", listOf("game", "games", "gaming", "gamedev", "game dev", "indie game", "unity", "godot", "unreal", "minecraft", "steam", "level design", "webgl"),
            setOf("gaming", "gamedev", "indiedev", "games", "videogames")),
        T("gadgets", listOf("apple", "iphone", "android", "pixel", "macbook", "ipad", "app store", "gadget", "hardware", "keyboard", "phone"),
            setOf("apple", "android", "tech", "gadgets", "hardware")),
        T("art", listOf("art", "artist", "drawing", "illustration", "painting", "design", "designer", "sketch", "pixel art", "pixelart"),
            setOf("art", "artstr", "design", "illustration", "pixelart", "digitalart")),
        T("news", listOf("government", "politics", "election", "regulation", "war", "parliament", "president", "minister"), setOf("politics", "news")),
    )
    private val NOISE = Regex("nostr:\\S+|https?://\\S+")

    /** The topics a post is about, strongest first: a hashtag counts more than a word. */
    fun of(e: Event): List<String> {
        val text = NOISE.replace(e.content, " ")
        val tags = e.tags.filter { it.size > 1 && it[0] == "t" }.map { it[1].lowercase() }.toSet()
        return ALL.mapNotNull { t ->
            val score = (if (t.tags.any { it in tags }) 2 else 0) + t.res.count { it.containsMatchIn(text) }.coerceAtMost(3)
            if (score > 0) t.id to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }
}

/**
 * What you care about, on this phone only: topic weights learned from what you like and open, the
 * topics you follow, and what you've said you want less of (a topic, a site).
 */
class Interests(private val prefs: android.content.SharedPreferences) {
    private val weights = HashMap<String, Double>(prefs.getString("interests", null)?.let { s -> (Json.parse(s) as? Map<*, *>)?.entries?.associate { (k, v) -> k as String to ((v as? Number)?.toDouble() ?: 0.0) } } ?: emptyMap())
    val followed: MutableSet<String> = prefs.getStringSet("followedTopics", emptySet())!!.toMutableSet()
    private val lessTopic = HashMap<String, Int>(prefs.getStringSet("lessTopics", emptySet())!!.associate { it.substringBefore(':') to (it.substringAfter(':').toIntOrNull() ?: 1) })
    private val lessSource = HashMap<String, Int>(prefs.getStringSet("lessSources", emptySet())!!.associate { it.substringBefore(':') to (it.substringAfter(':').toIntOrNull() ?: 1) })
    val seeded get() = prefs.getBoolean("interestsSeeded", false)

    @Synchronized fun learn(topics: List<String>, by: Double) {
        if (topics.isEmpty()) return
        topics.take(2).forEachIndexed { i, t -> weights[t] = ((weights[t] ?: 0.0) + by / (i + 1)).coerceAtLeast(0.0) }
        save()
    }
    @Synchronized fun seed(votes: Map<String, Double>) { votes.forEach { (t, v) -> weights[t] = (weights[t] ?: 0.0) + v }; prefs.edit().putBoolean("interestsSeeded", true).apply(); save() }
    @Synchronized fun follow(topic: String, on: Boolean) { if (on) { followed += topic; lessTopic -= topic } else followed -= topic; save() }
    @Synchronized fun lessOfTopic(topic: String) { lessTopic[topic] = (lessTopic[topic] ?: 0) + 1; followed -= topic; save() }
    @Synchronized fun lessOfSource(source: String) { lessSource[source] = (lessSource[source] ?: 0) + 1; save() }

    /** 0..1 for a topic: followed is the most; learned weight relative to your strongest; each "less" halves it. */
    @Synchronized fun of(topic: String?): Double {
        if (topic == null) return 0.25
        val max = weights.values.maxOrNull()?.takeIf { it > 0 }
        val learned = if (max == null) 0.4 else 0.15 + 0.85 * ln(1 + (weights[topic] ?: 0.0)) / ln(1 + max)
        val base = if (topic in followed) 1.0 else learned
        return base / (1 shl (lessTopic[topic] ?: 0).coerceAtMost(4))
    }
    @Synchronized fun sourcePenalty(source: String) = 1.0 / (1 shl (lessSource[source] ?: 0).coerceAtMost(4))

    @Synchronized private fun save() {
        prefs.edit().putString("interests", org.json.JSONObject(weights as Map<*, *>).toString())
            .putStringSet("followedTopics", followed.toSet())
            .putStringSet("lessTopics", lessTopic.map { (k, v) -> "$k:$v" }.toSet())
            .putStringSet("lessSources", lessSource.map { (k, v) -> "$k:$v" }.toSet()).apply()
    }
}

/** What's been on your screen properly (most of it, for a second and a half): never shown again. On this phone only. */
class Seen(private val file: File) {
    private val ids = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    init { try { file.readLines().filter { it.isNotBlank() }.forEach { ids += it } } catch (_: Exception) {} }
    operator fun contains(id: String) = id in ids
    fun add(id: String) {
        if (!ids.add(id)) return
        try {
            if (ids.size > 20_000) { val keep = synchronized(ids) { ids.toList().takeLast(15_000) }; ids.clear(); ids += keep; file.writeText(keep.joinToString("\n", postfix = "\n")) }
            else file.appendText(id + "\n")
        } catch (_: Exception) {}
    }
}

/** One thing in Discover: a post, or a link card. */
sealed class Item {
    abstract val key: String
    data class Post(val e: Event) : Item() { override val key get() = e.id }
    data class Card(val c: TopicCard) : Item() { override val key get() = "t:" + c.id }
}

/**
 * Putting a page together: each candidate scored by how well it matches your interests, then how
 * fresh it is (a day's half-life); then picked best first, with no more than [perTopic] from a topic
 * and [perSource] from a site on a page, and never two link cards in a row.
 */
object Arrange {
    fun page(posts: List<Event>, cards: List<TopicCard>, interests: Interests, now: Long = System.currentTimeMillis() / 1000,
             size: Int = 24, perTopic: Int = 3, perSource: Int = 2): List<Item> {
        fun fresh(t: Long) = exp(-((now - t).coerceAtLeast(0) / 3600.0) / 34.6)   // half-life of a day
        // Posts keep a little of the curated source's own order.
        val scored = posts.mapIndexed { i, e ->
            val topic = TopicGuess.of(e).firstOrNull()
            Triple<Item, String?, Double>(Item.Post(e), topic, 0.55 * interests.of(topic) + 0.3 * fresh(e.createdAt) + 0.15 * (1 - i.toDouble() / posts.size.coerceAtLeast(1)))
        } + cards.map { c ->
            Triple<Item, String?, Double>(Item.Card(c), c.topic, (0.6 * interests.of(c.topic) + 0.4 * fresh(c.publishedAt)) * interests.sourcePenalty(c.sourceId))
        }
        val left = scored.sortedByDescending { it.third }.toMutableList()
        val out = ArrayList<Item>()
        val byTopic = HashMap<String, Int>(); val bySource = HashMap<String, Int>()
        while (out.size < size && left.isNotEmpty()) {
            val lastWasCard = out.lastOrNull() is Item.Card
            fun fits(t: Triple<Item, String?, Double>, alone: Boolean): Boolean {
                val (item, topic, _) = t
                val src = (item as? Item.Card)?.c?.sourceId
                return !(alone && lastWasCard && item is Item.Card) && (topic == null || (byTopic[topic] ?: 0) < perTopic) && (src == null || (bySource[src] ?: 0) < perSource)
            }
            // Out of posts to put between them: cards may follow cards.
            val i = left.indexOfFirst { fits(it, true) }.takeIf { it >= 0 } ?: left.indexOfFirst { fits(it, false) }
            if (i < 0) break
            val (item, topic, _) = left.removeAt(i)
            out += item
            topic?.let { byTopic[it] = (byTopic[it] ?: 0) + 1 }
            (item as? Item.Card)?.c?.sourceId?.let { bySource[it] = (bySource[it] ?: 0) + 1 }
        }
        return out
    }
}
