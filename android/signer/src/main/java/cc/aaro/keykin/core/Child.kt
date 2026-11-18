// Child keys (the Keykin repo's SPEC 8k, and its src/child.ts, ported byte for byte). A child is the
// account's key plus a number, the tweak `t`: its nsec would be `a0 + t` in the even-y form, and nobody
// ever holds that. Every piece of the account becomes a piece of the child by adding `t` to it, so the
// same two pieces that sign for the account sign for the child, and nothing new is made or backed up.
//
// The tweak is made from the shared x of the account's key and a point the child's label hashes to. With
// pieces, a device and the box make that x together (`ecdh` with the label's point as the peer); with the
// whole key on this phone (tier 1) it is plain ECDH. Either way the same label gives the same child.
//
// Never form a child's nsec where it could be kept or shown: together with `t` it gives the account's.
package cc.aaro.keykin.core

import java.math.BigInteger

/** A child as a device keeps it. [t] is the tweak, hex; [npub] is the child's public key, x-only hex. */
data class Child(val label: String, val t: String, val npub: String)

object Children {
    /** A label as UTF-8, 1 to 100 bytes. A lone surrogate becomes U+FFFD, as TextEncoder makes it in the TypeScript. */
    fun labelBytes(l: Any?): ByteArray {
        if (l !is String) refuse("bad_label")
        val sb = StringBuilder(l.length)
        var i = 0
        while (i < l.length) {
            val c = l[i]
            when {
                c.isHighSurrogate() && i + 1 < l.length && l[i + 1].isLowSurrogate() -> { sb.append(c).append(l[i + 1]); i++ }
                c.isSurrogate() -> sb.append('�')
                else -> sb.append(c)
            }
            i++
        }
        val b = sb.toString().toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || b.size > 100) refuse("bad_label")
        return b
    }

    /** The point a label stands for, as an x-only key: the first hash that is the x of a point. */
    fun labelPoint(l: String): String {
        val b = labelBytes(l)
        for (n in 0 until 256) {
            val x = hex(sha256(utf8("keykin2-child-point"), byteArrayOf(n.toByte()), b))
            if (Secp.isX(x)) return x
        }
        refuse("bad_label")
    }

    /** The tweak for a label, from the shared x (hex) of the account's key and `labelPoint(label)`. */
    fun tweakFrom(sharedX: String, l: String): String {
        if (!HEX64.matches(sharedX)) refuse("bad_part")
        // The shared x goes into the hash as its hex text, as the TypeScript passes it.
        val t = BigInteger(1, sha256(utf8("keykin2-child"), utf8(sharedX), labelBytes(l))).mod(N)
        if (t.signum() == 0) refuse("bad_child")
        return scalarHex(t)
    }

    /** A tweak from storage or the wire: 32 bytes of lowercase hex, in range and not zero. Anything else is `bad_child`. */
    fun tweakOf(h: Any?): BigInteger {
        if (h !is String || !HEX64.matches(h)) refuse("bad_child")
        val t = BigInteger(h, 16)
        if (t >= N || t.signum() == 0) refuse("bad_child")
        return t
    }

    /** The child's line: the account's line moved up by `t·G`, and turned over if the child's key has odd y. */
    private fun shifted(c: List<String>, t: BigInteger): Pair<List<String>, Boolean> {
        // C0 + t·G is the identity only for the one `t` that is minus the account's key: no child there.
        val c0 = try { Secp.add(c[0], Secp.mulG(t)) } catch (_: Exception) { refuse("bad_child") }
        val flip = c0.startsWith("03")
        return (if (flip) listOf(Secp.neg(c0)) + c.drop(1).map(Secp::neg) else listOf(c0) + c.drop(1)) to flip
    }

    /** The child's public key (x-only hex) for an account's commitment. Anyone holding `t` can work it out. */
    fun npub(c: List<String>, t: BigInteger): String = shifted(c, t).first[0].substring(2)

    /** This holder's piece of the child. It signs and makes message keys exactly as a piece of the account does. */
    fun piece(p: Piece, t: BigInteger): Piece {
        val (c, flip) = shifted(p.c, t)
        val share = p.share.add(t).mod(N)
        return Piece(c, p.i, if (flip) N.subtract(share).mod(N) else share)
    }

    /** Makes the child for a label from a shared x with the label's point (pieces or the whole key, the same x). */
    fun of(sharedX: ByteArray, label: String, commitment: List<String>): Child {
        val t = tweakFrom(hex(sharedX), label)
        return Child(label, t, npub(commitment, BigInteger(t, 16)))
    }

    // --- Tier 1: the whole key on this phone, no box -----------------------------------------------

    /** The account's key in its even-y form (BIP-340), as a box account holds it: what a child is added to. */
    private fun evenY(sk: ByteArray): BigInteger {
        var a0 = BigInteger(1, sk).mod(N)
        if (a0.signum() == 0) refuse("bad_key")
        if (Secp.mulG(a0).startsWith("03")) a0 = N.subtract(a0)
        return a0
    }

    /** The child for a label, from the whole key: plain ECDH with the label's point, then the same tweak. */
    fun local(sk: ByteArray, label: String): Child = of(Secp.ecdhX(sk, labelPoint(label)), label, listOf(Secp.mulG(evenY(sk))))

    /**
     * The child's secret, from the whole key and the tweak, for the moment of one signature or one shared
     * secret. The caller wipes it at once. It is never kept, shown or sent anywhere.
     */
    fun localSecret(sk: ByteArray, t: BigInteger): ByteArray {
        var s = evenY(sk).add(t).mod(N)
        if (s.signum() == 0) refuse("bad_child")
        if (Secp.mulG(s).startsWith("03")) s = N.subtract(s)
        return unhex(scalarHex(s))
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}
