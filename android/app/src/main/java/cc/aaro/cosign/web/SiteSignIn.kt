package cc.aaro.cosign.web

import cc.aaro.cosign.Connect
import cc.aaro.cosign.Phone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The account signing a web pane's site in (marble-web-pane-plan M1: "Account.connectSite"). Main has no Account
 * interface yet (it's on the keykin branch, Account.kt); when keykin merges, Account takes this on and KeykinAccount
 * implements it with `nostrconnect(slot, uri, "sign-in,post")`.
 */
interface SiteAccount {
    /** Sign [origin] (the WebView's verified origin) in with the nostrconnect [code] its page showed. [pick] "new":
     *  a site new to this account gets an account of its own; otherwise the main account (SiteChoice). */
    fun connectSite(origin: String, code: String, pick: String? = null, lists: Boolean = false, dms: Boolean = false)
}

/** Where a sign-in from the pane stands, for the screens: the site whose Cosign confirm is skipped, and the follow-up. */
object WebSignIn {
    /** A site being signed in from the pane: Cosign's own "Use your account on …?" is skipped for it (you just said yes). */
    val confirming = MutableStateFlow<String?>(null)

    /** Cosign's connect sheet stays hidden while it only asks again what SitePrompt asked. */
    fun hides(c: Connect) = c.stage == Connect.Stage.CONFIRM && c.origin == confirming.value

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * A Cosign key signing a site in: the same nostrconnect connect as scanning the site's code (Phone.scanned, then
 * confirmConnect), called here in the main process, so no intent goes to MainActivity (whose sign-in handling sends
 * Marble to the back, MainActivity's LaunchedEffect on ui.connect).
 */
class CosignSiteAccount(private val phone: Phone) : SiteAccount {
    /** [lists], [dms]: the prompt's switches ("Read your private lists", "Read and send DMs"), kept with the sign-in. */
    override fun connectSite(origin: String, code: String, pick: String?, lists: Boolean, dms: Boolean) {
        if (!phone.isMember) return phone.showError("Set up your account before signing in to a site.")
        val label = WebRules.label(origin)
        val client = WebRules.clientOf(code)
        // One live pane sign-in per origin: only a sign-in this pane made on this phone, for this account, ends now (the
        // page asked again, so it lost it). Nothing else is touched: not a sign-in by code made anywhere else, not the
        // extension's, not an app's (PaneGrants keeps the record).
        val grants = PaneGrants.of(cc.aaro.cosign.CosignApp.instance)
        for (old in grants.toReplace(phone.slot, label, phone.ui.value.sites, client)) { phone.removeSite(old); grants.forget(phone.slot, label, old.pk) }
        val errorBefore = phone.ui.value.error
        WebSignIn.confirming.value = label
        phone.scanned(WebRules.namedFor(code, origin))
        WebSignIn.scope.launch {
            try {
                // The signer reads the code and asks; you already said yes in SitePrompt, named after the same origin.
                val asked = withTimeoutOrNull(10_000) {
                    phone.ui.first { u -> (u.connect?.stage == Connect.Stage.CONFIRM && u.connect?.origin == label) || (u.error != null && u.error != errorBefore) }
                }
                if (asked?.connect?.stage != Connect.Stage.CONFIRM) return@launch
                phone.setConnectScopes(lists, dms)
                phone.confirmConnect(pick)
                // From here the servers may take the site on, whatever this phone hears back: it's the pane's.
                grants.record(phone.slot, label, client)
                // Hidden until the signer has moved on from asking (to "Telling your servers…"), so it never flashes.
                withTimeoutOrNull(5_000) { phone.ui.first { u -> u.connect?.stage != Connect.Stage.CONFIRM || u.connect?.origin != label } }
                WebSignIn.confirming.value = null
            } finally {
                if (WebSignIn.confirming.value == label) WebSignIn.confirming.value = null
            }
        }
    }

    companion object {
        private val made = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Phone, CosignSiteAccount>())
        fun of(phone: Phone): CosignSiteAccount = made.getOrPut(phone) { CosignSiteAccount(phone) }
    }
}

/**
 * The sign-ins the web pane made on this phone: per account (slot) and site, the site keys it connected. A new pane
 * sign-in for a site replaces only these, so a sign-in you made anywhere else (by code in a browser, on another phone,
 * the extension) is never ended by the pane. Kept on this phone only.
 */
class PaneGrants(private val get: (String) -> String?, private val put: (String, String?) -> Unit) {
    private fun key(slot: String, label: String) = "$slot|$label"
    /** The site keys the pane signed [label] in with, for this account (lower case). */
    fun of(slot: String, label: String) = read(slot, label)
    private fun read(slot: String, label: String) = get(key(slot, label))?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    @Synchronized fun record(slot: String, label: String, pk: String) {
        put(key(slot, label), (read(slot, label) + pk.lowercase()).joinToString(","))
    }

    @Synchronized fun forget(slot: String, label: String, pk: String) {
        val left = read(slot, label) - pk.lowercase()
        put(key(slot, label), left.takeIf { it.isNotEmpty() }?.joinToString(","))
    }

    /** The sign-ins a new pane sign-in for [label] with [client] ends: the pane's own earlier ones still listed. */
    @Synchronized fun toReplace(slot: String, label: String, sites: List<cc.aaro.cosign.Site>, client: String): List<cc.aaro.cosign.Site> {
        val mine = read(slot, label)
        return sites.filter { it.pk.lowercase() in mine && it.origin == label && it.parent == null && it.pk.lowercase() != client.lowercase() }
    }

    companion object {
        @Volatile private var made: PaneGrants? = null
        fun of(context: android.content.Context): PaneGrants = made ?: synchronized(this) {
            made ?: context.getSharedPreferences("web-pane-grants", android.content.Context.MODE_PRIVATE).let { p ->
                PaneGrants({ p.getString(it, null) }, { k, v -> p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
            }.also { made = it }
        }
    }
}
