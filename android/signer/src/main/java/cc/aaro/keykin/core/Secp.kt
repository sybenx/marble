// secp256k1 through libsecp256k1 (constant-time): member keys, sealed values' shared secret, and the
// few point sums key generation and messages need.
package cc.aaro.keykin.core

import fr.acinq.secp256k1.Secp256k1
import java.math.BigInteger

object Secp {
    /** The x-only public key of a secret key, hex. */
    fun pub(sk: ByteArray): String = hex(Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(sk)).copyOfRange(1, 33))
    fun schnorrSign(msg32: ByteArray, sk: ByteArray): String = hex(Secp256k1.signSchnorr(msg32, sk, random(32)))
    fun schnorrVerify(sig: String, msg32: ByteArray, pubX: String): Boolean = try { Secp256k1.verifySchnorr(unhex(sig), msg32, unhex(pubX)) } catch (_: Exception) { false }
    /** x of `sk · P`, P the even-y point with x `pubX`. */
    fun ecdhX(sk: ByteArray, pubX: String): ByteArray = Secp256k1.pubKeyCompress(Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(unhex("02$pubX")), sk)).copyOfRange(1, 33)
    private fun parse(compHex: String) = Secp256k1.pubkeyParse(unhex(compHex))
    private fun comp(p: ByteArray) = hex(Secp256k1.pubKeyCompress(p))
    /** a + b and a − b, compressed hex in and out. */
    fun add(a: String, b: String) = comp(Secp256k1.pubKeyCombine(arrayOf(parse(a), parse(b))))
    fun sub(a: String, b: String) = comp(Secp256k1.pubKeyCombine(arrayOf(parse(a), Secp256k1.pubKeyNegate(parse(b)))))
    /** k · P, compressed hex. */
    fun mul(k: BigInteger, p: String) = comp(Secp256k1.pubKeyTweakMul(parse(p), unhex(scalarHex(k))))
    fun mulG(k: BigInteger) = comp(Secp256k1.pubkeyCreate(unhex(scalarHex(k))))
}
