package cc.aaro.cosign.spam

import cc.aaro.qrst.core.Event

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
    /**
     * Why something looks like spam, in a broad, plain category only: never a score, a threshold or the rule itself
     * (that would teach spammers the way around it). [short] on the post, [long] when you tap it.
     */
    data class Why(val short: String, val long: String)

    private val BOUGHT = Why("Its likes look bought", "Its likes look bought or arranged, not given by people who found it.")
    private val ADVERT = Why("A gambling advert", "It reads like an advert for a betting or casino site, posted from an account made for it.")
    private val BETTING = Why("Links to a betting site", "It sends people to a betting site, with a link that pays whoever posted it.")
    private val WRITING = Why("Its author posts spam", "This account's posts look like spam, and nobody you follow follows it.")

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
            return BOUGHT
        val reactedAt = reactions.groupBy { it.pubkey }.mapValues { (_, l) -> l.minOf { it.createdAt } }
        if (reactedAt.size < COORDINATED_MIN_REACTORS) return null
        val repostedAt = reposts.groupBy { it.pubkey }.mapValues { (_, l) -> l.minOf { it.createdAt } }
        val paired = reactedAt.count { (pk, at) -> repostedAt[pk]?.let { kotlin.math.abs(it - at) <= PAIR_WINDOW_SECS } == true }
        if (paired * 2 >= reactedAt.size)
            return BOUGHT
        return null
    }

    // The casino-review farm (found on diamonds, 2026-10-27): one templated "A note about <brand>" post per fresh account,
    // in many languages, praising a casino's layout and welcome bonus.
    private val OPENER = Regex("^\\s*(a note about|una nota su|une note sur|uma nota sobre|una nota sobre|eine notiz (über|zu)|een notitie over)\\b", RegexOption.IGNORE_CASE)
    private val GAMBLING = Regex("\\b(casino|casinò|cassino|kasino|kasyno|казино|betting|bookmaker|sportsbook|slots?|free spins|freispiele|giri gratis|tiradas gratis|welcome bonus|bonus di benvenuto|bono de bienvenida|bônus de boas-vindas|jackpot|roulette|blackjack|scratchcards?|wagering)\\b", RegexOption.IGNORE_CASE)

    // Betting and casino sites (a known list holds up even when it's known), and referral links' marks.
    private val BETTING_SITES = Regex("(^|\\.)(predyx\\.com|polymarket\\.com|kalshi\\.com|stake\\.com|stake\\.us|bc\\.game|roobet\\.com|rollbit\\.com|bet365\\.com|draftkings\\.com|fanduel\\.com|1xbet\\.com|betway\\.com|sportsbet\\.io|cloudbet\\.com|nitrogensports\\.eu|duelbits\\.com|shuffle\\.com|gamdom\\.com|bitstarz\\.com|7bitcasino\\.com|mystake\\.com|thunderpick\\.io)$")
    private val REFERRAL = Regex("[?&](ref|r|aff|affiliate|referral|refcode|invite|code|btag|promo)=", RegexOption.IGNORE_CASE)
    private val LINK = Regex("https?://([^/\\s?#]+)[^\\s]*", RegexOption.IGNORE_CASE)

    /** Links to betting sites that pay the poster (a referral mark), or many of them. */
    fun bettingLinks(e: Event): Why? {
        val bets = LINK.findAll(e.content).filter { BETTING_SITES.containsMatchIn(it.groupValues[1].lowercase().removePrefix("www.")) }.map { it.value }.toList()
        return if (bets.any { REFERRAL.containsMatchIn(it) } || bets.size >= 3) BETTING else null
    }

    /** A gambling advert: links to betting sites that pay the poster; the farm's opener and a gambling word; or three gambling words and a link. */
    fun advert(e: Event): Why? {
        bettingLinks(e)?.let { return it }
        val head = e.content.take(1500)
        val terms = GAMBLING.findAll(head).map { it.value.lowercase() }.toSet()
        if (terms.isEmpty()) return null
        if (OPENER.containsMatchIn(head) || (terms.size >= 3 && "https://" in head))
            return ADVERT
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

    @Suppress("UNUSED_PARAMETER") fun authorWhy(score: Float) = WRITING

    // "Not spam": you said so, on this phone; their posts aren't marked again.
    private val prefs get() = cc.aaro.cosign.CosignApp.instance.getSharedPreferences("marble", android.content.Context.MODE_PRIVATE)
    fun notSpam(pk: String) = pk in (prefs.getStringSet("notSpam", null) ?: emptySet())
    fun markNotSpam(pk: String) = prefs.edit().putStringSet("notSpam", (prefs.getStringSet("notSpam", null) ?: emptySet()) + pk).apply()
}
