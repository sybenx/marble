package cc.aaro.cosign.reader

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your Reader list (kind 30078, d=marble/reader, NIP-44 to yourself; reader.md §1): sources, folders, the people
 * toggle and muted sources. Private: who you read and how you group them is never published. Merged by
 * [ReaderRules.mergeReader]; fields this version doesn't know are kept.
 */
class ReaderList(val json: JSONObject = JSONObject().put("v", 1)) {
    data class Source(val id: String, val type: String, val value: String, val name: String, val folder: String, val at: Long, val gone: Boolean)
    data class Folder(val id: String, val name: String, val order: Int, val view: String, val at: Long, val gone: Boolean)

    private fun arr(k: String) = json.optJSONArray(k) ?: JSONArray()
    val sources: List<Source> get() = arr("sources").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
        Source(it.optString("id"), it.optString("type"), it.optString("value"), it.optString("name"), it.optString("folder"), it.optLong("at"), it.optBoolean("gone")) } }
    val folders: List<Folder> get() = arr("folders").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
        Folder(it.optString("id"), it.optString("name"), it.optInt("order"), it.optString("view", "list"), it.optLong("at"), it.optBoolean("gone")) } }
    val live get() = sources.filter { !it.gone }
    /** The people toggle; null: never set (a new user: on if they follow anyone). */
    val peopleOn: Boolean? get() = json.optJSONObject("people")?.let { if (it.has("on")) it.optBoolean("on") else null }
    val muted: Set<String> get() = arr("muted").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("gone") }.map { it.optString("source") }.toSet() }

    private fun upsert(k: String, key: String, o: JSONObject): ReaderList {
        val out = JSONObject(json.toString()); val a = arr(k); val n = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString(key) != o.optString(key)) n.put(it) }
        out.put(k, n.put(o))
        return ReaderList(out)
    }
    fun withSource(s: Source) = upsert("sources", "id", JSONObject().put("id", s.id).put("type", s.type).put("value", s.value).put("name", s.name).put("folder", s.folder).put("at", s.at).put("gone", s.gone))
    fun withFolder(f: Folder) = upsert("folders", "id", JSONObject().put("id", f.id).put("name", f.name).put("order", f.order).put("view", f.view).put("at", f.at).put("gone", f.gone))
    fun withPeople(on: Boolean, at: Long) = ReaderList(JSONObject(json.toString()).put("people", JSONObject().put("on", on).put("at", at)))
    fun withMute(source: String, on: Boolean, at: Long) = upsert("muted", "source", JSONObject().put("source", source).put("at", at).put("gone", !on))

    /** Removals older than 30 days dropped (other devices have learned them by then). */
    fun tidy(now: Long): ReaderList {
        val out = JSONObject(json.toString())
        for (k in listOf("sources", "folders", "muted")) {
            val a = arr(k); val n = JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (!(it.optBoolean("gone") && now - it.optLong("at") > 30 * 86_400L)) n.put(it) }
            out.put(k, n)
        }
        return ReaderList(out)
    }

    fun merge(o: ReaderList) = ReaderList(ReaderRules.mergeReader(json, o.json))
    override fun toString() = json.toString()

    companion object {
        const val D = "marble/reader"
        fun parse(s: String?): ReaderList? {
            if (s == null) return null
            return try { JSONObject(s).takeIf { it.optInt("v", 1) == 1 }?.let(::ReaderList) } catch (_: Exception) { null }
        }
        /** A source's id (reader.md §1): its type and value, a relay's address in one spelling. */
        fun id(type: String, value: String) = "$type:" + if (type == "relay") value.trim().lowercase().trimEnd('/') else value.trim()
    }
}
