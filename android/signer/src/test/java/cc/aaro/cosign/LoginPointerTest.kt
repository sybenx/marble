package cc.aaro.cosign

import cc.aaro.cosign.LoginPointer.Plan
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Secp
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.toHex
import cc.aaro.keykin.core.Texts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keeping the login-page pointer on the account's relays (Keykin SPEC 11.1). */
class LoginPointerTest {
    private val sk = SystemEnv.random(32)
    private val pk = Secp.publicKey(sk).toHex()
    private val page = "https://account.syb.lol"
    private val a = "wss://a.example"; private val b = "wss://b.example"
    /** The old kind (30078): d `keykin/login`, a `url` tag. */
    private fun pointer(url: String?, at: Long = 100, by: ByteArray = sk) =
        Event.sign(Secp.publicKey(by).toHex(), at, 30078, listOf(listOf("d", "keykin/login")) + (url?.let { listOf(listOf("url", it)) } ?: emptyList()), "", by, SystemEnv)
    /** The new kind (10046): a `nostrconnect_url` template, or none when removed. */
    private fun pointer46(tpl: String?, at: Long = 100, by: ByteArray = sk) =
        Event.sign(Secp.publicKey(by).toHex(), at, 10046, tpl?.let { listOf(listOf("nostrconnect_url", it)) } ?: emptyList(), "", by, SystemEnv)
    private val tpl = "$page/#connect=<nostrconnect>"
    private fun both(at: Long = 100) = listOf(pointer46(tpl, at), pointer(page, at))

    @Test fun onlyAnHttpsOriginIsALoginPage() {
        assertEquals(page, LoginPointer.origin("https://Account.syb.lol/"))
        assertEquals("https://box.example:8443", LoginPointer.origin("https://box.example:8443"))
        assertEquals("https://box.example", LoginPointer.origin("https://box.example:443"))
        assertNull(LoginPointer.origin("http://10.0.2.2:8790"))
        assertNull(LoginPointer.origin("https://box.example/keykin"))
        assertNull(LoginPointer.origin("https://box.example?x=1"))
        assertNull(LoginPointer.origin("not a url"))
        assertEquals("http://10.0.2.2:8790", LoginPointer.origin("http://10.0.2.2:8790", allowHttp = true))
    }

    @Test fun theTemplateIsTheLoginOriginWithConnectLast() {
        assertEquals("https://account.syb.lol/#connect=<nostrconnect>", LoginPointer.template(page))
        assertEquals(listOf(listOf("nostrconnect_url", tpl)), LoginPointer.tags(10046, page))
        assertEquals(listOf(listOf("d", "keykin/login"), listOf("url", page)), LoginPointer.tags(30078, page))
        // Removing: no template, no url.
        assertEquals(emptyList<List<String>>(), LoginPointer.tags(10046, null))
        assertEquals(listOf(listOf("d", "keykin/login")), LoginPointer.tags(30078, null))
        assertEquals(page, LoginPointer.templateOrigin(tpl))
        assertEquals(page, LoginPointer.templateOrigin("https://Account.syb.lol:443/x#connect=<nostrconnect>"))
        for (bad in listOf("http://account.syb.lol/#connect=<nostrconnect>", "https://account.syb.lol/#connect=", "https://u:p@account.syb.lol/#connect=<nostrconnect>",
                "https://account.syb.lol/<nostrconnect>#connect=<nostrconnect>", "https://account.syb.lol/#connect=<nostrconnect>&x=1"))
            assertNull(bad, LoginPointer.templateOrigin(bad))
    }

    @Test fun noPointerAnywhereIsSignedAnewAsBothKinds() =
        assertEquals(Plan.Act(page, sign = listOf(10046, 30078)), LoginPointer.plan(page, pk, mapOf(a to emptyList(), b to emptyList()), listOf(a, b)))

    @Test fun onlyTheOldKindOutThereGetsTheNewOneSignedAndTheOldOneKept() {
        // An account from before the move: its 30078 names this page, so a 10046 is signed, and the 30078 still goes where it's missing.
        val old = pointer(page)
        assertEquals(Plan.Act(page, sign = listOf(10046), resend = listOf(LoginPointer.Copy(old, listOf(b)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(old), b to emptyList()), listOf(a, b)))
        // And the other way: a 10046 alone gets a 30078 too.
        val p = pointer46(tpl)
        assertEquals(Plan.Act(page, sign = listOf(30078)), LoginPointer.plan(page, pk, mapOf(a to listOf(p), b to listOf(p)), listOf(a, b)))
    }

    @Test fun aRelayThatLacksACopyOfEitherKindGetsTheSameEventAgain() {
        val (p46, p78) = both()
        // b lacks the 10046, a lacks the 30078: each gets the same signed event, no new signature.
        assertEquals(Plan.Act(page, resend = listOf(LoginPointer.Copy(p46, listOf(b)), LoginPointer.Copy(p78, listOf(a)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(p46), b to listOf(p78)), listOf(a, b)))
        // A relay holding only an older one gets the newest too.
        val (o46, o78) = both(50)
        assertEquals(Plan.Act(page, resend = listOf(LoginPointer.Copy(p46, listOf(b)), LoginPointer.Copy(p78, listOf(b)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(p46, p78), b to listOf(o46, o78)), listOf(a, b)))
        // A relay that didn't answer is left for next time.
        assertEquals(Plan.Act(page, resend = listOf(LoginPointer.Copy(p46, listOf(b)), LoginPointer.Copy(p78, listOf(b)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(p46, p78), b to emptyList()), listOf(a, b, "wss://silent.example")))
    }

    @Test fun withNoLoginPageKnownNothingIsSignedOrSentAround() {
        // Not a new one, and not the one already out there: it can't be checked against a page this phone was told of.
        val p = pointer("https://box.lookalike.example")
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to emptyList(), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to listOf(p), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to listOf(pointer46(tpl)), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, emptyMap(), listOf(a)))
    }

    @Test fun theLoginPageComesOnlyFromTheCodeNeverTheBoxAddress() {
        val k = "ab".repeat(32)
        // A code naming no login page gives none, even when its box address is a perfectly good https origin
        // (a lookalike domain proxying the real box would answer anything asked of it there).
        val bare = Texts.qr("""{"keykin":2,"addr":"https://box.lookalike.example","key":"$k","code":"c"}""")
        assertNull(LoginPointer.fromCode(bare.login))
        val named = Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c","loginPage":"https://Account.syb.lol/"}""")
        assertEquals(page, LoginPointer.fromCode(named.login))
        assertNull(LoginPointer.fromCode("https://account.syb.lol/login"))
        // A pointer naming the page the code named is still sent to a relay lacking it.
        val (p46, p78) = both()
        assertEquals(Plan.Act(page, resend = listOf(LoginPointer.Copy(p46, listOf(b)), LoginPointer.Copy(p78, listOf(b)))),
            LoginPointer.plan(LoginPointer.fromCode(named.login), pk, mapOf(a to listOf(p46, p78), b to emptyList()), listOf(a, b)))
    }

    @Test fun theSignerNeverProbesTheBoxForALoginPage() {
        // The guess (POST <box address>/api answering 403) is gone; the origin is only ever read from what a code named.
        val src = java.io.File("src/main/java/cc/aaro/cosign/Signer.kt").readText()
        org.junit.Assert.assertFalse(src.contains("isKeykinLogin"))
        org.junit.Assert.assertFalse(src.contains("LoginPointer.origin(h.addr"))
        org.junit.Assert.assertFalse(src.contains("/api\""))
    }

    @Test fun onEveryRelayAlreadyIsLeft() {
        val all = both()
        assertEquals(Plan.Leave("on every write relay that answered"), LoginPointer.plan(page, pk, mapOf(a to all, b to all), listOf(a, b)))
    }

    @Test fun aPointerRemovedOnPurposeIsNeverRecreated() {
        // The newest 10046 has no template: removed, and the old kind (still naming the page) is not resent or a reason to sign.
        val gone = pointer46(null, 200)
        assertEquals(Plan.Leave("removed on purpose (10046)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46(tpl, 100), pointer(page)), b to listOf(gone)), listOf(a, b)))
        assertEquals(Plan.Leave("removed on purpose (10046)"), LoginPointer.plan(page, pk, mapOf(a to listOf(gone), b to emptyList()), listOf(a, b)))
        // The old kind removed on purpose (and no 10046): not recreated as either kind.
        assertEquals(Plan.Leave("removed on purpose (30078)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer(page, 100)), b to listOf(pointer(null, 200))), listOf(a, b)))
    }

    @Test fun aPointerToAnotherPageIsNeverReplacedOrSentAround() {
        assertEquals(Plan.Leave("names https://other.example (10046)"),
            LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46("https://other.example/#connect=<nostrconnect>"), pointer(page)), b to emptyList()), listOf(a, b)))
        assertEquals(Plan.Leave("names https://other.example (30078)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer("https://other.example")), b to emptyList()), listOf(a, b)))
        // A template that isn't a usable one is not this page either.
        val odd = "https://account.syb.lol/#connect="
        assertEquals(Plan.Leave("names $odd (10046)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46(odd))), listOf(a)))
    }

    // --- The pointer follows the box this phone signs with ---

    private val oldBox = "https://old-box.example"
    private fun both(origin: String, at: Long = 100) = listOf(pointer46(LoginPointer.template(origin), at), pointer(origin, at))
    private val bothKinds = listOf(10046, 30078)

    @Test fun movedToANewBoxSignsBothKindsNamingIt() {
        // The newest of both kinds names a page this phone used before: the person moved, so both are signed anew.
        val old = both(oldBox, 200)
        assertEquals(Plan.Act(page, sign = bothKinds), LoginPointer.plan(page, pk, mapOf(a to old, b to old), listOf(a, b), listOf(oldBox)))
        // Either kind alone naming it is enough, and the other kind is signed too where it is missing.
        assertEquals(Plan.Act(page, sign = bothKinds), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer("https://Old-Box.example/"))), listOf(a), listOf(oldBox)))
        assertEquals(Plan.Act(page, sign = bothKinds), LoginPointer.plan(page, pk, mapOf(a to listOf(old[0])), listOf(a), listOf(oldBox)))
        // One kind already moved (named this page), the other still on the old one: only the old one is signed, the new one kept.
        val new46 = pointer46(tpl, 300)
        assertEquals(Plan.Act(page, sign = listOf(30078), resend = listOf(LoginPointer.Copy(new46, listOf(b)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(new46, old[1]), b to listOf(old[1])), listOf(a, b), listOf(oldBox)))
    }

    @Test fun aPageNeverUsedHereIsLeftEvenAfterAMove() {
        assertEquals(Plan.Leave("names https://other.example (30078)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer("https://other.example")), b to emptyList()), listOf(a, b), listOf(oldBox)))
        // One kind on a page used here, the other on one never used: the unknown one wins, nothing is signed.
        assertEquals(Plan.Leave("names https://other.example (10046)"),
            LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46(LoginPointer.template("https://other.example")), pointer(oldBox))), listOf(a), listOf(oldBox)))
    }

    @Test fun aRemovalIsLeftEvenAfterAMove() {
        assertEquals(Plan.Leave("removed on purpose (30078)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer(oldBox, 100)), b to listOf(pointer(null, 200))), listOf(a, b), listOf(oldBox)))
        assertEquals(Plan.Leave("removed on purpose (10046)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46(null, 200), pointer(oldBox))), listOf(a), listOf(oldBox)))
    }

    @Test fun theSamePageIsSentAroundAsBeforeWithAHistory() {
        val (p46, p78) = both()
        assertEquals(Plan.Act(page, resend = listOf(LoginPointer.Copy(p46, listOf(b)), LoginPointer.Copy(p78, listOf(b)))),
            LoginPointer.plan(page, pk, mapOf(a to listOf(p46, p78), b to emptyList()), listOf(a, b), listOf(oldBox)))
        // Back at a box used before: its page is current again, so pointers naming it are kept, not re-signed.
        assertEquals(Plan.Leave("on every write relay that answered"), LoginPointer.plan(page, pk, mapOf(a to listOf(p46, p78)), listOf(a), listOf(oldBox, page)))
    }

    @Test fun withNoCurrentPageAMoveSignsNothing() =
        assertEquals(Plan.Leave("no login page"), LoginPointer.plan(null, pk, mapOf(a to both(oldBox)), listOf(a), listOf(oldBox), afterJoin = true))

    @Test fun joiningANewBoxMakesItsPageCurrentAndKeepsTheOldOne() {
        assertEquals(page to listOf(oldBox), LoginPointer.joined(oldBox, emptyList(), page))
        assertEquals(page to emptyList<String>(), LoginPointer.joined(null, emptyList(), page))
        // The same box's page again: nothing moves.
        assertEquals(page to emptyList<String>(), LoginPointer.joined(page, emptyList(), LoginPointer.origin("https://Account.syb.lol")))
        // A box whose code names no page: none is current, and the one left is kept.
        assertEquals(null to listOf(oldBox), LoginPointer.joined(oldBox, emptyList(), null))
        // No repeats; the newest stays last; only the last few are kept.
        assertEquals(page to listOf("https://c.example", oldBox), LoginPointer.joined(oldBox, listOf(oldBox, "https://c.example"), page))
        val many = (1..20).map { "https://b$it.example" }
        assertEquals(LoginPointer.USED_MAX, LoginPointer.joined(oldBox, many, page).second.size)
        assertEquals(oldBox, LoginPointer.joined(oldBox, many, page).second.last())
    }

    // --- Right after a join, a page never used here is asked about once ---

    private val unknown = "https://account.syb.lol"
    private val newBox = "https://new-box.example"

    @Test fun aJoinAsksAboutAPageNeverUsedHereButASignInCheckLeavesIt() {
        val by = mapOf(a to both(unknown))
        assertEquals(Plan.Ask(newBox, unknown), LoginPointer.plan(newBox, pk, by, listOf(a), afterJoin = true))
        assertEquals(Plan.Leave("names $unknown (10046)"), LoginPointer.plan(newBox, pk, by, listOf(a)))
        // Only the old kind out there: asked about the same way.
        assertEquals(Plan.Ask(newBox, unknown), LoginPointer.plan(newBox, pk, mapOf(a to listOf(pointer(unknown))), listOf(a), afterJoin = true))
        // A page used here before needs no question: it moves.
        assertEquals(Plan.Act(newBox, sign = bothKinds), LoginPointer.plan(newBox, pk, by, listOf(a), listOf(unknown), afterJoin = true))
    }

    @Test fun switchSignsTheNewPageAndRecordsTheOldOne() {
        val ans = LoginPointer.answer(Plan.Ask(newBox, unknown), true, listOf(oldBox), emptyList())
        assertEquals(newBox, ans.sign)
        assertEquals(listOf(oldBox, unknown), ans.used)
        assertEquals(emptyList<String>(), ans.declined)
        // Recorded as used: a later move goes past it with no question, even at a sign-in check.
        assertEquals(Plan.Act("https://third.example", sign = bothKinds), LoginPointer.plan("https://third.example", pk, mapOf(a to both(unknown)), listOf(a), ans.used))
    }

    @Test fun keepRecordsThePairAndALaterJoinLeavesIt() {
        val ans = LoginPointer.answer(Plan.Ask(newBox, unknown), false, listOf(oldBox), emptyList())
        assertNull(ans.sign)
        assertEquals(listOf(oldBox), ans.used)
        assertEquals(listOf(LoginPointer.pair(unknown, newBox)), ans.declined)
        val by = mapOf(a to listOf(pointer("https://Account.syb.lol/")))
        assertEquals(Plan.Leave("names https://Account.syb.lol (30078)"), LoginPointer.plan(newBox, pk, by, listOf(a), ans.used, afterJoin = true, declined = ans.declined))
        // Only that pair: joining yet another box asks again.
        assertEquals(Plan.Ask("https://third.example", "https://Account.syb.lol"), LoginPointer.plan("https://third.example", pk, by, listOf(a), ans.used, afterJoin = true, declined = ans.declined))
    }

    @Test fun aRemovalIsNeverAskedAbout() {
        assertEquals(Plan.Leave("removed on purpose (30078)"), LoginPointer.plan(newBox, pk, mapOf(a to listOf(pointer(unknown, 100), pointer(null, 200))), listOf(a), afterJoin = true))
        assertEquals(Plan.Leave("removed on purpose (10046)"), LoginPointer.plan(newBox, pk, mapOf(a to listOf(pointer46(null, 200), pointer(unknown))), listOf(a), afterJoin = true))
    }

    @Test fun noRelayAnsweredWaits() {
        assertEquals(Plan.Wait, LoginPointer.plan(page, pk, emptyMap(), listOf(a)))
    }

    @Test fun forgedAndOtherAccountsPointersDontCount() {
        val forged = pointer(page, 300).copy(sig = "00".repeat(64))
        val forged46 = pointer46(tpl, 300).copy(sig = "00".repeat(64))
        val someoneElse = pointer("https://other.example", 400, SystemEnv.random(32))
        val someoneElse46 = pointer46("https://other.example/#connect=<nostrconnect>", 400, SystemEnv.random(32))
        assertEquals(Plan.Act(page, sign = listOf(10046, 30078)), LoginPointer.plan(page, pk, mapOf(a to listOf(forged, forged46, someoneElse, someoneElse46)), listOf(a)))
    }

    @Test fun theBoxCodeAndJoinTextCarryTheLoginPage() {
        val k = "ab".repeat(32)
        assertEquals(page, Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c","loginPage":"$page"}""").login)
        assertNull(Texts.qr("""{"keykin":2,"addr":"https://box.example","key":"$k","code":"c"}""").login)
        assertEquals(page, Texts.readJoin("""{"keykin":2,"join":{"addr":"https://box.example","boxKey":"$k","npub":"$k","loginPage":"$page"}}""").login)
    }

    // --- A `pointer` note: the account page asks to be the sign-in page ---

    @Test fun aPointerNoteNamesAnHttpsOriginOnly() {
        assertEquals(page, LoginPointer.requested(" https://Account.syb.lol/ "))
        assertEquals("https://box.example:8443", LoginPointer.requested("https://box.example:8443"))
        assertNull(LoginPointer.requested("http://account.syb.lol"))
        assertNull(LoginPointer.requested("http://10.0.2.2:8790"))
        assertNull(LoginPointer.requested("https://account.syb.lol/login"))
        assertNull(LoginPointer.requested("https://account.syb.lol/#connect=x"))
        assertNull(LoginPointer.requested("https://me@account.syb.lol"))
        assertNull(LoginPointer.requested("javascript:alert(1)"))
        assertNull(LoginPointer.requested(""))
    }

    @Test fun yesSignsTheAskedPageAndTheOldOneJoinsTheUsed() {
        val m = LoginPointer.made(page, true, "https://old.example", listOf("https://older.example"))
        assertEquals("ok", m.result)
        assertEquals(page, m.sign)
        assertEquals(page, m.current)
        assertEquals(listOf("https://older.example", "https://old.example"), m.used)
    }

    @Test fun yesWithNoCurrentPageOrTheSameOneAddsNothingToTheUsed() {
        assertEquals(LoginPointer.Made("ok", page, page, emptyList()), LoginPointer.made(page, true, null, emptyList()))
        assertEquals(LoginPointer.Made("ok", page, page, listOf("https://old.example")), LoginPointer.made(page, true, "https://ACCOUNT.syb.lol", listOf("https://old.example")))
    }

    @Test fun yesToAPageUsedBeforeKeepsTheHistoryAsJoinedDoes() {
        val m = LoginPointer.made(page, true, "https://box.example", listOf(page, "https://old.example"))
        assertEquals(page, m.current)
        // As after a join: the asked page is current, and the one left behind is the newest used.
        assertEquals(listOf(page, "https://old.example", "https://box.example"), m.used)
    }

    @Test fun noChangesNothingAndDeclines() {
        val m = LoginPointer.made(page, false, "https://old.example", listOf("https://older.example"))
        assertEquals(LoginPointer.Made("declined", null, "https://old.example", listOf("https://older.example")), m)
    }

    private val me = cc.aaro.keykin.core.Me(ByteArray(32) { 1 }, "Pixel 9a", null)
    private val held = cc.aaro.keykin.core.Held(cc.aaro.keykin.core.Piece(emptyList(), 1, java.math.BigInteger.ONE), "b".repeat(64), "c".repeat(64), "http://box")
    private fun sentFor(yes: Boolean): org.json.JSONObject {
        var sent: org.json.JSONObject? = null
        LoginPointer.reply(me, held, "n1", LoginPointer.made(page, yes, null, emptyList())) { sent = it; org.json.JSONObject().put("ok", true) }
        return sent!!.getJSONObject("body")
    }

    @Test fun notNowSendsNoteDoneDeclinedNeverNoteRefuse() {
        val body = sentFor(false)
        assertEquals("note_done", body.getString("op"))
        assertEquals("declined", body.getString("result"))
        assertEquals("n1", body.getString("note"))
        assertEquals(held.npub, body.getString("account"))
    }

    @Test fun yesSendsNoteDoneOk() {
        val body = sentFor(true)
        assertEquals("note_done", body.getString("op"))
        assertEquals("ok", body.getString("result"))
    }

    @Test fun theBoxsPointerNoteReadsAsOne() {
        val n = cc.aaro.keykin.core.Device.Note.of(org.json.JSONObject("""{"id":"n1","kind":"pointer","text":"$page","at":5,"tap":true}"""))
        assertEquals("pointer", n.kind); assertEquals(true, n.tap)
        assertEquals(page, LoginPointer.requested(n.text))
    }

    @Test fun yesPublishesEvenWherePlanWouldLeaveIt() {
        // A removal, or a pointer naming a page this phone never used, is left by the checks; the person's Yes still signs.
        assertEquals(Plan.Leave("removed on purpose (10046)"), LoginPointer.plan(page, pk, mapOf(a to listOf(pointer46(null), pointer(null))), listOf(a)))
        assertEquals(page, LoginPointer.made(page, true, page, emptyList()).sign)
    }
}
