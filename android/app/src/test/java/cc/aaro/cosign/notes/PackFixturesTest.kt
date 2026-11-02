package cc.aaro.cosign.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The shared notes-packs vectors (marble-content-fixtures/fixtures/notes-packs/vectors.json, copied to test
 * resources): layout, merge and acceptance, as every client must compute them. NOTES_FIXTURES_WRITE=<path> writes them.
 */
class PackFixturesTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val l = Layout("k1", key.unhex())

    private fun note(id: String, text: String, t: Long, f: Map<String, Long>, base: String? = null, done: Long? = null, deleted: Long? = null) =
        Note(id, text, 1000, t, done = done, deleted = deleted, f = f, base = base)

    private fun vectors(): JSONObject {
        val ids = listOf("a", "note-1", "0123456789abcdef")
        val layout = JSONObject().put("key", key).put("kid", "k1").put("w", l.w).put("checkpointD", l.checkpointD)
            .put("slots", JSONArray(ids.map { JSONObject().put("id", it).put("bits", l.slot(it).take(16)).put("pack", l.owner(it, emptySet())).put("packAfterItsPackSplit", l.owner(it, setOf(l.owner(it, emptySet())))) }))
            .put("d", JSONArray(listOf("0000", "0101", "1111", "00001").map { JSONObject().put("prefix", it).put("d", l.d(it)) }))
            .put("hashOf", JSONObject().put("text", "milk").put("hash", Note.hashOf("milk")))
        val milk = note("n", "milk", 1000, mapOf("text" to 1000L))
        fun case(name: String, mine: Note, theirs: Note) = JSONObject().put("name", name).put("mine", mine.toJson()).put("theirs", theirs.toJson())
            .put("device", "the tablet").put("out", JSONArray(Merge.notes(mine, theirs, "the tablet").map { it.toJson() }))
        val merges = JSONArray(listOf(
            case("a tick and an edit: both kept", milk.copy(done = 2000, updated = 2000, f = mapOf("text" to 1000L, "done" to 2000L)),
                milk.copy(text = "oat milk", updated = 2001, base = Note.hashOf("milk"), f = mapOf("text" to 2001L))),
            case("two edits from the same start: a copy", milk.copy(text = "oat milk", updated = 2000, base = Note.hashOf("milk"), f = mapOf("text" to 2000L)),
                milk.copy(text = "almond milk", updated = 2001, base = Note.hashOf("milk"), f = mapOf("text" to 2001L))),
            case("an edit of an edit: no copy", milk.copy(text = "oat milk", updated = 2000, base = Note.hashOf("milk"), f = mapOf("text" to 2000L)),
                milk.copy(text = "oat milk, 2", updated = 2001, base = Note.hashOf("oat milk"), f = mapOf("text" to 2001L))),
            case("removed: the earliest removal holds", milk.copy(deleted = 3000, updated = 3000, f = mapOf("text" to 1000L, "deleted" to 3000L)),
                milk.copy(text = "edited later", updated = 9000, base = Note.hashOf("milk"), f = mapOf("text" to 9000L))),
        ))
        val owner = l.owner("n", emptySet())
        val wrong = l.live(emptySet()).first { it != owner }
        fun accept(name: String, d: String, content: String, taken: Boolean) = JSONObject().put("name", name).put("d", d).put("content", JSONObject(content)).put("taken", taken)
        val accepts = JSONArray(listOf(
            accept("a pack in its place", l.d(owner), Pack(owner, "k1", listOf(milk)).toJson(), true),
            accept("a note in another pack's place", l.d(wrong), Pack(wrong, "k1", listOf(milk)).toJson(), false),
            accept("a prefix that doesn't name its d", l.d("0000"), Pack("0001", "k1").toJson(), false),
            accept("another key's pack", l.d(owner), Pack(owner, "k2", listOf(milk)).toJson(), false),
            accept("a split pack", l.d(owner), Pack(owner, "k1", retired = true).toJson(), true),
        ))
        return JSONObject().put("spec", "~/igloo/marble-notes-sync-v3.md (option A)").put("layout", layout).put("merge", merges).put("accept", accepts)
    }

    /** Each acceptance case against a fresh vault. */
    private fun taken(c: JSONObject): Boolean {
        val v = Vault(l)
        v.apply(listOf(Vault.Incoming(c.getString("d"), 5000, c.getJSONObject("content").toString())))
        return v.seen.isNotEmpty()
    }

    @Test fun vectorsMatch() {
        val mine = vectors()
        System.getenv("NOTES_FIXTURES_WRITE")?.let { File(it).writeText(mine.toString(2) + "\n") }
        val shared = JSONObject(javaClass.getResource("/notes-packs/vectors.json")!!.readText())
        assertEquals(shared.toString(2), mine.toString(2))
        val a = shared.getJSONArray("accept")
        for (i in 0 until a.length()) a.getJSONObject(i).let { assertEquals(it.getString("name"), it.getBoolean("taken"), taken(it)) }
    }
}
