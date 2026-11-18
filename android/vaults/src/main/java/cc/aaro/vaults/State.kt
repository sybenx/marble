// The state of a vault (spec §5.3): a pure function of the counting versions, the puts that don't count, and the
// `dead` edges of counting checkpoints. Every device holding the same inputs shows the same entries.
package cc.aaro.vaults

/** An entry's current versions, lowest `ver` first (§5.4): one is the entry, several a conflict. */
class Entry(val id: String, val current: List<Put>) {
    val shown: Put get() = current.firstOrNull { !it.deleted } ?: current.first()
    val conflict get() = current.size > 1
    val deleted get() = current.all { it.deleted }
}

class State(val entries: Map<String, Entry>, /** superseded ver → the vers superseding it, as this state knows them */ val dead: Map<String, Map<String, Set<String>>>, val puts: Map<String, Put>) {
    companion object {
        /**
         * [counting]: puts carried by live owner dvks or held in counting checkpoints. [others]: puts held that don't count
         * (a non-live dvk's), which matter only when a counting version names them. [deadEdges]: (entry, superseded ver,
         * superseding ver) from counting checkpoints.
         */
        fun of(counting: Collection<Put>, others: Collection<Put> = emptyList(), deadEdges: Collection<Triple<String, String, String>> = emptyList()): State {
            // One put per (entry, ver): a collision is settled by the lower SHA-256 of canonical JSON.
            fun pick(m: HashMap<String, Put>, p: Put) { val k = p.entry + ":" + p.ver; val o = m[k]; if (o == null || p.order < o.order) m[k] = p }
            val count = HashMap<String, Put>(); counting.forEach { pick(count, it) }
            val other = HashMap<String, Put>(); others.forEach { pick(other, it) }
            val byEntry = count.values.groupBy { it.entry }
            val deadByEntry = deadEdges.groupBy { it.first }
            val entries = HashMap<String, Entry>()
            val deadOut = HashMap<String, Map<String, Set<String>>>()
            for (entry in (byEntry.keys + deadByEntry.keys)) {
                val vers = byEntry[entry].orEmpty()
                // Edges: superseding ver → superseded ver.
                val sup = HashMap<String, MutableSet<String>>()     // from → to (supersedes)
                val deadE = HashSet<Pair<String, String>>()
                fun edge(from: String, to: String) { sup.getOrPut(from) { HashSet() } += to }
                for (d in deadByEntry[entry].orEmpty()) { edge(d.third, d.second); deadE += d.third to d.second }
                // From counting versions, and on through any named put that doesn't count (§5.3).
                val walked = HashSet<String>()
                fun walk(p: Put) {
                    if (!walked.add(p.ver)) return
                    for (s in p.supersedes) {
                        edge(p.ver, s)
                        if ("$entry:$s" !in count) other["$entry:$s"]?.let(::walk)
                    }
                }
                vers.forEach(::walk)
                // Dead edges that lie on a cycle are ignored: drop a dead edge a→b when b reaches a.
                fun reaches(a: String, b: String): Boolean {
                    val seen = HashSet<String>(); val st = ArrayDeque(listOf(a))
                    while (st.isNotEmpty()) { val x = st.removeLast(); if (x == b) return true; if (seen.add(x)) sup[x]?.let { st.addAll(it) } }
                    return false
                }
                for ((a, b) in deadE.toList()) if (reaches(b, a)) { sup[a]?.remove(b) }
                val superseded = sup.values.flatten().toSet()
                val current = vers.filter { it.ver !in superseded }.sortedBy { it.ver }
                if (current.isNotEmpty()) entries[entry] = Entry(entry, current)
                val d = HashMap<String, MutableSet<String>>()
                for ((from, tos) in sup) for (to in tos) d.getOrPut(to) { HashSet() } += from
                if (d.isNotEmpty()) deadOut[entry] = d
            }
            return State(entries, deadOut, count.values.associateBy { it.entry + ":" + it.ver })
        }
    }
}
