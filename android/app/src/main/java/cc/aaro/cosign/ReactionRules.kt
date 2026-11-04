package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * Reactions as Marble draws and sends them (~/igloo/marble-reactions.md; content-rules §10): ❤️ goes out as "+" and
 * "+" (or nothing) shows as ❤️; "-" and anything that isn't one emoji or one :shortcode: (with its emoji tag) are
 * left out. On an opened post: each one with how many gave it, one per person (their newest). The tray: your recent
 * ones (from your own published reactions), then 👍 ❤️ 😂 😢 👀 🧐, then +.
 */
object ReactionRules {
    const val LIKE = "❤️"
    val DEFAULTS = listOf("👍", LIKE, "😂", "😢", "👀", "🧐")

    /** One reaction as shown: an emoji, or a custom one (:name: and its picture). */
    data class Shown(val emoji: String, val url: String? = null)

    /** What a reaction (kind 7) shows as; null: left out. */
    fun shown(e: Event): Shown? {
        if (e.kind != 7) return null
        val c = e.content.trim()
        if (c == "-") return null
        if (c.isEmpty() || c == "+") return Shown(LIKE)
        Regex("^:([A-Za-z0-9_+-]{1,60}):$").matchEntire(c)?.let { m ->
            val url = e.tags.firstOrNull { it.size > 2 && it[0] == "emoji" && it[1] == m.groupValues[1] && it[2].startsWith("https://") }?.get(2) ?: return null
            return Shown(c, url)
        }
        return if (oneEmoji(c)) Shown(c) else null
    }

    /** One emoji (with its modifiers and joiners), not words. */
    fun oneEmoji(s: String): Boolean {
        if (s.isEmpty() || s.length > 32 || s.any { it.isLetterOrDigit() && it.code < 0x2000 }) return false
        val cps = s.codePoints().toArray().filter { it != 0xFE0F && it != 0x200D && it !in 0x1F3FB..0x1F3FF && it !in 0xE0020..0xE007F }
        // Flags are two regional letters; keycaps a digit then U+20E3; otherwise, joined by ZWJ, still one picture.
        return cps.isNotEmpty() && cps.all { it >= 0x2000 || it == 0x20E3 } && (s.contains('‍') || cps.size <= 2)
    }

    /** What goes in a reaction's content (❤️ as "+", so other apps count it as a like). */
    fun wire(emoji: String) = if (emoji == LIKE) "+" else emoji

    /** On an opened post ([target]): each reaction with how many gave it, most first; one per person, their newest. */
    fun tally(reactions: List<Event>, target: String): List<Pair<Shown, List<String>>> {
        val newest = reactions.filter { it.kind == 7 && it.tags.lastOrNull { t -> t.size > 1 && t[0] == "e" }?.get(1) == target }
            .groupBy { it.pubkey }.mapValues { (_, l) -> l.maxBy { it.createdAt } }
        val by = LinkedHashMap<String, Pair<Shown, MutableList<String>>>()
        for ((pk, e) in newest.entries.sortedByDescending { it.value.createdAt }) {
            val s = shown(e) ?: continue
            by.getOrPut(s.emoji) { s to ArrayList() }.second += pk
        }
        return by.values.map { it.first to it.second.toList() }.sortedByDescending { it.second.size }
    }

    /** Your recent reactions: newest used first, how often as the tie-break, from your own kind 7s (each emoji once). */
    fun recents(mine: List<Event>, me: String, max: Int = 6): List<Shown> {
        val list = mine.filter { it.kind == 7 && it.pubkey == me }.mapNotNull { e -> shown(e)?.let { it to e.createdAt } }
        val count = list.groupingBy { it.first.emoji }.eachCount()
        val last = list.groupBy { it.first.emoji }.mapValues { (_, l) -> l.maxOf { it.second } }
        return list.map { it.first }.distinctBy { it.emoji }.sortedWith(compareByDescending<Shown> { last[it.emoji] }.thenByDescending { count[it.emoji] }).take(max)
    }

    /** The tray's six emoji (then +): your recents, then filled from the defaults, each once. */
    fun tray(recents: List<Shown>): List<Shown> = (recents.take(6) + DEFAULTS.map { Shown(it) }).distinctBy { it.emoji }.take(6)

    /** The tags of a reaction to [note] with [s]. */
    fun tags(note: Event, s: Shown): List<List<String>> = listOf(listOf("e", note.id), listOf("p", note.pubkey), listOf("k", note.kind.toString())) +
        (s.url?.let { listOf(listOf("emoji", s.emoji.trim(':'), it)) } ?: emptyList())
}
