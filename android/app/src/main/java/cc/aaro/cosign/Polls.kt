package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * Polls (NIP-88): a poll is kind 1068 (its question as content; `option` tags with an id and a label; `polltype`
 * singlechoice or multiplechoice; `endsAt`; `relay` tags where votes go). A vote is kind 1018 (`e` the poll,
 * `response` an option id, one per choice). Each person's newest vote before the end counts, once.
 */
object Polls {
    const val POLL = 1068
    const val VOTE = 1018

    data class Poll(val id: String, val question: String, val options: List<Pair<String, String>>, val multiple: Boolean, val endsAt: Long?, val relays: List<String>) {
        fun ended(now: Long) = endsAt != null && now > endsAt
    }

    fun of(e: Event): Poll? {
        if (e.kind != POLL) return null
        val options = e.tags.filter { it.size > 2 && it[0] == "option" && it[1].isNotEmpty() }.map { it[1] to it[2] }.distinctBy { it.first }
        if (options.size < 2) return null
        return Poll(e.id, e.content.trim(), options, e.firstTag("polltype") == "multiplechoice", e.firstTag("endsAt")?.toLongOrNull(),
            e.tags.filter { it.size > 1 && it[0] == "relay" && it[1].startsWith("wss://") }.map { it[1] })
    }

    /** Votes per option, and who voted for what: each person's newest vote before the end; one choice only, for a single-choice poll. */
    data class Tally(val counts: Map<String, Int>, val voters: Int, val choices: Map<String, List<String>>) {
        fun share(option: String) = if (voters == 0) 0f else (counts[option] ?: 0).toFloat() / voters
    }

    fun tally(p: Poll, votes: List<Event>): Tally {
        val valid = p.options.map { it.first }.toSet()
        val newest = votes.filter { it.kind == VOTE && it.tags.any { t -> t.size > 1 && t[0] == "e" && t[1] == p.id } && (p.endsAt == null || it.createdAt <= p.endsAt) }
            .groupBy { it.pubkey }.mapValues { (_, l) -> l.maxBy { it.createdAt } }
        val choices = newest.mapValues { (_, v) ->
            v.tags.filter { it.size > 1 && it[0] == "response" && it[1] in valid }.map { it[1] }.distinct().let { if (p.multiple) it else it.take(1) }
        }.filterValues { it.isNotEmpty() }
        val counts = HashMap<String, Int>()
        choices.values.flatten().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return Tally(counts, choices.size, choices)
    }

    /** A vote's tags. */
    fun voteTags(p: Poll, options: List<String>): List<List<String>> = listOf(listOf("e", p.id)) + options.map { listOf("response", it) }
}
