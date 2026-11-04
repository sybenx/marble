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

    /**
     * Exactly one emoji, as one picture: a flag (two regional letters), a keycap (#, * or 0–9, U+FE0F, U+20E3), or an
     * emoji with its variation selector, skin tone and tag letters, joined to more by U+200D (👨‍👩‍👧 is one). Not two, not words.
     */
    fun oneEmoji(s: String): Boolean {
        val cps = s.codePoints().toArray()
        if (cps.isEmpty() || cps.size > 40) return false
        fun pictograph(c: Int) = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF || c in 0x2190..0x21FF || c in 0x2300..0x23FF ||
            c in 0x25A0..0x25FF || c in 0x2900..0x297F || c in 0x3030..0x303D || c == 0x00A9 || c == 0x00AE || c == 0x203C || c == 0x2049 || c == 0x2122 || c == 0x2139 || c == 0x3297 || c == 0x3299
        fun regional(c: Int) = c in 0x1F1E6..0x1F1FF
        // A flag.
        if (cps.size == 2 && regional(cps[0]) && regional(cps[1])) return true
        // A keycap.
        if (cps.size in 2..3 && (cps[0] == '#'.code || cps[0] == '*'.code || cps[0] in '0'.code..'9'.code) && cps.last() == 0x20E3 && (cps.size == 2 || cps[1] == 0xFE0F)) return true
        // One or more pictographs joined by ZWJ, each with optional VS16, skin tone, tag letters.
        var i = 0
        while (true) {
            if (i >= cps.size || !pictograph(cps[i]) || regional(cps[i])) return false
            i++
            while (i < cps.size && (cps[i] == 0xFE0F || cps[i] in 0x1F3FB..0x1F3FF || cps[i] in 0xE0020..0xE007F)) i++
            if (i == cps.size) return true
            if (cps[i] != 0x200D) return false
            i++
        }
    }

    /** What a change sends: [to] (null: take it back) given your reactions to the post so far ([before], their ids). */
    data class Send(val kind: Int, val content: String, val tags: List<List<String>>)
    fun sends(before: List<String>, to: Shown?, note: Event): List<Send> {
        val delete = if (before.isEmpty()) emptyList() else listOf(Send(5, "", before.map { listOf("e", it) } + listOf(listOf("k", "7"))))
        return if (to == null) delete else listOf(Send(7, wire(to.emoji), tags(note, to))) + delete
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
