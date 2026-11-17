// FROST through the Rust library: signing rounds, making the key with the box, and building a new
// member's piece. A commitment is the line's two coefficients as points, `[C0, C1]`, compressed hex.
package cc.aaro.keykin.core

import keykin.FrostNative
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

data class Piece(val c: List<String>, val i: Int, val share: BigInteger)
data class Commit(val i: Int, val d: String, val e: String) {
    fun json() = JSONObject().put("i", i).put("D", d).put("E", e)
}

object Frost {
    private fun cj(c: List<String>) = JSONArray(c).toString()
    private fun commits(cs: List<Commit>) = JSONArray(cs.map { JSONObject().put("i", idHex(it.i)).put("D", it.d).put("E", it.e) }).toString()
    private fun pk(m: List<Pair<Int, String>>) = JSONArray(m.map { JSONArray(listOf(idHex(it.first), it.second)) }).toString()

    class Asked(val commit: Commit, internal val nonces: String)
    /** Asker, step 1: fresh nonces and the commitment to send. `msg` is the event id, hex. */
    fun ask(p: Piece, msg: String): Asked {
        val hedge = hex(sha256(random(32), unhex(scalarHex(p.share)), sha256(utf8("keykin2-round"), utf8(msg))))
        val r = JSONObject(FrostNative.run("commit", cj(p.c), idHex(p.i), scalarHex(p.share), hedge)); val cm = r.getJSONObject("commit")
        return Asked(Commit(p.i, cm.getString("D"), cm.getString("E")), r.getString("nonces"))
    }
    /** Asker, step 2: check the helper's partial, add its own, and return the BIP-340 signature. */
    fun finish(p: Piece, msg: String, mine: Asked, theirs: Commit, z: String): String {
        if (theirs.i == p.i) refuse("same_piece")
        val all = commits(listOf(mine.commit, theirs))
        if (FrostNative.run("verifyPartial", cj(p.c), msg, all, idHex(theirs.i), z) != "true") refuse("bad_partial")
        val own = FrostNative.run("sign", cj(p.c), idHex(p.i), scalarHex(p.share), mine.nonces, msg, all)
        val sig = FrostNative.run("aggregate", cj(p.c), msg, all, JSONArray(listOf(JSONArray(listOf(idHex(p.i), own)), JSONArray(listOf(idHex(theirs.i), z)))).toString())
        if (!Secp.schnorrVerify(sig, unhex(msg), p.c[0].substring(2))) refuse("bad_signature")
        return sig
    }

    // Making the key together: parts 1 to 3 of two-party key generation, this device at index 1, the box at 2.
    class Dkg1(val secret: String, val pkg: String)
    fun dkg1(i: Int): Dkg1 = JSONObject(FrostNative.run("dkgPart1", idHex(i), 2, 2, hex(random(32)))).let { Dkg1(it.getString("secret"), it.getString("package")) }
    class Dkg2(val secret: String, val pkgFor: Map<Int, String>)
    fun dkg2(secret: String, others: List<Pair<Int, String>>): Dkg2 {
        val r = JSONObject(FrostNative.run("dkgPart2", secret, pk(others))); val ps = r.getJSONArray("packages")
        return Dkg2(r.getString("secret"), (0 until ps.length()).associate { ps.getJSONArray(it).let { p -> BigInteger(p.getString(0), 16).toInt() to p.getString(1) } })
    }
    class Dkg3(val share: BigInteger, val npub: String, val c: List<String>)
    fun dkg3(secret2: String, r1: List<Pair<Int, String>>, r2: List<Pair<Int, String>>): Dkg3 {
        val r = JSONObject(FrostNative.run("dkgPart3", secret2, pk(r1), pk(r2))); val pts = r.getJSONArray("points")
        val at = (0 until pts.length()).associate { pts.getJSONArray(it).let { p -> BigInteger(p.getString(0), 16).toInt() to p.getString(1) } }
        // The line through the share points at 1 and 2: C1 = P2 − P1, C0 = P1 − C1.
        val c1 = Secp.sub(at[2] ?: refuse("bad_keygen"), at[1] ?: refuse("bad_keygen"))
        val c0 = Secp.sub(at[1]!!, c1)
        if (c0.substring(2) != r.getString("groupX")) refuse("bad_keygen")
        return Dkg3(BigInteger(r.getString("share"), 16), r.getString("groupX"), listOf(c0, c1))
    }

    /** Share repair, step 1 for this helper: a value for each helper index (keep yours, send the other). */
    fun repairDeltas(p: Piece, helpers: List<Int>, participant: Int): Map<Int, String> {
        val out = JSONArray(FrostNative.run("repairStep1", cj(p.c), JSONArray(helpers.map(::idHex)).toString(), idHex(p.i), scalarHex(p.share), idHex(participant)))
        return (0 until out.length()).associate { out.getJSONArray(it).let { x -> BigInteger(x.getString(0), 16).toInt() to x.getString(1) } }
    }
    fun repairSigma(deltas: List<String>): String = FrostNative.run("repairStep2", JSONArray(deltas).toString())
    /** The newcomer's piece from both halves; the library checks it against the commitment. */
    fun repairShare(c: List<String>, sigmas: List<String>, participant: Int): BigInteger = try {
        BigInteger(FrostNative.run("repairStep3", cj(c), JSONArray(sigmas).toString(), idHex(participant)), 16)
    } catch (_: Exception) { refuse("bad_piece") }
}
