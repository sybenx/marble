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

    /** A person's post in Reader: an article or a post with a link or media is an item; any other post is short; replies don't show. */
    fun kindOf(e: Event): Kind {
        if (e.tags.any { it.size > 1 && it[0] == "e" && it.getOrNull(3) != "mention" }) return Kind.REPLY
        if (e.kind == 30023) return Kind.ITEM
        if (e.tags.any { it.isNotEmpty() && it[0] == "imeta" }) return Kind.ITEM
        if (URL.containsMatchIn(e.content)) return Kind.ITEM
        return Kind.SHORT
    }

    /** One person's short posts of one local day, as one row: its read-state key. */
    fun digestKey(pubkey: String, createdAt: Long, offset: Long = 0): String = "p" + pubkey.take(8) + ":" + Math.floorDiv(createdAt + offset, 86_400L)

    /** A feed item's read-state key: r and 16 hex of SHA-256 of its link (or its guid without one). */
    fun feedItemKey(link: String?, guid: String?): String {
        val s = link?.takeIf { it.isNotEmpty() } ?: guid.orEmpty()
        return "r" + java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** A Nostr post's read-state key (as the seen list has always used): its id's first 16 hex. */
    fun postKey(id: String) = id.take(16)

    /** The read state, from the seen list: keys seen by day, each source's "read up to", and kept-unread items. */
    data class Seen(val days: Set<String>, val upto: Map<String, Long>, val unread: Map<String, Long>)

    /** Read: seen, or older than its source's read-up-to; unless kept unread. */
    fun isRead(key: String, source: String, at: Long, s: Seen): Boolean {
        if (key in s.unread) return false
        return key in s.days || (s.upto[source]?.let { at <= it } ?: false)
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
        return out.put("v", 1).put("at", maxOf(a.optLong("at"), b.optLong("at"))).put("days", days).put("upto", join("upto")).put("unread", join("unread"))
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
