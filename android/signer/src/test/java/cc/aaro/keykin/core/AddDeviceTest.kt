package cc.aaro.keykin.core

import cc.aaro.qrst.core.Checked
import cc.aaro.qrst.core.CodeCheck
import cc.aaro.qrst.core.Effect
import cc.aaro.qrst.core.Mode
import cc.aaro.qrst.core.PairingUri
import cc.aaro.qrst.core.Phase
import cc.aaro.qrst.core.Role
import cc.aaro.qrst.core.Session as QSession
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.generateSecretKey
import cc.aaro.qrst.core.hexToBytes
import cc.aaro.qrst.core.toHex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-code add (Keykin SPEC 8b): Marble's copy of the QRST `keykin-device` profile against the vectors in
 * qr-secret-transfer (vectors/keykin-device.json, copied here), and two sessions at the levels Marble uses.
 */
class AddDeviceTest {
    private val v = JSONObject(javaClass.getResource("/keykin-device.json")!!.readText())
    private val P = KeykinDeviceProfile
    private val env = SystemEnv

    private fun join() = v.getJSONObject("join").getJSONObject("value").let { KeykinGift.Join(it.getString("addr"), it.getString("boxKey"), it.getString("npub"), it.optString("loginPage").ifEmpty { null }) }

    @Test fun matchesTheVectors() {
        assertEquals(v.getJSONObject("join").getString("content"), P.encode(join()))
        assertEquals(v.getJSONObject("whole").getString("content"), P.encode(KeykinGift.Whole(v.getJSONObject("whole").getString("nsec").hexToBytes())))
        assertEquals(Checked.Ok(join()), P.check(v.getJSONObject("join").getString("content")))
        val w = P.check(v.getJSONObject("whole").getString("content")) as Checked.Ok
        assertEquals(v.getJSONObject("whole").getString("nsec"), (w.value as KeykinGift.Whole).nsec.toHex())
        assertEquals(v.getJSONObject("join").getString("render"), P.render(join()))
        val bad = v.getJSONArray("bad")
        for (i in 0 until bad.length()) assertTrue(bad.getJSONObject(i).getString("why"), P.check(bad.getJSONObject(i).getString("content")) is Checked.Bad)
    }

    @Test fun theInviteRidesInTheLink() {
        val uri = v.getString("uri")
        val inv = P.inviteOf(uri)!!
        assertEquals("Galaxy Tab", inv.name)
        assertEquals(Texts.readInvite(v.getJSONObject("invite").getString("text")).key, inv.key)
        val p = PairingUri.parse(uri)
        assertEquals(P.id, p.profile); assertEquals(Mode.OFFER, p.mode)
        assertNull(P.inviteOf(uri.substringBefore("&invite=")))
        // An invite Marble makes reads back the same, chip key and all.
        val me = Me(generateSecretKey(env), "Pixel Tablet", "02" + "cd".repeat(32))
        val back = P.inviteOf(P.withInvite(uri.substringBefore("&invite="), Texts.invite(me)))!!
        assertEquals(me.key, back.key); assertEquals("Pixel Tablet", back.name); assertEquals(me.chip, back.chip)
    }

    private fun <T> deliver(effects: List<Effect<T>>, to: QSession<*>): List<Effect<*>> {
        val out = mutableListOf<Effect<*>>()
        for (e in effects) if (e is Effect.Publish && e.to == to.pub) out += to.receive(e.event)
        return out
    }
    private fun pump(a: QSession<*>, b: QSession<*>, first: List<Effect<*>>, firstFrom: QSession<*>) {
        var pending = first; var from = firstFrom
        repeat(20) {
            val to = if (from === a) b else a
            @Suppress("UNCHECKED_CAST") val next = deliver(pending as List<Effect<Any?>>, to)
            if (next.none { it is Effect.Publish }) return
            pending = next; from = to
        }
    }

    /** A camera scan: no digits; the join text arrives, handed over only at release (after the add). */
    @Test fun cameraScanReleasesAtNone() {
        val rcv = QSession<KeykinGift>(Role.RECEIVER, true, P, generateSecretKey(env), env, check = CodeCheck.NONE)
        val snd = QSession<KeykinGift>(Role.SENDER, false, P, generateSecretKey(env), env, peerPub = rcv.pub, token = rcv.token, check = CodeCheck.NONE, peerCheck = CodeCheck.NONE)
        pump(rcv, snd, snd.start(), snd)
        assertEquals(CodeCheck.NONE, snd.view().check); assertEquals(Phase.RELEASE, snd.view().phase); assertNull(rcv.view().code)
        deliver(snd.release(join()), rcv)
        assertEquals(join(), rcv.accept().filterIsInstance<Effect.Commit<KeykinGift>>().single().value)
    }

    /** A pasted code: the five digits are compared first, then the whole key goes. */
    @Test fun pastedCodeComparesDigits() {
        val key = generateSecretKey(env)
        val rcv = QSession<KeykinGift>(Role.RECEIVER, true, P, generateSecretKey(env), env, check = CodeCheck.NONE)
        val snd = QSession<KeykinGift>(Role.SENDER, false, P, generateSecretKey(env), env, peerPub = rcv.pub, token = rcv.token, check = CodeCheck.COMPARE, peerCheck = CodeCheck.NONE)
        pump(rcv, snd, snd.start(), snd)
        assertEquals(CodeCheck.COMPARE, snd.view().check)
        assertEquals(rcv.view().code, snd.view().compare)
        deliver(snd.confirmMatch(KeykinGift.Whole(key.copyOf())), rcv)
        assertArrayEquals(key, (rcv.accept().filterIsInstance<Effect.Commit<KeykinGift>>().single().value as KeykinGift.Whole).nsec)
    }
}
