package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * The account's login-page pointer (Keykin SPEC 11.1). Kind 10046 with a `nostrconnect_url` template
 * `<login origin>/#connect=<nostrconnect>` (Aaron, 2026-11-19), and during the move also the old kind 30078
 * (d `keykin/login`, a `url` tag holding the page's origin). A phone that has just joined a box, or signs a
 * site in by a nostrconnect code, keeps both on the account's write relays so sites can send someone there
 * by name.
 */
object LoginPointer {
    const val KIND = 10046
    /** The old kind, kept in place alongside [KIND] until every reader reads 10046. */
    const val LEGACY = 30078
    const val D = "keykin/login"
    const val TAG = "nostrconnect_url"
    const val PLACEHOLDER = "<nostrconnect>"
    /** Both kinds, newest first in importance: the order they are checked and signed in. */
    val KINDS = listOf(KIND, LEGACY)
    /** Where the login page a box code or join text named is kept (its origin, from [fromCode]). */
    const val PREF = "login-origin-code"

    /** The login page a box code or join text named ([loginPage]), as an origin. Nothing named, nothing: never the box's address. */
    fun fromCode(loginPage: String?, allowHttp: Boolean = false): String? = loginPage?.let { origin(it, allowHttp) }

    /** [addr] as a login page origin: https, a host, maybe a port, nothing after. Null if it isn't one. */
    fun origin(addr: String, allowHttp: Boolean = false): String? {
        val u = try { java.net.URI(addr.trim()) } catch (_: Exception) { return null }
        if (!u.rawQuery.isNullOrEmpty() || !u.rawFragment.isNullOrEmpty()) return null
        if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) return null
        return originOf(u, allowHttp)
    }
    private fun originOf(u: java.net.URI, allowHttp: Boolean): String? {
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val host = u.host?.lowercase() ?: return null
        if (u.userInfo != null) return null
        val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) "" else ":${u.port}"
        return "$scheme://$host$port"
    }

    /** Keykin's template for a login page at [origin]: `connect=` last (SPEC 11.6). */
    fun template(origin: String) = "$origin/#connect=$PLACEHOLDER"

    /**
     * The origin a `nostrconnect_url` template sends people to, or null if it is not a usable one: one
     * [PLACEHOLDER], at its very end, an https (or, for a debug build's own page, http) address with no user.
     */
    fun templateOrigin(tpl: String, allowHttp: Boolean = false): String? {
        val t = tpl.trim()
        if (!t.endsWith(PLACEHOLDER) || t.indexOf(PLACEHOLDER) != t.length - PLACEHOLDER.length) return null
        val u = try { java.net.URI(t.removeSuffix(PLACEHOLDER)) } catch (_: Exception) { return null }
        return originOf(u, allowHttp)
    }

    /** The tags of a pointer of [kind] naming [origin], or one taking it away (null). */
    fun tags(kind: Int, origin: String?): List<List<String>> = when (kind) {
        KIND -> origin?.let { listOf(listOf(TAG, template(it))) } ?: emptyList()
        else -> listOf(listOf("d", D)) + (origin?.let { listOf(listOf("url", it)) } ?: emptyList())
    }

    private fun isPointer(e: Event, kind: Int) = e.kind == kind && (kind == KIND || e.tags.any { t -> t.size > 1 && t[0] == "d" && t[1] == D })

    /** The newest pointer of [kind] among [events] signed by [pubkey] (signatures checked), or null. */
    fun newest(events: List<Event>, pubkey: String, kind: Int = KIND): Event? =
        events.filter { isPointer(it, kind) && it.pubkey == pubkey && it.verify() }
            .maxWithOrNull(compareBy<Event> { it.createdAt }.thenBy { it.id })

    /** What a pointer names: its template's (10046) or its url's (30078) origin as written; null when it has none (removed). */
    fun urlOf(e: Event): String? = when (e.kind) {
        KIND -> e.tags.firstOrNull { it.size > 1 && it[0] == TAG }?.get(1)?.let { templateOrigin(it, allowHttp = true) ?: it }
        else -> e.tags.firstOrNull { it.size > 1 && it[0] == "url" }?.get(1)?.trimEnd('/')
    }

    /** Send the same signed [event] again to the write relays [to] that lack it. */
    data class Copy(val event: Event, val to: List<String>)

    sealed class Plan {
        /** No relay answered: try again next time. */
        object Wait : Plan() { override fun toString() = "Wait" }
        /** Nothing to do, and that's settled: [why]. */
        data class Leave(val why: String) : Plan()
        /** Sign a new pointer naming [origin] of each kind in [sign] (no relay has any of that kind), and send the [resend] copies again. */
        data class Act(val origin: String, val sign: List<Int> = emptyList(), val resend: List<Copy> = emptyList()) : Plan()
    }

    /**
     * What to do about the pointer, given what each relay that answered had ([byRelay]; one that didn't
     * answer isn't there), this box's login page [origin] (null if unknown), and the account's write
     * relays [writes]. It runs on both kinds. The person's choices stand: if the newest of either kind was
     * removed on purpose (no url) or names another page, nothing of either kind is signed or sent around.
     * Otherwise a kind with a pointer naming this page is sent again as it is to any write relay lacking it,
     * and a kind with none anywhere is signed anew. With no login page known (no box code named one),
     * nothing is signed or sent around: not even a pointer already out there.
     */
    fun plan(origin: String?, pubkey: String, byRelay: Map<String, List<Event>>, writes: List<String>): Plan {
        if (origin == null) return Plan.Leave("no login page")
        if (byRelay.isEmpty()) return Plan.Wait
        val all = byRelay.values.flatten()
        val newest = KINDS.associateWith { newest(all, pubkey, it) }
        for ((kind, e) in newest) {
            e ?: continue
            val url = urlOf(e) ?: return Plan.Leave("removed on purpose ($kind)")
            if (!url.equals(origin, ignoreCase = true)) return Plan.Leave("names $url ($kind)")
        }
        val sign = KINDS.filter { newest[it] == null }
        val resend = KINDS.mapNotNull { k -> newest[k]?.let { e -> writes.filter { r -> byRelay[r]?.none { it.id == e.id } == true }.ifEmpty { null }?.let { Copy(e, it) } } }
        if (sign.isEmpty() && resend.isEmpty()) return Plan.Leave("on every write relay that answered")
        return Plan.Act(origin, sign, resend)
    }
}
