package cc.aaro.cosign

/**
 * Every Keykin account always has a password (Keykin SPEC 11.3): asked at setup, changed later, never removed.
 * The rules here are plain so they can be tested without a phone.
 */
object AccountPassword {
    const val MIN = 10
    /** Shown until an account made before this rule has a password. */
    const val PROMPT = "Your account has no password yet. Set one so you can reach your account page from any browser."

    fun cleanUsername(s: String) = s.trim().lowercase()

    /** What is wrong with what was typed, in plain words, or null when it is fine. */
    fun problem(username: String, password: String, again: String): String? = when {
        !Regex("^[a-z0-9._-]{3,32}$").matches(cleanUsername(username)) -> "A username is 3 to 32 of a-z, 0-9, dot, dash and underscore."
        password.length < MIN -> "Use a password of at least $MIN characters."
        password != again -> "The two passwords do not match."
        else -> null
    }

    /** The username and password as one text for the signer process ("username\npassword"), and back. */
    fun pack(username: String, password: String) = cleanUsername(username) + "\n" + password
    fun unpack(text: String?): Pair<String, String>? {
        if (text == null || '\n' !in text) return null
        return cleanUsername(text.substringBefore('\n')) to text.substringAfter('\n')
    }

    /** Making or bringing in an account needs a username and password first; joining an existing one does not. */
    fun setupProblem(creating: Boolean, text: String?): String? {
        if (!creating) return null
        val (u, p) = unpack(text) ?: return "Choose a username and a password first."
        return problem(u, p, p)
    }

    /** Whether to show [PROMPT]: only when the box said this account has none (null: not known yet, or an older box). */
    fun needsPrompt(inAccount: Boolean, hasPassword: Boolean?) = inAccount && hasPassword == false
}
