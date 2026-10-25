package cc.aaro.cosign

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Links Marble has acted on (a pairing code, a server's code, a sign-in code), so none is acted on
 * twice. Android hands an activity back the link that first opened its task whenever it recreates
 * it: after an update, say, or when the system freed it while you were away. That link is old news,
 * not something you just scanned. Kept as hashes (sign-in links carry secrets), the newest 200.
 */
class HandledLinks(private val prefs: SharedPreferences) {
    private fun hash(link: String) = MessageDigest.getInstance("SHA-256").digest(link.trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /**
     * Whether to act on [link]. [fresh]: it just arrived (a new scan or tap while Marble was open), so it's
     * acted on even if seen before (scanning the same code again after a failed try). Otherwise (the link
     * the activity was created with) only if it's never been handled.
     */
    @Synchronized fun take(link: String, fresh: Boolean): Boolean {
        val h = hash(link)
        val seen = prefs.getString("handled", "")!!.split(',').filter { it.isNotEmpty() }
        if (!fresh && h in seen) return false
        prefs.edit().putString("handled", (seen.filter { it != h } + h).takeLast(200).joinToString(",")).apply()
        return true
    }
}
