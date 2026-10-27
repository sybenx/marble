package cc.aaro.cosign

// "Go to example.com" at the top of search: only for a typed address. A bare name ("apple") searches; going to
// apple.com has to be typed. Nothing is looked up or sent while you type.

object Sites {
    private val ADDRESS = Regex("^(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?:[/:?#]\\S*)?$", RegexOption.IGNORE_CASE)

    /** The site to go to for what's typed (Enter opens it): only an address, a dot and an ending, or a scheme. */
    data class Go(val address: String, val url: String)

    fun match(typed: String): Go? {
        val q = typed.trim()
        if (q.isEmpty() || q.contains(' ')) return null
        // An address: lemmy.world, example.com/page.
        ADDRESS.matchEntire(q)?.let { m ->
            val host = m.groupValues[1].lowercase()
            if (host.substringAfterLast('.') in NOT_TLDS) return null
            val url = if (q.startsWith("http://", true) || q.startsWith("https://", true)) q else "https://$q"
            return Go(host.removePrefix("www."), url)
        }
        return null
    }

    /** Endings that look like an address but aren't one in a search ("node.js", "file.txt"). */
    private val NOT_TLDS = setOf("js", "ts", "kt", "py", "rs", "txt", "md", "json", "png", "jpg", "jpeg", "gif", "pdf", "mp4", "mp3", "exe", "apk", "zip", "html", "css")
}
