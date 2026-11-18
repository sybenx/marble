package cc.aaro.cosign

import cc.aaro.keykin.core.SiteSigner
import cc.aaro.qrst.core.PairingUri

/**
 * Which account a site signs in with (Aaron, 2026-11-17, an experiment): a site this phone has not signed
 * in to before gets a new account of its own by default, the child of this account whose label is the
 * site's web origin (`https://host`, lower-case, no trailing slash), so every device makes the same one.
 * The person may pick the main account instead. The choice is kept in the site's record and used for every
 * later sign-in from that site, with no question. Keykin account pages (any address, whoever runs them: their
 * code or link carries `kk=account`, Keykin SPEC 11.6) and Marble's own site are always the main account.
 */
object SiteChoice {
    /** Marble's own site: it is about the main account and breaks with another. */
    val ALWAYS_MAIN = setOf("https://marble.syb.lol")

    sealed class Decision {
        /** Ask: the main button makes a new account for [place], the small one uses the main account. */
        data class Ask(val place: String) : Decision()
        /** No question: the main account. */
        data object Main : Decision()
        /** A Keykin account page (marked `kk=account`): the main account only, and the card says why. */
        data object AccountPage : Decision()
        /** No question: the account this site already has ([place]'s). */
        data class Own(val place: String) : Decision()
    }

    /** A site's web origin as a child's label, or null when there is no web address to go by. */
    fun origin(raw: String?): String? = raw?.let { PairingUri.normalizeOrigin(it) }

    /** The same site, however its record names it (a sign-in by nostrconnect keeps only the host). */
    private fun same(a: String?, b: String) = a != null && host(a) == host(b)
    private fun host(o: String) = o.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * Whether a sign-in code or link is marked as a Keykin account page's (`kk=account` among its query or
     * fragment parameters): a scanned or pasted code, the same as a `qrst://` link, or a `nostrconnect://` link.
     */
    fun marked(code: String): Boolean {
        val parts = code.substringAfter('?', "").substringBefore('#').split('&') + code.substringAfter('#', "").split('&')
        return parts.any { it == "kk=account" }
    }

    /**
     * What a new sign-in from [origin] (a web origin, or null) gets. [sites] are this account's signed-in
     * sites; [accountPage]: the code was marked as a Keykin account page's ([marked]).
     */
    fun decide(origin: String?, sites: List<SiteSigner.Site>, accountPage: Boolean = false): Decision {
        if (accountPage) return Decision.AccountPage
        val o = origin(origin) ?: return Decision.Main
        if (o in ALWAYS_MAIN) return Decision.Main
        // Signed in before (and still on the list): what it got then. A site signed in before this existed stays on the main account.
        sites.firstOrNull { same(it.origin, o) }?.let { s -> return s.place?.let { Decision.Own(it) } ?: Decision.Main }
        return Decision.Ask(o)
    }
}
