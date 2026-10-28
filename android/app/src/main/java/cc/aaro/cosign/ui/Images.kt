package cc.aaro.cosign.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import cc.aaro.cosign.CosignApp
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.dp

/** Pictures in notes and profiles: fetched over https, cached on disk by OkHttp, scaled down to what's shown. */
object Images {
    private val http by lazy {
        Fetch.http.newBuilder().cache(Cache(File(CosignApp.instance.cacheDir, "images"), 40L * 1024 * 1024))
            .callTimeout(15, TimeUnit.SECONDS).build()
    }
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(4)
    private const val MAX_BYTES = 12L * 1024 * 1024

    fun cached(url: String, px: Int): ImageBitmap? = memory.get("$px $url")

    suspend fun load(url: String, px: Int): ImageBitmap? {
        val key = "$px $url"
        memory.get(key)?.let { return it }
        if (!url.startsWith("https://") || synchronized(failed) { url in failed }) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        val body = r.body ?: return@use null
                        if (!r.isSuccessful || body.contentLength() > MAX_BYTES) null else body.source().let { s -> s.request(MAX_BYTES + 1); if (s.buffer.size > MAX_BYTES) null else s.readByteArray() }
                    } ?: error("no image")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= px) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("not an image")
                    bmp.asImageBitmap().also { memory.put(key, it) }
                } catch (_: Exception) { synchronized(failed) { failed += url }; null }
            }
        }
    }
}

/**
 * A moving picture (an animated GIF or WebP): decoded whole by the platform (ImageDecoder plays it; BitmapFactory would
 * keep only its first frame, which for a drawing being drawn is a blank page). Null if it can't be had or decoded.
 */
suspend fun Images.animated(url: String): android.graphics.drawable.Drawable? {
    if (!url.startsWith("https://")) return null
    return withContext(Dispatchers.IO) {
        try {
            val bytes = Fetch.http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return@withContext null
            if (bytes.size > 12 * 1024 * 1024) return@withContext null
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        } catch (_: Exception) { null }
    }
}

/**
 * A picture in a post: animated if it's a GIF or WebP, else as [NetImage]. One that can't be shown falls back to the
 * post's own preview of it ([preview]), and failing that to a quiet "Open" (in your browser), never an empty box.
 */
@Composable
fun PostImage(url: String, preview: String?, modifier: Modifier, onTap: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val moving = remember(url) { Regex("\\.(gif|webp)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url) }
    if (moving) {
        // null: still loading; Unit: couldn't; a Drawable: playing.
        val got by androidx.compose.runtime.produceState<Any?>(null, url) { value = Images.animated(url) ?: Unit }
        when (val d = got) {
            is android.graphics.drawable.Drawable -> androidx.compose.ui.viewinterop.AndroidView({ c ->
                android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start() }
            }, modifier.clickable(onClick = onTap), update = { it.setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start() })
            Unit -> if (preview != null) NetImage(preview, modifier.clickable(onClick = onTap), 1080, placeholder = { OpenLink(url, context) }) else Box(modifier.background(Vault.Surface)) { OpenLink(url, context) }
            else -> Box(modifier.background(Vault.Surface))
        }
        return
    }
    NetImage(url, modifier.clickable(onClick = onTap), 1080, description = "Picture", placeholder = { OpenLink(url, context, after = 5000) })
}

/** "Open ›" in the middle of a picture's place, quietly (after [after] ms, so not while it's still on its way). */
@Composable
private fun OpenLink(url: String, context: android.content.Context, after: Long = 0) {
    var shown by remember(url) { mutableStateOf(after == 0L) }
    if (after > 0) androidx.compose.runtime.LaunchedEffect(url) { kotlinx.coroutines.delay(after); shown = true }
    if (!shown) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text("Open ›", color = Vault.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {} }.padding(12.dp))
    }
}

/** A picture from the web, or [placeholder] until (or unless) it arrives. */
@Composable
fun NetImage(url: String?, modifier: Modifier, px: Int, scale: ContentScale = ContentScale.Crop, description: String? = null,
             ground: androidx.compose.ui.graphics.Color = Vault.Surface, placeholder: @Composable () -> Unit = {}) {
    var img by remember(url, px) { mutableStateOf(url?.let { Images.cached(it, px) }) }
    LaunchedEffect(url, px) { if (img == null && url != null) img = Images.load(url, px) }
    val shown = img
    if (shown != null) Image(shown, description, modifier, contentScale = scale)
    else Box(modifier.background(ground)) { placeholder() }
}
