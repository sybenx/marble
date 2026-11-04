package cc.aaro.cosign

import android.content.Context
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A feed you added: its address, what it calls itself, its site, and the topic its stories go under. */
data class OwnFeed(val url: String, val name: String, val site: String, val topic: String, val at: Long, val gone: Boolean = false)

/**
 * Where Today's stories come from (Account › News sources): Marble's sources (each can be taken out, and put back) and
 * feeds you add (a site or its RSS or Atom address), fetched on this phone. Kept on your account (kind 30078,
 * d=marble/news-sources, NIP-44 to yourself), merged per feed and per source by the newest change.
 */
object NewsSources {
    const val TAG = "marble/news-sources"
    /** Your feeds, removed ones too (as markers, so another device learns it). */
    val own = MutableStateFlow<List<OwnFeed>>(emptyList())
    /** Marble's sources you took out (true) or put back (false), by source id, with when. */
    val off = MutableStateFlow<Map<String, Pair<Boolean, Long>>>(emptyMap())
    /** Marble's sources as last seen in its stories: id → name and topics. */
    val marble = MutableStateFlow<Map<String, Pair<String, Set<String>>>>(emptyMap())
    /** Called after a change made here (Social sends it a little later). */
    var changed: () -> Unit = {}
    private var prefs: android.content.SharedPreferences? = null

    /** Whose sources these are: one account's, as everything else (kept under that account's name). */
    @Volatile var account: String? = null; private set

    /** The shown account's sources (loaded again when you switch). */
    fun load(c: Context, account: String) = load(account) { c.getSharedPreferences(it, Context.MODE_PRIVATE) }

    /**
     * [account]'s sources. Before 0.12.27 there was one list for the phone: it moves, once, to the account signed in when
     * this version first loads, and no other account ever gets it, or a copy merged from another account.
     */
    fun load(account: String, prefsFor: (String) -> android.content.SharedPreferences) {
        if (this.account == account && prefs != null) return
        val p = prefsFor("news-sources.$account")
        val legacy = prefsFor("news-sources")
        if (!p.contains("state") && legacy.getString("movedTo", null) == null) {
            legacy.getString("state", null)?.let { p.edit().putString("state", it).apply() }
            legacy.edit().putString("movedTo", account).apply()
        }
        own.value = emptyList(); off.value = emptyMap()
        prefs = p; this.account = account
        p.getString("state", null)?.let { take(it) }
    }

    fun shownOwn() = own.value.filter { !it.gone }
    fun isOff(id: String) = off.value[id]?.first == true

    fun add(f: OwnFeed) { own.value = own.value.filter { it.url != f.url } + f; save(); changed() }
    fun remove(url: String) { own.value = own.value.map { if (it.url == url) it.copy(gone = true, at = now()) else it }; save(); changed() }
    fun setOff(id: String, on: Boolean) { off.value = off.value + (id to (on to now())); save(); changed() }

    private fun now() = System.currentTimeMillis() / 1000

    fun json(): String = JSONObject().put("v", 1)
        .put("own", JSONArray(own.value.map { JSONObject().put("url", it.url).put("name", it.name).put("site", it.site).put("topic", it.topic).put("at", it.at).put("gone", it.gone) }))
        .put("off", JSONObject().apply { off.value.forEach { (k, v) -> put(k, JSONObject().put("off", v.first).put("at", v.second)) } }).toString()

    /** Another copy merged in (the account's): per feed and per source, the newest change. True if this one has something it hasn't. */
    fun take(json: String): Boolean {
        val o = try { JSONObject(json) } catch (_: Exception) { return false }
        if (o.optInt("v", 1) != 1) return false
        val theirsOwn = o.optJSONArray("own")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
            OwnFeed(x.optString("url").ifEmpty { return@mapNotNull null }, x.optString("name"), x.optString("site"), x.optString("topic", "news"), x.optLong("at"), x.optBoolean("gone"))
        } } ?: emptyList()
        val theirsOff = o.optJSONObject("off")?.let { x -> x.keys().asSequence().associateWith { k -> x.getJSONObject(k).let { it.optBoolean("off") to it.optLong("at") } } } ?: emptyMap()
        val ownM = (theirsOwn + own.value).groupBy { it.url }.values.map { s -> s.maxBy { it.at } }
        val offM = (theirsOff.keys + off.value.keys).associateWith { k -> listOfNotNull(theirsOff[k], off.value[k]).maxBy { it.second } }
        val ahead = ownM.toSet() != theirsOwn.toSet() || offM != theirsOff
        own.value = ownM; off.value = offM; save()
        return ahead
    }

    private fun save() { prefs?.edit()?.putString("state", json())?.apply() }
    /** As if new (tests). */
    internal fun clear() { own.value = emptyList(); off.value = emptyMap(); marble.value = emptyMap() }

    // --- Fetching ------------------------------------------------------------------------

    private val http by lazy { Fetch.http.newBuilder().callTimeout(10, TimeUnit.SECONDS).followRedirects(true).build() }

    private fun get(url: String): Pair<String, String>? = try {
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Marble").build()).execute().use { r ->
            if (r.isSuccessful) (r.header("Content-Type") ?: "") to (r.body?.string() ?: return null) else null
        }
    } catch (_: Exception) { null }

    /** A site or a feed's address, as typed: its feed, named; null if none was found. */
    suspend fun find(input: String): OwnFeed? = withContext(Dispatchers.IO) {
        val t = input.trim().ifEmpty { return@withContext null }
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        val (type, body) = get(url) ?: return@withContext null
        val feed = if (looksLikeFeed(type, body)) url to body else {
            // A page: the feed it names (<link rel="alternate" type="application/rss+xml" href="…">).
            val link = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(body).map { it.value }
                .firstOrNull { Regex("type=[\"']application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?.let { Regex("href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: return@withContext null
            val abs = java.net.URI(url).resolve(HtmlText.decode(link)).toString()
            abs to (get(abs)?.second ?: return@withContext null)
        }
        val parsed = parse(feed.second, feed.first, "", "news") ?: return@withContext null
        val site = try { java.net.URI(parsed.first.ifEmpty { url }).host.removePrefix("www.") } catch (_: Exception) { "" }
        OwnFeed(feed.first, parsed.second.ifEmpty { site }, site, "news", now())
    }

    private fun looksLikeFeed(type: String, body: String) = "xml" in type || body.trimStart().let { it.startsWith("<?xml") || it.startsWith("<rss") || it.startsWith("<feed") }

    /** Your feeds' stories, from the phone's copy if fetched in the last 30 minutes (or [cachedOnly]). */
    suspend fun cards(context: Context, cachedOnly: Boolean): List<TopicCard> = withContext(Dispatchers.IO) {
        val feeds = shownOwn()
        if (feeds.isEmpty()) return@withContext emptyList()
        val dir = File(context.cacheDir, "own-feeds").also { it.mkdirs() }
        coroutineScope {
            feeds.map { f -> async {
                val file = File(dir, sha(f.url) + ".xml")
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 30 * 60_000L
                val body = if (fresh || cachedOnly) file.takeIf { it.exists() }?.readText() else get(f.url)?.second?.also { try { file.writeText(it) } catch (_: Exception) {} } ?: file.takeIf { it.exists() }?.readText()
                body?.let { parse(it, f.url, f.name, f.topic)?.third }.orEmpty()
            } }.awaitAll().flatten()
        }
    }

    /** One feed's stories (Reader's feed: sources), from the phone's copy if fetched in the last 30 minutes. */
    suspend fun feedCards(context: Context, url: String, name: String, cachedOnly: Boolean = false): List<TopicCard> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "own-feeds").also { it.mkdirs() }
        val file = File(dir, sha(url) + ".xml")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 30 * 60_000L
        val body = if (fresh || cachedOnly) file.takeIf { it.exists() }?.readText() else get(url)?.second?.also { try { file.writeText(it) } catch (_: Exception) {} } ?: file.takeIf { it.exists() }?.readText()
        body?.let { parse(it, url, name, "news")?.third }.orEmpty()
    }

    /** Marble's stories as you've chosen (its sources you took out, gone) and your own feeds' added. */
    suspend fun mix(context: Context, site: List<TopicCard>, cachedOnly: Boolean): List<TopicCard> {
        if (site.isNotEmpty()) marble.value = site.groupBy { it.sourceId }.mapValues { (_, l) -> l[0].sourceName to l.map { it.topic }.toSet() }
        val mine = try { cards(context, cachedOnly) } catch (_: Exception) { emptyList() }
        return site.filter { !isOff(it.sourceId) } + mine.filter { m -> site.none { it.url == m.url } }
    }

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Android's pull parser (tests pass another). */
    var newParser: () -> org.xmlpull.v1.XmlPullParser = { android.util.Xml.newPullParser() }

    /** RSS 2.0 or Atom: (the site's link, the feed's title, its stories as cards). */
    fun parse(xml: String, feedUrl: String, name: String, topic: String): Triple<String, String, List<TopicCard>>? = try {
        val p = newParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(java.io.StringReader(xml)) }
        var siteLink = ""; var title = ""
        val items = ArrayList<Map<String, String>>()
        var cur: HashMap<String, String>? = null
        var depthItem = -1
        var ev = p.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val n = p.name.lowercase()
                when {
                    n == "item" || n == "entry" -> { cur = HashMap(); depthItem = p.depth }
                    n == "link" && p.getAttributeValue(null, "href") != null -> {
                        val rel = p.getAttributeValue(null, "rel") ?: "alternate"
                        val href = p.getAttributeValue(null, "href")
                        if (rel == "alternate") { if (cur != null) cur.putIfAbsent("link", href) else if (siteLink.isEmpty()) siteLink = href }
                        else if (rel == "replies" && cur != null) cur.putIfAbsent("replies", href)
                    }
                    (n == "media:content" || n == "media:thumbnail" || n == "enclosure") && cur != null ->
                        p.getAttributeValue(null, "url")?.takeIf { (p.getAttributeValue(null, "type") ?: "image").startsWith("image") || n != "enclosure" }?.let { cur.putIfAbsent("image", it) }
                    n in TEXT -> {
                        val text = try { p.nextText() } catch (_: Exception) { null }
                        if (text != null) {
                            if (cur != null && p.depth >= depthItem) when (n) {
                                "title" -> cur.putIfAbsent("title", text); "link" -> cur.putIfAbsent("link", text.trim())
                                "description", "summary", "content:encoded", "content" -> cur.putIfAbsent("summary", text)
                                "pubdate", "published", "updated", "dc:date" -> cur.putIfAbsent("date", text.trim()); "guid", "id" -> cur.putIfAbsent("id", text.trim())
                                "comments" -> cur.putIfAbsent("comments", text.trim())
                            } else if (cur == null) when (n) { "title" -> if (title.isEmpty()) title = text.trim(); "link" -> if (siteLink.isEmpty()) siteLink = text.trim() }
                            ev = p.eventType; continue
                        }
                    }
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && (p.name.lowercase() == "item" || p.name.lowercase() == "entry")) { cur?.let { items += it }; cur = null }
            ev = p.next()
        }
        val src = "own:" + sha(feedUrl).take(12)
        val cards = items.mapNotNull { m ->
            val link = m["link"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val t = m["title"]?.let(HtmlText::decode)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val summary = m["summary"]?.replace(Regex("<[^>]+>"), " ")?.let(HtmlText::decode)?.replace(Regex("\\s+"), " ")?.trim()?.take(300).orEmpty()
            // Where it's discussed (reader.md §5): <comments>, rel=replies, a discussion page as its guid or link, hnrss's "Comments URL".
            val talk = cc.aaro.cosign.reader.ReaderRules.discussion(link, m["comments"], m["replies"], m["id"], m["summary"])
            TopicCard("own:" + sha(m["id"] ?: link), link, t, summary, m["image"]?.takeIf { it.startsWith("https://") }, src, name.ifEmpty { title }, date(m["date"]), topic,
                listOfNotNull(talk.takeIf { it.startsWith("http") }?.let { Discussion(it, try { java.net.URI(it).host.removePrefix("www.") } catch (_: Exception) { "" }) }))
        }
        if (title.isEmpty() && items.isEmpty()) null else Triple(siteLink, title, cards)
    } catch (_: Exception) { null }

    /** The elements whose text is read (anything else is walked into, not read). */
    private val TEXT = setOf("title", "link", "description", "summary", "content:encoded", "content", "pubdate", "published", "updated", "dc:date", "guid", "id", "comments")

    private fun date(s: String?): Long {
        s ?: return 0
        for (f in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'"))
            try { return java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)!!.time / 1000 } catch (_: Exception) {}
        return 0
    }
}
