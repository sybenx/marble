package cc.aaro.cosign.reader

/**
 * When this device may write a synced copy on your account (Reader's marble/reader and discover/seen):
 * marble-content-fixtures fixtures/sync/guard.json and reader.md §6, shared with Marble Web. A read reached the
 * account when more than half of your write relays (where copies go) said they were done; read relays don't count. Never written under a newer copy this device has seen, until
 * three complete reads in a row have missed it, or it has expired on relays; then started again from what's there.
 */
object SyncGuard {
    enum class Do { WAIT, WRITE, REBASE }
    data class Decision(val doThis: Do, val misses: Int)

    private fun norm(r: Collection<String>) = r.map { it.trimEnd('/') }.toSet()

    /** Reached the account: more than half of the write relays (every one in the list, down or not) sent EOSE. */
    fun complete(write: Collection<String>, answeredBy: Collection<String>): Boolean {
        val w = norm(write); val a = norm(answeredBy)
        return w.isNotEmpty() && w.count { it in a } * 2 > w.size
    }

    /** Every write relay sent EOSE: only such reads count toward the escape. */
    fun full(write: Collection<String>, answeredBy: Collection<String>): Boolean = norm(write).let { w -> w.isNotEmpty() && norm(answeredBy).containsAll(w) }

    /** A write is done: more than half of the write relays said OK (an OK from a relay not on the list doesn't count). */
    fun written(write: Collection<String>, okBy: Collection<String>): Boolean {
        val w = norm(write); val o = norm(okBy)
        return w.isNotEmpty() && w.count { it in o } * 2 > w.size
    }

    /** The wait after the [failures]-th failure in a row: 60 · 2^(n−1) s, at most 600 s. */
    fun retryAfter(failures: Int): Long = minOf(600L, 60L shl (maxOf(failures, 1) - 1).coerceAtMost(10))

    /**
     * [complete]: reached; [full]: every write relay answered. [found]: the newest copy's created_at the relays gave (0:
     * none); [seenAt]: the newest seen here; [expires]: a copy's life on relays (0: for good). Only full reads count a
     * miss; a read where any write relay didn't answer neither counts nor rebases (it may hold the copy).
     */
    fun decide(complete: Boolean, full: Boolean, found: Long, seenAt: Long, misses: Int, expires: Long, now: Long): Decision = when {
        !complete -> Decision(Do.WAIT, misses)
        seenAt == 0L || found >= seenAt -> Decision(Do.WRITE, 0)
        expires > 0 && now > seenAt + expires -> Decision(Do.REBASE, 0)
        !full -> Decision(Do.WAIT, misses)
        misses + 1 >= MISSES -> Decision(Do.REBASE, 0)
        else -> Decision(Do.WAIT, misses + 1)
    }
    const val MISSES = 3

    /** A relay's address as both apps write it: scheme://lowercase-host + path, no trailing slash. */
    fun normRelay(u: String): String {
        val t = u.trim().trimEnd('/')
        val i = t.indexOf("://").takeIf { it > 0 } ?: return t
        val rest = t.substring(i + 3); val cut = rest.indexOf('/').let { if (it < 0) rest.length else it }
        return t.substring(0, i).lowercase() + "://" + rest.substring(0, cut).lowercase() + rest.substring(cut)
    }

    /** A 10002 list's write relays (reader.md §6): every r tag not marked read, ws:// or wss://, once each, no cap. */
    fun writeList(tags: List<List<String>>): List<String> = tags.filter { it.size > 1 && it[0] == "r" && it.getOrNull(2) != "read" && (it[1].trim().startsWith("wss://", true) || it[1].trim().startsWith("ws://", true)) }
        .map { normRelay(it[1]) }.distinct()

    /** Misses count against one copy: once the newest seen moves on ([countedFor] isn't [seenAt]), they start again. */
    fun misses(countedFor: Long, seenAt: Long, misses: Int): Int = if (countedFor == seenAt) misses else 0
}
