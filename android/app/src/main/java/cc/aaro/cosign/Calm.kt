package cc.aaro.cosign

import cc.aaro.cosign.core.Event
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

// The home feed the user picked from the mock (~/igloo/feed-mock, column C, "Calm news"): posts from
// people, matched to your topics, from the last two days, each saying why it's there; and a few of
// today's stories kept together in one box at the top, never between the posts. It ends.
// The rules and the ranking are the mock's (discover-mock.mjs, column B); this file is only that.

/** A post in the feed and why it's there ("AI and agents · followed by 12 people you follow"). */
data class Pick(val e: Event, val topic: String, val score: Double, val reason: String, val likes: Int = 0, val more: List<String> = emptyList())

/** Who wrote a post, as far as trust goes: someone you follow, how many of your follows follow them, their followers. */
data class Standing(val follow: Boolean, val trustedBy: Int, val followers: Long)

object Calm {
    /** Notes, articles and videos. */
    val POOL_KINDS = listOf(1, 30023, 21, 22, 34235, 34236)
    /** The last two days. */
    const val WINDOW = 48 * 3600L

    private val URL = Regex("https?://[^\\s)\\]]+")
    private val IMG = Regex("\\.(jpe?g|png|gif|webp|avif)(\\?|$)", RegexOption.IGNORE_CASE)
    private val VID = Regex("\\.(mp4|mov|webm|m3u8)(\\?|$)|youtu\\.?be|youtube\\.com|rumble\\.com|vimeo\\.com|zap\\.stream", RegexOption.IGNORE_CASE)
    private val BOTNAME = Regex("\\bbot\\b|bot$|🤖", RegexOption.IGNORE_CASE)
    private val FEEBOT = Regex("^Block \\d{6,}|mempool|sat/vB", RegexOption.IGNORE_CASE)
    private val ADS = Regex("\\b(earn (free )?sats|faucet|airdrop|giveaway)\\b", RegexOption.IGNORE_CASE)
    private val GM = Regex("^\\s*(gm|gn|good morning|good night|buongiorno|guten morgen)\\b", RegexOption.IGNORE_CASE)
    private val GITBOT = Regex("merge pull request|^\\[\\s*[\\w-]+/[\\w-]+\\s*\\]", RegexOption.IGNORE_CASE)

    fun urls(e: Event) = URL.findAll(e.content).map { it.value }.toList()
    /** The first link in a post that isn't a picture or a video: what a preview card shows. */
    fun link(e: Event) = urls(e).firstOrNull { !IMG.containsMatchIn(it) && !VID.containsMatchIn(it) }
    private fun videos(e: Event) = urls(e).count { VID.containsMatchIn(it) } + e.tags.count { it.firstOrNull() == "imeta" && it.any { p -> p.startsWith("m video/") } }
    private fun isReply(e: Event) = e.kind == 1 && e.tags.any { it.size > 1 && it[0] == "e" && it.getOrNull(3) != "mention" }
    /** What a person reads: no links, mentions or hashtags. */
    fun text(e: Event) = TopicGuess.textOf(e).replace(Regex("#\\w+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Why [e] stays out of the feed, or null if it can be in it. [readable]: in a language you read, not
     * muted, not flagged (Marble's own checks); [name]: the author's name, for bots.
     */
    fun drop(e: Event, me: String?, liked: Set<String>, postsByAuthor: Int, name: String?, readable: Boolean, standing: Standing): String? {
        val text = text(e)
        return when {
            e.pubkey == me -> "own post"
            e.id in liked -> "already liked"
            isReply(e) -> "reply"
            BOTNAME.containsMatchIn(name ?: "") || FEEBOT.containsMatchIn(e.content) || e.tags.any { it.size > 1 && it[0] == "t" && it[1].equals("bitcoinfees", true) } -> "bot"
            e.tags.any { it.firstOrNull() == "content-warning" || (it.size > 1 && it[0] == "t" && Regex("nsfw|porn|nude", RegexOption.IGNORE_CASE).containsMatchIn(it[1])) } -> "adult"
            postsByAuthor > 25 || GITBOT.containsMatchIn(e.content) -> "news feed or bot account"
            ADS.containsMatchIn(text) -> "ad or giveaway"
            GM.containsMatchIn(text) -> "good-morning post"
            e.kind == 1 && (text.length < 60 || (link(e) == null && videos(e) == 0)) -> "no link or video, or too short"
            !readable -> "not in a language you read"
            !standing.follow && Curate.heavy(text) -> "personal or grief post from a stranger"
            !standing.follow && standing.trustedBy < 3 && standing.followers < 300 -> "nobody you know follows them"
            else -> null
        }
    }

    /**
     * How well [e] fits, and why: topic match × freshness × trust × people. [weights]: your topics, 0..1
     * (only these count); [fans]: who liked or shared it; [follows]: who you follow; [engaged]: people
     * whose posts you've liked before.
     */
    fun score(e: Event, weights: Map<String, Double>, standing: Standing, fans: Set<String>, follows: Set<String>, engaged: Boolean, now: Long): Pick? {
        val hits = TopicGuess.hits(e, strict = true).filter { (weights[it.topic] ?: 0.0) > 0 }
            .sortedWith(compareByDescending<TopicGuess.Hit> { it.strength }.thenByDescending { weights[it.topic] ?: 0.0 })
        if (hits.isEmpty()) return null
        val (trust, why0) = when {
            standing.follow -> 0.9 to "from someone you follow"
            standing.trustedBy >= 3 -> (0.4 + 0.6 * min(1.0, ln(1.0 + standing.trustedBy) / ln(51.0))) to "followed by ${standing.trustedBy} people you follow"
            standing.followers >= 300 -> 0.35 to "${if (standing.followers >= 1000) "%.1fk".format(standing.followers / 1000.0) else standing.followers.toString()} followers"
            else -> return null
        }
        val friendFans = fans.count { it in follows }
        val why = if (friendFans >= 2) "liked by $friendFans people you follow" else why0
        val top = hits[0]
        val match = weights[top.topic]!! * min(1.0, 0.5 + top.strength / 2) + 0.15 * hits.drop(1).sumOf { weights[it.topic] ?: 0.0 }
        val ageH = (now - e.createdAt).coerceAtLeast(0) / 3600.0
        val fresh = 0.5.pow(ageH / 18)
        val people = 0.6 + 0.35 * log2(1.0 + fans.size) + 0.15 * min(friendFans, 6) + (if (engaged) 0.3 else 0.0)
        return Pick(e, top.topic, match * fresh * trust * people, "${TopicFeeds.name(top.topic)} · $why", fans.size)
    }

    /** One post per person; at most 2 per topic in the first ten, 3 after; [max] in all. Then it ends. */
    fun diversify(scored: List<Pick>, max: Int = 20): List<Pick> {
        val out = ArrayList<Pick>(); val authors = HashSet<String>(); val perTopic = HashMap<String, Int>()
        for (p in scored.sortedByDescending { it.score }) {
            if (out.size >= max) break
            val cap = if (out.size < 10) 2 else 3
            if (p.e.pubkey in authors || (perTopic[p.topic] ?: 0) >= cap) continue
            authors += p.e.pubkey; perTopic[p.topic] = (perTopic[p.topic] ?: 0) + 1
            out += p
        }
        return out
    }

    private val STOP = "the a an and or of to in on for with is are was be by at from as it its this that how why what new your you we our after into over about than more now just will can".split(' ').toSet()
    private fun words(s: String) = Regex("[a-z0-9]{3,}").findAll(URL.replace(s.lowercase(), " ")).map { it.value }.filter { it !in STOP }.toSet()
    /** About the same thing: three or more words in common, at least half of the shorter. */
    fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.size < 3 || y.size < 3) return false
        val n = x.count { it in y }
        return n >= 3 && n.toDouble() / min(x.size, y.size) >= 0.5
    }

    /** Posts about the same thing (the same link, or mostly the same words) become one card that names the others. */
    fun collapse(picks: List<Pick>, name: (String) -> String): List<Pick> {
        val groups = ArrayList<MutableList<Pick>>()
        for (p in picks) {
            val l = link(p.e); val t = text(p.e)
            val g = groups.firstOrNull { g -> (l != null && l == link(g[0].e)) || similar(t, text(g[0].e)) }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        return groups.map { g -> g[0].copy(more = g.drop(1).map { name(it.e.pubkey) }) }
    }

    /** How well a story fits you: your interest in its topic (followed first), and how new it is (half as much every 18 hours). */
    fun storyScore(c: TopicCard, interests: Interests, now: Long) =
        interests.of(c.topic) * interests.sourcePenalty(c.sourceId) * 0.5.pow((now - c.publishedAt).coerceAtLeast(0) / 3600.0 / 18)

    /** The box at the top: the [max] stories that fit you best, one per topic, from the last two days. */
    fun todays(all: List<TopicCard>, interests: Interests, now: Long, max: Int = 4): List<TopicCard> {
        val byTopic = HashSet<String>()
        return stories(all, interests, now).filter { byTopic.add(it.topic) }.take(max)
    }

    /** All of today's stories, for "More stories": the topics you follow first, then the best fit. */
    fun stories(all: List<TopicCard>, interests: Interests, now: Long): List<TopicCard> =
        all.filter { now - it.publishedAt < WINDOW && !interests.hidden("hiddenSources", it.sourceId) }
            .sortedWith(compareByDescending<TopicCard> { it.topic in interests.followed }.thenByDescending { storyScore(it, interests, now) })
}
