// Hashes, HKDF and every derived key of spec §3, and the secp256k1 and NIP-44 calls vaults makes with local keys.
package cc.aaro.vaults

import cc.aaro.keykin.core.Nip44
import cc.aaro.keykin.core.Secp
import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import cc.aaro.keykin.core.utf8
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun cat(vararg a: ByteArray): ByteArray { val o = ByteArray(a.sumOf { it.size }); var i = 0; for (x in a) { x.copyInto(o, i); i += x.size }; return o }
fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }
/** HKDF-SHA256, empty salt, 32 bytes (spec §2). */
fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray = hmac(hmac(ByteArray(32), ikm), info, byteArrayOf(1))
/** The tagged hash `SHA-256(utf8(tag) ‖ 0x00 ‖ m)`. */
fun th(tag: String, vararg m: ByteArray): ByteArray = sha256(utf8(tag), byteArrayOf(0), *m)
fun thHex(tag: String, vararg m: ByteArray, chars: Int = 32) = hex(th(tag, *m)).substring(0, chars)

private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
fun scalarOK(b: ByteArray): Boolean { val k = BigInteger(1, b); return k.signum() > 0 && k < N }

object Keys {
    // §3.1
    fun genId(v: ByteArray, prev: String?): String = thHex("nostr-vault/gen/1", v, prev?.let(::unhex) ?: ByteArray(0))
    fun epoch(v: ByteArray, vaultId: String, rho: String) = hkdf(v, cat(utf8("nostr-vault/epoch/1"), unhex(vaultId), unhex(rho)))
    fun kid(k: ByteArray) = thHex("nostr-vault/kid/1", k)
    fun opsKey(k: ByteArray, vaultId: String) = hkdf(k, cat(utf8("nostr-vault/ops/1"), unhex(vaultId)))
    fun locator(k: ByteArray, vaultId: String) = hex(hkdf(k, cat(utf8("nostr-vault/loc/1"), unhex(vaultId)))).substring(0, 32)
    fun authKey(k0: ByteArray, vaultId: String) = hkdf(k0, cat(utf8("nostr-vault/auth/1"), unhex(vaultId)))
    fun dedupKey(k0: ByteArray, vaultId: String) = hkdf(k0, cat(utf8("nostr-vault/ph/1"), unhex(vaultId)))
    fun inboxSecret(v: ByteArray, vaultId: String, rho: String): ByteArray {
        var c = 0
        while (true) { val s = hkdf(v, cat(utf8("nostr-vault/inbox/1"), unhex(vaultId), unhex(rho), u32(c))); if (scalarOK(s)) return s; c++ }
    }
    /** A scalar from HKDF: if it isn't one, a counter byte is appended to the info and it's tried again (§3.3). */
    fun scalar(ikm: ByteArray, info: ByteArray): ByteArray {
        var s = hkdf(ikm, info); var c = 0
        while (!scalarOK(s)) { s = hkdf(ikm, cat(info, byteArrayOf(c.toByte()))); c++ }
        return s
    }
    // §3.3
    fun dvk(root: ByteArray, genesis: String, n: Int) = scalar(root, cat(utf8("nostr-vault/dvk/1"), unhex(genesis), u32(n)))
    fun storage(dvk: ByteArray) = scalar(dvk, utf8("nostr-vault/blossom/1"))
    // §9.1
    fun shardKey(dedup: ByteArray, plain: ByteArray) = hkdf(dedup, cat(utf8("nostr-vault/shard/1"), sha256(plain)))

    fun newSecret(): ByteArray { while (true) { val s = random(32); if (scalarOK(s)) return s } }
    fun pub(sk: ByteArray): String = Secp.pub(sk)
    fun sign(msg32: ByteArray, sk: ByteArray): String = Secp.schnorrSign(msg32, sk)
    fun verify(pubX: String, msg32: ByteArray, sig: String): Boolean = isHex(sig, 64) && isHex(pubX, 32) && Secp.schnorrVerify(sig, msg32, pubX)
    /** The NIP-44 v2 conversation key between a secret and an x-only public key. */
    fun conv(sk: ByteArray, pubX: String): ByteArray = Nip44.conversationKey(Secp.ecdhX(sk, pubX))
    fun encrypt(text: String, key: ByteArray, nonce: ByteArray = random(32)): String = Nip44.encrypt(text, key, nonce)
    /** Null when it doesn't decrypt. */
    fun decrypt(payload: String, key: ByteArray): String? = try { Nip44.decrypt(payload, key) } catch (_: Exception) { null }

    /**
     * A wrap or seal (§3.5, §3.6): NIP-44 between a fresh ephemeral secret and [to], over [text].
     * Only [to]'s key opens it again; nothing is kept here.
     */
    fun seal(to: String, text: String): JSONPair {
        val eph = newSecret()
        try { return JSONPair(pub(eph), encrypt(text, conv(eph, to))) } finally { eph.fill(0) }
    }
    data class JSONPair(val eph: String, val ct: String)
}
