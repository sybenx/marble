package cc.aaro.cosign.reader

import cc.aaro.qrst.core.Event
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reader's shared rules (marble-content-fixtures reader.md, agreed with Marble Web): what a person's post is, the keys
 * read state uses, when something is read, how two copies of the seen list and of the Reader list merge, and a feed
 * item's discussion link. The phone and the web compute these the same way (fixtures/reader/vectors.json).
 */
object ReaderRules {
    enum class Kind { ITEM, SHORT, REPLY }

    private val URL = Regex("""(?i)\bhttps?://[^\s<>"]+""")
    /** A Blossom picture or video (BUD-10). */
    private val BLOSSOM_MEDIA = Regex("""(?i)blossom:[0-9a-f]{64}\.(jpe?g|png|gif|webp|avif|mp4|webm|mov|m4v)\b""")
    /** A quoted post or article (with or without nostr:). */
    private val QUOTES = Regex("""(?i)(?<![\p{L}\p{N}_/])(?:nostr:)?(nevent|note|naddr)1[02-9ac-hj-np-z]+""")

    /** A person's post in Reader: an article or a post with a link or media is an item; any other post is short; replies don't show. */
    fun kindOf(e: Event): Kind {
        if (e.tags.any { it.size > 1 && it[0] == "e" && it.getOrNull(3) != "mention" }) return Kind.REPLY
        if (e.kind == 30023) return Kind.ITEM
        if (e.tags.any { it.isNotEmpty() && it[0] == "imeta" }) return Kind.ITEM
        if (URL.containsMatchIn(e.content) || BLOSSOM_MEDIA.containsMatchIn(e.content) || QUOTES.containsMatchIn(e.content)) return Kind.ITEM
        if (e.tags.any { it.size > 1 && it[0] == "q" }) return Kind.ITEM
        return Kind.SHORT
    }

    /** One person's short posts of one local day, as one row: its read-state key. */
    fun digestKey(pubkey: String, createdAt: Long, offset: Long = 0): String = "p" + pubkey.take(8) + ":" + Math.floorDiv(createdAt + offset, 86_400L)

    /** A feed item's read-state key: r and 16 hex of SHA-256 of its link (or its guid without one). */
    fun feedItemKey(link: String?, guid: String?): String {
        val s = link?.takeIf { it.isNotEmpty() } ?: guid.orEmpty()
        return "r" + java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** An article's (any addressable event's) read-state key: a and 16 hex of SHA-256 of kind:pubkey:d, the same after an edit. */
    fun addressKey(kind: Int, pubkey: String, d: String): String =
        "a" + java.security.MessageDigest.getInstance("SHA-256").digest("$kind:$pubkey:$d".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A post's key, or an addressable event's. */
    fun keyOf(e: Event): String = if (e.kind in 30000..39999) addressKey(e.kind, e.pubkey, e.firstTag("d") ?: "") else postKey(e.id)

    /** Fits NIP-44 (reader.md §2): upto and unread entries older than 30 days go; then while over [max] bytes, the oldest day of keys. */
    fun fitSeen(o: JSONObject, now: Long, max: Int = MAX_SEEN): JSONObject {
        val cutoff = now - UNREAD_FOR
        for (name in listOf("upto", "unread")) o.optJSONObject(name)?.let { m -> m.keys().asSequence().toList().forEach { k -> if (Math.abs(m.getLong(k)) <= cutoff) m.remove(k) } }
        fun big() = o.toString().toByteArray().size > max
        // The oldest days first, but never the newest one; then the oldest read-up-tos; then the oldest read-again marks.
        o.optJSONObject("days")?.let { days -> while (big() && days.length() > 1) days.remove(days.keys().asSequence().minBy { it.toLongOrNull() ?: Long.MAX_VALUE }) }
        o.optJSONObject("upto")?.let { m -> while (big() && m.length() > 0) m.remove(m.keys().asSequence().minBy { m.getLong(it) }) }
        o.optJSONObject("unread")?.let { m -> while (big() && m.keys().asSequence().any { m.getLong(it) < 0 }) m.remove(m.keys().asSequence().filter { m.getLong(it) < 0 }.minBy { -m.getLong(it) }) }
        return o
    }

    /** Still in Reader (§4): unread (up to 30 days, filtered before this), read but not aged yet, or starred. */
    fun stays(read: Boolean, at: Long, type: String, starred: Boolean, now: Long): Boolean = !read || now - at < keepFor(type) || starred

    /** A Reader item for [ageUpto]: its source, its time, whether it's read, and whether it has aged out. */
    data class Aged(val source: String, val at: Long, val read: Boolean, val aged: Boolean)

    /**
     * Read-up-to as items age out (reader.md §2, vectors readUpTo): to the newest aged-out read item's time, but never
     * to or past an unread item of that source (at most the oldest unread one's time − 1). Only the sources that rise.
     */
    fun ageUpto(items: List<Aged>, upto: Map<String, Long>): Map<String, Long> = items.groupBy { it.source }.mapNotNull { (src, l) ->
        val to = l.filter { it.read && it.aged }.maxOfOrNull { it.at } ?: return@mapNotNull null
        val cap = l.filter { !it.read }.minOfOrNull { it.at - 1 } ?: Long.MAX_VALUE
        minOf(to, cap).takeIf { it > (upto[src] ?: 0) }?.let { src to it }
    }.toMap()
    const val MAX_SEEN = 48_000

    /** Open in… for a post or an article (reader.md §5): in this order. */
    val OPEN_IN = listOf("Your Nostr app", "Marble", "njump.me")

    /** A Nostr post's read-state key (as the seen list has always used): its id's first 16 hex. */
    fun postKey(id: String) = id.take(16)

    /** The read state, from the seen list: keys seen (key to its day number), each source's (and people folder's) "read up to", and kept-unread items. */
    data class Seen(val days: Map<String, Long>, val upto: Map<String, Long>, val unread: Map<String, Long>)

    /**
     * Read (reader.md §2): seen, or no newer than its source's read-up-to, or (in a folder of people) the folder's.
     * Kept unread (a positive time) wins, unless the mark is no newer than that read-up-to (Mark all as read came
     * after it). A read-again mark is negative.
     */
    fun isRead(key: String, source: String, at: Long, s: Seen, folder: String? = null): Boolean {
        val upto = maxOf(s.upto[source] ?: Long.MIN_VALUE, folder?.let { s.upto["folder:$it"] } ?: Long.MIN_VALUE)
        val u = s.unread[key] ?: 0
        if (u > 0 && u > upto) return false
        return key in s.days || at <= upto
    }

    /** Two copies of the seen list: days joined, read-up-to the larger, kept-unread the newer, other fields kept. */
    fun mergeSeen(a: JSONObject, b: JSONObject): JSONObject {
        val out = JSONObject()
        for (o in listOf(b, a)) o.keys().forEach { k -> if (k !in setOf("v", "at", "days", "upto", "unread")) out.put(k, o.get(k)) }
        val days = JSONObject()
        for (o in listOf(a, b)) o.optJSONObject("days")?.let { d -> d.keys().forEach { k ->
            val have = days.optString(k).split(' ').filter { it.isNotEmpty() }
            days.put(k, (have + d.optString(k).split(' ').filter { it.isNotEmpty() }).distinct().joinToString(" "))
        } }
        fun join(name: String) = JSONObject().also { r -> for (o in listOf(a, b)) o.optJSONObject(name)?.let { m -> m.keys().forEach { k -> r.put(k, maxOf(r.optLong(k, Long.MIN_VALUE), m.getLong(k))) } } }
        // Kept unread: the later of the two by size (a negative time is "read again then").
        val unread = JSONObject().also { r -> for (o in listOf(a, b)) o.optJSONObject("unread")?.let { m -> m.keys().forEach { k ->
            val t = m.getLong(k); if (!r.has(k) || Math.abs(t) > Math.abs(r.getLong(k))) r.put(k, t) } } }
        return out.put("v", 1).put("at", maxOf(a.optLong("at"), b.optLong("at"))).put("days", days).put("upto", join("upto")).put("unread", unread)
    }

    /** Two copies of the Reader list (sources, folders, mutes): per entry the newest `at` wins; the toggle too; other fields kept. */
    fun mergeReader(a: JSONObject, b: JSONObject): JSONObject {
        val out = JSONObject()
        for (o in listOf(b, a)) o.keys().forEach { k -> if (k !in setOf("sources", "folders", "people", "muted")) out.put(k, o.get(k)) }
        fun each(name: String, key: String): JSONArray {
            val by = LinkedHashMap<String, JSONObject>()
            for (o in listOf(a, b)) o.optJSONArray(name)?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { e ->
                val id = e.optString(key); val have = by[id]
                if (have == null || e.optLong("at") > have.optLong("at")) by[id] = e
            } }
            return JSONArray(by.values.toList())
        }
        val pa = a.optJSONObject("people"); val pb = b.optJSONObject("people")
        val people = listOfNotNull(pa, pb).maxByOrNull { it.optLong("at") }
        return out.put("v", 1).put("sources", each("sources", "id")).put("folders", each("folders", "id")).put("muted", each("muted", "source"))
            .apply { people?.let { put("people", it) } }
    }

    private val DISCUSSION = listOf(Regex("""^https?://news\.ycombinator\.com/item\?id="""), Regex("""^https?://(www\.)?lobste\.rs/s/"""), Regex("""^https?://(www\.|old\.)?reddit\.com/r/[^/]+/comments/"""))
    private fun isDiscussion(u: String?) = u != null && DISCUSSION.any { it.containsMatchIn(u) }

    /**
     * A feed item's discussion link, or "" (reader.md §5): RSS <comments>, Atom rel=replies, the guid or link when it's a
     * discussion page itself, a "Comments URL" in the description (hnrss); never the item's own link.
     */
    fun discussion(link: String?, comments: String?, replies: String?, guid: String?, description: String?): String {
        val fromText = description?.let { Regex("""Comments URL:\s*(?:<a [^>]*href="([^"]+)"|(\S+))""").find(it) }?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        val found = listOfNotNull(comments?.takeIf { it.isNotBlank() }, replies?.takeIf { it.isNotBlank() }, guid?.takeIf(::isDiscussion), link?.takeIf(::isDiscussion), fromText)
            .map { it.trim() }.firstOrNull() ?: return ""
        return if (found == link?.trim()) "" else found
    }

    /** How long a read item stays in the river (seconds): news a day, people two, articles a week. Unread stay up to 30 days. */
    fun keepFor(type: String): Long = when (type) { "news" -> 86_400L; "people" -> 2 * 86_400L; else -> 7 * 86_400L }
    const val UNREAD_FOR = 30 * 86_400L
}
