// One account's vaults on this device: its device root and cert, its keyring entries, Keykin's view, and the vaults.
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.unhex
import org.json.JSONArray
import org.json.JSONObject

class Vaults(
    val account: Account, val relays: RelayNet, blobs: Blobs, val store: Store, val clock: Clock,
    /** The account's own write relays (NIP-65): where keyring entries live (§3.6). */
    val accountRelays: () -> List<String>,
    /** This device's name, for its sub-certs (§3.4). */
    val deviceName: String,
) {
    val storage = Storage(blobs, clock).also { it.signer = { at, kind, tags, content -> account.sign(at, kind, tags, content) } }
    private val vaults = HashMap<String, Vault>()

    // --- This device ----------------------------------------------------------------------------------

    /** The device root (§2): random, made here, never backed up. One per account per install. */
    val root: ByteArray by lazy { store.get("root")?.let(::unhex) ?: Keys.newSecret().also { store.put("root", hex(it)) } }
    val rootPub: String get() = Keys.pub(root)

    /** This device's cert, signed once by the npub (one signer round, ever). On Keykin v2 it names this device's member key. */
    @Synchronized fun deviceCert(): Ev {
        val label = (kk ?: askKeykin())?.me
        store.get("deviceCert")?.let { Ev.of(obj(it)) }?.takeIf { c -> (Cert.of(c) as? Cert.Device)?.let { it.root == rootPub && it.keykin == label && c.pubkey == account.npub } == true }?.let { return it }
        val body = JSONObject().put("type", "device").put("root", rootPub).apply { if (label != null) put("keykin", label) }
        val e = account.sign(clock.now(), Kinds.CERT, emptyList(), canon(body))
        if (Cert.of(e) !is Cert.Device || e.pubkey != account.npub) throw IllegalStateException("the signer returned a bad device cert")
        store.put("deviceCert", e.json().toString())
        return e
    }

    // --- Keykin's view (§3.1's triggers) ----------------------------------------------------------------

    /** The last view asked this run. */
    @Volatile var kk: KeykinView? = null; private set
    /** Member keys the box has listed, with the account epoch they were first seen at. */
    private val listed: JSONObject get() = store.get("kkListed")?.let(::obj) ?: JSONObject()

    /** Asks the box who is in the account (if it is a Keykin account) and remembers who was listed. */
    @Synchronized fun askKeykin(): KeykinView? {
        val v = try { account.keykin() } catch (_: Exception) { null } ?: return null.also { kk = null }
        kk = v
        val m = v.members ?: return v
        val l = listed
        for (k in m.keys) if (!l.has(k)) l.put(k, v.epoch)
        store.put("kkListed", l.toString())
        store.put("kkLast", JSONObject().put("epoch", v.epoch).put("members", JSONObject(m as Map<*, *>)).toString())
        val grants = (store.get("kkGrants")?.let(::arr)?.strings().orEmpty() + v.revokedGrants).distinct().sorted()
        store.put("kkGrants", JSONArray(grants).toString())
        return v
    }
    /** Members the box last showed blocked. */
    fun blockedMembers(): Set<String> = store.get("kkLast")?.let(::obj)?.objOr("members")?.let { m -> m.keys().asSequence().filter { m.optBoolean(it) }.toSet() } ?: emptySet()
    /** Members an earlier answer listed and a later one, after a cycle, no longer does (§3.1). */
    fun removedMembers(): Set<String> {
        val last = store.get("kkLast")?.let(::obj) ?: return emptySet()
        val e = last.optInt("epoch"); val now = last.objOr("members")
        val l = listed
        return l.keys().asSequence().filter { !now.has(it) && l.optInt(it) < e }.toSet()
    }
    fun revokedGrants(): Set<String> = store.get("kkGrants")?.let(::arr)?.strings()?.toSet() ?: emptySet()
    val isKeykin get() = kk != null || store.get("kkLast") != null

    // --- Keyring entries (§3.6) ---------------------------------------------------------------------------

    /**
     * Keyring entries the last [keyrings] read couldn't open because the signer didn't answer (busy, out of reach, or
     * refusing this device). Not "no vault": a new notes vault is never made while this is above zero.
     */
    @Volatile var unopened = 0; private set
    /**
     * Every keyring entry of the account that opens, by event. One that opens (or is malformed) is remembered for good;
     * one the signer couldn't open is asked again next time: a box answering `slow_down` once must not hide a new
     * generation from this device for ever (it would never read the other devices' notes again).
     */
    @Synchronized fun keyrings(): List<Pair<Ev, JSONObject>>? {
        val r = accountRelays().ifEmpty { return null }
        val a = relays.query(r, JSONObject().put("kinds", JSONArray(listOf(Kinds.KEYRING))).put("authors", JSONArray(listOf(account.npub))))
        if (a.answered.isEmpty()) return null
        var failed = 0
        val out = a.events.filter { it.kind == Kinds.KEYRING && it.pubkey == account.npub && it.valid() }.distinctBy { it.id }.mapNotNull { e ->
            when (val o = openKeyring(e)) { is Opened.Payload -> e to o.p; Opened.NotNow -> { failed++; null }; Opened.Never -> null }
        }
        unopened = failed
        return out
    }
    private sealed class Opened { class Payload(val p: JSONObject) : Opened(); object NotNow : Opened(); object Never : Opened() }
    private fun openKeyring(e: Ev): Opened {
        // ("x": malformed for good. "-", written before 0.16.21 for any failure, the signer's included: asked again.)
        store.get("kr/${e.id}")?.let { if (it == "x") return Opened.Never; if (it != "-") return Opened.Payload(obj(it)) }
        // A copy dated more than a day ahead is treated as missing (§3.6), until it isn't.
        if (e.createdAt > clock.now() + 86_400) return Opened.Never
        val self = try {
            obj(e.content).getJSONObject("self").let { it.hexOf("eph", 32) to it.str("ct") }
        } catch (_: Exception) { null } ?: return Opened.Never.also { store.put("kr/${e.id}", "x") }
        val plain = try { account.openSelf(self.first, self.second) } catch (_: Exception) { null } ?: return Opened.NotNow
        val p = try { obj(plain).takeIf { it.optString("d") == e.tag("d") && isHex(it.optString("vault"), 32) } } catch (_: Exception) { null }
        store.put("kr/${e.id}", p?.toString() ?: "x")
        return if (p != null) Opened.Payload(p) else Opened.Never
    }
    /** A keyring entry, sealed `self` to the npub with a fresh one-time key (§3.6), signed by the npub. */
    fun publishKeyring(payload: JSONObject): Set<String> {
        val s = Keys.seal(account.npub, canon(payload))
        // Always later than this entry's last copy (as Web does): two copies in one second tie, a relay keeps the lower
        // id (NIP-01), and the one with the new generation could be the one dropped, leaving the other devices unable
        // to read anything written under it.
        val d = payload.str("d")
        val at = maxOf(clock.now(), (store.get("krAt/$d")?.toLongOrNull() ?: 0) + 1)
        val e = account.sign(at, Kinds.KEYRING, listOf(listOf("d", d)), canon(JSONObject().put("self", JSONObject().put("eph", s.eph).put("ct", s.ct))))
        store.put("krAt/$d", at.toString())
        store.put("kr/${e.id}", payload.toString())
        return relays.publish(accountRelays(), e)
    }

    /** The account's vault of [profile] that it owns: the lowest genesis id among its owner entries (Marble's rule for notes). */
    fun findOwned(profile: String, entries: List<Pair<Ev, JSONObject>>): JSONObject? =
        entries.map { it.second }.filter { it.optString("role") == "owner" && it.optString("profile") == profile && it.optString("owner") == account.npub }
            .minByOrNull { it.optString("genesis") }

    // --- Vaults -----------------------------------------------------------------------------------------

    @Synchronized fun open(vaultId: String): Vault? = vaults[vaultId] ?: store.get("vault/$vaultId")?.let { Vault(this, vaultId).also { v -> vaults[vaultId] = v } }

    /** Joins a vault this device found in a keyring entry: it becomes an owner device of it (§8.4). */
    @Synchronized fun join(payload: JSONObject): Vault {
        val vid = payload.hexOf("vault", 32)
        open(vid)?.let { it.mergeKeyring(payload); return it }
        val o = JSONObject().put("vault", vid).put("genesisId", payload.hexOf("genesis", 32)).put("owner", payload.str("owner"))
            .put("name", payload.optString("name")).put("profile", payload.optString("profile"))
            .put("relays", payload.arrOr("relays")).put("servers", payload.arrOr("servers")).put("d", hex(random(16))).put("gens", JSONArray())
        store.put("vault/$vid", o.toString())
        return Vault(this, vid).also { vaults[vid] = it; it.mergeKeyring(payload) }
    }

    /**
     * A new vault (§8.2): its id and first generation, the genesis (one npub signature), the keyring entry (one more),
     * and the first head with this device's certs and the genesis. [relays] and [servers] have been probed already.
     */
    @Synchronized fun create(profile: String, name: String, vaultRelays: List<String>, servers: List<String>): Vault {
        val vid = hex(random(32))
        val v = random(32)
        val gen = Gen(Keys.genId(v, null), v, null, JSONObject())
        val rho0 = Readers.of(emptyList()).rho
        val k0 = Keys.epoch(v, vid, rho0)
        val config = JSONObject().put("prev", JSONArray()).put("name", name).put("profile", profile).put("relays", JSONArray(vaultRelays)).put("servers", JSONArray(servers))
        val body = JSONObject().put("vault", vid).put("type", "genesis").put("config", config)
        val genesis = account.sign(clock.now(), Kinds.AUTH, emptyList(), Keys.encrypt(canon(body), Keys.authKey(k0, vid)))
        val o = JSONObject().put("vault", vid).put("genesisId", genesis.id).put("genesis", genesis.json()).put("owner", account.npub)
            .put("name", name).put("profile", profile).put("relays", JSONArray(vaultRelays)).put("servers", JSONArray(servers))
            .put("d", hex(random(16))).put("gens", JSONArray(listOf(gen.json()))).put("mine", JSONArray(listOf(gen.id))).put("first", gen.id)
        store.put("vault/$vid", o.toString())
        val vault = Vault(this, vid)
        vaults[vid] = vault
        vault.created(genesis)
        return vault
    }
}
