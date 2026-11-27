package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.unhex
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import java.io.File

/**
 * Interop with Marble Web's core, on the local vaults-test-server (7447 relay and Blossom, 7448 Blossom). Skipped unless
 * VAULTS_INTEROP is set:
 *   open:<nsec hex>[:<note id>=<text>,...][:done=<id>]   open Web's vault, print the notes, edit and tick
 *   create                                                make a notes vault with a fresh test key, print its nsec
 * The device's store is kept in build/interop-<npub>, so a second run is the same device.
 */
class InteropTest {
    // (VAULTS_RELAY and VAULTS_SERVERS move them, as tools/notes-sync-smoke.sh does)
    private val relays = listOf(System.getenv("VAULTS_RELAY") ?: "ws://127.0.0.1:7447")
    private val servers = System.getenv("VAULTS_SERVERS")?.split(",") ?: listOf("http://127.0.0.1:7447", "http://127.0.0.1:7448")

    private class FileStore(val dir: File) : Store {
        init { dir.mkdirs() }
        private fun f(k: String) = File(dir, k.replace('/', '_'))
        override fun get(k: String) = f(k).takeIf { it.exists() }?.readText()
        override fun put(k: String, v: String?) { if (v == null) f(k).delete() else f(k).writeText(v) }
    }
    private fun vaults(sk: ByteArray): Vaults {
        // VAULTS_BLOSSOM: the test account's kind 10063 list, comma-separated, as published (unnormalized).
        val blossom = System.getenv("VAULTS_BLOSSOM")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val net = OkNet(); val acc = NsecAccount(sk, blossom)
        return Vaults(acc, net, net, FileStore(File("build/interop-" + acc.npub.take(12))), WallClock, { relays }, "Android engine (JVM)")
    }
    private fun show(v: Vault) {
        for (e in v.state().entries.values.sortedBy { it.shown.meta.optString("id") }) {
            val p = e.shown
            val text = p.data?.takeIf { it.has("text") }?.optString("text") ?: p.data?.optJSONObject("blob")?.let { BlobRef.of(it) }?.let { v.fetch(it)?.let(::String) }
            println("NOTE ${p.meta.optString("id")} deleted=${e.deleted} done=${p.meta.optBoolean("done")} versions=${e.current.size} text=${text?.take(60)?.replace("\n", "\\n")} (${text?.length})")
        }
        println("DEVICES " + v.devices().joinToString { "${it.name} trusted=${it.trusted}" })
        println("DEBUG " + v.debug())
    }
    private fun note(v: Vault, id: String): Put? = v.state().entries[entryOf(id)]?.shown

    @Test fun interop() {
        val mode = System.getenv("VAULTS_INTEROP") ?: System.getProperty("vaults.interop")
        assumeTrue(mode != null)
        val parts = mode!!.split(":")
        when (parts[0]) {
            "open" -> {
                val vs = vaults(try { unhex(parts[1]) } catch (e: Exception) { error("bad key in VAULTS_INTEROP: $mode") })
                val p = vs.findOwned("notes", vs.keyrings()!!) ?: error("no notes vault")
                val v = vs.join(p)
                check(v.catchUp())
                show(v)
                val edits = parts.drop(2).flatMap { it.split(",") }.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
                val now = WallClock.now()
                for ((k, value) in edits) {
                    // "R<id>=<text>": settle a conflict, one put superseding every current version (§5.4).
                    if (k.startsWith("R")) {
                        val e = v.state().entries[entryOf(k.drop(1))] ?: error("no note")
                        val m = e.shown.meta.copy().put("updated", now)
                        v.enqueue(listOf(Put.make(e.id, e.current.map { it.ver }, m, JSONObject().put("text", value), false).json)); continue
                    }
                    val ids = if (k == "done") listOf(value) else listOf(k)
                    val base = note(v, ids[0]) ?: error("no note ${ids[0]}")
                    val meta = base.meta.copy().put("updated", now)
                    var data = base.data
                    if (k == "done") meta.put("done", true).put("done_at", now) else data = JSONObject().put("text", value)
                    v.enqueue(listOf(Put.make(base.entry, listOf(base.ver), meta, data, false).json))
                }
                if (edits.isNotEmpty()) { check(v.sync()); println("EDITED"); show(v) }
            }
            "probe" -> for (server in parts.drop(1).joinToString(":").split(",")) {
                // §6.4's probe against a real server: 64 random bytes, deleted afterwards.
                val st = Storage(OkNet(), WallClock); val net = OkNet()
                val a = Keys.newSecret(); val b = Keys.newSecret(); val bytes = cc.aaro.keykin.core.random(64); val sha = hex(cc.aaro.keykin.core.sha256(bytes))
                fun up(k: ByteArray) = try { net.http.newCall(okhttp3.Request.Builder().url("$server/upload").put(okhttp3.RequestBody.create("application/octet-stream".toMediaType(), bytes)).header("Authorization", st.auth(k, "upload", listOf(sha), server)).build()).execute().use { "${it.code} ${it.body?.string()?.take(120)}" } } catch (e: Exception) { e.toString() }
                fun del(k: ByteArray) = try { net.http.newCall(okhttp3.Request.Builder().url("$server/$sha").delete().header("Authorization", st.auth(k, "delete", listOf(sha), server)).build()).execute().use { "${it.code} ${it.body?.string()?.take(120)}" } } catch (e: Exception) { e.toString() }
                fun get() = try { net.http.newCall(okhttp3.Request.Builder().url("$server/$sha").build()).execute().use { "${it.code}" } } catch (e: Exception) { e.toString() }
                println("PROBE $server upA=${up(a)} | upB=${up(b)} | get=${get()} | delB=${del(b)} | get=${get()} | delA=${del(a)} | get=${get()}")
            }
            "create" -> {
                val sk = Keys.newSecret()
                val vs = vaults(sk)
                // The account's list, published on the test relay so Web finds it too; the vault's config names it.
                val own = vs.account.servers()
                if (own.isNotEmpty()) check(OkNet().publish(relays, Ev.sign(sk, WallClock.now(), 10063, own.map { listOf("server", it) }, "")).isNotEmpty())
                val v = vs.create("notes", "Notes", relays, Storage.normalize(own).ifEmpty { servers })
                val t = WallClock.now()
                fun mk(id: String, text: String, done: Boolean = false): JSONObject {
                    val m = JSONObject().put("kind", "note").put("created", t).put("title", "").put("tags", org.json.JSONArray()).put("done", done).put("pinned", false).put("id", id).put("updated", t)
                    if (done) m.put("done_at", t)
                    val data = if (text.toByteArray().size > 4096) JSONObject().put("blob", v.upload(text.toByteArray(), need = 2)!!.json) else JSONObject().put("text", text)
                    return Put.make(entryOf(id), emptyList(), m, data, false).json
                }
                // An import's batch: 300 notes and two removals, as the v3 import writes them.
                val batch = (1..300).map { mk("a%015x".format(it), "Imported note $it") } +
                    listOf("b000000000000001", "b000000000000002").map { Put.make(entryOf(it), emptyList(), JSONObject().put("kind", "note"), null, true).json }
                v.enqueue(batch); check(v.sync())
                v.enqueue(listOf(mk("c000000000000001", "Buy milk"), mk("c000000000000002", "Done one", done = true), mk("c000000000000003", "Long note: " + "lorem ipsum ".repeat(500))))
                check(v.sync())
                check(v.writeCheckpoint())
                v.enqueue(listOf(mk("c000000000000004", "After the checkpoint")))
                check(v.sync())
                // "create:<n>": then n other devices join and are each reported lost here, and one more note is written:
                // the move to each new generation carries every revocation (past 12 KiB at about 16).
                val removals = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (removals > 0) {
                    val tmp = java.nio.file.Files.createTempDirectory("interop-removed").toFile()
                    repeat(removals) { i ->
                        val other = Vaults(NsecAccount(sk), OkNet(), OkNet(), FileStore(File(tmp, "d$i")), WallClock, { relays }, "Removed $i")
                        val o = other.join(other.findOwned("notes", other.keyrings()!!)!!); check(o.sync())
                        check(v.catchUp()); v.reportLost(other.rootPub)
                    }
                    tmp.deleteRecursively()
                    v.enqueue(listOf(mk("c000000000000005", "After $removals removals")))
                    check(v.sync())
                }
                show(v)
                println("REFUSED " + vs.storage.refused)
                println("CREATED nsec=${hex(sk)} npub=${vs.account.npub} vault=${v.id}")
            }
        }
    }
}
