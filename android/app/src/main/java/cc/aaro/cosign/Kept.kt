package cc.aaro.cosign

import cc.aaro.cosign.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One claimed marble: when, and the issuer's award for it (a signed kind 8, as JSON). [gone]: when it was taken off
 * your marbles (0: it's there); [upd]: its last change, so a removal, or claiming it again, wins over an older copy.
 */
data class KeptMarble(val at: Long, val award: String? = null, val gone: Long = 0, val upd: Long = at) {
    val here get() = gone == 0L
}

/**
 * Your marbles: each day's marble you claimed on its day, by its id (marble-YYYY-MM-DD) and when. Only what you
 * have: nothing here counts days you didn't come. On your account as app data encrypted to you, a year to an event
 * (kind 30078, d=marble/marbles/<year>), merged by id (the first claiming stands; the newest change says if it's there).
 */
class Kept(private val prefs: android.content.SharedPreferences) {
    /** Id → when it was claimed, and its award; with the ones taken off (see [KeptMarble.here]). */
    val all = MutableStateFlow(fromJson(prefs.getString("kept", null)))
    /** The ones that are there. */
    fun here() = all.value.filterValues { it.here }
    /** Called with the year whose list changed (Social sends that year's copy a little later). */
    var changed: (String) -> Unit = {}

    /** Kept before this was synced (0.12.16 kept ids on this phone only): taken in once, at the start of their day. */
    fun takeIn(ids: Set<String>) {
        val add = ids.filter { idOk(it) && it !in all.value }
        if (add.isEmpty()) return
        all.update { m -> m + add.associateWith { KeptMarble(dayStart(it)) } }
        store()
        add.map { yearOf(it) }.toSet().forEach { changed(it) }
    }

    /** Today's marble, claimed (or claimed again after it was taken off). True if it wasn't there (the flourish). */
    fun keep(date: String): Boolean {
        val id = "marble-$date"
        val k = all.value[id]
        if (k?.here == true) return false
        val t = Clock.now()
        all.update { it + (id to (k?.copy(gone = 0, upd = maxOf(t, k.upd + 1)) ?: KeptMarble(t))) }
        store(); changed(yearOf(id))
        return true
    }

    /** Taken off your marbles (the trash can): a marker that syncs, so your other devices drop it too. */
    fun remove(id: String) {
        val k = all.value[id] ?: return
        if (!k.here) return
        val t = maxOf(Clock.now(), k.upd + 1)
        all.update { it + (id to k.copy(gone = t, upd = t)) }
        store(); changed(yearOf(id))
    }

    /** The issuer's award for [id], claimed: kept with it (and synced). */
    fun awarded(id: String, award: String) {
        val k = all.value[id] ?: return
        if (k.award != null) return
        all.update { it + (id to k.copy(award = award)) }
        store(); changed(yearOf(id))
    }

    /** The account's copy of one year merged in; true if this phone has that year's ones the copy hasn't. */
    fun merge(json: String, year: String): Boolean {
        val theirs = try {
            val o = org.json.JSONObject(json)
            if (o.optInt("v", 1) != 1) return false
            entries(o.optJSONArray("kept"))
        } catch (_: Exception) { return false }
        val mine = all.value.filterKeys { yearOf(it) == year }
        // The first claiming stands, an award from either side is kept, and the newest change says whether it's there.
        all.update { m -> m + theirs.mapValues { (id, t) -> m[id]?.let { k -> val n = if (t.upd > k.upd) t else k; KeptMarble(minOf(k.at, t.at), k.award ?: t.award, n.gone, n.upd) } ?: t } }
        store()
        return mine.any { (id, k) -> val t = theirs[id]; t == null || k.at < t.at || (k.here != t.here && k.upd > t.upd) || (k.award != null && t.award == null) }
    }

    /** {"v":1,"at":…,"kept":[{"id":"marble-2026-10-27","at":…,"award":{kind 8}}, {"id":…,"at":…,"upd":…,"gone":…}]}: one year's. */
    fun json(year: String): String {
        val l = all.value.filterKeys { yearOf(it) == year }.toList().sortedBy { it.first }
        return org.json.JSONObject().put("v", 1).put("at", l.maxOfOrNull { it.second.upd } ?: Clock.now())
            .put("kept", org.json.JSONArray(l.map { (id, k) -> entry(id, k) })).toString()
    }

    fun years() = all.value.keys.map { yearOf(it) }.toSet()

    private fun store() = prefs.edit().putString("kept", org.json.JSONObject().put("v", 1)
        .put("kept", org.json.JSONArray(all.value.map { (id, k) -> entry(id, k) })).toString()).apply()

    companion object {
        const val TAG = "marble/marbles"
        private val ID = Regex("^marble-(\\d{4})-\\d{2}-\\d{2}$")
        fun idOk(id: String) = ID.matches(id)
        fun yearOf(id: String) = ID.matchEntire(id)?.groupValues?.get(1) ?: "0"
        fun dateOf(id: String) = id.removePrefix("marble-")
        private fun dayStart(id: String) = try { java.time.LocalDate.parse(dateOf(id)).toEpochDay() * 86_400 } catch (_: Exception) { 0L }
        private fun entry(id: String, k: KeptMarble) = org.json.JSONObject().put("id", id).put("at", k.at).apply {
            k.award?.let { put("award", org.json.JSONObject(it)) }
            if (k.upd != k.at) put("upd", k.upd)
            if (!k.here) put("gone", k.gone)
        }
        private fun entries(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONObject(i)?.let { x -> x.optString("id").takeIf(::idOk)?.let { val at = x.optLong("at", dayStart(it)); it to KeptMarble(at, x.optJSONObject("award")?.toString(), x.optLong("gone", 0), x.optLong("upd", at)) } }
        }.toMap()
        fun fromJson(json: String?): Map<String, KeptMarble> = try { entries(org.json.JSONObject(json ?: "{}").optJSONArray("kept")) } catch (_: Exception) { emptyMap() }
    }
}
