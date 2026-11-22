package cc.aaro.cosign

import java.util.Base64

/**
 * Private zaps (NIP-57's `anon` tag, as Amethyst makes them): the zap request is signed by a throwaway key, and the
 * real one (who zapped, and their message) is encrypted inside `anon` as `pzap1…_iv1…`: AES-256-CBC under the
 * NIP-04 shared secret of that throwaway key and the person zapped. So whoever was zapped opens it with an ordinary
 * NIP-04 decrypt, which Marble does with your box ([toNip04] re-encodes it for that).
 *
 * The one who sent it can't: their copy is keyed by sha256(their private key's hex + …), and a key split between
 * this phone and your box is never whole to hash. Those get a quiet no.
 */
object PrivateZap {
    const val KIND = 9734

    /**
     * The NIP-04 payload to decrypt, with the key to decrypt it with, when [ev] is a private zap request to [me];
     * else null (not a zap request, not private, or to someone else).
     */
    fun forMe(ev: Map<*, *>, me: String): Pair<String, String>? {
        if ((ev["kind"] as? Number)?.toInt() != KIND) return null
        val tags = (ev["tags"] as? List<*>)?.mapNotNull { it as? List<*> } ?: return null
        val anon = tags.firstOrNull { it.getOrNull(0) == "anon" }?.getOrNull(1) as? String ?: return null
        if (anon.isBlank()) return null
        if (tags.none { it.getOrNull(0) == "p" && it.getOrNull(1) == me }) return null
        val from = ev["pubkey"] as? String ?: return null
        if (!Regex("^[0-9a-f]{64}$").matches(from)) return null
        return (toNip04(anon) ?: return null) to from
    }

    /** `pzap1…_iv1…` as NIP-04's `base64?iv=base64`, or null if it isn't that. */
    fun toNip04(anon: String): String? {
        val parts = anon.split("_")
        if (parts.size != 2) return null
        val ct = decode(parts[0], "pzap") ?: return null
        val iv = decode(parts[1], "iv") ?: return null
        if (iv.size != 16 || ct.isEmpty()) return null
        val b64 = Base64.getEncoder()
        return b64.encodeToString(ct) + "?iv=" + b64.encodeToString(iv)
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Bech32 (BIP-173, no length limit: a zap note can be long) with prefix [hrp], as bytes; null if it isn't. */
    internal fun decode(s: String, hrp: String): ByteArray? {
        val str = s.lowercase()
        val sep = str.lastIndexOf('1')
        if (sep < 1 || str.substring(0, sep) != hrp || str.length - sep - 1 < 6) return null
        val data = str.substring(sep + 1).map { c -> CHARSET.indexOf(c).takeIf { it >= 0 } ?: return null }
        if (polymod(hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + data) != 1) return null
        // 5-bit groups to bytes; leftover bits are padding.
        var acc = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (v in data.dropLast(6)) {
            acc = (acc shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) }
        }
        return out.toByteArray()
    }

    private fun polymod(v: List<Int>): Int {
        val g = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (x in v) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor x
            for (i in 0 until 5) if ((b shr i) and 1 == 1) chk = chk xor g[i]
        }
        return chk
    }
}
