package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
import org.json.JSONObject

/**
 * Later's items and packs' notes, one to the other. A note's change times (`f`), the text an edit started from (`base`)
 * and a moved-out body ride in the item's extra fields, so Later and the single-event list keep them unchanged.
 */
object LaterNotes {
    private val CARRIED = setOf("f", "base", "body")

    fun note(i: LaterItem): Note {
        val fo = i.extra["f"] as? JSONObject
        // An item from the single-event list has no field times: every field counts as changed when it last was.
        val f = fo?.keys()?.asSequence()?.associateWith { fo.optLong(it) }?.filterValues { it > 0 } ?: Note.FIELDS.associateWith { i.updated }
        return Note(i.id, i.text, i.created, i.updated, i.done, i.deleted, i.url, i.quote, i.from, f, i.extra["base"] as? String,
            i.extra["body"] as? JSONObject, i.extra.filterKeys { it !in CARRIED })
    }

    fun item(n: Note): LaterItem = LaterItem(n.id, n.text, n.created, n.updated, n.done, n.deleted, n.url,
        n.unknown + listOfNotNull("f" to JSONObject(n.f.toSortedMap() as Map<*, *>), n.base?.let { "base" to it }, n.body?.let { "body" to it }), n.quote, n.from)

    /** A change made here ([was] → [now]): each changed field gets the change's time, and an edit says what it started from. */
    fun touched(was: LaterItem?, now: LaterItem): LaterItem {
        val f = HashMap<String, Long>()
        (was?.extra?.get("f") as? JSONObject)?.let { fo -> fo.keys().forEach { f[it] = fo.optLong(it) } }
        if (was == null) Note.FIELDS.forEach { f[it] = now.created }
        else {
            if (was.text != now.text) f["text"] = now.updated
            if (was.done != now.done) f["done"] = now.updated
            if (was.deleted != now.deleted) f["deleted"] = now.updated
            if (was.url != now.url) f["url"] = now.updated
            if (was.quote != now.quote) f["quote"] = now.updated
        }
        val base = if (was != null && was.text != now.text) Note.hashOf(was.text) else was?.extra?.get("base") as? String
        return now.copy(extra = now.extra + ("f" to JSONObject(f.toSortedMap() as Map<*, *>)) + listOfNotNull(base?.let { "base" to it }))
    }
}
