package cc.aaro.cosign.spam

import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wisp's two spam checks, as ported (spam-signals.md), on the posts the user found. */
class NSpamTest {
    companion object {
        val classifier by lazy {
            val assets = File("src/main/assets/nspam")
            NSpamClassifier(NSpamWeights.load(File(assets, "model.txt").inputStream(), File(assets, "calibration.npz").inputStream()))
        }
        fun events(path: String): List<Event> = (Json.parse(NSpamTest::class.java.getResource(path)!!.readText()) as List<*>).mapNotNull { Event.fromMap(it) }
        fun event(path: String): Event = Event.fromMap(Json.parse(NSpamTest::class.java.getResource(path)!!.readText()))!!
        fun score(notes: List<Event>) = classifier.score(notes.sortedByDescending { it.createdAt }.take(10).map { NoteInput(it.content, it.tags, it.createdAt) })!!
    }

    /** "Zap 50k and I'll post…" (nevent1qgs8874e…): 101 of its 102 likes from one account. Its words alone don't give it away. */
    @Test fun zapBait() {
        val post = event("/spam/zap-bait/post.json")
        val why = Spam.inflated(post.id, events("/spam/zap-bait/engagement.json"))
        assertEquals("Its likes look bought", why?.short)
        // Never the numbers or the rule (they'd show the way around it).
        assertTrue(why!!.long.none { it.isDigit() })
        val s = score(events("/spam/zap-bait/author-notes.json"))
        println("zap-bait author scores $s")
        assertTrue("its author's writing isn't what gives it away ($s)", s < Spam.AUTHOR_LINE)
    }

    @Test fun ordinaryLikesAreNotInflated() {
        val post = "p".repeat(64)
        fun like(pk: Int, at: Long) = Event("%064x".format(pk), at, 7, listOf(listOf("e", post)), "+")
        assertNull(Spam.inflated(post, (1..30).map { like(it, 1000L + it) }))
        // A friend double-tapping a quiet post: under ten likes, never inflated.
        assertNull(Spam.inflated(post, (1..6).map { like(1, 1000L + it) } + like(2, 1)))
    }

    @Test fun aRingIsInflated() {
        val post = "p".repeat(64)
        val got = (1..20).flatMap { i ->
            val pk = "%064x".format(i)
            listOf(Event(pk, 1000L + i, 7, listOf(listOf("e", post)), "+")) + if (i <= 12) listOf(Event(pk, 1030L + i, 6, listOf(listOf("e", post)), "")) else emptyList()
        }
        assertEquals("Its likes look bought", Spam.inflated(post, got)?.short)
    }

    /** The casino-review farm ("A note about Golden…"): one templated post per fresh account. */
    @Test fun casinoFarmAuthors() {
        val posts = events("/spam/casino/posts.json")
        val scores = posts.map { score(listOf(it)) }
        println("casino authors score: " + scores.sorted().joinToString { "%.2f".format(it) })
        posts.zip(scores).filter { it.second < Spam.AUTHOR_LINE }.forEach { (e, sc) -> println("casino missed %.2f %s: %s".format(sc, e.id.take(12), e.content.take(300).replace("\n", " "))) }
        // Nearly all caught by the author check alone (a one-post account's single post is all it has to go on).
        assertTrue(scores.count { it >= Spam.AUTHOR_LINE } * 10 >= posts.size * 9)
    }

    /** Diamonds' sports digest (000002ac…): betting-market links on beta.predyx.com, each with the poster's referral code. */
    @Test fun bettingReferralLinks() {
        assertEquals("Links to a betting site", Spam.advert(event("/spam/betting-links/post.json"))?.short)
        fun note(text: String) = Event("1".repeat(64), 0, 1, emptyList(), text)
        // Talking about a market (no referral mark, one link) is fine.
        assertNull(Spam.advert(note("Polymarket has the election at 60% https://polymarket.com/event/x")))
    }

    @Test fun casinoAdverts() {
        val posts = events("/spam/casino/posts.json")
        // With the words too, every one.
        assertTrue(posts.all { Spam.advert(it) != null || score(listOf(it)) >= Spam.AUTHOR_LINE })
        fun note(text: String) = Event("1".repeat(64), 0, 1, emptyList(), text)
        assertNull(Spam.advert(note("guy getting kicked out of a casino for saying Foid instead of Fold at the poker table")))
        assertNull(Spam.advert(note("Dont go to the casino!!!!! dont do it!")))
        assertNull(Spam.advert(note("A note about my garden: the tomatoes are in")))
        assertNotNull(Spam.advert(note("Big welcome bonus, free spins and live roulette at https://example.casino")))
    }
}
