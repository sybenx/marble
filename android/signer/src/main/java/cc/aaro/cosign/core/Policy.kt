package cc.aaro.cosign.core

import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json

/**
 * What has to wait for you: the phone's own copy of server/policy.ts, line for line.
 *
 * The phone co-signs routine things without asking, so it can't take a server's word
 * that something is routine: a compromised server could just leave the flag off. It
 * judges each event itself, against the last version of that list it has seen signed.
 */
object Policy {
    val ARCHIVED = setOf(
        0, 3, 10000, 10001, 10002, 10003, 10004, 10006, 10007, 10015, 10030,
        30000, 30001, 30002, 30003, 30004, 30015, 30030,
    )

    private val NAMES = mapOf(
        0 to ("profile" to "profile field"), 3 to ("follow list" to "follow"), 10000 to ("mute list" to "mute"), 10001 to ("pinned notes" to "pin"),
        10002 to ("relay list" to "relay"), 10003 to ("bookmarks" to "bookmark"), 10004 to ("communities" to "community"),
        10006 to ("blocked relays" to "relay"), 10007 to ("search relays" to "relay"), 10015 to ("interests" to "interest"), 10030 to ("emoji list" to "emoji"),
        30000 to ("follow set" to "person"), 30001 to ("list" to "item"), 30002 to ("relay set" to "relay"), 30003 to ("bookmark set" to "bookmark"),
        30004 to ("curation set" to "item"), 30015 to ("interest set" to "interest"), 30030 to ("emoji set" to "emoji"),
    )
    private val RELAYISH = setOf(10002, 10006, 10007, 30002)
    /** How far ahead of now a clock may be; past this, an event is dated in the future. */
    const val AHEAD = 600L
    private fun replaceable(k: Int) = k == 0 || k == 3 || k in 10000 until 20000 || k in 30000 until 40000
    private fun day(t: Long) = java.time.Instant.ofEpochSecond(t).toString().take(10)
    private fun upper(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Set when this must never be signed, approved or not. Same rules as server/policy.ts. */
    fun refuse(e: Event, now: Long = Clock.now()): String? {
        if (e.createdAt <= now + AHEAD) return null
        if (replaceable(e.kind)) return "${upper(kindName(e.kind))} dated ${day(e.createdAt)}: a version dated ahead would block every later change"
        if (e.kind == 5 && e.tags.any { it.isNotEmpty() && it[0] == "a" }) return "Deletion dated ${day(e.createdAt)}: it would delete every version until then"
        return null
    }
    private val PROFILE_FIELDS = listOf("name", "display_name", "picture", "about", "nip05", "lud16", "banner", "website")

    private val EVENT_NAMES = mapOf(
        1 to "note", 4 to "DM", 5 to "deletion", 6 to "repost", 7 to "reaction", 13 to "DM", 14 to "DM", 16 to "repost", 62 to "request to vanish",
        1111 to "comment", 9734 to "zap request", 22242 to "relay login", 24242 to "upload login", 27235 to "web login", 30023 to "article", 30078 to "app data", 10046 to "login page",
    )
    private fun kindName(k: Int) = NAMES[k]?.first ?: EVENT_NAMES[k] ?: "kind $k event"
    /** A kind's name in plain words ("follow list", "note"), or null when it has none. */
    fun nameOf(k: Int): String? = NAMES[k]?.first ?: EVENT_NAMES[k]
    private fun itemName(k: Int) = NAMES[k]?.second ?: "item"
    private fun plural(n: Int, k: Int) = "$n ${itemName(k)}${if (n == 1) "" else "s"}"

    /** Replaceable events are keyed by kind; parameterised ones by kind and d tag. */
    fun keyOf(e: Event): String = if (e.kind in 30000 until 40000) "${e.kind}:${e.firstTag("d") ?: ""}" else "${e.kind}"

    private fun items(e: Event): List<String> = tagged(e).map { it.first }
    /** Each entry of a list (who, which relay, which note) with its whole tag. */
    private fun tagged(e: Event): List<Pair<String, List<String>>> = when (e.kind) {
        3 -> e.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] to it }
        in RELAYISH -> e.tags.filter { it.size > 1 && (it[0] == "r" || it[0] == "relay") }.map { it[1].trimEnd('/') to it }
        else -> e.tags.filter { it.isNotEmpty() && it[0] != "d" && it[0] != "title" && it[0] != "description" }.map { Json.stringify(it.take(2)) to it }
    }

    /** Of these, one must be left filled, or the whole profile goes (Keykin's rule, and level 2). */
    private val IDENTITY = listOf("name", "display_name", "about", "picture", "nip05", "lud16")

    private fun profile(e: Event?): Map<*, *> = try { Json.parse(e?.content ?: "{}") as? Map<*, *> ?: emptyMap<String, Any>() } catch (_: Exception) { emptyMap<String, Any>() }
    private fun filled(v: Any?) = if (v is String) v.isNotBlank() else v != null

    /** The profile fields [now] empties (empty: all of it), when that is a wipe: two or more, or everything; else null. */
    private fun wiped(prev: Event, now: Event): List<String>? {
        val was = profile(prev); val after = profile(now)
        val gone = PROFILE_FIELDS.filter { filled(was[it]) && !filled(after[it]) }
        return if (gone.size >= 2 || (after.isEmpty() && was.isNotEmpty())) gone else null
    }
    /**
     * The private part (encrypted, in the content) was there and is gone. Old follow lists kept your relays in the
     * content as plain JSON: that is no private part, and apps drop it all the time.
     */
    private fun emptied(prev: Event, now: Event) = now.kind != 0 && prev.content.isNotBlank() && !prev.content.trimStart().startsWith("{") && now.content.isBlank()

    /**
     * Set when you have to approve: what would be destroyed, in plain words, and how you are asked (Keykin SPEC 11.6, The
     * tap; Aaron, 2026-11-20). Level 1, "Are you sure?": Yes or No. Level 2, never by accident: erasing the account,
     * wiping the profile, emptying the follow list, deleting a whole kept list, a list's private part emptied. Yes only
     * on this phone, behind the fingerprint.
     */
    fun question(e: Event, prev: Event?, now: Long = Clock.now()): BoxAsk.Question? {
        if (e.createdAt > now + AHEAD) {
            if (refuse(e, now) != null) return null
            // Level 1, unless the same event dated now would be level 2 (a request to vanish, a list's deletion by k).
            val level = question(e.copy(createdAt = now), prev, now)?.level ?: 1
            return BoxAsk.Question("${upper(kindName(e.kind))} dated in the future (${day(e.createdAt)})", level)
        }
        if (e.kind == 62) return BoxAsk.Question("Ask relays to erase everything you have ever published", 2)
        if (e.kind == 5) {
            val targets = e.tags.count { it.isNotEmpty() && (it[0] == "e" || it[0] == "a") }
            val core = e.tags.filter { it.size > 1 && (it[0] == "a" || it[0] == "k") }.mapNotNull { it[1].substringBefore(':').toIntOrNull() }.filter { it in ARCHIVED }
            if (core.isNotEmpty()) return BoxAsk.Question("Delete your ${kindName(core[0])}", 2)
            if (targets >= 5) return BoxAsk.Question("Delete $targets of your events", 1)
            return null
        }
        if (e.kind !in ARCHIVED || prev == null) return null
        if (e.kind == 0) return wiped(prev, e)?.let { gone ->
            // Nothing left that says who this is: the whole profile goes.
            val after = profile(e)
            BoxAsk.Question("Wipe your profile: ${gone.joinToString(", ").ifEmpty { "everything" }}", if (IDENTITY.none { filled(after[it]) }) 2 else 1)
        }
        // The encrypted private part (private bookmarks, private mutes): an app that can't read it may write the list back without it.
        if (emptied(prev, e)) return BoxAsk.Question("Empty your private ${kindName(e.kind)}", 2)
        val before = items(prev); val after = items(e)
        val kept = after.toSet(); val had = before.toSet()
        val removed = before.count { it !in kept }
        val added = after.count { it !in had }
        // Of the lists emptied, only the follow list is level 2.
        if (after.isEmpty() && before.size >= 3) return BoxAsk.Question("Empty your ${kindName(e.kind)} (it has ${plural(before.size, e.kind)})", if (e.kind == 3) 2 else 1)
        val big = if (e.kind in RELAYISH) removed >= 2 && removed >= before.size * 0.5 else removed >= 10 && removed >= before.size * 0.2
        return if (big) BoxAsk.Question("Remove $removed of your ${before.size} ${itemName(e.kind)}s" + (if (added > 0) " (and add $added)" else ""), 1) else null
    }

    /** The question's words alone. */
    fun hold(e: Event, prev: Event?, now: Long = Clock.now()): String? = question(e, prev, now)?.says

    // --- Changes made somewhere else ---------------------------------------------------------------

    /**
     * A change to one of your lists that this phone did not sign, said for Recent changes: [text] for its row,
     * [alarm] (whole sentences) when something was lost, which also gets a notification titled [title].
     */
    data class Outside(val text: String, val alarm: String?, val title: String)

    /** What a version found elsewhere means against the last one this phone saw. */
    enum class Found {
        /** Not yours, not one of your lists, not signed, dated ahead, or not newer: nothing to do. */
        IGNORE,
        /** The first version of this list this phone sees: kept to compare with, nothing said. */
        BASELINE,
        /** Newer than the one this phone saw: kept, and a row in Recent changes. */
        CHANGED,
    }

    /** Judge [e], found on a relay, for [account] (the account's own key, never a site's), against [have], the latest seen. */
    fun found(e: Event, account: String?, have: Event?, now: Long = Clock.now()): Found = when {
        account == null || e.pubkey != account || e.kind !in ARCHIVED -> Found.IGNORE
        e.createdAt > now + AHEAD || (have != null && (have.id == e.id || e.createdAt <= have.createdAt)) -> Found.IGNORE
        !e.verify() -> Found.IGNORE
        have == null -> Found.BASELINE
        else -> Found.CHANGED
    }

    /** Entries as [hold] counts them, with their whole tags, less what an app adds about itself (its name, a description for other apps). */
    private fun entries(e: Event) = tagged(e.copy(tags = e.tags.filter { it.isEmpty() || (it[0] != "client" && it[0] != "alt") }))
    private fun verb(name: String) = if (name.endsWith("s")) "were" else "was"

    /**
     * What happened between [prev] (the version this phone saw last) and [now] (made in another app, or on another device
     * with your box), however many edits that took. Null when nothing at all differs. Entries rewritten in their details
     * (a relay hint dropped) are not counted as added or removed, but most of them rewritten is said, as is a private part
     * emptied, most of a list removed or a profile wiped (the same lines [hold] draws).
     */
    fun outside(prev: Event, now: Event): Outside? {
        if (prev.tags == now.tags && prev.content == now.content) return null
        val name = kindName(now.kind)
        val title = "Your $name ${verb(name)} changed in another app or device"
        if (now.kind == 0) {
            val gone = wiped(prev, now) ?: return Outside("Profile changed", null, title)
            val what = "your profile was wiped (${gone.joinToString(", ").ifEmpty { "everything" }})"
            return Outside("Profile: $what", upper(what) + ".", title)
        }
        val alarms = mutableListOf<String>()
        if (emptied(prev, now)) alarms += "your private $name ${verb(name)} emptied"
        val old = entries(prev); val new = entries(now)
        val before = old.map { it.first }; val after = new.map { it.first }
        val had = before.toSet(); val kept = after.toSet()
        val removed = before.count { it !in kept }; val added = after.count { it !in had }
        val big = (after.isEmpty() && before.size >= 3) ||
            (if (now.kind in RELAYISH) removed >= 2 && removed >= before.size * 0.5 else removed >= 10 && removed >= before.size * 0.2)
        if (big) alarms += if (after.isEmpty()) "all ${plural(before.size, now.kind)} removed"
            else "$removed of ${before.size} ${itemName(now.kind)}s removed" + (if (added > 0) ", $added added" else "")
        // Still there, but not as it was: a client that drops what it doesn't know (hints, petnames) can do this to every entry.
        val a = old.reversed().toMap(); val b = new.reversed().toMap()
        val rewritten = a.count { (k, t) -> b[k]?.let { it != t } ?: false }
        val stayed = a.keys.count { it in b }
        if (rewritten >= 3 && rewritten * 2 > stayed) alarms += "$rewritten of ${before.size} entries rewritten"
        val counts = if (big) emptyList() else listOfNotNull("$added added".takeIf { added > 0 }, "$removed removed".takeIf { removed > 0 })
        val private = { c: String -> c.isNotBlank() && !c.trimStart().startsWith("{") }
        val parts = alarms + counts + listOfNotNull("your private $name changed".takeIf { alarms.isEmpty() && counts.isEmpty() && private(prev.content) && private(now.content) && prev.content != now.content })
        val text = if (parts.isEmpty()) "${upper(name)} changed" else "${upper(name)}: ${parts.joinToString(", ")}"
        return Outside(text, alarms.takeIf { it.isNotEmpty() }?.joinToString(" ") { upper(it) + "." }, title)
    }
}
