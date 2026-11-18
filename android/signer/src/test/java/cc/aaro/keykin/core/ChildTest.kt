package cc.aaro.keykin.core

import keykin.FrostNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Child keys (Keykin SPEC 8k) against vectors made by the Keykin TypeScript (branch claude/child-keys,
 * android/tools/child-vectors.ts), and tier 1 (the whole key here) against tier 2 (pieces and a box).
 * The box here is a stand-in that holds piece 2 and answers `ecdh` and `sign` as the real one does.
 */
class ChildTest {
    private val v = JSONObject(javaClass.getResource("/child-vectors.json")!!.readText())
    private fun objs(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    private fun code(f: () -> Unit): String = try { f(); "ok" } catch (e: KeykinException) { e.code }

    // --- The maths, against the TypeScript -------------------------------------------------------

    @Test fun labelPointsMatchTheTypeScript() {
        for (p in objs(v.getJSONArray("points"))) {
            assertEquals(p.getString("label"), p.getString("labelUtf8"), hex(Children.labelBytes(p.getString("label"))))
            assertEquals(p.getString("label"), p.getString("point"), Children.labelPoint(p.getString("label")))
        }
        // Some labels need a second or third try before the hash is a point: the vectors include them.
        assertTrue(objs(v.getJSONArray("points")).any { it.getInt("n") > 0 })
    }

    @Test fun tweaksMatchTheTypeScript() {
        for (t in objs(v.getJSONArray("tweaks"))) assertEquals(t.getString("t"), Children.tweakFrom(t.getString("sharedX"), t.getString("label")))
        assertEquals("bad_part", code { Children.tweakFrom("AB".repeat(32), "home") })
    }

    @Test fun childPiecesAndNpubsMatchTheTypeScript() {
        var flips = 0
        for (a in objs(v.getJSONArray("accounts"))) {
            val c = strings(a.getJSONArray("commitment"))
            val p1 = Piece(c, 1, BigInteger(a.getString("share1"), 16)); val p2 = Piece(c, 2, BigInteger(a.getString("share2"), 16))
            for (ch in objs(a.getJSONArray("children"))) {
                val t = Children.tweakOf(ch.getString("t"))
                assertEquals(ch.getString("t"), Children.tweakFrom(ch.getString("sharedX"), ch.getString("label")))
                assertEquals(ch.getString("npub"), Children.npub(c, t))
                val c1 = Children.piece(p1, t); val c2 = Children.piece(p2, t)
                assertEquals(strings(ch.getJSONArray("commitment")), c1.c)
                assertEquals(ch.getString("share1"), scalarHex(c1.share)); assertEquals(ch.getString("share2"), scalarHex(c2.share))
                if (ch.getBoolean("flip")) flips++
            }
        }
        // Both parities of the child's key, and an account whose key had odd y, are covered.
        assertTrue(flips > 0); assertTrue(objs(v.getJSONArray("accounts")).any { it.getBoolean("oddY") })
    }

    @Test fun refusesBadTweaksAndLabels() {
        for (b in objs(v.getJSONArray("tweakOf"))) assertEquals(b.opt("t").toString(), b.getString("code"), code { Children.tweakOf(if (b.isNull("t")) null else b.get("t")) })
        for (b in objs(v.getJSONArray("labels"))) assertEquals(b.getString("label"), b.getString("code"), code { Children.labelPoint(b.getString("label")) })
        // A zero, out-of-range or malformed tweak never reaches the maths, here or in a piece.
        val p = Piece(listOf(Secp.mulG(BigInteger.TEN), Secp.mulG(BigInteger.TWO)), 1, BigInteger.valueOf(12))
        for (t in listOf("00".repeat(32), "ff".repeat(32), scalarHex(BigInteger.ZERO), N.toString(16), "xyz", "AB".repeat(32)))
            assertEquals(t, "bad_child", code { Children.piece(p, Children.tweakOf(t)) })
        // The one tweak that would make the child the identity (minus the account's key) is refused too.
        assertEquals("bad_child", code { Children.npub(listOf(Secp.mulG(BigInteger.TEN)), N.subtract(BigInteger.TEN)) })
    }

    // --- Tier 1: the whole key on this phone gives the same child --------------------------------

    @Test fun tier1GivesTheTypeScriptsChild() {
        for (a in objs(v.getJSONArray("accounts"))) {
            val nsec = unhex(a.getString("nsec"))
            for (ch in objs(a.getJSONArray("children"))) {
                val c = Children.local(nsec, ch.getString("label"))
                assertEquals(Child(ch.getString("label"), ch.getString("t"), ch.getString("npub")), c)
                val s = Children.localSecret(nsec, Children.tweakOf(c.t))
                assertEquals(ch.getString("secret"), hex(s))
                assertEquals(c.npub, Secp.pub(s))
                // Messages as the child, from the whole key: the same conversation key the pieces make.
                assertEquals(ch.getString("conversationKey"), hex(Nip44.conversationKey(Secp.ecdhX(s, ch.getString("peer")))))
            }
        }
    }

    // --- Tier 2: pieces and a box, the same child -----------------------------------------------

    /** A stand-in box holding piece 2 of a key it was given: `ecdh` and `sign`, with `child`, as box.ts does them. */
    private class Box(val c: List<String>, val share: BigInteger) {
        var calls = 0
        val send: Send = { req ->
            calls++
            val b = req.getJSONObject("body")
            try {
                val mine = Piece(c, 2, share)
                val piece = if (b.has("child")) Children.piece(mine, Children.tweakOf(b.get("child"))) else mine
                val from = 1
                when (b.getString("op")) {
                    "ecdh" -> {
                        val peer = b.getString("peer")
                        val l = BigInteger.valueOf(from.toLong()).multiply(BigInteger.valueOf(from.toLong() - 2).modInverse(N)).mod(N)
                        JSONObject().put("ok", true).put("i", 2).put("part", Secp.mul(l.multiply(piece.share).mod(N), "02$peer"))
                    }
                    "sign" -> {
                        val ev = b.getJSONObject("event")
                        if (ev.getString("pubkey") != piece.c[0].substring(2)) refuse("wrong_account")
                        val tags = ev.getJSONArray("tags").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { t -> List(t.length()) { t.getString(it) } } } }
                        val id = eventId(ev.getString("pubkey"), ev.getLong("created_at"), ev.getInt("kind"), tags, ev.getString("content"))
                        val asker = b.getJSONObject("commit")
                        val own = Frost.ask(piece, id)
                        val commits = JSONArray(listOf(asker, own.commit.json()).map { JSONObject().put("i", idHex(it.getInt("i"))).put("D", it.getString("D")).put("E", it.getString("E")) }).toString()
                        val z = FrostNative.run("sign", JSONArray(piece.c).toString(), idHex(2), scalarHex(piece.share), own.nonces, id, commits)
                        JSONObject().put("ok", true).put("id", id).put("commit", own.commit.json()).put("z", z)
                    }
                    else -> refuse("unknown_op")
                }
            } catch (e: KeykinException) { JSONObject().put("error", e.code) }
        }
    }

    /** Splits a key the way Device.importKey does: even-y form, a line through it, pieces at 1 (this phone) and 2 (the box). */
    private fun split(nsec: ByteArray, a1: BigInteger = BigInteger(1, random(32)).mod(N)): Pair<Held, Box> {
        var a0 = BigInteger(1, nsec).mod(N)
        if (Secp.mulG(a0).startsWith("03")) a0 = N.subtract(a0)
        val c = listOf(Secp.mulG(a0), Secp.mulG(a1))
        val at = { i: Int -> a0.add(a1.multiply(BigInteger.valueOf(i.toLong()))).mod(N) }
        return Held(Piece(c, 1, at(1)), c[0].substring(2), "00".repeat(32), "http://box.test") to Box(c, at(2))
    }
    private val me = Me(random(32), "Test phone", null)

    @Test fun tier1AndTier2GiveTheSameChild() {
        // The TypeScript's accounts, then fresh random keys: the box and this phone make the child together,
        // and the whole key alone makes the same one.
        val keys = objs(v.getJSONArray("accounts")).map { unhex(it.getString("nsec")) } + List(8) { random(32) }
        for (nsec in keys) for (label in listOf("home", "work", "house 3 \uD83C\uDFE0", "x".repeat(100))) {
            val (h, box) = split(nsec)
            val two = Device.makeChild(me, h, label, box.send)
            val one = Children.local(nsec, label)
            assertEquals(one, two)
            assertNotEquals(h.npub, two.npub)
            // As the child, both tiers make the same shared secret with someone else.
            val peer = Secp.pub(random(32))
            val s = Children.localSecret(nsec, Children.tweakOf(one.t))
            try { assertArrayEquals(Secp.ecdhX(s, peer), Device.sharedX(me, h, peer, box.send, child = two)) } finally { s.fill(0) }
        }
        // And the same as the TypeScript's, from its own pieces (a fixed second coefficient).
        for (a in objs(v.getJSONArray("accounts"))) {
            val (h, box) = split(unhex(a.getString("nsec")), BigInteger(a.getString("a1"), 16))
            assertEquals(strings(a.getJSONArray("commitment")), h.piece.c)
            for (ch in objs(a.getJSONArray("children"))) {
                assertEquals(Child(ch.getString("label"), ch.getString("t"), ch.getString("npub")), Device.makeChild(me, h, ch.getString("label"), box.send))
                val child = Child(ch.getString("label"), ch.getString("t"), ch.getString("npub"))
                assertEquals(ch.getString("conversationKey"), hex(Nip44.conversationKey(Device.sharedX(me, h, ch.getString("peer"), box.send, child = child))))
            }
        }
    }

    @Test fun aBadTweakIsRefusedBeforeAnythingIsSent() {
        val (h, box) = split(random(32))
        val good = Device.makeChild(me, h, "home", box.send)
        val before = box.calls
        for (t in listOf("00".repeat(32), "ff".repeat(32), N.toString(16), "xyz")) {
            val bad = good.copy(t = t)
            assertEquals("bad_child", code { Device.sharedX(me, h, Secp.pub(random(32)), box.send, child = bad) })
            assertEquals("bad_child", code { Device.sign(me, h, 1, "hi", send = box.send, child = bad) })
        }
        assertEquals(before, box.calls)
        // And the stand-in box refuses one too, as the real box does, from a device that did not check.
        assertEquals("bad_child", box.send(JSONObject().put("body", JSONObject().put("op", "ecdh").put("peer", good.npub).put("child", "00".repeat(32)))).getString("error"))
    }

    // --- Signing as a child, with FROST (needs the desktop FROST library: KEYKIN_FROSTLIB) ---------

    @Test fun signsAsTheChildInBothTiers() {
        assumeTrue("set KEYKIN_FROSTLIB to the Keykin repo's desktop FROST library", System.getProperty("keykin.frostlib") != null)
        for (nsec in List(4) { random(32) }) {
            val (h, box) = split(nsec)
            val child = Device.makeChild(me, h, "home", box.send)
            val ev = Device.sign(me, h, 1, "from home", send = box.send, child = child)
            assertEquals(child.npub, ev.getString("pubkey"))
            assertTrue(Secp.schnorrVerify(ev.getString("sig"), unhex(ev.getString("id")), child.npub))
            assertTrue(!Secp.schnorrVerify(ev.getString("sig"), unhex(ev.getString("id")), h.npub))
            // The account itself still signs as itself.
            val own = Device.sign(me, h, 1, "me", send = box.send)
            assertEquals(h.npub, own.getString("pubkey")); assertTrue(Secp.schnorrVerify(own.getString("sig"), unhex(own.getString("id")), h.npub))
            // Tier 1 signs as the same child with the whole key, for the moment of signing only.
            val s = Children.localSecret(nsec, Children.tweakOf(child.t))
            val id = eventId(child.npub, 1, 1, emptyList(), "tier 1")
            try { assertTrue(Secp.schnorrVerify(Secp.schnorrSign(unhex(id), s), unhex(id), child.npub)) } finally { s.fill(0) }
        }
        // A box that is asked as one key for another's event says wrong_account.
        val (h, box) = split(random(32))
        val home = Device.makeChild(me, h, "home", box.send); val work = Device.makeChild(me, h, "work", box.send)
        val req = JSONObject().put("body", JSONObject().put("op", "sign").put("child", home.t)
            .put("event", JSONObject().put("pubkey", work.npub).put("created_at", 1).put("kind", 1).put("tags", JSONArray()).put("content", "")))
        assertEquals("wrong_account", box.send(req).getString("error"))
    }
}
