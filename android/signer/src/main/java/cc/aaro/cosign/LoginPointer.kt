package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * The account's login-page pointer (Keykin SPEC 11.1): kind 30078, d `keykin/login`, a `url` tag
 * holding the page's origin. A phone that has just joined a box publishes it, if the account has
 * none yet, so marble.syb.lol can send someone there by name.
 */
object LoginPointer {
    const val KIND = 30078
    const val D = "keykin/login"

    /** [addr] as a login page origin: https, a host, maybe a port, nothing after. Null if it isn't one. */
    fun origin(addr: String, allowHttp: Boolean = false): String? {
        val u = try { java.net.URI(addr.trim()) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val host = u.host?.lowercase() ?: return null
        if (!u.rawQuery.isNullOrEmpty() || !u.rawFragment.isNullOrEmpty() || u.userInfo != null) return null
        if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) return null
        val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) "" else ":${u.port}"
        return "$scheme://$host$port"
    }

    /** The newest pointer among [events] signed by [pubkey] (signatures checked), or null. */
    fun newest(events: List<Event>, pubkey: String): Event? =
        events.filter { it.kind == KIND && it.pubkey == pubkey && it.tags.any { t -> t.size > 1 && t[0] == "d" && t[1] == D } && it.verify() }
            .maxWithOrNull(compareBy<Event> { it.createdAt }.thenBy { it.id })

    fun urlOf(e: Event): String? = e.tags.firstOrNull { it.size > 1 && it[0] == "url" }?.get(1)?.trimEnd('/')

    sealed class Decision {
        /** Publish a pointer naming [origin]. */
        data class Publish(val origin: String) : Decision()
        /** Leave it: [why] says what was found. */
        data class Skip(val why: String) : Decision()
    }

    /**
     * Whether to publish after joining a box whose login page is [origin] (null: this box has none we
     * know of). [heard] is false when no relay answered, so we don't know whether one exists.
     * An existing pointer, even one naming another box, or one removed on purpose (no url), is left alone.
     */
    fun decide(origin: String?, existing: Event?, heard: Boolean): Decision = when {
        origin == null -> Decision.Skip("no login page")
        !heard -> Decision.Skip("no relay answered")
        existing == null -> Decision.Publish(origin)
        urlOf(existing) == null -> Decision.Skip("removed on purpose")
        urlOf(existing).equals(origin, ignoreCase = true) -> Decision.Skip("already names $origin")
        else -> Decision.Skip("names ${urlOf(existing)}")
    }
}
