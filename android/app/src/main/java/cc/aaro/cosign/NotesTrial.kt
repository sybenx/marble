package cc.aaro.cosign

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The four-week notes trial (~/igloo/notes-processing-research.md §5), on this phone only. Days 1–14: a silent log of
 * when each note is shown (in search, in Notes, on home), opened, edited, done. From day 15: notes you haven't seen
 * sink in Notes (never leaving it), a "Keep in view" shelf sits at its top, and a long-press on an old note in search
 * offers Next step / Keep in view / Let go. Day 28: one question, once. Nothing here is shown as a count, an age or a
 * date; shown and opened times never leave the phone, and only decisions sync (through the note's own fields).
 */
object NotesTrial {
    private var prefs: android.content.SharedPreferences? = null
    private var log: File? = null
    /** When the trial began (seconds). */
    @Volatile var start = 0L; private set
    private val lastSeen = HashMap<String, Long>()
    private val recent = HashMap<String, Long>()
    /** The Keep in view shelf (device-local for the trial), in order. */
    val shelf = MutableStateFlow<List<String>>(emptyList())
    /** The day-28 answer, once given ("calmer", "same", "worse"). */
    val answer = MutableStateFlow<String?>(null)

    fun load(c: Context) {
        if (prefs != null) return
        val p = c.getSharedPreferences("notes-trial", Context.MODE_PRIVATE).also { prefs = it }
        log = File(c.filesDir, "notes-trial.log")
        start = p.getLong("start", 0L).takeIf { it > 0 } ?: now().also { p.edit().putLong("start", it).apply() }
        p.getString("seen", null)?.let { s -> try { val o = org.json.JSONObject(s); o.keys().forEach { lastSeen[it] = o.getLong(it) } } catch (_: Exception) {} }
        shelf.value = p.getString("shelf", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        answer.value = p.getString("answer", null)
    }

    private fun now() = System.currentTimeMillis() / 1000
    /** Which day of the trial it is (1 on the first). */
    fun day(): Int = ((now() - start) / 86_400L).toInt() + 1
    /** Weeks 3–4 and after: the sinking order, the shelf and the long-press choices. */
    val on: Boolean get() = start > 0 && day() >= 15
    /** Day 28 and not answered yet: the one question. */
    val ask: Boolean get() = start > 0 && day() >= 28 && answer.value == null

    private fun write(kind: String, id: String, where: String = "") {
        val f = log ?: return
        try { f.appendText("${now()}\t$kind\t$id\t$where\n") } catch (_: Exception) {}
    }
    private fun touch(id: String) {
        lastSeen[id] = now()
        prefs?.edit()?.putString("seen", org.json.JSONObject(lastSeen.entries.sortedByDescending { it.value }.take(2000).associate { it.key to it.value } as Map<*, *>).toString())?.apply()
    }

    /** A note on screen ([where]: search, notes, home); once a quarter hour per place, so scrolling back isn't many. */
    fun shown(id: String, where: String) {
        val k = "$where:$id"
        if (now() - (recent[k] ?: 0) < 900) return
        recent[k] = now(); write("shown", id, where); touch(id)
    }
    fun opened(id: String, where: String) { write("opened", id, where); touch(id) }
    fun edited(id: String) { write("edited", id); touch(id) }
    fun done(id: String) { write("done", id); touch(id) }
    /** A decision from the long-press: "step", "view" or "go". */
    fun decided(id: String, which: String) { write("decided", id, which); touch(id) }

    /** When this phone last saw or touched [id] (0: never), for the sinking order. */
    fun seen(id: String): Long = lastSeen[id] ?: 0

    fun keepInView(id: String, on: Boolean) {
        shelf.value = if (on) (shelf.value - id) + id else shelf.value - id
        prefs?.edit()?.putString("shelf", shelf.value.joinToString(","))?.apply()
    }

    fun answered(a: String) { answer.value = a; prefs?.edit()?.putString("answer", a)?.apply(); write("answer", "", a) }

    /** Notes in the order Notes shows them in the trial: the shelf first, then the rest by when last seen or touched here. */
    fun order(items: List<LaterItem>): List<LaterItem> = items.sortedByDescending { maxOf(seen(it.id), it.updated) }

    /**
     * The summary for the hub, read once at the end (never shown in Marble): per week, how many notes older than 30 days
     * were shown, opened, edited, done or decided; the decisions by kind; the answer.
     */
    fun summary(items: List<LaterItem>): String {
        val created = items.associate { it.id to it.created }
        val lines = try { log?.readLines().orEmpty() } catch (_: Exception) { emptyList() }
        val weeks = HashMap<String, HashMap<String, MutableSet<String>>>()
        val decisions = HashMap<String, Int>()
        for (l in lines) {
            val p = l.split('\t'); if (p.size < 4) continue
            val t = p[0].toLongOrNull() ?: continue
            val week = "week " + (((t - start) / (7 * 86_400L)).toInt() + 1)
            if (p[1] == "decided") decisions[p[3]] = (decisions[p[3]] ?: 0) + 1
            val old = created[p[2]]?.let { t - it > 30 * 86_400L } ?: false
            if (!old) continue
            weeks.getOrPut(week) { HashMap() }.getOrPut(p[1]) { HashSet() } += p[2]
        }
        return org.json.JSONObject().put("days", day()).put("answer", answer.value ?: JSONNULL)
            .put("oldNotes", org.json.JSONObject(weeks.toSortedMap().mapValues { (_, k) -> org.json.JSONObject(k.mapValues { it.value.size } as Map<*, *>) } as Map<*, *>))
            .put("decisions", org.json.JSONObject(decisions as Map<*, *>)).put("shelf", shelf.value.size).put("events", lines.size).toString(2)
    }
    private val JSONNULL = org.json.JSONObject.NULL
}
