package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Relays in memory: the newest event at each addressable address, as real relays keep. */
class FakeRelays : RelayNet {
    val events = ConcurrentHashMap<String, Ev>()
    var down = false
    private fun addr(e: Ev) = if (e.kind in 30000..39999) "${e.kind}:${e.pubkey}:${e.tag("d")}" else null
    @Synchronized override fun publish(relays: List<String>, ev: Ev): Set<String> {
        if (down || !ev.valid()) return emptySet()
        addr(ev)?.let { a -> events.values.firstOrNull { addr(it) == a }?.let { o -> if (o.createdAt > ev.createdAt || (o.createdAt == ev.createdAt && o.id < ev.id)) return relays.toSet(); events.remove(o.id) } }
        events[ev.id] = ev
        return relays.toSet()
    }
    override fun query(relays: List<String>, filter: JSONObject, timeoutMs: Long): Answer {
        if (down) return Answer(emptyList(), emptySet())
        val kinds = filter.optJSONArray("kinds")?.let { a -> List(a.length()) { a.getInt(it) } }
        val authors = filter.optJSONArray("authors")?.strings()
        val tagF = filter.keys().asSequence().filter { it.startsWith("#") }.associate { it.substring(1) to filter.getJSONArray(it).strings() }
        val out = events.values.filter { e -> (kinds == null || e.kind in kinds) && (authors == null || e.pubkey in authors) && tagF.all { (t, vs) -> e.tags.any { it.size > 1 && it[0] == t && it[1] in vs } } }
        return Answer(out, relays.toSet())
    }
}

/** Blossom servers in memory, keeping a blob per owner (§6.4). */
class FakeBlobs : Blobs {
    val held = ConcurrentHashMap<String, Pair<ByteArray, MutableSet<String>>>() // server/sha
    private fun who(auth: String) = Ev.of(obj(String(java.util.Base64.getDecoder().decode(auth.removePrefix("Nostr "))))).pubkey
    /** Servers that answer every upload with this status (403: refuses the key, 415: refuses the type). */
    val refuse = ConcurrentHashMap<String, Int>()
    val uploads = java.util.concurrent.atomic.AtomicInteger()
    override fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Int {
        refuse[server]?.let { return it }
        if (hex(sha256(bytes)) != sha256) return -2
        uploads.incrementAndGet()
        held.getOrPut("$server/$sha256") { bytes to HashSet() }.second += who(auth); return 200
    }
    override fun get(server: String, sha256: String) = held["$server/$sha256"]?.first
    override fun delete(server: String, sha256: String, auth: String): Boolean {
        held["$server/$sha256"]?.let { it.second -= who(auth); if (it.second.isEmpty()) held.remove("$server/$sha256") }; return true
    }
}

/** A Keykin v2 box in miniature: members, blocks, cycles, revoked grants; it answers only members not blocked. */
class FakeBox(val npubSecret: ByteArray) {
    val npub = Keys.pub(npubSecret)
    val members = LinkedHashMap<String, Boolean>() // key → blocked
    var epoch = 0
    val revokedGrants = HashSet<String>()
    fun add(key: String) { members[key] = false }
    fun block(key: String) { members[key] = true }
    /** Blocked members are dropped for good. */
    fun cycle() { members.entries.removeIf { it.value }; epoch++ }
    fun ok(key: String) = members[key] == false
}

class FakeAccount(val box: FakeBox, val me: String, val keykin: Boolean = true) : Account {
    override val npub get() = box.npub
    /** The account's kind 10063 list, as the app would hand it over (unnormalized here, on purpose). */
    var blossom: List<String> = emptyList()
    override fun servers() = blossom
    var signs = 0; var ecdhs = 0
    override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Ev {
        if (keykin && !box.ok(me)) throw IllegalStateException("blocked")
        signs++; return Ev.sign(box.npubSecret, createdAt, kind, tags, content)
    }
    override fun openSelf(eph: String, ct: String): String? {
        if (keykin && !box.ok(me)) return null
        ecdhs++; return Keys.decrypt(ct, Keys.conv(box.npubSecret, eph))
    }
    override fun keykin(): KeykinView? = if (!keykin) null else KeykinView(me, box.epoch, if (box.ok(me)) LinkedHashMap(box.members) else null, box.revokedGrants.toSet())
}

class MapStore : Store {
    val m = ConcurrentHashMap<String, String>()
    override fun get(k: String) = m[k]
    override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
}

class TestClock(var t: Long = 1_793_750_000) : Clock { override fun now() = t }

/** A world: one relay set, one pair of servers, one box. */
class World {
    val relays = FakeRelays(); val blobs = FakeBlobs(); val clock = TestClock()
    val box = FakeBox(Keys.newSecret())
    fun device(name: String, keykin: Boolean = true): Pair<Vaults, FakeAccount> {
        val member = Keys.pub(Keys.newSecret()); box.add(member)
        val acc = FakeAccount(box, member, keykin)
        return Vaults(acc, relays, blobs, MapStore(), clock, { listOf("wss://a", "wss://b") }, name) to acc
    }
}

fun note(id: String, text: String, at: Long, done: Boolean = false) =
    JSONObject().put("kind", "note").put("created", at).put("title", "").put("tags", JSONArray()).put("done", done).put("pinned", false).put("id", id).put("updated", at)
fun entryOf(id: String) = thHex("marble/later/1", id.toByteArray())
