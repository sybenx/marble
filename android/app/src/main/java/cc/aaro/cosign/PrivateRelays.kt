package cc.aaro.cosign

import cc.aaro.cosign.reader.SyncGuard
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A private relay you added (a Chronicle archive, say): its address, when it last changed, and whether it was removed. */
data class PrivateRelay(val url: String, val at: Long, val gone: Boolean = false) {
    /** How to show it: the host and path, without `wss://` ("chronicle.example.com"). */
    val label: String get() = PrivateRelays.label(url)
}

/**
 * Relays only you use (your own archive): Marble logs in to them (NIP-42) and, later, searches them for you. Kept on
 * your account (kind 30078, d=marble/private-relays, NIP-44 to yourself) so every device you sign in on has them;
 * merged per relay by the newest change. Removed ones stay as markers so other devices learn it.
 */
class PrivateRelays(private val prefs: android.content.SharedPreferences?) {
    companion object {
        const val TAG = "marble/private-relays"
        /** [url] for people: no scheme, no trailing slash. */
        fun label(url: String): String = SyncGuard.normRelay(url).substringAfter("://")
        /** A typed address as stored: wss:// added if missing; null if it isn't a relay address. */
        fun parse(input: String): String? {
            val t = input.trim().ifEmpty { return null }
            val u = if ("://" in t) t else "wss://$t"
            val n = SyncGuard.normRelay(u)
            if (!(n.startsWith("wss://") || n.startsWith("ws://"))) return null
            val host = n.substringAfter("://").substringBefore('/')
            return if (host.isEmpty() || host.any { it.isWhitespace() }) null else n
        }
    }

    /** Every relay, removed ones too. */
    val all = MutableStateFlow(prefs?.getString("privateRelays", null)?.let { fromJson(it) } ?: emptyList())
    /** Called after a change made here (Social sends the account's copy a little later). */
    var changed: () -> Unit = {}

    /** Your private relays, normalised (what login and search use). */
    fun urls(): List<String> = all.value.filter { !it.gone }.map { it.url }
    fun shown(): List<PrivateRelay> = all.value.filter { !it.gone }

    /** Add [input] (an address as typed); the stored relay, or null if it isn't one. */
    fun add(input: String): PrivateRelay? {
        val url = parse(input) ?: return null
        val r = PrivateRelay(url, next(url))
        put(r); return r
    }
    fun remove(url: String) {
        val u = SyncGuard.normRelay(url)
        if (all.value.none { it.url == u && !it.gone }) return
        put(PrivateRelay(u, next(u), gone = true))
    }

    private fun next(url: String) = maxOf(System.currentTimeMillis() / 1000, (all.value.firstOrNull { it.url == url }?.at ?: 0) + 1)
    private fun put(r: PrivateRelay) { all.value = all.value.filter { it.url != r.url } + r; save(); changed() }

    fun json(): String = toJson(all.value)

    /** The account's copy merged in, per relay by the newest change. True if this one has something that copy hasn't. */
    fun take(json: String): Boolean {
        val theirs = fromJson(json) ?: return false
        val merged = merge(theirs, all.value)
        all.value = merged; save()
        return merged.toSet() != theirs.toSet()
    }

    private fun save() { prefs?.edit()?.putString("privateRelays", json())?.apply() }

    private fun toJson(l: List<PrivateRelay>) = JSONObject().put("v", 1)
        .put("relays", JSONArray(l.map { JSONObject().put("url", it.url).put("at", it.at).put("gone", it.gone) })).toString()

    /** Null if it isn't a copy this version can read. */
    private fun fromJson(s: String): List<PrivateRelay>? {
        val o = try { JSONObject(s) } catch (_: Exception) { return null }
        if (o.optInt("v", 1) != 1) return null
        val a = o.optJSONArray("relays") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
            PrivateRelay(parse(x.optString("url")) ?: return@mapNotNull null, x.optLong("at"), x.optBoolean("gone"))
        }
    }

    private fun merge(a: List<PrivateRelay>, b: List<PrivateRelay>) = (a + b).groupBy { it.url }.values.map { s -> s.maxBy { it.at } }.sortedBy { it.url }
}
