package cc.aaro.cosign

import android.content.Context
import cc.aaro.qrst.core.Json
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The feed log: what you do with Discover's posts and today's stories, one JSON object a line, so what Discover
 * should learn can be worked out from what you actually did. Off unless you turn it on (Account › Advanced: tap
 * the version seven times, then the switch). On this phone only: never synced, backed up or sent anywhere; Export
 * puts a zip in Downloads/Marble and offers the share sheet. The format: android/docs/feed-log.md.
 */
object FeedLog {
    /** The format of a line: it changes only when a field's meaning does. */
    const val V = 1
    /** Which ranking put the lists in order, on every list logged: change it whenever Calm's ranking does. */
    /** Which feed made what's logged: "following1", "following2" or "following3" (Feed 1, 2, 3); set by Social from the switch, and on every event. */
    @Volatile var RANKER = "following2"

    val on = MutableStateFlow(false)
    /** The switch shows in Account › Advanced. */
    val unlocked = MutableStateFlow(false)
    /** How much is kept, in bytes (brought up to date on load, export and delete). */
    val bytes = MutableStateFlow(0L)
    @Volatile private var files: LogFiles? = null
    @Volatile private var app = ""
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "feedlog").apply { isDaemon = true } }
    /** This use of Marble: a new one on start, and on coming back after a while. */
    @Volatile var session = newSession(); private set
    /** Items already described today (an `item` line), by id. */
    private val described = HashMap<String, Long>()
    /** When each item was last opened, for `again` on the next open. */
    private val openedAt = HashMap<String, Long>()

    private fun prefs(c: Context) = c.getSharedPreferences("feedlog", Context.MODE_PRIVATE)
    private fun newSession() = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36) + "-" + (100..999).random()

    @Synchronized fun load(c: Context) {
        if (files != null) return
        val p = prefs(c)
        files = LogFiles(File(c.filesDir, "feedlog"))
        app = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        // Off once (marble-2009-spec, question 22): with no ranked feed it has nothing to teach. The switch stays in Advanced.
        if (!p.getBoolean("off2009", false)) p.edit().putBoolean("on", false).putBoolean("off2009", true).apply()
        on.value = p.getBoolean("on", false)
        unlocked.value = p.getBoolean("unlocked", false) || on.value
        io.execute { bytes.value = files?.bytes() ?: 0 }
        if (on.value) start("launch")
    }

    fun unlock(c: Context) { unlocked.value = true; prefs(c).edit().putBoolean("unlocked", true).apply() }

    fun set(c: Context, v: Boolean) {
        if (v == on.value) return
        if (!v) ev("log", "on" to false)
        on.value = v
        prefs(c).edit().putBoolean("on", v).apply()
        if (v) { ev("log", "on" to true); start("on") }
    }

    /** A new use of Marble: [why] "launch", "back" (after a while away) or "on" (the switch). */
    fun start(why: String) {
        if (!on.value) return
        session = newSession()
        val z = java.time.ZonedDateTime.now()
        ev("session", "why" to why, "hour" to z.hour, "dow" to z.dayOfWeek.value, "tz" to z.offset.totalSeconds / 60, "app" to app, "rv" to RANKER)
    }

    /** One line: [name] and its fields, with the time and the session. Nothing when the log is off. */
    fun ev(name: String, vararg f: Pair<String, Any?>) {
        if (!on.value) return
        val out = files ?: return
        val t = System.currentTimeMillis()
        // Which feed was on, on every line, so the feeds can be compared.
        val text = line(t, session, name, if (f.any { it.first == "rv" }) f.asList() else f.asList() + ("rv" to RANKER))
        io.execute { try { out.append(text, t); bytes.value += text.length + 1 } catch (_: Exception) {} }
    }

    /** What an item is (its source, topics, format), once a day, for the analysis to group by. */
    fun item(id: String, vararg f: Pair<String, Any?>) {
        if (!on.value) return
        val day = System.currentTimeMillis() / 86_400_000
        synchronized(described) { if (described[id] == day) return; described[id] = day }
        ev("item", "id" to id, *f)
    }

    /** An item opened: [what] "story", "comments", "link", "thread", "image"… With `again`: seconds since it was last opened. */
    fun open(id: String, what: String, vararg f: Pair<String, Any?>) {
        if (!on.value) return
        val now = System.currentTimeMillis()
        val last = synchronized(openedAt) { openedAt.put(id, now) }
        ev("open", "id" to id, "what" to what, "again" to last?.let { (now - it) / 1000 }, *f)
    }

    /** Something you did to an item: [a] "like", "react", "later", "less", "notint", "hideAuthor"… */
    fun act(id: String?, a: String, vararg f: Pair<String, Any?>) = ev("act", "id" to id, "a" to a, *f)

    /** The log, zipped, into Downloads/Marble (where `adb pull` finds it too). Null if there was nothing or it failed. Not on the main thread. */
    fun export(c: Context): android.net.Uri? {
        val f = files ?: return null
        return io.submit<android.net.Uri?> {
            if (f.list().isEmpty()) return@submit null
            val name = "marble-feedlog-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Marble")
            }
            val uri = c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@submit null
            try { c.contentResolver.openOutputStream(uri)?.use { f.zip(it) } ?: return@submit null } catch (e: Exception) { c.contentResolver.delete(uri, null, null); return@submit null }
            bytes.value = f.bytes()
            uri
        }.get()
    }

    /** Delete everything kept. */
    fun clear() { val f = files ?: return; io.execute { f.clear(); bytes.value = 0; synchronized(described) { described.clear() } } }

    /** One line of the log, as JSON: `t` (ms), `s` (session), `ev`, then [fields], leaving out nulls. */
    fun line(t: Long, session: String, name: String, fields: List<Pair<String, Any?>>): String {
        val b = StringBuilder("{\"t\":").append(t).append(",\"s\":")
        Json.quote(session, b); b.append(",\"ev\":"); Json.quote(name, b)
        for ((k, v) in fields) { if (v == null) continue; b.append(','); Json.quote(k, b); b.append(':'); value(v, b) }
        return b.append('}').toString()
    }

    private fun value(v: Any?, b: StringBuilder) {
        when (v) {
            null -> b.append("null")
            is String -> Json.quote(v, b)
            is Boolean, is Int, is Long -> b.append(v.toString())
            is Float -> value(v.toDouble(), b)
            is Double -> if (v.isNaN() || v.isInfinite()) b.append("null") else b.append(Math.round(v * 10_000) / 10_000.0)
            is Map<*, *> -> { b.append('{'); var first = true; for ((k, x) in v) { if (x == null) continue; if (!first) b.append(','); first = false; Json.quote(k.toString(), b); b.append(':'); value(x, b) }; b.append('}') }
            is Iterable<*> -> { b.append('['); var first = true; for (x in v) { if (!first) b.append(','); first = false; value(x, b) }; b.append(']') }
            is Array<*> -> value(v.asList(), b)
            else -> Json.quote(v.toString(), b)
        }
    }
}

/** The log's files: one a day (`yyyy-mm-dd.jsonl`, the phone's date), the oldest dropped past [cap] bytes or [days] days. */
class LogFiles(val dir: File, private val cap: Long = 30L shl 20, private val days: Int = 60, private val zone: ZoneId = ZoneId.systemDefault()) {
    private var day: String? = null
    private var since = 0

    fun append(line: String, t: Long) {
        dir.mkdirs()
        val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()
        File(dir, "$d.jsonl").appendText(line + "\n")
        if (d != day || ++since >= 500) { day = d; since = 0; trim(d) }
    }

    fun list(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()
    fun bytes() = list().sumOf { it.length() }
    fun clear() = list().forEach { it.delete() }

    /** Drop the oldest days: any from more than [days] ago, then more until it's within [cap]. Today's file stays. */
    fun trim(today: String) {
        val oldest = LocalDate.parse(today).minusDays(days.toLong()).toString()
        val l = list().toMutableList()
        var total = l.sumOf { it.length() }
        while (l.size > 1 && (total > cap || l.first().name.take(10) < oldest)) { total -= l.first().length(); l.removeAt(0).delete() }
    }

    fun zip(out: OutputStream) = ZipOutputStream(out).use { z ->
        for (f in list()) { z.putNextEntry(ZipEntry("feedlog/" + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
    }
}

/** The feeds' names, to tell them apart (Account › Advanced; the feed log says "following1" and so on). */
object FeedNames {
    fun of(v: Int) = if (v == 4) "Reader" else "Feed $v · " + short(v).substringAfter(" · ")
    fun short(v: Int) = when (v) { 1 -> "1 · Original"; 2 -> "2 · Mixed"; 4 -> "Reader"; else -> "3 · Edition" }
}
