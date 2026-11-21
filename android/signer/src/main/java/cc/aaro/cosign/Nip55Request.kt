package cc.aaro.cosign

import android.app.Activity
import android.content.Intent
import cc.aaro.qrst.core.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** The key this app connected to, or (to connect one) the key on screen. */
    val phone get() = Cosign.signers.let { a -> a.forApp(pkg) ?: a.active }
    private val work = Executors.newSingleThreadExecutor()
    private val current = MutableStateFlow<Stage>(Stage.Working("…"))
    val stage: StateFlow<Stage> = current
    private lateinit var type: String
    private lateinit var payload: String
    private var peer = ""
    /** The key the app thinks it is signed in as (NIP-55's current_user), if it says. */
    private var asUser: String? = null
    private var reqId: String? = null
    private var pkg = ""
    /** Who's asking: the app's name, or the page's host. */
    var label = "This app"
        private set
    private var event: Event? = null
    /** A web page's request (NIP-55 for the web): the answer goes back to this URL, not to an app. */
    private var callback: String? = null
    private var returnType = "signature"
    private var gzip = false

    /** More than one key on this phone: say which one this is about. */
    val several get() = Cosign.signers.all.count { it.isMember } > 1

    fun handle(intent: Intent) {
        val data = intent.dataString?.removePrefix("nostrsigner:") ?: ""
        // A web page puts everything in the link: nostrsigner:<payload>?type=…&callbackUrl=…
        val query = data.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        callback = query["callbackUrl"]?.takeIf { intent.getStringExtra("type") == null }
        val raw = if (callback != null) data.substringBefore('?') else data
        type = intent.getStringExtra("type") ?: query["type"] ?: "get_public_key"
        payload = if (raw.startsWith("%7B", ignoreCase = true) || (type != "sign_event" && Regex("%[0-9A-Fa-f]{2}").containsMatchIn(raw)))
            URLDecoder.decode(raw, "UTF-8") else raw
        peer = intent.getStringExtra("pubkey") ?: query["pubkey"] ?: ""
        asUser = (intent.getStringExtra("current_user") ?: query["current_user"])?.takeIf { it.length == 64 }
        reqId = intent.getStringExtra("id")
        val web = callback?.let { webOrigin(it) }
        if (callback != null) {
            returnType = query["returnType"] ?: "signature"; gzip = query["compressionType"] == "gzip"
            if (web == null) return fail("That page's return address isn't one $appName answers (it must be https).")
            pkg = "web:$web"; label = android.net.Uri.parse(web).host ?: web
        } else {
            val pm = activity.packageManager
            pkg = activity.callingActivity?.packageName ?: activity.callingPackage ?: ""
            label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.ifEmpty { "This app" } }
        }
        when {
            phone.ui.value.member == null -> current.value = Stage.Failed("$appName isn't set up yet. Open $appName and set up your account first.")
            pkg.isEmpty() -> current.value = Stage.Failed("Open this from an app: $appName can't tell who's asking.")
            phone.appGrant(pkg) == null -> current.value = Stage.Connect
            else -> next()
        }
    }

    /** Yes to [Stage.Connect]. The grant is kept on this phone, so the app's request is answered at once. */
    fun connect() {
        phone.connectApp(pkg, label)
        if (type == "get_public_key") return next()
        next()
    }

    /** Yes to [Stage.Dms]. */
    fun allowDms() { phone.allowDms(phone.appGrant(pkg)!!); crypt() }

    /** Yes to [Stage.Approve]: "Let it change my main lists". */
    fun approve() = sign(event!!, approved = true)

    /**
     * "Switch" on [Stage.Approve]: the app gets a key of its own, its access to the main account ends, and its changes
     * to your relay lists are put back. This request is not signed: the app hears no, and signs in again.
     */
    fun switchKey() {
        current.value = Stage.Working("Making $label a key of its own…")
        val app = pkg; val name = label
        work.execute {
            try {
                val back = phone.switchApp(app)
                val restored = if (back > 0) " Your relay lists are being put back as they were before $name changed them." else ""
                activity.runOnUiThread {
                    current.value = Stage.Switched("$name now has a key of its own, and can no longer sign as your main account.$restored\n\n" +
                        "To start using it, sign out in $name and sign in again with $appName.")
                }
            } catch (e: Exception) {
                activity.runOnUiThread { fail("Couldn't move $name to a key of its own: ${e.message ?: "no answer"}") }
            }
        }
    }

    /** Continue on [Stage.Moved]: the app signs in on its own key from now on. */
    fun signInMoved() { phone.signedInAgain(pkg); next() }

    /** With the app connected: answer, or ask you first. */
    private fun next() {
        val g = phone.appGrant(pkg) ?: return run { current.value = Stage.Connect }
        val main = phone.ui.value.member!!.group.groupX
        // Moved to a key of its own and still signed in as the main account: it has to sign in again.
        if (g.place != null && type != "get_public_key" && (asUser == main || eventBy() == main))
            return fail("$label is still signed in as your main account, but it has a key of its own now. Sign out in $label and sign in again with $appName.")
        when (type) {
            "get_public_key" -> phone.moved(pkg)?.let { at ->
                current.value = Stage.Moved("$label gets its own key now, not your main account: you moved it on ${java.text.DateFormat.getDateInstance().format(java.util.Date(at * 1000))} after it tried to change your relays. " +
                    "Messages to your main account won't reach it, so it gets no spam meant for you.")
            } ?: keyThen { answer("result" to it, "package" to activity.packageName) }
            "sign_event" -> {
                val ev = try { phone.appEvent(payload) } catch (e: Exception) { return fail("That isn't an event $appName can sign (${e.message}).") }
                event = ev
                cc.aaro.cosign.core.Policy.refuse(ev)?.let { return fail("$appName won't sign this: $it.") }
                val q = if (g.place != null) cc.aaro.cosign.core.Policy.question(ev, null, child = true) else phone.questionFor(ev)
                if (q != null) current.value = Stage.Approve(q.says, q.level, phone.offersSwitch(g, ev)) else sign(ev, approved = false)
            }
            "nip44_encrypt", "nip44_decrypt", "nip04_encrypt", "nip04_decrypt" ->
                if (!g.dm) current.value = Stage.Dms else crypt()
            else -> fail("$appName doesn't do “$type” yet.")
        }
    }

    /** The pubkey the app put in the event it sent, if any. */
    private fun eventBy(): String? = if (type != "sign_event") null else try { ((cc.aaro.qrst.core.Json.parse(payload) as? Map<*, *>)?.get("pubkey") as? String)?.takeIf { it.length == 64 } } catch (_: Exception) { null }

    /** The app's key (its own child may have to be made, with the box), then [then] with it. */
    private fun keyThen(then: (String) -> Unit) {
        val app = pkg
        work.execute {
            try { val k = phone.appKey(phone.appGrant(app)!!); activity.runOnUiThread { then(k) } }
            catch (e: Exception) { activity.runOnUiThread { fail(e.message ?: "Couldn't find this app's key.") } }
        }
    }

    private fun sign(ev: Event, approved: Boolean) {
        current.value = Stage.Working(if (approved) "Signing…" else "Signing with your box…")
        // What this request is, as it is now: an app may send another before this one is
        // answered (the sheet is singleTop and takes them on the same object), and work
        // already under way must stay the request it started as.
        val app = pkg
        work.execute {
            try {
                val signed = phone.signForApp(phone.appGrant(app)!!, ev, approved)
                activity.runOnUiThread { answer("result" to signed.sig, "event" to signed.toJson()) }
            } catch (e: NeedsApproval) {
                val g = phone.appGrant(app)
                activity.runOnUiThread { current.value = Stage.Approve(e.what, e.level, g != null && phone.offersSwitch(g, ev)) } // your server saw what this phone missed
            } catch (_: Revoked) {
                activity.runOnUiThread { current.value = Stage.Connect }
            } catch (e: Exception) {
                activity.runOnUiThread { fail(e.message ?: "Couldn't sign.") }
            }
        }
    }

    private fun crypt() {
        current.value = Stage.Working("Working with your box…")
        // The request as it is now, for the same reason as in [sign].
        val app = pkg; val how = type; val who = peer; val what = payload
        work.execute {
            try {
                val g = phone.appGrant(app)!!
                val encrypt = how.endsWith("encrypt")
                val out = if (how.startsWith("nip44")) phone.nip44(g, who, what, encrypt) else phone.nip04(g, who, what, encrypt)
                activity.runOnUiThread { answer("result" to out) }
            } catch (e: Exception) {
                // A message that doesn't open: the app hears no, and you aren't shown it (see [unreadable]).
                activity.runOnUiThread { if (!how.endsWith("encrypt") && unreadable(e)) reject() else fail(e.message ?: "Couldn't do that.") }
            }
        }
    }

    /** The site behind a callback URL: https, or http on this machine for a site being built. */
    private fun webOrigin(url: String): String? {
        val u = android.net.Uri.parse(url)
        val host = u.host ?: return null
        if (u.scheme != "https" && !(u.scheme == "http" && host in setOf("localhost", "127.0.0.1"))) return null
        return "${u.scheme}://$host${if (u.port > 0) ":${u.port}" else ""}"
    }

    private fun answer(vararg extras: Pair<String, String>) {
        callback?.let { url ->
            // The page asked for the whole event or just the result; gzip as "Signer1" + base64.
            val map = extras.toMap()
            var value = (if (returnType == "event") map["event"] else null) ?: map["result"] ?: ""
            if (gzip) value = "Signer1" + java.util.Base64.getEncoder().encodeToString(
                java.io.ByteArrayOutputStream().also { b -> java.util.zip.GZIPOutputStream(b).use { it.write(value.toByteArray()) } }.toByteArray())
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url + android.net.Uri.encode(value))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity.finish()
            return
        }
        val data = Intent().putExtra("package", activity.packageName)
        reqId?.let { data.putExtra("id", it) }
        for ((k, v) in extras) data.putExtra(k, v)
        activity.setResult(Activity.RESULT_OK, data)
        activity.finish()
    }

    /** No, or back: the asking app hears it was rejected. */
    fun reject() {
        activity.setResult(Activity.RESULT_OK, Intent().putExtra("rejected", true).apply { reqId?.let { putExtra("id", it) } })
        activity.finish()
    }

    private fun fail(text: String) { current.value = Stage.Failed(text) }
}
