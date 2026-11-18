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
    /** Where the login page a box code or join text named is kept (its origin, from [fromCode]). */
    const val PREF = "login-origin-code"

    /** The login page a box code or join text named ([loginPage]), as an origin. Nothing named, nothing: never the box's address. */
    fun fromCode(loginPage: String?, allowHttp: Boolean = false): String? = loginPage?.let { origin(it, allowHttp) }

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

    sealed class Plan {
        /** No relay answered: try again next time. */
        object Wait : Plan() { override fun toString() = "Wait" }
        /** Nothing to do, and that's settled: [why]. */
        data class Leave(val why: String) : Plan()
        /** Send the same signed [event] again to the write relays [to] that lack it. */
        data class Resend(val event: Event, val to: List<String>) : Plan()
        /** No relay has any pointer: sign a new one naming [origin]. */
        data class Sign(val origin: String) : Plan()
    }

    /**
     * What to do about the pointer, given what each relay that answered had ([byRelay]; one that didn't
     * answer isn't there), this box's login page [origin] (null if unknown), and the account's write
     * relays [writes]. The person's choices stand: a pointer removed on purpose (no url), or one naming
     * another page, is never replaced or sent around again. With no login page known (no box code named
     * one), nothing is signed or sent around: not even a pointer already out there.
     */
    fun plan(origin: String?, pubkey: String, byRelay: Map<String, List<Event>>, writes: List<String>): Plan {
        if (origin == null) return Plan.Leave("no login page")
        if (byRelay.isEmpty()) return Plan.Wait
        val newest = newest(byRelay.values.flatten(), pubkey) ?: return Plan.Sign(origin)
        val url = urlOf(newest) ?: return Plan.Leave("removed on purpose")
        if (!url.equals(origin, ignoreCase = true)) return Plan.Leave("names $url")
        val missing = writes.filter { r -> byRelay[r]?.none { it.id == newest.id } == true }
        return if (missing.isEmpty()) Plan.Leave("on every write relay that answered") else Plan.Resend(newest, missing)
    }
}
