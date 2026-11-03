package cc.aaro.cosign

import cc.aaro.cosign.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One search: its words (the last wording, if you changed it), when, and the pages you opened from it, newest first. */
data class SearchEntry(val id: String, val text: String, val at: Long, val pages: List<Page> = emptyList())

/**
 * Searches: what you searched, on this phone only, for 30 days (at most 500). Nothing here is a note: keeping the words
 * is the bookmark's job (Later). Changing a search within a couple of minutes is the same search, not a second one.
 */
class Searches(private val prefs: android.content.SharedPreferences) {
    val all = MutableStateFlow(load())

    /**
     * Enter: [text] searched. [current]: the search this one may be an edit of (null: a new search); an edit within
     * [GROUP] seconds of it takes its place. The same words searched again come to the top, with their pages.
     */
    fun searched(text: String, current: String? = null): SearchEntry {
        val t = text.trim().take(500)
        val now = Clock.now()
        val l = all.value
        val edited = current?.let { id -> l.firstOrNull { it.id == id && now - it.at < GROUP } }
        val again = l.firstOrNull { it.id != edited?.id && Later.same(it.text, t) }
        val pages = ((edited?.pages ?: emptyList()) + (again?.pages ?: emptyList())).distinctBy { it.url }
        val e = SearchEntry(edited?.id ?: again?.id ?: newId(), t, now, pages)
        put(listOf(e) + l.filter { it.id != edited?.id && it.id != again?.id })
        return e
    }

    /** A page opened from search [id]: listed under it. */
    fun opened(id: String, url: String, title: String) {
        val now = Clock.now()
        put(all.value.map { e -> if (e.id != id) e else e.copy(pages = listOf(Page(url, title.ifBlank { url }, now, e.text)) + e.pages.filter { it.url != url }) })
    }

    fun titled(url: String, title: String) {
        if (title.isBlank()) return
        put(all.value.map { e -> e.copy(pages = e.pages.map { if (it.url == url) it.copy(title = title) else it }) })
    }

    /** Taken out of your searches (held down in the list). */
    fun remove(id: String) = put(all.value.filter { it.id != id })

    private fun put(l: List<SearchEntry>) {
        val now = Clock.now()
        all.value = l.filter { now - it.at < LIFE }.take(500)
        prefs.edit().putString("searches", toJson(all.value)).apply()
    }

    private fun load(): List<SearchEntry> {
        val now = Clock.now()
        return fromJson(prefs.getString("searches", null)).filter { now - it.at < LIFE }
    }

    companion object {
        /** Changed within this long (seconds): the same search. */
        const val GROUP = 120L
        const val LIFE = 30 * 86_400L
        private fun newId() = java.security.SecureRandom().let { r -> ByteArray(8).also(r::nextBytes) }.joinToString("") { "%02x".format(it) }
        fun toJson(l: List<SearchEntry>) = org.json.JSONArray(l.map { e ->
            org.json.JSONObject().put("id", e.id).put("text", e.text).put("at", e.at)
                .put("pages", org.json.JSONArray(e.pages.map { org.json.JSONObject().put("url", it.url).put("title", it.title).put("at", it.at) }))
        }).toString()
        fun fromJson(s: String?): List<SearchEntry> = try {
            val a = org.json.JSONArray(s ?: "[]")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").ifEmpty { return@mapNotNull null }
                val p = o.optJSONArray("pages")
                SearchEntry(o.optString("id"), text, o.optLong("at"), (0 until (p?.length() ?: 0)).mapNotNull { j ->
                    p!!.optJSONObject(j)?.let { x -> Page(x.optString("url"), x.optString("title"), x.optLong("at"), text) }
                })
            }
        } catch (_: Exception) { emptyList() }
    }
}
