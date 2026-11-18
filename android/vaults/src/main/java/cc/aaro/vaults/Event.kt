package cc.aaro.vaults

import cc.aaro.keykin.core.eventId
import cc.aaro.keykin.core.unhex
import org.json.JSONArray
import org.json.JSONObject

/** A Nostr event (NIP-01), as vaults reads and writes them. */
data class Ev(val id: String, val pubkey: String, val createdAt: Long, val kind: Int, val tags: List<List<String>>, val content: String, val sig: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("pubkey", pubkey).put("created_at", createdAt).put("kind", kind)
        .put("tags", JSONArray(tags.map { JSONArray(it) })).put("content", content).put("sig", sig)
    fun tag(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)
    /** The id is the hash of the event, and the signature is the pubkey's over it. */
    fun valid(): Boolean = try { isHex(pubkey, 32) && eventId(pubkey, createdAt, kind, tags, content) == id && Keys.verify(pubkey, unhex(id), sig) } catch (_: Exception) { false }

    companion object {
        fun of(o: JSONObject): Ev = try {
            val t = o.getJSONArray("tags")
            Ev(o.hexOf("id", 32), o.hexOf("pubkey", 32), o.long("created_at"), o.long("kind").toInt(), List(t.length()) { t.getJSONArray(it).strings() }, o.str("content"), o.hexOf("sig", 64))
        } catch (e: Malformed) { throw e } catch (e: Exception) { throw Malformed("event: ${e.message}") }
        fun unsigned(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String) =
            Ev(eventId(pubkey, createdAt, kind, tags, content), pubkey, createdAt, kind, tags, content, "")
        fun sign(sk: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Ev {
            val u = unsigned(Keys.pub(sk), createdAt, kind, tags, content)
            return u.copy(sig = Keys.sign(unhex(u.id), sk))
        }
    }
}

/** Working kind numbers (§4.7). */
object Kinds { const val HEAD = 30810; const val KEYRING = 30811; const val CONTRIB = 4813; const val AUTH = 4810; const val CERT = 4811; const val INVITE = 4812; const val BLOSSOM_AUTH = 24242 }
