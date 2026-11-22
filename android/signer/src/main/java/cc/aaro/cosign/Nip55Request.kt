package cc.aaro.cosign

import android.app.Activity
import android.content.Intent
import cc.aaro.qrst.core.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Android signer, when you need to say something (NIP-55's Intents): an app that isn't
 * connected yet, something destructive, or the first time an app wants your messages.
 * Everything else an app asks goes to Nip55Provider and never opens this.
 *
 * The app's own activity for `nostrsigner:` (in the signer process) hands each intent to
 * [handle], draws [stage], and calls [connect], [allowDms], [approve] or [reject] on the
 * person's answer. This answers the asking app and closes [activity].
 *
 * An app can send many at once (Amethyst sends one for each private zap it shows, dozens in a second). The sheet is
 * singleTop, so they all arrive here: each is kept as it came ([Req]), the ones after the first wait their turn, and
 * when none is left they all go back in one answer, each with its id (NIP-55's `results`). Before 2026-11-21 a request
 * that came in while you were answering another took its place, and your Yes went to the wrong one ("bad_peer").
 *
 * What goes wrong is recorded in [ErrorLog]. You're only shown what you can do something about; an app's request
 * Marble can't or won't answer is turned down quietly.
 */
class Nip55Request(private val activity: Activity) {
    sealed interface Stage {
        data object Connect : Stage
        data object Dms : Stage
        /**
         * [level] 2: Yes goes on to the fingerprint. [switch]: a change to the relay list or DM relays by an app on the
         * main account, where the first answer offered is to move the app to a key of its own ([switchKey]).
         */
        data class Approve(val what: String, val level: Int = 1, val switch: Boolean = false) : Stage
        /** The app was moved to a key of its own; [text] says what happened and what to do in the app. */
        data class Switched(val text: String) : Stage
        /** An app moved to a key of its own signs in again: [text] says why it gets a different key now. */
        data class Moved(val text: String) : Stage
        data class Working(val text: String) : Stage
        data class Failed(val text: String) : Stage
    }

    /** One request, as it came. Nothing that comes after it changes it. */
    private class Req(
        val type: String,
        val payload: String,
        val peer: String,
        /** The key the app thinks it is signed in as (NIP-55's current_user), if it says. */
        val asUser: String?,
        val id: String?,
        val pkg: String,
        /** Who's asking: the app's name, or the page's host. */
        val label: String,
        /** A web page's request (NIP-55 for the web): the answer goes back to this URL, not to an app. */
        val callback: String? = null,
        val returnType: String = "signature",
        val gzip: Boolean = false,
        /** Why Marble can't answer this one at all (no app to answer, a bad return address): turned down quietly. */
        val bad: String? = null,
    ) {
        var event: Event? = null
    }

    private var r = Req("get_public_key", "", "", null, null, "", "This app")
    /** A request is on screen or being worked on; any that come now wait in [waiting]. */
    private var busy = false
    private val waiting = ArrayDeque<Req>()
    /** The answers so far, sent back together once [waiting] is empty. */
    private val answers = mutableListOf<Map<String, Any>>()

    /** The key this app connected to, or (to connect one) the key on screen. */
    val phone get() = Cosign.signers.let { a -> a.forApp(r.pkg) ?: a.active }
    /** Who's asking: the app's name, or the page's host. */
    val label get() = r.label
    private val work = Executors.newSingleThreadExecutor()
    private val current = MutableStateFlow<Stage>(Stage.Working("…"))
    val stage: StateFlow<Stage> = current

    /** More than one key on this phone: say which one this is about. */
    val several get() = Cosign.signers.all.count { it.isMember } > 1

    fun handle(intent: Intent) {
        val q = read(intent)
        if (busy) waiting.addLast(q) else start(q)
    }

    private fun read(intent: Intent): Req {
        val data = intent.dataString?.removePrefix("nostrsigner:") ?: ""
        // A web page puts everything in the link: nostrsigner:<payload>?type=…&callbackUrl=…
        val query = data.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val callback = query["callbackUrl"]?.takeIf { intent.getStringExtra("type") == null }
        val raw = if (callback != null) data.substringBefore('?') else data
        val type = intent.getStringExtra("type") ?: query["type"] ?: "get_public_key"
        val payload = if (raw.startsWith("%7B", ignoreCase = true) || (type != "sign_event" && Regex("%[0-9A-Fa-f]{2}").containsMatchIn(raw)))
            URLDecoder.decode(raw, "UTF-8") else raw
        val peer = intent.getStringExtra("pubkey") ?: query["pubkey"] ?: ""
        val asUser = (intent.getStringExtra("current_user") ?: query["current_user"])?.takeIf { it.length == 64 }
        val id = intent.getStringExtra("id")
        if (callback != null) {
            val web = webOrigin(callback) ?: return Req(type, payload, peer, asUser, id, "web:?", "This page", bad = "callback not https")
            return Req(type, payload, peer, asUser, id, "web:$web", android.net.Uri.parse(web).host ?: web, callback,
                query["returnType"] ?: "signature", query["compressionType"] == "gzip")
        }
        val pm = activity.packageManager
        val pkg = activity.callingActivity?.packageName ?: activity.callingPackage ?: ""
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.ifEmpty { "This app" } }
        return Req(type, payload, peer, asUser, id, pkg, label, bad = if (pkg.isEmpty()) "no calling package" else null)
    }

    private fun start(q: Req) {
        r = q; busy = true
        current.value = Stage.Working("…")
        when {
            q.bad != null -> quietNo(q.bad)
            phone.ui.value.member == null -> fail("$appName isn't set up yet. Open $appName and set up your account first.")
            phone.appGrant(q.pkg) == null -> current.value = Stage.Connect
            else -> next()
        }
    }

    /** Yes to [Stage.Connect]. The grant is kept on this phone, so the app's request is answered at once. */
    fun connect() {
        phone.connectApp(r.pkg, r.label)
        next()
    }

    /** Yes to [Stage.Dms]. */
    fun allowDms() { phone.allowDms(phone.appGrant(r.pkg)!!); crypt() }

    /** Yes to [Stage.Approve]: "Let it change my main lists". */
    fun approve() = sign(r.event!!, approved = true)

    /**
     * "Switch" on [Stage.Approve]: the app gets a key of its own, its access to the main account ends, and its changes
     * to your relay lists are put back. This request is not signed: the app hears no, and signs in again.
     */
    fun switchKey() {
        val q = r
        current.value = Stage.Working("Making ${q.label} its own account…")
        work.execute {
            try {
                val back = phone.switchApp(q.pkg)
                val restored = if (back > 0) " Your relay lists are being put back as they were before ${q.label} changed them." else ""
                activity.runOnUiThread {
                    current.value = Stage.Switched("${q.label} has its own account now, and can no longer use your main one.$restored\n\n" +
                        "To start using it, sign out in ${q.label} and sign in again with $appName.")
                }
            } catch (e: Exception) {
                activity.runOnUiThread { failed(e, "Couldn't give ${q.label} its own account: ") }
            }
        }
    }

    /** Continue on [Stage.Moved]: the app signs in on its own key from now on. */
    fun signInMoved() { phone.signedInAgain(r.pkg); next() }

    /** With the app connected: answer, or ask you first. */
    private fun next() {
        val q = r
        val g = phone.appGrant(q.pkg) ?: return run { current.value = Stage.Connect }
        val main = phone.ui.value.member!!.group.groupX
        // Moved to a key of its own and still signed in as the main account: it has to sign in again.
        if (g.place != null && q.type != "get_public_key" && (q.asUser == main || eventBy(q) == main))
            return fail("${q.label} is still signed in as your main account, but it has its own account now. Sign out in ${q.label} and sign in again with $appName.")
        when (q.type) {
            "get_public_key" -> phone.moved(q.pkg)?.let { at ->
                current.value = Stage.Moved("${q.label} has its own account now, not your main one: you moved it on ${java.text.DateFormat.getDateInstance().format(java.util.Date(at * 1000))} after it tried to change your relays. " +
                    "Messages to your main account won't reach it, so it gets no spam meant for you.")
            } ?: keyThen { answer("result" to it, "package" to activity.packageName) }
            "sign_event" -> {
                val ev = try { phone.appEvent(q.payload) } catch (e: Exception) { return quietNo("not an event it can sign", e) }
                q.event = ev
                cc.aaro.cosign.core.Policy.refuse(ev)?.let { return fail("$appName won't sign this: $it.") }
                val ask = if (g.place != null) cc.aaro.cosign.core.Policy.question(ev, null, child = true) else phone.questionFor(ev)
                if (ask != null) current.value = Stage.Approve(ask.says, ask.level, phone.offersSwitch(g, ev)) else sign(ev, approved = false)
            }
            "nip44_encrypt", "nip44_decrypt", "nip04_encrypt", "nip04_decrypt" -> when {
                // No one Marble can make out to write to or read from: the app's mistake, so a quiet no (before 2026-11-21, "bad_peer" on screen).
                peerKey(q.peer) == null -> quietNo("bad peer (${peerShape(q.peer)})")
                !g.dm -> current.value = Stage.Dms
                else -> crypt()
            }
            // A private zap (Amethyst asks for each it shows). Never a question: without messages allowed, a quiet no.
            "decrypt_zap_event" -> if (!g.dm) quietNo("messages not allowed for this app") else zap()
            else -> quietNo("type not supported")
        }
    }

    /** The pubkey the app put in the event it sent, if any. */
    private fun eventBy(q: Req): String? = if (q.type != "sign_event") null else try { ((cc.aaro.qrst.core.Json.parse(q.payload) as? Map<*, *>)?.get("pubkey") as? String)?.takeIf { it.length == 64 } } catch (_: Exception) { null }

    /** The app's key (its own child may have to be made, with the box), then [then] with it. */
    private fun keyThen(then: (String) -> Unit) {
        val q = r
        work.execute {
            try { val k = phone.appKey(phone.appGrant(q.pkg)!!); activity.runOnUiThread { then(k) } }
            catch (e: Exception) { activity.runOnUiThread { failed(e) } }
        }
    }

    private fun sign(ev: Event, approved: Boolean) {
        current.value = Stage.Working(if (approved) "Signing…" else "Signing with your box…")
        val q = r
        work.execute {
            try {
                val signed = phone.signForApp(phone.appGrant(q.pkg)!!, ev, approved)
                activity.runOnUiThread { answer("result" to signed.sig, "event" to signed.toJson()) }
            } catch (e: NeedsApproval) {
                val g = phone.appGrant(q.pkg)
                activity.runOnUiThread { current.value = Stage.Approve(e.what, e.level, g != null && phone.offersSwitch(g, ev)) } // your server saw what this phone missed
            } catch (_: Revoked) {
                activity.runOnUiThread { current.value = Stage.Connect }
            } catch (e: Exception) {
                activity.runOnUiThread { failed(e) }
            }
        }
    }

    private fun crypt() {
        current.value = Stage.Working("Working with your box…")
        val q = r
        work.execute {
            try {
                val g = phone.appGrant(q.pkg)!!
                val encrypt = q.type.endsWith("encrypt")
                val peer = peerKey(q.peer)!!
                val out = if (q.type.startsWith("nip44")) phone.nip44(g, peer, q.payload, encrypt) else phone.nip04(g, peer, q.payload, encrypt)
                activity.runOnUiThread { answer("result" to out) }
            } catch (e: Exception) {
                // A message that doesn't open: the app hears no, and you aren't shown it (see [unreadable]).
                val appsFault = !q.type.endsWith("encrypt") && unreadable(e)
                activity.runOnUiThread { if (appsFault) quietNo("can't open or address it", e) else failed(e) }
            }
        }
    }

    private fun zap() {
        current.value = Stage.Working("Working with your box…")
        val q = r
        work.execute {
            val out = try { phone.decryptZap(phone.appGrant(q.pkg)!!, q.payload) } catch (e: Exception) { ErrorLog.record("nip55", e, "app" to q.pkg, "type" to q.type); null }
            activity.runOnUiThread { if (out != null) answer("result" to out) else quietNo("private zap not to this key, or sent by it") }
        }
    }

    /** The site behind a callback URL: https, or http on this machine for a site being built. */
    private fun webOrigin(url: String): String? {
        val u = android.net.Uri.parse(url)
        val host = u.host ?: return null
        if (u.scheme != "https" && !(u.scheme == "http" && host in setOf("localhost", "127.0.0.1"))) return null
        return "${u.scheme}://$host${if (u.port > 0) ":${u.port}" else ""}"
    }

    private fun answer(vararg extras: Pair<String, String>) { done(r, mapOf("package" to activity.packageName) + extras.toMap()); after() }

    /** No, or back: the asking app hears it was rejected. A no to connecting it, or to its messages, is a no to the ones of those it has waiting, too. */
    fun reject() {
        val q = r
        val alike: (Req) -> Boolean = when (current.value) {
            Stage.Connect, is Stage.Failed -> { w -> w.pkg == q.pkg }
            Stage.Dms -> { w -> w.pkg == q.pkg && (w.type.startsWith("nip04") || w.type.startsWith("nip44")) }
            else -> { _ -> false }
        }
        done(q, REJECTED)
        waiting.filter(alike).forEach { done(it, REJECTED) }
        waiting.removeAll(alike)
        after()
    }

    /** The app's request is one Marble can't or won't answer: it hears no, you aren't asked, and [why] goes to [ErrorLog]. */
    private fun quietNo(why: String, e: Throwable? = null) {
        ErrorLog.record("nip55", e, "app" to r.pkg, "type" to r.type, "why" to why)
        done(r, REJECTED); after()
    }

    /** [e] went wrong on this phone or with your box: in plain words on screen (the detail is in ErrorLog). */
    private fun failed(e: Throwable, lead: String = "") {
        ErrorLog.record("nip55", e, "app" to r.pkg, "type" to r.type)
        fail(lead + phone.words(e))
    }

    private fun fail(text: String) { current.value = Stage.Failed(text) }

    /** [q] is answered: a page hears it at once, at its own address; an app's answers wait for the rest. */
    private fun done(q: Req, a: Map<String, Any>) {
        val url = q.callback
        if (url == null) { answers += (q.id?.let { mapOf("id" to it) } ?: emptyMap()) + a; return }
        if (a["rejected"] == true) return
        // The page asked for the whole event or just the result; gzip as "Signer1" + base64.
        var value = ((if (q.returnType == "event") a["event"] else null) ?: a["result"] ?: "").toString()
        if (q.gzip) value = "Signer1" + java.util.Base64.getEncoder().encodeToString(
            java.io.ByteArrayOutputStream().also { b -> java.util.zip.GZIPOutputStream(b).use { it.write(value.toByteArray()) } }.toByteArray())
        activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url + android.net.Uri.encode(value))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** On to the next request waiting, or, with none, answer the app and close. */
    private fun after() {
        busy = false
        waiting.removeFirstOrNull()?.let { return start(it) }
        if (answers.isNotEmpty()) activity.setResult(Activity.RESULT_OK, reply(answers))
        activity.finish()
    }

    companion object {
        private val REJECTED = mapOf<String, Any>("rejected" to true)

        /** One answer as the intent's own extras; more than one as NIP-55's `results`, a JSON list each with its id. */
        internal fun reply(answers: List<Map<String, Any>>): Intent = Intent().apply {
            if (answers.size == 1) for ((k, v) in answers[0]) if (v is Boolean) putExtra(k, v) else putExtra(k, v.toString())
            else putExtra("results", JSONArray(answers.map { JSONObject(it) }).toString())
        }
    }
}
