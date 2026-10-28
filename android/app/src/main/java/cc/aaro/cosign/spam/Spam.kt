package cc.aaro.cosign.spam

import cc.aaro.cosign.core.Event

/**
 * Likely spam, as Wisp finds it (github.com/barrydeen/wisp, MIT), shared with Marble Web and discover
 * (marble-content-fixtures: spam-signals.md):
 *  - engagement that was manufactured, not earned (Wisp's InflatedEngagement, from its trending feed): one account giving
 *    more than half of 10+ reactions, or 15+ reactors of whom half also repost within two minutes;
 *  - an author whose last ten posts read like spam (Wisp's NSpam classifier, 0.7 or more);
 *  - a casino advert, by its words (the farm discover's diamonds showed: [advert]).
 * Never for people you follow (they're only ever cued) or yourself. Likely spam is left out of search and Following's
 * Beyond; shown anyway (opened by its link), it says so, with the reason a tap away.
 */
object Spam {
    /** Why something looks like spam: [short] on the post, [long] when you tap it. */
    data class Why(val short: String, val long: String)

    const val MIN_REACTIONS = 10
    const val COORDINATED_MIN_REACTORS = 15
    const val PAIR_WINDOW_SECS = 120L
    /** Wisp's line: an author scoring this or more is hidden. */
    const val AUTHOR_LINE = 0.7f

    /** [post]'s reactions (kind 7) and reposts (6, 16) among [got]: manufactured, and how. */
    fun inflated(post: String, got: List<Event>): Why? {
        fun target(e: Event) = e.tags.lastOrNull { it.size > 1 && it[0] == "e" }?.get(1)
        val reactions = got.filter { it.kind == 7 && target(it) == post }
        val reposts = got.filter { (it.kind == 6 || it.kind == 16) && target(it) == post }
        val counts = reactions.groupingBy { it.pubkey }.eachCount()
        val total = counts.values.sum()
        val most = counts.values.maxOrNull() ?: 0
        if (total >= MIN_REACTIONS && most * 2 > total)
            return Why("Its likes come from one account", "$most of its $total likes come from a single account: that's how a post is pushed up the trending lists, not people liking it.")
        val reactedAt = reactions.groupBy { it.pubkey }.mapValues { (_, l) -> l.minOf { it.createdAt } }
        if (reactedAt.size < COORDINATED_MIN_REACTORS) return null
        val repostedAt = reposts.groupBy { it.pubkey }.mapValues { (_, l) -> l.minOf { it.createdAt } }
        val paired = reactedAt.count { (pk, at) -> repostedAt[pk]?.let { kotlin.math.abs(it - at) <= PAIR_WINDOW_SECS } == true }
        if (paired * 2 >= reactedAt.size)
            return Why("Its likes look arranged", "$paired of the ${reactedAt.size} accounts that liked it also reposted it within two minutes: a ring pushing it up, not people finding it.")
        return null
    }

    // The casino-review farm (found on diamonds, 2026-10-27): one templated "A note about <brand>" post per fresh account,
    // in many languages, praising a casino's layout and welcome bonus.
    private val OPENER = Regex("^\\s*(a note about|una nota su|une note sur|uma nota sobre|una nota sobre|eine notiz (über|zu)|een notitie over)\\b", RegexOption.IGNORE_CASE)
    private val GAMBLING = Regex("\\b(casino|casinò|cassino|kasino|kasyno|казино|betting|bookmaker|sportsbook|slots?|free spins|freispiele|giri gratis|tiradas gratis|welcome bonus|bonus di benvenuto|bono de bienvenida|bônus de boas-vindas|jackpot|roulette|blackjack|scratchcards?|wagering)\\b", RegexOption.IGNORE_CASE)

    /** A casino advert, by its words: the farm's opener and a gambling word, or three gambling words and a link. */
    fun advert(e: Event): Why? {
        val head = e.content.take(1500)
        val terms = GAMBLING.findAll(head).map { it.value.lowercase() }.toSet()
        if (terms.isEmpty()) return null
        if (OPENER.containsMatchIn(head) || (terms.size >= 3 && "https://" in head))
            return Why("Reads like a casino advert", "It's written like the casino adverts posted from throwaway accounts (\"A note about…\", ${terms.take(3).joinToString(", ")}).")
        return null
    }

    @Volatile private var classifier: NSpamClassifier? = null
    private val scores = android.util.LruCache<String, Pair<Float, Int>>(2000)

    /** Wisp's classifier, loaded once (its model is large: off the main thread). Null: it couldn't be. */
    fun classifier(context: android.content.Context): NSpamClassifier? = classifier ?: synchronized(this) {
        classifier ?: try { NSpamClassifier(NSpamWeights.loadFromAssets(context)).also { classifier = it } } catch (_: Exception) { null }
    }

    /** How spammy [pk]'s writing is, from their latest posts [notes] (0..1); kept, and asked again once there are 5+. */
    fun author(context: android.content.Context, pk: String, notes: List<Event>): Float? {
        scores.get(pk)?.let { (s, n) -> if (n >= 5 || notes.size < 5) return s }
        val mine = notes.filter { it.pubkey == pk && it.kind == 1 }.sortedByDescending { it.createdAt }.take(10)
        if (mine.isEmpty()) return null
        val s = classifier(context)?.score(mine.map { NoteInput(it.content, it.tags, it.createdAt) }) ?: return null
        scores.put(pk, s to mine.size)
        return s
    }

    /** Kept already (no work): [pk]'s score, if known. */
    fun known(pk: String): Float? = scores.get(pk)?.first

    fun authorWhy(score: Float) = Why("Its author posts like spam",
        "This account's latest posts read like spam to the same check Wisp uses (it scores ${"%.2f".format(score)}; 0.7 or more counts).")
}
