package cc.aaro.cosign.core

import cc.aaro.qrst.core.Event

/**
 * Putting one of your lists back (Undo). A list (profile, follows, relays, bookmarks, a named set) is replaceable:
 * relays and apps keep only its newest version. So Undo never deletes the change and never sends the old version
 * again as it was: either one loses to the change. It signs a new copy of the version before, dated after every
 * version seen, so it is the newest everywhere (2026-11-21: "Undo all" on Vector's DM relays sent a deletion by id,
 * and most relays kept Vector's).
 */
object Undoing {
    /** Kinds where only the newest version counts: the profile, the follow list, 10000-19999, and named sets. */
    fun replaceable(k: Int) = k == 0 || k == 3 || k in 10000 until 20000 || k in 30000 until 40000

    /** The date of the copy that puts a list back: now, or just after the newest version seen if that is later. */
    fun at(now: Long, vararg seen: Long?): Long = maxOf(now, (seen.filterNotNull().maxOrNull() ?: 0L) + 1)

    sealed class Before {
        /** The version [replaced] replaced: sign a new copy of it. */
        data class Found(val version: Event) : Before()
        /** The list changed again after [replaced] (in another app or device): Undo would throw that away too. */
        data class ChangedSince(val newest: Event) : Before()
        /** No earlier version on the relays asked. */
        object NotFound : Before()
    }

    /**
     * From versions of a list found on relays, the one [replaced] replaced. Only signed versions under the same key and
     * of the same list count. A newer one than [replaced] stops it, unless its id is in [ours] (later changes being
     * undone together, as Undo all does).
     */
    fun before(found: Collection<Event>, replaced: Event, ours: Set<String> = emptySet()): Before {
        val key = Policy.keyOf(replaced)
        val same = found.filter { it.pubkey == replaced.pubkey && it.kind == replaced.kind && Policy.keyOf(it) == key && it.verify() }.distinctBy { it.id }
        same.filter { it.createdAt > replaced.createdAt && it.id !in ours && it.id != replaced.id }.maxByOrNull { it.createdAt }?.let { return Before.ChangedSince(it) }
        return same.filter { it.createdAt < replaced.createdAt }.maxByOrNull { it.createdAt }?.let { Before.Found(it) } ?: Before.NotFound
    }
}
