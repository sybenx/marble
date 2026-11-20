// One vault on this owner device (spec §3–§10): generations and epochs, heads, segments, checkpoints, catching up,
// writing, removing a device and starting a new generation. Everything it holds is kept in the Store (which the app
// encrypts at rest); nothing here keeps an ECDH output or a conversation key with the npub.
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.sha256
import cc.aaro.keykin.core.unhex
import cc.aaro.keykin.core.utf8
import org.json.JSONArray
import org.json.JSONObject

class Vault internal constructor(private val vs: Vaults, val id: String) {
    private val store = vs.store
    private val clock = vs.clock
    private val key = "vault/$id"
    private val p: JSONObject = obj(store.get(key)!!)

    val genesisId: String get() = p.str("genesisId")
    val owner: String get() = p.str("owner")
    val profile: String get() = p.optString("profile")

    // --- What this device holds -------------------------------------------------------------------------

    /** Every commit seen for this vault, by id (all of them: a notes vault is small, and history is display). */
    private val commits = HashMap<String, Commit>().also { m -> store.get("$key/commits")?.let(::arr)?.objs()?.forEach { o -> Commit.of(o)?.let { m[it.id] = it } } }
    private var commitsDirty = false
    private fun hold(c: Commit): Boolean {
        if (c.genesis != genesisId || commits.containsKey(c.id)) return false
        commits[c.id] = c; commitsDirty = true; stateCache = null; return true
    }
    private val gens = LinkedHashMap<String, Gen>().also { m -> p.arrOr("gens").objs().forEach { o -> try { Gen.of(o).takeIf { it.ok }?.let { m[it.id] = it } } catch (_: Malformed) {} } }
    private val mine: MutableSet<String> = p.strs("mine").toMutableSet()

    @Synchronized private fun save() {
        p.put("gens", JSONArray(gens.values.map { it.json() })).put("mine", JSONArray(mine.sorted()))
        store.put(key, p.toString())
        if (commitsDirty) { store.put("$key/commits", JSONArray(commits.values.sortedWith(compareBy({ it.dvk }, { it.seq })).map { it.json() }).toString()); commitsDirty = false }
    }

    // --- Status the app shows -----------------------------------------------------------------------------

    /** Why it can't sync right now, in a few words, or null. */
    @Volatile var trouble: String? = null; private set
    /** Something is missing that a commit or checkpoint names (§7.4): "syncing…", and nothing missing is a deletion. */
    @Volatile var gaps = 0; private set
    /** This device's own root was revoked by the npub (§8.5): it writes nothing more. */
    val removed: Boolean get() = revokedRoots().containsKey(vs.rootPub)
    /** Owner devices to show: root → its name, whether trusted, and why not. */
    data class DeviceRow(val root: String, val name: String, val label: String?, val trusted: Boolean, val note: String?)

    // --- Authority, certs and liveness (§2, §3.4) ---------------------------------------------------------

    private val genesisEv: Ev? get() = p.objOrNull("genesis")?.let { try { Ev.of(it) } catch (_: Malformed) { null } }
    /** K₀, the genesis epoch's key: the first generation under the genesis ρ. */
    private val k0: ByteArray? get() = first()?.let { Keys.epoch(it.secret, id, Readers.of(emptyList()).rho) }
    private val authKey: ByteArray? get() = k0?.let { Keys.authKey(it, id) }

    /** Every AUTH and CERT event carried anywhere, by id. */
    private fun authority(): Map<String, Ev> {
        val out = HashMap<String, Ev>()
        genesisEv?.let { out[it.id] = it }
        fun add(o: JSONObject?) { try { o?.let { Ev.of(it) }?.takeIf { it.kind == Kinds.AUTH || it.kind == Kinds.CERT }?.let { out[it.id] = it } } catch (_: Malformed) {} }
        for (c in commits.values) for (op in c.ops) if (op.optString("op") == "auth") add(op.optJSONObject("event"))
        for (cp in checkpointsHeld().values) cp.authority.forEach { out[it.id] = it }
        return out
    }
    private fun auths(): List<Auth> { val k = authKey ?: return emptyList(); return authority().values.filter { it.kind == Kinds.AUTH }.mapNotNull { Auth.of(it, k, id, owner) } }
    private fun certs(): List<Cert> = authority().values.filter { it.kind == Kinds.CERT }.mapNotNull { Cert.of(it) }

    /** Owner roots: those with a device cert by the owner npub, and their labels. */
    private fun ownerRoots(cs: List<Cert> = certs()): Map<String, String?> = cs.filterIsInstance<Cert.Device>().filter { it.ev.pubkey == owner }.associate { it.root to it.keykin }
    /** Revocations by the owner npub of owner roots: root → the revocations (several only for a forked root, §7.4). */
    private fun revokedRoots(cs: List<Cert> = certs()): Map<String, List<Cert.Revoke>> {
        val roots = ownerRoots(cs)
        return cs.filterIsInstance<Cert.Revoke>().filter { it.ev.pubkey == owner && it.root in roots }.groupBy { it.root }
    }
    /** dvk → its sub-cert, counting only sub-certs carried by a commit the dvk signed; a dvk with two that differ has none. */
    private fun subCerts(): Map<String, Cert.Sub> {
        val found = HashMap<String, MutableSet<String>>(); val subs = HashMap<String, Cert.Sub>()
        for (c in commits.values) for (op in c.ops) if (op.optString("op") == "auth") {
            val e = try { op.optJSONObject("event")?.let { Ev.of(it) } } catch (_: Malformed) { null } ?: continue
            val s = (if (e.kind == Kinds.CERT) Cert.of(e) else null) as? Cert.Sub ?: continue
            if (s.dvk != c.dvk || s.genesis != genesisId) continue
            found.getOrPut(s.dvk) { HashSet() } += canon(obj(e.content)) + e.pubkey
            subs[s.dvk] = s
        }
        return subs.filterKeys { found[it]!!.size == 1 }
    }
    /** Live owner dvks (§2): sub-cert by a live owner root. */
    private fun liveDvks(): Map<String, Cert.Sub> {
        val cs = certs(); val roots = ownerRoots(cs); val revoked = revokedRoots(cs)
        return subCerts().filterValues { it.ev.pubkey in roots && it.ev.pubkey !in revoked }
    }

    // --- Trust (§3.1, §3.4) --------------------------------------------------------------------------------

    /** Triggers this device knows of: "roots:x", "members:x", "grants:x". */
    private fun knownTriggers(): Set<String> {
        val cs = certs(); val roots = ownerRoots(cs)
        val t = HashSet<String>()
        for (r in revokedRoots(cs).keys) {
            t += "roots:$r"
            // A counting revocation of a labelled root makes its label's trigger known (§3.1, as of adb7f42).
            roots[r]?.let { l -> if (l.startsWith("grant:")) t += "grants:${l.removePrefix("grant:")}" else t += "members:$l" }
        }
        vs.blockedMembers().forEach { t += "members:$it" }
        vs.removedMembers().forEach { t += "members:$it" }
        vs.revokedGrants().forEach { t += "grants:$it" }
        return t
    }
    /** Labels this device distrusts: blocked or removed members and revoked grants (a block distrusts; only a cycle revokes). */
    private fun badLabels(): Set<String> = vs.blockedMembers() + vs.removedMembers() + vs.revokedGrants().map { "grant:$it" }
    /** Live owner roots this device trusts. Without Keykin, every live one. */
    private fun trustedRoots(): Set<String> {
        val cs = certs(); val roots = ownerRoots(cs); val revoked = revokedRoots(cs)
        val live = roots.filterKeys { it !in revoked }
        if (!vs.isKeykin) return live.keys
        val bad = badLabels()
        val replaced = p.strs("replacedRoots").toSet()
        val counts = live.filterKeys { it !in replaced }.values.filterNotNull().groupingBy { it }.eachCount()
        return live.filter { (r, l) -> l != null && l !in bad && (counts[l] ?: 0) <= 1 || r == vs.rootPub }.keys
    }
    fun devices(): List<DeviceRow> {
        val cs = certs(); val roots = ownerRoots(cs); val revoked = revokedRoots(cs); val trusted = trustedRoots()
        val names = subCerts().values.groupBy { it.ev.pubkey }.mapValues { it.value.maxByOrNull { s -> s.n }!!.name }
        val counts = roots.filterKeys { it !in revoked }.values.filterNotNull().groupingBy { it }.eachCount()
        return roots.filterKeys { it !in revoked }.map { (r, l) ->
            DeviceRow(r, names[r] ?: "A device", l, r in trusted, when {
                r in trusted -> null
                l != null && (counts[l] ?: 0) > 1 -> "Two devices claim the same Keykin key: remove the one you don't know"
                l != null && l in badLabels() -> "Blocked in Keykin"
                else -> "Not trusted"
            })
        }
    }

    // --- Generations (§3.1) ------------------------------------------------------------------------------

    /** The first generation: anchored at the vault's start (kept when this device first held it). */
    private fun first(): Gen? = p.strOrNull("first")?.let { gens[it] } ?: gens.values.filter { it.prev == null }.let { firsts ->
        // Several unchained firsts (a forged keyring copy): the one whose genesis epoch holds the genesis commit.
        val rho0 = Readers.of(emptyList()).rho
        val pick = if (firsts.size <= 1) firsts.firstOrNull() else firsts.firstOrNull { g -> val kid = Keys.kid(Keys.epoch(g.secret, id, rho0)); commits.values.any { c -> c.kid == kid && c.ops.any { op -> op.optString("op") == "auth" && op.optJSONObject("event")?.optString("id") == genesisId } } }
        pick?.also { p.put("first", it.id) }
    }
    private fun depth(g: Gen): Int? { var d = 0; var x: Gen? = g; val seen = HashSet<String>(); while (x != null) { if (!seen.add(x.id)) return null; if (x.prev == null) return if (x.id == first()?.id) d else null; x = gens[x.prev]; d++ }; return null }
    private fun anchored(g: Gen) = depth(g) != null
    private fun chainOf(g: Gen): List<Gen> { val out = ArrayList<Gen>(); var x: Gen? = g; while (x != null && out.size < 10_000) { out += x; x = x.prev?.let { gens[it] } }; return out }

    /** Generations named by `rekey`s, with the dvk that named them. */
    private fun rekeys(): List<Pair<String, JSONObject>> = commits.values.flatMap { c -> c.ops.filter { it.optString("op") == "rekey" }.map { c.dvk to it } }

    /** Vetted generations (§3.1), checked now (trust is checked at use). */
    private fun vetted(): Set<String> {
        val live = liveDvks(); val trusted = trustedRoots(); val known = knownTriggers()
        val named = HashSet<String>(mine)
        first()?.let { named += it.id }
        for ((dvk, op) in rekeys()) { val s = live[dvk] ?: continue; if (s.ev.pubkey !in trusted) continue; op.optJSONArray("state")?.optString(0)?.let { named += it } }
        val out = HashSet<String>()
        for (gid in named) {
            val g = gens[gid] ?: continue
            if (!anchored(g)) continue
            val chain = chainOf(g)
            // Never vetted while it (or a parent) names a trigger this device doesn't know of yet.
            if (chain.any { x -> (x.triggers() - known).isNotEmpty() }) continue
            chain.forEach { out += it.id }
        }
        return out
    }
    /** The current generation: the vetted one with the longest chain, then the lower id. */
    fun current(): Gen? { val v = vetted(); return gens.values.filter { it.id in v }.sortedWith(compareByDescending<Gen> { depth(it) ?: -1 }.thenBy { it.id }).firstOrNull() }
    fun generations(): List<Gen> = gens.values.toList()

    /** Merges generations from a keyring payload (by id; `because` by union). */
    @Synchronized fun mergeKeyring(payload: JSONObject) {
        if (payload.optString("vault") != id || payload.optString("genesis") != genesisId) return
        for (o in payload.arrOr("gens").objs()) {
            val g = try { Gen.of(o) } catch (_: Malformed) { continue }
            if (!g.ok) continue
            gens[g.id] = gens[g.id]?.let { Gen.merge(it, g) } ?: g
        }
        if (p.arrOr("relays").length() == 0) p.put("relays", payload.arrOr("relays"))
        if (p.arrOr("servers").length() == 0) p.put("servers", payload.arrOr("servers"))
        save()
    }
    private fun keyringPayload(): JSONObject = JSONObject().put("vault", id).put("genesis", genesisId).put("owner", owner).put("name", p.optString("name"))
        .put("profile", profile).put("role", "owner").put("gens", JSONArray(gens.values.filter(::anchored).sortedBy { depth(it) }.map { it.json() }))
        .put("relays", JSONArray(vaultRelays())).put("servers", JSONArray(servers())).put("d", p.str("d"))

    /** Reads every keyring entry, merges, and republishes this device's if a relay copy lacks a generation it holds (§3.6). */
    private fun readKeyrings(): Boolean {
        val all = vs.keyrings() ?: return false
        val mineEntries = all.filter { it.second.optString("vault") == id }
        mineEntries.forEach { mergeKeyring(it.second) }
        val held = gens.values.filter(::anchored).map { it.id }.toSet()
        val myCopy = mineEntries.filter { it.second.optString("d") == p.str("d") }.maxByOrNull { it.first.createdAt }
        val lacks = myCopy == null || held.any { g -> myCopy.second.arrOr("gens").objs().none { it.optString("id") == g } }
        if (lacks) {
            // A copy at this d that isn't this device's newest (a stale or forged one): a fresh d (§3.6).
            if (myCopy != null) p.put("d", hex(random(16)))
            // (A device the box refuses can't sign it; it carries on with what it holds.)
            if (try { vs.publishKeyring(keyringPayload()).isNotEmpty() } catch (_: Exception) { false }) save()
        }
        return true
    }

    // --- Epochs (§3.1) -----------------------------------------------------------------------------------

    class Epoch(val kid: String, val k: ByteArray, val gen: String, val rho: String, val locator: String, val opsKey: ByteArray)
    private val epochs = HashMap<String, Epoch>()
    private fun epochOf(g: Gen, rho: String): Epoch { val k = Keys.epoch(g.secret, id, rho); val kid = Keys.kid(k); return epochs.getOrPut(kid) { Epoch(kid, k, g.id, rho, Keys.locator(k, id), Keys.opsKey(k, id)) } }
    /** Every epoch this device can derive: each generation it holds with every ρ it has seen named, plus the current one. */
    private fun knownEpochs(): Collection<Epoch> {
        val rhos = HashSet<String>(); rhos += Readers.of(emptyList()).rho; rhos += Readers.of(auths()).rho
        rekeys().forEach { (_, op) -> op.optString("rho").takeIf { isHex(it, 32) }?.let { rhos += it } }
        for (g in gens.values.filter(::anchored)) for (r in rhos) epochOf(g, r)
        return epochs.values
    }
    /** The epoch to write under now: the current generation and the current ρ. */
    fun writeEpoch(): Epoch? = current()?.let { epochOf(it, Readers.of(auths()).rho) }

    // --- Config ------------------------------------------------------------------------------------------

    fun config(): JSONObject? = Readers.config(auths())
    fun vaultRelays(): List<String> = (config()?.strs("relays") ?: p.strs("relays")).ifEmpty { p.strs("relays") }
    fun servers(): List<String> = (config()?.strs("servers") ?: p.strs("servers")).ifEmpty { p.strs("servers") }
    /** Where blobs go: the account's own list as published now, else the vault's config (the defaults it was made with). */
    fun writeServers(): List<String> = Storage.normalize(vs.account.servers()).ifEmpty { Storage.normalize(servers()) }
    /** Where blobs are looked for: the account's list, then the vault's config. */
    fun readServers(): List<String> = Storage.normalize(vs.account.servers() + servers())

    // --- Blobs ----------------------------------------------------------------------------------------------

    private val storageKey: ByteArray get() = Keys.storage(dvk)
    /** Encrypts and uploads [plain] to two servers; the ref once on [need] of them (two for what the vault relies on, §6.3). */
    fun upload(plain: ByteArray, need: Int = 1, key: ByteArray? = null): BlobRef? {
        val s = if (key != null) Nv1.seal(plain, key) else Nv1.seal(plain)
        val servers = writeServers()
        val took = vs.storage.put(s, servers, 2)
        if (took.size < minOf(need, servers.size).coerceAtLeast(1)) return null
        store.put("blob/" + refKey(s.ref), hex(plain))
        return s.ref
    }
    private fun refKey(r: BlobRef) = hex(sha256(utf8(canon(r.json)))).substring(0, 32)
    /** A blob's plaintext: kept here, or fetched from the vault's servers (null: missing for now). */
    /** A blob's plaintext; kept in the store unless [keep] is false (a picture: the app keeps its own copy). */
    fun fetch(ref: BlobRef, keep: Boolean = true): ByteArray? {
        store.get("blob/" + refKey(ref))?.let { return unhex(it) }
        val b = vs.storage.get(ref, readServers()) ?: return null
        if (keep) store.put("blob/" + refKey(ref), hex(b))
        return b
    }

    // --- This device's keys -------------------------------------------------------------------------------

    private val n: Int get() = p.optInt("n", 0)
    private val dvk: ByteArray get() = Keys.dvk(vs.root, genesisId, n)
    val dvkPub: String get() = Keys.pub(dvk)
    private fun subCert(): Ev {
        p.optJSONObject("subCert")?.let { o -> Ev.of(o).takeIf { (Cert.of(it) as? Cert.Sub)?.dvk == dvkPub }?.let { return it } }
        val st = storageKey
        val body = JSONObject().put("type", "sub").put("genesis", genesisId).put("n", n).put("dvk", dvkPub).put("name", vs.deviceName)
            .put("storage", Keys.pub(st)).put("storage_sig", Keys.sign(th("nostr-vault/storage/1", unhex(dvkPub)), st))
        val e = Ev.sign(vs.root, clock.now(), Kinds.CERT, emptyList(), canon(body))
        p.put("subCert", e.json()); save()
        return e
    }
    private fun certOps(): List<JSONObject> = listOf(JSONObject().put("op", "auth").put("event", vs.deviceCert().json()), JSONObject().put("op", "auth").put("event", subCert().json()))

    // --- Checkpoints (§9) -------------------------------------------------------------------------------

    class Cp(val commit: Commit, val puts: List<Put>, val dead: List<Triple<String, String, String>>, val authority: List<Ev>, val kept: List<Commit>, val covers: JSONObject, val root: JSONObject)
    private val cps = HashMap<String, Cp?>()
    /** A checkpoint commit's content, loaded whole (root, lists and every shard), or null if something is missing. */
    private fun loadCp(c: Commit): Cp? {
        if (cps.containsKey(c.id)) cps[c.id]?.let { return it }
        val op = c.ops.firstOrNull { it.optString("op") == "checkpoint" } ?: return null
        val ref = BlobRef.of(op.opt("ref")) ?: return null
        val cp = try {
            val root = obj(String(fetch(ref) ?: return null.also { gaps++ }))
            fun list(k: String): JSONArray = when (val v = root.opt(k)) { is JSONArray -> v; is JSONObject -> BlobRef.of(v.opt("ref"))?.let { fetch(it) }?.let { arr(String(it)) } ?: throw Malformed("list $k"); else -> JSONArray() }
            val puts = ArrayList<Put>(); val dead = ArrayList<Triple<String, String, String>>()
            for (s in root.arrOr("shards")) {
                val sref = BlobRef.of(s) ?: throw Malformed("shard")
                val shard = obj(String(fetch(sref) ?: return null.also { gaps++ }))
                val es = shard.objOr("entries")
                for (e in es.keys()) {
                    val eo = es.getJSONObject(e)
                    eo.arrOr("current").objs().forEach { Put.of(it)?.takeIf { p -> p.entry == e }?.let(puts::add) }
                    val d = eo.objOr("dead"); for (v in d.keys()) d.strs(v).forEach { s2 -> dead += Triple(e, v, s2) }
                }
            }
            Cp(c, puts, dead, list("authority").objs().mapNotNull { try { Ev.of(it) } catch (_: Malformed) { null } },
                list("kept").objs().mapNotNull { Commit.of(it) }, root.objOr("covers"), root)
        } catch (_: Exception) { null }
        cps[c.id] = cp
        return cp
    }
    private operator fun JSONArray.iterator(): Iterator<Any?> = (0 until length()).map { opt(it) }.iterator()
    private fun checkpointsHeld(): Map<String, Cp> = cps.filterValues { it != null }.mapValues { it.value!! }

    /** The counting checkpoints (§9.2): each live root's newest by (n, seq), plus every kept one. */
    private fun countingCps(): List<Cp> {
        val live = liveDvks()
        val withCp = commits.values.filter { it.dvk in live && it.ops.any { op -> op.optString("op") == "checkpoint" } }
        val newest = withCp.groupBy { live[it.dvk]!!.ev.pubkey }.values.map { cs -> cs.maxWith(compareBy({ live[it.dvk]!!.n }, { it.seq })) }
        val out = newest.mapNotNull { loadCp(it) }.toMutableList()
        // Kept: in a revocation by the owner of an owner root, the commit signed by one of that root's dvks.
        val cs = certs(); val subs = subCerts()
        val keep = revokedRoots(cs).flatMap { (root, rs) -> rs.flatMap { r -> r.keep.map { it to root } } }.toMap()
        val keptCommits = commits.values.filter { it.dvk in live }.flatMap { c -> c.ops.filter { it.optString("op") == "kept" }.mapNotNull { Commit.of(it.optJSONObject("commit")) } } +
            out.flatMap { it.kept }
        for (k in keptCommits.distinctBy { it.id }) {
            val root = keep[k.id] ?: continue
            if (subs[k.dvk]?.ev?.pubkey != root) continue
            hold(k); loadCp(k)?.let { if (out.none { o -> o.commit.id == it.commit.id }) out += it }
        }
        return out
    }

    // --- The state (§5.3) --------------------------------------------------------------------------------

    private var stateCache: State? = null
    private fun opsOf(c: Commit): List<JSONObject> = c.ops.flatMap { op ->
        if (op.optString("op") == "batch") BlobRef.of(op.opt("ref"))?.let { fetch(it) }?.let { b -> try { arr(String(b)).objs().filter { it.optString("op") == "put" || it.optString("op") == "contrib" } } catch (_: Malformed) { null } } ?: emptyList<JSONObject>().also { gaps++ }
        else listOf(op)
    }
    @Synchronized fun state(): State {
        stateCache?.let { return it }
        val live = liveDvks()
        val counting = ArrayList<Put>(); val others = ArrayList<Put>()
        for (c in commits.values) for (op in opsOf(c)) if (op.optString("op") == "put") Put.of(op)?.let { if (c.dvk in live) counting += it else others += it }
        val cps = countingCps()
        cps.forEach { counting += it.puts }
        return State.of(counting, others, cps.flatMap { it.dead }).also { stateCache = it }
    }

    // --- Reading (§7.2, §10) -------------------------------------------------------------------------------

    private val headsSeen: JSONObject get() = p.optJSONObject("headsSeen") ?: JSONObject().also { p.put("headsSeen", it) }
    private val segmentsRead: MutableSet<String> = p.strs("segmentsRead").toMutableSet()

    /** Takes one head (§10, 1): checked, decrypted, never older than one already taken at its address. */
    private fun takeHead(e: Ev) {
        if (e.kind != Kinds.HEAD || !e.valid()) return
        val kid = e.tag("d") ?: return
        val ep = epochs[kid] ?: return
        if (e.tag("v") != ep.locator) return
        val addr = "${e.pubkey}:$kid"
        if (headsSeen.optLong(addr, -1) > e.createdAt) return
        val body = try { obj(Keys.decrypt(e.content, ep.opsKey) ?: return) } catch (_: Malformed) { return }
        if (body.optString("vault") != id || body.optString("genesis") != genesisId || body.optString("kid") != kid) return
        headsSeen.put(addr, e.createdAt)
        for (x in body.arrOr("commits").objs()) Commit.of(x)?.takeIf { it.dvk == e.pubkey && it.kid == kid }?.let(::hold)
        body.optJSONObject("checkpoint")?.let { Commit.of(it) }?.takeIf { it.dvk == e.pubkey }?.let(::hold)
        BlobRef.of(body.opt("segment"))?.let { readSegments(it, e.pubkey) }
    }
    /** A segment chain back from [ref], until it reaches commits already held (§4.1). */
    private fun readSegments(ref: BlobRef, dvk: String) {
        var r: BlobRef? = ref; var hops = 0
        while (r != null && hops++ < 1000) {
            val k = refKey(r)
            if (k in segmentsRead) return
            val seg = try { obj(String(fetch(r) ?: return Unit.also { gaps++ })) } catch (_: Malformed) { return }
            if (seg.optString("genesis") != genesisId || seg.optString("dvk") != dvk) return
            var allHeld = true
            for (x in seg.arrOr("commits").objs()) Commit.of(x)?.takeIf { it.dvk == dvk }?.let { if (hold(it)) allHeld = false }
            segmentsRead += k; p.put("segmentsRead", JSONArray(segmentsRead.toList()))
            if (allHeld && hops > 1) return
            r = BlobRef.of(seg.opt("prev"))
        }
    }

    /**
     * Catching up (§8.3): keyring entries, Keykin's view, every head under every epoch this device can derive (until no
     * new ρ turns up), segments and checkpoints. True once a majority of the vault's relays answered.
     */
    @Synchronized fun catchUp(): Boolean {
        gaps = 0; trouble = null
        readKeyrings()
        vs.askKeykin()
        var answeredAny = false; var majority = false
        val asked = HashSet<String>()
        repeat(8) {
            val locs = knownEpochs().map { it.locator }.filter { it !in asked }
            if (locs.isEmpty()) return@repeat
            asked += locs
            val relays = vaultRelays()
            val a = vs.relays.query(relays, JSONObject().put("kinds", JSONArray(listOf(Kinds.HEAD))).put("#v", JSONArray(locs)))
            if (a.answered.isNotEmpty()) answeredAny = true
            if (a.answered.size * 2 > relays.size) majority = true
            // Newest first per address, so an older copy is refused.
            a.events.sortedByDescending { it.createdAt }.forEach(::takeHead)
        }
        stateCache = null
        countingCps()
        if (!answeredAny) trouble = "Can't reach relays"
        save()
        return majority || answeredAny
    }

    // --- Writing (§7.1) -------------------------------------------------------------------------------------

    private val queue: JSONArray get() = p.optJSONArray("queue") ?: JSONArray().also { p.put("queue", it) }
    /** Ops wait here, unnumbered, until the next publish after a catch-up (§7.1). */
    @Synchronized fun enqueue(ops: List<JSONObject>) { ops.forEach { queue.put(it) }; save() }
    val pending: Int get() = queue.length()

    private fun myCommits(): List<Commit> = commits.values.filter { it.dvk == dvkPub }.sortedBy { it.seq }
    /** The next commit of this dvk; its certs ride along in its first commit, and wherever [certs] asks (§5.2). */
    private fun nextCommit(kid: String, ops: List<JSONObject>, certs0: Boolean = false): Commit {
        val last = myCommits().lastOrNull()
        val certs = if (certs0 || last == null || myCommits().none { c -> c.ops.any { it.optString("op") == "auth" && it.optJSONObject("event")?.optString("id") == subCert().id } }) certOps() else emptyList()
        val c = Commit.make(dvk, genesisId, (last?.seq ?: -1) + 1, last?.id, kid, clock.now(), certs + ops)
        hold(c); return c
    }

    /** The newest checkpoint commit of this dvk. */
    private fun myCheckpoint(): Commit? = myCommits().lastOrNull { c -> c.ops.any { it.optString("op") == "checkpoint" } }
    private val seg: JSONObject get() = p.optJSONObject("seg") ?: JSONObject().put("commits", JSONArray()).also { p.put("seg", it) }

    /** This dvk's commits under [kid] not yet in its segment chain. */
    private fun headCommits(kid: String): List<Commit> { val inSeg = seg.strs("ids").toSet(); return myCommits().filter { it.kid == kid && it.id !in inSeg } }

    /** Moves [cs] into the segment (a new blob each time, a new segment past 64 KiB), on two servers first (§4.1). */
    private fun toSegment(cs: List<Commit>): Boolean {
        if (cs.isEmpty()) return true
        var current = seg.arrOr("commits").objs().toMutableList()
        var prev: Any? = seg.opt("prev")
        if (canon(JSONArray(current)).length > 64 * 1024) { prev = seg.opt("ref"); current = mutableListOf() }
        current.addAll(cs.map { it.json() })
        val plain = JSONObject().put("genesis", genesisId).put("dvk", dvkPub).put("commits", JSONArray(current)).put("prev", prev ?: JSONObject.NULL)
        val ref = upload(utf8(canon(plain)), need = 2) ?: return false
        p.put("seg", JSONObject().put("commits", JSONArray(current)).put("prev", prev ?: JSONObject.NULL).put("ref", ref.json)
            .put("ids", JSONArray(seg.strs("ids") + cs.map { it.id })))
        segmentsRead += refKey(ref)
        return true
    }

    /** Publishes this dvk's head under [ep] (§4.1). True when at least one relay took it. */
    private fun publishHead(ep: Epoch): Boolean {
        var cs = headCommits(ep.kid)
        // Over 8 KiB of commits: the oldest go into the segment.
        while (cs.size > 1 && cs.sumOf { it.c.length } > 8 * 1024) { if (!toSegment(listOf(cs.first()))) break; cs = headCommits(ep.kid) }
        val seen = JSONObject()
        commits.values.filter { it.dvk != dvkPub }.groupBy { it.dvk }.entries.sortedByDescending { e -> e.value.maxOf { it.time } }.take(32)
            .forEach { (d, list) -> val top = list.maxBy { it.seq }; seen.put(d, JSONArray().put(top.seq).put(top.id)) }
        val body = JSONObject().put("v", 1).put("vault", id).put("genesis", genesisId).put("kid", ep.kid).put("commits", JSONArray(cs.map { it.json() }))
            .put("segment", seg.optJSONObject("ref") ?: JSONObject.NULL).put("checkpoint", myCheckpoint()?.json() ?: JSONObject.NULL).put("seen", seen)
        val last = p.optJSONObject("myHeads")?.optLong(ep.kid, 0) ?: 0
        val at = maxOf(clock.now(), last + 1)
        val e = Ev.sign(dvk, at, Kinds.HEAD, listOf(listOf("d", ep.kid), listOf("v", ep.locator)), Keys.encrypt(canon(body), ep.opsKey))
        val ok = vs.relays.publish(vaultRelays(), e)
        if (ok.isNotEmpty()) { p.put("myHeads", (p.optJSONObject("myHeads") ?: JSONObject()).put(ep.kid, at)); headsSeen.put("$dvkPub:${ep.kid}", at) }
        return ok.isNotEmpty()
    }

    /**
     * Publishes what waits (§7.1): catches up first, starts a generation for any uncovered trigger, revokes roots a
     * cycle or a revoked grant removed, then numbers and signs the queued ops under the current epoch (a `rekey` under
     * the old one first when the epoch moved) and publishes the head. True when everything queued went out.
     */
    @Synchronized fun sync(): Boolean {
        if (!catchUp()) return false
        if (removed) { trouble = "This device was removed"; return false }
        revokeRemoved()
        newGenerationIfNeeded()
        return publish()
    }

    private fun publish(): Boolean {
        val ep = writeEpoch() ?: return false.also { trouble = "No key to write with yet" }
        val last = myCommits().lastOrNull()
        if (last != null && last.kid != ep.kid) moveEpoch(epochs[last.kid] ?: knownEpochs().let { epochs[last.kid] }, ep)
        else if (last == null && ep.gen != first()?.id && !reached(ep.kid)) moveEpoch(null, ep)
        val ops = queue.objs()
        if (ops.isNotEmpty()) {
            for (chunk in chunked(ops)) nextCommit(ep.kid, chunk)
            p.put("queue", JSONArray())
        } else if (last == null) nextCommit(ep.kid, emptyList())
        save()
        val ok = publishHead(ep)
        save()
        return ok
    }
    /** Ops in commits of at most 12 KiB; puts past that go in a batch blob (§5.1). */
    private fun chunked(ops: List<JSONObject>): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>(); var cur = ArrayList<JSONObject>(); var size = 0
        val budget = 9 * 1024
        val big = ops.filter { (it.optString("op") == "put" || it.optString("op") == "contrib") }.sumOf { canon(it).length } > budget
        if (big) {
            val puts = ops.filter { it.optString("op") == "put" || it.optString("op") == "contrib" }
            val ref = upload(utf8(canon(JSONArray(puts))), need = 2) ?: throw IllegalStateException("can't sync: storage unavailable")
            return listOf(listOf(JSONObject().put("op", "batch").put("ref", ref.json))) + chunked(ops.filter { it !in puts }).filter { it.isNotEmpty() }
        }
        for (op in ops) { val s = canon(op).length; if (size + s > budget && cur.isNotEmpty()) { out += cur; cur = ArrayList(); size = 0 }; cur += op; size += s }
        if (cur.isNotEmpty()) out += cur
        return out
    }
    /** Whether a live owner dvk's `rekey` reaches [kid]. */
    private fun reached(kid: String): Boolean { val live = liveDvks(); return rekeys().any { (d, op) -> d in live && op.optString("to") == kid } }

    /**
     * Moving to a new epoch (§4.1, §8.3): the last commit under the old epoch holds the `rekey` and, in `auth` ops, the
     * authority events that moved ρ, every owner-root revocation held and this device's certs; that head goes out, and
     * every commit of it moves into the segment so the next head reaches the whole chain.
     */
    private fun moveEpoch(from: Epoch?, to: Epoch) {
        val old = from ?: current()?.prev?.let { gens[it] }?.let { epochOf(it, to.rho) } ?: return
        val rs = Readers.of(auths())
        val moving = auths().filter { it.ev.id in rs.ended || it.adds.isNotEmpty() }.map { JSONObject().put("op", "auth").put("event", it.ev.json()) }
        val revs = revokedRoots().values.flatten().map { JSONObject().put("op", "auth").put("event", it.ev.json()) }
        val rekey = JSONObject().put("op", "rekey").put("to", to.kid).put("state", JSONArray(listOf(to.gen))).put("rho", to.rho).put("wraps", JSONArray())
        nextCommit(old.kid, listOf(rekey) + moving + revs, certs0 = true)
        publishHead(old)
        toSegment(headCommits(old.kid))
        save()
    }

    // --- Generations started here (§3.1) and removals (§8.5) ---------------------------------------------

    /** Triggers this device knows of that no vetted generation on the current chain lists. */
    fun uncovered(): Set<String> {
        val cur = current() ?: return emptySet()
        val covered = chainOf(cur).flatMap { it.triggers() }.toSet()
        val t = HashSet<String>()
        revokedRoots().keys.forEach { t += "roots:$it" }
        vs.removedMembers().forEach { t += "members:$it" }
        vs.revokedGrants().forEach { t += "grants:$it" }
        return t - covered
    }
    /** After every catch-up: a new generation on top for every uncovered trigger (§3.1). */
    private fun newGenerationIfNeeded() {
        val u = uncovered(); if (u.isEmpty()) return
        val cur = current() ?: return
        fun of(k: String) = u.filter { it.startsWith("$k:") }.map { it.substringAfter(':') }
        startGeneration(Gen.because(of("roots"), of("members"), of("grants")))
    }
    /** A new generation on top of the current one, saved in the keyring before anything is written under it. */
    @Synchronized fun startGeneration(because: JSONObject): Gen? {
        val cur = current() ?: return null
        val v = random(32)
        val g = Gen(Keys.genId(v, cur.id), v, cur.id, because)
        gens[g.id] = g; mine += g.id; save()
        // The keyring first, so the other devices can follow the rekey that comes next.
        if (vs.publishKeyring(keyringPayload()).isEmpty()) { gens.remove(g.id); mine.remove(g.id); save(); trouble = "Couldn't save the new key"; return null }
        return g
    }

    /** Owner roots a cycle removed from Keykin, or a revoked grant labels, that aren't revoked yet (§3.4, §8.5). */
    fun toRevoke(): List<String> {
        val removed = vs.removedMembers(); val grants = vs.revokedGrants().map { "grant:$it" }.toSet()
        val revoked = revokedRoots()
        return ownerRoots().filter { (r, l) -> r !in revoked && r != vs.rootPub && l != null && (l in removed || l in grants) }.keys.toList()
    }
    private fun revokeRemoved() { for (r in toRevoke()) revoke(r) }

    /**
     * Revokes an owner root (§8.5): a checkpoint of what this device holds first, then the npub's revocation keeping each
     * of the root's dvks' newest checkpoint commits, carried with those commits (`kept` ops); the next sync starts the
     * new generation.
     */
    @Synchronized fun revoke(root: String) {
        if (root in revokedRoots()) return
        writeCheckpoint()
        val subs = subCerts().values.filter { it.ev.pubkey == root }.map { it.dvk }.toSet()
        val keptCs = commits.values.filter { it.dvk in subs && it.ops.any { op -> op.optString("op") == "checkpoint" } }.groupBy { it.dvk }.values.map { cs -> cs.maxBy { it.seq } }
        val body = JSONObject().put("type", "revoke").put("root", root).put("keep", JSONArray(keptCs.map { it.id }.sorted()))
        val rev = vs.account.sign(clock.now(), Kinds.CERT, emptyList(), canon(body))
        if ((Cert.of(rev) as? Cert.Revoke)?.root != root) throw IllegalStateException("the signer returned a bad revocation")
        enqueue(listOf(JSONObject().put("op", "auth").put("event", rev.json())) + keptCs.map { JSONObject().put("op", "kept").put("commit", it.json()) })
        stateCache = null
        // Out under the current epoch now, so the generation that follows names the root (§3.1).
        publish()
    }

    // --- Writing a checkpoint (§9.1) --------------------------------------------------------------------------

    /** Ops not yet in this root's counting checkpoint, and the oldest one's time. */
    fun uncheckpointed(): Pair<Int, Long?> {
        val cp = myCheckpoint()?.let { loadCp(it) }
        val covered = cp?.covers ?: JSONObject()
        var count = 0; var oldest: Long? = null
        for (c in commits.values) {
            val tip = covered.optJSONArray(c.dvk)?.optJSONArray(0)?.optLong(0) ?: -1
            if (c.seq > tip) { count += c.ops.size; oldest = minOf(oldest ?: c.time, c.time) }
        }
        return count to oldest
    }
    /** Writes a checkpoint when due (§9.1): over 5,000 ops, or any op older than 3 days, not in it. */
    @Synchronized fun checkpointIfDue(): Boolean {
        val (count, oldest) = uncheckpointed()
        if (count > 5000 || (oldest != null && clock.now() - oldest > 3 * 86_400)) return writeCheckpoint()
        return false
    }
    /** The whole state as a checkpoint: root blob, shards, then a commit holding only it and this device's certs. */
    @Synchronized fun writeCheckpoint(): Boolean {
        val ep = writeEpoch() ?: return false
        val st = state()
        val dedup = Keys.dedupKey(k0 ?: return false, id)
        val all = (st.entries.keys + st.dead.keys).toSortedSet()
        var depth = 0; while (depth < 4 && all.size >= 2000 * (1 shl (4 * depth))) depth++
        val shards = ArrayList<JSONObject>()
        for (i in 0 until (1 shl (4 * depth))) {
            val prefix = if (depth == 0) "" else Integer.toHexString(i).padStart(depth, '0')
            val es = JSONObject()
            for (e in all.filter { it.startsWith(prefix) }) {
                val cur = st.entries[e]?.current.orEmpty().sortedBy { it.ver }
                val dead = JSONObject(); st.dead[e]?.forEach { (v, sups) -> dead.put(v, JSONArray(sups.sorted())) }
                es.put(e, JSONObject().put("current", JSONArray(cur.map { it.json })).put("dead", dead))
            }
            val plain = utf8(canon(JSONObject().put("entries", es)))
            shards += (upload(plain, need = 2, key = Keys.shardKey(dedup, plain)) ?: return false).json
        }
        val covers = JSONObject()
        commits.values.groupBy { it.dvk }.forEach { (d, cs) -> val top = cs.maxBy { it.seq }; covers.put(d, JSONArray().put(JSONArray().put(top.seq).put(top.id))) }
        val kept = countingCps().filter { it.commit.dvk != dvkPub && it.commit.id in revokedRoots().values.flatten().flatMap { r -> r.keep } }.map { it.commit.json() }
        val root = JSONObject().put("genesis", genesisId).put("covers", covers).put("lost", JSONObject())
            .put("authority", JSONArray(authority().values.sortedBy { it.id }.map { it.json() })).put("kept", JSONArray(kept)).put("decided", JSONArray())
            .put("unknown_ops", JSONArray()).put("depth", depth).put("shards", JSONArray(shards))
            .put("prev", myCheckpoint()?.ops?.firstOrNull { it.optString("op") == "checkpoint" }?.opt("ref") ?: JSONObject.NULL)
        val ref = upload(utf8(canon(root)), need = 2) ?: return false
        // Queued ops go first, under the current epoch, so the checkpoint commit holds only itself and the certs.
        if (!publish()) return false
        nextCommit(ep.kid, listOf(JSONObject().put("op", "checkpoint").put("ref", ref.json)), certs0 = true)
        save()
        return publishHead(ep).also { save() }
    }

    // --- Creating ------------------------------------------------------------------------------------------

    /** Right after [Vaults.create]: the keyring entry, then the first head with the certs and the genesis. */
    internal fun created(genesis: Ev) {
        if (vs.publishKeyring(keyringPayload()).isEmpty()) throw IllegalStateException("couldn't publish the keyring entry")
        val ep = writeEpoch() ?: throw IllegalStateException("no epoch")
        nextCommit(ep.kid, listOf(JSONObject().put("op", "auth").put("event", genesis.json())))
        save()
        publishHead(ep); save()
    }

    // --- Removing this device or another (§8.5) ------------------------------------------------------------

    /** "Lost or stolen" from this app, for another owner device: revoked at once, then a new generation on the next sync. */
    @Synchronized fun reportLost(root: String) { if (!catchUp()) return; revoke(root); newGenerationIfNeeded(); publish() }

    /** For tests and debugging: the counts behind the state. */
    fun debug(): JSONObject = JSONObject().put("commits", commits.size).put("live", liveDvks().size).put("gens", gens.size).put("current", current()?.id)
        .put("epoch", writeEpoch()?.kid).put("entries", state().entries.size).put("gaps", gaps).put("trouble", trouble).put("pending", pending)
        .put("uncovered", JSONArray(uncovered().sorted())).put("trusted", JSONArray(trustedRoots().sorted()))
        .put("servers", JSONArray(writeServers())).put("refused", JSONObject(vs.storage.refused as Map<*, *>))
        .put("lastPut", vs.storage.lastPut?.let { (sha, on) -> JSONObject().put("sha", sha).put("on", JSONArray(on)) })
}
