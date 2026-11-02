package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.isHex32

/**
 * Something made in an app Marble doesn't draw (a 3D object, say): what it is in words, and where "Open" goes.
 * Never its raw content (often JSON) and never a nostr:… string. Shared with Marble Web (content-rules: other apps).
 */
object OtherApps {
    /** Kinds Marble shows itself: a post and an article. */
    fun drawn(kind: Int) = kind == 1 || kind == 30023

    /** Its NIP-31 alt, else its title, else its name, else "Something made in another app" (and which, if it says). */
    fun what(e: Event): String = listOf("alt", "title", "name").firstNotNullOfOrNull { e.firstTag(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }?.take(200)
        ?: ("Something made in another app" + (client(e)?.let { " · $it" } ?: ""))

    /** The card a quoted event gets (content-rules §3): "note", "article" or "other". */
    fun card(kind: Int) = when (kind) { 1 -> "note"; 30023 -> "article"; else -> "other" }

    /** The app it says it was made with (its client tag). */
    fun client(e: Event): String? = e.firstTag("client")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }

    /** The handler the client tag names (["client", name, "31990:pubkey:d", relay]), if it names one. */
    fun clientHandler(e: Event): Ref.Addr? {
        val t = e.tags.firstOrNull { it.size > 2 && it[0] == "client" } ?: return null
        val (k, pk, d) = t[2].split(':', limit = 3).takeIf { it.size == 3 } ?: return null
        if (k != "31990" || !isHex32(pk)) return null
        return Ref.Addr(31990, pk, d).apply { relays = listOfNotNull(t.getOrNull(3)?.takeIf { it.startsWith("wss://") }) }
    }

    /** The client's site, when its name is a web address (SNOcrash.art: https://snocrash.art). */
    fun clientSite(e: Event): String? = client(e)?.lowercase()?.takeIf { SITE.matches(it) }?.let { "https://$it" }
    private val SITE = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")

    /**
     * A handler's (kind 31990) web link for [e]: its "web" template for [e]'s kind of link (naddr for addressable
     * kinds, nevent otherwise), else its untyped one, with <bech32> filled in. Only https.
     */
    fun webLink(handler: Event, e: Event): String? {
        if (handler.kind != 31990) return null
        val entity = marbleLink(e).removePrefix("https://marble.syb.lol/")
        val type = if (e.kind in 30000..39999) "naddr" else "nevent"
        val webs = handler.tags.filter { it.size > 1 && it[0] == "web" && it[1].startsWith("https://") && "<bech32>" in it[1] }
        val t = webs.firstOrNull { it.getOrNull(2) == type } ?: webs.firstOrNull { it.size == 2 } ?: return null
        return t[1].replace("<bech32>", entity)
    }

    /**
     * Where "Open" takes [e] (content-rules §3), from what's been found: [named], the handler its client tag names; [found],
     * handlers (31990) and recommendations (31989) for its kind; [trusted], whose count (you, your follows, its author).
     * The named handler, else a trusted or trusted-recommended one, else the client's site, else its marble.syb.lol page.
     */
    fun open(e: Event, named: Event?, found: List<Event>, trusted: Set<String>): String {
        named?.takeIf { handles(it, e.kind) }?.let { webLink(it, e) }?.let { return it }
        val recommended = found.filter { it.kind == 31989 && it.pubkey in trusted }.flatMap { r -> r.tags.filter { it.size > 1 && it[0] == "a" }.map { it[1] } }.toSet()
        return found.filter { it.kind == 31990 && handles(it, e.kind) && (it.pubkey in trusted || "31990:${it.pubkey}:${it.firstTag("d")}" in recommended) }
            .sortedByDescending { it.createdAt }.firstNotNullOfOrNull { webLink(it, e) }
            ?: clientSite(e) ?: marbleLink(e)
    }

    /** Whether [handler] says it opens [kind]. */
    fun handles(handler: Event, kind: Int) = handler.kind == 31990 && handler.tags.any { it.size > 1 && it[0] == "k" && it[1] == kind.toString() }
}
