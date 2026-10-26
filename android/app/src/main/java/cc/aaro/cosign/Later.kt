package cc.aaro.cosign

import android.content.Context
import android.net.Uri
import cc.aaro.cosign.core.Clock
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// The search box is for typing in: Enter searches the web with your engine, Save keeps what you typed in Later.
// Finding old things happens in Later, with its own filter. (~/igloo/marble-browser-research.md, mocks A1–A3, S1–S4.)

/** One thing you saved: your words, when, and whether it's done. Removed ones stay a month as [deleted], so other devices learn it. */
data class LaterItem(val id: String, val text: String, val created: Long, val updated: Long, val done: Long? = null, val deleted: Long? = null,
                     val url: String? = null, val extra: Map<String, Any?> = emptyMap())

/** A page you opened from Marble (on this phone only, 30 days): to find it again without keeping a tab. */
data class Page(val url: String, val title: String, val at: Long, val from: String)

/**
 * Later: what you saved, as your account keeps it (kind 30078, d=marble/later, NIP-44 to yourself; the desktop's
 * Marble Home writes the same), and the pages you opened here. Merged by item: the newer `updated` wins.
 */
class Later(private val prefs: android.content.SharedPreferences) {
    val items = MutableStateFlow(fromJson(prefs.getString("later", null)))
    val pages = MutableStateFlow(pagesFrom(prefs.getString("laterPages", null)))
    /** Fields of the account's copy this version doesn't know: written back unchanged. */
    private var unknown: Map<String, Any?> = emptyMap()
    /** Called after each change you make (Social sends the account's copy a little later). */
    var changed: () -> Unit = {}

    fun shown() = items.value.filter { it.deleted == null }
    fun find(text: String) = shown().firstOrNull { it.text.equals(text.trim(), ignoreCase = true) }

    /** Save [text] (or bring it back if it was done). */
    fun save(text: String, url: String? = null): LaterItem? {
        val t = text.trim().take(500).ifEmpty { return null }
        val now = Clock.now()
        val have = items.value.firstOrNull { it.text.equals(t, ignoreCase = true) && it.deleted == null }
        val item = have?.copy(done = null, updated = now) ?: LaterItem(newId(), t, now, now, url = url)
        put(item)
        return item
    }
    fun done(item: LaterItem, on: Boolean) = put(item.copy(done = if (on) Clock.now() else null, updated = Clock.now()))
    fun remove(item: LaterItem) = put(item.copy(deleted = Clock.now(), updated = Clock.now()))
    /** Undo of a save just made: gone as if it never was (other devices may not have it yet; a tombstone tells them). */
    fun unsave(item: LaterItem) = remove(item)

    private fun put(item: LaterItem) {
        items.update { l -> (l.filter { it.id != item.id } + item).sortedByDescending { it.created } }
        store(); changed()
    }

    /** A page opened from a search or a saved item: kept 30 days, newest first, at most 500. */
    fun opened(url: String, title: String, from: String) {
        val now = Clock.now()
        pages.update { l -> (listOf(Page(url, title.ifBlank { Uri.parse(url).host ?: url }, now, from)) + l.filter { it.url != url }).filter { now - it.at < 30 * 86_400L }.take(500) }
        prefs.edit().putString("laterPages", org.json.JSONArray(pages.value.map { org.json.JSONObject().put("url", it.url).put("title", it.title).put("at", it.at).put("from", it.from) }).toString()).apply()
    }
    fun titled(url: String, title: String) {
        if (title.isBlank()) return
        pages.update { l -> l.map { if (it.url == url) it.copy(title = title) else it } }
        prefs.edit().putString("laterPages", org.json.JSONArray(pages.value.map { org.json.JSONObject().put("url", it.url).put("title", it.title).put("at", it.at).put("from", it.from) }).toString()).apply()
    }

    /** The account's copy merged in; true if this phone has something the copy hasn't (so it should be sent). */
    fun merge(json: String): Boolean {
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return false }
        if (o.optInt("v", 1) != 1) return false
        unknown = o.keys().asSequence().filter { it !in setOf("v", "at", "items") }.associateWith { o.opt(it) }
        val theirs = itemsOf(o.optJSONArray("items"))
        val mine = items.value
        val ids = (theirs.map { it.id } + mine.map { it.id }).toSet()
        val merged = ids.mapNotNull { id -> listOfNotNull(theirs.firstOrNull { it.id == id }, mine.firstOrNull { it.id == id }).maxByOrNull { it.updated } }
            .filter { it.deleted == null || Clock.now() - it.deleted < 30 * 86_400L }.sortedByDescending { it.created }
        items.value = merged
        store()
        return merged.toSet() != theirs.toSet()
    }

    /** {"v":1,"at":…,"items":[{"id","text","created","updated","done","deleted"(,"url")}]} plus fields kept from the account's copy. */
    fun json(): String {
        val o = org.json.JSONObject()
        unknown.forEach { (k, v) -> o.put(k, v) }
        o.put("v", 1).put("at", items.value.maxOfOrNull { it.updated } ?: Clock.now())
        o.put("items", org.json.JSONArray(items.value.map { it.toJson() }))
        return o.toString()
    }

    private fun store() = prefs.edit().putString("later", json()).apply()

    companion object {
        const val TAG = "marble/later"
        private fun newId() = java.security.SecureRandom().let { r -> ByteArray(8).also(r::nextBytes) }.joinToString("") { "%02x".format(it) }
        private fun LaterItem.toJson() = org.json.JSONObject().apply {
            extra.forEach { (k, v) -> put(k, v) }
            put("id", id); put("text", text); put("created", created); put("updated", updated)
            put("done", done ?: org.json.JSONObject.NULL); put("deleted", deleted ?: org.json.JSONObject.NULL)
            url?.let { put("url", it) }
        }
        private val KNOWN = setOf("id", "text", "created", "updated", "done", "deleted", "url")
        private fun itemsOf(a: org.json.JSONArray?): List<LaterItem> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val x = a!!.optJSONObject(i) ?: return@mapNotNull null
            val id = x.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = x.optString("text").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            fun long(k: String) = if (x.isNull(k)) null else x.optLong(k).takeIf { it > 0 }
            LaterItem(id, text, x.optLong("created"), x.optLong("updated", x.optLong("created")), long("done"), long("deleted"),
                x.optString("url").takeIf { it.isNotEmpty() }, x.keys().asSequence().filter { it !in KNOWN }.associateWith { x.opt(it) })
        }
        fun fromJson(json: String?): List<LaterItem> = try { itemsOf(org.json.JSONObject(json ?: "{}").optJSONArray("items")).sortedByDescending { it.created } } catch (_: Exception) { emptyList() }
        private fun pagesFrom(json: String?): List<Page> = try {
            val a = org.json.JSONArray(json ?: "[]")
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Page(it.optString("url"), it.optString("title"), it.optLong("at"), it.optString("from")) } }
        } catch (_: Exception) { emptyList() }
    }
}

/** The search engine you use: where Enter goes, where suggestions come from, and which pages may open inside Marble. */
enum class Engine(val label: String, val home: String) {
    DuckDuckGo("DuckDuckGo", "duckduckgo.com"), Google("Google", "google.com"), Bing("Bing", "bing.com"),
    Brave("Brave", "search.brave.com"), Ecosia("Ecosia", "ecosia.org");

    /** The results page for [q], in light or dark where the engine takes it in the address. */
    fun results(q: String, dark: Boolean): String {
        val e = Uri.encode(q)
        return when (this) {
            DuckDuckGo -> "https://duckduckgo.com/?q=$e&kae=${if (dark) "d" else "-1"}"
            Google -> "https://www.google.com/search?q=$e"
            Bing -> "https://www.bing.com/search?q=$e"
            Brave -> "https://search.brave.com/search?q=$e"
            Ecosia -> "https://www.ecosia.org/search?q=$e"
        }
    }

    /** Pages of the engine itself (they open inside Marble); anything else opens in your browser. */
    fun owns(uri: Uri): Boolean {
        val h = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https") return false
        return when (this) {
            DuckDuckGo -> h == "duckduckgo.com" || h == "html.duckduckgo.com" || h == "lite.duckduckgo.com" || h == "external-content.duckduckgo.com" || h == "improving.duckduckgo.com"
            // Google's own results pages (any country), and its EU consent page; not sign-in, Maps or anything else.
            Google -> (Regex("^(www\\.)?google\\.[a-z.]{2,8}$").matches(h) && (uri.path ?: "").let { it == "/search" || it.startsWith("/search") || it == "/" || it.startsWith("/imgres") || it.startsWith("/sorry") })
                || h == "consent.google.com"
            Bing -> h == "www.bing.com" && (uri.path ?: "").let { p -> listOf("/search", "/images", "/videos", "/news", "/shop").any { p.startsWith(it) } }
            Brave -> h == "search.brave.com"
            Ecosia -> h == "www.ecosia.org" && (uri.path ?: "").let { it.startsWith("/search") || it.startsWith("/images") || it.startsWith("/news") || it.startsWith("/videos") }
        }
    }

    /** Suggestions for [q] from the engine's free endpoint, as the phone itself asks (nothing goes through Marble). */
    suspend fun suggest(q: String): List<String> = withContext(Dispatchers.IO) {
        val e = Uri.encode(q)
        val url = when (this@Engine) {
            DuckDuckGo -> "https://ac.duckduckgo.com/ac/?q=$e&type=list"
            Google -> "https://www.google.com/complete/search?client=chrome&q=$e"
            Bing -> "https://www.bing.com/osjson.aspx?query=$e"
            Brave -> "https://search.brave.com/api/suggest?q=$e"
            Ecosia -> "https://ac.ecosia.org/autocomplete?q=$e&type=list"
        }
        try {
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                // The OpenSearch shape: [query, [suggestion, …], …].
                val a = org.json.JSONArray(r.body?.string() ?: "[]").optJSONArray(1) ?: return@use emptyList()
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.filter { !it.equals(q, true) }.take(5)
            }
        } catch (_: Exception) { emptyList() }
    }

    companion object {
        private val http by lazy { Fetch.http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build() }
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DuckDuckGo

        /**
         * Where a result link really goes: the engine's click-through wrappers taken off (DuckDuckGo /l/?uddg=,
         * Google /url?q=, Bing /ck/a?u=a1…). Ad links stay wrapped, so the click counts as the engine and the
         * advertiser expect.
         */
        fun unwrap(uri: Uri): Uri {
            val h = uri.host?.lowercase() ?: return uri
            val p = uri.path ?: ""
            val out = when {
                h.endsWith("duckduckgo.com") && p.startsWith("/l/") -> uri.getQueryParameter("uddg")
                Regex("^(www\\.)?google\\.[a-z.]{2,8}$").matches(h) && p == "/url" -> uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
                h == "www.bing.com" && p.startsWith("/ck/a") -> uri.getQueryParameter("u")?.takeIf { it.startsWith("a1") }?.let {
                    try { String(android.util.Base64.decode(it.drop(2), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)) } catch (_: Exception) { null }
                }
                else -> null
            }
            return out?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse) ?: uri
        }
    }
}

/** How you search: the engine, suggestions as you type, and when "Search posts" shows. On this phone. */
object SearchPrefs {
    private fun p(c: Context) = c.getSharedPreferences("search", Context.MODE_PRIVATE)
    val engine = MutableStateFlow(Engine.DuckDuckGo)
    val suggestions = MutableStateFlow(true)
    /** "auto" (only if you follow people), "always", "never". */
    val posts = MutableStateFlow("auto")
    fun load(c: Context) { val s = p(c); engine.value = Engine.of(s.getString("engine", null)); suggestions.value = s.getBoolean("suggest", true); posts.value = s.getString("posts", "auto")!! }
    fun setEngine(c: Context, e: Engine) { engine.value = e; p(c).edit().putString("engine", e.name).apply() }
    fun setSuggestions(c: Context, on: Boolean) { suggestions.value = on; p(c).edit().putBoolean("suggest", on).apply() }
    fun setPosts(c: Context, v: String) { posts.value = v; p(c).edit().putString("posts", v).apply() }
}
