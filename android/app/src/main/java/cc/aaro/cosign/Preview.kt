package cc.aaro.cosign

import android.content.Context
import cc.aaro.qrst.core.Json
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** HTML's character references in text a page or feed gave us: "&#x20AC;" is "€", "&amp;" is "&" (twice over, if escaped twice). */
object HtmlText {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "euro" to "€", "pound" to "£", "yen" to "¥",
        "cent" to "¢", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "eacute" to "é",
        "egrave" to "è", "aacute" to "á", "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "ccedil" to "ç",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß",
    )
    private val REF = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    fun decode(s: String): String {
        var out = s
        repeat(2) {
            if ('&' !in out) return out
            out = REF.replace(out) { m ->
                val r = m.groupValues[1]
                when {
                    r.startsWith("#x") || r.startsWith("#X") -> r.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }
                    r.startsWith("#") -> r.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }
                    else -> NAMED[r] ?: NAMED[r.lowercase()]
                } ?: m.value
            }
        }
        return out
    }
}

/**
 * A link's preview, as a post shows it: the page's title, picture and a line of summary, from its own
 * OpenGraph tags (what any messenger shows). Fetched once per link, from this phone, and kept.
 */
data class Preview(val title: String, val image: String?, val summary: String, val site: String) {
    companion object {
        private val http by lazy { Fetch.http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

        /** The preview for [url], or null when the page has none (or isn't a page). */
        suspend fun fetch(url: String): Preview? = withContext(Dispatchers.IO) {
            try {
                http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (link preview)").build()).execute().use { r ->
                    if (!r.isSuccessful || r.body?.contentType()?.subtype?.contains("html") != true) return@use null
                    val html = r.body!!.source().let { src -> src.request(300_000); src.buffer.clone().readUtf8() }
                    of(html, r.request.url.toString())
                }
            } catch (_: Exception) { null }
        }

        /** The preview in a page's own tags. */
        fun of(html: String, url: String): Preview? {
            fun meta(k: String) = Regex("<meta[^>]+(?:property|name)=[\"']" + Regex.escape(k) + "[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value
                ?.let { Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            fun dec(s: String?) = s?.let { HtmlText.decode(it) }?.trim()
            val title = dec(meta("og:title") ?: meta("twitter:title") ?: Regex("<title[^>]*>([^<]*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))?.takeIf { it.isNotEmpty() } ?: return null
            val image = dec(meta("og:image") ?: meta("twitter:image"))?.let { i -> try { java.net.URI(url).resolve(i).toString() } catch (_: Exception) { null } }?.takeIf { it.startsWith("https://") }
            val site = try { java.net.URI(url).host.removePrefix("www.") } catch (_: Exception) { "" }
            return Preview(title.take(200), image, dec(meta("og:description") ?: meta("description"))?.take(220) ?: "", site)
        }

        private fun file(context: Context) = File(context.cacheDir, "previews.json")
        fun load(context: Context): Map<String, Preview> = try {
            (Json.parse(file(context).readText()) as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                // (Kept before references were all decoded: decoded now.)
                (k as String) to Preview(HtmlText.decode(m["title"] as? String ?: return@mapNotNull null), m["image"] as? String, HtmlText.decode(m["summary"] as? String ?: ""), m["site"] as? String ?: "")
            }?.toMap() ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        fun save(context: Context, all: Map<String, Preview>) = try {
            file(context).writeText(Json.stringify(all.entries.toList().takeLast(500).associate { (k, p) -> k to mapOf("title" to p.title, "image" to p.image, "summary" to p.summary, "site" to p.site) }))
        } catch (_: Exception) {}
    }
}
