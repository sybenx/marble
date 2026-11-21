package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import cc.aaro.keykin.core.utf8
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * ~/vaults/vectors/verify.mjs, ported check for check (its 81), plus remakes: the same bytes made again
 * by this core (ρ from the AUTH events, puts, Padmé, nv1 sizes, the shard, accepted contributions, commits).
 * vaults.json is a copy of the spec repo's; refresh it when the vectors change.
 */
class VectorsTest {
    private val v = obj(javaClass.getResource("/vaults.json")!!.readText())
    private var n = 0
    private fun ok(c: Boolean, m: String) { assertTrue("FAIL: $m", c); n++ }
    private fun fails(m: String, f: () -> Any?) { val t = try { f() } catch (_: Exception) { ok(true, m); return }; ok(t == false || t == null, m) }
    private fun ev(o: JSONObject) = Ev.of(o)
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun dec(key: ByteArray, payload: String) = Keys.decrypt(payload, key) ?: throw IllegalStateException("decrypt")
    private fun kidOK(k: String, kid: String) = Keys.kid(unhex(k)) == kid

    @Test fun vectors() {
        val inp = v.getJSONObject("inputs")
        val vid = inp.getString("vault_id"); val os = v.getJSONObject("owner_secret"); val V = unhex(os.getString("V"))
        ok(Keys.genId(V, null) == os.getString("id") && inp.getJSONArray("sigma").getString(0) == os.getString("id"), "generation id")
        os.getJSONObject("second").let { g -> ok(g.getString("prev") == os.getString("id") && Keys.genId(unhex(g.getString("secret")), g.getString("prev")) == g.getString("id")
            && Keys.genId(unhex(g.getString("secret")), null) != g.getString("id"), "second generation id binds prev")
            ok(Gen.of(g).ok, "Gen.of checks the id") }
        val epochs = v.getJSONObject("epochs")
        for (name in epochs.keys()) {
            val e = epochs.getJSONObject(name)
            ok(hex(sha256(utf8(e.getString("rho_preimage")))) == e.getString("rho"), "$name rho")
            val K = Keys.epoch(V, vid, e.getString("rho"))
            ok(hex(K) == e.getString("K") && Keys.kid(K) == e.getString("kid"), "$name K, kid")
            ok(Keys.locator(K, vid) == e.getString("locator"), "$name locator")
            ok(hex(Keys.inboxSecret(V, vid, e.getString("rho"))) == e.getString("inbox_secret"), "$name inbox")
            ok(Keys.pub(unhex(e.getString("inbox_secret"))) == e.getString("inbox"), "$name inbox pub")
            ok(hex(Keys.opsKey(K, vid)) == e.getString("ops_key"), "$name ops key (remade)")
        }
        val auth = v.getJSONObject("auth")
        val ended = obj(epochs.getJSONObject("reader_removed").getString("rho_preimage")).getJSONArray("ended")
        ok(ended.length() == 1 && ended.getString(0) == auth.getJSONObject("remove").getJSONObject("event").getString("id"), "rho ended names the remove")
        val K0 = unhex(epochs.getJSONObject("genesis").getString("K")); val authKey = Keys.authKey(K0, vid)
        ok(hex(authKey) == v.getJSONObject("per_vault").getString("auth_key"), "auth key")
        ok(hex(Keys.dedupKey(K0, vid)) == v.getJSONObject("per_vault").getString("dedup_key"), "dedup key (remade)")
        val owner = inp.getString("owner_npub")
        val auths = HashMap<String, Auth>()
        for (k in auth.keys()) {
            val a = auth.getJSONObject(k); val e = ev(a.getJSONObject("event"))
            ok(e.valid() && e.pubkey == owner, "auth sig $k")
            ok(dec(authKey, e.content) == a.getString("plaintext"), "auth dec $k")
            auths[k] = Auth.of(e, authKey, vid, owner)!!
        }
        ok(auth.getJSONObject("genesis").getJSONObject("event").getString("id") == inp.getString("genesis_id"), "genesis id")
        for (d in listOf("owner_device", "reader_device")) {
            val dev = v.getJSONObject(d)
            val dc = ev(dev.getJSONObject("device_cert")); val sc = ev(dev.getJSONObject("sub_cert"))
            ok(dc.valid() && sc.valid(), "certs")
            val sub = Cert.of(sc) as Cert.Sub
            ok(sub.dvk == dev.getString("dvk") && Cert.of(dc) is Cert.Device, "storage_sig")
            // remade: the dvk and storage key from the root (§3.3)
            val dvk = Keys.dvk(unhex(dev.getString("root_secret")), inp.getString("genesis_id"), 0)
            ok(Keys.pub(dvk) == dev.getString("dvk") && Keys.pub(Keys.storage(dvk)) == sub.storage, "dvk and storage key (remade) $d")
        }
        // keyring
        val kr = v.getJSONObject("keyring")
        val self = obj(kr.getString("event_content")).getJSONObject("self")
        val seal = Keys.conv(unhex(inp.getString("owner_npub_secret")), self.getString("eph"))
        val krp = obj(dec(seal, self.getString("ct")))
        ok(hex(seal) == kr.getString("conversation_key") && krp.getString("d") == kr.getString("d") && krp.getJSONArray("gens").getJSONObject(0).getString("secret") == os.getString("V"), "keyring self seal")
        ok(canon(krp) == kr.getString("payload"), "keyring payload canonical (remade)")
        // heads and commits
        val byKid = epochs.keys().asSequence().map { epochs.getJSONObject(it) }.associateBy { it.getString("kid") }
        var prev: String? = null; var seq = 0L
        val heads = v.getJSONObject("heads")
        for (hk in heads.keys().asSequence().sorted().toList().let { listOf("genesis_epoch", "with_reader_epoch") }) {
            val e = ev(heads.getJSONObject(hk).getJSONObject("event")); val ep = byKid[e.tags[0][1]]!!
            ok(e.valid() && e.pubkey == v.getJSONObject("owner_device").getString("dvk") && e.tags[1][1] == ep.getString("locator"), "head sig, tags")
            val body = obj(dec(unhex(ep.getString("ops_key")), e.content))
            ok(body.getString("kid") == ep.getString("kid") && body.getString("genesis") == inp.getString("genesis_id"), "head body")
            for (x in body.getJSONArray("commits").objs()) {
                val cm = Commit.of(x)!!
                ok(cm.prev == prev && cm.seq == seq && cm.kid == ep.getString("kid"), "commit $seq")
                for (op in cm.ops) {
                    if (op.getString("op") == "put") ok(Put.of(op) != null, "put in commit")
                    if (op.getString("op") == "rekey") {
                        ok(byKid[op.getString("to")]?.getString("rho") == op.getString("rho"), "rekey target")
                        for (w in op.getJSONArray("wraps").objs()) {
                            val p = obj(dec(Keys.conv(unhex(inp.getString("reader_npub_secret")), w.getString("eph")), w.getString("ct")))
                            ok(p.getString("vault") == vid && p.getJSONObject("keys").let { ks -> ks.keys().asSequence().all { kidOK(ks.getString(it), it) } }, "wrap")
                        }
                    }
                }
                prev = cm.id; seq++
            }
        }
        ok(seq == 4L, "four commits")
        // contributions
        val readerDev = v.getJSONObject("reader_device")
        for ((name, profile) in listOf("contrib_drive" to "drive", "contrib_photos" to "photos")) {
            val c = v.getJSONObject(name); val e = ev(c.getJSONObject("event"))
            ok(e.valid() && e.pubkey == readerDev.getString("dvk"), "contrib sig")
            val plain = Contrib.open(e, unhex(epochs.getJSONObject("with_reader").getString("inbox_secret")))!!
            ok(canon(plain) == c.getString("plaintext"), "contrib dec")
            ok(Contrib.entryOf(e.id) == c.getJSONObject("accepted").getString("entry"), "contrib entry")
            // remade: the accepted puts, byte for byte (test 32)
            val acc = c.getJSONObject("accepted")
            val made = Contrib.accepted(e.id, profile, plain.getJSONObject("item"), plain.getString("from"), "Bob's phone")
            ok(canon(made[0].json) == canon(acc.getJSONObject("put")), "accepted put (remade) $profile")
            if (profile == "photos") ok(canon(made[1].json) == canon(acc.getJSONObject("link")), "accepted link (remade)")
        }
        val small = v.getJSONObject("nv1_small")
        ok(Nv1.open(BlobRef(small.getJSONObject("ref")), listOf(b64(small.getString("part0_b64"))))?.let { String(it) } == "hello vault", "nv1")
        // negatives
        val neg = v.getJSONArray("negative").objs().associateBy { it.getString("name") }
        ok(Put.of(v.getJSONObject("ver").getJSONObject("put")) != null && Put.of(v.getJSONObject("contrib_drive").getJSONObject("accepted").getJSONObject("put")) != null
            && Put.of(v.getJSONObject("contrib_photos").getJSONObject("accepted").getJSONObject("link")) != null, "positive vers")
        ok(Put.of(neg["put whose ver does not hash"]!!.getJSONObject("input")) == null, "neg ver")
        ok(Regex("\\d\\.\\d").containsMatchIn(neg["put with a float"]!!.getString("input_json")), "neg float")
        ok(try { obj(neg["put with a float"]!!.getString("input_json")); false } catch (_: Malformed) { true }, "neg float refused (remade)")
        ok(neg["keyring payload d differs from the d tag"]!!.getJSONObject("input").getString("tag_d") != kr.getString("d"), "neg d")
        fails("neg seal") { dec(unhex(neg["keyring seal opened with the to-self conversation key instead of the eph one"]!!.getJSONObject("input").getString("wrong_key")), self.getString("ct")) }
        for (k in listOf("commit signed by a key other than its dvk", "commit signed over the bare id, not TH"))
            ok(Commit.of(neg[k]!!.getJSONObject("input")) == null, "neg $k")
        val commits = v.getJSONArray("commits").objs()
        ok(obj(neg["commit whose prev breaks the chain"]!!.getJSONObject("input").getString("c")).getString("prev") != commits[1].getString("id"), "neg prev")
        neg["commit kid differs from its head epoch"]!!.getJSONObject("input").let { ok(obj(it.getString("c")).getString("kid") != it.getString("head_kid"), "neg kid") }
        ok(!ev(neg["head with content altered after signing"]!!.getJSONObject("input")).valid(), "neg head content")
        ev(neg["head signed correctly but sealed under another epoch"]!!.getJSONObject("input")).let { e ->
            ok(e.valid(), "neg head signed"); fails("neg head epoch") { dec(unhex(byKid[e.tags[0][1]]!!.getString("ops_key")), e.content) } }
        neg["wrap whose kid is not the hash of its key"]!!.getJSONObject("input").getJSONObject("wrap").let { w ->
            val p = obj(dec(Keys.conv(unhex(inp.getString("reader_npub_secret")), w.getString("eph")), w.getString("ct")))
            ok(!p.getJSONObject("keys").let { ks -> ks.keys().asSequence().all { kidOK(ks.getString(it), it) } }, "neg wrap kid") }
        neg["wrap NIP-44 MAC altered"]!!.getJSONObject("input").let { w -> fails("neg wrap mac") { dec(Keys.conv(unhex(inp.getString("reader_npub_secret")), w.getString("eph")), w.getString("ct")) } }
        neg["nv1 part with a flipped byte"]!!.getJSONObject("input").let { ok(Nv1.open(BlobRef(small.getJSONObject("ref")), listOf(b64(it.getString("part0_b64")))) == null, "neg nv1 sha") }
        neg["nv1 blob truncated: last chunk sealed with flag 0"]!!.getJSONObject("input").let { t ->
            val p1 = b64(t.getString("part1_b64")); val ref = t.getJSONObject("ref")
            ok(hex(sha256(p1)) == ref.getJSONArray("parts").getString(1), "neg trunc sha")
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val nn = ByteArray(12).also { it[10] = 1; it[11] = 1 }
            fails("neg trunc gcm") { c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(unhex(ref.getString("key")), "AES"), javax.crypto.spec.GCMParameterSpec(128, nn)); c.updateAAD(utf8("nostr-vault/blob/1")); c.doFinal(p1) }
        }
        neg["sub-cert whose storage_sig signs another dvk"]!!.getJSONObject("input").let { val e = ev(it); ok(e.valid() && Cert.of(e) == null, "neg storage_sig") }
        neg["CONTRIB signed by a key that is not the sub-cert dvk"]!!.getJSONObject("input").let { val e = ev(it); ok(e.valid() && e.pubkey != readerDev.getString("dvk"), "neg contrib signer") }
        neg["AUTH remove naming the owner"]!!.getJSONObject("input").let { a -> val e = ev(a.getJSONObject("event"))
            ok(e.valid() && obj(dec(authKey, e.content)).getJSONArray("remove").getJSONObject(0).getString("reader") == owner, "neg owner remove")
            ok(Auth.of(e, authKey, vid, owner) == null, "neg owner remove refused (remade)") }
        neg["removed reader repeats leave"]!!.getJSONObject("input").let { l -> val e = ev(l.getJSONObject("event")); val p = obj(dec(authKey, e.content))
            val remove = auth.getJSONObject("remove").getJSONObject("event").getString("id")
            ok(e.valid() && e.pubkey == inp.getString("reader_npub") && p.getString("type") == "leave" && p.getJSONArray("remove").getJSONObject(0).getJSONArray("prev").getString(0) == remove && e.id < remove, "neg repeated leave: prev names the remove, lower id")
            ok(obj(epochs.getJSONObject("reader_removed").getString("rho_preimage")).getJSONArray("ended").join(",") == "\"$remove\"", "neg repeated leave: ended keeps the remove")
            // remade: ρ from the AUTH events, with the repeated leave changing nothing
            val leave = Auth.of(e, authKey, vid, owner)!!
            ok(Readers.of(listOf(auths["genesis"]!!, auths["add"]!!, auths["remove"]!!, leave)).rho == epochs.getJSONObject("reader_removed").getString("rho"), "rho after a repeated leave (remade)") }
        // remade: ρ for each epoch from the AUTH events
        ok(Readers.of(listOf(auths["genesis"]!!)).rho == epochs.getJSONObject("genesis").getString("rho"), "rho genesis (remade)")
        ok(Readers.of(listOf(auths["genesis"]!!, auths["add"]!!)).rho == epochs.getJSONObject("with_reader").getString("rho"), "rho with reader (remade)")
        ok(Readers.of(listOf(auths["genesis"]!!, auths["add"]!!, auths["remove"]!!)).rho == epochs.getJSONObject("reader_removed").getString("rho"), "rho removed (remade)")
        ok(Readers.of(listOf(auths["genesis"]!!, auths["add"]!!, auths["remove"]!!, auths["rekey"]!!)).rho == epochs.getJSONObject("fresh_key").getString("rho"), "rho fresh key (remade)")
        // remade: Padmé, part sizes, ver, the shard
        val pad = v.getJSONObject("padme"); for (k in pad.keys()) ok(Nv1.padme(k.toLong()) == pad.getLong(k), "padme $k")
        val two = v.getJSONObject("nv1_two_parts"); val tref = BlobRef(two.getJSONObject("ref"))
        ok(tref.partSizes() == two.getJSONArray("part_sizes").let { a -> List(a.length()) { a.getLong(it) } }, "part sizes")
        val big = ByteArray(70000) { (it % 251).toByte() }
        ok(canon(Nv1.seal(big, unhex(tref.key), 1).ref.json) == canon(tref.json), "nv1 two parts (remade)")
        val vp = v.getJSONObject("ver").getJSONObject("put")
        ok(canon(Put.make(vp.getString("entry"), emptyList(), vp.getJSONObject("meta"), vp.getJSONObject("data"), false).json) == canon(vp), "put (remade)")
        val sh = v.getJSONObject("shard"); val sk = Keys.shardKey(Keys.dedupKey(K0, vid), utf8(sh.getString("plaintext")))
        ok(hex(sk) == sh.getString("key") && canon(Nv1.seal(utf8(sh.getString("plaintext")), sk).ref.json) == canon(sh.getJSONObject("ref")), "shard (remade)")
        // remade: commit c0 from its ops, signed again (BIP-340 with random aux, so the signature differs but verifies)
        val c0 = Commit.of(commits[0])!!
        val again = Commit.make(unhex(v.getJSONObject("owner_device").getString("dvk_secret")), c0.genesis, 0, null, c0.kid, c0.time, c0.ops)
        ok(again.c == c0.c && again.id == commits[0].getString("id") && again.signedOK(), "commit (remade)")
        println("all $n checks pass")
        assertEquals(true, n >= 81)
    }
}
