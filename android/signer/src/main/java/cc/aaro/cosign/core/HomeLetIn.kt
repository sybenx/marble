package cc.aaro.cosign.core

import cc.aaro.qrst.core.Event

/**
 * Every child key of this account is a member of each home (a bothy relay) the account belongs to (Keykin SPEC 8k,
 * "children in the home", 2026-11-22). Aaron: "my child account should never not be in. none of them".
 *
 * Only this phone knows which children exist (the box keeps nothing about them, the account page signs only as the
 * account), so it is done here: when a child is made, and once a day for the ones made before.
 *
 * - **Owner or admin:** one `listmembers`, then `allowpubkey` for each child that is neither in nor removed, signed as
 *   the account. bothy's `allowpubkey` is an upsert that rewrites who let someone in and when, so it is never sent for
 *   someone already in.
 * - **Plain member:** they can't `allowpubkey`. The child asks `myrole` itself; if it isn't in, the account calls
 *   `addmykey [child, proof]` (bothy v0.30: the member's own key, no invite spent), with the child's signed proof.
 *   On a home without it, the account makes a one-use invite (`createinvite`) and the child redeems it. When bothy
 *   says no (no invites left, too far from the owner), that is said in one plain line.
 * - **Removed stays removed.** A child this phone has once seen in a home is never let in again by this phone: if
 *   it is out now, the owner or an admin took it out, and "Let back in" on the account page is the way back. bothy
 *   forgets a removal after 14 days; this phone does not.
 *
 * Homes are found from the account's DM inbox list (kind 10050) as this phone keeps it, else as published, and
 * from each child's published inbox list. A relay is a home when its NIP-11 names an owner (`pubkey`) and its own
 * key (`self`), as bothy's does.
 */
object HomeLetIn {
    /** The https address a home answers NIP-11 and NIP-86 on, from a relay in an inbox list. */
    fun httpOf(relay: String): String? {
        val r = relay.trim()
        val rest = when {
            r.startsWith("wss://", true) -> r.substring(6)
            r.startsWith("https://", true) -> r.substring(8)
            else -> return null
        }
        if (rest.isEmpty() || rest.startsWith("/")) return null
        val host = rest.substringBefore('/').lowercase()
        val path = rest.substringAfter('/', "")
        return "https://$host/$path"
    }

    /** The relays in a kind 10050, as homes' addresses. */
    fun inboxHomes(ev: Event?): List<String> =
        ev?.takeIf { it.kind == 10050 }?.tags?.filter { it.size > 1 && it[0] == "relay" }?.mapNotNull { httpOf(it[1]) } ?: emptyList()

    /** A bothy home's NIP-11: the owner's key, or null when this relay isn't a home. */
    fun ownerOf(info: Map<*, *>?): String? {
        val owner = info?.get("pubkey") as? String ?: return null
        if (info["self"] !is String || !HEX64.matches(owner)) return null
        return owner.lowercase()
    }

    data class Members(val members: Set<String>, val removed: Set<String>)

    /** `listmembers`'s result. */
    fun members(result: Any?): Members? {
        val m = result as? Map<*, *> ?: return null
        fun keys(k: String) = (m[k] as? List<*>)?.mapNotNull { ((it as? Map<*, *>)?.get("pubkey") as? String)?.lowercase() }?.toSet()
        return Members(keys("members") ?: return null, keys("removed") ?: emptySet())
    }

    /** One NIP-86 call to a home, signed as the account ([asChild] null) or as that child. Its `result`, or [Refused] with bothy's words. */
    interface Net {
        fun info(home: String): Map<*, *>?
        fun call(home: String, method: String, params: List<Any?>, asChild: String?): Any?
        /**
         * The child's half of `addmykey`, sent only to the home and never published: a kind 21236 signed by [child],
         * tags `["p", member]` and `["relay", home]`, empty content, made now. As a JSON object.
         */
        fun proof(home: String, member: String, child: String): Map<String, Any?>
    }
    /** bothy said no, in words meant for people (`error`, or a 401's text). */
    class Refused(message: String) : Exception(message)

    /** What this phone keeps: per home, the children it has seen in it (or let in). */
    interface Memory {
        fun seen(home: String): Set<String>
        fun markSeen(home: String, child: String)
    }

    /**
     * Let in each of [children] (x-only hex) that this phone has not yet seen in each of [homes] the account
     * ([account], hex) belongs to. Returns the plain lines to show (bothy saying no to a plain member's invite).
     * Throws only if nothing could be reached at all, so a caller can try again later.
     */
    fun run(account: String, children: Collection<String>, homes: Collection<String>, net: Net, memory: Memory): List<String> {
        val said = mutableListOf<String>()
        val ownKeys = HashMap<String, Boolean>()
        var failed = 0
        val all = homes.distinct()
        for (home in all) try {
            val todo = children.map { it.lowercase() }.filter { it !in memory.seen(home) && it != account }.distinct()
            if (todo.isEmpty()) continue
            val owner = ownerOf(net.info(home)) ?: continue
            val role = if (owner == account.lowercase()) "owner"
                else ((net.call(home, "myrole", emptyList(), null) as? Map<*, *>)?.get("role") as? String) ?: "none"
            when (role) {
                "owner", "admin" -> {
                    val m = members(net.call(home, "listmembers", emptyList(), null)) ?: continue
                    for (c in todo) {
                        // In already, or removed by someone's hand (and so to stay out): seen, nothing sent.
                        if (c in m.members || c in m.removed || c == owner) { memory.markSeen(home, c); continue }
                        net.call(home, "allowpubkey", listOf(c), null)
                        memory.markSeen(home, c)
                    }
                }
                "member" -> for (c in todo) {
                    val theirs = ((net.call(home, "myrole", emptyList(), c) as? Map<*, *>)?.get("role") as? String) ?: "none"
                    if (theirs != "none") { memory.markSeen(home, c); continue }
                    // bothy v0.30's own-key call first: no invite spent. A home without it says so (an unknown
                    // method, or not for members); its own refusals start "addmykey:" and are the answer.
                    if (ownKeys.getOrPut(home) { true }) try {
                        net.call(home, "addmykey", listOf(c, net.proof(home, account.lowercase(), c)), null)
                        memory.markSeen(home, c); continue
                    } catch (e: Refused) {
                        val why = e.message ?: ""
                        if (why.startsWith("addmykey:")) {
                            // In already, or taken out on purpose: settled, never asked again (bothy v0.30 contract).
                            if ("already a member" in why || "was removed" in why) { memory.markSeen(home, c); continue }
                            said += "Your other account can't be let into this home: ${why.substringAfter(": ")}"; continue
                        }
                        ownKeys[home] = false
                    }
                    val code = try {
                        (net.call(home, "createinvite", listOf(1, 600), null) as? Map<*, *>)?.get("code") as? String
                    } catch (e: Refused) { said += cantInvite(e.message ?: ""); break } ?: continue
                    net.call(home, "redeeminvite", listOf(code), c)
                    memory.markSeen(home, c)
                }
                else -> {}   // not in this home: nothing to bring a child into
            }
        } catch (e: Refused) { failed++ } catch (e: Exception) { failed++ }
        if (failed > 0 && failed == all.size) throw IllegalStateException("no home answered")
        return said.distinct()
    }

    /** bothy's reason a member can't invite, as one plain line about their own accounts. */
    fun cantInvite(why: String): String = when {
        "used all" in why -> "You have no invites left in this home, so your other accounts can't be let in."
        "steps from the owner" in why -> "Your other accounts can't be let into this home: invites stop where you are in it."
        "only the owner and admins" in why -> "Your other accounts can't be let into this home: only its owner and admins make invites."
        else -> "Your other accounts can't be let into this home: ${why.substringAfter(": ").ifBlank { "it said no" }}"
    }

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
}
