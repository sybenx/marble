package cc.aaro.cosign.core

/**
 * What a signed-in site may do with your key (0.15.1). Three switches, set at sign-in and in its sheet:
 *  - "Sign in and post as you": always on. Any event but a DM, signed as before (anything destructive still asks).
 *  - "Read your private lists" ([lists]): decrypt and encrypt with your own key, which is what private lists use.
 *  - "Read and send DMs" ([dms]): decrypt and encrypt with anyone, and sign DMs (kinds 4, 13, 14, 15, gift wraps).
 * Anything else is refused outright, never asked.
 */
object SiteScopes {
    /** Direct messages: NIP-04 (4), NIP-17's seal (13), chat message (14) and file message (15), and gift wraps (1059). */
    val DM_KINDS = setOf(4, 13, 14, 15, 1059)

    /**
     * Whether a site may run [method] with [params], acting as [me] (the account it signs in as: yours, or its own).
     * [kind] is the event's kind, for `sign_event`. Null: allowed. Otherwise the scope it lacks: "lists" or "dms".
     */
    fun lacks(method: String, params: List<String>, me: String, lists: Boolean, dms: Boolean, kind: Int? = null): String? = when (method) {
        "sign_event" -> if (kind in DM_KINDS && !dms) "dms" else null
        "nip44_encrypt", "nip44_decrypt", "nip04_encrypt", "nip04_decrypt" -> {
            val peer = params.getOrNull(0)?.lowercase()
            when {
                dms -> null
                peer == me.lowercase() -> if (lists) null else "lists"
                else -> "dms"
            }
        }
        else -> null
    }

    /** What a site's request is refused with, when it lacks a scope. */
    fun refusal(lacking: String) = if (lacking == "dms") "not_allowed: DMs are off for this site" else "not_allowed: private lists are off for this site"
}
