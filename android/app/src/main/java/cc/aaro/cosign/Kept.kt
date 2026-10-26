package cc.aaro.cosign

import cc.aaro.cosign.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The marbles you kept: each day's marble you looked at big on its day, by its id (marble-YYYY-MM-DD) and when.
 * Only what you have: nothing here counts days you didn't come. On your account as app data encrypted to you,
 * a year to an event (kind 30078, d=marble/marbles/<year>), merged by id (the first keeping stands).
 */
class Kept(private val prefs: android.content.SharedPreferences) {
    /** Id → when it was kept (unix seconds). */
    val all = MutableStateFlow(fromJson(prefs.getString("kept", null)))
    /** Called with the year whose list changed (Social sends that year's copy a little later). */
    var changed: (String) -> Unit = {}

    /** Kept before this was synced (0.12.16 kept ids on this phone only): taken in once, at the start of their day. */
    fun takeIn(ids: Set<String>) {
        val add = ids.filter { idOk(it) && it !in all.value }
        if (add.isEmpty()) return
        all.update { m -> m + add.associateWith { dayStart(it) } }
        store()
        add.map { yearOf(it) }.toSet().forEach { changed(it) }
    }

    /** Today's marble, looked at big: kept. True the first time (the flourish). */
    fun keep(date: String): Boolean {
        val id = "marble-$date"
        if (id in all.value) return false
        all.update { it + (id to Clock.now()) }
        store(); changed(yearOf(id))
        return true
    }

    /** The account's copy of one year merged in; true if this phone has that year's ones the copy hasn't. */
    fun merge(json: String, year: String): Boolean {
        val theirs = try {
            val o = org.json.JSONObject(json)
            if (o.optInt("v", 1) != 1) return false
            entries(o.optJSONArray("kept"))
        } catch (_: Exception) { return false }
        val mine = all.value.filterKeys { yearOf(it) == year }
        all.update { m -> m + theirs.mapValues { (id, at) -> minOf(at, m[id] ?: at) } }
        store()
        return mine.keys.any { it !in theirs } || mine.any { (id, at) -> theirs[id]?.let { at < it } == true }
    }

    /** {"v":1,"at":…,"kept":[{"id":"marble-2026-10-27","at":…}]}: one year's. */
    fun json(year: String): String {
        val l = all.value.filterKeys { yearOf(it) == year }.toList().sortedBy { it.first }
        return org.json.JSONObject().put("v", 1).put("at", l.maxOfOrNull { it.second } ?: Clock.now())
            .put("kept", org.json.JSONArray(l.map { (id, at) -> org.json.JSONObject().put("id", id).put("at", at) })).toString()
    }

    fun years() = all.value.keys.map { yearOf(it) }.toSet()

    private fun store() = prefs.edit().putString("kept", org.json.JSONObject().put("v", 1)
        .put("kept", org.json.JSONArray(all.value.map { (id, at) -> org.json.JSONObject().put("id", id).put("at", at) })).toString()).apply()

    companion object {
        const val TAG = "marble/marbles"
        private val ID = Regex("^marble-(\\d{4})-\\d{2}-\\d{2}$")
        fun idOk(id: String) = ID.matches(id)
        fun yearOf(id: String) = ID.matchEntire(id)?.groupValues?.get(1) ?: "0"
        fun dateOf(id: String) = id.removePrefix("marble-")
        private fun dayStart(id: String) = try { java.time.LocalDate.parse(dateOf(id)).toEpochDay() * 86_400 } catch (_: Exception) { 0L }
        private fun entries(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONObject(i)?.let { x -> x.optString("id").takeIf(::idOk)?.let { it to x.optLong("at", dayStart(it)) } }
        }.toMap()
        fun fromJson(json: String?): Map<String, Long> = try { entries(org.json.JSONObject(json ?: "{}").optJSONArray("kept")) } catch (_: Exception) { emptyMap() }
    }
}
