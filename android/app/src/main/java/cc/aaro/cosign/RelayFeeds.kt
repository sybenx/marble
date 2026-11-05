package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * Relays read as feeds (Following › Relays): a relay's own posts, newest first, as Marble Web's relay page shows them;
 * your favourite relays (NIP-51 kind 10012, `relay` tags) and relay sets (kind 30002: `d`, `title`, `relay` tags), the
 * lists Jumble keeps too. A relay's link here is marble.syb.lol/relay/<address>, the web's.
 */
object RelayFeeds {
    /** A relay as typed or linked ("pyramid.fiatjaf.com/popular", "wss://nos.lol/"): its wss:// address and short form; null: not a relay's. */
    data class Relay(val url: String, val address: String)

    fun of(typed: String): Relay? {
        val t = typed.trim()
        val m = Regex("^(?:(wss?)://)?([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?::\\d+)?)(/[^\\s?#]*)?/?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
        val host = m.groupValues[2].lowercase()
        val address = host + m.groupValues[3].trimEnd('/')
        return Relay((if (m.groupValues[1].lowercase() == "ws") "ws" else "wss") + "://" + address, address)
    }

    /** The relay a marble.syb.lol/relay/… link opens. */
    fun fromLink(url: String): Relay? {
        val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
        if (u.host?.lowercase()?.removePrefix("www.") != "marble.syb.lol") return null
        val p = u.path.orEmpty()
        return if (p.startsWith("/relay/")) of(p.removePrefix("/relay/")) else null
    }

    fun link(r: Relay) = "https://marble.syb.lol/relay/" + r.address

    /** A list's relays (10012 or 30002), in order, once each. */
    fun relaysIn(e: Event?): List<Relay> = e?.tags.orEmpty().filter { it.size > 1 && it[0] == "relay" }.mapNotNull { of(it[1]) }.distinctBy { it.url }

    data class RelaySet(val d: String, val title: String, val relays: List<Relay>, val event: Event)

    /** Your sets, newest version of each, those with relays. */
    fun sets(events: List<Event>, me: String): List<RelaySet> = events.filter { it.kind == 30002 && it.pubkey == me }
        .groupBy { it.firstTag("d") ?: "" }.values.map { l -> l.maxBy { it.createdAt } }
        .map { e -> RelaySet(e.firstTag("d") ?: "", e.firstTag("title")?.trim()?.ifEmpty { null } ?: e.firstTag("name")?.trim()?.ifEmpty { null } ?: e.firstTag("d") ?: "A set", relaysIn(e), e) }
        .filter { it.relays.isNotEmpty() }.sortedBy { it.title.lowercase() }

    /** A list with [r] added (at the end) or taken out; the rest as it was. */
    fun with(tags: List<List<String>>, r: Relay, on: Boolean): List<List<String>> {
        val rest = tags.filterNot { it.size > 1 && it[0] == "relay" && of(it[1])?.url == r.url }
        return if (on) rest + listOf(listOf("relay", r.url)) else rest
    }

    /**
     * Relays the people you follow write to (their kind 10002 lists), most used first: for each, up to two of them by
     * name. Ones you've kept already, and the big general ones everyone has, are left out.
     */
    fun peopleUse(lists: List<Event>, follows: Set<String>, kept: Set<String>, common: Set<String>): List<Pair<Relay, List<String>>> {
        val by = HashMap<String, MutableList<String>>(); val relays = HashMap<String, Relay>()
        lists.filter { it.kind == 10002 && it.pubkey in follows }.groupBy { it.pubkey }.values.map { l -> l.maxBy { it.createdAt } }.forEach { l ->
            l.tags.filter { it.size > 1 && it[0] == "r" && (it.getOrNull(2) == null || it[2] == "write") }.mapNotNull { of(it[1]) }.distinctBy { it.url }.forEach { r ->
                relays[r.url] = r; by.getOrPut(r.url) { ArrayList() } += l.pubkey
            }
        }
        return by.entries.filter { it.key !in kept && it.key !in common && it.value.size >= 2 }.sortedByDescending { it.value.size }
            .map { relays[it.key]!! to it.value }.take(12)
    }
}
