// P-256 encodings. The platform signs in DER and gives keys as two coordinates; the box wants a
// 64-byte r ‖ s signature and a 33-byte compressed key.
package cc.aaro.keykin.core

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

object Der {
    private fun b32(v: BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
    fun compressed(k: ECPublicKey): String = (if (k.w.affineY.testBit(0)) "03" else "02") + hex(b32(k.w.affineX))
    /** DER `SEQUENCE { INTEGER r, INTEGER s }` to r ‖ s, hex. */
    fun toCompact(der: ByteArray): String {
        var p = 0
        fun len(): Int { val b = der[p++].toInt() and 0xff; return if (b < 0x80) b else { var n = 0; repeat(b and 0x7f) { n = (n shl 8) or (der[p++].toInt() and 0xff) }; n } }
        fun int(): BigInteger { if (der[p++].toInt() != 0x02) refuse("bad_der"); val n = len(); val v = BigInteger(1, der.copyOfRange(p, p + n)); p += n; return v }
        if (der[p++].toInt() != 0x30) refuse("bad_der"); len()
        val r = int(); val s = int()
        return hex(b32(r)) + hex(b32(s))
    }
}
