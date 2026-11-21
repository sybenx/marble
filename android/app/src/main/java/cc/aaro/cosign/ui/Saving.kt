package cc.aaro.cosign.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saving a picture or a video to the phone, from a post (the full-screen viewers) or from a site in the web pane
 * (a long-press in Jumble). Pictures go to Pictures/Marble and videos to Movies/Marble, where the gallery shows them.
 * Anything else is not saved.
 */
object Saving {
    enum class Kind(val word: String) { Picture("picture"), Video("video") }
    data class Named(val name: String, val type: String, val kind: Kind)

    private val TYPES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "avif" to "image/avif", "heic" to "image/heic", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mkv" to "video/x-matroska",
    )
    private fun extFor(type: String) = TYPES.entries.firstOrNull { it.value == type }?.key ?: type.substringAfter('/').substringBefore('+')

    /** What [url] is saved as, from its name and the type it was served as; null when it's neither a picture nor a video. */
    fun name(url: String, served: String?, hint: Kind? = null): Named? {
        val raw = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { try { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { it } }.ifEmpty { "marble" }
        val ext = raw.substringAfterLast('.', "").lowercase()
        val byName = TYPES[ext]
        val byServer = served?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
        // What the server says wins over the name (a Blossom address ends in its hash, often with no extension or the wrong one).
        val type = byServer ?: byName ?: when (hint) { Kind.Picture -> "image/jpeg"; Kind.Video -> "video/mp4"; null -> return null }
        val kind = if (type.startsWith("video/")) Kind.Video else Kind.Picture
        val base = (if (byName != null) raw.substringBeforeLast('.') else raw).replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "marble" }
        return Named("$base.${extFor(type)}", type, kind)
    }

    /** Saves [url] (http(s), or a data: picture) and says so. [hint]: what the page or post says it is. */
    suspend fun save(context: Context, url: String, hint: Kind? = null) {
        val said = try {
            val n = withContext(Dispatchers.IO) { if (url.startsWith("data:")) fromData(context, url) else fromWeb(context, url, hint) }
            "Saved the ${n.kind.word} to ${folder(n.kind)}"
        } catch (e: NotMedia) {
            "Marble only saves pictures and videos."
        } catch (e: Exception) {
            "Couldn't save it: ${e.message ?: "something went wrong"}"
        }
        Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
    }

    private class NotMedia : Exception()
    private fun folder(k: Kind) = when (k) { Kind.Picture -> Environment.DIRECTORY_PICTURES; Kind.Video -> Environment.DIRECTORY_MOVIES } + "/Marble"

    private fun fromWeb(context: Context, url: String, hint: Kind?): Named {
        val u = url.toHttpUrlOrNull() ?: error("that isn't a web address")
        Fetch.http.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) error("it answered ${r.code}")
            val n = name(url, r.header("Content-Type"), hint) ?: throw NotMedia()
            write(context, n) { out -> r.body!!.byteStream().use { it.copyTo(out) } }
            return n
        }
    }

    private fun fromData(context: Context, url: String): Named {
        val head = url.substringAfter("data:").substringBefore(',')
        val type = head.substringBefore(';').lowercase()
        if (!type.startsWith("image/") && !type.startsWith("video/")) throw NotMedia()
        if (!head.endsWith(";base64")) error("it isn't stored as base64")
        val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
        val n = Named("marble-${System.currentTimeMillis()}.${extFor(type)}", type, if (type.startsWith("video/")) Kind.Video else Kind.Picture)
        write(context, n) { it.write(bytes) }
        return n
    }

    private fun write(context: Context, n: Named, body: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, n.name); put(MediaStore.MediaColumns.MIME_TYPE, n.type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder(n.kind)); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val where = when (n.kind) {
            Kind.Picture -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = context.contentResolver.insert(where, values) ?: error("no room")
        try {
            context.contentResolver.openOutputStream(uri)?.use(body) ?: error("can't write")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null) // no half-written file left in the gallery
            throw e
        }
    }

    private fun String.toHttpUrlOrNull() = with(okhttp3.HttpUrl.Companion) { this@toHttpUrlOrNull.toHttpUrlOrNull() }?.takeIf { it.scheme == "https" || it.scheme == "http" }
}
