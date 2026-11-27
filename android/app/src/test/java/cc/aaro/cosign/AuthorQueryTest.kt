package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "thailand anarko": Anarko's posts about Thailand, when you follow Anarko; no one's, when you don't. */
class AuthorQueryTest {
    private val anarko = "a".repeat(64)
    private val anna = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private val ann2 = "d".repeat(64)
    private val known = mapOf(
        anarko to Person(anarko, "Anarko", null, null, "anarko@example.com", 0),
        anna to Person(anna, "Anna Karenina", null, null, "anna@books.example", 0),
        stranger to Person(stranger, "Elon", null, null, null, 0),
        ann2 to Person(ann2, "Anarko 🌴", null, null, "ak@example.org", 0),
    )
    private val follows = setOf(anarko, anna, ann2)

    @Test fun nameAfter() = assertEquals(AuthorQuery.Split(anarko, "thailand", listOf(ann2)), AuthorQuery.split("thailand anarko", follows, known))
    @Test fun nameBefore() = assertEquals("thailand", AuthorQuery.split("anarko thailand", follows, known)!!.rest)
    @Test fun fromName() = assertEquals(AuthorQuery.Split(anarko, "thailand", listOf(ann2)), AuthorQuery.split("from:anarko thailand", follows, known))
    @Test fun atName() = assertEquals(anarko, AuthorQuery.split("thailand ＠Anarko", follows, known)!!.author)
    @Test fun twoWordName() = assertEquals(AuthorQuery.Split(anna, "trains"), AuthorQuery.split("anna karenina trains", follows, known))
    @Test fun byTheirAddressName() = assertEquals(ann2, AuthorQuery.split("ak beaches", follows, known)!!.author)
    @Test fun notSomeoneYouFollow() = assertNull(AuthorQuery.split("elon rockets", follows, known))
    @Test fun fromSomeoneYouDontFollow() = assertNull(AuthorQuery.split("from:elon rockets", follows, known))
    @Test fun aNameAloneIsAPeopleSearch() = assertNull(AuthorQuery.split("anarko", follows, known))
    @Test fun signedOut() = assertNull(AuthorQuery.split("thailand anarko", null, known))
    @Test fun noOneNamed() = assertNull(AuthorQuery.split("thailand beaches", follows, known))

    // Partial names (the user: "would something like thailand arko work too?").
    private val arthur = "e".repeat(64)
    private val withArthur = known + (arthur to Person(arthur, "Arthur", null, null, null, 0))

    @Test fun aPartOfTheirName() {
        val s = AuthorQuery.split("thailand arko", follows, known)!!
        assertEquals(anarko, s.author); assertEquals("thailand", s.rest); assertTrue(s.loose)
    }
    @Test fun theStartOfTheirName() = assertEquals(anarko, AuthorQuery.split("ana thailand", follows, known)!!.author)
    @Test fun oneTypoOff() = assertEquals(anarko, AuthorQuery.split("thailand anrako", follows, known)!!.author)
    @Test fun theirNameBeatsAPartOfSomeoneElses() = assertEquals(AuthorQuery.Split(anarko, "art"), AuthorQuery.split("anarko art", follows + arthur, withArthur).let { it!!.copy(others = emptyList()) })
    @Test fun artOnlyLooselyMeansArthur() {
        // A loose match: shown after the first of the usual results, never instead of them.
        val s = AuthorQuery.split("art nouveau", follows + arthur, withArthur)!!
        assertEquals(arthur, s.author); assertTrue(s.loose)
    }
    @Test fun twoLettersNameNoOne() = assertNull(AuthorQuery.split("ar thailand", follows + arthur, withArthur))
    @Test fun aPartOfAStrangersName() = assertNull(AuthorQuery.split("thailand elo", follows, known))
    @Test fun oneOff() {
        assertTrue(AuthorQuery.oneOff("anarko", "anrako")); assertTrue(AuthorQuery.oneOff("anarko", "anarkoo")); assertTrue(AuthorQuery.oneOff("anarko", "anaro"))
        assertFalse(AuthorQuery.oneOff("anarko", "anarko")); assertFalse(AuthorQuery.oneOff("anarko", "abarka"))
    }

    // from: (2026-11-27: "from:fiatjaf failed" brought back lots of posts that weren't fiatjaf's, or didn't say "failed").
    // The search relays don't know from: and answer "failed" by anyone (syb.lol, relay.ditto.pub: 40 of 40 by others).
    private val fiatjaf = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private fun post(by: String, text: String, kind: Int = 1) = cc.aaro.qrst.core.Event(by, 1_700_000_000, kind, emptyList(), text)

    @Test fun fromTakesTheNameOut() = assertEquals(AuthorQuery.From("fiatjaf", "failed"), AuthorQuery.from("from:fiatjaf failed"))
    @Test fun fromAnywhere() = assertEquals(AuthorQuery.From("fiatjaf", "relay failed"), AuthorQuery.from("relay FROM:fiatjaf failed"))
    @Test fun fromAlone() = assertEquals(AuthorQuery.From("fiatjaf", ""), AuthorQuery.from("from:fiatjaf"))
    // @name with other words: the name out of the words too (as web), but the softer kind.
    @Test fun atNameIsSofter() = assertEquals(AuthorQuery.From("fiatjaf", "failed", strict = false), AuthorQuery.from("@fiatjaf failed"))
    @Test fun noFrom() {
        assertNull(AuthorQuery.from("fiatjaf failed")); assertNull(AuthorQuery.from("where it came from: here"))
        assertNull(AuthorQuery.from("@fiatjaf"))  // a bare @name: a people search
        assertNull(AuthorQuery.from("from:x failed"))  // one letter names no one
    }
    @Test fun fromAloneIsTheirPosts() = assertEquals(AuthorQuery.Split(anarko, "", listOf(ann2)), AuthorQuery.split("from:anarko", follows, known))

    @Test fun onlyPostsWithEveryWord() {
        assertTrue(AuthorQuery.has(post(fiatjaf, "The push FAILED: relay said no"), "failed", all = true))
        assertFalse(AuthorQuery.has(post(fiatjaf, "Instead of polluting all the events"), "failed", all = true))
        assertTrue(AuthorQuery.has(post(fiatjaf, "the relay failed again"), "relay failed", all = true))
        assertFalse(AuthorQuery.has(post(fiatjaf, "the relay is fine"), "relay failed", all = true))
        assertTrue(AuthorQuery.has(post(fiatjaf, "the relay is fine"), "relay failed", all = false))
        assertTrue(AuthorQuery.has(post(fiatjaf, "", 30023).copy(tags = listOf(listOf("title", "Why it failed"))), "failed", all = true))
        assertTrue(AuthorQuery.has(post(fiatjaf, "anything"), "", all = true))
        assertFalse(AuthorQuery.has(post(fiatjaf, "failed", kind = 7), "failed", all = true))
    }

    // Someone you don't follow: the search relays know dozens of "fiatjaf"s (copies with his name, no address, a follower or two).
    @Test fun fromAStrangerPicksTheRealOne() {
        val copies = (1..6).map { "%064x".format(it) }
        val found = copies.map { Person(it, "fiatjaf", null, null, null, 0) } +
            Person(fiatjaf, "fiatjaf", "https://fiatjaf.com/static/favicon.jpg", "~", "_@fiatjaf.com", 0) + Person("f".repeat(64), "fiatjafbot", null, null, null, 0)
        val hits = copies.associateWith { Hit(it, "fiatjaf", 0, followers = 3, bare = true) } + (fiatjaf to Hit(fiatjaf, "fiatjaf", 50, followers = 40_000, nip05 = Nip05.Check.OK))
        val s = AuthorQuery.pick("fiatjaf", "failed", found, hits)!!
        assertEquals(fiatjaf, s.author); assertEquals("failed", s.rest); assertFalse(s.loose)
        // The next ones by that name are chips (fiatjafbot is a part of a name: not one of them).
        assertEquals(copies.take(4), s.others)
        // Before anything is counted: still the one with an address and an about, not a bare copy.
        assertEquals(fiatjaf, AuthorQuery.pick("fiatjaf", "failed", found, found.associate { it.pubkey to Hit(it.pubkey, it.name, 0, bare = Hit.bare(it)) })!!.author)
        assertNull(AuthorQuery.pick("nobodyhere", "failed", found, hits))
    }
}
