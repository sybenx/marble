package cc.aaro.cosign

import android.content.Context
import android.net.Uri
import cc.aaro.cosign.core.Clock
import cc.aaro.cosign.core.Policy
import cc.aaro.keykin.core.Child
import cc.aaro.keykin.core.Children
import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.Fingerprint
import cc.aaro.keykin.core.Held
import cc.aaro.keykin.core.Invite
import cc.aaro.keykin.core.JoinText
import cc.aaro.keykin.core.KeykinException
import cc.aaro.keykin.core.Me
import cc.aaro.keykin.core.Qr
import cc.aaro.keykin.core.Send
import cc.aaro.keykin.core.SiteSigner
import cc.aaro.keykin.core.Texts
import cc.aaro.qrst.core.CodeCheck
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import cc.aaro.qrst.core.Mode
import cc.aaro.qrst.core.Nip19
import cc.aaro.qrst.core.Outcome
import cc.aaro.qrst.core.PairingParams
import cc.aaro.qrst.core.PairingUri
import cc.aaro.qrst.core.Phase
import cc.aaro.qrst.core.SignerTicket
import cc.aaro.qrst.core.SignerTicketProfile
import cc.aaro.qrst.core.SystemEnv
import cc.aaro.qrst.core.hexToBytes
import cc.aaro.qrst.core.isHex32
import cc.aaro.qrst.core.toHex
import cc.aaro.qrst.net.KeyValue
import cc.aaro.qrst.net.Store
import cc.aaro.qrst.net.Transfer
import cc.aaro.qrst.net.TransferDeps
import cc.aaro.qrst.net.TransferView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One account on this phone, on Keykin v2: this phone holds one piece of the key and its box holds
 * another, and any two pieces sign (the Keykin repo's SPEC.md). No whole key exists unless you form
 * it with a fingerprint.
 *
 * What it does: makes an account with a box or joins one; signs and encrypts for Marble, for apps on
 * this phone (NIP-55) and for sites (NIP-46: a scanned code, bunker:// and nostrconnect://); holds
 * anything destructive for your yes; lists and removes what is signed in; keeps what a change
 * replaced, so it can be undone; and makes account changes (add a device, unblock, cycle the keys,
 * form the whole key, log in to the box's page) with the chip key, after a fingerprint.
 *
 * It lives in the signer process only (Proc); the UI has a stand-in, [Phone], that asks it over [ISigner].
 */
class Signer(private val context: Context, val slot: String = "0") {
    private fun named(base: String) = if (slot == "0") base else "$base.$slot"
    interface Listener {
        fun onAsk(ask: Ask); fun onAskGone(id: String); fun onStatus()
        fun onDeleting(d: Deleting) {}; fun onDeletingGone(batch: String) {}
        fun onBatch(b: BatchAsk) {}; fun onBatchGone(batch: String) {}
        /** Something to say while the app isn't on screen (a sign-in that didn't go through). A tap opens [open] ("changes": Recent changes), or the app. */
        fun onNotice(title: String, text: String, open: String? = null) {}
        /** Something hard to undo waits for Yes or No: shown with both as buttons. */
        fun onTap(t: Tap) {}; fun onTapGone(id: String) {}
    }

    private val secrets = Secrets(context, slot)
    private val lists = Lists(context, slot)
    private val env = SystemEnv
    val ui = MutableStateFlow(Ui())
    @Volatile var listener: Listener? = null
    /** Called when this account is left, so the app can forget it (Signers). */
    @Volatile var onLeft: ((Signer) -> Unit)? = null

    /** Which account this is, for a notification or a list: its npub, shortened. */
    val label get() = ui.value.member?.group?.npub?.let { if (it.length > 20) it.take(12) + "…" + it.takeLast(6) else it } ?: "New key"
    val isMember get() = ui.value.member != null
    /** One device and one box: lose this phone and the account is gone. (The old paper backup's nudge.) */
    val needsPaper get() = !ui.value.keptKey && (ui.value.member?.group?.members?.let { ms -> ms.count { !it.blocked } <= 2 } ?: false)

    private val prefs = context.getSharedPreferences(named("keykin-account"), Context.MODE_PRIVATE)
    private val work = Executors.newCachedThreadPool()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var pinger: ScheduledFuture<*>? = null
    /** Every 30 minutes: your lists on your relays, for changes made somewhere else ([checkLists]). */
    private var lookout: ScheduledFuture<*>? = null
    /** One request to the box at a time: a piece that is being caught up after a cycle must not be used meanwhile. */
    private val boxLock = Any()

    // --- What this phone keeps (all of it sealed by Secrets) -------------------------------------

    @Volatile private var meNow: Me? = null
    /** This device: its member key, made on first use, its name, and its chip key if it has a fingerprint. */
    private fun me(): Me = meNow ?: synchronized(this) {
        meNow ?: run {
            val o = secrets.load("device")?.let(::JSONObject)
                ?: JSONObject().put("sk", env.random(32).toHex()).put("name", (android.os.Build.MODEL ?: "").ifBlank { "Phone" }.take(40)).also { secrets.save("device", it.toString()) }
            val chip = try { Chip.publicKey(context, slot) } catch (_: Exception) { null }
            Me(o.getString("sk").hexToBytes(), o.getString("name"), chip).also { meNow = it }
        }
    }
    @Volatile private var held: Held? = null
    /**
     * Tier 1: the whole key on this phone, sealed by Secrets, and nothing else needed. It signs for Marble, for
     * apps and for sites by itself. Kept after a move to a box too (tier 2), as the backup, until you forget it.
     */
    private fun localKey(): ByteArray? = secrets.load("nsec")?.let { try { it.hexToBytes() } catch (_: Exception) { null } }
    @Volatile private var localPub: String? = null
    /** The account's public key (hex): the box account's, or the one of the key on this phone. */
    private fun accountHex(): String? = held?.npub ?: localPub
    /** Signing with the key on this phone, not with the box. */
    private val tier1 get() = held == null && localPub != null
    private fun keep(h: Held) { held = h; h.changed = { secrets.save("held", it.json().toString()) }; secrets.save("held", h.json().toString()) }
    private fun helpers(): List<String> = secrets.load("helpers")?.let { JSONArray(it) }?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    /** The box, then devices that listen when it is off (Keykin SPEC §8g): those only sign and make message keys. */
    private fun send(h: Held): Send = Device.withHelpers(Device.http(h.addr), helpers().map { Device.http(it) })
    private fun fp(title: String): Fingerprint = { bytes -> Chip.sign(context, slot, title, bytes) }

    private fun membersFrom(a: JSONArray) = List(a.length()) { a.getJSONObject(it).let { m -> Member(m.getInt("i"), m.getString("name"), m.getString("kind"), m.getString("key"), m.optBoolean("blocked"), m.optBoolean("chip"), m.optLong("added")) } }
    /** Tier 1: just this phone, no box. */
    private fun localMembership(pub: String): Membership {
        val mine = me()
        return Membership(1, sites.pubkey, Group(pub, Nip19.npub(pub), 1, sites.sites().flatMap { it.relays }.distinct(), listOf(Member(1, mine.name, "device", mine.key, chip = mine.chip != null))), 0, "", "", mine.chip != null)
    }
    private fun membership(h: Held, members: List<Member>): Membership {
        val mine = me()
        val list = members.ifEmpty { listOf(Member(h.piece.i, mine.name, "device", mine.key, chip = mine.chip != null), Member(0, "Your box", "box", h.boxKey)) }
        return Membership(h.piece.i, sites.pubkey, Group(h.npub, Nip19.npub(h.npub), 2, sites.sites().flatMap { it.relays }.distinct(), list), h.epoch, h.addr, h.boxKey, mine.chip != null)
    }

    private fun why(e: Throwable): String = when ((e as? KeykinException)?.code) {
        "blocked" -> "This phone is blocked from your account. Unblock it from another of your devices, or from your box's page."
        "no_box" -> "Couldn't reach your box. Is this phone on the same network as it?"
        "needs_fingerprint", "no_chip_key" -> "This phone has no fingerprint set up, so it can't change your account. Add one in the phone's settings, then add this phone again."
        "cancelled" -> "Cancelled. Nothing changed."
        "fingerprint_changed" -> "The fingerprints on this phone changed, so it can no longer approve changes. Block this phone from another device and add it again."
        "pending_welcome" -> "A device you added hasn't finished joining. Finish that first, then try again."
        "bad_qr", "bad_invite", "bad_join", "bad_keygen" -> "That code is damaged or old. Ask for a new one."
        "wrong_box" -> "That code is from a different box than your account's."
        "wrong_account" -> "That code is for a different account."
        "no_account" -> "$appName isn't set up yet."
        "denied" -> "You said no."
        "site_silent" -> "The site didn't answer. Ask it for a new code."
        "digits_differ" -> "The digits were different, so nothing was sent."
        "site_declined" -> "The site cancelled."
        "no_relay" -> "Couldn't reach the site's relay."
        null -> e.message ?: "That didn't work."
        else -> "That didn't work (${(e as KeykinException).code})."
    }
    /** Marble talks https only (its network rules). A box at a plain http address can't be reached, so say that and not "no box". */
    private fun plainHttp(addr: String): String? {
        if (!addr.startsWith("http://")) return null
        val host = try { java.net.URI(addr).host } catch (_: Exception) { null } ?: return null
        return if (android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) null
            else "Your box's address ($addr) is plain http, and $appName only connects over https. Give the box an https address, then use the code its page shows there."
    }
    private fun blockedHere(e: Throwable) { if ((e as? KeykinException)?.code == "blocked") ui.update { it.copy(removedBy = "another of your devices, or your box's owner") } }

    // --- Starting and stopping ---------------------------------------------------------------------

    /** Start answering sites and keep the device list fresh, if this phone is in an account. Called by the service. */
    fun start() {
        if (held == null && localPub == null) return
        work.execute { try { sites.start() } catch (_: Exception) { } }
        if (pinger == null) pinger = timer.scheduleWithFixedDelay({ refresh() }, 0, 30, TimeUnit.SECONDS)
        if (lookout == null) lookout = timer.scheduleWithFixedDelay({ work.execute { checkLists() } }, 1, 30, TimeUnit.MINUTES)
        if (screenOff == null) {
            val r = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context?, i: android.content.Intent?) { sharedXs.forgetAll() } }
            context.registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
            screenOff = r
        }
    }
    fun stop() { pinger?.cancel(false); pinger = null; lookout?.cancel(false); lookout = null; sites.stop(); sharedXs.forgetAll() }

    /** Ask the box who is in the account. Also how this phone learns it was blocked. */
    fun refresh() {
        val h = held ?: return
        try {
            val (list, _) = Device.membersAndNext(me(), h, send(h))
            secrets.save("helpers", JSONArray(list.filter { it.addr != null && it.key != me().key }.map { it.addr }).toString())
            val members = list.map { Member(it.i, it.name, it.kind, it.key, it.blocked, it.chip, it.added) }
            secrets.save("members", JSONArray(list.map { JSONObject().put("i", it.i).put("name", it.name).put("kind", it.kind).put("key", it.key).put("blocked", it.blocked).put("chip", it.chip).put("added", it.added) }).toString())
            ui.update { it.copy(member = membership(h, members), relaysUp = 1, removedBy = null, needsCycle = members.any { m -> m.blocked },
                online = members.associate { m -> m.idx to (m.isBox || m.idx == h.piece.i) }) }
            reportGrants(h)
            try { takeNotes(h) } catch (_: Exception) { }
        } catch (e: Exception) {
            blockedHere(e)
            ui.update { it.copy(relaysUp = 0, online = it.member?.let { m -> mapOf(m.idx to true) } ?: emptyMap()) }
        }
        listener?.onStatus()
    }

    // --- What is signed in, as the box knows it (Keykin SPEC 11.5) ---------------------------------
    //
    // Each site and app this phone let in has a grant id. The box is told the list, so the account
    // page can show it, and every signature for a site or an app names its grant. Revoking at the
    // box (from the account page, or from here) makes the box refuse that grant from any device.

    private fun grantOf(g: AppGrant) = g.pk.take(32)
    private fun reportGrants(h: Held) {
        val mine = (try { sites.sites() } catch (_: Exception) { emptyList() }).map { Device.Grant(it.grant, "site", it.origin ?: "A site", it.added) } +
            apps.all.keys.mapNotNull { appGrant(it) }.filter { it.pkg != context.packageName }.map { Device.Grant(grantOf(it), "app", it.label, appTimes.getLong(it.pkg + ":added", 0)) }
        val revoked = try { Device.reportGrants(me(), h, mine, send(h)) } catch (_: Exception) { return }   // an older box: it keeps no list
        if (revoked.isNotEmpty()) dropRevoked(revoked.toSet())
    }
    /** The box says these were revoked: they go from this phone's lists too. */
    private fun dropRevoked(ids: Set<String>) {
        rememberRevoked(ids)
        for (s in sites.sites()) if (s.grant in ids) { sites.signOut(s.key); note("${s.origin ?: "A site"} was signed out from your account page", "denied") }
        for (g in apps.all.keys.mapNotNull { appGrant(it) }) if (grantOf(g) in ids) { apps.edit().remove(g.pkg).apply(); note("${g.label} was signed out from your account page", "denied") }
        sharedXs.forgetAll(); showSites()
    }
    private fun grantsChanged() { val h = held ?: return; work.execute { reportGrants(h) } }

    // --- Things the account page started, for this phone to finish (Keykin SPEC 11.6) --------------

    // A signed-in account page has the person's authority (Aaron, 2026-11-17), so this phone just does
    // what it asks: add a device, make a bunker link, become a site's signer. Only what is hard to undo
    // (removing blocked devices for good) asks Yes or No here first, and No signs that browser out.

    private val notesSeen = ConcurrentHashMap.newKeySet<String>()
    private fun takeNotes(h: Held) {
        for (n in Device.notes(me(), h, send(h))) {
            if (!notesSeen.add(n.id)) continue
            try {
                when (n.kind) {
                    "invite" -> {
                        val inv = Texts.readInvite(n.text)
                        synchronized(boxLock) { Device.add(me(), h, inv, null, send(h), n.id) }
                        Device.noteDone(me(), h, n.id, Texts.join(h, prefs.getString("login-origin", null)?.ifEmpty { null }), send(h))
                        note("Added ${inv.name} to your account, from your account page", "approved")
                    }
                    "bunker" -> { Device.noteDone(me(), h, n.id, sites.bunker(signerRelays()), send(h)); note("Made a bunker link for your account page", "approved") }
                    "nostrconnect" -> {
                        val uri = Uri.parse(n.text.trim().replaceFirst(Regex("^nostrconnect:(?!//)"), "nostrconnect://"))
                        val client = uri.host?.lowercase(); val secret = uri.getQueryParameter("secret")
                        val relays = uri.getQueryParameters("relay").filter { it.startsWith("wss://") || (debuggable && it.startsWith("ws://")) }
                        if (!isHex32(client) || relays.isEmpty() || secret.isNullOrBlank()) throw KeykinException("bad_qr")
                        val origin = uri.getQueryParameter("url")?.let { Uri.parse(it).host } ?: uri.getQueryParameter("name")?.take(40)?.trim()?.ifBlank { null } ?: "A site"
                        sites.acceptNostrConnect(client!!, relays, secret, origin)
                        pointerCheck(h)
                        Device.noteDone(me(), h, n.id, "ok", send(h))
                        note("Connected $origin, from your account page", "approved")
                    }
                    "cycle" -> if (n.tap) {
                        ui.value.tap?.let { old -> listener?.onTapGone(old.id) }
                        ui.update { it.copy(tap = Tap(n.id, "Remove blocked devices for good?", "Your account page asked. Their pieces stop working, and this can't be undone. No also signs that browser out of your account page.")) }
                        ui.value.tap?.let { listener?.onTap(it) }; Cosign.host.joined(context)
                    }
                }
            } catch (e: Exception) { note("Couldn't do what your account page asked (${n.kind}): ${why(e)}", "denied") }
        }
    }
    /** Yes or No to something hard to undo that the account page asked for. */
    /** [id] names the request a notification's button was for; an older one's buttons answer nothing. */
    fun tap(yes: Boolean, id: String? = null) {
        val t = ui.value.tap ?: return; val h = held ?: return
        if (id != null && id != t.id) return
        ui.update { it.copy(tap = null) }
        listener?.onTapGone(t.id)
        if (yes) change("Removing blocked devices…") { me, _ ->
            Device.cycle(me, h, null, send(h), t.id)
            Device.noteDone(me, h, t.id, "ok", send(h))
            note("Removed blocked devices for good, from your account page", "approved")
        } else work.execute {
            try { Device.noteRefuse(me(), h, t.id, send(h)) } catch (_: Exception) { }
            note("Said No to your account page: removing blocked devices", "denied")
        }
    }

    // --- Username and password for the account page, and where that page is -----------------------

    /** Set the account page's username and password (text is "username\npassword"). Fingerprint. The box never sees the password. */
    fun setPassword(text: String) {
        val username = text.substringBefore('\n').trim().lowercase(); val password = text.substringAfter('\n', "")
        if (!Regex("^[a-z0-9._-]{3,32}$").matches(username)) return ui.update { it.copy(error = "A username is 3 to 32 of a-z, 0-9, dot, dash and underscore.") }
        if (password.length < 8) return ui.update { it.copy(error = "Use a password of at least 8 characters.") }
        change("Setting your password…") { me, h ->
            Device.passwordSet(me, h, username, password, fp("Set your account page's password"), send(h))
            prefs.edit().putString("username", username).apply()
            ui.update { it.copy(username = username) }
            note("Set a username and password for your account page", "approved")
        }
    }
    fun clearPassword() = change("Removing your password…") { me, h ->
        Device.passwordClear(me, h, fp("Remove your account page's password"), send(h))
        prefs.edit().remove("username").apply()
        ui.update { it.copy(username = null) }
        note("Removed the password from your account page", "approved")
    }

    /**
     * Say where this account's login page is (Keykin SPEC 11.1): a small event signed by the account,
     * which marble.syb.lol reads for its account button. An empty [url] takes it away again.
     */
    fun setLoginPage(url: String) {
        val pub = accountHex() ?: return
        val origin = url.trim().trimEnd('/')
        if (origin.isNotEmpty() && !Regex("^https://[a-z0-9.-]+(:[0-9]+)?$").matches(origin)) return ui.update { it.copy(error = "A login page's address is https:// and a name, with nothing after it.") }
        ui.update { it.copy(working = "Signing…", error = null) }
        work.execute {
            try {
                val tags = listOf(listOf("d", "keykin/login")) + if (origin.isEmpty()) emptyList() else listOf(listOf("url", origin))
                val signed = signNow(Event(pub, Clock.now(), 30078, tags, "").withId())
                publish(signed)
                prefs.edit().putString("login-page", origin).apply()
                ui.update { it.copy(loginPage = origin.ifEmpty { null }) }
                note(if (origin.isEmpty()) "Removed your login page's address" else "Your login page is $origin", "approved")
            } catch (e: Exception) { ui.update { it.copy(error = e.message ?: "That didn't work.") } }
            finally { ui.update { it.copy(working = null) } }
        }
    }

    /**
     * Keep the account's login-page pointer on its relays (Keykin SPEC 11.1). Right after joining a box,
     * and whenever this phone signs a site in by a nostrconnect code: a site looking someone up by name
     * (Marble Web, marking its code kk_login=missing) found no pointer, so this is when it matters. The newest pointer, if it names this box's login page, is sent again as it
     * is to any write relay that lacks it (no new signature). A new one is signed only when no relay has
     * any. A pointer removed on purpose (no url) or naming another page is the person's choice: left alone.
     */
    private fun pointerCheck(h: Held) {
        if (!pointerBusy.compareAndSet(false, true)) return
        work.execute {
            try { pointerStep(h) }
            catch (e: Exception) { if (debuggable) android.util.Log.i("Keykin", "pointer failed: $e") }
            finally { pointerBusy.set(false) }
        }
    }
    private val pointerBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Where this box's login page is: from the box's code, else the box address if Keykin Login answers there. Remembered. */
    private fun loginOrigin(h: Held): String? {
        prefs.getString("login-origin", null)?.let { return it.ifEmpty { null } }
        val o = LoginPointer.origin(h.addr, allowHttp = debuggable)?.takeIf(::isKeykinLogin)
        prefs.edit().putString("login-origin", o ?: "").apply()
        return o
    }
    /** One check. True when it settled (nothing more to do until the next one), false when no relay answered. */
    private fun pointerStep(h: Held): Boolean {
        val pub = accountHex() ?: return false
        val origin = loginOrigin(h)
        // A debug build told to use test relays stays on them; otherwise ask the account's own and Marble's.
        val testing = debuggable && context.getSharedPreferences("keykin", 0).getString("signer-relays", null) != null
        val writes = writeRelays()
        val look = if (testing) writes else (writes + DEFAULT_RELAYS).distinct()
        val filter = "{\"kinds\":[10002,30078],\"authors\":[\"$pub\"]}"
        var byRelay = queryEach(look, filter)
        // The account's own write list, if a relay had it and this phone hadn't seen it.
        val outbox = byRelay.values.flatten().filter { it.kind == 10002 && it.pubkey == pub && it.verify() }.maxByOrNull { it.createdAt }
            ?.tags?.filter { it.size > 1 && it[0] == "r" && (it.size < 3 || it[2] == "write") }?.map { it[1] }.orEmpty().take(6)
        val targets = if (testing) writes else (writes + outbox).distinct()
        val more = targets - look.toSet()
        if (more.isNotEmpty()) byRelay = byRelay + queryEach(more, filter)
        val plan = LoginPointer.plan(origin, pub, byRelay, targets)
        if (debuggable) android.util.Log.i("Keykin", "pointer for ${pub.take(8)}: $plan (origin $origin, ${byRelay.size} of ${(look + more).size} relays answered)")
        when (plan) {
            is LoginPointer.Plan.Wait -> return false
            is LoginPointer.Plan.Leave -> return true
            is LoginPointer.Plan.Resend -> { publish(plan.event, plan.to); return true }
            is LoginPointer.Plan.Sign -> {
                val signed = signNow(Event(pub, Clock.now(), LoginPointer.KIND, listOf(listOf("d", LoginPointer.D), listOf("url", plan.origin)), "").withId())
                publish(signed, if (testing) writes else (targets + DEFAULT_RELAYS).distinct())
                prefs.edit().putString("login-page", plan.origin).apply()
                ui.update { it.copy(loginPage = plan.origin) }
                note("Your account's sign-in page is now ${plan.origin}", "approved")
                return true
            }
        }
    }
    /** Keykin Login answers a call to its page API without the page's header with 403 forbidden; a bare box has no /api. */
    private fun isKeykinLogin(origin: String): Boolean = try {
        val c = java.net.URL("$origin/api").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 5000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json"); c.outputStream.use { it.write("{}".toByteArray()) }
        val code = c.responseCode; val body = (c.errorStream ?: c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty(); c.disconnect()
        code == 403 && body.contains("forbidden")
    } catch (_: Exception) { false }

    // --- Scanning: the box's code, a device's invite, the join text, a site's code -----------------

    private var pendingQr: Qr? = null
    private var pendingJoin: JoinText? = null
    private var pendingInvite: Invite? = null
    private var pendingLogin: String? = null

    /** A paper backup's code was pasted. Keykin v2 has no paper backup yet (PLAN.md, open decision). */
    fun pastedBackup(text: String) = scan(text, true)
    /** Whatever the camera read. */
    fun scanned(text: String) = scan(text, false)
    /** The same, for a code that was copied and pasted: a site's code is then checked by comparing digits. */
    fun pasted(text: String) = scan(text, true)

    private fun scan(text: String, pasted: Boolean) {
        val t = text.trim()
        val err = { s: String -> ui.update { it.copy(error = s) } }
        if (t.startsWith("{")) {
            val o = try { JSONObject(t) } catch (_: Exception) { return err("That isn't a code $appName understands.") }
            if (o.optInt("keykin") != 2) return err("That isn't a code $appName understands.")
            try {
                when {
                    o.has("login") -> {
                        if (!isMember) return err("Set up your account on this phone first.")
                        val addr = o.getJSONObject("login").optString("addr")
                        val host = try { java.net.URI(addr).host ?: addr } catch (_: Exception) { addr }
                        pendingLogin = t; connecting = null; nostrConnect = null
                        val who = prefs.getString("username", null) ?: ui.value.member?.group?.npub?.let { it.take(12) + "…" + it.takeLast(6) } ?: ""
                        ui.update { it.copy(error = null, loginAsk = LoginAsk(host, who, "12 hours")) }
                    }
                    o.has("invite") -> {
                        if (!isMember) return err("That's a new device's code. Scan it with a device that's already in your account.")
                        val inv = Texts.readInvite(t)
                        if (ui.value.member?.group?.members?.any { it.pk == inv.key } == true) return err("That device is already in your account.")
                        pendingInvite = inv
                        ui.update { it.copy(error = null, adding = AddingDevice(inv.key, inv.name.take(40))) }
                    }
                    o.has("join") -> {
                        if (isMember) return err("This phone is already in an account. Use Add another account first.")
                        val j = Texts.readJoin(t)
                        plainHttp(j.addr)?.let { return err(it) }
                        pendingJoin = j; pendingQr = null
                        ui.update { it.copy(error = null, joinCode = null, offer = Offer(j.addr, false, Nip19.npub(j.npub))) }
                    }
                    else -> {
                        if (isMember && !tier1) return err("This phone is already in an account. That code makes a new one: use Add another account first.")
                        val qr = Texts.qr(t)
                        plainHttp(qr.addr)?.let { return err(it) }
                        pendingQr = qr; pendingJoin = null
                        ui.update { it.copy(error = null, joinCode = null, offer = Offer(pendingQr!!.addr, true, bringing = bringing != null || tier1)) }
                    }
                }
            } catch (e: Exception) { err(why(e)) }
            return
        }
        if (t.startsWith("nostrconnect:")) return scannedNostrConnect(t)
        // The whole key, by QRST: another device's code to send it to, or one that sends it here.
        try { PairingUri.parse(t) } catch (_: Exception) { null }?.takeIf { it.profile == cc.aaro.qrst.core.NsecProfile.id }?.let { p ->
            return if (cc.aaro.qrst.core.scannerRole(p.mode) == cc.aaro.qrst.core.Role.SENDER) { if (isMember) scannedKeyCode(p, pasted) else err("That device is asking for a key. Set up your account here first.") }
            else if (isMember) err("That device is offering a key, and this phone already has an account. Use Add another account first.")
            else startMove(Transfer.join(moveDeps(), p, !pasted), "receive", !pasted)
        }
        if (t.startsWith("cosign:")) return err("That code is from the older Cosign servers. This version of $appName works with a Keykin box.")
        PairingUri.allowLoopback = debuggable
        val params = try { PairingUri.parse(t) } catch (_: Exception) { null }
        // A sign-in code that carries a nostrconnect:// fallback (`nc`): use the ticket if it's one for us, the fallback if not.
        val rest = if ('#' in t) t.substringAfter('#') else t.substringAfter('?', "")
        val nc = rest.split('&').firstOrNull { it.startsWith("nc=") }?.let { Uri.decode(it.removePrefix("nc=")) }
        if (nc != null && nc.startsWith("nostrconnect:") && (params == null || params.profile != SignerTicketProfile.id || params.mode != Mode.OFFER))
            return scannedNostrConnect(nc, SiteChoice.marked(t))
        when {
            params == null -> err("That isn't a code $appName understands.")
            !isMember -> err("Set up your account on this phone first.")
            params.profile != SignerTicketProfile.id -> err("That code is for something else (${params.profile}).")
            params.mode != Mode.OFFER -> err("That code isn't asking to sign in.")
            usedCode(params.token) -> err("You've already used that code. Ask the site for a new one.")
            else -> {
                connecting = params; connectPasted = pasted; nostrConnect = null; pendingLogin = null
                choice = siteChoice(params.origin, SiteChoice.marked(t))
                ui.update { it.copy(error = null, connect = confirmCard(params.origin ?: "This site", choice)) }
            }
        }
    }

    // --- Making an account with a box, or joining one ----------------------------------------------

    // --- Bringing in an account you already have (Keykin SPEC 2) ----------------------------------
    //
    // This phone splits the key with the box and signs with the pieces from then on. It KEEPS the whole
    // key, sealed like everything else here, as the account's backup (Aaron, 2026-11-17): it is how the
    // account comes back if the pieces are lost. Only you forget it, with "Forget the whole key on this
    // phone". Nothing else does, except removing this phone from the account, which says so first.

    private var bringing: ByteArray? = null
    /** The nsec typed in, as characters, wiped once read. Next comes the box's code. */
    // --- Moving the whole key between your devices by QRST (tier 1) ---------------------------------
    //
    // As QRST Signer does it: the receiving device shows a code, the sending device scans it, the person
    // there types the receiver's digits and says they understand the other device gets permanent control,
    // then gives a fingerprint. The key goes once, sealed, and the receiver keeps it.

    private var keyMove: Transfer<ByteArray>? = null
    private var sendTo: PairingParams? = null
    private fun moveDeps() = TransferDeps(cc.aaro.qrst.core.NsecProfile, qrstStore, env, "https://qrst.aaro.cc/", CodeCheck.TYPE)
    /** "I have an account on another device": show a code for that device to send the key to. */
    fun receiveKey() {
        if (isMember) return
        startMove(Transfer.show(moveDeps(), cc.aaro.qrst.core.Role.RECEIVER), "receive", true)
    }
    private fun startMove(t: Transfer<ByteArray>, kind: String, viaCamera: Boolean) {
        keyMove?.dispose(); keyMove = t
        t.onChange = { showMove(t, kind, viaCamera) }
        showMove(t, kind, viaCamera)
    }
    private fun showMove(t: Transfer<ByteArray>, kind: String, viaCamera: Boolean) {
        if (keyMove !== t) return
        val v = t.view(); val s = v.session
        var kept = ui.value.move?.kept == true
        // What arrived is on the view once the session has committed it; it is taken out (and wiped) off this callback.
        if (kind == "receive" && s.phase == Phase.ENDED && !kept) v.value?.let { k ->
            val copy = k.copyOf()
            try { becomeLocal(copy, "Received your key from another device"); kept = true } finally { copy.fill(0) }
            work.execute { t.takeValue()?.fill(0) }
        }
        ui.update { it.copy(move = KeyMove(kind, v.uri, s.phase.name, s.check.name, s.code, s.compare, s.rendering, s.outcome?.wire, v.connected, viaCamera, kept)) }
    }
    /** Receiving: keep what arrived. */
    fun keepKey() { keyMove?.accept() }
    fun declineKey() { keyMove?.decline() }
    /** A device's receive code was read here: ask first, nothing moves yet. */
    private fun scannedKeyCode(params: PairingParams, pasted: Boolean) {
        val k = localKey() ?: return ui.update { it.copy(error = "There's no key on this phone to send.") }
        k.fill(0)
        sendTo = params
        ui.update { it.copy(error = null, move = KeyMove("consent", viaCamera = !pasted)) }
    }
    /** The person said they understand, and gave the fingerprint: reach the other device. */
    fun sendKey() {
        val params = sendTo ?: return
        val camera = ui.value.move?.viaCamera ?: true
        work.execute {
            try {
                if (me().chip != null) Chip.sign(context, slot, "Send your key to that device", env.random(32))
                startMove(Transfer.join(moveDeps(), params, camera), "send", camera)
            } catch (e: Exception) { ui.update { it.copy(move = it.move?.copy(problem = why(e))) } }
        }
    }
    /** Sending: the digits the other device shows (typed), or Yes to the compared ones. The key leaves only here. */
    fun moveDigits(digits: String?) {
        val t = keyMove ?: return
        val k = localKey() ?: return
        when (t.view().session.check) {
            CodeCheck.TYPE -> t.enterCode(digits ?: "", k)
            CodeCheck.COMPARE -> if (digits == null) t.rejectMatch() else t.confirmMatch(k)
            else -> t.release(k)
        }
        note("Sent your key to another device", "approved")
    }
    fun closeMove() { keyMove?.let { try { if (it.view().session.phase != Phase.ENDED) it.cancel() } catch (_: Exception) { }; it.dispose() }; keyMove = null; sendTo = null; ui.update { it.copy(move = null) } }

    /** Tier 1: a new account, its key made here and kept here. No box, no server. */
    fun createLocal() {
        if (isMember) return
        val k = cc.aaro.qrst.core.generateSecretKey(env)
        try { becomeLocal(k, "Made your account on this phone") } finally { k.fill(0) }
    }
    private fun becomeLocal(k: ByteArray, line: String) {
        secrets.save("nsec", k.toHex())
        val pub = cc.aaro.qrst.core.publicKeyHex(k)
        localPub = pub
        ui.update { it.copy(member = localMembership(pub), keptKey = true, bringing = false, error = null, joining = null, offer = null) }
        note(line, "approved")
        start(); Cosign.host.joined(context)
    }

    fun importKey(chars: CharArray) {
        val t = chars.filter { !it.isWhitespace() }.toCharArray(); chars.fill(' ')
        val key = try {
            if (t.size == 64 && t.all { it in '0'..'9' || it in 'a'..'f' }) String(t).hexToBytes() else cc.aaro.qrst.core.Bech32.decodeNsec(t)
        } catch (_: Exception) { null } finally { t.fill(' ') }
        if (key == null || key.size != 32) return ui.update { it.copy(bringing = false, error = "That isn't an nsec (or a 64-character hex key). Nothing was kept.") }
        if (isMember) { key.fill(0); return ui.update { it.copy(error = "This phone is already in an account. Use Add another account first.") } }
        // Tier 1: it signs from here at once. A box comes later, only if you sign in to one.
        try { becomeLocal(key, "Brought your key to this phone") } finally { key.fill(0) }
    }
    fun cancelImport() { bringing?.fill(0); bringing = null; ui.update { it.copy(bringing = false) } }
    /** The person's own choice: the whole key goes from this phone. The pieces keep signing. */
    fun forgetKey() {
        if (held == null) return ui.update { it.copy(error = "On this phone your account is that key. To remove it, use Remove this phone from your account.") }
        secrets.save("nsec", null); localPub = null
        ui.update { it.copy(keptKey = false) }
        note("Forgot the whole key on this phone", "denied")
    }

    // --- Asking a box for an account (Keykin SPEC 11.4) -------------------------------------------
    //
    // For someone with no setup code: ask, and the box's admin approves. The answer carries the box's
    // member key, so it is only taken over https: whoever answers that address is trusted as the box.

    private var askPoll: java.util.concurrent.ScheduledFuture<*>? = null
    fun askAccount(addr: String, note: String) {
        val box = addr.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
        if (!box.startsWith("https://") && !(debuggable && box.startsWith("http://"))) return ui.update { it.copy(asking = Asking(box, note, 0, "A box's address starts with https://.")) }
        ui.update { it.copy(asking = Asking(box, note, 1), error = null) }
        work.execute {
            try {
                val id = Device.askBox(me(), "make", note.take(200), Device.http(box))
                prefs.edit().putString("ask", JSONObject().put("box", box).put("note", note).put("request", id).toString()).apply()
                ui.update { it.copy(asking = Asking(box, note, 2)) }
                watchAsk()
            } catch (e: Exception) { ui.update { it.copy(asking = Asking(box, note, 0, if ((e as? KeykinException)?.code == "no_box") "Couldn't reach a box at $box." else why(e))) } }
        }
    }
    private fun watchAsk() {
        askPoll?.cancel(false)
        askPoll = timer.scheduleWithFixedDelay({
            val o = prefs.getString("ask", null)?.let(::JSONObject) ?: run { askPoll?.cancel(false); return@scheduleWithFixedDelay }
            val box = o.getString("box"); val note = o.optString("note"); val id = o.getString("request")
            try {
                val (state, key) = Device.askedWithKey(me(), id, Device.http(box))
                when (state) {
                    "approved" -> {
                        askPoll?.cancel(false); prefs.edit().remove("ask").apply()
                        ui.update { it.copy(asking = Asking(box, note, 3)) }
                        pendingQr = Qr(box, key, id); pendingJoin = null
                        ui.update { it.copy(offer = Offer(box, true, bringing = bringing != null)) }
                        accept()
                        ui.update { it.copy(asking = null) }
                    }
                    "refused", "gone" -> {
                        askPoll?.cancel(false); prefs.edit().remove("ask").apply()
                        ui.update { it.copy(asking = Asking(box, note, 0, if (state == "refused") "The box's owner said no." else "That request is gone. Ask again.")) }
                    }
                    else -> ui.update { it.copy(asking = it.asking ?: Asking(box, note, 2)) }
                }
            } catch (_: Exception) { }     // the box out of reach for a moment: ask again next time
        }, 0, 5, TimeUnit.SECONDS)
    }
    fun cancelAsk() {
        askPoll?.cancel(false)
        val o = prefs.getString("ask", null)?.let(::JSONObject)
        prefs.edit().remove("ask").apply()
        ui.update { it.copy(asking = null) }
        if (o != null) work.execute { try { Device.askCancel(me(), o.getString("request"), Device.http(o.getString("box"))) } catch (_: Exception) { } }
    }
    fun startAsk() = ui.update { it.copy(asking = Asking("https://account.syb.lol", "", 0)) }
    fun closeAsk() { if (prefs.getString("ask", null) == null) ui.update { it.copy(asking = null) } }

    /** "Create your account": it is made with a box, so the next thing is the box's code. */
    fun createAccount() = ui.update { it.copy(error = "Open your Keykin box's page and scan the code it shows (or copy it and tap Paste a code). Your account is made by this phone and the box together.") }

    /** "Sign in with another device": show this phone's invite for a device in the account to scan. */
    fun startJoin() {
        if (isMember) return
        ui.update { it.copy(error = null, joinCode = Texts.invite(me())) }
    }
    fun cancelJoin() = ui.update { it.copy(joinCode = null) }

    /** Yes to making the account, or to joining it. */
    fun accept() {
        val o = ui.value.offer ?: return
        val qr = pendingQr; val j = pendingJoin
        val key = if (o.create) (bringing ?: if (o.bringing) localKey() else null) else null
        ui.update { it.copy(offer = null, joining = if (key != null) "Bringing your account to your box…" else if (o.create) "Making your account with your box…" else "Collecting this phone's piece from your box…") }
        work.execute {
            try {
                val h = when {
                    key != null -> Device.importKey(me(), qr ?: error("no code"), key.toHex())
                    o.create -> Device.create(me(), qr ?: error("no code"))
                    else -> Device.join(me(), j ?: error("no code"))
                }
                keep(h)
                // The box's code (or the device's join text) may say where its login page is (SPEC 11.1).
                (qr?.login ?: j?.login)?.let { LoginPointer.origin(it, allowHttp = debuggable) }?.let { prefs.edit().putString("login-origin", it).apply() }
                // Kept first, forgotten never (unless you say so): the whole key, sealed, as this account's backup.
                if (key != null) { secrets.save("nsec", key.toHex()); key.fill(0); bringing = null }
                ui.update { it.copy(joining = null, error = null, bringing = false, keptKey = key != null, member = membership(h, emptyList())) }
                note(if (key != null) "Brought your account to your box. The whole key is kept on this phone as its backup" else if (o.create) "Made your account with your box" else "Joined your account on this phone", "approved")
                refresh(); start()
                Cosign.host.joined(context)
                pointerCheck(h)
            } catch (e: Exception) {
                ui.update { it.copy(joining = null, error = when ((e as? KeykinException)?.code) {
                    "no_box" -> "Couldn't reach the box at ${o.box}. Is this phone on the same network as it?"
                    "already_here" -> "That box already holds this account. Sign in with another device instead."
                    "bad_key" -> "That key can't be used. Nothing was kept."
                    else -> why(e)
                }) }
            } finally { pendingQr = null; pendingJoin = null }
        }
    }
    fun decline() { pendingQr = null; pendingJoin = null; ui.update { it.copy(offer = null, joining = null) } }

    fun clearError() = ui.update { it.copy(error = null) }
    fun showError(text: String) = ui.update { it.copy(error = text) }

    /** Remove this phone from the account: the box is told to block it, then everything here is wiped. */
    fun leave() {
        val h = held; val mine = meNow
        work.execute {
            if (h != null && mine != null) try { Device.block(mine, h, mine.key, send(h)) } catch (_: Exception) { }
            stop(); secrets.clear(); lists.clear(); apps.edit().clear().apply(); appTimes.edit().clear().apply(); prefs.edit().clear().apply(); history.delete(); kept.delete()
            Chip.forget(context, slot); held = null; meNow = null
            ui.value = Ui()
            onLeft?.invoke(this)
        }
    }

    // --- Account changes: each needs the fingerprint, and the box checks it -------------------------

    /** Runs an account change off the main thread, saying what is going on and what went wrong. */
    private fun change(what: String, f: (Me, Held) -> Unit) {
        val h = held ?: return
        ui.update { it.copy(working = what, error = null) }
        work.execute {
            try { synchronized(boxLock) { f(me(), h) } }
            catch (e: Exception) { blockedHere(e); ui.update { it.copy(error = why(e)) } }
            finally { ui.update { it.copy(working = null) }; refresh() }
        }
    }

    /** Yes to adding the device whose invite was scanned. Then it needs the join text, shown next. */
    fun confirmAddDevice() {
        val inv = pendingInvite ?: return
        ui.update { it.copy(adding = null) }
        change("Adding ${inv.name}…") { me, h ->
            Device.add(me, h, inv, fp("Add ${inv.name} to your account"), send(h))
            pendingInvite = null
            ui.update { it.copy(joinText = Texts.join(h, prefs.getString("login-origin", null)?.ifEmpty { null })) }
            note("Added ${inv.name} to your account", "approved")
        }
    }
    fun cancelAddDevice() { pendingInvite = null; ui.update { it.copy(adding = null) } }
    fun closeJoinText() = ui.update { it.copy(joinText = null) }
    fun lockMissing() = ui.update { it.copy(adding = null, error = "Set up a fingerprint on this phone first: changing your account needs it.") }

    fun askReportLost(d: Member) = ui.update { it.copy(reporting = d) }
    fun cancelReportLost() = ui.update { it.copy(reporting = null) }
    /** Block a device (one press, no fingerprint), then cycle the keys so its piece fits nothing (fingerprint). */
    fun confirmReportLost() {
        val d = ui.value.reporting ?: return
        val pk = d.pk ?: return
        ui.update { it.copy(reporting = null) }
        change("Blocking ${d.name}…") { me, h ->
            Device.block(me, h, pk, send(h))
            sharedXs.forgetAll()
            note("Blocked ${d.name}", "denied")
            ui.update { it.copy(needsCycle = true, working = "Cycling your keys…") }
            Device.cycle(me, h, fp("Cycle your keys"), send(h))
            note("Cycled your keys: ${d.name}'s piece no longer fits", "approved")
        }
    }
    /** Cycle the keys by itself: after a block whose cycle was cancelled, or one made from another device. */
    fun cycle() = change("Cycling your keys…") { me, h ->
        Device.cycle(me, h, fp("Cycle your keys"), send(h))
        note("Cycled your keys", "approved")
    }
    fun unblock(pk: String) {
        val d = ui.value.member?.group?.members?.firstOrNull { it.pk == pk } ?: return
        change("Unblocking ${d.name}…") { me, h ->
            Device.unblock(me, h, pk, fp("Unblock ${d.name}"), send(h))
            note("Unblocked ${d.name}", "approved")
        }
    }

    /** Form the whole key on this phone and show it (the old "take my account out"). The only place an nsec exists. */
    fun takeOutViaServers() {
        if (held == null) {
            val k0 = localKey() ?: return
            k0.fill(0)
            ui.update { it.copy(takeOut = TakeOut(TakeOut.Stage.WAITING, "Waiting for your fingerprint…"), error = null) }
            return work.execute {
                try {
                    // The key is already here; the prompt is the plain kind: nothing a box could check.
                    if (me().chip != null) Chip.sign(context, slot, "Show your secret key", env.random(32))
                    val k = localKey() ?: return@execute
                    try { ui.update { it.copy(takeOut = TakeOut(TakeOut.Stage.SHOWING, nsec = Nip19.nsec(k), npub = Nip19.npub(cc.aaro.qrst.core.publicKeyHex(k)))) } } finally { k.fill(0) }
                } catch (e: Exception) { ui.update { it.copy(takeOut = if ((e as? KeykinException)?.code == "cancelled") null else TakeOut(TakeOut.Stage.FAILED, why(e))) } }
            }
        }
        val h = held ?: return
        ui.update { it.copy(takeOut = TakeOut(TakeOut.Stage.WAITING, "Waiting for your fingerprint…"), error = null) }
        work.execute {
            try {
                val hexKey = synchronized(boxLock) { Device.formNsec(me(), h, fp("Show your secret key"), send(h)) }
                note("Formed your whole key on this phone", "approved")
                ui.update { it.copy(takeOut = TakeOut(TakeOut.Stage.SHOWING, nsec = Nip19.nsec(hexKey.hexToBytes()), npub = Nip19.npub(h.npub))) }
            } catch (e: Exception) {
                blockedHere(e)
                ui.update { it.copy(takeOut = if ((e as? KeykinException)?.code == "cancelled") null else TakeOut(TakeOut.Stage.FAILED, why(e))) }
            }
        }
    }
    fun closeTakeOut() = ui.update { it.copy(takeOut = null) }

    // --- Signing in to sites (NIP-46): a scanned code, nostrconnect://, bunker:// ------------------
    //
    // This phone is the remote signer. A site that is let in gets a key of its own on the list here,
    // and is answered with no prompt, except for anything destructive, which waits for you. Every
    // signature still needs the box, so blocking this phone stops every site it serves.

    private val debuggable get() = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val sites: SiteSigner by lazy {
        val st = secrets.load("signer")?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: SiteSigner.fresh().also { secrets.save("signer", it.toString()) }
        SiteSigner({ accountHex() }, ::siteAct, st, { secrets.save("signer", it.toString()); showSites(); grantsChanged() }, { if (debuggable) android.util.Log.i("Keykin", it) }, ::childKnown)
    }
    private val qrstStore = Store(object : KeyValue {
        private val prefs = context.getSharedPreferences("qrst", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    })
    private var transfer: Transfer<SignerTicket>? = null
    private var connecting: PairingParams? = null
    private var connectPasted = false
    private var digitsAnswer: CompletableFuture<Boolean>? = null
    /** A nostrconnect:// code waiting for a yes: the site's key, its relays, its secret. */
    private var nostrConnect: Triple<String, List<String>, String>? = null
    /** The sign-in is finishing while you're back in the app that asked. */
    @Volatile var connectInBackground = false

    /** Where sites reach this phone when they name no relay of their own (a bunker link). */
    private fun signerRelays(): List<String> = context.getSharedPreferences("keykin", 0).getString("signer-relays", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_RELAYS
    /** A debug build's tests name a relay of their own (one on this machine), for bunker links and for what this phone publishes. */
    fun testRelays(urls: String) { if (debuggable) context.getSharedPreferences("keykin", 0).edit().putString("signer-relays", urls).apply() }
    /** Debug builds: the login-page pointer step again, as after joining a box. */
    fun pointerNow() { if (debuggable) held?.let { pointerCheck(it) } }

    private fun showSites() {
        val fromSites = try { sites.sites().map { Site(it.key, it.origin ?: "A site", it.added, it.used, null, it.via, own = it.place != null) } } catch (_: Exception) { emptyList() }
        val fromApps = apps.all.keys.mapNotNull { appGrant(it) }.filter { it.pkg != context.packageName }.map { Site(it.pk, it.label, appTimes.getLong(it.pkg + ":added", 0), appTimes.getLong(it.pkg + ":used", 0), null, "App on this phone") }
        ui.update { u -> u.copy(sites = fromSites + fromApps, member = u.member?.let { m -> m.copy(group = m.group.copy(relays = try { sites.sites().flatMap { it.relays }.distinct() } catch (_: Exception) { emptyList() })) }) }
    }

    /**
     * Yes to a site's sign-in. [pick] answers the choice for a site new to this account: "new" signs it in
     * with an account of its own, anything else (or nothing: the web pane, older callers) with the main account.
     */
    fun confirmConnect(pick: String? = null) {
        if (pendingLogin != null) return confirmLogin()
        val place = placeFor(choice, pick); choice = SiteChoice.Decision.Main
        if (nostrConnect != null) return confirmNostrConnect(place)
        val params = connecting ?: return
        val origin = params.origin ?: "a site"
        useCode(params.token)
        ui.update { it.copy(connect = Connect(origin, Connect.Stage.WORKING, "Sending to $origin…")) }
        val camera = !connectPasted
        work.execute {
            val deps = TransferDeps(SignerTicketProfile, qrstStore, env, "https://qrst.aaro.cc/", if (camera) CodeCheck.NONE else CodeCheck.COMPARE)
            val t = Transfer.join(deps, params, camera)
            transfer = t
            fun until(ms: Long, pred: (TransferView<SignerTicket>) -> Boolean): TransferView<SignerTicket> {
                val end = System.currentTimeMillis() + ms
                while (true) { val v = t.view(); if (pred(v)) return v; if (transfer !== t) throw KeykinException("cancelled"); if (System.currentTimeMillis() > end) throw KeykinException("site_silent"); Thread.sleep(80) }
            }
            try {
                val v = until(30_000) { it.session.phase == Phase.RELEASE || it.stage == TransferView.Stage.ENDED }
                if (v.stage == TransferView.Stage.ENDED) return@execute finishConnect(origin, v.session.outcome)
                // The ticket is made only now, at the moment it is released.
                // A site's own account is made (or found) first: with a box this asks it, once per place.
                place?.let { childFor(it) }
                fun ticket() = sites.mint(params.origin, params.relays.ifEmpty { signerRelays() }, place = place).let { j ->
                    SignerTicket(j.getString("pubkey"), j.getString("signer"), params.relays.ifEmpty { signerRelays() }, j.getString("t"), j.getLong("expires"))
                }
                when (v.session.check) {
                    CodeCheck.NONE -> t.release(ticket())
                    CodeCheck.COMPARE -> {
                        val f = CompletableFuture<Boolean>(); digitsAnswer = f
                        ui.update { it.copy(connect = Connect(origin, Connect.Stage.COMPARE, digits = v.session.compare ?: throw KeykinException("site_silent"))) }
                        val same = try { f.get(120, TimeUnit.SECONDS) } catch (_: Exception) { false }
                        if (!same) { t.rejectMatch(); throw KeykinException("digits_differ") }
                        ui.update { it.copy(connect = Connect(origin, Connect.Stage.WORKING, "Sending to $origin…")) }
                        t.confirmMatch(ticket())
                    }
                    else -> throw KeykinException("site_wants_typing")
                }
                finishConnect(origin, until(60_000) { it.stage == TransferView.Stage.ENDED }.session.outcome, place != null)
            } catch (e: Exception) {
                if ((e as? KeykinException)?.code != "cancelled") failConnect(origin, if ((e as? KeykinException)?.code == "site_silent") "$origin didn't answer. Ask it for a new code." else why(e))
            } finally { if (transfer === t) transfer = null; t.dispose() }
        }
    }
    // --- Which account a site signs in with (SiteChoice): a new one of its own by default, or the main one ---

    /** What the sign-in being asked about gets, worked out when its code was read. */
    @Volatile private var choice: SiteChoice.Decision = SiteChoice.Decision.Main
    private fun siteChoice(origin: String?, accountPage: Boolean) = SiteChoice.decide(origin, try { sites.sites() } catch (_: Exception) { emptyList() }, accountPage)
    private fun confirmCard(origin: String, d: SiteChoice.Decision) = Connect(origin, Connect.Stage.CONFIRM, ask = d is SiteChoice.Decision.Ask, own = d is SiteChoice.Decision.Own, accountPage = d == SiteChoice.Decision.AccountPage)
    private fun placeFor(d: SiteChoice.Decision, pick: String?): String? = when (d) {
        is SiteChoice.Decision.Ask -> if (pick == "new") d.place else null
        is SiteChoice.Decision.Own -> d.place
        SiteChoice.Decision.Main, SiteChoice.Decision.AccountPage -> null
    }

    /** The five digits were compared with the site's: the same, or not. */
    fun digits(same: Boolean) { digitsAnswer?.complete(same) }

    private fun failConnect(origin: String, message: String) {
        if (connectInBackground) listener?.onNotice("Not signed in", message)
        connectInBackground = false; connecting = null
        ui.update { it.copy(connect = Connect(origin, Connect.Stage.FAILED, message)) }
    }
    private fun finishConnect(origin: String, outcome: Outcome?, own: Boolean = false) {
        when (outcome) {
            Outcome.DELIVERED, Outcome.SENT_UNCONFIRMED -> {
                note(if (own) "Connected $origin with a new account" else "Connected $origin", "approved")
                connectInBackground = false; connecting = null
                ui.update { it.copy(connect = Connect(origin, Connect.Stage.DONE, if (own) "$origin has its own account now. Remove it under Your account any time." else "$origin can sign as you now. Remove it under Your account any time.")) }
                // A success needs no tap to dismiss, and mustn't sit over an approval that arrives next.
                timer.schedule({ ui.update { u -> if (u.connect?.stage == Connect.Stage.DONE) u.copy(connect = null) else u } }, 2500, TimeUnit.MILLISECONDS)
                Cosign.host.joined(context)
            }
            Outcome.SECOND_RESPONDER -> failConnect(origin, "Two devices answered that code, so nothing was sent. Ask the site for a new code.")
            Outcome.PEER_ABORTED, Outcome.DECLINED -> failConnect(origin, "$origin cancelled.")
            Outcome.EXPIRED -> failConnect(origin, "That code expired. Ask the site for a new one.")
            else -> failConnect(origin, "Couldn't connect $origin (${outcome?.wire ?: "unknown"}).")
        }
    }

    /** Sign-in codes already used here (their token, or a nostrconnect site's key): a code is good once. */
    private val usedCodes = context.getSharedPreferences("used-codes", Context.MODE_PRIVATE)
    private fun usedCode(id: String) = usedCodes.contains(id)
    private fun useCode(id: String) {
        val all = usedCodes.all
        val e = usedCodes.edit().putLong(id, System.currentTimeMillis())
        if (all.size > 200) all.entries.sortedBy { it.value as? Long ?: 0 }.take(all.size - 200).forEach { e.remove(it.key) }
        e.apply()
    }

    fun closeConnect() {
        choice = SiteChoice.Decision.Main
        digitsAnswer?.complete(false)
        transfer?.let { transfer = null; try { it.cancel() } catch (_: Exception) { } }
        connecting = null; nostrConnect = null; pendingLogin = null
        ui.update { it.copy(connect = null, loginAsk = null) }
    }

    /** [accountPage]: the code that carried this link was marked as a Keykin account page's. */
    private fun scannedNostrConnect(text: String, accountPage: Boolean = false) {
        val uri = Uri.parse(text.replaceFirst(Regex("^nostrconnect:(?!//)"), "nostrconnect://"))
        val client = uri.host?.lowercase()
        val relayList = uri.getQueryParameters("relay").filter { it.startsWith("wss://") || (debuggable && it.startsWith("ws://")) }
        val secret = uri.getQueryParameter("secret")
        when {
            !isMember -> ui.update { it.copy(error = "Set up your account on this phone first.") }
            !isHex32(client) || relayList.isEmpty() || secret.isNullOrBlank() -> ui.update { it.copy(error = "That nostrconnect code is incomplete. Ask the site for a new one.") }
            usedCode(client!!) -> ui.update { it.copy(error = "You've already used that code. Ask the site for a new one.") }
            else -> {
                // What the site says it is: its web address if it gave one, else its name.
                val site = uri.getQueryParameter("url")?.let { Uri.parse(it).let { u -> u.host?.let { h -> if (u.port > 0) "$h:${u.port}" else h } } }
                    ?: uri.getQueryParameter("name")?.take(40)?.trim()?.ifBlank { null } ?: "This app"
                nostrConnect = Triple(client, relayList, secret); connecting = null; pendingLogin = null
                choice = siteChoice(uri.getQueryParameter("url"), accountPage || SiteChoice.marked(text))
                ui.update { it.copy(error = null, connect = confirmCard(site, choice)) }
            }
        }
    }
    private fun confirmNostrConnect(place: String? = null) {
        val (client, relays, secret) = nostrConnect ?: return
        val origin = ui.value.connect?.origin ?: "a site"
        useCode(client)
        ui.update { it.copy(connect = Connect(origin, Connect.Stage.WORKING, "Telling $origin…")) }
        work.execute {
            try { place?.let { childFor(it) }; sites.acceptNostrConnect(client, relays, secret, origin, place); nostrConnect = null; finishConnect(origin, Outcome.DELIVERED, place != null); held?.let { pointerCheck(it) } }
            catch (e: Exception) { nostrConnect = null; failConnect(origin, why(e)) }
        }
    }

    /** Log a browser in to the box's page, from the code that page shows. Fingerprint. */
    private fun confirmLogin() {
        val text = pendingLogin ?: return
        val h = held ?: return
        ui.update { it.copy(loginAsk = null, connect = Connect("your box's page", Connect.Stage.WORKING, "Waiting for your fingerprint…")) }
        work.execute {
            try {
                synchronized(boxLock) { Device.approveLogin(me(), h, text, fp("Log in to your box's page"), send(h)) }
                pendingLogin = null
                note("Logged a browser in to your box's page", "approved")
                ui.update { it.copy(connect = Connect("your box's page", Connect.Stage.DONE, "That browser is logged in.")) }
            } catch (e: Exception) { pendingLogin = null; blockedHere(e); ui.update { it.copy(connect = Connect("your box's page", Connect.Stage.FAILED, why(e))) } }
        }
    }

    /** A bunker:// link to paste into a site. Shown by the signer process (the app only learns that there is one). */
    fun makeBunker() {
        if (!isMember) return
        work.execute {
            try {
                val link = sites.bunker(signerRelays())
                if (debuggable) android.util.Log.i("Keykin", "bunker link $link")     // for tests on an emulator; never in a release build
                ui.update { it.copy(bunker = link, error = null) }
            } catch (e: Exception) { ui.update { it.copy(error = why(e)) } }
        }
    }
    fun closeBunker() = ui.update { it.copy(bunker = null) }

    fun removeSite(site: Site) {
        val app = appByPk(site.pk)
        val grant = app?.let(::grantOf) ?: sites.sites().firstOrNull { it.key == site.pk }?.grant
        if (app != null) apps.edit().remove(app.pkg).apply() else sites.signOut(site.pk)
        // Revoked at the box too, so it sticks whichever device let it in. An older box has no such list.
        held?.let { h -> if (grant != null) { rememberRevoked(listOf(grant)); work.execute { try { Device.revoke(me(), h, grant, send(h)) } catch (_: Exception) { }; grantsChanged() } } }
        // Access has ended: what it could read with goes now, not when the screen does.
        sharedXs.forgetAll()
        showSites()
        note("Removed ${site.origin}", "denied")
    }

    /** One request from a site that is signed in. Anything destructive waits for you here first. */
    private fun siteAct(site: SiteSigner.Site, method: String, params: List<String>): String {
        val by = site.origin ?: "A site"
        // A site with an account of its own signs and reads as that account only.
        val own = site.place?.let { childFor(it) }
        return when (method) {
            "sign_event" -> signChecked(labelled(appEvent(params[0], own?.npub), site.grant, own), by, approved = false, mayAsk = true, grant = site.grant, asChild = own).toJson()
            "nip44_encrypt" -> nip44With(params[0], params[1], true, site.grant, own)
            "nip44_decrypt" -> nip44With(params[0], params[1], false, site.grant, own)
            "nip04_encrypt" -> nip04With(params[0], params[1], true, site.grant, own)
            "nip04_decrypt" -> nip04With(params[0], params[1], false, site.grant, own)
            else -> throw KeykinException("unsupported")
        }
    }

    /**
     * A vault device cert a site asks this phone to sign (kind 4811, type "device") names the site's grant as its
     * `keykin` label (vaults §3.4, as of adb7f42): a page can't know its grant id, and the label lets your other devices
     * find the page's root when the grant is revoked. Only on a box account, and only as the account itself.
     */
    private fun labelled(e: Event, grant: String, asChild: Child?): Event {
        if (e.kind != 4811 || held == null || asChild != null) return e
        val o = try { JSONObject(e.content) } catch (_: Exception) { return e }
        if (o.optString("type") != "device") return e
        o.put("keykin", "grant:$grant")
        return Event(e.pubkey, e.createdAt, e.kind, e.tags, cc.aaro.keykin.core.jcs(o)).withId()
    }

    // --- Signing -----------------------------------------------------------------------------------

    /** What would be destroyed, if this needs you to approve it; else null. */
    fun holdFor(ev: Event): String? = Policy.hold(ev, lists.latest(Policy.keyOf(ev)))

    /** The event an app or a site sent, as it will be signed: under your key, with its id. */
    fun appEvent(json: String, asKey: String? = null): Event {
        val pub = asKey ?: accountHex() ?: error("$appName isn't set up yet")
        val raw = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not an event")
        val tags = (raw["tags"] as? List<*>)?.map { t -> (t as List<*>).map { it.toString() } } ?: emptyList()
        return Event(pub, raw["created_at"] as? Long ?: env.now(), (raw["kind"] as Long).toInt(), tags, raw["content"] as? String ?: "").withId()
    }

    /** The signature itself: this phone's piece and the box's (or a helper's when the box is off). */
    private fun signNow(ev: Event, grant: String? = null, tapped: Boolean = false, asChild: Child? = null): Event {
        if (held == null) {
            val k = localKey() ?: error("$appName isn't set up yet")
            if (asChild != null) {
                // Tier 1 as a child: the key moved by the tweak, for this one signature, then wiped. Never kept or shown.
                val s = try { Children.localSecret(k, Children.tweakOf(asChild.t)) } finally { k.fill(0) }
                try {
                    if (cc.aaro.qrst.core.publicKeyHex(s) != asChild.npub) throw KeykinException("wrong_account")
                    return Event.sign(asChild.npub, ev.createdAt, ev.kind, ev.tags, ev.content, s, env)
                } finally { s.fill(0) }
            }
            try { return Event.sign(cc.aaro.qrst.core.publicKeyHex(k), ev.createdAt, ev.kind, ev.tags, ev.content, k, env) } finally { k.fill(0) }
        }
        val h = held ?: error("$appName isn't set up yet")
        // The box wants to hear that you said Yes to the few things it calls hard to undo. What this phone itself
        // signs to put something back (an undo, a repost) or at your own word is always that.
        val yes = tapped && Device.destructive(ev.kind, ev.tags, ev.content) != null
        val j = try { synchronized(boxLock) { Device.sign(me(), h, ev.kind, ev.content, ev.tags, ev.createdAt, send(h), grant, yes, asChild) } }
            catch (e: KeykinException) {
                if (e.code == "needs_tap") throw NeedsApproval(boxHold(ev))
                if (e.code == "revoked" && grant != null) { dropRevoked(setOf(grant)); throw Revoked() }
                blockedHere(e); if (e.code == "no_box") ui.update { it.copy(relaysUp = 0) }; throw IllegalStateException(why(e))
            }
        return Event.fromMap(Json.parse(j.toString())) ?: error("bad event from the box")
    }

    /**
     * Sign for [by] (Marble itself, an app, a site), judging the event first: some things are never
     * signed, and anything destructive needs your yes. With [mayAsk] the question is put on screen and
     * waited for; without it the caller hears [NeedsApproval] and asks you itself.
     */
    private fun signChecked(ev: Event, by: String, approved: Boolean, mayAsk: Boolean, grant: String? = null, asChild: Child? = null): Event {
        Policy.refuse(ev)?.let { throw IllegalStateException(it) }
        var held = approved
        // This phone's own rules (against the last version of a list it saw), and the box's shorter list. A site's own
        // account has no lists kept here: its change is judged on its own, never against the main account's lists.
        if (!approved) ((if (asChild == null) holdFor(ev) else Policy.hold(ev, null)) ?: Device.destructive(ev.kind, ev.tags, ev.content)?.let { boxHold(ev) })?.let { hold ->
            if (!mayAsk) throw NeedsApproval(hold)
            if (!askAndWait(by, ev, hold)) { note("Said no to $by: $hold", "denied"); throw KeykinException("denied") }
            held = true
        }
        val prev = if (asChild == null) lists.latest(Policy.keyOf(ev)) else null
        val signed = signNow(ev, grant, held, asChild)
        // What changed and what was deleted are kept for the main account only (their Undo signs as the main account).
        if (asChild == null) remember(signed, prev, by, held)
        // Relay logins happen once per relay an app connects to: not worth a line each.
        if (ev.kind != 22242 || held) note("Signed ${Describe.kind(ev.kind)} for $by", if (held) "approved" else "auto")
        return signed
    }

    /** What the box calls hard to undo, said the way this phone's own holds are. */
    private fun boxHold(ev: Event) = when (Device.destructive(ev.kind, ev.tags, ev.content)) {
        "asks every relay to erase the whole account" -> "Ask relays to erase everything you have ever published"
        "deletes five or more posts at once" -> "Delete ${ev.tags.count { it.isNotEmpty() && (it[0] == "e" || it[0] == "a") }} of your events"
        "empties the follow list" -> "Empty your follow list"
        "empties the profile" -> "Wipe your profile: everything"
        else -> "Something that is hard to undo"
    }
    private val answers = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private fun askAndWait(by: String, ev: Event, hold: String): Boolean {
        val ask = Ask(env.random(8).toHex(), by, ev, hold, System.currentTimeMillis() + ASK_LIFE)
        val f = CompletableFuture<Boolean>(); answers[ask.id] = f
        ui.update { it.copy(asks = it.asks + ask) }
        listener?.onAsk(ask)
        Cosign.host.joined(context)
        return try { f.get(ASK_LIFE, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
        finally { answers.remove(ask.id); ui.update { it.copy(asks = it.asks.filter { a -> a.id != ask.id }) }; listener?.onAskGone(ask.id) }
    }
    /** Your answer to something that waited for you. */
    fun decide(id: String, approve: Boolean) { answers[id]?.complete(approve) }

    /** The latest version of one of your lists this phone has seen signed ("3": your follows). */
    fun latest(key: String): Event? = lists.latest(key)

    /** Sign something you did in Marble itself. Blocks for a moment; never call on the main thread. */
    fun signOwn(ev: Event, approved: Boolean = false): Event = signChecked(ev, appName, approved, mayAsk = false)

    // --- Message keys (NIP-44, NIP-04) ---------------------------------------------------------------

    /**
     * The shared secrets this phone has worked out with other people. Each one takes the box, so
     * they are kept while this phone is awake and this process lives. Never written down. They go
     * when the screen goes off, when an app's or a site's access ends, and when a device is blocked,
     * and the bytes are wiped, not merely dropped.
     */
    internal class Kept {
        private val by = ConcurrentHashMap<String, ByteArray>()
        val size get() = by.size
        operator fun get(peer: String): ByteArray? = by[peer]
        operator fun set(peer: String, x: ByteArray) { by.put(peer, x)?.let { java.util.Arrays.fill(it, 0) } }
        /** Forget them all, for real. Returns how many there were. */
        fun forgetAll(): Int {
            val n = by.size
            for (x in by.values) java.util.Arrays.fill(x, 0)
            by.clear()
            return n
        }
    }
    private val sharedXs = Kept()
    private var screenOff: android.content.BroadcastReceiver? = null

    /** The x of the shared point with [peer], from this phone's part and the box's. The key is never whole. */
    private fun sharedX(peer: String, grant: String?, asChild: Child? = null, keep: Boolean = true): ByteArray {
        // Kept per grant: a revoked site or app must not ride on a secret made for another. And per child.
        val slot = (grant ?: "") + ":" + (asChild?.npub?.let { "$it:" } ?: "") + peer
        if (keep) sharedXs[slot]?.let { return it.copyOf() }
        val sharedXs = if (keep) sharedXs else Kept()
        if (held == null) {
            val k = localKey() ?: error("$appName isn't set up yet")
            if (asChild == null) try { return cc.aaro.keykin.core.Secp.ecdhX(k, peer).also { sharedXs[slot] = it.copyOf() } } finally { k.fill(0) }
            // Tier 1 as a child: the key moved by the tweak for this one moment, then wiped.
            val s = try { Children.localSecret(k, Children.tweakOf(asChild.t)) } finally { k.fill(0) }
            try { return cc.aaro.keykin.core.Secp.ecdhX(s, peer).also { sharedXs[slot] = it.copyOf() } } finally { s.fill(0) }
        }
        val h = held ?: error("$appName isn't set up yet")
        val x = try { synchronized(boxLock) { Device.sharedX(me(), h, peer, send(h), grant, asChild) } }
            catch (e: KeykinException) {
                if (e.code == "revoked" && grant != null) { dropRevoked(setOf(grant)); throw Revoked() }
                blockedHere(e); throw IllegalStateException(why(e))
            }
        sharedXs[slot] = x.copyOf()
        return x
    }
    private fun nip44With(peer: String, text: String, encrypt: Boolean, grant: String? = null, asChild: Child? = null): String {
        val key = cc.aaro.keykin.core.Nip44.conversationKey(sharedX(peer, grant, asChild))
        return if (encrypt) cc.aaro.keykin.core.Nip44.encrypt(text, key) else cc.aaro.keykin.core.Nip44.decrypt(text, key)
    }
    private fun nip04With(peer: String, text: String, encrypt: Boolean, grant: String? = null, asChild: Child? = null): String {
        val key = SecretKeySpec(sharedX(peer, grant, asChild), "AES")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val b64 = Base64.getEncoder()
        if (encrypt) {
            val iv = env.random(16)
            c.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            return b64.encodeToString(c.doFinal(text.toByteArray())) + "?iv=" + b64.encodeToString(iv)
        }
        val (ct, iv) = text.split("?iv=").let { it[0] to it.getOrElse(1) { "" } }
        c.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(Base64.getDecoder().decode(iv)))
        return String(c.doFinal(Base64.getDecoder().decode(ct)))
    }

    /**
     * Marble's own data, encrypted to this account itself (NIP-44 to self): what it keeps in app data
     * (NIP-78). The other side is always this account's own key, so this reads nobody else's messages.
     */
    fun nip44Self(text: String, encrypt: Boolean): String = nip44With(accountHex() ?: error("$appName isn't set up yet"), text, encrypt)

    // --- Vaults (~/nostr-sync-nip/vaults.md §3.6, §12.2) --------------------------------------------
    //
    // A vault's keyring entry is sealed to this account with a one-time key: opening it is one `ecdh` with the box,
    // between the account and that key. The ECDH output is used once and wiped, never kept (§3.2), and only a vault
    // keyring payload comes back, so this can't be used to read anyone's messages.

    /** The keyring payload sealed in `{eph, ct}`, or null if it doesn't open or isn't one. Blocks. */
    fun openVaultSeal(eph: String, ct: String): String? {
        if (!isHex32(eph)) return null
        val x = sharedX(eph, null, keep = false)
        val key = try { cc.aaro.keykin.core.Nip44.conversationKey(x) } finally { x.fill(0) }
        val text = try { cc.aaro.keykin.core.Nip44.decrypt(ct, key) } catch (_: Exception) { return null } finally { key.fill(0) }
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val hex64 = Regex("^[0-9a-f]{64}$"); val hex32 = Regex("^[0-9a-f]{32}$")
        return text.takeIf { hex64.matches(o.optString("vault")) && hex64.matches(o.optString("genesis")) && hex32.matches(o.optString("d")) && o.has("role") }
    }

    /**
     * Keykin's view for vaults, as JSON: this device's member key, the account's key epoch, the members (key → blocked)
     * or null when the box can't be asked now, and every grant id the box has reported revoked to this phone. Null for
     * an account with no box (the key on this phone). Blocks.
     */
    fun vaultKeykin(): String? {
        val h = held ?: return null
        val o = JSONObject().put("me", me().key).put("epoch", h.epoch).put("revokedGrants", JSONArray(revokedGrants().sorted()))
        try {
            val (list, epoch) = synchronized(boxLock) { Device.membersWithEpoch(me(), h, send(h)) }
            o.put("epoch", epoch).put("members", JSONObject().apply { list.filter { it.kind != "box" }.forEach { put(it.key, it.blocked) } })
        } catch (e: Exception) { blockedHere(e) }
        return o.toString()
    }
    private fun revokedGrants(): Set<String> = secrets.load("revokedGrants")?.let { JSONArray(it) }?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()
    private fun rememberRevoked(ids: Collection<String>) = synchronized(childLock) { secrets.save("revokedGrants", JSONArray((revokedGrants() + ids).sorted()).toString()) }

    // --- Child keys, one per place (Keykin SPEC 8k) -------------------------------------------------
    //
    // Engine only; no screen uses it yet. A place (a site, an app: any name) can get a child of this
    // account: another public key, which nothing published ties to this one. The same place always gives
    // the same child, from the key on this phone (tier 1) or with the box (tier 2), and the child is
    // remembered for this account (sealed: its name, tweak and public key) so it is made only once.
    // The child signs and makes message keys through this account, moved by its tweak for that one
    // moment. Its secret is never kept, shown or offered for export: with the tweak it gives this account's.

    private val childLock = Any()
    private fun children(): JSONObject = secrets.load("children")?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: JSONObject()

    /** The child for [place]: the one this account remembers, or made now and remembered. Blocks (the box may take part). */
    fun childFor(place: String): Child {
        if (accountHex() == null) error("$appName isn't set up yet")
        synchronized(childLock) {
            val all = children()
            all.optJSONObject(place)?.let { o -> return Child(place, o.getString("t"), o.getString("npub")) }
            val c = makeChild(place)
            secrets.save("children", all.put(place, JSONObject().put("t", c.t).put("npub", c.npub)).toString())
            return c
        }
    }
    /** The public key (x-only hex) of [place]'s child, if this account has made one. Asks nothing. */
    fun childKnown(place: String): String? = children().optJSONObject(place)?.optString("npub")?.ifEmpty { null }
    /** Forget the child remembered for [place]. Nothing is lost: the same place makes the same child again. */
    fun forgetChild(place: String) = synchronized(childLock) { secrets.save("children", children().apply { remove(place) }.toString()) }
    /** The child for a place: from the whole key here (tier 1), or with the box (tier 2). The same either way. */
    private fun makeChild(place: String): Child {
        val h = held
        if (h == null) {
            val k = localKey() ?: error("$appName isn't set up yet")
            try { return Children.local(k, place) } finally { k.fill(0) }
        }
        return try { synchronized(boxLock) { Device.makeChild(me(), h, place, send(h)) } }
            catch (e: KeykinException) { blockedHere(e); throw IllegalStateException(why(e)) }
    }

    /**
     * Sign [ev] as [place]'s child (its pubkey is replaced by the child's). Some things are never signed;
     * anything hard to undo throws [NeedsApproval] unless [approved]. No list of the child's is kept here, so
     * a change is judged on its own. Blocks; never call on the main thread.
     */
    fun signAs(place: String, ev: Event, approved: Boolean = false, grant: String? = null): Event {
        val c = childFor(place)
        val e = Event(c.npub, ev.createdAt, ev.kind, ev.tags, ev.content).withId()
        Policy.refuse(e)?.let { throw IllegalStateException(it) }
        if (!approved) (Policy.hold(e, null) ?: Device.destructive(e.kind, e.tags, e.content)?.let { boxHold(e) })?.let { throw NeedsApproval(it) }
        return signNow(e, grant, approved, c)
    }
    /** NIP-44 as [place]'s child, with [peer]. */
    fun nip44As(place: String, peer: String, text: String, encrypt: Boolean, grant: String? = null): String = nip44With(peer, text, encrypt, grant, childFor(place))
    /** NIP-04 as [place]'s child, with [peer]. */
    fun nip04As(place: String, peer: String, text: String, encrypt: Boolean, grant: String? = null): String = nip04With(peer, text, encrypt, grant, childFor(place))
    /** NIP-44 to [place]'s child itself: that place's own data, readable by nothing else. */
    fun nip44SelfAs(place: String, text: String, encrypt: Boolean): String { val c = childFor(place); return nip44With(c.npub, text, encrypt, null, c) }

    /**
     * Debug builds only, for tests on an emulator: make (or find) [place]'s child, sign a note as it and
     * encrypt to itself, and log both (never the tweak) under MarbleState, for a computer to check.
     * "-" before the place forgets the remembered child first.
     */
    fun testChild(arg: String) {
        if (!debuggable) return
        val forget = arg.startsWith("-"); val place = arg.removePrefix("-")
        work.execute {
            try {
                if (forget) forgetChild(place)
                val known = childKnown(place)
                val c = childFor(place)
                val ev = signAs(place, Event(c.npub, Clock.now(), 1, emptyList(), "as $place").withId())
                val enc = nip44SelfAs(place, "hello $place", true)
                android.util.Log.i("MarbleState", "child place=$place remembered=${known != null} npub=${c.npub} account=${accountHex()} tier=${if (held == null) 1 else 2} signed=${ev.toJson()} verifies=${ev.verify()} nip44=$enc back=${nip44SelfAs(place, enc, false)}")
            } catch (e: Exception) { android.util.Log.i("MarbleState", "child failed: $e") }
        }
    }

    // --- Apps on this phone (Android signer, NIP-55) -------------------------------------------------

    private val apps = context.getSharedPreferences(named("apps"), Context.MODE_PRIVATE)
    /** When each app was connected and last answered, in seconds: for the list only. */
    private val appTimes = context.getSharedPreferences(named("app-times"), Context.MODE_PRIVATE)
    private fun usedApp(g: AppGrant) { if (Clock.now() - appTimes.getLong(g.pkg + ":used", 0) > 60) appTimes.edit().putLong(g.pkg + ":used", Clock.now()).apply() }
    fun appGrant(pkg: String): AppGrant? = apps.getString(pkg, null)?.let { j ->
        (Json.parse(j) as? Map<*, *>)?.let { AppGrant(pkg, it["pk"] as String, it["label"] as String, it["dm"] == true) }
    }
    private fun appByPk(pk: String) = apps.all.keys.mapNotNull { appGrant(it) }.firstOrNull { it.pk == pk }
    private fun store(g: AppGrant) = apps.edit().putString(g.pkg, Json.stringify(mapOf("pk" to g.pk, "label" to g.label, "dm" to g.dm))).apply()

    fun connectApp(pkg: String, label: String): AppGrant {
        val had = appGrant(pkg)
        val g = had ?: AppGrant(pkg, env.random(32).toHex(), label, false).also(::store)
        if (had == null) grantsChanged()
        if (had == null) { appTimes.edit().putLong(pkg + ":added", Clock.now()).putLong(pkg + ":used", Clock.now()).apply(); note("Connected ${g.label}", "approved"); showSites() }
        return g
    }
    fun allowDms(g: AppGrant): AppGrant = g.copy(dm = true).also { store(it); note("${g.label} may read and send your messages", "approved") }

    /** Sign for an app, with the box. Blocks for a moment; never call on the main thread. */
    fun signForApp(g: AppGrant, ev: Event, approved: Boolean): Event {
        if (appGrant(g.pkg) == null) throw Revoked()
        usedApp(g)
        return signChecked(ev, g.label, approved, mayAsk = false, grant = grantOf(g))
    }
    fun nip44(g: AppGrant, peer: String, text: String, encrypt: Boolean): String { if (appGrant(g.pkg) == null) throw Revoked(); usedApp(g); return nip44With(peer, text, encrypt, grantOf(g)) }
    fun nip04(g: AppGrant, peer: String, text: String, encrypt: Boolean): String { if (appGrant(g.pkg) == null) throw Revoked(); usedApp(g); return nip04With(peer, text, encrypt, grantOf(g)) }

    // --- What changed, with Undo; what was deleted, with its copy ------------------------------------
    //
    // The old servers kept these. Now this phone keeps them, for what was signed through this phone:
    // the version of a list that a change replaced, and a copy of each thing it signed, so a deletion
    // can be put back. Changes to your lists made somewhere else (another app with your key, another
    // device with your box) are found on your relays and shown here too, one row for all of them
    // since this phone last saw that list ([seen]).

    private val history = File(context.filesDir, named("changes") + ".json")
    private val kept = File(context.filesDir, named("signed") + ".json")
    private fun readArray(f: File) = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    private fun objects(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }

    @Synchronized private fun remember(signed: Event, prev: Event?, by: String, held: Boolean) {
        val pub = accountHex() ?: return
        if (lists.record(signed, pub) && prev != null && prev.id != signed.id) {
            val all = objects(readArray(history))
            val row = JSONObject().put("id", signed.id).put("text", changeText(prev, signed)).put("by", by).put("at", System.currentTimeMillis()).put("held", held).put("undone", false)
                .put("key", Policy.keyOf(signed)).put("prev", prev.toJson())
            history.writeText(JSONArray((listOf(row) + all).take(40)).toString())
        }
        if (signed.kind !in Policy.ARCHIVED && signed.kind !in 20000 until 30000 && signed.kind != 22242 && signed.kind != 5) {
            val all = objects(readArray(kept))
            kept.writeText(JSONArray((listOf(JSONObject().put("by", by).put("event", signed.toJson())) + all).take(300)).toString())
        }
        if (signed.kind == 5) {
            val gone = signed.tags.filter { it.size > 1 && it[0] == "e" }.map { it[1] }.toSet()
            val all = objects(readArray(kept))
            if (all.any { Event.fromMap(Json.parse(it.getString("event")))?.id in gone }) {
                kept.writeText(JSONArray(all.map { o -> if (Event.fromMap(Json.parse(o.getString("event")))?.id in gone) o.put("deleted", System.currentTimeMillis()) else o }).toString())
            }
        }
        showHistory()
    }
    private fun changeText(prev: Event, now: Event): String {
        val name = Describe.kind(now.kind).replaceFirstChar { it.uppercase() }
        if (now.kind == 0) return "$name changed"
        val before = prev.tags.map { Json.stringify(it) }.toSet(); val after = now.tags.map { Json.stringify(it) }.toSet()
        val added = (after - before).size; val removed = (before - after).size
        return "$name: " + listOfNotNull("$added added".takeIf { added > 0 }, "$removed removed".takeIf { removed > 0 }).joinToString(", ").ifEmpty { "changed" }
    }
    private fun showHistory() {
        val changes = objects(readArray(history)).map { Change(it.getString("id"), it.getString("text"), it.getString("by"), it.getLong("at"), it.optBoolean("held"), it.optBoolean("undone"), it.optBoolean("outside"), it.optLong("since")) }
        val deleted = objects(readArray(kept)).filter { it.has("deleted") }.mapNotNull { o ->
            Event.fromMap(Json.parse(o.getString("event")))?.let { e -> Deleted(e.id, Describe.kind(e.kind), o.getLong("deleted"), e.content.take(140)) }
        }
        ui.update { it.copy(changes = changes, deleted = deleted) }
    }

    /**
     * A version of one of your lists found on a relay, by the app's reader or by [checkLists]. Only the account's own key
     * counts (a site's own account is never watched). The first version of a list this phone sees is kept to compare with;
     * a newer one than the last seen is a row in Recent changes covering everything since, and a notification when
     * something was lost (Policy.outside). What this phone signed itself comes back with the same id, and is passed over.
     */
    fun seen(e: Event) {
        val pub = accountHex() ?: return
        val key = Policy.keyOf(e)
        val row = synchronized(this) {
            val prev = lists.latest(key)
            when (Policy.found(e, pub, prev)) {
                Policy.Found.IGNORE -> return
                Policy.Found.BASELINE -> { lists.record(e, pub); return }
                Policy.Found.CHANGED -> {
                    if (!lists.record(e, pub)) return
                    val o = Policy.outside(prev!!, e) ?: return
                    // Its time is when the change was made, not when this phone found it.
                    val r = JSONObject().put("id", e.id).put("text", o.text).put("by", ELSEWHERE).put("at", e.createdAt * 1000).put("held", o.alarm != null).put("undone", false)
                        .put("key", key).put("prev", prev.toJson()).put("outside", true).put("since", prev.createdAt)
                    history.writeText(JSONArray((listOf(r) + objects(readArray(history))).sortedByDescending { it.getLong("at") }.take(40)).toString())
                    o
                }
            }
        }
        showHistory()
        note("Changed in another app or device: ${row.text}")
        row.alarm?.let { listener?.onNotice(row.title, "$it Tap to see or undo.", "changes") }
    }
    /** The same, from the app's process: one list, as JSON. Off this process's main thread (it checks a signature and writes). */
    fun seen(json: String) = work.execute { try { Event.fromMap(Json.parse(json))?.let(::seen) } catch (_: Exception) { } }

    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var checkedAt = 0L
    /**
     * Ask your relays (and two profile indexes) for the newest of each of your lists, and pass each through [seen]. Every
     * 30 minutes while the signer runs, and when the app opens. This process's own small client, as [publish] is: the
     * app's reader stays out of the signer. Blocks for a few seconds at most; nothing found offline is nothing said.
     */
    fun checkLists() {
        val pub = accountHex() ?: return
        if (System.currentTimeMillis() - checkedAt < 60_000 || !checking.compareAndSet(false, true)) return
        try {
            checkedAt = System.currentTimeMillis()
            val testing = debuggable && context.getSharedPreferences("keykin", 0).getString("signer-relays", null) != null
            val relays = (writeRelays().map { it.trimEnd('/') } + if (testing) emptyList() else INDEX_RELAYS).distinct()
            val filter = Json.stringify(mapOf("kinds" to Policy.ARCHIVED.sorted(), "authors" to listOf(pub), "limit" to 200))
            val found = query(relays, filter).filter { it.pubkey == pub && it.kind in Policy.ARCHIVED && it.verify() }
            for (newest in found.groupBy { Policy.keyOf(it) }.values.map { l -> l.maxBy { it.createdAt } }) seen(newest)
            if (debuggable) android.util.Log.i("MarbleState", "checkLists: ${found.size} versions from ${relays.size} relays")
        } catch (_: Exception) { } finally { checking.set(false) }
    }
    /** Debug builds and the app opening: check now (at most once a minute). */
    fun checkListsSoon() = work.execute { checkLists() }

    /** One REQ to each of [relays], until each says it has sent all (or a few seconds pass): every event that came. */
    /** [query], relay by relay: what each relay that answered (sent EOSE) had. A relay missing from the map didn't answer. */
    private fun queryEach(relays: List<String>, filter: String): Map<String, List<Event>> {
        val per = ConcurrentHashMap<String, List<Event>>()
        query(relays, filter, perRelay = per)
        return per.toMap()
    }
    private fun query(relays: List<String>, filter: String, seconds: Long = 6, perRelay: ConcurrentHashMap<String, List<Event>>? = null): List<Event> {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val got = ConcurrentHashMap<String, Event>()
        val done = java.util.concurrent.CountDownLatch(relays.size)
        for (url in relays) try {
            val over = java.util.concurrent.atomic.AtomicBoolean(false)
            val mine = java.util.Collections.synchronizedList(mutableListOf<Event>())
            fun finish(ws: okhttp3.WebSocket?) { if (over.compareAndSet(false, true)) { done.countDown(); ws?.close(1000, null) } }
            http.newWebSocket(okhttp3.Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) { ws.send("[\"REQ\",\"lists\",$filter]") }
                override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                    val m = try { Json.parse(text) as? List<*> } catch (_: Exception) { null } ?: return
                    when (m.getOrNull(0)) {
                        "EVENT" -> Event.fromMap(m.getOrNull(2))?.let { got.putIfAbsent(it.id, it); mine.add(it) }
                        "EOSE" -> { perRelay?.put(url, mine.toList()); finish(ws) }
                        "CLOSED" -> finish(ws)
                    }
                }
                override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { if (debuggable && !over.get()) android.util.Log.i("Keykin", "query $url failed: $t"); finish(null) }
                override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) = finish(null)
            })
        } catch (_: Exception) { done.countDown() }
        done.await(seconds, TimeUnit.SECONDS)
        http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        return got.values.toList()
    }

    /** Where this account's own events go: its write relays if this phone has seen its relay list, else a few well-known ones. */
    private fun writeRelays(): List<String> = (if (debuggable) context.getSharedPreferences("keykin", 0).getString("signer-relays", null)?.split(',')?.filter { it.isNotBlank() } else null) ?: lists.latest("10002")?.tags?.filter { it.size > 1 && it[0] == "r" && (it.size < 3 || it[2] == "write") }?.map { it[1] }?.take(6)?.ifEmpty { null } ?: DEFAULT_RELAYS
    private fun publish(e: Event, relays: List<String> = writeRelays()) {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
        val done = java.util.concurrent.CountDownLatch(1)
        for (url in relays) try {
            http.newWebSocket(okhttp3.Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) { ws.send("[\"EVENT\",${e.toJson()}]") }
                override fun onMessage(ws: okhttp3.WebSocket, text: String) { if (text.startsWith("[\"OK\"")) { done.countDown(); ws.close(1000, null) } }
            })
        } catch (_: Exception) { }
        done.await(6, TimeUnit.SECONDS)
        timer.schedule({ http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }, 10, TimeUnit.SECONDS)
    }

    /** Put a list back as it was before a change: the version it replaced, signed again as the newest. */
    fun undo(change: Change) {
        val pub = accountHex() ?: return
        work.execute {
            try {
                val row = objects(readArray(history)).firstOrNull { it.getString("id") == change.id } ?: return@execute
                val prev = Event.fromMap(Json.parse(row.getString("prev"))) ?: return@execute
                val signed = signNow(Event(pub, maxOf(Clock.now(), (lists.latest(Policy.keyOf(prev))?.createdAt ?: 0) + 1), prev.kind, prev.tags, prev.content).withId(), tapped = true)
                lists.record(signed, pub)
                publish(signed)
                synchronized(this) { history.writeText(JSONArray(objects(readArray(history)).map { if (it.getString("id") == change.id) it.put("undone", true) else it }).toString()) }
                note("Undid: ${change.text}", "approved"); showHistory()
            } catch (e: Exception) { ui.update { it.copy(error = "Couldn't undo that: ${e.message}") } }
        }
    }

    /** Post a deleted thing again, from the copy this phone kept. It gets a new date; the old one stays deleted. */
    fun repost(d: Deleted) {
        val pub = accountHex() ?: return
        work.execute {
            try {
                val old = objects(readArray(kept)).mapNotNull { Event.fromMap(Json.parse(it.getString("event"))) }.firstOrNull { it.id == d.id } ?: return@execute
                val signed = signNow(Event(pub, Clock.now(), old.kind, old.tags, old.content).withId())
                publish(signed)
                synchronized(this) { kept.writeText(JSONArray(objects(readArray(kept)).filter { Event.fromMap(Json.parse(it.getString("event")))?.id != d.id }).toString()) }
                remember(signed, null, appName, true)
                note("Posted a deleted ${d.kind} again", "approved")
            } catch (e: Exception) { ui.update { it.copy(error = "Couldn't post that again: ${e.message}") } }
        }
    }

    /** What a site or an app did through this phone, before taking it all back. */
    fun askUndoSite(site: Site) {
        val events = objects(readArray(kept)).filter { it.optString("by") == site.origin && !it.has("deleted") }.mapNotNull { Event.fromMap(Json.parse(it.getString("event"))) }
        val listsChanged = objects(readArray(history)).filter { it.optString("by") == site.origin && !it.optBoolean("undone") }
        ui.update { it.copy(summary = SiteSummary(site.pk, site.origin, events.size + listsChanged.size, events.groupingBy { e -> Describe.kind(e.kind) }.eachCount(),
            listsChanged.map { r -> r.getString("text") }, events.size)) }
    }
    fun closeSummary() = ui.update { it.copy(summary = null) }
    /** Delete what it posted, put back the lists it changed, and sign it out. */
    fun confirmUndoSite() {
        val s = ui.value.summary ?: return
        val pub = accountHex() ?: return
        ui.update { it.copy(summary = null) }
        work.execute {
            try {
                val events = objects(readArray(kept)).filter { it.optString("by") == s.origin && !it.has("deleted") }.mapNotNull { Event.fromMap(Json.parse(it.getString("event"))) }
                if (events.isNotEmpty()) {
                    val del = signNow(Event(pub, Clock.now(), 5, events.map { listOf("e", it.id) }, "").withId(), tapped = true)
                    publish(del); remember(del, null, appName, true)
                }
                // The oldest change it made to each list holds what the list was before it.
                val rows = objects(readArray(history)).filter { it.optString("by") == s.origin && !it.optBoolean("undone") }
                for (r in rows.groupBy { it.getString("key") }.values.map { it.last() }) undo(Change(r.getString("id"), r.getString("text"), s.origin, r.getLong("at"), false, false))
                ui.value.sites.firstOrNull { it.pk == s.pk }?.let(::removeSite)
                sharedXs.forgetAll()
                note("Took back what ${s.origin} did", "approved")
            } catch (e: Exception) { ui.update { it.copy(error = "Couldn't take that back: ${e.message}") } }
        }
    }

    // --- Kept for the screens and services that still call them; nothing in Keykin v2 feeds them ----

    fun decideBatch(batch: String, approve: Boolean) {}
    fun cancelDeletion(batch: String) {}
    fun removeFuture(f: Future) {}
    /** ntfy woke the old servers' approvals. Nothing sends one now: the service keeps this phone listening. */
    fun setPushEndpoint(url: String?) {}

    private fun note(text: String, kind: String = "info") {
        ui.update { it.copy(log = (listOf(Line(System.currentTimeMillis(), text, kind)) + it.log).take(60)) }
        secrets.saveLog(Json.stringify(ui.value.log.map { mapOf("ts" to it.ts, "text" to it.text, "kind" to it.kind) }))
    }

    // Last, once everything above exists: what this phone kept from before.
    init {
        secrets.load("held")?.let { try { Held.of(JSONObject(it)) } catch (_: Exception) { null } }?.let { h ->
            held = h; h.changed = { secrets.save("held", it.json().toString()) }
            val cached = secrets.load("members")?.let { try { JSONArray(it) } catch (_: Exception) { null } }
            ui.update { it.copy(member = membership(h, cached?.let(::membersFrom) ?: emptyList())) }
        }
        secrets.loadLog()?.let { Json.parse(it) as? List<*> }?.let { lines ->
            ui.update { u -> u.copy(log = lines.mapNotNull { (it as? Map<*, *>)?.let { m -> Line(m["ts"] as Long, m["text"] as String, m["kind"] as? String ?: "info") } }) }
        }
        if (held != null) { showSites(); showHistory() }
        localKey()?.let { k -> localPub = cc.aaro.qrst.core.publicKeyHex(k); k.fill(0) }
        if (localPub != null) ui.update { it.copy(keptKey = true) }
        if (held == null) localPub?.let { pub -> ui.update { it.copy(member = localMembership(pub)) }; showSites(); showHistory() }
        // A request to a box that was still waiting when the app stopped: keep checking it.
        if (held == null) prefs.getString("ask", null)?.let(::JSONObject)?.let { o -> ui.update { it.copy(asking = Asking(o.getString("box"), o.optString("note"), 2)) }; watchAsk() }
        ui.update { it.copy(username = prefs.getString("username", null), loginPage = prefs.getString("login-page", null)?.ifEmpty { null }) }
    }

    companion object {
        /** How long something destructive waits for your answer before the app or site is told no. */
        const val ASK_LIFE = 120_000L
        val DEFAULT_RELAYS = listOf("wss://syb.lol", "wss://nos.lol", "wss://relay.primal.net")
        /** Where apps look up anyone's lists, asked too when [checkLists] looks for yours. */
        val INDEX_RELAYS = listOf("wss://purplepag.es", "wss://user.kindpag.es")
        /** Who made a change found on your relays, for its row: any app with your key, or another device with your box. */
        const val ELSEWHERE = "another app or device"
    }
}
