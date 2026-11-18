// Blobs (spec §6): every blob padded, encrypted with its own key in 64 KiB AES-GCM chunks, cut into Blossom parts.
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import cc.aaro.keykin.core.utf8
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A blob ref (§6.2). [json] keeps any member this version doesn't know. */
class BlobRef(val json: JSONObject) {
    val key: String = json.hexOf("key", 32)
    val size: Long = json.long("size").also { if (it < 0) throw Malformed("size") }
    val chunks: Int = json.long("chunks").toInt().also { if (it < 1) throw Malformed("chunks") }
    val parts: List<String> = json.strs("parts").onEach { if (!isHex(it, 32)) throw Malformed("part") }
    init {
        if (json.optString("alg") != "nv1") throw Malformed("alg")
        val count = Nv1.chunkCount(Nv1.padme(size))
        if (parts.size.toLong() != (count + chunks - 1) / chunks) throw Malformed("parts don't match size")
    }
    /** The bytes each part must have, from the size (§6.1). */
    fun partSizes(): List<Long> {
        val l = Nv1.padme(size); val count = Nv1.chunkCount(l)
        val lastChunk = l - Nv1.CHUNK * (count - 1) + 16
        return parts.indices.map { i ->
            val first = i.toLong() * chunks; val n = minOf(chunks.toLong(), count - first)
            if (first + n == count) (n - 1) * (Nv1.CHUNK + 16) + lastChunk else n * (Nv1.CHUNK + 16)
        }
    }
    override fun equals(other: Any?) = other is BlobRef && canon(other.json) == canon(json)
    override fun hashCode() = canon(json).hashCode()
    companion object {
        fun of(o: Any?): BlobRef? = (o as? JSONObject)?.takeIf { it.optString("alg") == "nv1" }?.let { try { BlobRef(it) } catch (_: Malformed) { null } }
    }
}

object Nv1 {
    const val CHUNK = 65536L
    private val AAD = utf8("nostr-vault/blob/1")

    fun padme(len: Long): Long {
        if (len <= 4096) return 4096
        val e = 63 - java.lang.Long.numberOfLeadingZeros(len)
        val s = (31 - Integer.numberOfLeadingZeros(e)) + 1
        val m = (1L shl (e - s)) - 1
        return (len + m) and m.inv()
    }
    fun chunkCount(l: Long) = (l + CHUNK - 1) / CHUNK
    private fun nonce(i: Long, last: Boolean) = ByteArray(12).also { n -> for (k in 0 until 7) n[10 - k] = (i ushr (8 * k)).toByte(); n[11] = if (last) 1 else 0 }

    class Sealed(val ref: BlobRef, val parts: List<ByteArray>)

    /** [plain] encrypted under a fresh key (or [key], for a shard), cut into parts of [chunks] chunks. */
    fun seal(plain: ByteArray, key: ByteArray = random(32), chunks: Int = 127): Sealed {
        val l = padme(plain.size.toLong()).toInt()
        val padded = plain.copyOf(l)
        val count = chunkCount(l.toLong()).toInt()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(i.toLong(), i == count - 1)))
            c.updateAAD(AAD)
            out += c.doFinal(padded, i * CHUNK.toInt(), minOf(CHUNK.toInt(), l - i * CHUNK.toInt()))
        }
        val parts = out.chunked(chunks).map { cat(*it.toTypedArray()) }
        val ref = JSONObject().put("alg", "nv1").put("key", hex(key)).put("size", plain.size).put("chunks", chunks)
            .put("parts", JSONArray(parts.map { hex(sha256(it)) }))
        return Sealed(BlobRef(ref), parts)
    }

    /** The plaintext, or null if a part's hash, a GCM tag or the last-chunk flag is wrong (§6.2). */
    fun open(ref: BlobRef, parts: List<ByteArray>): ByteArray? = try {
        if (parts.size != ref.parts.size || parts.indices.any { hex(sha256(parts[it])) != ref.parts[it] }) null
        else {
            val all = cat(*parts.toTypedArray())
            val count = chunkCount(padme(ref.size)).toInt()
            val step = (CHUNK + 16).toInt()
            if (all.size.toLong() != ref.partSizes().sum()) null
            else {
                val out = java.io.ByteArrayOutputStream()
                for (i in 0 until count) {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(unhex(ref.key), "AES"), GCMParameterSpec(128, nonce(i.toLong(), i == count - 1)))
                    c.updateAAD(AAD)
                    out.write(c.doFinal(all, i * step, minOf(step, all.size - i * step)))
                }
                out.toByteArray().copyOf(ref.size.toInt())
            }
        }
    } catch (_: Exception) { null }

    /** One part checked alone against its hash (for a fetch that tries the next server). */
    fun partOK(ref: BlobRef, i: Int, bytes: ByteArray) = hex(sha256(bytes)) == ref.parts[i]
}
