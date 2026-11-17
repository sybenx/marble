// NIP-44 version 2: the message encryption Nostr apps use. Given a conversation key, this is ChaCha20
// with a padded plaintext and an HMAC-SHA256 over nonce and ciphertext. How the conversation key is made
// from two pieces is in Device.conversationKey.
package cc.aaro.keykin.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Nip44 {
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }
    /** HKDF-extract with the salt "nip44-v2" over the shared x. */
    fun conversationKey(sharedX: ByteArray): ByteArray = hmac(utf8("nip44-v2"), sharedX)
    private fun expand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        var t = ByteArray(0); var out = ByteArray(0); var n = 1
        while (out.size < len) { t = hmac(prk, t, info, byteArrayOf(n.toByte())); out += t; n++ }
        return out.copyOf(len)
    }
    private fun paddedLen(len: Int): Int {
        if (len <= 32) return 32
        val next = Integer.highestOneBit(len - 1) shl 1
        val chunk = if (next <= 256) 32 else next / 8
        return chunk * ((len - 1) / chunk + 1)
    }
    // ChaCha20 (RFC 8439, block counter from 0), written out here. The platform's "ChaCha20" cipher gave a
    // different keystream on Android than on a desktop JVM, so this does not depend on it.
    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun chacha(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val init = IntArray(16)
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574
        for (i in 0 until 8) init[4 + i] = le(key, i * 4)
        for (i in 0 until 3) init[13 + i] = le(nonce, i * 4)
        val out = ByteArray(data.size); val x = IntArray(16); val block = ByteArray(64)
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        var counter = 0; var pos = 0
        while (pos < data.size) {
            init[12] = counter++
            init.copyInto(x)
            repeat(10) { qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15); qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14) }
            for (i in 0 until 16) { val w = x[i] + init[i]; for (k in 0 until 4) block[i * 4 + k] = (w ushr (8 * k)).toByte() }
            val n = minOf(64, data.size - pos)
            for (i in 0 until n) out[pos + i] = (data[pos + i].toInt() xor block[i].toInt()).toByte()
            pos += n
        }
        return out
    }

    fun encrypt(text: String, convKey: ByteArray, nonce: ByteArray = random(32)): String {
        val plain = utf8(text)
        if (plain.isEmpty() || plain.size > 65535) refuse("bad_message")
        val keys = expand(convKey, nonce, 76)
        val padded = ByteArray(2 + paddedLen(plain.size)).also { it[0] = (plain.size ushr 8).toByte(); it[1] = plain.size.toByte(); plain.copyInto(it, 2) }
        val ct = chacha(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), padded)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ct + hmac(keys.copyOfRange(44, 76), nonce, ct))
    }
    fun decrypt(payload: String, convKey: ByteArray): String {
        val b = try { Base64.getDecoder().decode(payload) } catch (_: Exception) { refuse("bad_message") }
        if (b.size < 99 || b[0].toInt() != 2) refuse("bad_message")
        val nonce = b.copyOfRange(1, 33); val ct = b.copyOfRange(33, b.size - 32); val mac = b.copyOfRange(b.size - 32, b.size)
        val keys = expand(convKey, nonce, 76)
        if (!java.security.MessageDigest.isEqual(mac, hmac(keys.copyOfRange(44, 76), nonce, ct))) refuse("bad_message", "mac")
        val padded = chacha(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), ct)
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len == 0 || padded.size != 2 + paddedLen(len)) refuse("bad_message", "padding")
        return String(padded, 2, len, Charsets.UTF_8)
    }
}

/** bech32 (NIP-19) for showing keys the way Nostr apps do: `npub1…` and `nsec1…`. */
object Bech32 {
    private const val CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private fun polymod(v: List<Int>): Int {
        var chk = 1
        for (x in v) { val b = chk ushr 25; chk = ((chk and 0x1ffffff) shl 5) xor x
            for ((i, g) in intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3).withIndex()) if ((b ushr i) and 1 == 1) chk = chk xor g }
        return chk
    }
    fun encode(hrp: String, data: ByteArray): String {
        val five = ArrayList<Int>(); var acc = 0; var bits = 0
        for (b in data) { acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8; while (bits >= 5) { bits -= 5; five.add((acc ushr bits) and 31) } }
        if (bits > 0) five.add((acc shl (5 - bits)) and 31)
        val exp = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        val mod = polymod(exp + five + List(6) { 0 }) xor 1
        return hrp + "1" + (five + List(6) { (mod ushr (5 * (5 - it))) and 31 }).joinToString("") { CHARS[it].toString() }
    }
    fun npub(hexKey: String) = encode("npub", unhex(hexKey))
    fun nsec(hexKey: String) = encode("nsec", unhex(hexKey))
}
