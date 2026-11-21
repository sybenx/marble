package cc.aaro.cosign

import cc.aaro.cosign.web.WebRules
import cc.aaro.cosign.web.WebSite
import cc.aaro.cosign.web.WebSites

// What each row of the search box's list is, and where a tap on it goes. The row's second line starts with its kind
// ("Web app", "nsite", "Profile") in the text colour, then the detail; its right edge names where it opens.

/** Where a tap on a search row goes: Marble itself (the web pane, or a profile), the browser, or Zapstore's page. */
enum class Goes(val word: String) { Marble("Marble"), Browser("Browser"), Zapstore("Zapstore") }

object SearchRows {
    /** One row's words: its name, what it is, the detail after that, where a tap goes, and what TalkBack says a tap does. */
    data class Row(val title: String, val kind: String, val detail: String, val goes: Goes, val say: String)

    /**
     * Where openApp opens [a] on the web pane: the chip already on its origin (Marble Web, Jumble, one you kept), or, for a
     * web app or nsite at an https address, a visiting chip. Null: it opens in the browser (an Android app's Zapstore page,
     * an http address). The row's "Marble" or "Browser" comes from this same test.
     */
    fun paneSite(a: AppEntry): WebSite? = WebSites.byOrigin(WebRules.originOf(a.url))
        ?: WebSites.visiting(a.name, a.url)?.takeIf { a.kind == "web" || a.kind == "nsite" }

    /**
     * An app or nsite from the index. It opens in the web pane when [pane] (openApp's test, [paneSite]); otherwise an
     * Android listing opens its Zapstore page and anything else the browser. The about text isn't on the line: it's crowded already.
     */
    fun app(a: AppEntry, follows: Set<String> = emptySet(), pane: Boolean = paneSite(a) != null): Row {
        val (kind, detail) = when (a.kind) {
            "nsite" -> "nsite" to a.by?.let { "by $it" }
            "android" -> "Android app" to a.zapstore
            else -> "Web app" to a.domain
        }
        val goes = when {
            pane -> Goes.Marble
            a.kind == "android" -> Goes.Zapstore
            else -> Goes.Browser
        }
        val say = when (goes) {
            Goes.Zapstore -> "Open ${a.name} on Zapstore"
            Goes.Marble -> "Open ${a.name} in Marble"
            Goes.Browser -> "Open ${a.name}${if (a.kind == "nsite") " nsite" else ""} in your browser"
        }
        return Row(a.name, kind, listOfNotNull(detail, "You follow them".takeIf { a.pubkey in follows }).joinToString(" · "), goes, say)
    }

    /**
     * Where a "Go to" row's site opens on the web pane: the chip already on its origin (one you kept), or a visiting chip
     * for an https address, which you can then Keep. Null for http (a name on your network): the browser only. A visit
     * can't ask to sign in until you keep it, the same as an app from the index.
     */
    fun paneSite(go: Sites.Go): WebSite? = WebSites.byOrigin(WebRules.originOf(go.url)) ?: WebSites.visiting(go.address, go.url)

    /** "Go to" an address: the bare address, a website. In the browser, or in Marble when it's already a chip of yours ([chip]). */
    fun site(address: String, chip: Boolean = false) =
        if (chip) Row(address, "Website", "", Goes.Marble, "Open $address in Marble")
        else Row(address, "Website", "", Goes.Browser, "Open $address in your browser")

    /** A handle's page elsewhere: who (their name, or the handle), "Profile on Mastodon" and the handle, in the browser. */
    fun handle(on: String, name: String?, handle: String) =
        Row(name ?: handle, "Profile on $on", if (name != null) handle else "", Goes.Browser, "Open ${name ?: handle}'s profile on $on in your browser")

    /** Someone on Nostr: their profile, in Marble. [line] is what the row showed before (their address, followers). */
    fun person(name: String, line: String) = Row(name, "Profile", line, Goes.Marble, "Open $name's profile")
}
