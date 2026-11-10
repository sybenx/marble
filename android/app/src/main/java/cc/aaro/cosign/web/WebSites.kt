package cc.aaro.cosign.web

import java.net.URI

/**
 * The web apps on Marble's left pane, one chip each (marble-web-pane-plan M1). Two, hard-coded: Marble Web and Jumble.
 * Adding and removing sites is M3's site sheet.
 *
 * Each site is exactly one origin (https, host, port): its WebView stays on it, and anything a page does is named by
 * the WebView's origin, never by what the page says it is.
 */
data class WebSite(val id: String, val name: String, val start: String, /** It reads your private mute list right after sign-in. */ val asksLists: Boolean = false) {
    val origin: String = WebRules.originOf(start)!!
    /** The origin as people read it: its host (and port, if it has one). */
    val label: String get() = WebRules.label(origin)
}

object WebSites {
    val marble = WebSite("marble", "Marble", "https://marble.syb.lol/")
    // jumble.social for M1 (decision 3); self-hosted jumble.syb.lol at the start of M2.
    val jumble = WebSite("jumble", "Jumble", "https://jumble.social/", asksLists = true)
    val all = listOf(marble, jumble)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byOrigin(origin: String?) = origin?.let { o -> all.firstOrNull { it.origin == o } }
}

/** What a navigation in a site's WebView does. */
sealed class Nav {
    /** Same origin: it loads in the pane. */
    data object Stay : Nav()
    /** Another site on the pane: its chip opens, at [url]. */
    data class OtherSite(val site: WebSite, val url: String) : Nav()
    /** Off the pane's sites, after a tap: a Custom Tab. */
    data class Outside(val url: String) : Nav()
    /** Off the pane's sites, with no tap (a page moving itself on): not loaded; the strip offers to open it. */
    data class Blocked(val url: String) : Nav()
    /** A nostrconnect sign-in code from a tap in the page itself: the sign-in prompt, named after the WebView's origin. */
    data class SignIn(val uri: String) : Nav()
    /** Anything else (another scheme, a subframe's sign-in code, a scripted one): nothing happens. */
    data object Drop : Nav()
}

object WebRules {
    /** "https://host[:port]" for an http(s) [url]; null for anything else. Default ports are left out. */
    fun originOf(url: String): String? {
        val u = try { URI(url) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = u.port.takeIf { it > 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    fun label(origin: String) = origin.substringAfter("://")

    /** Hosts that always go to a Custom Tab, even when a site's own page sends you there (sign-in with Google). */
    private val alwaysOutside = setOf("accounts.google.com")

    /**
     * Where a navigation in [site]'s WebView goes. [mainFrame] and [gesture] come from WebView's own request
     * (`isForMainFrame`, `hasGesture`); nothing the page says can set them.
     */
    fun decide(site: WebSite, url: String, mainFrame: Boolean, gesture: Boolean): Nav {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            // Only a sign-in code, only from a tap in the page itself. A parsed intent: is never started.
            val code = nostrConnectIn(url) ?: return Nav.Drop
            return if (mainFrame && gesture) Nav.SignIn(code) else Nav.Drop
        }
        val origin = originOf(url) ?: return Nav.Drop
        // A frame inside the page (a video, an embed) loads where it likes: it can't sign, and it isn't the page.
        if (!mainFrame) return Nav.Stay
        val host = label(origin).substringBefore(':')
        if (host in alwaysOutside) return if (gesture) Nav.Outside(url) else Nav.Blocked(url)
        if (origin == site.origin) return Nav.Stay
        WebSites.byOrigin(origin)?.let { return Nav.OtherSite(it, url) }
        return if (gesture) Nav.Outside(url) else Nav.Blocked(url)
    }

    /**
     * The nostrconnect:// code in [url]: one itself, or Marble Web's `intent://…#Intent;scheme=nostrconnect;…;end`
     * (site/js/act.js phoneLink). Nothing else is read out of an intent: link (no package, component, action or
     * fallback). Null for anything that isn't a complete-looking code.
     */
    fun nostrConnectIn(url: String): String? {
        val t = url.trim()
        val code = when {
            t.startsWith("nostrconnect://", ignoreCase = true) -> "nostrconnect://" + t.substring("nostrconnect://".length)
            t.startsWith("nostrconnect:", ignoreCase = true) -> "nostrconnect://" + t.substring("nostrconnect:".length)
            t.startsWith("intent://", ignoreCase = true) -> {
                val hash = t.indexOf("#Intent;")
                if (hash < 0 || !t.endsWith(";end")) return null
                val extras = t.substring(hash + "#Intent;".length, t.length - ";end".length).split(';')
                // Exactly one scheme, nostrconnect, and nothing that names an app to open.
                val schemes = extras.filter { it.startsWith("scheme=") }
                if (schemes != listOf("scheme=nostrconnect")) return null
                if (extras.any { it.startsWith("package=") || it.startsWith("component=") || it.startsWith("action=") || it.startsWith("selector") || it.startsWith("SEL;") }) return null
                "nostrconnect://" + t.substring("intent://".length, hash)
            }
            else -> return null
        }
        val client = code.removePrefix("nostrconnect://").substringBefore('?').substringBefore('/').lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(client)) return null
        if ('?' !in code) return null
        return code
    }

    /**
     * The code as it goes to your account: the site's own name and address taken out (a page can say it's anyone) and
     * the WebView's verified [origin] put in, so the sign-in is named after the origin the code came from.
     */
    fun namedFor(code: String, origin: String): String {
        val base = code.substringBefore('?')
        val query = code.substringAfter('?', "").substringBefore('#')
        val kept = query.split('&').filter { it.isNotEmpty() }.filter { p ->
            val k = p.substringBefore('=').lowercase()
            k != "name" && k != "url" && k != "image"
        }
        return base + "?" + (kept + ("url=" + java.net.URLEncoder.encode(origin, "UTF-8"))).joinToString("&")
    }

    /** The site key in a code (the nostrconnect:// host): one sign-in per code. */
    fun clientOf(code: String) = code.removePrefix("nostrconnect://").substringBefore('?').lowercase()
}
