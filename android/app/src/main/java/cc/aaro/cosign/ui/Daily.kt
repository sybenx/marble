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
    /** Each night's name and picture, as the list last said (status ok only), by date: for your marbles. */
    val nights = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    @Volatile private var nightsRead = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { Fetch.http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    /** Where today's marble is claimed (the manifest can name another): its award, a badge, for the one who kept it. */
    @Volatile var issuer: String? = "https://marble-issuer.sybenx.workers.dev"
    /** The issuer's key (made when it was set up; its GET /today says it): whose badges are marbles, on profiles. */
    @Volatile var issuerKey: String? = null

    /** The issuer's key, asked of it once (GET /today). */
    fun issuerKey(): String? {
        issuerKey?.let { return it }
        val base = issuer ?: return null
        val o = try { org.json.JSONObject(get("$base/today")?.decodeToString() ?: return null) } catch (_: Exception) { return null }
        return o.optString("issuer").takeIf { cc.aaro.cosign.core.isHex32(it) }?.also { issuerKey = it }
    }
    /** The key the pipeline sends its owner tomorrow's marble from (the manifest's preview_bot). */
    @Volatile var previewBot: String? = null
    @Volatile private var asked = 0L

    /** Debug builds: the nights from a test list instead, asked at once (it can come after the first ask). */
    fun test(context: Context, url: String) { manifest = url; askedFor = ""; refresh(context) }
    @Volatile private var askedFor = ""

    fun utcDate(): String = LocalDate.now(ZoneOffset.UTC).toString()


    /** Today's from the phone at once; from the site if it isn't here yet (at most once an hour). */
    fun refresh(context: Context) = scope.launch {
        // Nothing here may take Marble down: at worst the collection's marble stands in.
        try { fetch(context) } catch (e: Exception) { debug(context, "failed: ${e.javaClass.simpleName} ${e.message}") }
    }

    private fun debug(context: Context, what: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("CosignDaily", what)
    }

    /**
     * A night's marble's real detail, as a radius in pixels: the generator draws it about 530 px across in its
     * render, and the 768 px cut is that, enlarged. The list's "detail" (the marble's own width, if it ever says) wins.
     */
    private const val NIGHT_DETAIL = 265

    private fun fetch(context: Context) {
        val date = utcDate()
        val dir = File(context.filesDir, "marbles").apply { mkdirs() }
        val png = File(dir, "$date.png"); val name = File(dir, "$date.name")
        val have = png.exists() && name.exists()
        today.value = if (have) Marble(name.readText(), 0, NIGHT_DETAIL, png) else null
        if (askedFor == date && System.currentTimeMillis() - asked < AGAIN) return
        asked = System.currentTimeMillis(); askedFor = date
        val text = get(manifest)?.decodeToString() ?: return debug(context, "no list from $manifest")
        val m = Json.parse(text) as? Map<*, *> ?: return debug(context, "list unreadable")
        (m["preview_bot"] as? String)?.takeIf { cc.aaro.cosign.core.isHex32(it) }?.let { previewBot = it }
        (m["issuer"] as? String)?.takeIf { it.startsWith("https://") || manifest != DEFAULT }?.let { issuer = it.trimEnd('/') }
        (m["issuer_pubkey"] as? String)?.takeIf { cc.aaro.cosign.core.isHex32(it) }?.let { issuerKey = it }
        if (have) return
        val days = m["days"] as? List<*> ?: return
        rememberNights(context, days)
        val e = days.mapNotNull { it as? Map<*, *> }.lastOrNull { it["date"] == date } ?: return debug(context, "none for $date in the list")
        if (e["status"] != "ok") return
        val url = (e["image"] as? String)?.takeIf { it.startsWith("https://") || manifest != DEFAULT } ?: return
        val bytes = get(url) ?: return
        // Exactly what the pipeline made, or nothing.
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha != (e["sha256"] as? String)?.lowercase()) return debug(context, "picture doesn't match its hash")
        png.writeBytes(bytes); name.writeText((e["name"] as? String)?.trim()?.take(40)?.ifEmpty { null } ?: "Today's marble")
        // A week of them on the phone, no more.
        dir.listFiles()?.filter { it.name.take(10) < LocalDate.now(ZoneOffset.UTC).minusDays(7).toString() }?.forEach { it.delete() }
        if (utcDate() == date) today.value = Marble(name.readText(), 0, (e["detail"] as? Long)?.toInt()?.div(2) ?: NIGHT_DETAIL, png)
    }

    private fun rememberNights(context: Context, days: List<*>) {
        val n = days.mapNotNull { it as? Map<*, *> }.filter { it["status"] == "ok" }.mapNotNull { e ->
            val d = e["date"] as? String ?: return@mapNotNull null
            val img = (e["image"] as? String)?.takeIf { it.startsWith("https://") || manifest != DEFAULT } ?: return@mapNotNull null
            d to ((e["name"] as? String)?.trim()?.take(40)?.ifEmpty { null } ?: "Marble") to img
        }.associate { (dn, img) -> dn.first to (dn.second to img) }
        nights.value = n
        try { File(context.filesDir, "nights.json").writeText(org.json.JSONObject(n.mapValues { org.json.JSONArray(listOf(it.value.first, it.value.second)) }).toString()) } catch (_: Exception) {}
    }

    /** The nights as last read, from the phone (once), for your marbles before the list is fetched again. */
    fun loadNights(context: Context) {
        if (nightsRead) return
        nightsRead = true
        if (nights.value.isNotEmpty()) return
        try {
            val o = org.json.JSONObject(File(context.filesDir, "nights.json").readText())
            nights.value = o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getString(0) to it.getString(1) } }
        } catch (_: Exception) {}
    }

    /** The marble of [date]: the night's own if the list had it, else the collection's for that date. */
    fun marbleOf(date: java.time.LocalDate): Marble {
        val k = date.toString()
        nights.value[k]?.let { (name, url) ->
            val local = today.value?.file?.takeIf { it.name.startsWith(k) }
            return Marble(name, 0, NIGHT_DETAIL, local, url)
        }
        return MARBLES[Math.floorMod(date.toEpochDay(), MARBLES.size.toLong()).toInt()]
    }

    fun get(url: String): ByteArray? = try {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (_: Exception) { null }
}
