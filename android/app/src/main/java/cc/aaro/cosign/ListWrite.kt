package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * Changing one entry of one of your lists (marble-content-fixtures list-writes.md; marble-2009-spec §4): follows (3),
 * bookmarks (10003), Special Follow (10010), favourite relays (10012), DM inbox (10050), sets (30000, 30002), app data
 * (30078). A list is replaced whole, so a write from the wrong start wipes it everywhere: start from your newest
 * version anywhere, change only the one entry, keep the rest (unknown tags and content too), and check it all again
 * ([guard]) before it's signed.
 */
object ListWrite {
    /** add or remove one entry (a tag: its name and value; more of the tag may follow, like a relay hint). */
    data class Change(val add: List<String>? = null, val remove: List<String>? = null)

    sealed class Plan {
        data class Write(val kind: Int, val tags: List<List<String>>, val content: String, val createdAt: Long, val old: Event?) : Plan()
        data object Nothing : Plan()
        data class Refuse(val why: String) : Plan()
    }

    /** An entry's identity: its name and value (a relay's address in one spelling). */
    fun key(t: List<String>): Pair<String, String>? {
        if (t.size < 2) return null
        val v = when (t[0]) {
            "relay", "r" -> t[1].trim().lowercase().trimEnd('/')
            "p", "e" -> t[1].trim().lowercase()
            else -> t[1]
        }
        return t[0] to v
    }

    /** Your newest version of the list: yours, that kind (a set: that d); a tie in time goes to the lowest id (NIP-01). */
    fun newest(found: List<Event>, me: String, kind: Int, d: String? = null): Event? = found
        .filter { it.pubkey == me && it.kind == kind && (d == null || it.firstTag("d") == d) }
        .minWithOrNull(compareByDescending<Event> { it.createdAt }.thenBy { it.id })

    /**
     * What one change does to your list. [expected]: this device has seen your list of this kind, or the account
     * shows one (a follows count): then no list found is a refusal, never a list started from empty.
     */
    fun plan(found: List<Event>, me: String, kind: Int, d: String?, change: Change, expected: Boolean, now: Long): Plan {
        val old = newest(found, me, kind, d)
        val entry = change.add ?: change.remove ?: return Plan.Refuse("no change")
        val k = key(entry) ?: return Plan.Refuse("no change")
        if (old == null) {
            if (expected) return Plan.Refuse("no list found")
            if (change.add == null) return Plan.Nothing
            return Plan.Write(kind, listOfNotNull(d?.let { listOf("d", it) }, change.add), "", now, null)
        }
        val has = old.tags.any { key(it) == k }
        val tags = when {
            change.add != null -> if (has) return Plan.Nothing else old.tags + listOf(change.add)
            else -> if (!has) return Plan.Nothing else old.tags.filterNot { key(it) == k }
        }
        return Plan.Write(kind, tags, old.content, maxOf(now, old.createdAt + 1), old)
    }

    /** One version of a list, for the last check: what it is, when, its private part, its tags. */
    data class Version(val kind: Int, val createdAt: Long, val content: String, val tags: List<List<String>>)

    /**
     * The last check before signing, whatever made the list: null if [next] changes exactly one entry of [old] and
     * nothing else; else why not.
     */
    fun guard(old: Version, next: Version): String? {
        if (old.kind != next.kind || old.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) != next.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)) return "a different list"
        if (old.content != next.content) return "the private part changed"
        if (next.createdAt <= old.createdAt) return "not newer"
        return tagsGuard(old.tags, next.tags)
    }

    /** The tag part of [guard] (also run on a decrypted private part, its public tags unchanged). */
    fun tagsGuard(old: List<List<String>>, next: List<List<String>>): String? {
        val before = old.mapNotNull(::key).toSet(); val after = next.mapNotNull(::key).toSet()
        val removed = before - after; val added = after - before
        if (removed.size > 1) return "takes out more than one"
        if (removed.size + added.size != 1) return "changes more than the one entry"
        if (old.filterNot { key(it) in removed } != next.filterNot { key(it) in added }) return "other tags changed"
        return null
    }

    /** A list's private part checked the same way (decrypted tags), with its public tags unchanged. */
    fun privateGuard(oldPublic: List<List<String>>, nextPublic: List<List<String>>, oldPrivate: List<List<String>>, nextPrivate: List<List<String>>): String? =
        if (oldPublic != nextPublic) "other tags changed" else tagsGuard(oldPrivate, nextPrivate)
}
