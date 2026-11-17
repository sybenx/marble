// Sealed values between two member keys (SPEC §5): ECDH on the member keys, SHA-256("keykin2-seal" ‖ x)
// as the key, XChaCha20-Poly1305 with a random 24-byte nonce, the purpose as associated data.
// Android has ChaCha20-Poly1305 but not the X variant, so HChaCha20 is done here: it turns the key and
// the first 16 nonce bytes into a subkey, and the rest is the platform's cipher.
package cc.aaro.keykin.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Seal {
    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun hchacha(key: ByteArray, nonce16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[1] = 0x3320646e; x[2] = 0x79622d32; x[3] = 0x6b206574
        for (i in 0 until 8) x[4 + i] = le(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le(nonce16, i * 4)
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) { qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15); qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14) }
        val out = ByteArray(32)
        for ((n, w) in (listOf(0, 1, 2, 3, 12, 13, 14, 15)).withIndex()) for (k in 0 until 4) out[n * 4 + k] = (x[w] ushr (8 * k)).toByte()
        return out
    }
    private fun cipher(mode: Int, key: ByteArray, nonce24: ByteArray, why: String): Cipher {
        val sub = hchacha(key, nonce24.copyOfRange(0, 16))
        return Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(mode, SecretKeySpec(sub, "ChaCha20"), IvParameterSpec(ByteArray(4) + nonce24.copyOfRange(16, 24)))
            updateAAD(utf8(why))
        }
    }
    private fun keyFor(sk: ByteArray, peer: String) = sha256(utf8("keykin2-seal"), Secp.ecdhX(sk, peer))

    fun to(sk: ByteArray, peer: String, why: String, secretHex: String): String {
        val nonce = random(24)
        return hex(nonce + cipher(Cipher.ENCRYPT_MODE, keyFor(sk, peer), nonce, why).doFinal(unhex(secretHex)))
    }
    fun from(sk: ByteArray, peer: String, why: String, boxHex: String): String = try {
        val b = unhex(boxHex)
        hex(cipher(Cipher.DECRYPT_MODE, keyFor(sk, peer), b.copyOfRange(0, 24), why).doFinal(b.copyOfRange(24, b.size)))
    } catch (e: KeykinException) { throw e } catch (_: Exception) { refuse("bad_seal") }
}
