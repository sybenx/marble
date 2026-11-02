package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * Your circle, for the trust filter: you, the people you follow, anyone at least [AT] of them follow, and anyone you
 * said isn't spam. Worked out on this phone from your follows' follow lists (kind 3); nothing about it is sent, and no
 * score or number is shown. Outside it: not in Following or Beyond, after the rest in search, folded under your posts.
 */
object Circle {
    /** How many of the people you follow must follow someone. */
    const val AT = 2

    fun has(pk: String, me: String?, follows: Set<String>, counts: Map<String, Int>, kept: (String) -> Boolean): Boolean =
        pk == me || pk in follows || (counts[pk] ?: 0) >= AT || kept(pk)

    /** For each person, how many of [follows] follow them: each one's newest list, counted once, from them alone. */
    fun counts(lists: List<Event>, follows: Set<String>): Map<String, Int> {
        val count = HashMap<String, Int>()
        lists.filter { it.kind == 3 && it.pubkey in follows }.groupBy { it.pubkey }.values.map { l -> l.maxBy { it.createdAt } }
            .forEach { l -> l.tags.filter { it.size > 1 && it[0] == "p" && it[1] != l.pubkey }.map { it[1] }.distinct().forEach { count[it] = (count[it] ?: 0) + 1 } }
        return count
    }
}
