package cc.aaro.cosign.core

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLetInTest {
    private val home = "https://bothy-relay.sybenx.workers.dev/"
    private val me = "a".repeat(64)
    private val owner = "f".repeat(64)
    private val kidA = "1".repeat(64)
    private val kidB = "2".repeat(64)
    private val kidC = "3".repeat(64)

    /** A home that answers like bothy, and remembers every call made to it. */
    private class FakeHome(val owner: String, val roles: Map<String?, String>, var members: MutableSet<String>, val removed: Set<String> = emptySet(),
                           val inviteError: String? = null, val ownKeys: String? = null) : HomeLetIn.Net {
        override fun proof(home: String, member: String, child: String): Map<String, Any?> = mapOf("kind" to 21236, "pubkey" to child, "tags" to listOf(listOf("p", member), listOf("relay", home)))
        val calls = mutableListOf<String>()
        override fun info(home: String): Map<*, *> = mapOf("pubkey" to owner, "self" to "e".repeat(64), "name" to "Aaro's relay")
        override fun call(home: String, method: String, params: List<Any?>, asChild: String?): Any? {
            calls += "$method ${params.joinToString(",")}${asChild?.let { " as ${it.take(4)}" } ?: ""}"
            return when (method) {
                "myrole" -> mapOf("role" to (if (asChild != null) (if (asChild in members) "member" else "none") else roles[null]))
                "listmembers" -> mapOf("members" to members.map { mapOf("pubkey" to it) }, "removed" to removed.map { mapOf("pubkey" to it) })
                "allowpubkey" -> { members += params[0] as String; true }
                "createinvite" -> inviteError?.let { throw HomeLetIn.Refused(it) } ?: mapOf("code" to "c0de")
                "redeeminvite" -> { members += asChild!!; true }
                // ownKeys: null = a home before v0.30; "ok" = it has addmykey; anything else = addmykey's refusal.
                "addmykey" -> when (ownKeys) {
                    null -> throw HomeLetIn.Refused("unknown method 'addmykey' -- call supportedmethods for the list this relay implements")
                    "ok" -> { members += params[0] as String; true }
                    else -> throw HomeLetIn.Refused(ownKeys)
                }
                else -> error(method)
            }
        }
    }
    private class Kept : HomeLetIn.Memory {
        val m = HashMap<String, MutableSet<String>>()
        override fun seen(home: String) = m[home] ?: emptySet()
        override fun markSeen(home: String, child: String) { m.getOrPut(home) { mutableSetOf() } += child }
    }

    @Test fun inboxRelaysBecomeHomeAddresses() {
        assertEquals("https://bothy-relay.sybenx.workers.dev/", HomeLetIn.httpOf("wss://Bothy-Relay.sybenx.workers.dev"))
        assertEquals("https://a.example/inbox", HomeLetIn.httpOf("wss://a.example/inbox"))
        assertNull(HomeLetIn.httpOf("ws://a.example"))
        val ev = Event(me, 1L, 10050, listOf(listOf("relay", "wss://bothy-relay.sybenx.workers.dev"), listOf("r", "wss://x")), "")
        assertEquals(listOf(home), HomeLetIn.inboxHomes(ev))
    }

    @Test fun aRelayWithoutAnOwnerAndItsOwnKeyIsNoHome() {
        assertNull(HomeLetIn.ownerOf(mapOf("pubkey" to owner)))
        assertEquals(owner, HomeLetIn.ownerOf(mapOf("pubkey" to owner, "self" to "e".repeat(64))))
    }

    @Test fun theOwnersMissingChildrenAreLetInAndNobodyElseIsTouched() {
        val net = FakeHome(me, emptyMap(), mutableSetOf(kidA))
        val kept = Kept()
        HomeLetIn.run(me, listOf(kidA, kidB), listOf(home), net, kept)
        // kidA was in already: allowpubkey is an upsert that rewrites who let it in, so it is not sent again.
        assertEquals(listOf("listmembers ", "allowpubkey $kidB"), net.calls)
        assertEquals(setOf(kidA, kidB), kept.seen(home))
    }

    @Test fun aRemovedChildStaysOutEvenAfterBothyForgetsTheRemoval() {
        val net = FakeHome(me, emptyMap(), mutableSetOf(), removed = setOf(kidA))
        val kept = Kept()
        HomeLetIn.run(me, listOf(kidA), listOf(home), net, kept)
        assertEquals(listOf("listmembers "), net.calls)
        // Day 15: bothy no longer lists it as removed, and it is not in. This phone remembers.
        val later = FakeHome(me, emptyMap(), mutableSetOf())
        HomeLetIn.run(me, listOf(kidA), listOf(home), later, kept)
        assertTrue(later.calls.isEmpty())
    }

    @Test fun anAdminAsksItsRoleThenLetsIn() {
        val net = FakeHome(owner, mapOf(null to "admin"), mutableSetOf())
        HomeLetIn.run(me, listOf(kidA), listOf(home), net, Kept())
        assertEquals(listOf("myrole ", "listmembers ", "allowpubkey $kidA"), net.calls)
    }

    @Test fun aPlainMemberSpendsAnInviteTheChildRedeems() {
        val net = FakeHome(owner, mapOf(null to "member"), mutableSetOf(kidB))
        val kept = Kept()
        val said = HomeLetIn.run(me, listOf(kidA, kidB), listOf(home), net, kept)
        assertTrue(said.isEmpty())
        // addmykey is tried once; this home doesn't have it, so the rest go straight to an invite.
        assertEquals(listOf("myrole ", "myrole  as 1111", "addmykey $kidA,{kind=21236, pubkey=$kidA, tags=[[p, $me], [relay, $home]]}", "createinvite 1,600", "redeeminvite c0de as 1111", "myrole  as 2222"), net.calls)
        assertEquals(setOf(kidA, kidB), kept.seen(home))
    }

    @Test fun aHomeWithOwnKeysTakesTheChildWithoutAnInvite() {
        val net = FakeHome(owner, mapOf(null to "member"), mutableSetOf(), ownKeys = "ok")
        val kept = Kept()
        HomeLetIn.run(me, listOf(kidA, kidC), listOf(home), net, kept)
        assertTrue(net.calls.none { it.startsWith("createinvite") })
        assertEquals(setOf(kidA, kidC), net.members)
        assertEquals(setOf(kidA, kidC), kept.seen(home))
    }

    @Test fun addmykeysOwnNoIsTheAnswerNotAnInvite() {
        val net = FakeHome(owner, mapOf(null to "member"), mutableSetOf(), ownKeys = "addmykey: at most 10 own keys each")
        val kept = Kept()
        val said = HomeLetIn.run(me, listOf(kidA), listOf(home), net, kept)
        assertTrue(net.calls.none { it.startsWith("createinvite") })
        assertEquals(listOf("Your other account can't be let into this home: at most 10 own keys each"), said)
        assertTrue(kept.seen(home).isEmpty())   // tried again tomorrow
    }

    @Test fun aChildRemovedOnPurposeIsNotAskedAgain() {
        val net = FakeHome(owner, mapOf(null to "member"), mutableSetOf(),
            ownKeys = "addmykey: that account was removed from this home. Ask the owner or an admin to let it back in.")
        val kept = Kept()
        val said = HomeLetIn.run(me, listOf(kidA), listOf(home), net, kept)
        assertTrue(said.isEmpty())
        assertTrue(net.calls.none { it.startsWith("createinvite") })
        assertEquals(setOf(kidA), kept.seen(home))
    }

    @Test fun aMemberWithNoInvitesLeftIsToldInOneLine() {
        val net = FakeHome(owner, mapOf(null to "member"), mutableSetOf(), inviteError = "createinvite: you have used all 5 of your invites")
        val kept = Kept()
        val said = HomeLetIn.run(me, listOf(kidA, kidC), listOf(home), net, kept)
        assertEquals(listOf("You have no invites left in this home, so your other accounts can't be let in."), said)
        assertTrue(kept.seen(home).isEmpty())   // tried again tomorrow
    }

    @Test fun nothingIsAskedWhenEveryChildWasSeen() {
        val kept = Kept().apply { markSeen(home, kidA) }
        val net = FakeHome(me, emptyMap(), mutableSetOf())
        HomeLetIn.run(me, listOf(kidA), listOf(home), net, kept)
        assertTrue(net.calls.isEmpty())
    }

    @Test fun notInTheHomeMeansNothingSent() {
        val net = FakeHome(owner, mapOf(null to "none"), mutableSetOf())
        HomeLetIn.run(me, listOf(kidA), listOf(home), net, Kept())
        assertEquals(listOf("myrole "), net.calls)
    }

    @Test(expected = IllegalStateException::class) fun noHomeAnsweringIsATryAgain() {
        val down = object : HomeLetIn.Net {
            override fun info(home: String): Map<*, *> = throw java.io.IOException("offline")
            override fun call(home: String, method: String, params: List<Any?>, asChild: String?): Any? = error("no")
            override fun proof(home: String, member: String, child: String): Map<String, Any?> = error("no")
        }
        HomeLetIn.run(me, listOf(kidA), listOf(home), down, Kept())
    }
}
