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
}
