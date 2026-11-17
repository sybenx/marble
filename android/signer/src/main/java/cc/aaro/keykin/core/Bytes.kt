// Bytes, hashes and the two exact text forms the box checks: canonical JSON (what requests are signed
// over) and the Nostr event serialisation (what an event id is the hash of). Both must match the
// TypeScript byte for byte, so strings are written here by hand, the way JSON.stringify writes them.
package cc.aaro.keykin.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

class KeykinException(val code: String, detail: String? = null) : Exception(if (detail != null) "$code: $detail" else code)
fun refuse(code: String, detail: String? = null): Nothing = throw KeykinException(code, detail)

private val HEX = "0123456789abcdef".toCharArray()
fun hex(b: ByteArray): String { val o = CharArray(b.size * 2); for (i in b.indices) { val v = b[i].toInt() and 0xff; o[i * 2] = HEX[v ushr 4]; o[i * 2 + 1] = HEX[v and 15] }; return String(o) }
fun unhex(s: String): ByteArray {
    if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' }) refuse("bad_hex")
    return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }
}
fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
private val rnd = SecureRandom()
fun random(n: Int) = ByteArray(n).also(rnd::nextBytes)
val N: BigInteger = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
fun scalarHex(k: BigInteger): String = k.mod(N).toString(16).padStart(64, '0')
fun idHex(i: Int) = scalarHex(BigInteger.valueOf(i.toLong()))

/** A string as JSON.stringify writes it. */
fun jsonString(s: String): String {
    val o = StringBuilder(s.length + 2).append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> o.append("\\\""); c == '\\' -> o.append("\\\\")
            c == '\b' -> o.append("\\b"); c == '\u000c' -> o.append("\\f"); c == '\n' -> o.append("\\n"); c == '\r' -> o.append("\\r"); c == '\t' -> o.append("\\t")
            c < ' ' -> o.append("\\u00").append(HEX[c.code ushr 4]).append(HEX[c.code and 15])
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { o.append(c).append(s[i + 1]); i++ }
            c.isSurrogate() -> o.append("\\u").append(c.code.toString(16))
            else -> o.append(c)
        }
        i++
    }
    return o.append('"').toString()
}

/** Canonical JSON (RFC 8785 for the values Keykin uses): keys sorted, no spaces, whole numbers only. */
fun jcs(v: Any?): String = when (v) {
    null, JSONObject.NULL -> "null"
    is Boolean -> v.toString()
    is Int, is Long -> v.toString()
    is String -> jsonString(v)
    is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { jcs(v.get(it)) }
    is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { jsonString(it) + ":" + jcs(v.get(it)) }
    is List<*> -> v.joinToString(",", "[", "]") { jcs(it) }
    else -> refuse("bad_json", v::class.java.name)
}

/** The Nostr event id (NIP-01) of an unsigned event. */
fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
    hex(sha256(utf8("[0," + jsonString(pubkey) + "," + createdAt + "," + kind + "," + jcs(tags) + "," + jsonString(content) + "]")))
