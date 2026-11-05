package cc.aaro.cosign

/**
 * Home's shared rules (marble-content-fixtures home-rules.md, fixtures/home/rules.json, as Marble Web): what the
 * focused, empty box lists, and which place a typed word goes to.
 */
object HomeRules {
    data class Note(val id: String, val text: String, val created: Long)
    data class Search(val text: String, val at: Long)
    /** One row of the focused box: "inView:<id>", "older:<id>", "note:<id>", "search:<words>", or "all" (Notes and searches). */
    data class Row(val kind: String, val id: String) { override fun toString() = if (kind == "all") "all" else "$kind:$id" }

    private const val DAY = 86_400L
    private const val OLDER_AFTER = 14 * DAY

    /**
     * The focused, empty box, at most 7 rows: Keep in view (3, in your order); one older note (the oldest open one not
     * seen or changed in 14 days, the same one all day once offered) when [older] is on; your 2 latest searches or
     * notes not already above; then Notes and searches. [seen]: when each note was last looked at; [offered]: the
     * older note offered last, and when.
     */
    fun focus(inView: List<String>, notes: List<Note>, searches: List<Search>, now: Long, seen: Map<String, Long> = emptyMap(),
              offered: Pair<String, Long>? = null, older: Boolean = true): List<Row> {
        val byId = notes.associateBy { it.id }
        val rows = ArrayList<Row>()
        val view = inView.filter { it in byId }.take(3)
        view.forEach { rows += Row("inView", it) }
        if (older) {
            fun isOld(n: Note) = n.id !in view && maxOf(n.created, seen[n.id] ?: 0) <= now - OLDER_AFTER
            val again = offered?.takeIf { (id, at) -> Math.floorDiv(at, DAY) == Math.floorDiv(now, DAY) && byId[id]?.let(::isOld) == true }?.first
            (again ?: notes.filter(::isOld).minByOrNull { it.created }?.id)?.let { rows += Row("older", it) }
        }
        val above = rows.map { it.id }.toSet()
        val aboveWords = above.mapNotNull { byId[it]?.text?.trim()?.lowercase() }.toSet()
        val latest = (notes.filter { it.id !in above }.map { Triple("note", it.id, it.created) } +
            searches.filter { it.text.trim().lowercase() !in aboveWords }.map { Triple("search", it.text, it.at) })
            .sortedByDescending { it.third }.take(2)
        latest.forEach { (k, id, _) -> rows += Row(k, id) }
        rows += Row("all", "")
        return rows
    }

    /**
     * Go to: the place [typed] names (its name or a whole word of it, or 4 or more letters it begins with), and whether
     * it goes below one of your notes that's exactly what's typed. Null: no place.
     */
    fun goTo(typed: String, places: List<String>, notes: List<String>): Pair<String, Boolean>? {
        val t = typed.trim().lowercase().ifEmpty { return null }
        val place = places.firstOrNull { p ->
            val name = p.lowercase()
            t == name || t in name.split(' ') || (t.length >= 4 && name.startsWith(t))
        } ?: return null
        return place to notes.any { it.trim().equals(typed.trim(), ignoreCase = true) }
    }
}
