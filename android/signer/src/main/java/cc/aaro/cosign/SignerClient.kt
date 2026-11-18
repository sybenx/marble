package cc.aaro.cosign

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The main process's side of the signer. The UI works with [Accounts] and [Phone] as it
// always did; underneath, each call goes to SignerService in the signer process, and each
// account's state comes back with its secrets taken out. Nothing here can sign or decrypt
// by itself, or read a share.

/** The accounts on this phone, as the UI sees them. Main process only. */
class Accounts(private val context: Context) {
    private val phones = java.util.concurrent.ConcurrentHashMap<String, Phone>()
    private val order = MutableStateFlow<List<String>>(emptyList())
    private val shown = MutableStateFlow("0")
    /** Which account is on screen, by slot. */
    val activeSlot: StateFlow<String> = shown
    private val known = MutableStateFlow(false)
    /** The signer has said which accounts there are (and how each one is): until then, draw nothing. */
    val ready: StateFlow<Boolean> = known
    @Volatile private var signer: ISigner? = null

    private val client = object : ISignerClient.Stub() {
        override fun accounts(slots: Array<String>, active: String) {
            for (s in slots) at(s)
            order.value = slots.toList()
            shown.value = active
            known.value = true
        }

        override fun state(slot: String, ui: ByteArray, label: String, needsPaper: Boolean) {
            val p = at(slot)
            p.label = label
            p.needsPaper = needsPaper
            p.ui.value = try { Wire.read(ui) } catch (e: Exception) { Log.w(TAG, "state: $e"); return }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Startup.mark("signer")
            val s = ISigner.Stub.asInterface(binder)
            signer = s
            try { s.listen(client) } catch (e: Exception) { Log.w(TAG, "listen: $e") }
        }
        // The signer process stopped: Android starts it again and calls onServiceConnected.
        override fun onServiceDisconnected(name: ComponentName) { signer = null }
        override fun onBindingDied(name: ComponentName) { signer = null; context.unbindService(this); bind() }
    }

    fun bind() { context.bindService(Intent(context, SignerService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }

    private fun at(slot: String) = phones.getOrPut(slot) { Phone(slot) { signer } }

    val all: List<Phone> get() = order.value.map(::at)
    val active: Phone get() = at(shown.value)
    fun phone(slot: String): Phone? = phones[slot]

    fun show(slot: String) {
        if (slot !in order.value) return
        shown.value = slot
        call("show") { it.show(slot) }
    }

    /** A place for another account: a new, empty one, shown, ready to be joined, made, imported or restored. */
    fun add(): Phone {
        val slot = call("add") { it.add() } ?: return active
        if (slot !in order.value) order.value = order.value + slot
        shown.value = slot
        return at(slot)
    }

    /** An empty place given up: forget it, and show another. */
    fun forget(slot: String) {
        if (order.value.size <= 1) return
        order.value = order.value - slot
        if (shown.value == slot) shown.value = order.value.first()
        call("forget") { it.forget(slot) }
    }

    private fun <T> call(what: String, f: (ISigner) -> T): T? = try {
        signer?.let(f) ?: run { Log.w(TAG, "$what: the signer isn't connected"); null }
    } catch (e: Exception) { Log.w(TAG, "$what: $e"); null }

    private companion object { const val TAG = "CosignClient" }
}

/**
 * One account, as the UI sees it: its state ([ui], with no secrets in it) and what you can
 * do with it. Every action goes to the account's [Signer] in the signer process and comes
 * back as a change of state; the UI never waits on one. Main process only.
 */
class Phone internal constructor(val slot: String, private val signer: () -> ISigner?) {
    val ui = MutableStateFlow(Ui())
    /** Which key this is, for a list: its npub, shortened (from the signer, with each state). */
    @Volatile var label = "New key"; internal set
    /** One device and one box: lose this phone and the account is gone. */
    @Volatile var needsPaper = false; internal set
    val isMember get() = ui.value.member != null

    /** A sign-in you said yes to finishes in the background; a failure is a notification. */
    var connectInBackground = false
        set(v) { field = v; act("connectInBackground", flag = v) }

    private fun act(action: String, text: String? = null, flag: Boolean = false) {
        try { signer()?.act(slot, action, text, flag) ?: Log.w("CosignClient", "$action: the signer isn't connected") } catch (e: Exception) { Log.w("CosignClient", "$action: $e") }
    }

    // Joining, making, importing or restoring a key.
    fun accept() = act("accept")
    fun decline() = act("decline")
    fun createAccount() = act("createAccount")
    fun startJoin() = act("startJoin")
    fun cancelJoin() = act("cancelJoin")
    fun scanned(text: String) = act("scanned", text)
    /** A code that was copied and pasted, not read by this phone's camera: a site's code is then checked by comparing digits. */
    fun pasted(text: String) = act("pasted", text)
    fun closeJoinText() = act("closeJoinText")
    /** Ask the box again who is in the account. */
    fun refresh() = act("refresh")
    fun pastedBackup(text: String) = act("pastedBackup", text)
    /** The nsec typed in: straight to the signer, which reads it and wipes it. Wipes [chars] here. */
    fun importKey(chars: CharArray) {
        try { signer()?.importKey(slot, chars) ?: Log.w("CosignClient", "importKey: the signer isn't connected") } catch (e: Exception) { Log.w("CosignClient", "importKey: $e") }
        finally { chars.fill(' ') }
    }
    fun cancelImport() = act("cancelImport")
    /** Debug builds only: make or find the child for a place, sign and encrypt as it, and log it for a test ("-place" forgets it first). */
    fun testChild(place: String) = act("testChild", place)
    /** Tier 1: a new account with its key on this phone. No box. */
    fun createLocal() = act("createLocal")
    /** The whole key by QRST: receive it here, keep it, send it (after the consent and fingerprint), digits, close. */
    fun receiveKey() = act("receiveKey"); fun keepKey() = act("keepKey"); fun declineKey() = act("declineKey")
    fun sendKey() = act("sendKey"); fun moveDigits(digits: String?) = act("moveDigits", digits); fun closeMove() = act("closeMove")
    /** Your own choice: the whole key of a brought-in account goes from this phone. The pieces keep signing. */
    fun forgetKey() = act("forgetKey")
    fun clearError() = act("clearError")
    fun showError(text: String) = act("showError", text)
    fun lockMissing() = act("lockMissing")
    fun leave() = act("leave")

    // Approvals, deletions, sign-ins.
    fun decide(id: String, approve: Boolean) = act("decide", id, approve)
    fun decideBatch(batch: String, approve: Boolean) = act("decideBatch", batch, approve)
    fun cancelDeletion(batch: String) = act("cancelDeletion", batch)
    /** Yes to a site's sign-in. [pick] "new": a site new to this account gets an account of its own; otherwise the main account. */
    /** The sign-in card's two switches, for the [confirmConnect] that follows. */
    fun setConnectScopes(lists: Boolean, dms: Boolean) = act("connectScopes", listOfNotNull("lists".takeIf { lists }, "dms".takeIf { dms }).joinToString(","))
    fun confirmConnect(pick: String? = null) = act("confirmConnect", pick)
    /** A signed-in site's "Read your private lists" and "Read and send DMs", from its sheet. */
    fun setSiteScopes(site: Site, lists: Boolean, dms: Boolean) = act("siteScopes", site.pk + "|" + listOfNotNull("lists".takeIf { lists }, "dms".takeIf { dms }).joinToString(","))
    fun closeConnect() = act("closeConnect")
    /** The five digits this phone shows were compared with the site's. */
    fun digits(same: Boolean) = act("digits", flag = same)
    /** A bunker:// link to paste into a site: made, then shown by the signer ([reveal]). */
    fun makeBunker() = act("makeBunker")
    fun closeBunker() = act("closeBunker")
    /** Yes or No to something hard to undo that your account page asked for. */
    fun tap(yes: Boolean, id: String? = null) = act("tap", id, flag = yes)
    /** Ask a box for an account (no setup code): its admin approves, then the key is made. */
    fun askAccount(box: String, note: String) = act("askAccount", box + "\n" + note)
    fun cancelAsk() = act("cancelAsk"); fun startAsk() = act("startAsk"); fun closeAsk() = act("closeAsk")
    /** Debug builds only: the relays this phone's bunker links name and its own events go to (a test's own relay). */
    fun testRelays(urls: String) = act("testRelays", urls)
    fun pointerNow() = act("pointerNow")
    /** The account page's username and password. Fingerprint. The box never sees the password. */
    fun setPassword(username: String, password: String) = act("setPassword", username + "\n" + password)
    fun clearPassword() = act("clearPassword")
    /** Where this account's login page is: published as a small signed event that marble.syb.lol reads. Empty removes it. */
    fun setLoginPage(url: String) = act("setLoginPage", url)

    // Devices, sites, history.
    fun confirmAddDevice() = act("confirmAddDevice")
    fun cancelAddDevice() = act("cancelAddDevice")
    fun askReportLost(d: Member) = act("askReportLost", d.idx.toString())
    /** Cycle the keys, so a blocked device's piece fits nothing. Fingerprint. */
    fun cycle() = act("cycle")
    /** Fingerprint. */
    fun unblock(d: Member) = act("unblock", d.pk)
    fun confirmReportLost() = act("confirmReportLost")
    fun cancelReportLost() = act("cancelReportLost")
    fun removeSite(site: Site) = act("removeSite", site.pk)
    fun askUndoSite(site: Site) = act("askUndoSite", site.pk)
    fun confirmUndoSite() = act("confirmUndoSite")
    fun closeSummary() = act("closeSummary")
    fun undo(change: Change) = act("undo", change.id)
    /**
     * The newest version of one of your lists that the reader found on a relay: the signer compares it with the one it saw
     * last, and a change made in another app or device goes into Recent changes. One call per list.
     */
    fun seen(e: Event) = act("seen", e.toJson())
    /** Ask your relays now for changes to your lists made elsewhere (the signer also does every 30 minutes). */
    fun checkLists() = act("checkLists")
    fun repost(d: Deleted) = act("repost", d.id)
    fun removeFuture(f: Future) = act("removeFuture", f.id)

    // Forming the whole key.
    /** Form the whole key on this phone and show it. Fingerprint. */
    fun takeOutViaServers() = act("takeOutViaServers")
    fun closeTakeOut() = act("closeTakeOut")
    /** The whole key or a bunker link is ready ([ui] says so, without it): the signer shows it, over this app. */
    fun reveal() = act("reveal")

    // Cosign's own actions (a like, a follow).

    /** Sign something you did in Cosign itself. Blocks for a few seconds; never call on the main thread. */
    fun signOwn(ev: Event, approved: Boolean = false): Event {
        val s = signer() ?: throw IllegalStateException("$appName's signer isn't running")
        val r = s.signOwn(slot, ev.toJson(), approved)
        r.getString("approval")?.let { throw NeedsApproval(it) }
        r.getString("event")?.let { j -> return Event.fromMap(Json.parse(j)) ?: throw IllegalStateException("bad event from the signer") }
        throw IllegalStateException(r.getString("error") ?: "no answer")
    }

    /**
     * Encrypt or decrypt your own app data with your own account (NIP-44 to self). The other
     * side is always your account, so this reads nothing but what it wrote. Blocks for a few
     * seconds, as a server takes part: never call it on the main thread. Null if it failed.
     */
    fun nip44Self(text: String, encrypt: Boolean): String? = try { signer()?.nip44Own(slot, text, encrypt) } catch (e: Exception) { null }

    /** Vaults: a keyring entry's seal opened (one `ecdh` with your box), or null. Blocks; never on the main thread. */
    fun vaultSeal(eph: String, ct: String): String? = try { signer()?.vaultSeal(slot, eph, ct) } catch (e: Exception) { null }
    /** Vaults: Keykin's view of the account (JSON), or null without a box or a signer. Blocks. */
    fun vaultKeykin(): String? = try { signer()?.vaultKeykin(slot) } catch (e: Exception) { null }

    /** The latest version of one of your lists this phone has seen signed ("3": your follows). */
    fun latest(key: String): Event? = try { signer()?.latest(slot, key)?.let { Event.fromMap(Json.parse(it)) } } catch (e: Exception) { null }
}
