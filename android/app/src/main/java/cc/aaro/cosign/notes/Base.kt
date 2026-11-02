package cc.aaro.cosign.notes

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Option I (v3 §10) and the Blossom file format (§7): a big import as one encrypted file, the "base", that packs overlay.

/** HKDF-SHA256 (RFC 5869), no salt, 32 bytes: how the Blossom keys come from K_tag. */
fun hkdf(ikm: ByteArray, info: String): ByteArray {
    val prk = javax.crypto.Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ikm) }
    return javax.crypto.Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); doFinal(info.toByteArray() + 1) }
}

/**
 * A file on Blossom (§7): a 12-byte nonce, then AES-256-GCM of (a 4-byte big-endian length, the body, zeros up to the
 * next 32 KiB, or Padmé steps above 1 MiB), with AAD = the id's UTF-8 then `updated` as 8 bytes big-endian.
 */
object Sealed {
    const val STEP = 32 * 1024
    const val ALG = "aes-256-gcm/v1"

    /** How long the padded plaintext is, for [n] bytes (length prefix included). */
    fun padded(n: Int): Int {
        if (n <= 1 shl 20) return ((n + STEP - 1) / STEP).coerceAtLeast(1) * STEP
        // Padmé: at most 12% more, leaking O(log log n) bits of the size.
        val e = 31 - Integer.numberOfLeadingZeros(n)
        val s = 31 - Integer.numberOfLeadingZeros(e) + 1
        val mask = (1 shl (e - s)) - 1
        return (n + mask) and mask.inv()
    }

    private fun aad(id: String, updated: Long) = id.toByteArray() + ByteBuffer.allocate(8).putLong(updated).array()

    fun seal(body: ByteArray, id: String, updated: Long, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val plain = ByteArray(padded(4 + body.size))
        ByteBuffer.wrap(plain).putInt(body.size).put(body)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad(id, updated)) }
        return nonce + c.doFinal(plain)
    }

    /** The body, or null if the tag doesn't verify or the length is wrong. */
    fun open(file: ByteArray, id: String, updated: Long, key: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, file, 0, 12)); updateAAD(aad(id, updated)) }
        val plain = c.doFinal(file, 12, file.size - 12)
        val n = ByteBuffer.wrap(plain).int
        if (n < 0 || n > plain.size - 4) null else plain.copyOfRange(4, 4 + n)
    } catch (_: Exception) { null }
}

/**
 * The pointer to a base file (`"base": {sha256, size, key, alg, servers, updated}`), in its own small event whose `d`
 * is [Layout.baseD]. The file holds `{"v":3,"kid","items":[…],"markers":[…]}`: every note when it was made.
 */
data class BasePointer(val sha256: String, val size: Long, val key: String, val servers: List<String>, val updated: Long, val kid: String) {
    fun toJson(): String = JSONObject().put("v", 3).put("kid", kid).put("base", JSONObject().put("sha256", sha256).put("size", size).put("key", key)
        .put("alg", Sealed.ALG).put("servers", JSONArray(servers)).put("updated", updated)).toString()
    companion object {
        fun parse(json: String?): BasePointer? = try {
            val o = JSONObject(json ?: return null); val b = o.getJSONObject("base")
            if (b.optString("alg") != Sealed.ALG) null
            else BasePointer(b.getString("sha256"), b.getLong("size"), b.getString("key"),
                b.optJSONArray("servers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), b.getLong("updated"), o.optString("kid"))
        } catch (_: Exception) { null }
    }
}

/** The base's own contents. */
object BaseFile {
    const val ID = "base"
    fun body(v: Vault): ByteArray = JSONObject().put("v", 3).put("kid", v.layout.kid)
        .put("items", JSONArray(v.notes.values.map { it.toJson() })).put("markers", JSONArray(v.markers.values.map { it.toJson() })).toString().toByteArray()
    fun parse(body: ByteArray, kid: String): Pair<List<Note>, List<Marker>>? = try {
        val o = JSONObject(String(body))
        if (o.optInt("v") != 3 || o.optString("kid") != kid) null
        else {
            val items = o.optJSONArray("items"); val markers = o.optJSONArray("markers")
            (0 until (items?.length() ?: 0)).mapNotNull { items!!.optJSONObject(it)?.let(Note::of) } to
                (0 until (markers?.length() ?: 0)).mapNotNull { markers!!.optJSONObject(it)?.let(Marker::of) }
        }
    } catch (_: Exception) { null }
}

/** The base under this vault: notes not held here are taken without making their packs go out (packs overlay it, §10 I). */
fun Vault.under(notes: List<Note>, markers: List<Marker>) {
    for (m in markers) if (this.markers[m.id] == null) { this.markers[m.id] = m; this.notes.remove(m.id) }
    for (n in notes) {
        if (n.id in this.markers) continue
        val have = this.notes[n.id]
        if (have == null) { this.notes[n.id] = n; continue }
        // Held here too: the base's copy if it's newer throughout; both, merged and sent, if each side has something newer.
        val out = Merge.notes(have, n)
        when {
            out.size == 1 && out[0].same(have) -> {}
            out.size == 1 && out[0].same(n) -> this.notes[n.id] = out[0]
            else -> take(n)
        }
    }
}
