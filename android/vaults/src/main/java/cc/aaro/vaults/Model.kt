// The wire objects of spec §3–§5: generations, certs, AUTH events and the reader set, commits and puts.
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import cc.aaro.keykin.core.utf8
import org.json.JSONArray
import org.json.JSONObject

/** A generation of the owner secret (§3.1). */
class Gen(val id: String, val secret: ByteArray, val prev: String?, val because: JSONObject) {
    fun json(): JSONObject = JSONObject().put("id", id).put("secret", hex(secret)).put("prev", prev ?: JSONObject.NULL).put("because", because)
    /** The id binds the secret and the parent; one that fails is ignored. */
    val ok get() = Keys.genId(secret, prev) == id
    fun triggers(): Set<String> = listOf("roots", "members", "grants").flatMap { k -> because.strs(k).map { "$k:$it" } }.toSet()
    companion object {
        fun of(o: JSONObject): Gen = Gen(o.hexOf("id", 16), unhex(o.hexOf("secret", 32)), o.strOrNull("prev")?.also { if (!isHex(it, 16)) throw Malformed("prev") }, o.objOr("because"))
        fun because(roots: Collection<String>, members: Collection<String>, grants: Collection<String>, fresh: Boolean = false) = JSONObject().apply {
            if (roots.isNotEmpty()) put("roots", JSONArray(roots.sorted().distinct()))
            if (members.isNotEmpty()) put("members", JSONArray(members.sorted().distinct()))
            if (grants.isNotEmpty()) put("grants", JSONArray(grants.sorted().distinct()))
            if (fresh) put("fresh", true)
        }
        /** Merged by id: one id is one secret and one parent; `because` lists merge by union (§3.1). */
        fun merge(a: Gen, b: Gen): Gen {
            val m = JSONObject()
            for (k in listOf("roots", "members", "grants")) { val u = (a.because.strs(k) + b.because.strs(k)).distinct().sorted(); if (u.isNotEmpty()) m.put(k, JSONArray(u)) }
            if (a.because.optBoolean("fresh") || b.because.optBoolean("fresh")) m.put("fresh", true)
            return Gen(a.id, a.secret, a.prev, m)
        }
    }
}

/** A CERT event's content (§3.4). Counts on its signature; plain canonical JSON. */
sealed class Cert(val ev: Ev) {
    class Device(ev: Ev, val root: String, val keykin: String?) : Cert(ev)
    class Sub(ev: Ev, val genesis: String, val n: Int, val dvk: String, val name: String, val storage: String) : Cert(ev)
    class Revoke(ev: Ev, val root: String, val keep: List<String>) : Cert(ev)
    companion object {
        /** Null for anything malformed or wrongly signed. */
        fun of(ev: Ev): Cert? = try {
            if (ev.kind != Kinds.CERT || !ev.valid()) null else {
                val o = obj(ev.content)
                when (o.str("type")) {
                    "device" -> Device(ev, o.hexOf("root", 32), o.strOrNull("keykin"))
                    "sub" -> {
                        val dvk = o.hexOf("dvk", 32); val storage = o.hexOf("storage", 32)
                        if (!Keys.verify(storage, th("nostr-vault/storage/1", unhex(dvk)), o.str("storage_sig"))) null
                        else Sub(ev, o.hexOf("genesis", 32), o.long("n").toInt(), dvk, o.optString("name"), storage)
                    }
                    "revoke" -> Revoke(ev, o.hexOf("root", 32), o.strs("keep").onEach { if (!isHex(it, 32)) throw Malformed("keep") })
                    else -> null
                }
            }
        } catch (_: Exception) { null }
    }
}

/** An AUTH event, decrypted (§4.3). */
class Auth(val ev: Ev, val body: JSONObject) {
    val type: String = body.str("type")
    data class Add(val reader: String, val after: List<String>)
    data class Remove(val reader: String, val prev: List<String>)
    val adds = body.arrOr("add").objs().map { Add(it.hexOf("reader", 32), it.strs("after").map { a -> if (a == "self") ev.id else a }) }
    val removes = body.arrOr("remove").objs().map { Remove(it.hexOf("reader", 32), it.strs("prev")) }
    val config: JSONObject? = body.objOrNull("config")
    val rekey: Boolean = body.bool("rekey")
    companion object {
        /** Null if it isn't a well-formed AUTH of this vault, signed by [owner] (or, for a leave, by the reader it removes). */
        fun of(ev: Ev, authKey: ByteArray, vaultId: String, owner: String): Auth? = try {
            if (ev.kind != Kinds.AUTH || !ev.valid()) null else {
                val a = Auth(ev, obj(Keys.decrypt(ev.content, authKey) ?: throw Malformed("decrypt")))
                when {
                    a.body.str("vault") != vaultId -> null
                    a.type == "leave" -> a.takeIf { it.removes.size == 1 && it.removes[0].reader == ev.pubkey && it.adds.isEmpty() && ev.pubkey != owner }
                    ev.pubkey != owner -> null
                    a.type == "genesis" -> a.takeIf { it.adds.isEmpty() && it.config != null }
                    a.type == "change" -> a.takeIf { (it.adds + it.removes.map { r -> Add(r.reader, emptyList()) }).none { x -> x.reader == owner } }
                    else -> null
                }
            }
        } catch (_: Exception) { null }
        fun seal(owner: ByteArray?, body: JSONObject, authKey: ByteArray) = canon(body).let { Keys.encrypt(it, authKey) }
    }
}

/** The reader set and ρ from the authority events (§3.1, §4.3). */
object Readers {
    class Result(val readers: List<String>, val ended: List<String>, val rho: String)

    fun of(auths: Collection<Auth>): Result {
        val all = auths.distinctBy { it.ev.id }
        data class R(val id: String, val reader: String, val prev: List<String>)
        data class A(val id: String, val reader: String, val after: List<String>)
        val removes = all.flatMap { a -> a.removes.map { R(a.ev.id, it.reader, it.prev) } }
        val adds = all.flatMap { a -> a.adds.map { A(a.ev.id, it.reader, it.after) } }
        val byId = removes.groupBy { it.id }
        // Does a remove named [x] reach remove [r] through its `prev` chain (itself included)?
        fun reaches(x: String, r: String, seen: HashSet<String> = HashSet()): Boolean {
            if (x == r) return true
            if (!seen.add(x)) return false
            return byId[x].orEmpty().any { it.prev.any { p -> reaches(p, r, seen) } }
        }
        fun covers(r: R, a: A) = r.reader == a.reader && a.after.none { reaches(it, r.id) }
        val readers = adds.filter { a -> removes.none { covers(it, a) } }.map { it.reader }.distinct().sorted()
        // A remove has effect when it covers an add that no remove coming before it covers: O comes before R when R's
        // `prev` chain reaches O; when neither reaches the other, the lower AUTH id comes first (§3.1, as of 4803658).
        fun before(o: R, r: R): Boolean {
            if (o.id == r.id) return false
            val rReachesO = r.prev.any { reaches(it, o.id) }; val oReachesR = o.prev.any { reaches(it, r.id) }
            return rReachesO || (!oReachesR && o.id < r.id)
        }
        val effect = removes.filter { r -> adds.any { a -> covers(r, a) && removes.none { o -> before(o, r) && covers(o, a) } } }.map { it.id }
        val rekeys = all.filter { it.rekey && it.type == "change" }.map { it.ev.id }
        val ended = (effect + rekeys).distinct().sorted()
        val rho = hex(sha256(utf8(canon(jobj("readers" to JSONArray(readers), "ended" to JSONArray(ended))))))
        return Result(readers, ended, rho)
    }

    /** The current config: the one no other names in `prev`, the lowest AUTH id among several (§4.3). */
    fun config(auths: Collection<Auth>): JSONObject? {
        val cs = auths.filter { it.config != null }.distinctBy { it.ev.id }
        val named = cs.flatMap { it.config!!.strs("prev") }.toSet()
        return cs.filter { it.ev.id !in named }.minByOrNull { it.ev.id }?.config
    }
}

/** A put (§5.3). [json] is the whole op as found, unknown members included. */
class Put(val json: JSONObject) {
    val entry: String = json.hexOf("entry", 16)
    val ver: String = json.hexOf("ver", 16)
    val supersedes: List<String> = json.getJSONArray("supersedes").strings()
    val meta: JSONObject = json.get("meta") as? JSONObject ?: throw Malformed("meta")
    val data: JSONObject? = json.objOrNull("data")
    val deleted: Boolean = json.get("deleted") as? Boolean ?: throw Malformed("deleted")
    init {
        if (json.optString("op") != "put") throw Malformed("op")
        if (!json.has("data")) throw Malformed("data")
        if (supersedes.any { !isHex(it, 16) } || supersedes != supersedes.sorted()) throw Malformed("supersedes")
        if (hashed(json) != ver) throw Malformed("ver doesn't hash")
    }
    val kind: String? get() = meta.optString("kind").ifEmpty { null }
    /** The collision order (§5.3): the lower SHA-256 of canonical JSON wins. */
    val order: String by lazy { hex(sha256(utf8(canon(json)))) }

    companion object {
        fun hashed(op: JSONObject): String {
            val o = op.copy(); o.remove("op"); o.remove("ver")
            return thHex("nostr-vault/ver/1", utf8(canon(o)))
        }
        fun of(o: JSONObject): Put? = try { Put(o) } catch (_: Exception) { null }
        fun make(entry: String, supersedes: List<String>, meta: JSONObject, data: JSONObject?, deleted: Boolean, extra: JSONObject? = null): Put {
            val o = extra?.copy() ?: JSONObject()
            o.put("entry", entry).put("supersedes", JSONArray(supersedes.distinct().sorted())).put("meta", meta).put("data", data ?: JSONObject.NULL).put("deleted", deleted)
            val ver = hashed(o)
            return Put(JSONObject().put("op", "put").put("ver", ver).also { r -> o.keys().forEach { r.put(it, o.get(it)) } })
        }
    }
}

/** A commit (§5.1): its string as signed, and what it says. */
class Commit(val c: String, val sig: String) {
    val id: String = hex(sha256(utf8(c)))
    val o: JSONObject = obj(c)
    val genesis = o.hexOf("genesis", 32)
    val dvk = o.hexOf("dvk", 32)
    val seq: Long = o.long("seq").also { if (it < 0) throw Malformed("seq") }
    val prev: String? = o.strOrNull("prev")?.also { if (!isHex(it, 32)) throw Malformed("prev") }
    val kid = o.hexOf("kid", 16)
    val time = o.long("time")
    val ops: List<JSONObject> = o.getJSONArray("ops").let { a -> List(a.length()) { a.opt(it) as? JSONObject ?: JSONObject() } }
    init {
        // Read as the JS engine reads (kist core.js readCommit): up to four times §5.1's 12 KiB: Marble Web (kist
        // sync.js) makes a rekey commit with every revocation in it however many there are, and dropping it would hide
        // that device's move to a new epoch here. This engine writes at most 12 KiB (make).
        if (utf8(c).size > READ_MAX) throw Malformed("commit too large")
        if ((seq == 0L) != (prev == null)) throw Malformed("prev")
    }
    fun signedOK() = Keys.verify(dvk, th("nostr-vault/commit/1", unhex(id)), sig)
    fun json(): JSONObject = JSONObject().put("c", c).put("sig", sig)

    companion object {
        /** §5.1: a commit string is at most 12 KiB. */
        const val MAX = 12 * 1024
        private const val READ_MAX = 4 * MAX
        /** Null if malformed or the signature fails (§10, 2). */
        fun of(o: JSONObject?): Commit? = try { o?.let { Commit(it.str("c"), it.hexOf("sig", 64)) }?.takeIf { it.signedOK() } } catch (_: Exception) { null }
        fun make(dvkSecret: ByteArray, genesis: String, seq: Long, prev: String?, kid: String, time: Long, ops: List<JSONObject>): Commit {
            val c = canon(jobj("genesis" to genesis, "dvk" to Keys.pub(dvkSecret), "seq" to seq, "prev" to prev, "kid" to kid, "time" to time, "ops" to JSONArray(ops)))
            if (utf8(c).size > MAX) throw Malformed("commit over 12 KiB")
            val id = sha256(utf8(c))
            return Commit(c, Keys.sign(th("nostr-vault/commit/1", id), dvkSecret))
        }
    }
}
