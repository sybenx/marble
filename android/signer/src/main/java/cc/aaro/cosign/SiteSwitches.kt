package cc.aaro.cosign

import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.SiteSigner

/**
 * Changing a signed-in site's two switches (SiteScopes: "Read your private lists", "Read and send DMs"),
 * from its sheet on this phone or from the account page (Aaron, 2026-11-19). The box keeps what the page
 * chose and hands it over in its reply to this phone's `grants` report; this phone's next report carries
 * the new switches, which is how the box knows it is done.
 */
object SiteSwitches {
    /**
     * Sets [was]'s switches. Turning either off forgets the message keys worked out for it ([forget], with its
     * grant id), so it cannot keep reading with them. False: no such site any more.
     */
    fun set(sites: SiteSigner, was: SiteSigner.Site, lists: Boolean, dms: Boolean, forget: (String) -> Unit): Boolean {
        if (!sites.setScopes(was.key, lists, dms)) return false
        if ((was.dms && !dms) || (was.lists && !lists)) forget(was.grant)
        return true
    }

    /**
     * What the account page wants ([wants], by grant id), applied to the sites whose grant matches, each the same way
     * as its sheet does it, with a line in the history for each switch that changes. Unknown ids, and wants that are
     * already so, change nothing. Returns how many sites changed.
     */
    fun fromPage(sites: SiteSigner, wants: Map<String, Device.Want>, forget: (String) -> Unit, note: (String, String) -> Unit): Int {
        if (wants.isEmpty()) return 0
        var changed = 0
        for (s in sites.sites()) {
            val w = wants[s.grant] ?: continue
            val lists = w.lists ?: s.lists
            val dms = w.dms ?: s.dms
            if (lists == s.lists && dms == s.dms) continue
            if (!set(sites, s, lists, dms, forget)) continue
            changed++
            val name = s.origin ?: "a site"
            if (lists != s.lists) note("Private lists turned ${if (lists) "on" else "off"} for $name from your account page", if (lists) "approved" else "denied")
            if (dms != s.dms) note("DMs turned ${if (dms) "on" else "off"} for $name from your account page", if (dms) "approved" else "denied")
        }
        return changed
    }
}
