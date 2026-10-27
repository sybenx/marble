package cc.aaro.cosign

import cc.aaro.cosign.core.Json
import cc.aaro.cosign.core.isHex32
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Handles in the search box (shared with Marble Web and the Mac: handles-rule.md). "@" is no mode: Enter always
// searches the web. A handle only adds rows, and only for what a cheap lookup confirms; nothing goes anywhere by itself.

object Handles {
    sealed class Kind {
        /** name@domain, @name@domain, _@domain: NIP-05 and WebFinger, both asked at once. */
        data class Address(val name: String, val domain: String) : Kind()
        /** @name.bsky.social, @domain.tld: Bluesky. */
        data class Bsky(val handle: String) : Kind()
        /** @name: a Nostr person, only for an exact name whose NIP-05 checks out. */
        data class Name(val name: String) : Kind()
    }

    private const val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    private val ADDRESS = Regex("^@?([a-z0-9._~+-]+)@((?:$LABEL\\.)+[a-z]{2,24})$", RegexOption.IGNORE_CASE)
    private val BSKY = Regex("^@((?:$LABEL\\.)+[a-z]{2,24})$", RegexOption.IGNORE_CASE)
    private val NAME = Regex("^@([\\p{L}\\p{N}_]{2,30})$")

    /** What [typed] is, once it's complete (a dot and an ending of two letters or more after the @); null: nothing to ask. */
    fun kind(typed: String): Kind? {
        val t = typed.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        ADDRESS.matchEntire(t)?.let { return Kind.Address(it.groupValues[1].lowercase(), it.groupValues[2].lowercase()) }
        BSKY.matchEntire(t)?.let { return Kind.Bsky(it.groupValues[1].lowercase()) }
        NAME.matchEntire(t)?.let { return Kind.Name(it.groupValues[1]) }
        return null
    }

    /** A row: a Nostr person ([pubkey]: their profile in Marble), or a page elsewhere ([url]) with its title, line and picture. */
    data class Row(val pubkey: String? = null, val title: String = "", val sub: String = "", val picture: String? = null, val url: String? = null)

    // No cookies, no referrer (OkHttp sends none), short waits. NIP-05 never follows a redirect; WebFinger may (instances delegate).
    private val strict by lazy { Fetch.http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).callTimeout(2500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build() }
    private val loose by lazy { strict.newBuilder().followRedirects(true).followSslRedirects(true).build() }
    private val seen = ConcurrentHashMap<String, List<Row>>()

    /** What [k] confirms: Nostr, then the fediverse, then Bluesky. Asked once per address while Marble runs. */
    suspend fun look(k: Kind, social: Social): List<Row> {
        val key = k.toString()
        seen[key]?.let { return it }
        val rows = when (k) {
            is Kind.Address -> coroutineScope {
                val nostr = async { nip05(k.name, k.domain) }
                val fedi = async { fediverse(k.name, k.domain) }
                listOfNotNull(nostr.await()?.let { Row(pubkey = it) }, fedi.await())
            }
            is Kind.Bsky -> listOfNotNull(bluesky(k.handle))
            is Kind.Name -> named(k.name, social)
        }
        seen[key] = rows
        return rows
    }

    private suspend fun get(url: String, accept: String, redirects: Boolean = false): Map<*, *>? = withContext(Dispatchers.IO) {
        try {
            (if (redirects) loose else strict).newCall(okhttp3.Request.Builder().url(url).header("Accept", accept).build()).execute().use { r ->
                if (!r.isSuccessful) null else Json.parse(r.body?.string().orEmpty()) as? Map<*, *>
            }
        } catch (_: Exception) { null }
    }

    private suspend fun nip05(name: String, domain: String): String? {
        val names = get("https://$domain/.well-known/nostr.json?name=$name", "application/json")?.get("names") as? Map<*, *>
        return (names?.get(name) as? String)?.lowercase()?.takeIf { isHex32(it) }
    }

    /** WebFinger, then (at once) what software the server runs and who the actor is. */
    private suspend fun fediverse(name: String, domain: String): Row? = coroutineScope {
        val acct = "$name@$domain"
        val wf = get("https://$domain/.well-known/webfinger?resource=acct:$acct", "application/jrd+json, application/json", redirects = true) ?: return@coroutineScope null
        val links = (wf["links"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        val page = links.firstOrNull { it["rel"] == "http://webfinger.net/rel/profile-page" }?.get("href") as? String
        val self = links.firstOrNull { it["rel"] == "self" && (it["type"] as? String)?.contains("activity+json") == true }?.get("href") as? String
        if (page == null && self == null) return@coroutineScope null
        val software = async { software(domain) }
        val actor = async { self?.takeIf { it.startsWith("https://") }?.let { get(it, "application/activity+json", redirects = true) } }
        val a = actor.await()
        val shown = (a?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val icon = ((a?.get("icon") as? Map<*, *>)?.get("url") as? String)?.takeIf { it.startsWith("https://") }
        val url = page ?: (a?.get("url") as? String) ?: self
        Row(title = "Open on ${software.await() ?: domain}", sub = listOfNotNull(shown, "@$acct").joinToString(" · "), picture = icon, url = url)
    }

    private val SOFTWARE = listOf("Mastodon", "Pleroma", "Akkoma", "Misskey", "Sharkey", "GoToSocial", "Pixelfed", "Friendica", "Lemmy", "Firefish", "Iceshrimp", "PeerTube", "Hubzilla", "Mitra", "Takahe")
    private suspend fun software(domain: String): String? {
        val links = (get("https://$domain/.well-known/nodeinfo", "application/json", redirects = true)?.get("links") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("href") as? String }
        val info = links?.lastOrNull { it.startsWith("https://") }?.let { get(it, "application/json", redirects = true) } ?: return null
        val n = ((info["software"] as? Map<*, *>)?.get("name") as? String)?.lowercase() ?: return null
        return SOFTWARE.firstOrNull { it.lowercase() == n }
    }

    private suspend fun bluesky(handle: String): Row? {
        val p = get("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=$handle", "application/json") ?: return null
        val h = (p["handle"] as? String) ?: return null
        val shown = (p["displayName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return Row(title = "Open on Bluesky", sub = listOfNotNull(shown, "@$h").joinToString(" · "), picture = (p["avatar"] as? String)?.takeIf { it.startsWith("https://") },
            url = "https://bsky.app/profile/$h")
    }

    /** @name: someone Nostr search finds named exactly that (their name, or their NIP-05's name), whose NIP-05 checks out. A name alone proves nothing. */
    private suspend fun named(name: String, social: Social): List<Row> {
        social.search(name)
        var f = social.found.value
        for (i in 0 until 40) { f = social.found.value; if (f != null && f.query == name && !f.loading) break; kotlinx.coroutines.delay(100) }
        if (f == null || f.query != name) return emptyList()
        val known = People.known.value
        return f.people.take(20).filter { pk ->
            val p = known[pk]
            SearchRank.exact(p?.name, name) || SearchRank.exact(f.hits[pk]?.name, name) || SearchRank.exact(p?.nip05?.substringBefore('@')?.takeIf { it != "_" }, name)
        }.filter { pk -> Nip05.check(pk, known[pk]?.nip05) == Nip05.Check.OK }.take(2).map { Row(pubkey = it) }
    }
}
