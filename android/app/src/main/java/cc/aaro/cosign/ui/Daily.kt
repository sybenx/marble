package cc.aaro.cosign.ui

import android.content.Context
import cc.aaro.cosign.core.Json
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The night's own marble, from the nightly pipeline (sybenx.github.io/marble-daily): the entry for today's UTC date,
 * if it's there and not vetoed, kept on the phone once fetched. Until it comes (early in the day, no connection),
 * the collection's marble of the day stands in. Its [Marble.name] is the pipeline's; its id (marble-YYYY-MM-DD) is
 * what collecting it will refer to.
 */
object Daily {
    const val DEFAULT = "https://sybenx.github.io/marble-daily/manifest.json"
    /** Where the list of nights is; a debug build's test can point it elsewhere (--es daily <url>). */
    @Volatile var manifest = DEFAULT
    /** Asked again at most this often while today's hasn't come. */
    private const val AGAIN = 60 * 60_000L
    val today = MutableStateFlow<Marble?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { Fetch.http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    /** The key the pipeline sends its owner tomorrow's marble from (the manifest's preview_bot). */
    @Volatile var previewBot: String? = null
    @Volatile private var asked = 0L
    @Volatile private var askedFor = ""

    fun utcDate(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /**
     * Today's marble, looked at big: collected, on this phone for now (the days, as marble-YYYY-MM-DD ids, for the
     * shelf to come). True the first time on [date].
     */
    fun collect(context: Context, date: String): Boolean {
        val prefs = context.getSharedPreferences("marbles", Context.MODE_PRIVATE)
        val have = prefs.getStringSet("collected", emptySet())!!
        val id = "marble-$date"
        if (id in have) return false
        prefs.edit().putStringSet("collected", have + id).apply()
        return true
    }

    /** Today's from the phone at once; from the site if it isn't here yet (at most once an hour). */
    fun refresh(context: Context) = scope.launch {
        val date = utcDate()
        val dir = File(context.filesDir, "marbles").apply { mkdirs() }
        val png = File(dir, "$date.png"); val name = File(dir, "$date.name")
        val have = png.exists() && name.exists()
        today.value = if (have) Marble(name.readText(), 0, 384, png) else null
        if (askedFor == date && System.currentTimeMillis() - asked < AGAIN) return@launch
        asked = System.currentTimeMillis(); askedFor = date
        val m = Json.parse(get(manifest)?.decodeToString() ?: return@launch) as? Map<*, *> ?: return@launch
        (m["preview_bot"] as? String)?.takeIf { cc.aaro.cosign.core.isHex32(it) }?.let { previewBot = it }
        if (have) return@launch
        val days = m["days"] as? List<*> ?: return@launch
        val e = days.mapNotNull { it as? Map<*, *> }.lastOrNull { it["date"] == date } ?: return@launch
        if (e["status"] != "ok") return@launch
        val url = (e["image"] as? String)?.takeIf { it.startsWith("https://") || manifest != DEFAULT } ?: return@launch
        val bytes = get(url) ?: return@launch
        // Exactly what the pipeline made, or nothing.
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha != (e["sha256"] as? String)?.lowercase()) return@launch
        png.writeBytes(bytes); name.writeText((e["name"] as? String)?.trim()?.take(40)?.ifEmpty { null } ?: "Today's marble")
        // A week of them on the phone, no more.
        dir.listFiles()?.filter { it.name.take(10) < LocalDate.now(ZoneOffset.UTC).minusDays(7).toString() }?.forEach { it.delete() }
        if (utcDate() == date) today.value = Marble(name.readText(), 0, ((e["size"] as? Long)?.toInt() ?: 768) / 2, png)
    }

    fun get(url: String): ByteArray? = try {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (_: Exception) { null }
}
