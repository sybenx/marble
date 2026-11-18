package cc.aaro.cosign

import android.content.Context
import cc.aaro.cosign.net.Fetch
import cc.aaro.qrst.core.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

// Nostr apps and nsites for search: apps.json, built once a day on Took (apps-index/build.ts in this repo) and served
// through discover.syb.lol, is downloaded at most once a day and kept on the phone. What you type is matched here, so
// nothing is sent while you type. The same rules run on Marble Web (site/js/apps.js); test resources apps/vectors.json
// checks both.
//
// Everything in the file comes from strangers: names and descriptions are shown as text, links must be https, icons
// go through Marble's image loader. Opening an app never gives it signing: it's a visit (see openApp in Discover.kt).

/** One app or nsite from the index. [url] is what opens: the app's own domain, an nsite gateway, or its Zapstore page. */
data class AppEntry(
    val address: String, val kind: String, val name: String, val about: String, val icon: String?, val url: String,
    val domain: String?, val site: String?, val pubkey: String, val by: String?, val official: Boolean, val zapstore: String?,
    val score: Double,
) {
    companion object {
        private val HTTPS = Regex("^https://[^\\s/?#@]+\\.[^\\s/?#@]+(/.*)?$", RegexOption.IGNORE_CASE)
        private val HEX = Regex("^[0-9a-f]{64}$")
        private fun https(s: Any?) = (s as? String)?.takeIf { HTTPS.matches(it) }
        private fun text(s: Any?, max: Int) = (s as? String)?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(max) ?: ""

        fun from(x: Any?): AppEntry? {
            val m = x as? Map<*, *> ?: return null
            val name = text(m["name"], 60).ifEmpty { return null }
            val url = https(m["url"]) ?: return null
            val pk = (m["pk"] as? String)?.takeIf { HEX.matches(it) } ?: return null
            return AppEntry(text(m["a"], 200), text(m["kind"], 10), name, text(m["about"], 200), https(m["icon"]), url,
                (m["domain"] as? String)?.lowercase()?.takeIf { it.isNotEmpty() }, m["site"] as? String, pk, text(m["by"], 40).ifEmpty { null },
                m["official"] == true, (m["zapstore"] as? String)?.takeIf { it.isNotEmpty() }, (m["score"] as? Number)?.toDouble() ?: 0.0)
        }
    }
}

object AppIndex {
    const val URL = "https://discover.syb.lol/apps.json"
    private const val FRESH_MS = 20 * 3600_000L // a day, give or take: the index itself is rebuilt daily
    private const val RETRY_MS = 30 * 60_000L   // after a failed download, not again for half an hour

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps
    private val lock = Mutex()
    private var loaded = false
    private var lastTry = 0L
    private val http by lazy { Fetch.http.newBuilder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build() }

    private fun file(context: Context) = File(context.filesDir, "apps.json")

    /** The copy on the phone, then a new one if it's a day old. Called when search opens, never per keystroke. */
    suspend fun refresh(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = file(context)
            if (!loaded) { loaded = true; runCatching { parse(f.readText()) }.getOrNull()?.let { _apps.value = it } }
            val now = System.currentTimeMillis()
            if (f.exists() && now - f.lastModified() < FRESH_MS) return@withContext
            if (now - lastTry < RETRY_MS) return@withContext
            lastTry = now
            val text = runCatching {
                http.newCall(okhttp3.Request.Builder().url(URL).header("accept", "application/json").build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.let { b -> if ((b.contentLength()) > 8_000_000) null else b.string() }
                }
            }.getOrNull() ?: return@withContext
            val list = parse(text) ?: return@withContext
            val tmp = File(f.path + ".tmp")
            runCatching { tmp.writeText(text); tmp.renameTo(f) }
            _apps.value = list
        }
    }

    /** The index file's apps, or null when it isn't one (a short or broken download keeps the old copy). */
    fun parse(text: String): List<AppEntry>? {
        val d = runCatching { Json.parse(text) as? Map<*, *> }.getOrNull() ?: return null
        if ((d["v"] as? Number)?.toInt() != 1) return null
        val list = (d["apps"] as? List<*>)?.mapNotNull { AppEntry.from(it) } ?: return null
        return list.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ matching (the same as site/js/apps.js)

    /** Lowercase letters and digits only, accents dropped ("noStrudel" -> "nostrudel"). */
    fun norm(s: String?): String = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFKD).filter { it in 'a'..'z' || it in '0'..'9' }
    private fun words(s: String?): List<String> = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFKD)
        .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * How well one app matches what was typed, 0 for not at all. Name exact 100, name prefix 80, domain 75
     * ("jumble.social", "jumble"), every word starting a name word 60, the name containing it (4 or more letters) 50,
     * every word in the name, description, domain or author 30.
     */
    fun textScore(a: AppEntry, query: String): Int {
        val q = norm(query)
        if (q.length < 2) return 0
        val n = norm(a.name)
        if (n == q) return 100
        if (n.startsWith(q)) return 80
        val domain = a.domain ?: ""
        if (domain.isNotEmpty() && (norm(domain) == q || norm(domain.substringBefore('.')) == q || (q.length >= 4 && norm(domain).startsWith(q)))) return 75
        val qw = words(query)
        if (qw.isEmpty()) return 0
        val nw = words(a.name)
        if (qw.all { w -> nw.any { it.startsWith(w) } }) return 60
        if (q.length >= 4 && n.contains(q)) return 50
        val hay = (nw + words(a.about) + words(domain) + words(a.by)).toSet()
        if (qw.all { w -> if (w.length >= 3) hay.any { it.startsWith(w) } else w in hay }) return 30
        return 0
    }

    /** Apps for a search, best first (text, the index's score, you follow the author), at most 3 from one author. */
    fun search(apps: List<AppEntry>, query: String, follows: Set<String> = emptySet(), limit: Int = 3): List<AppEntry> {
        val hits = apps.mapNotNull { a -> textScore(a, query).takeIf { it > 0 }?.let { t -> a to t + 0.3 * a.score + if (a.pubkey in follows) 20.0 else 0.0 } }
            .sortedWith(compareByDescending<Pair<AppEntry, Double>> { it.second }.thenBy { it.first.name })
        val per = HashMap<String, Int>()
        val out = ArrayList<AppEntry>()
        for ((a, _) in hits) {
            val c = per[a.pubkey] ?: 0
            if (c >= 3) continue
            per[a.pubkey] = c + 1
            out += a
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * The one line under an app's name, as Marble Web shows it (vectors.json keeps the two in step). The phone's search
     * row has its own, with the kind first and where a tap goes: SearchRows.app. (The index's follower count is its
     * builder's network, not yours: not shown.)
     */
    fun line(a: AppEntry, follows: Set<String>): String = listOfNotNull(
        when (a.kind) { "nsite" -> a.by?.let { "nsite by $it" } ?: "nsite"; "android" -> "Android app on Zapstore"; else -> a.domain },
        "You follow them".takeIf { a.pubkey in follows },
        "Also on Android".takeIf { a.kind == "web" && a.zapstore != null },
    ).joinToString(" · ")
}
