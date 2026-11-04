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

    /** More than half of the write relays answered (none known: never). */
    fun complete(write: Collection<String>, answeredBy: Collection<String>): Boolean {
        val w = write.map { it.trimEnd('/') }.toSet()
        val a = answeredBy.map { it.trimEnd('/') }.toSet()
        return w.isNotEmpty() && w.count { it in a } * 2 > w.size
    }

    /** The wait after the [failures]-th failure in a row: 60 · 2^(n−1) s, at most 600 s. */
    fun retryAfter(failures: Int): Long = minOf(600L, 60L shl (maxOf(failures, 1) - 1).coerceAtMost(10))

    /** [found]: the newest copy's created_at the relays gave (0: none); [seenAt]: the newest seen here; [expires]: a copy's life on relays (0: for good). */
    fun decide(complete: Boolean, found: Long, seenAt: Long, misses: Int, expires: Long, now: Long): Decision = when {
        !complete -> Decision(Do.WAIT, misses)
        seenAt == 0L || found >= seenAt -> Decision(Do.WRITE, 0)
        expires > 0 && now > seenAt + expires -> Decision(Do.REBASE, 0)
        misses + 1 >= MISSES -> Decision(Do.REBASE, 0)
        else -> Decision(Do.WAIT, misses + 1)
    }
    const val MISSES = 3
}
