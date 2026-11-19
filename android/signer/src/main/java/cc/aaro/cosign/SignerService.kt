package cc.aaro.cosign

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteCallbackList
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * The signer process's door, and its only one for the main process (bound, not exported).
 * The UI's actions come in by name and run on this process's main thread, as they did when
 * the UI called [Signer] directly. Each account's state goes out with its secrets taken out
 * ([forUi]); the paper backup's code and a taken-out key are shown by the app, here ([Cosign.Host.reveal]).
 */
class SignerService : Service() {
    private val signers get() = Cosign.signers
    private val clients = RemoteCallbackList<ISignerClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    /** Every message to the UI goes out in order, from one thread. */
    private val out = Executors.newSingleThreadExecutor()
    private val watched = HashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        Proc.signerOnly("SignerService")
        scope.launch {
            combine(signers.slots, signers.activeSlot) { s, a -> s to a }.collect { (slots, active) ->
                watch(slots)
                send { it.accounts(slots.toTypedArray(), active) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        clients.kill()
        out.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Follow each account's state while it's on this phone. */
    private fun watch(slots: List<String>) = synchronized(watched) {
        for (gone in watched.keys - slots.toSet()) watched.remove(gone)?.cancel()
        for (slot in slots) if (slot !in watched) {
            val s = signers.signer(slot) ?: continue
            watched[slot] = scope.launch { s.ui.collect { u -> if (clients.registeredCallbackCount > 0) state(s, u).let { st -> send(st) } } }
        }
    }

    private fun state(s: Signer, u: Ui): (ISignerClient) -> Unit {
        val bytes = Wire.write(forUi(u))
        val label = s.label
        val needsPaper = s.needsPaper
        return { it.state(s.slot, bytes, label, needsPaper) }
    }

    private fun send(call: (ISignerClient) -> Unit) = out.execute {
        val n = clients.beginBroadcast()
        try { for (i in 0 until n) try { call(clients.getBroadcastItem(i)) } catch (e: Exception) { Log.w(TAG, "client: $e") } }
        finally { clients.finishBroadcast() }
    }

    /** Run on this process's main thread and wait: the UI used to call [Signer] from its main thread. */
    private fun <T> onMain(f: () -> T): T = FutureTask(f).also { main.post(it) }.get()

    private val binder = object : ISigner.Stub() {
        override fun listen(client: ISignerClient) {
            clients.register(client)
            // Everything as it is now, to this one: each account, then the list (which says it's ready).
            val now = signers.all.map { s -> state(s, s.ui.value) }
            val slots = signers.slots.value.toTypedArray()
            val active = signers.activeSlot.value
            out.execute {
                try { for (st in now) st(client); client.accounts(slots, active) } catch (e: Exception) { Log.w(TAG, "listen: $e") }
            }
        }

        override fun act(slot: String, action: String, text: String?, flag: Boolean) {
            main.post {
                val s = signers.signer(slot) ?: run { Log.w(TAG, "$action: no account $slot"); return@post }
                try { act(s, action, text, flag) } catch (e: Exception) { Log.w(TAG, "$action: $e") }
            }
        }

        override fun importKey(slot: String, secret: CharArray) {
            main.post { signers.signer(slot)?.importKey(secret) ?: secret.fill(' ') }
        }

        // Blocks for a few seconds (your servers sign too): the UI calls it off its main thread.
        override fun signOwn(slot: String, event: String, approved: Boolean): Bundle = Bundle().apply {
            try {
                val s = signers.signer(slot) ?: error("no account $slot")
                // Rebuilt here as it will be signed: under this account's key, with its own id.
                putString("event", s.signOwn(s.appEvent(event), approved).toJson())
            } catch (e: NeedsApproval) {
                putString("approval", e.what)
            } catch (e: Exception) {
                putString("error", e.message ?: e.javaClass.simpleName)
            }
        }

        // Blocks (a server takes part in the shared secret): the UI calls it off its main thread.
        override fun nip44Own(slot: String, text: String, encrypt: Boolean): String? = try {
            signers.signer(slot)?.nip44Self(text, encrypt)
        } catch (e: Exception) { null }

        // Both block (the box takes part): the app calls them off its main thread.
        override fun vaultSeal(slot: String, eph: String, ct: String): String? = try { signers.signer(slot)?.openVaultSeal(eph, ct) } catch (e: Exception) { null }
        override fun vaultKeykin(slot: String): String? = try { signers.signer(slot)?.vaultKeykin() } catch (e: Exception) { null }

        override fun latest(slot: String, key: String): String? = signers.signer(slot)?.latest(key)?.toJson()

        override fun add(): String = onMain { signers.add().slot }
        override fun show(slot: String) { main.post { signers.show(slot) } }
        override fun forget(slot: String) { main.post { signers.forget(slot) } }
    }

    /** What the UI may ask of an account, all of it. Things on screen are named by their id. */
    private fun act(s: Signer, action: String, text: String?, flag: Boolean) {
        val u = s.ui.value
        when (action) {
            "accept" -> s.accept()
            "decline" -> s.decline()
            "createAccount" -> s.createAccount()
            "startJoin" -> s.startJoin()
            "pasted" -> s.pasted(text ?: "")
            "closeJoinText" -> s.closeJoinText()
            "cycle" -> s.cycle()
            "unblock" -> s.unblock(text ?: return)
            "digits" -> s.digits(flag)
            "makeBunker" -> s.makeBunker()
            "cancelImport" -> s.cancelImport()
            "forgetKey" -> s.forgetKey()
            "tap" -> s.tap(flag, text)
            "testChild" -> s.testChild(text ?: return)   // debug builds only (the signer checks)
            "createLocal" -> s.createLocal(); "receiveKey" -> s.receiveKey(); "keepKey" -> s.keepKey(); "declineKey" -> s.declineKey()
            "sendKey" -> s.sendKey(); "moveDigits" -> s.moveDigits(text); "closeMove" -> s.closeMove()
            "askAccount" -> s.askAccount(text?.substringBefore('\n') ?: return, text.substringAfter('\n', ""))
            "cancelAsk" -> s.cancelAsk(); "startAsk" -> s.startAsk(); "closeAsk" -> s.closeAsk()
            "testRelays" -> s.testRelays(text ?: return)
            "pointerNow" -> s.pointerNow()
            "loginSwitch" -> s.answerLoginSwitch(flag)
            "setPassword" -> s.setPassword(text ?: return)
            "clearPassword" -> s.clearPassword()
            "setLoginPage" -> s.setLoginPage(text ?: "")
            "closeBunker" -> s.closeBunker()
            "refresh" -> Thread { s.refresh() }.start()
            "cancelJoin" -> s.cancelJoin()
            "scanned" -> s.scanned(text ?: "")
            "pastedBackup" -> s.pastedBackup(text ?: "")
            "clearError" -> s.clearError()
            "showError" -> s.showError(text ?: "")
            "lockMissing" -> s.lockMissing()
            "leave" -> s.leave()
            "decide" -> s.decide(text ?: return, flag)
            "decideBatch" -> s.decideBatch(text ?: return, flag)
            "cancelDeletion" -> s.cancelDeletion(text ?: return)
            "connectScopes" -> (text ?: "").split(',').let { s.setConnectScopes("lists" in it, "dms" in it) }
            "siteScopes" -> (text ?: "").split('|', limit = 2).let { p -> val f = p.getOrNull(1).orEmpty().split(','); s.setSiteScopes(p[0], "lists" in f, "dms" in f) }
            "confirmConnect" -> s.confirmConnect(text)
            "closeConnect" -> s.closeConnect()
            "connectInBackground" -> s.connectInBackground = flag
            "confirmAddDevice" -> s.confirmAddDevice()
            "cancelAddDevice" -> s.cancelAddDevice()
            "askReportLost" -> u.member?.group?.members?.firstOrNull { it.idx.toString() == text }?.let(s::askReportLost)
            "confirmReportLost" -> s.confirmReportLost()
            "cancelReportLost" -> s.cancelReportLost()
            "removeSite" -> u.sites.firstOrNull { it.pk == text }?.let(s::removeSite)
            "askUndoSite" -> u.sites.firstOrNull { it.pk == text }?.let(s::askUndoSite)
            "confirmUndoSite" -> s.confirmUndoSite()
            "closeSummary" -> s.closeSummary()
            "undo" -> u.changes.firstOrNull { it.id == text }?.let(s::undo)
            // A version of one of your lists the app's reader found (the signer checks it is yours and signed), and a look now.
            "seen" -> s.seen(text ?: return)
            "checkLists" -> s.checkListsSoon()
            "repost" -> u.deleted.firstOrNull { it.id == text }?.let(s::repost)
            "removeFuture" -> u.future.firstOrNull { it.id == text }?.let(s::removeFuture)
            "takeOutViaServers" -> s.takeOutViaServers()
            "closeTakeOut" -> s.closeTakeOut()
            // The whole key, or a bunker link, is ready: show it here, never in the UI's process.
            "reveal" -> if (u.bunker != null || u.takeOut?.stage == TakeOut.Stage.SHOWING) Cosign.host.reveal(this, s.slot)
            else -> Log.w(TAG, "unknown action $action")
        }
    }

    companion object {
        private const val TAG = "CosignSigner"

        /** An account's state as the UI may see it: no nsec and no bunker link (Ui never holds a piece or a member key). */
        fun forUi(u: Ui) = u.copy(
            // Still there (so the UI knows to ask for it to be shown), but empty.
            bunker = u.bunker?.let { "" },
            takeOut = u.takeOut?.copy(nsec = null),
        )
    }
}

/** A [Ui] across the process boundary: from the signer to the UI only. */
object Wire {
    fun write(u: Ui): ByteArray = java.io.ByteArrayOutputStream().also { b -> java.io.ObjectOutputStream(b).use { it.writeObject(u) } }.toByteArray()

    fun read(bytes: ByteArray): Ui = object : java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)) {
        override fun resolveClass(desc: java.io.ObjectStreamClass): Class<*> = Class.forName(desc.name, false, Wire::class.java.classLoader)
    }.use { it.readObject() as Ui }
}
