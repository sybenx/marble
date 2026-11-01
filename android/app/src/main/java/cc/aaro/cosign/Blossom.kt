package cc.aaro.cosign

import cc.aaro.cosign.core.isHex32
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Blossom links (BUD-10): `blossom:<sha256>.<ext>?xs=server&as=<pubkey>&sz=<bytes>`, a file by its hash with hints
 * where to find it. Found in order: the link's servers (https, then http), each author's server list (BUD-03,
 * kind 10063), then a few well-known servers; a server's copy counts only if its size matches `sz` (when given).
 * Shared with Marble Web (content-rules: Blossom links).
 */
object Blossom {
    data class Link(val raw: String, val sha: String, val ext: String, val servers: List<String>, val authors: List<String>, val size: Long?) {
        val file get() = "$sha.$ext"
        val isImage get() = ext in IMAGES
        val isVideo get() = ext in VIDEOS
    }

    /** One in a post's text (the query ends at whitespace, as a link's does). */
    val PATTERN = Regex("blossom:[0-9a-fA-F]{64}\\.[A-Za-z0-9]{1,10}(?:\\?[^\\s<>\"]*)?")
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")
    private val VIDEOS = setOf("mp4", "webm", "mov", "m4v")
    /** Asked last, when the link's hints don't have it. */
    val WELL_KNOWN = listOf("https://blossom.primal.net", "https://cdn.satellite.earth", "https://blossom.band")

    fun parse(s: String): Link? {
        val m = Regex("^blossom:([0-9a-fA-F]{64})\\.([A-Za-z0-9]{1,10})(?:\\?(.*))?$").find(s.trim()) ?: return null
        val params = m.groupValues[3].split('&').filter { '=' in it }.map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val xs = params.filter { it.first == "xs" }.map { it.second.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        val authors = params.filter { it.first == "as" }.map { it.second.lowercase() }.filter { isHex32(it) }
        return Link(s.trim(), m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), xs, authors, params.firstOrNull { it.first == "sz" }?.second?.toLongOrNull()?.takeIf { it > 0 })
    }

    /** Where to ask first: the link's servers (a bare domain: https, then http). */
    fun hinted(l: Link): List<String> = l.servers.flatMap { s ->
        if (s.startsWith("https://") || s.startsWith("http://")) listOf("$s/${l.file}") else listOf("https://$s/${l.file}", "http://$s/${l.file}")
    }

    private val found = ConcurrentHashMap<String, String>()
    private val serverLists = ConcurrentHashMap<String, List<String>>()
    private val http by lazy { Fetch.http.newBuilder().callTimeout(8, TimeUnit.SECONDS).followRedirects(true).build() }

    /** A web address that has this file (asked once per file while Marble runs); null: nowhere found. */
    suspend fun resolve(raw: String): String? {
        found[raw]?.let { return it }
        val l = parse(raw) ?: return null
        val tried = HashSet<String>()
        suspend fun try1(url: String): Boolean {
            if (!tried.add(url)) return false
            return withContext(Dispatchers.IO) {
                try {
                    http.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { r ->
                        r.isSuccessful && (l.size == null || r.header("Content-Length")?.toLongOrNull()?.let { it == l.size } ?: true)
                    }
                } catch (_: Exception) { false }
            }
        }
        for (u in hinted(l)) if (try1(u)) return u.also { found[raw] = it }
        for (pk in l.authors) for (s in serversOf(pk)) if (try1("${s.trimEnd('/')}/${l.file}")) return "${s.trimEnd('/')}/${l.file}".also { found[raw] = it }
        for (s in WELL_KNOWN) if (try1("$s/${l.file}")) return "$s/${l.file}".also { found[raw] = it }
        return null
    }

    /** Someone's Blossom servers (BUD-03, kind 10063), in their order. */
    suspend fun serversOf(pk: String): List<String> = serverLists[pk] ?: Fetch.query(Sources.PROFILES + Sources.GENERAL, mapOf("kinds" to listOf(10063), "authors" to listOf(pk), "limit" to 2), 5000)
        .filter { it.pubkey == pk && it.kind == 10063 }.maxByOrNull { it.createdAt }
        ?.tags?.filter { it.size > 1 && it[0] == "server" && (it[1].startsWith("https://") || it[1].startsWith("http://")) }?.map { it[1] }.orEmpty()
        .also { serverLists[pk] = it }
}
