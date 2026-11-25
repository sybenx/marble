// Being told no (good-guest §3.5): a Blossom server's 402, 403, 413, 429 or 507 is an answer, not an error and not
// missing data. The device stops that kind of request to that server until its Retry-After, or else for a wait that
// doubles from a minute up to a day, and says plainly which server said what. A 503 counts when it says when to come
// back. The same rules as the JS engine's js/vault/refusals.js in ~/kist; both check against vectors/refusals.json.
//
// A kind of request: "upload", "get" or "delete". A 429 is about the server, not the kind: every kind waits.
package cc.aaro.vaults

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What a server said when it refused: the HTTP [status], its X-Reason, and Retry-After as sent. */
class BlobRefused(val status: Int, val reason: String?, val retryAfter: String?) : Exception("refused: $status ${reason ?: ""}".trim())

object Refusing {
    val CODES = setOf(402, 403, 413, 429, 507)
    const val FIRST_WAIT = 60L
    const val LONGEST_WAIT = 86_400L
    private val PHRASE = mapOf(402 to "payment required", 403 to "not allowed", 413 to "too large", 429 to "too many requests", 503 to "busy", 507 to "out of space")
    private val WHAT = mapOf("upload" to "storage", "get" to "downloads", "delete" to "deleting")

    fun isRefusal(status: Int, retryAfter: String?): Boolean = status in CODES || (status == 503 && !retryAfter.isNullOrEmpty())

    /** Retry-After in seconds from [t]: seconds, or an HTTP date. Null when absent or unreadable. */
    fun retryAfterSeconds(value: String?, t: Long): Long? {
        val v = value?.trim() ?: return null
        if (v.isNotEmpty() && v.all { it.isDigit() }) return v.toLongOrNull()
        return try { maxOf(0L, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - t) } catch (_: Exception) { null }
    }
    /** The wait after the [strikes]-th refusal in a row with no Retry-After: 1 min, 2, 4, … up to a day. */
    fun backoff(strikes: Int): Long = minOf(LONGEST_WAIT, FIRST_WAIT shl (strikes - 1).coerceIn(0, 20))

    fun host(server: String): String = try { java.net.URI(server).let { u -> u.host.lowercase() + if (u.port > 0) ":${u.port}" else "" } } catch (_: Exception) { server }

    /**
     * The line Notes shows. [whenAt] words the time ("at 3 pm", "tomorrow at 9 am").
     *   "blossom.x says too many requests, trying again at 3 pm" / "blossom.x refused storage: 402 payment required"
     */
    fun line(r: Refusal, whenAt: (Long) -> String): String {
        if (r.status == 429) return "${r.host} says too many requests, trying again ${whenAt(r.until)}"
        val why = r.reason ?: PHRASE[r.status] ?: ""
        val l = "${r.host} refused ${WHAT[r.kind] ?: "a request"}: ${r.status}${if (why.isNotEmpty()) " $why" else ""}"
        return if (r.status == 503) "$l, trying again ${whenAt(r.until)}" else l
    }
}

data class Refusal(val server: String, val host: String, val kind: String, val status: Int, val reason: String?, val retryAfter: Long?, val at: Long, val until: Long, val strikes: Int)

/** Which servers refused what, until when. Thread-safe; kept for the life of the process. */
class Refusals {
    private val map = LinkedHashMap<String, Refusal>()
    private fun key(server: String, kind: String) = "$kind ${Refusing.host(server)}"

    @Synchronized fun waiting(server: String, kind: String, t: Long): Refusal? =
        listOf(key(server, kind), key(server, "*")).firstNotNullOfOrNull { k -> map[k]?.takeIf { it.until > t } }
    fun open(servers: List<String>, kind: String, t: Long): List<String> = servers.filter { waiting(it, kind, t) == null }

    @Synchronized fun refused(server: String, kind: String, status: Int, reason: String?, retryAfter: String?, t: Long): Refusal {
        val k2 = if (status == 429) "*" else kind
        val k = key(server, k2)
        val strikes = (map[k]?.strikes ?: 0) + 1
        val said = Refusing.retryAfterSeconds(retryAfter, t)
        val wait = minOf(Refusing.LONGEST_WAIT, if (said != null && said > 0) said else Refusing.backoff(strikes))
        val clean = reason?.trim()?.replace(Regex("\\s+"), " ")?.take(80)?.ifEmpty { null }
        return Refusal(server, Refusing.host(server), k2, status, clean, said, t, t + wait, strikes).also { map[k] = it }
    }
    fun refused(server: String, kind: String, e: BlobRefused, t: Long) = refused(server, kind, e.status, e.reason, e.retryAfter, t)

    @Synchronized fun answered(server: String, kind: String) { map.remove(key(server, kind)); map.remove(key(server, "*")) }
    /** Refusals still holding something back at [t], newest first. */
    @Synchronized fun active(t: Long): List<Refusal> = map.values.filter { it.until > t }.sortedByDescending { it.at }
    fun nextTry(servers: List<String>, kind: String, t: Long): Long? = servers.mapNotNull { waiting(it, kind, t)?.until }.minOrNull()
    @Synchronized fun clear() = map.clear()
}
