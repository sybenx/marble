package cc.aaro.cosign.notes

import cc.aaro.cosign.LaterItem
import cc.aaro.vaults.Account
import cc.aaro.vaults.Answer
import cc.aaro.vaults.Blobs
import cc.aaro.vaults.Ev
import cc.aaro.vaults.KeykinView
import cc.aaro.vaults.Keys
import cc.aaro.vaults.RelayNet
import cc.aaro.vaults.Store
import cc.aaro.vaults.Vaults
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Notes on a vault, as the app keeps them: the import of real v3 packs and marble/later (written by NotesSync, the
 * v3 code), then two devices editing, a conflict and its pick, Trash, and a note too long to go inline.
 */
class VaultNotesTest {
    private class Rel : RelayNet {
        val ev = ConcurrentHashMap<String, Ev>()
        private fun addr(e: Ev) = if (e.kind in 30000..39999) "${e.kind}:${e.pubkey}:${e.tag("d")}" else null
        @Synchronized override fun publish(relays: List<String>, ev: Ev): Set<String> {
            addr(ev)?.let { a -> this.ev.values.firstOrNull { addr(it) == a }?.let { o -> if (o.createdAt > ev.createdAt) return relays.toSet(); this.ev.remove(o.id) } }
            this.ev[ev.id] = ev; return relays.toSet()
        }
        override fun query(relays: List<String>, filter: JSONObject, timeoutMs: Long): Answer {
            val kinds = filter.optJSONArray("kinds")?.let { a -> List(a.length()) { a.getInt(it) } }
            val v = filter.optJSONArray("#v")?.let { a -> List(a.length()) { a.getString(it) } }
            return Answer(ev.values.filter { (kinds == null || it.kind in kinds) && (v == null || it.tag("v") in v) }, relays.toSet())
        }
    }
    private class Blo : Blobs {
        val m = ConcurrentHashMap<String, ByteArray>()
        override fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Int { m["$server/$sha256"] = bytes; return 200 }
        override fun get(server: String, sha256: String) = m["$server/$sha256"]
        override fun delete(server: String, sha256: String, auth: String) = true
    }
    private class Acc(val sk: ByteArray) : Account {
        override val npub = Keys.pub(sk)
        override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String) = Ev.sign(sk, createdAt, kind, tags, content)
        override fun openSelf(eph: String, ct: String) = Keys.decrypt(ct, Keys.conv(sk, eph))
        override fun keykin(): KeykinView? = null
    }
    private class Mem : Store { val m = HashMap<String, String>(); override fun get(k: String) = m[k]; override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v } }

    private val rel = Rel(); private val blo = Blo(); private val sk = Keys.newSecret()
    private var clock = 1_793_750_000L
    private fun device(name: String) = VaultNotes(Vaults(Acc(sk), rel, blo, Mem(), { clock }, { listOf("wss://r") }, name), { clock }) {
        listOf("wss://r") to listOf("https://s1", "https://s2")
    }
    private fun shown(items: List<LaterItem>) = items.filter { it.deleted == null }.associate { it.id to it.text }

    @Test fun importThenTwoDevices() {
        // An older Marble (v3) wrote packs and marble/later: three notes, one done, one removed.
        val old = NotesSyncTest.Relay()
        val v3 = NotesSyncTest().let { t -> t.Device(old) }
        v3.add("0000000000000001", "Buy milk"); v3.add("0000000000000002", "Call Sam"); v3.add("0000000000000003", "Gone soon")
        v3.flush()
        v3.edit("0000000000000002") { it.copy(done = 1_000_100, updated = 1_000_100) }
        v3.edit("0000000000000003") { it.copy(text = "", deleted = 1_000_100, updated = 1_000_100) }
        v3.flush()
        // This phone after the update: reads the old notes (removals included) and imports them once.
        val reader = NotesSync(old.net, NotesSyncTest.Store(), { 1_000_200 }, emptyList())
        val read = runBlocking { reader.pull(emptyList(), full = true) }!! + reader.removals()
        assertEquals(3, read.size)
        val a = device("Pixel")
        assertEquals(3, a.import(read))
        assertEquals("only once", 0, a.import(read))
        assertTrue(a.checkImport())
        var aItems = a.pull(emptyList())!!
        assertEquals(mapOf("0000000000000001" to "Buy milk", "0000000000000002" to "Call Sam"), shown(aItems))
        assertNotNull(aItems.first { it.id == "0000000000000002" }.done)
        // The removed one went in as a deleted version: the vault "has it", so nothing brings it back.
        val removedEntry = VaultNotes.entryFor("0000000000000003")
        assertTrue(a.vault!!.state().entries[removedEntry]!!.deleted)

        // A second device finds the vault from the keyring and sees the same.
        val b = device("Mac")
        var bItems = b.pull(emptyList())!!
        assertEquals(shown(aItems), shown(bItems))

        // An edit on A reaches B.
        clock += 10
        aItems = aItems.map { if (it.id == "0000000000000001") it.copy(text = "Buy oat milk", updated = clock) else it }
        aItems = a.push(aItems)!!
        bItems = b.pull(bItems)!!
        assertEquals("Buy oat milk", shown(bItems)["0000000000000001"])

        // Both edit it at once: two versions on both, the same one first; B keeps A's side.
        clock += 10
        aItems = a.push(aItems.map { if (it.id == "0000000000000001") it.copy(text = "Buy oat milk (A)", updated = clock) else it })!!
        bItems = b.push(bItems.map { if (it.id == "0000000000000001") it.copy(text = "Buy oat milk (B)", updated = clock) else it })!!
        aItems = a.pull(aItems)!!; bItems = b.pull(bItems)!!
        val va = a.conflicts.value["0000000000000001"]!!; val vb = b.conflicts.value["0000000000000001"]!!
        assertEquals(2, va.size); assertEquals(va, vb)
        val sideA = vb.first { it.text == "Buy oat milk (A)" }
        assertTrue(b.resolve("0000000000000001", ver = sideA.ver))
        bItems = b.push(bItems)!!; aItems = a.pull(aItems)!!
        assertFalse(a.conflicts.value.containsKey("0000000000000001"))
        assertEquals("Buy oat milk (A)", shown(aItems)["0000000000000001"])

        // Removed on A: in Trash on B, with its words; restored from B, back on A.
        aItems = a.push(aItems.map { if (it.id == "0000000000000002") it.copy(text = "", deleted = clock, updated = clock + 1) else it })!!
        bItems = b.pull(bItems)!!
        assertFalse(shown(bItems).containsKey("0000000000000002"))
        val t = b.trash.value.first { it.id == "0000000000000002" }
        assertEquals("Call Sam", t.text)
        assertTrue(b.restore(t.entry)); bItems = b.push(bItems)!!
        aItems = a.pull(aItems)!!
        assertEquals("Call Sam", shown(aItems)["0000000000000002"])
        b.emptyTrash(); assertTrue(b.trash.value.isEmpty())

        // A note made here, too long to go inline: a blob, read back on B.
        val long = "lorem ipsum ".repeat(600)
        aItems = a.push(aItems + LaterItem("0000000000000009", long, clock, clock))!!
        bItems = b.pull(bItems)!!
        assertEquals(long, shown(bItems)["0000000000000009"])
        assertTrue(a.vault!!.state().entries[VaultNotes.entryFor("0000000000000009")]!!.shown.data!!.has("blob"))
    }
}
