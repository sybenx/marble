package cc.aaro.cosign

import cc.aaro.qrst.core.Json
import cc.aaro.qrst.core.isHex32
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
        /** @name: only someone you follow, called that. Never from open search, which ranks impersonators. */
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
                // (The Nostr row waits a moment for their name and picture, so it doesn't change once it's there.)
                listOfNotNull(nostr.await()?.also { profiles(listOf(it)) }?.let { Row(pubkey = it) }, fedi.await())
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
        // The actor, or (where it won't answer an unsigned request, as mastodon.social does) Mastodon's public lookup.
        val a = actor.await()
        val api = if (a == null) get("https://$domain/api/v1/accounts/lookup?acct=$name", "application/json") else null
        val shown = ((a?.get("name") ?: api?.get("display_name")) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val icon = ((a?.get("icon") as? Map<*, *>)?.get("url") as? String ?: api?.get("avatar") as? String)?.takeIf { it.startsWith("https://") }
        val url = page ?: (a?.get("url") as? String) ?: (api?.get("url") as? String) ?: self
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

    /**
     * @name among [follows]: someone called that (their name, or their NIP-05's name part), at most two. Signed out,
     * or nobody you follow by that name: none. A display name or a NIP-05 from open search proves nothing: anyone can
     * call themselves elonmusk, and get an address somewhere.
     */
    fun followed(name: String, follows: Set<String>?, known: Map<String, Person>): List<String> {
        val n = SearchRank.norm(name).ifEmpty { return emptyList() }
        return follows.orEmpty().filter { pk ->
            val p = known[pk] ?: return@filter false
            SearchRank.norm(p.name ?: "") == n || SearchRank.norm(p.nip05?.substringBefore('@')?.takeIf { it != "_" } ?: "") == n
        }.sorted().take(2)
    }

    private suspend fun named(name: String, social: Social): List<Row> {
        val follows = social.follows.value ?: return emptyList()
        profiles(follows)
        return followed(name, follows, People.known.value).map { Row(pubkey = it) }
    }

    /** Their names and pictures, asked for and waited on a moment (1.5 s at most). */
    private suspend fun profiles(pks: Collection<String>) {
        val missing = pks.filter { it !in People.known.value }
        if (missing.isEmpty()) return
        People.want(missing)
        for (i in 0 until 15) { if (missing.all { it in People.known.value }) break; kotlinx.coroutines.delay(100) }
    }
}
