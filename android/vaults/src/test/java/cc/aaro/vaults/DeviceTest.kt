package cc.aaro.vaults

import cc.aaro.keykin.core.Der
import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.Held
import cc.aaro.keykin.core.Me
import cc.aaro.keykin.core.Nip44
import cc.aaro.keykin.core.Texts
import cc.aaro.keykin.core.random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Marble on an emulator, a throwaway Keykin v2 box and the local relay and Blossom servers (spec §18 tests 9, 33 and 36,
 * and the task's removal). Skipped unless KEYKIN_BOX is set. Needs, running: `bun tools/vaults-box.ts`,
 * `bun tools/vaults-test-server.ts 7447` and `… 7448`, an emulator with a debug Marble on it (ANDROID_SERIAL), and
 * `adb reverse` for 7447, 7448 and 7460. KEYKIN_FROSTLIB names the desktop FROST library.
 *
 * The admin is a device here with a software "fingerprint" key (the box only checks a P-256 signature); the stolen
 * phone is another device here; the phone under test is Marble on the emulator.
 */
class DeviceTest {
    private val box = System.getenv("KEYKIN_BOX") ?: ""
    private val relays = listOf("ws://127.0.0.1:7447")
    private val send = Device.http("http://127.0.0.1:7460")
    private val serial = System.getenv("ANDROID_SERIAL") ?: "emulator-5586"

    /** A Keykin device running here, with a software chip key. */
    class Dev(name: String) {
        private val chipKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val me = Me(random(32), name, Der.compressed(chipKey.public as ECPublicKey))
        lateinit var h: Held
        val fp: (ByteArray) -> String = { b -> Der.toCompact(Signature.getInstance("SHA256withECDSA").apply { initSign(chipKey.private); update(b) }.sign()) }
    }
    inner class KeykinAccount(private val d: Dev) : Account {
        override val npub get() = d.h.npub
        override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Ev = Ev.of(Device.sign(d.me, d.h, kind, content, tags, createdAt, send))
        override fun openSelf(eph: String, ct: String): String? = try {
            val x = Device.sharedX(d.me, d.h, eph, send); try { Keys.decrypt(ct, Nip44.conversationKey(x)) } finally { x.fill(0) }
        } catch (_: Exception) { null }
        override fun keykin(): KeykinView = try {
            val (list, epoch) = Device.membersWithEpoch(d.me, d.h, send)
            KeykinView(d.me.key, epoch, list.filter { it.kind != "box" }.associate { it.key to it.blocked }, emptySet())
        } catch (_: Exception) { KeykinView(d.me.key, d.h.epoch, null, emptySet()) }
    }
    private fun vaults(d: Dev) = Vaults(KeykinAccount(d), OkNet(), OkNet(), MapStore(), WallClock, { relays }, d.me.name)

    // --- The emulator ----------------------------------------------------------------------------------------
    private fun adb(vararg a: String): String = ProcessBuilder(listOf("adb", "-s", serial) + a).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
    private fun start(extra: String) = adb("shell", "am start -n cc.aaro.cosign/.MainActivity --ez local_only true --es relay ws://127.0.0.1:7447 " +
        "--es vault_servers http://127.0.0.1:7447,http://127.0.0.1:7448 $extra")
    private fun log(tag: String): List<String> = adb("logcat", "-d", "-s", "$tag:I").lines().filter { "$tag:" in it || " $tag " in it }
    /** Asks Marble's vault for [what] and waits for its state line (the vault logs one after each action). */
    private fun phone(what: String, arg: String? = null, waitMs: Long = 60_000): JSONObject {
        adb("logcat", "-c")
        start("--es vault $what" + (arg?.let { " --es arg '${it.replace("'", "")}'" } ?: ""))
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            log("MarbleVault").lastOrNull { "state: " in it }?.let { l -> println("PHONE $what: " + l.substringAfter("state: ").take(600)); return JSONObject(l.substringAfter("state: ").substringBefore(" devices ")) }
            Thread.sleep(1000)
        }
        throw AssertionError("the phone didn't answer $what: " + log("MarbleVault").takeLast(5))
    }
    private fun phoneState(): String { adb("logcat", "-c"); start("--es act state"); Thread.sleep(2500); return log("MarbleState").lastOrNull() ?: "" }

    private fun text(v: Vault, words: String) = v.state().entries.values.any { !it.deleted && it.shown.data?.optString("text") == words }

    @Test fun removalAndStolenPhone() {
        assumeTrue(box.isNotEmpty())
        // The account: the admin makes it with the box.
        val admin = Dev("Admin laptop")
        admin.h = Device.create(admin.me, Texts.qr(java.net.URL("$box/code?local=1").readText()), send)
        // The phone under test joins: it shows its invite, the admin adds it, the phone takes the answer.
        start("--es act startJoin"); Thread.sleep(2000)
        val code = phoneState().substringAfter("joinCode=").substringBefore(" joinText=")
        assertTrue("no join code: $code", code.startsWith("{"))
        Device.add(admin.me, admin.h, Texts.readInvite(code), admin.fp, send)
        start("--es paste '${Texts.join(admin.h)}'"); Thread.sleep(3000)
        start("--es act accept"); Thread.sleep(6000)
        assertTrue(phoneState().contains("member=npub"))
        // The phone that will be stolen joins too.
        val thief = Dev("Stolen phone")
        Device.add(admin.me, admin.h, Texts.readInvite(Texts.invite(thief.me)), admin.fp, send)
        thief.h = Device.join(thief.me, Texts.readJoin(Texts.join(admin.h)), send)

        // The phone's first run with vaults: it makes the notes vault and writes a note.
        var p = phone("save", "Note from the phone")
        assertTrue(p.getInt("entries") >= 1)
        val firstGen = p.getString("current")

        // The other two devices open it from the keyring (one ecdh each) and see the note.
        val tv = vaults(thief); val t = tv.join(tv.findOwned("notes", tv.keyrings()!!)!!); assertTrue(t.catchUp())
        assertTrue(text(t, "Note from the phone"))
        t.enqueue(listOf(Put.make(entryOf("1111111111111111"), emptyList(), note("1111111111111111", "Note from the stolen phone", WallClock.now()), JSONObject().put("text", "Note from the stolen phone"), false).json))
        assertTrue(t.sync()); assertTrue(t.writeCheckpoint())
        val av = vaults(admin); val a = av.join(av.findOwned("notes", av.keyrings()!!)!!); assertTrue(a.sync())
        assertTrue(text(a, "Note from the stolen phone"))
        p = phone("sync"); assertTrue(phone("state").toString().isNotEmpty())

        // Test 33: still trusted, the thief pre-publishes a keyring copy with a 50-long chain claiming its own block.
        var prev = firstGen
        val chain = JSONArray()
        repeat(50) { val v = Keys.newSecret(); val g = Gen(Keys.genId(v, prev), v, prev, Gen.because(emptyList(), listOf(thief.me.key), emptyList())); chain.put(g.json()); prev = g.id }
        tv.publishKeyring(JSONObject().put("vault", t.id).put("genesis", t.genesisId).put("owner", thief.h.npub).put("name", "Notes").put("profile", "notes").put("role", "owner")
            .put("gens", chain).put("relays", JSONArray(relays)).put("servers", JSONArray()).put("d", cc.aaro.keykin.core.hex(random(16))))
        // Test 36: and starts a generation naming its own root and member key, with a rekey naming it.
        val bad = t.startGeneration(Gen.because(listOf(tv.rootPub), listOf(thief.me.key), emptyList()))!!
        t.enqueue(listOf(Put.make(entryOf("2222222222222222"), emptyList(), note("2222222222222222", "Under the thief's generation", WallClock.now()), JSONObject().put("text", "Under the thief's generation"), false).json))
        assertTrue(t.sync())
        p = phone("sync")
        assertEquals("the phone writes under neither", firstGen, p.getString("current"))
        assertTrue(a.sync()); assertEquals(firstGen, a.current()!!.id)

        // The thief is blocked and the keys cycled (the admin, with its fingerprint).
        Device.block(admin.me, admin.h, thief.me.key, send)
        p = phone("sync")
        assertEquals("blocked: distrusted, no generation yet", firstGen, p.getString("current"))
        Device.cycle(admin.me, admin.h, admin.fp, send)
        p = phone("sync"); p = phone("sync")
        val newGen = p.getString("current")
        assertNotEquals(firstGen, newGen); assertNotEquals(bad.id, newGen)
        assertTrue(chain.objs().none { it.getString("id") == newGen })
        assertEquals("[]", p.getJSONArray("uncovered").toString())
        p = phone("save", "After the removal")

        // The admin follows the phone's new generation: it sits on the first, and names the thief.
        assertTrue(a.sync())
        val g = a.current()!!
        assertEquals(newGen, g.id); assertEquals(firstGen, g.prev)
        assertTrue(g.because.strs("members").contains(thief.me.key) && g.because.strs("roots").contains(tv.rootPub))
        assertTrue(text(a, "After the removal"))
        assertTrue("the thief's kept checkpoint keeps its note", text(a, "Note from the stolen phone"))
        // Written while it was still trusted: the phone's checkpoint before the revocation holds it (§8.5, §15), so it
        // stays, shown as that device's; only the generation was never followed.
        assertTrue(text(a, "Under the thief's generation"))

        // The stolen phone can't open the new keyring seal (the box refuses it), so it never holds the generation.
        t.catchUp()
        assertTrue(t.generations().none { it.id == newGen })
        assertFalse(text(t, "After the removal"))
        assertNull(KeykinAccount(thief).openSelf(Keys.pub(Keys.newSecret()), "x"))
        // And what it writes now counts for nothing.
        t.enqueue(listOf(Put.make(entryOf("3333333333333333"), emptyList(), note("3333333333333333", "Thief after removal", WallClock.now()), JSONObject().put("text", "Thief after removal"), false).json))
        t.sync(); a.catchUp(); assertFalse(text(a, "Thief after removal"))
        p = phone("sync"); println("PHONE final: $p")
        File("build/device-test-npub.txt").writeText(admin.h.npub)
    }
}
