package cc.aaro.cosign.core

import cc.aaro.qrst.core.Event

/**
 * The words of asking you before something hard to undo is signed (Keykin SPEC 11.6, The tap): the question, the line
 * under it, the fingerprint prompt's lines, and which buttons a notification may carry. Level 1 is Yes or No; level 2
 * is never done by accident, and its Yes goes on to the fingerprint.
 *
 * Android lets an app open a screen only while it is on screen itself, so a Yes given in a notification can never
 * bring up the fingerprint prompt. A level 2 notification therefore has no Yes: tapping it opens the question in the
 * app, and the prompt follows from there.
 */
object Asking {
    /** What a notification for one question carries, besides opening the question when tapped. */
    enum class Action { DENY, APPROVE }

    /** Level 1: Deny and Approve. Level 2: Deny only; the Yes is given in the app, where the fingerprint can follow. */
    fun actions(level: Int): List<Action> = if (level >= 2) listOf(Action.DENY) else listOf(Action.DENY, Action.APPROVE)

    /** The fingerprint prompt has no second line, for an account change or a signature: its title says what it is for. */
    const val ACCOUNT_CHANGE = ""
    const val SIGNATURE = ""
    /** A fingerprint that could not come up by itself (the app was not on screen): the notification that opens it. */
    const val FINISH = "Tap to finish."

    // The words under a question say who asked, and stop. They do not explain how asking works
    // (Aaron, 2026-11-20: let the app work, do not teach how it works).

    /** The line under the question on a card or a sheet. */
    @Suppress("UNUSED_PARAMETER")
    fun cardLine(app: String, level: Int): String = "$app asked."

    /** A notification's short line and its long one, [appName] being this app's name. Level 2 has no Approve, so it says where to answer. */
    fun noticeLines(app: String, level: Int, appName: String): Pair<String, String> =
        if (level >= 2) "$app asked. Tap to answer in $appName." to "$app asked. Tap to answer in $appName."
        else "$app asked" to "$app asked"

    /** The line in the app's own Yes or No dialog, for something you did in the app itself: none. The question is enough. */
    @Suppress("UNUSED_PARAMETER")
    fun ownLine(level: Int): String = ""

    /**
     * What a box from before it gave its reasons asks about, said from the event itself. Such a box asked only about a
     * request to vanish, deleting five or more events, an empty follow list and an empty profile.
     */
    fun unsaid(e: Event): String = when (e.kind) {
        62 -> "Ask relays to erase everything you have ever published"
        5 -> e.tags.count { it.isNotEmpty() && (it[0] == "e" || it[0] == "a") }.let { n -> if (n > 0) "Delete $n of your events" else "Delete some of your events" }
        3 -> "Empty your follow list"
        0 -> "Wipe your profile: everything"
        else -> Policy.nameOf(e.kind)?.let { "Sign this $it" } ?: "Sign this"
    }

    /**
     * What needs you, as a question about what would happen: "Remove 212 of your 230 follows" becomes "Unfollow 212
     * people?". The words come from the policy (here and on your box).
     */
    fun question(what: String): String {
        val s = what.trim().removeSuffix(".").removeSuffix("?")
        fun people(n: String) = if (n == "1") "1 person" else "$n people"
        Regex("^Remove (\\d+) of your \\d+ follows?(?: \\(and add (\\d+)\\))?$").matchEntire(s)?.let {
            val add = it.groupValues[2]
            return "Unfollow ${people(it.groupValues[1])}" + (if (add.isEmpty()) "" else " and follow ${people(add)} instead") + "?"
        }
        Regex("^Empty your follow list \\(it has (\\d+) follows?\\)$").matchEntire(s)?.let { return "Unfollow all ${people(it.groupValues[1])}?" }
        Regex("^Empty your (.+) \\(it has (\\d+) (.+)\\)$").matchEntire(s)?.let { return "Remove all ${it.groupValues[2]} ${it.groupValues[3].replace("persons", "people")} from your ${it.groupValues[1]}?" }
        Regex("^(.+) dated in the future \\((.+)\\)$").matchEntire(s)?.let { return "Allow a ${it.groupValues[1].lowercase()} dated ${it.groupValues[2]}?" }
        Regex("^Delete (\\d+) (?:of your )?events?(?: for .*)?$").matchEntire(s)?.let { val n = it.groupValues[1]; return "Delete $n post${if (n == "1") "" else "s"}?" }
        Regex("^Delete (?:several|some) (?:of your )?events?(?: for .*)?$").matchEntire(s)?.let { return "Delete several posts?" }
        Regex("^Wipe your profile: (.+)$").matchEntire(s)?.let { return "Clear your profile (${it.groupValues[1]})?" }
        if (s.startsWith("Ask relays to erase everything")) return "Erase everything you've ever posted?"
        return "$s?"
    }
}
