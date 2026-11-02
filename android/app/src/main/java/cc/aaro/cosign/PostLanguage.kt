package cc.aaro.cosign

import cc.aaro.qrst.core.Event

/**
 * What language a post is in, and how to translate it without breaking it: the post's own NIP-32 tag first; else
 * a guess from its words alone (links, references, hashtags, mentions, emoji and :shortcodes: taken out first), made
 * only when one language is clearly ahead. Links, mentions, hashtags and shortcodes go through translation as
 * placeholders and come back untouched.
 */
object PostLanguage {
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val REF = Regex("""(?i)\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{8,}""")
    private val HASHTAG = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
    private val MENTION = Regex("""(?<![\p{L}\p{N}_.])[@＠][\p{L}\p{N}_.-]+""")
    private val SHORTCODE = Regex(""":[A-Za-z0-9_+-]{2,}:""")
    private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}\x{200D}\x{1F1E6}-\x{1F1FF}\x{E0020}-\x{E007F}]""")

    /** The post's own language tag (NIP-32: `L ISO-639-1`, `l <code> ISO-639-1`), if it has one. */
    fun tagged(e: Event): String? {
        val ns = e.tags.any { it.size > 1 && it[0] == "L" && it[1] == "ISO-639-1" }
        return e.tags.firstOrNull { it.size > 1 && it[0] == "l" && (it.getOrNull(2) == "ISO-639-1" || ns) && Regex("^[a-z]{2}$").matches(it[1].lowercase()) }?.get(1)?.lowercase()
    }

    /** The words alone, for guessing the language. */
    fun clean(text: String): String = listOf(URL, REF, SHORTCODE, HASHTAG, MENTION, EMOJI).fold(text) { t, r -> r.replace(t, " ") }
        .replace(Regex("""\s+"""), " ").trim()

    /** Enough words to guess from. */
    fun guessable(cleaned: String) = cleaned.count { it.isLetter() } >= 12

    /** A guess: sure of [lang], or unsure, with the likeliest few to offer. */
    data class Guess(val lang: String?, val likeliest: List<String>)

    /** From the detector's candidates (language, confidence): sure only when the top one is clearly ahead. */
    fun decide(candidates: List<Pair<String, Float>>, atLeast: Float = 0.6f, ahead: Float = 0.25f): Guess {
        val c = candidates.filter { it.first != "und" && it.first.isNotEmpty() }.sortedByDescending { it.second }
        val top = c.firstOrNull() ?: return Guess(null, emptyList())
        val second = c.getOrNull(1)?.second ?: 0f
        return if (top.second >= atLeast && top.second - second >= ahead) Guess(top.first, listOf(top.first)) else Guess(null, c.take(3).map { it.first })
    }

    /** [text] with links, references, mentions, hashtags and shortcodes as numbered placeholders, and what they were. */
    fun protect(text: String): Pair<String, List<String>> {
        val kept = ArrayList<String>()
        val all = Regex(listOf(URL, REF, SHORTCODE, HASHTAG, MENTION).joinToString("|") { "(?:${it.pattern})" })
        val out = all.replace(text) { m -> kept += m.value; "⟦${kept.size - 1}⟧" }
        return out to kept
    }

    /** Placeholders back (translators sometimes add spaces inside them); any it lost go at the end, so nothing is dropped. */
    fun restore(translated: String, kept: List<String>): String {
        val used = HashSet<Int>()
        var out = Regex("""⟦\s*(\d+)\s*⟧""").replace(translated) { m -> val i = m.groupValues[1].toInt(); kept.getOrNull(i)?.also { used += i } ?: "" }
        val lost = kept.indices.filter { it !in used }.map { kept[it] }
        if (lost.isNotEmpty()) out = out.trimEnd() + " " + lost.joinToString(" ")
        return out
    }
}
